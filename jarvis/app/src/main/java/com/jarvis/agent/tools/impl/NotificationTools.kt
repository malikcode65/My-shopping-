package com.jarvis.agent.tools.impl

import android.app.ActivityOptions
import android.os.Build
import com.jarvis.agent.brain.bool
import com.jarvis.agent.brain.int
import com.jarvis.agent.brain.str
import com.jarvis.agent.notifications.JarvisNotificationListener
import com.jarvis.agent.notifications.NotificationStore
import com.jarvis.agent.notifications.StoredNotification
import com.jarvis.agent.safety.SensitiveInputGuard
import com.jarvis.agent.tools.BaseTool
import com.jarvis.agent.tools.RiskAssessment
import com.jarvis.agent.tools.RiskLevel
import com.jarvis.agent.tools.ToolContext
import com.jarvis.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun listenerOff() = ToolResult.error(
    "NOTIFICATION_ACCESS_OFF: tell the user to allow Notification access for Jarvis in Jarvis → Permissions.",
)

private fun lookup(input: JsonObject): StoredNotification? = input.int("id")?.let { NotificationStore.byId(it) }

class ReadNotificationsTool : BaseTool(
    "read_notifications",
    "Read recent notifications (newest first): app, sender/title, text, time, and whether inline reply is possible.",
    RiskLevel.LOW, readOnly = true,
    {
        string("app", "Optional app name filter, e.g. 'WhatsApp'", required = false)
        integer("limit", "Default 10, max 30", required = false, min = 1, max = 30)
    },
) {
    override fun describe(input: JsonObject) = "Read notifications" + (input.str("app")?.let { " from $it" } ?: "")
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        if (JarvisNotificationListener.instance == null) return listenerOff()
        val app = input.str("app")?.lowercase()
        val fmt = SimpleDateFormat("HH:mm", Locale.ENGLISH)
        val list = NotificationStore.list()
            .filter { app == null || it.appLabel.lowercase().contains(app) || it.packageName.contains(app) }
            .take((input.int("limit") ?: 10).coerceIn(1, 30))
        if (list.isEmpty()) return ToolResult.ok("No notifications.")
        return ToolResult.untrusted(
            "notifications",
            list.joinToString("\n") {
                "[id=${it.id}] ${fmt.format(Date(it.postTime))} ${it.appLabel} — ${it.title}: ${it.text}" +
                    if (it.replyAction != null) " (can reply)" else ""
            },
        )
    }
}

class ReplyNotificationTool : BaseTool(
    "reply_notification",
    "Reply to a message notification using its inline reply (WhatsApp, SMS, Telegram…). The user confirms the exact text.",
    RiskLevel.MEDIUM,
    schemaBlock = {
        integer("id", "Notification id from read_notifications", min = 1)
        string("text", "Exact reply text")
    },
) {
    override fun describe(input: JsonObject) = "Reply to notification ${input.int("id")}"

    override suspend fun assess(input: JsonObject, ctx: ToolContext): RiskAssessment {
        val n = lookup(input)
        val who = n?.let { "${it.title} on ${it.appLabel}" } ?: "notification ${input.int("id")}"
        return RiskAssessment(RiskLevel.MEDIUM, "Reply to $who", input.str("text").orEmpty())
    }

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        if (JarvisNotificationListener.instance == null) return listenerOff()
        val n = lookup(input) ?: return ToolResult.error("Notification not found (it may have been dismissed). Read notifications again.")
        val text = input.str("text").orEmpty()
        SensitiveInputGuard.checkOutgoingText(text)?.let { return ToolResult.error("BLOCKED_SECRET: $it") }
        if (n.replyAction == null) return ToolResult.error("This notification has no reply option; open the app instead.")
        return if (JarvisNotificationListener.reply(ctx.app, n, text)) ToolResult.ok("Replied to ${n.title} on ${n.appLabel}.")
        else ToolResult.error("Reply failed.")
    }
}

class OpenNotificationTool : BaseTool(
    "open_notification", "Open the app screen a notification points to.", RiskLevel.LOW,
    schemaBlock = { integer("id", "Notification id from read_notifications", min = 1) },
) {
    override fun describe(input: JsonObject) = "Open notification ${input.int("id")}"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val n = lookup(input) ?: return ToolResult.error("Notification not found")
        val pi = n.contentIntent ?: return ToolResult.error("This notification has nothing to open")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val opts = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            pi.send(ctx.app, 0, null, null, null, null, opts.toBundle())
        } else {
            pi.send()
        }
        return ToolResult.ok("Opened. Use read_screen to see it.")
    }
}

class DismissNotificationTool : BaseTool(
    "dismiss_notification", "Dismiss (clear) a notification, or all notifications from an app.", RiskLevel.LOW,
    schemaBlock = {
        integer("id", "Notification id", required = false, min = 1)
        boolean("all", "Dismiss all notifications", required = false)
    },
) {
    override fun describe(input: JsonObject) = if (input.bool("all") == true) "Clear all notifications" else "Dismiss notification ${input.int("id")}"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val l = JarvisNotificationListener.instance ?: return listenerOff()
        if (input.bool("all") == true) {
            l.cancelAllNotifications()
            return ToolResult.ok("Cleared all notifications.")
        }
        val n = lookup(input) ?: return ToolResult.error("Notification not found")
        l.dismiss(n.key)
        NotificationStore.remove(n.key)
        return ToolResult.ok("Dismissed.")
    }
}
