package com.jarvis.agent.ui.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.app.NotificationManager
import android.os.Build
import android.os.Environment
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

internal object NotificationManagerCompatShim {
    fun listenerEnabled(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(':').any { it.startsWith(ctx.packageName + "/") }
    }
}

object PermissionCatalog {

    fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun runtimeItem(
        id: String, title: String, why: String, required: Boolean, perms: List<String>,
    ) = PermissionItem(
        id = id, title = title, why = why, required = required, runtime = perms,
        isGranted = { ctx -> perms.all { granted(ctx, it) } },
    )

    fun isAccessibilityEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.startsWith(ctx.packageName + "/") }
    }

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
                    ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true
                },
            )
        )
        add(
            PermissionItem(
                id = "accessibility", title = "Accessibility (screen control)", required = true,
                why = "Yeh Jarvis ki asal taaqat hai: screen parh kar kisi bhi app mein tap/type/scroll karna, aur " +
                    "volume-down 3 dafa wala kill switch. Settings → Accessibility → Installed apps → \"Jarvis voice agent\" on karein. " +
                    "Android 13+ par agar option grey ho to App info → ⋮ → \"Allow restricted settings\" pehle karein.",
                settingsIntent = { _ -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                isGranted = { ctx -> isAccessibilityEnabled(ctx) },
            )
        )
        add(
            PermissionItem(
                id = "notification_access", title = "Notification access", required = false,
                why = "Aane wale messages/notifications parh kar sunane aur (aap ki tasdeeq ke baad) unka jawab dene ke liye. " +
                    "Notifications ka text sirf data samjha jata hai — us mein likhi hidayat Jarvis kabhi nahi maanta.",
                settingsIntent = { _ -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
                isGranted = { ctx -> NotificationManagerCompatShim.listenerEnabled(ctx) },
            )
        )
        add(
            runtimeItem(
                "photos", "Photos", required = false,
                why = "\"Meri aakhri photo mein kya hai?\" jaise sawal ke liye gallery ki tasveer dekhna.",
                perms = listOf(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
                    else Manifest.permission.READ_EXTERNAL_STORAGE,
                ),
            )
        )
        // Milestone 2: tools
        add(
            runtimeItem(
                "contacts", "Contacts", required = false,
                why = "\"Ammi ko call karo\" jaisi baat samajhne ke liye naam se number dhoondna.",
                perms = listOf(Manifest.permission.READ_CONTACTS),
            )
        )
        add(
            runtimeItem(
                "phone", "Phone calls", required = false,
                why = "Aap ke kehne par call milane ke liye. Har call se pehle Jarvis aap se tasdeeq karega.",
                perms = listOf(Manifest.permission.CALL_PHONE),
            )
        )
        add(
            runtimeItem(
                "sms", "SMS bhejna aur parhna", required = false,
                why = "Messages parh kar sunane aur aap ki tasdeeq ke baad SMS bhejne ke liye. OTP kabhi aage nahi bheje jaate.",
                perms = listOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS),
            )
        )
        add(
            runtimeItem(
                "calendar", "Calendar", required = false,
                why = "Aap ka schedule batane aur naye events banane ke liye.",
                perms = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            )
        )
        add(
            runtimeItem(
                "location", "Location", required = false,
                why = "\"Main kahan hoon?\" ya qareeb ki jagah dhoondne ke liye. Sirf jab aap poochein.",
                perms = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        )
        add(
            PermissionItem(
                id = "write_settings", title = "System settings badalna", required = false,
                why = "Screen ki brightness kam/zyada karne ke liye.",
                settingsIntent = { ctx -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")) },
                isGranted = { ctx -> Settings.System.canWrite(ctx) },
            )
        )
        add(
            PermissionItem(
                id = "dnd", title = "Do Not Disturb control", required = false,
                why = "\"Phone silent kar do\" ya DND on/off karne ke liye.",
                settingsIntent = { _ -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) },
                isGranted = { ctx -> ctx.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true },
            )
        )
        add(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                PermissionItem(
                    id = "files", title = "Files (all files access)", required = false,
                    why = "Downloads/Documents ki files dhoondne, parhne aur (fingerprint ke baad) delete karne ke liye.",
                    settingsIntent = { ctx ->
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    },
                    isGranted = { _ -> Environment.isExternalStorageManager() },
                )
            } else {
                runtimeItem(
                    "files", "Files", required = false,
                    why = "Downloads/Documents ki files dhoondne aur parhne ke liye.",
                    perms = listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                )
            }
        )
    }
}
