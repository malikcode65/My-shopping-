package com.jarvis.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.jarvis.agent.core.KillSwitch
import com.jarvis.agent.core.MultiPressDetector
import com.jarvis.agent.tools.impl.Apps
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Lets Jarvis see and operate any app: reads the UI tree, taps, long-presses, types, scrolls, swipes,
 * performs back/home/recents, and takes screenshots (Android 11+). Also watches the volume-down key for
 * the triple-press kill switch.
 */
class JarvisAccessibilityService : AccessibilityService() {

    private val volumeDown = MultiPressDetector(presses = 3, windowMs = 1500)
    private val shotExecutor = Executors.newSingleThreadExecutor()

    /** Node handles from the most recent snapshot, by element id. */
    @Volatile private var handles: Map<Int, AccessibilityNodeInfo> = emptyMap()
    @Volatile var foregroundPackage: String = ""
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { if (it != packageName) foregroundPackage = it }
        }
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (volumeDown.onPress(event.eventTime)) KillSwitch.trigger("volume-down x3")
        }
        return false // never swallow keys; volume still works normally
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        shotExecutor.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- reading

    private fun activeRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { return it }
        return windows.firstOrNull { it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root
            ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root
    }

    fun snapshot(maxNodes: Int = 350): ScreenSnapshot {
        val root = activeRoot()
        val (w, h) = screenSize()
        if (root == null) return ScreenSnapshot(foregroundPackage, foregroundPackage, w, h, emptyList())
        val pkg = root.packageName?.toString().orEmpty()
        val out = ArrayList<UiNode>()
        val map = HashMap<Int, AccessibilityNodeInfo>()
        var truncated = false
        val rect = Rect()

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (out.size >= maxNodes) {
                truncated = true
                return
            }
            if (depth > 60 || !node.isVisibleToUser) return
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString() else null
            val interesting = !text.isNullOrBlank() || !desc.isNullOrBlank() || node.isClickable || node.isLongClickable ||
                node.isEditable || node.isScrollable || node.isCheckable
            if (interesting) {
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    val id = out.size + 1
                    out += UiNode(
                        id = id,
                        className = node.className?.toString()?.substringAfterLast('.') ?: "View",
                        text = text,
                        description = desc,
                        viewId = node.viewIdResourceName,
                        hint = hint,
                        left = rect.left, top = rect.top, right = rect.right, bottom = rect.bottom,
                        clickable = node.isClickable,
                        longClickable = node.isLongClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                        checkable = node.isCheckable,
                        checked = node.isChecked,
                        password = node.isPassword,
                        focused = node.isFocused,
                        enabled = node.isEnabled,
                        selected = node.isSelected,
                    )
                    map[id] = node
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        handles = map
        if (pkg.isNotEmpty() && pkg != packageName) foregroundPackage = pkg
        return ScreenSnapshot(pkg, Apps.label(this, pkg), w, h, out, truncated)
    }

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java) ?: return 0 to 0
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    /** The live node for an element id from the last snapshot, or null if it disappeared. */
    fun handle(id: Int): AccessibilityNodeInfo? = handles[id]?.takeIf { it.refresh() }

    fun focusedInput(): AccessibilityNodeInfo? = activeRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    // ---------------------------------------------------------------- acting

    fun click(node: AccessibilityNodeInfo, long: Boolean = false): Boolean {
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 6) {
            if ((if (long) n.isLongClickable else n.isClickable) && n.performAction(action)) return true
            n = n.parent
            depth++
        }
        return false
    }

    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun pressImeEnter(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)

    fun scroll(node: AccessibilityNodeInfo, forward: Boolean): Boolean {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 6) {
            if (n.isScrollable && n.performAction(action)) return true
            n = n.parent
            depth++
        }
        return false
    }

    suspend fun tapAt(x: Int, y: Int, durationMs: Long = 60L): Boolean =
        gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, durationMs)

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 350L): Boolean =
        gesture(Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }, durationMs)

    private suspend fun gesture(path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 5000)))
            .build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)
        if (!ok && cont.isActive) cont.resume(false)
    }

    fun global(action: String): Boolean {
        val code = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else return false
            "power_menu" -> GLOBAL_ACTION_POWER_DIALOG
            else -> return false
        }
        return performGlobalAction(code)
    }

    /** Screenshot of the default display (Android 11+). Null if unsupported or the app blocks capture. */
    suspend fun capture(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(Display.DEFAULT_DISPLAY, shotExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    result.hardwareBuffer.close()
                    if (cont.isActive) cont.resume(soft)
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        }
    }

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set
    }
}
