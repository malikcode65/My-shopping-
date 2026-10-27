package com.jarvis.agent.tools.impl

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.jarvis.agent.R
import com.jarvis.agent.brain.int
import com.jarvis.agent.brain.str
import com.jarvis.agent.core.Notifications
import com.jarvis.agent.core.TaskOrigin
import com.jarvis.agent.safety.UserSignals
import com.jarvis.agent.tools.BaseTool
import com.jarvis.agent.tools.RiskLevel
import com.jarvis.agent.tools.ToolContext
import com.jarvis.agent.tools.ToolResult
import com.jarvis.agent.voice.LanguageDetector
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class GetTimeTool : BaseTool(
    "get_time", "Current date, time, weekday and time zone.", RiskLevel.LOW, readOnly = true,
) {
    override fun describe(input: JsonObject) = "Check the time"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val now = Date()
        val fmt = SimpleDateFormat("EEEE yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        return ToolResult.ok("${fmt.format(now)} ${TimeZone.getDefault().id}")
    }
}

class DeviceStatusTool : BaseTool(
    "device_status",
    "Battery, charging, network, Wi-Fi, Bluetooth, ringer mode, volumes, Do-Not-Disturb, screen on and lock state.",
    RiskLevel.LOW, readOnly = true,
) {
    override fun describe(input: JsonObject) = "Check phone status"

    @SuppressLint("MissingPermission") // Bluetooth state read is best-effort (runCatching)
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val c = ctx.app
        val battery = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            else -> "other"
        }
        val wifiOn = runCatching { c.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled }.getOrNull()
        val btOn = runCatching { c.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled }.getOrNull()
        val am = c.getSystemService(AudioManager::class.java)
        val ringer = when (am?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
        fun vol(stream: Int) = am?.let { "${it.getStreamVolume(stream) * 100 / it.getStreamMaxVolume(stream).coerceAtLeast(1)}%" } ?: "?"
        val dnd = c.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter?.let {
            if (it == NotificationManager.INTERRUPTION_FILTER_ALL) "off" else "on"
        } ?: "?"
        val screenOn = c.getSystemService(PowerManager::class.java)?.isInteractive
        val locked = c.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked
        return ToolResult.ok(
            "battery=${if (level >= 0) level * 100 / scale else -1}% charging=$plugged network=$net wifi_on=$wifiOn " +
                "bluetooth_on=$btOn ringer=$ringer media_volume=${vol(AudioManager.STREAM_MUSIC)} " +
                "ring_volume=${vol(AudioManager.STREAM_RING)} dnd=$dnd screen_on=$screenOn locked=$locked",
        )
    }
}

class WaitTool : BaseTool(
    "wait", "Wait a few seconds for an app or page to finish loading, then observe again.", RiskLevel.LOW, readOnly = true,
    { integer("seconds", "1 to 10", min = 1, max = 10) },
) {
    override fun describe(input: JsonObject) = "Wait ${input.int("seconds")}s"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        delay((input.int("seconds") ?: 2).coerceIn(1, 10) * 1000L)
        return ToolResult.ok("waited")
    }
}

class AskUserTool : BaseTool(
    "ask_user",
    "Ask the user a short spoken question (in their language) and get their spoken answer. Use for ambiguity.",
    RiskLevel.LOW, readOnly = true,
    { string("question", "One short question in the user's language") },
) {
    override val timeoutMs = 60_000L
    override fun describe(input: JsonObject) = "Ask: ${input.str("question")}"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val q = input.str("question").orEmpty()
        val voice = ctx.graph.voice
        repeat(2) {
            voice.speak(q, LanguageDetector.detect(q))
            val answer = voice.listen()
            if (!answer.isNullOrBlank()) return ToolResult.ok("User answered: $answer")
        }
        return ToolResult.error(
            if (ctx.origin == TaskOrigin.ROUTINE) "NO_ANSWER: the user is not responding (routine)." else "NO_ANSWER: the user did not answer.",
        )
    }
}

class HandOffTool : BaseTool(
    "hand_off_to_user",
    "Ask the user to do a step themselves — entering a password, PIN, OTP or card number, unlocking the " +
        "phone, solving a captcha — and wait until they say they are done. Jarvis never types secrets.",
    RiskLevel.LOW, readOnly = true,
    { string("instruction", "What the user must do, in their language, e.g. 'Apna PIN daalein'") },
) {
    override val timeoutMs = 190_000L
    override fun describe(input: JsonObject) = "Hand over to user: ${input.str("instruction")}"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val instruction = input.str("instruction").orEmpty()
        val urdu = LanguageDetector.detect(instruction) != com.jarvis.agent.voice.SpokenLanguage.ENGLISH
        val tail = if (urdu) " Ho jaye to 'Hey Jarvis' kahein ya notification mein Continue dabayein." else
            " When you're done, say 'Hey Jarvis' or tap Continue in the notification."
        postNotification(ctx.app, instruction)
        ctx.graph.voice.speak(instruction + tail)
        val done = UserSignals.awaitContinue(180_000L)
        runCatching { ctx.app.getSystemService(NotificationManager::class.java)?.cancel(Notifications.ID_HANDOFF) }
        return if (done) ToolResult.ok("The user says they finished. Observe the screen before continuing.")
        else ToolResult.error("HANDOFF_TIMEOUT: the user did not continue within 3 minutes.")
    }

    private fun postNotification(c: Context, text: String) {
        val n = NotificationCompat.Builder(c, Notifications.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Jarvis aap ka intezar kar raha hai")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(R.drawable.ic_mic, "Continue", UserSignals.continueIntent(c))
            .setOngoing(true)
            .build()
        runCatching { c.getSystemService(NotificationManager::class.java)?.notify(Notifications.ID_HANDOFF, n) }
    }
}
