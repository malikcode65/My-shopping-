package com.jarvis.agent.tools.impl

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.jarvis.agent.brain.bool
import com.jarvis.agent.brain.int
import com.jarvis.agent.brain.str
import com.jarvis.agent.tools.BaseTool
import com.jarvis.agent.tools.RiskAssessment
import com.jarvis.agent.tools.RiskLevel
import com.jarvis.agent.tools.ToolContext
import com.jarvis.agent.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import kotlin.coroutines.resume

class SetVolumeTool : BaseTool(
    "set_volume", "Set a volume level (percent) or mute/unmute a stream.", RiskLevel.LOW,
    schemaBlock = {
        string("stream", "Which volume", enum = listOf("media", "ring", "alarm", "notification", "call"))
        integer("percent", "0-100", min = 0, max = 100)
    },
) {
    override fun describe(input: JsonObject) = "Set ${input.str("stream")} volume to ${input.int("percent")}%"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val am = ctx.app.getSystemService(AudioManager::class.java) ?: return ToolResult.error("No audio service")
        val stream = when (input.str("stream")) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "call" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val max = am.getStreamMaxVolume(stream)
        val target = ((input.int("percent") ?: 50) * max + 50) / 100
        return try {
            am.setStreamVolume(stream, target.coerceIn(0, max), 0)
            ToolResult.ok("Volume set to ${target * 100 / max.coerceAtLeast(1)}%.")
        } catch (e: SecurityException) {
            ToolResult.error("PERMISSION_MISSING: changing ring volume while Do-Not-Disturb is on needs DND access.")
        }
    }
}

class SetBrightnessTool : BaseTool(
    "set_brightness", "Set screen brightness percent, or automatic brightness.", RiskLevel.LOW,
    schemaBlock = {
        integer("percent", "0-100 (ignored when auto=true)", required = false, min = 0, max = 100)
        boolean("auto", "true = adaptive brightness", required = false)
    },
) {
    override fun describe(input: JsonObject) =
        if (input.bool("auto") == true) "Auto brightness" else "Brightness ${input.int("percent")}%"

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        if (!Settings.System.canWrite(ctx.app)) {
            return ToolResult.error("PERMISSION_MISSING: 'Modify system settings' is not granted for Jarvis.")
        }
        val cr = ctx.app.contentResolver
        if (input.bool("auto") == true) {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            return ToolResult.ok("Automatic brightness on.")
        }
        val percent = input.int("percent") ?: return ToolResult.error("percent required")
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (percent * 255 / 100).coerceIn(1, 255))
        return ToolResult.ok("Brightness set to $percent%.")
    }
}

class SetDndTool : BaseTool(
    "set_do_not_disturb", "Turn Do-Not-Disturb on (priority only / total silence) or off.", RiskLevel.LOW,
    schemaBlock = { string("mode", "off, priority or silent", enum = listOf("off", "priority", "silent")) },
) {
    override fun describe(input: JsonObject) = "Do-Not-Disturb: ${input.str("mode")}"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val nm = ctx.app.getSystemService(NotificationManager::class.java) ?: return ToolResult.error("No notification service")
        if (!nm.isNotificationPolicyAccessGranted) {
            return ToolResult.error("PERMISSION_MISSING: Do-Not-Disturb access is not granted for Jarvis.")
        }
        nm.setInterruptionFilter(
            when (input.str("mode")) {
                "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                "silent" -> NotificationManager.INTERRUPTION_FILTER_NONE
                else -> NotificationManager.INTERRUPTION_FILTER_ALL
            },
        )
        return ToolResult.ok("Do-Not-Disturb ${input.str("mode")}.")
    }
}

class FlashlightTool : BaseTool(
    "flashlight", "Turn the flashlight (torch) on or off.", RiskLevel.LOW,
    schemaBlock = { boolean("on", "true = on") },
) {
    override fun describe(input: JsonObject) = "Flashlight ${if (input.bool("on") == true) "on" else "off"}"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val cm = ctx.app.getSystemService(CameraManager::class.java) ?: return ToolResult.error("No camera service")
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return ToolResult.error("This phone has no flashlight")
        cm.setTorchMode(id, input.bool("on") == true)
        return ToolResult.ok(describe(input))
    }
}

/**
 * Android 10+ no longer lets apps toggle Wi-Fi/Bluetooth/mobile data directly, so this opens the right
 * settings panel; the agent then flips the switch with the screen tools.
 */
class OpenSettingsTool : BaseTool(
    "open_settings",
    "Open a settings page or quick panel. Use for Wi-Fi, Bluetooth, mobile data, hotspot, location, display, " +
        "sound, battery, apps, etc. Then use read_screen + tap to change the switch.",
    RiskLevel.LOW,
    schemaBlock = {
        string(
            "page", "Which page",
            enum = listOf(
                "wifi", "bluetooth", "internet", "mobile_data", "hotspot", "location", "display", "sound", "battery",
                "apps", "date_time", "accessibility", "security", "main",
            ),
        )
    },
) {
    override fun describe(input: JsonObject) = "Open ${input.str("page")} settings"

    override suspend fun assess(input: JsonObject, ctx: ToolContext): RiskAssessment =
        // Opening is harmless; any change on security pages is escalated by the screen-tool risk classifier.
        RiskAssessment(RiskLevel.LOW, describe(input))

    @SuppressLint("MissingPermission")
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val page = input.str("page")
        val panel = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val action = when (page) {
            "wifi" -> if (panel) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            "internet", "mobile_data" -> if (panel) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_DATA_ROAMING_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "hotspot" -> Settings.ACTION_WIRELESS_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "date_time" -> Settings.ACTION_DATE_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "security" -> Settings.ACTION_SECURITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        // Pre-Android 10 Wi-Fi can still be toggled directly.
        if (!panel && page == "wifi") {
            @Suppress("DEPRECATION")
            ctx.app.applicationContext.getSystemService(WifiManager::class.java)?.let { wm ->
                wm.isWifiEnabled = !wm.isWifiEnabled
                return ToolResult.ok("Wi-Fi is now ${if (wm.isWifiEnabled) "on" else "off"}.")
            }
        }
        ctx.app.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult.ok("Opened $page settings. Use read_screen to find and tap the switch, then verify.")
    }
}

class ClipboardReadTool : BaseTool(
    "clipboard_read", "Read the text currently on the clipboard.", RiskLevel.LOW, readOnly = true,
) {
    override fun describe(input: JsonObject) = "Read clipboard"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.Main) {
        val cm = ctx.app.getSystemService(ClipboardManager::class.java)
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx.app)?.toString()
        if (text == null) {
            ToolResult.error("Clipboard empty or not readable in the background (Android 10+ limits this). Use read_screen instead.")
        } else {
            ToolResult.untrusted("clipboard", text)
        }
    }
}

class ClipboardWriteTool : BaseTool(
    "clipboard_write", "Copy text to the clipboard.", RiskLevel.LOW,
    schemaBlock = { string("text", "Text to copy") },
) {
    override fun describe(input: JsonObject) = "Copy text to clipboard"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.Main) {
        val cm = ctx.app.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("Jarvis", input.str("text").orEmpty()))
        ToolResult.ok("Copied.")
    }
}

class GetLocationTool : BaseTool(
    "get_location", "Current location (coordinates and nearest address).", RiskLevel.LOW, readOnly = true,
) {
    override val timeoutMs = 25_000L
    override fun describe(input: JsonObject) = "Get location"

    @SuppressLint("MissingPermission")
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val fine = ContextCompat.checkSelfPermission(ctx.app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx.app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) throw SecurityException("Location permission not granted")
        val lm = ctx.app.getSystemService(LocationManager::class.java) ?: return ToolResult.error("No location service")
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) && fine -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return ToolResult.error("Location is turned off. Use open_settings page=location.")
        }
        val current: Location? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            withTimeoutOrNull(15_000L) {
                suspendCancellableCoroutine { cont ->
                    val signal = android.os.CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(ctx.app)) { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                }
            }
        } else null
        val loc = current ?: lm.getLastKnownLocation(provider) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: return ToolResult.error("Location not available right now.")
        val address = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(ctx.app, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                    ?.getAddressLine(0)
            }.getOrNull()
        }
        return ToolResult.ok(
            "lat=${loc.latitude}, lng=${loc.longitude} (±${loc.accuracy.toInt()}m)" +
                (address?.let { ", address: $it" } ?: "") +
                ", map: https://maps.google.com/?q=${loc.latitude},${loc.longitude}",
        )
    }
}
