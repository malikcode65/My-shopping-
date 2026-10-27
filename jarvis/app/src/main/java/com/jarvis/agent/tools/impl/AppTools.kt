package com.jarvis.agent.tools.impl

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.jarvis.agent.brain.str
import com.jarvis.agent.tools.BaseTool
import com.jarvis.agent.tools.RiskAssessment
import com.jarvis.agent.tools.RiskLevel
import com.jarvis.agent.tools.ToolContext
import com.jarvis.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject

data class AppInfo(val label: String, val packageName: String)

object Apps {
    fun launchable(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(intent, 0)
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /** Best match by package name, exact label, prefix, then substring. */
    fun find(context: Context, query: String): AppInfo? {
        val q = query.trim().lowercase()
        val apps = launchable(context)
        return apps.firstOrNull { it.packageName.lowercase() == q }
            ?: apps.firstOrNull { it.label.lowercase() == q }
            ?: apps.firstOrNull { it.label.lowercase().startsWith(q) }
            ?: apps.firstOrNull { it.label.lowercase().contains(q) }
            ?: apps.firstOrNull { q.contains(it.label.lowercase()) && it.label.length >= 3 }
    }

    fun label(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }
}

class ListAppsTool : BaseTool(
    "list_apps", "List installed apps (name and package). Optional filter text.", RiskLevel.LOW, readOnly = true,
    { string("filter", "Optional part of the app name", required = false) },
) {
    override fun describe(input: JsonObject) = "List apps"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val f = input.str("filter")?.lowercase()
        val apps = Apps.launchable(ctx.app).filter { f == null || it.label.lowercase().contains(f) || it.packageName.contains(f) }
        return ToolResult.ok(apps.take(150).joinToString("\n") { "${it.label} (${it.packageName})" }.ifEmpty { "No matching apps" })
    }
}

class LaunchAppTool : BaseTool(
    "launch_app", "Open an installed app by name or package (e.g. 'WhatsApp', 'com.whatsapp').", RiskLevel.LOW,
    schemaBlock = { string("app", "App name or package name") },
) {
    override fun describe(input: JsonObject) = "Open ${input.str("app")}"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val app = Apps.find(ctx.app, input.str("app").orEmpty())
            ?: return ToolResult.error("APP_NOT_FOUND: no installed app matches '${input.str("app")}'. Use list_apps.")
        val intent = ctx.app.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return ToolResult.error("App ${app.label} has no launcher screen")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        ctx.app.startActivity(intent)
        return ToolResult.ok("Opened ${app.label}. Use read_screen to see it.")
    }
}

class OpenUrlTool : BaseTool(
    "open_url",
    "Open a web link or app deep link (https://, geo:, market://, whatsapp://, upi:// etc.) in the matching app.",
    RiskLevel.LOW,
    schemaBlock = { string("url", "The URL or deep link") },
) {
    private val blockedSchemes = setOf("javascript", "file", "content", "intent", "data", "tel", "sms", "smsto", "mailto")
    private val paymentSchemes = setOf("upi", "jazzcash", "easypaisa", "paypal", "venmo", "cashapp")

    override fun describe(input: JsonObject) = "Open link ${input.str("url")}"

    override suspend fun assess(input: JsonObject, ctx: ToolContext): RiskAssessment {
        val scheme = Uri.parse(input.str("url").orEmpty()).scheme?.lowercase()
        return if (scheme in paymentSchemes) {
            RiskAssessment(RiskLevel.HIGH, "Open payment link", input.str("url").orEmpty())
        } else {
            RiskAssessment(RiskLevel.LOW, describe(input))
        }
    }

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val raw = input.str("url").orEmpty().trim()
        val uri = Uri.parse(if (!raw.contains(":")) "https://$raw" else raw)
        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme in blockedSchemes) {
            return ToolResult.error("BLOCKED_SCHEME: use make_call / send_sms / send_email for those, other schemes are not allowed.")
        }
        return try {
            ctx.app.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ToolResult.ok("Opened $uri")
        } catch (e: ActivityNotFoundException) {
            ToolResult.error("No app can open $uri")
        }
    }
}
