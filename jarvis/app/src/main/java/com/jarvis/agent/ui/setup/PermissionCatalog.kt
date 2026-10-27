package com.jarvis.agent.ui.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * One entry in the permission-setup flow. Every permission explains, in Roman Urdu, why Jarvis needs it.
 */
data class PermissionItem(
    val id: String,
    val title: String,
    val why: String,
    val required: Boolean,
    /** Runtime permissions requested with the system dialog (already filtered for this Android version). */
    val runtime: List<String> = emptyList(),
    /** Settings screen for "special" permissions that have no dialog. */
    val settingsIntent: ((Context) -> Intent)? = null,
    val isGranted: (Context) -> Boolean,
)

object PermissionCatalog {

    fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun runtimeItem(
        id: String, title: String, why: String, required: Boolean, perms: List<String>,
    ) = PermissionItem(
        id = id, title = title, why = why, required = required, runtime = perms,
        isGranted = { ctx -> perms.all { granted(ctx, it) } },
    )

    fun items(): List<PermissionItem> = buildList {
        add(
            runtimeItem(
                "mic", "Microphone", required = true,
                why = "\"Hey Jarvis\" sunne aur aap ki awaaz ko text mein badalne ke liye. Awaaz sirf command ke waqt " +
                    "recognizer ko jaati hai; wake word phone ke andar hi detect hota hai.",
                perms = listOf(Manifest.permission.RECORD_AUDIO),
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(
                runtimeItem(
                    "notifications", "Notifications dikhana", required = true,
                    why = "Hamesha chalne wali Jarvis notification (STOP button ke saath) aur confirmation alerts ke liye.",
                    perms = listOf(Manifest.permission.POST_NOTIFICATIONS),
                )
            )
        }
        add(
            PermissionItem(
                id = "overlay", title = "Doosri apps ke upar dikhana", required = true,
                why = "Floating mic button ke liye, aur taake Jarvis background se confirmation screen khol sake.",
                settingsIntent = { ctx ->
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                },
                isGranted = { ctx -> Settings.canDrawOverlays(ctx) },
            )
        )
        add(
            PermissionItem(
                id = "battery", title = "Battery optimization se bahar", required = true,
                why = "Warna Android screen off hone par Jarvis ko band kar deta hai aur wake word kaam nahi karta.",
                settingsIntent = { ctx ->
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                },
                isGranted = { ctx ->
                    ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
                },
            )
        )
    }
}
