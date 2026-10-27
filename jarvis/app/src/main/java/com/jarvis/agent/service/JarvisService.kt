package com.jarvis.agent.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.jarvis.agent.JarvisApp
import com.jarvis.agent.core.AgentPhase
import com.jarvis.agent.core.JarvisRuntime
import com.jarvis.agent.core.KillSwitch
import com.jarvis.agent.core.Notifications
import com.jarvis.agent.core.TaskOrigin
import com.jarvis.agent.overlay.FloatingBubble
import com.jarvis.agent.safety.ConfirmationBroker
import com.jarvis.agent.safety.UserSignals
import com.jarvis.agent.voice.VoiceIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Always-on foreground service (type = microphone). Owns the wake word engine, the floating bubble and
 * the conversation loop: wake word / push-to-talk → listen → agent → speak.
 */
class JarvisService : LifecycleService() {

    private val graph get() = JarvisApp.graph
    private var wakeWord: WakeWordEngine? = null
    private var bubble: FloatingBubble? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var session: Job? = null

    private val killListener: (String) -> Unit = { _ ->
        session?.cancel()
        stopEverything()
    }

    private val micGuard = object : VoiceIO.MicGuard {
        override fun releaseMic() {
            wakeWord?.stop()
        }

        override fun reacquireMic() {
            if (graph.prefs.wakeWordEnabled) wakeWord?.start()
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (!goForeground()) {
            stopSelf()
            return
        }
        instance = this
        KillSwitch.addListener(killListener)
        graph.voice.micGuard = micGuard
        acquireWakeLock()

        wakeWord = WakeWordEngine(
            context = this,
            prefs = graph.prefs,
            secure = graph.secure,
            onWake = { lifecycleScope.launch { startConversation() } },
            onError = { msg -> JarvisRuntime.update { it.copy(wakeWordError = msg) } },
        )
        if (graph.prefs.wakeWordEnabled) {
            if (wakeWord?.start() == true) JarvisRuntime.update { it.copy(wakeWordError = null) }
        }
        if (graph.prefs.overlayEnabled && Settings.canDrawOverlays(this)) {
            bubble = FloatingBubble(
                context = this,
                onTap = { startConversation() },
                onLongPress = { KillSwitch.trigger("bubble long-press") },
            ).also { it.show() }
        }
        JarvisRuntime.update { it.copy(serviceRunning = true, phase = idlePhase()) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (instance !== this) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_PUSH_TO_TALK -> startConversation()
            ACTION_RUN_TEXT -> intent.getStringExtra(EXTRA_TEXT)?.let { runCommand(it, TaskOrigin.TYPED) }
            ACTION_STOP -> stopEverything()
        }
        return START_STICKY
    }

    /** Wake word, bubble tap, notification button or in-app mic button. Interrupts current speech/task. */
    fun startConversation() {
        // "Hey Jarvis" / bubble tap while Jarvis waits for the user to finish a hand-over = continue.
        if (UserSignals.signalContinue()) return
        // Never cancel a task while a confirmation is on screen; the user answers there.
        if (ConfirmationBroker.current.value != null) return
        graph.voice.stopSpeaking()
        session?.cancel()
        session = lifecycleScope.launch {
            val heard = graph.voice.listen()
            if (heard.isNullOrBlank()) {
                JarvisRuntime.phase(idlePhase())
                return@launch
            }
            process(heard, TaskOrigin.VOICE)
        }
    }

    fun runCommand(text: String, origin: TaskOrigin) {
        graph.voice.stopSpeaking()
        session?.cancel()
        session = lifecycleScope.launch { process(text, origin) }
    }

    private suspend fun process(text: String, origin: TaskOrigin) {
        JarvisRuntime.update { it.copy(lastHeard = text, phase = AgentPhase.THINKING, currentStep = 0) }
        try {
            val reply = graph.tasks.track { graph.commandHandler.handle(text, origin) }
            JarvisRuntime.update { it.copy(lastReply = reply) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "Maazrat, kuch masla ho gaya: ${e.message ?: e.javaClass.simpleName}"
            JarvisRuntime.update { it.copy(lastReply = msg) }
            graph.voice.speak(msg)
        } finally {
            JarvisRuntime.update { it.copy(phase = idlePhase(), currentAction = "") }
        }
    }

    private fun idlePhase() = if (wakeWord?.isListening == true) AgentPhase.WAITING_WAKE_WORD else AgentPhase.OFF

    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(
            this, Notifications.ID_SERVICE,
            Notifications.serviceNotification(this, getString(com.jarvis.agent.R.string.notif_listening)),
            type,
        )
        true
    } catch (e: Exception) {
        // Android 14+ forbids starting a microphone service from the background (e.g. at boot).
        Notifications.simpleAlert(
            this, Notifications.ID_BOOT, "Jarvis",
            "Jarvis start karne ke liye yahan tap karein.",
        )
        false
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:always-on").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun stopEverything() {
        stopSelf()
    }

    override fun onDestroy() {
        KillSwitch.removeListener(killListener)
        session?.cancel()
        wakeWord?.release()
        wakeWord = null
        bubble?.hide()
        bubble = null
        if (graph.voice.micGuard === micGuard) graph.voice.micGuard = null
        wakeLock?.let { if (it.isHeld) it.release() }
        if (instance === this) instance = null
        JarvisRuntime.update { it.copy(serviceRunning = false, phase = AgentPhase.OFF) }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.jarvis.agent.START"
        const val ACTION_STOP = "com.jarvis.agent.STOP"
        const val ACTION_PUSH_TO_TALK = "com.jarvis.agent.PTT"
        const val ACTION_RUN_TEXT = "com.jarvis.agent.RUN_TEXT"
        const val EXTRA_TEXT = "text"

        @Volatile
        var instance: JarvisService? = null
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, JarvisService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisService::class.java))
        }

        fun pushToTalk(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, JarvisService::class.java).setAction(ACTION_PUSH_TO_TALK))
        }

        fun runText(context: Context, text: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, JarvisService::class.java).setAction(ACTION_RUN_TEXT).putExtra(EXTRA_TEXT, text),
            )
        }
    }
}
