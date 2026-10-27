package com.jarvis.agent.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import com.jarvis.agent.R
import kotlin.math.abs

/**
 * Draggable floating mic button drawn over other apps.
 * Tap = talk to Jarvis, long-press = kill switch.
 */
class FloatingBubble(
    private val context: Context,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: ImageView? = null

    private val params = WindowManager.LayoutParams(
        dp(56), dp(56),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(8)
        y = dp(240)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (view != null) return
        val v = ImageView(context).apply {
            setImageResource(R.drawable.ic_mic)
            setBackgroundResource(R.drawable.bubble_bg)
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
            contentDescription = "Jarvis"
        }
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var downAt = 0L
        var dragging = false
        var longPressed = false
        val longPressRunnable = Runnable {
            if (!dragging) {
                longPressed = true
                onLongPress()
            }
        }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    downAt = System.currentTimeMillis()
                    dragging = false; longPressed = false
                    view.postDelayed(longPressRunnable, longPressMs)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        view.removeCallbacks(longPressRunnable)
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPressRunnable)
                    val quick = System.currentTimeMillis() - downAt < longPressMs
                    if (e.actionMasked == MotionEvent.ACTION_UP && !dragging && !longPressed && quick) onTap()
                }
            }
            true
        }
        runCatching { wm.addView(v, params) }.onSuccess { view = v }
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
