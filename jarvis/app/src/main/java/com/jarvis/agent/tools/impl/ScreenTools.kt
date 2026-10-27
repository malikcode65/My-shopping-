package com.jarvis.agent.tools.impl

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import com.jarvis.agent.accessibility.JarvisAccessibilityService
import com.jarvis.agent.accessibility.ScreenSnapshot
import com.jarvis.agent.accessibility.UiNode
import com.jarvis.agent.brain.bool
import com.jarvis.agent.brain.int
import com.jarvis.agent.brain.str
import com.jarvis.agent.safety.SensitiveInputGuard
import com.jarvis.agent.safety.UiRiskClassifier
import com.jarvis.agent.tools.BaseTool
import com.jarvis.agent.tools.RiskAssessment
import com.jarvis.agent.tools.RiskLevel
import com.jarvis.agent.tools.SchemaBuilder
import com.jarvis.agent.tools.ToolContext
import com.jarvis.agent.tools.ToolResult
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/** Shared plumbing for tools that drive the UI through the accessibility service. */
internal object Ui {
    const val SETTLE_MS = 800L

    fun service(): JarvisAccessibilityService? = JarvisAccessibilityService.instance

    fun unavailable() = ToolResult.error(
        "ACCESSIBILITY_OFF: Jarvis accessibility service is not enabled. Tell the user to enable it in " +
            "Jarvis → Permissions → Accessibility.",
    )

    /** Wakes the screen if needed; returns an error result when the phone is locked. */
    @Suppress("DEPRECATION")
    fun ensureUsable(context: Context): ToolResult? {
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isInteractive) {
            runCatching {
                pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "jarvis:wake",
                ).acquire(10_000L)
            }
        }
        val km = context.getSystemService(KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) {
            return ToolResult.error("PHONE_LOCKED: use hand_off_to_user and ask the user to unlock the phone, then continue.")
        }
        return null
    }

    /** Snapshot after an action has had time to settle, returned as untrusted screen data. */
    suspend fun observe(service: JarvisAccessibilityService, what: String): ToolResult {
        delay(SETTLE_MS)
        val snap = service.snapshot()
        return ToolResult.untrusted("screen", "$what\nScreen now:\n${snap.render()}")
    }

    /** Element addressing shared by tap/long_press/type: element id, visible text, or coordinates. */
    fun SchemaBuilder.target() {
        integer("element", "Element id from the latest read_screen", required = false, min = 1)
        string("text", "Or: visible text/label of the element", required = false)
        integer("x", "Or: x coordinate", required = false, min = 0)
        integer("y", "Or: y coordinate", required = false, min = 0)
    }

    data class Target(val node: UiNode?, val x: Int?, val y: Int?, val snapshot: ScreenSnapshot)

    fun resolve(service: JarvisAccessibilityService, input: JsonObject): Target? {
        val snap = service.snapshot()
        val id = input.int("element")
        val node = when {
            id != null -> snap.node(id)
            input.str("text") != null -> snap.findByText(input.str("text")!!)
            else -> null
        }
        val x = input.int("x")
        val y = input.int("y")
        if (node == null && (x == null || y == null)) return null
        return Target(node, x, y, snap)
    }

    fun label(t: Target): String = t.node?.let { "[${it.id}] '${it.label}'" } ?: "point (${t.x},${t.y})"

    /**
     * The element the user approved must still be the element we tap: if the screen changed during the
     * confirmation (new message, popup), refuse instead of tapping something else.
     */
    fun sameTarget(approved: Target, now: Target): Boolean {
        val a = approved.node
        val b = now.node
        if (a == null || b == null) return a == null && b == null && approved.x == now.x && approved.y == now.y
        return a.label == b.label && kotlin.math.abs(a.centerX - b.centerX) < 48 && kotlin.math.abs(a.centerY - b.centerY) < 48 &&
            approved.snapshot.packageName == now.snapshot.packageName
    }
}

/** Remembers what was assessed (and possibly confirmed) for one tool call, keyed by the input object. */
internal class AssessedTargets {
    @Volatile private var last: Pair<JsonObject, Ui.Target>? = null
    fun put(input: JsonObject, t: Ui.Target) { last = input to t }
    fun take(input: JsonObject): Ui.Target? = last?.takeIf { it.first === input }?.second.also { last = null }
}

class ReadScreenTool : BaseTool(
    "read_screen",
    "Read the current screen as a list of elements with ids, text, type, state and position. Call this " +
        "before tapping and after actions to verify. Element ids change after every read.",
    RiskLevel.LOW, readOnly = true,
) {
    override fun describe(input: JsonObject) = "Read the screen"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val s = Ui.service() ?: return Ui.unavailable()
        Ui.ensureUsable(ctx.app)?.let { return it }
        return ToolResult.untrusted("screen", s.snapshot().render())
    }
}

class TapTool : BaseTool(
    "tap",
    "Tap an element (by element id from read_screen, by its visible text, or by x/y). Taps that pay, buy, " +
        "delete, send, post, call or install are confirmed with the user automatically. Returns the new screen.",
    RiskLevel.LOW,
    schemaBlock = { with(Ui) { target() } },
) {
    override fun describe(input: JsonObject) = "Tap ${input.int("element") ?: input.str("text") ?: "${input.int("x")},${input.int("y")}"}"

    private val assessed = AssessedTargets()

    override suspend fun assess(input: JsonObject, ctx: ToolContext): RiskAssessment {
        val s = Ui.service() ?: return RiskAssessment(RiskLevel.LOW, describe(input))
        val t = Ui.resolve(s, input) ?: return RiskAssessment(RiskLevel.LOW, describe(input))
        assessed.put(input, t)
        return assessTap(t, "Tap")
    }

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val s = Ui.service() ?: return Ui.unavailable()
        Ui.ensureUsable(ctx.app)?.let { return it }
        val approved = assessed.take(input)
        val t = Ui.resolve(s, input)
            ?: return ToolResult.error("ELEMENT_NOT_FOUND: call read_screen and use a current element id.")
        if (approved != null && !Ui.sameTarget(approved, t)) {
            return ToolResult.error("SCREEN_CHANGED: the screen changed since this tap was checked. Call read_screen and try again.")
        }
        val ok = t.node?.let { n -> s.handle(n.id)?.let { s.click(it) } == true || s.tapAt(n.centerX, n.centerY) }
            ?: s.tapAt(t.x!!, t.y!!)
        if (!ok) return ToolResult.error("Tap failed on ${Ui.label(t)}")
        return Ui.observe(s, "Tapped ${Ui.label(t)}.")
    }
}

/** Shared by tap and long_press. Adds the text in input boxes so "Send" confirmations show the message. */
internal fun assessTap(t: Ui.Target, verb: String): RiskAssessment {
    val label = t.node?.label ?: "(${t.x},${t.y})"
    val verdict = UiRiskClassifier.classifyTap(label, t.snapshot.packageName, t.snapshot.allText())
    val typed = t.snapshot.editableTexts()
    val details = buildString {
        append("App: ").append(t.snapshot.appLabel)
        if (verdict.reason.isNotEmpty()) append("\nWhy confirm: ").append(verdict.reason)
        if (typed.isNotEmpty()) append("\nText on screen to be sent:\n").append(typed.joinToString("\n"))
    }
    return RiskAssessment(verdict.level, "$verb '$label' in ${t.snapshot.appLabel}", details)
}

class LongPressTool : BaseTool(
    "long_press", "Long-press an element (by id, text or x/y), e.g. to select a message or open a context menu.",
    RiskLevel.LOW,
    schemaBlock = { with(Ui) { target() } },
) {
    override fun describe(input: JsonObject) = "Long-press ${input.int("element") ?: input.str("text")}"

    private val assessed = AssessedTargets()

    override suspend fun assess(input: JsonObject, ctx: ToolContext): RiskAssessment {
        val s = Ui.service() ?: return RiskAssessment(RiskLevel.LOW, describe(input))
        val t = Ui.resolve(s, input) ?: return RiskAssessment(RiskLevel.LOW, describe(input))
        assessed.put(input, t)
        return assessTap(t, "Long-press")
    }

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val s = Ui.service() ?: return Ui.unavailable()
        Ui.ensureUsable(ctx.app)?.let { return it }
        val approved = assessed.take(input)
        val t = Ui.resolve(s, input) ?: return ToolResult.error("ELEMENT_NOT_FOUND: call read_screen first.")
        if (approved != null && !Ui.sameTarget(approved, t)) {
            return ToolResult.error("SCREEN_CHANGED: the screen changed since this action was checked. Call read_screen and try again.")
        }
        val ok = t.node?.let { n -> s.handle(n.id)?.let { s.click(it, long = true) } == true || s.tapAt(n.centerX, n.centerY, 800L) }
            ?: s.tapAt(t.x!!, t.y!!, 800L)
        if (!ok) return ToolResult.error("Long-press failed")
        return Ui.observe(s, "Long-pressed ${Ui.label(t)}.")
    }
}

class TypeTextTool : BaseTool(
    "type_text",
    "Type text into an input field (by element id or visible text; default: the focused field). Replaces the " +
        "field's content unless append=true. Refuses password, PIN, OTP and card fields — use hand_off_to_user.",
    RiskLevel.LOW,
    schemaBlock = {
        integer("element", "Element id of the input field", required = false, min = 1)
        string("field_text", "Or: visible text/hint of the field", required = false)
        string("text", "The text to type")
        boolean("append", "Append instead of replacing", required = false)
        boolean("submit", "Press the keyboard's enter/search key afterwards", required = false)
    },
) {
    override fun describe(input: JsonObject) = "Type \"${input.str("text")?.take(60)}\""

    override suspend fun assess(input: JsonObject, ctx: ToolContext): RiskAssessment {
        val s = Ui.service() ?: return RiskAssessment(RiskLevel.LOW, describe(input))
        val snap = s.snapshot()
        val v = UiRiskClassifier.classifyTyping(snap.packageName, snap.allText())
        // Submitting a search/chat with enter can send it: treat like tapping "send".
        val level = if (input.bool("submit") == true && v.level == RiskLevel.LOW && snap.packageName.isMessagingApp()) RiskLevel.MEDIUM else v.level
        return RiskAssessment(level, "Type into ${snap.appLabel}" + if (input.bool("submit") == true) " and press enter" else "", input.str("text").orEmpty())
    }

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val s = Ui.service() ?: return Ui.unavailable()
        Ui.ensureUsable(ctx.app)?.let { return it }
        val snap = s.snapshot()
        val uiNode: UiNode? = input.int("element")?.let { snap.node(it) }
            ?: input.str("field_text")?.let { snap.findByText(it) }
            ?: snap.nodes.firstOrNull { it.editable && it.focused }
            ?: snap.nodes.firstOrNull { it.editable }
        val handle = uiNode?.let { s.handle(it.id) } ?: s.focusedInput()
            ?: return ToolResult.error("NO_INPUT_FIELD: tap the input field first.")
        val field = SensitiveInputGuard.FieldInfo(
            isPassword = handle.isPassword,
            hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) handle.hintText?.toString() else null,
            label = listOfNotNull(handle.contentDescription?.toString(), handle.labeledBy?.text?.toString(), uiNode?.hint)
                .joinToString(" "),
            viewId = handle.viewIdResourceName,
            packageName = snap.packageName,
        )
        val text = input.str("text").orEmpty()
        SensitiveInputGuard.checkTyping(text, field)?.let {
            return ToolResult.error("BLOCKED_SECRET_FIELD: $it Jarvis never types secrets. Use hand_off_to_user.")
        }
        val newText = if (input.bool("append") == true) (handle.text?.toString().orEmpty() + text) else text
        if (!s.setText(handle, newText)) return ToolResult.error("Could not type into this field.")
        if (input.bool("submit") == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) s.pressImeEnter(handle)
        return Ui.observe(s, "Typed text.")
    }

    private fun String.isMessagingApp() = listOf("whatsapp", "messag", "telegram", "signal", "instagram", "facebook.orca", "gm", "twitter", "snapchat")
        .any { contains(it) }
}

class ScrollTool : BaseTool(
    "scroll", "Scroll the screen or a scrollable element in a direction. Returns the new screen.", RiskLevel.LOW,
    schemaBlock = {
        string("direction", "Direction to move the content", enum = listOf("down", "up", "left", "right"))
        integer("element", "Optional scrollable element id", required = false, min = 1)
    },
) {
    override fun describe(input: JsonObject) = "Scroll ${input.str("direction")}"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val s = Ui.service() ?: return Ui.unavailable()
        Ui.ensureUsable(ctx.app)?.let { return it }
        val dir = input.str("direction") ?: "down"
        val forward = dir == "down" || dir == "right"
        val snap = s.snapshot()
        val target = input.int("element")?.let { s.handle(it) }
            ?: snap.nodes.filter { it.scrollable }.maxByOrNull { (it.right - it.left) * (it.bottom - it.top) }?.let { s.handle(it.id) }
        val ok = target?.let { s.scroll(it, forward) } == true || run {
            val w = snap.width.coerceAtLeast(1)
            val h = snap.height.coerceAtLeast(1)
            when (dir) {
                "down" -> s.swipe(w / 2, h * 3 / 4, w / 2, h / 4)
                "up" -> s.swipe(w / 2, h / 4, w / 2, h * 3 / 4)
                "right" -> s.swipe(w * 3 / 4, h / 2, w / 4, h / 2)
                else -> s.swipe(w / 4, h / 2, w * 3 / 4, h / 2)
            }
        }
        if (!ok) return ToolResult.error("Could not scroll")
        return Ui.observe(s, "Scrolled $dir.")
    }
}

class SwipeTool : BaseTool(
    "swipe", "Swipe from one point to another (for carousels, sliders, pull-to-refresh).", RiskLevel.LOW,
    schemaBlock = {
        integer("x1", "start x", min = 0); integer("y1", "start y", min = 0)
        integer("x2", "end x", min = 0); integer("y2", "end y", min = 0)
        integer("duration_ms", "Default 350", required = false, min = 50, max = 3000)
    },
) {
    override fun describe(input: JsonObject) = "Swipe"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val s = Ui.service() ?: return Ui.unavailable()
        Ui.ensureUsable(ctx.app)?.let { return it }
        val ok = s.swipe(
            input.int("x1")!!, input.int("y1")!!, input.int("x2")!!, input.int("y2")!!,
            (input.int("duration_ms") ?: 350).toLong(),
        )
        if (!ok) return ToolResult.error("Swipe failed")
        return Ui.observe(s, "Swiped.")
    }
}

class PressKeyTool : BaseTool(
    "press_key", "System navigation: back, home, recents, open notifications or quick settings, lock screen.",
    RiskLevel.LOW,
    schemaBlock = {
        string("key", "Which", enum = listOf("back", "home", "recents", "notifications", "quick_settings", "lock_screen"))
    },
) {
    override fun describe(input: JsonObject) = "Press ${input.str("key")}"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val s = Ui.service() ?: return Ui.unavailable()
        val key = input.str("key").orEmpty()
        if (key != "lock_screen") Ui.ensureUsable(ctx.app)?.let { return it }
        if (!s.global(key)) return ToolResult.error("Could not press $key")
        if (key == "lock_screen") return ToolResult.ok("Screen locked.")
        return Ui.observe(s, "Pressed $key.")
    }
}

/** Composes in Gmail via intent, then presses Send through accessibility after the user confirmed. */
class SendEmailTool : BaseTool(
    "send_email", "Send an email with Gmail. The user sees the exact email and confirms before it is sent.",
    RiskLevel.MEDIUM,
    schemaBlock = {
        string("to", "Recipient email address(es), comma separated")
        string("subject", "Subject")
        string("body", "Exact body text")
        string("cc", "Optional CC addresses", required = false)
    },
) {
    override val timeoutMs = 45_000L
    override fun describe(input: JsonObject) = "Email ${input.str("to")}: ${input.str("subject")}"

    override suspend fun assess(input: JsonObject, ctx: ToolContext) = RiskAssessment(
        RiskLevel.MEDIUM, "Send email to ${input.str("to")}",
        "Subject: ${input.str("subject")}\n" + (input.str("cc")?.let { "CC: $it\n" } ?: "") + "\n${input.str("body")}",
    )

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val body = input.str("body").orEmpty()
        SensitiveInputGuard.checkOutgoingText(body + " " + input.str("subject").orEmpty())?.let {
            return ToolResult.error("BLOCKED_SECRET: $it")
        }
        val to = input.str("to").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, to)
            .putExtra(Intent.EXTRA_SUBJECT, input.str("subject").orEmpty())
            .putExtra(Intent.EXTRA_TEXT, body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        input.str("cc")?.let { cc -> intent.putExtra(Intent.EXTRA_CC, cc.split(',').map { it.trim() }.toTypedArray()) }
        val gmail = "com.google.android.gm"
        val hasGmail = runCatching { ctx.app.packageManager.getPackageInfo(gmail, 0) }.isSuccess
        if (hasGmail) intent.setPackage(gmail)
        try {
            ctx.app.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            return ToolResult.error("No email app installed")
        }
        val s = Ui.service() ?: return ToolResult.ok("Email composed but accessibility is off, so ask the user to press Send.")
        Ui.ensureUsable(ctx.app)?.let { return it }
        repeat(20) {
            delay(400)
            val snap = s.snapshot()
            val send = snap.nodes.firstOrNull { n ->
                n.viewId?.endsWith(":id/send") == true || n.description.equals("Send", ignoreCase = true)
            }
            if (send != null) {
                val ok = s.handle(send.id)?.let { s.click(it) } == true || s.tapAt(send.centerX, send.centerY)
                if (!ok) return ToolResult.error("Could not press Send; ask the user to press it.")
                delay(1500)
                val after = s.snapshot()
                val stillComposing = after.nodes.any { it.viewId?.endsWith(":id/send") == true }
                return if (stillComposing) ToolResult.untrusted("screen", "Pressed Send but compose is still open:\n${after.render()}")
                else ToolResult.ok("Email sent to ${to.joinToString()}.")
            }
        }
        return ToolResult.error("Compose screen opened but the Send button was not found. Use read_screen.")
    }
}
