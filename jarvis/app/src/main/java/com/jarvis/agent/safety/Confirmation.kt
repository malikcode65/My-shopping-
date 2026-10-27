package com.jarvis.agent.safety

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.jarvis.agent.R
import com.jarvis.agent.core.AgentPhase
import com.jarvis.agent.core.JarvisRuntime
import com.jarvis.agent.core.Notifications
import com.jarvis.agent.core.TaskOrigin
import com.jarvis.agent.tools.RiskLevel
import com.jarvis.agent.voice.SpokenLanguage
import com.jarvis.agent.voice.VoiceIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class ConfirmationRequest(
    val id: String = UUID.randomUUID().toString(),
    val level: RiskLevel,
    val toolName: String,
    val summary: String,
    val details: String,
    val origin: TaskOrigin,
    val language: SpokenLanguage,
)

enum class Decision { APPROVED, DENIED, TIMED_OUT }

/** The single pending confirmation, shared between the agent loop, ConfirmActivity and the voice path. */
object ConfirmationBroker {
    private val _current = MutableStateFlow<ConfirmationRequest?>(null)
    val current: StateFlow<ConfirmationRequest?> = _current.asStateFlow()
    private var deferred: CompletableDeferred<Boolean>? = null

    @Synchronized
    fun open(req: ConfirmationRequest): CompletableDeferred<Boolean> {
        deferred?.complete(false)
        return CompletableDeferred<Boolean>().also {
            deferred = it
            _current.value = req
        }
    }

    /** Completes the request with [id]. Returns false if it was already decided. */
    @Synchronized
    fun resolve(id: String, approved: Boolean): Boolean {
        if (_current.value?.id != id) return false
        val d = deferred ?: return false
        _current.value = null
        deferred = null
        return d.complete(approved)
    }

    /** Kill switch: deny everything that is pending. */
    @Synchronized
    fun denyAll() {
        deferred?.complete(false)
        deferred = null
        _current.value = null
    }
}

fun interface Confirmer {
    suspend fun confirm(req: ConfirmationRequest): Decision
}

/**
 * MEDIUM: shows the exact content on screen and asks by voice; a spoken "haan/yes" or the on-screen
 * button approves. HIGH: on-screen only, and the Approve button requires biometric / device credential.
 * No answer within the timeout = denied.
 */
class AndroidConfirmer(
    private val context: Context,
    private val voice: VoiceIO,
    private val timeoutMs: Long = 60_000L,
) : Confirmer {

    override suspend fun confirm(req: ConfirmationRequest): Decision {
        val deferred = ConfirmationBroker.open(req)
        val previousPhase = JarvisRuntime.state.value.phase
        JarvisRuntime.phase(AgentPhase.WAITING_CONFIRMATION)
        showUi(req)
        try {
            val result = withTimeoutOrNull(timeoutMs) {
                coroutineScope {
                    val voiceAnswer = async { if (req.level == RiskLevel.MEDIUM) askByVoice(req) else awaitForever() }
                    val decided = select {
                        deferred.onAwait { it }
                        voiceAnswer.onAwait { it }
                    }
                    voiceAnswer.cancel()
                    decided
                }
            }
            if (result == null) {
                ConfirmationBroker.resolve(req.id, false)
                return Decision.TIMED_OUT
            }
            ConfirmationBroker.resolve(req.id, result)
            return if (result) Decision.APPROVED else Decision.DENIED
        } finally {
            ConfirmationBroker.resolve(req.id, false)
            cancelNotification()
            JarvisRuntime.phase(previousPhase)
        }
    }

    private suspend fun awaitForever(): Boolean = CompletableDeferred<Boolean>().await()

    /** Asks up to twice; returns the spoken decision, or waits for the screen if unclear. */
    private suspend fun askByVoice(req: ConfirmationRequest): Boolean {
        val urdu = req.language != SpokenLanguage.ENGLISH
        val prompt = if (urdu) "${req.summary}. Kya main kar doon? Haan ya nahi?" else "${req.summary}. Should I go ahead? Yes or no?"
        voice.speak(prompt, req.language)
        repeat(2) {
            when (YesNoParser.parse(voice.listen(beep = true))) {
                true -> return true
                false -> return false
                null -> voice.speak(if (urdu) "Sirf haan ya nahi kahein, ya screen par button dabayein." else "Please say yes or no, or use the screen.", req.language)
            }
        }
        return awaitForever()
    }

    private fun showUi(req: ConfirmationRequest) {
        val intent = ConfirmActivity.intent(context, req.id)
        // Starting an activity from the background is allowed because Jarvis holds the overlay permission.
        if (Settings.canDrawOverlays(context)) runCatching { context.startActivity(intent) }
        // Full-screen / heads-up notification as a fallback (and for the lock screen).
        val pi = PendingIntent.getActivity(
            context, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (req.level == RiskLevel.HIGH) "Jarvis: fingerprint se tasdeeq zaroori" else "Jarvis: tasdeeq karein"
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(req.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(req.summary + "\n\n" + req.details))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(timeoutMs)
            .build()
        runCatching {
            context.getSystemService(android.app.NotificationManager::class.java)?.notify(Notifications.ID_CONFIRM, n)
        }
    }

    private fun cancelNotification() {
        runCatching { context.getSystemService(android.app.NotificationManager::class.java)?.cancel(Notifications.ID_CONFIRM) }
    }
}

/**
 * Hand-over to the user (e.g. "please enter your PIN"). The agent waits until the user taps
 * "Continue" in the notification, taps the floating bubble, or says "Hey Jarvis".
 */
object UserSignals {
    private var waiting: CompletableDeferred<Unit>? = null

    val isWaiting: Boolean @Synchronized get() = waiting?.isActive == true

    suspend fun awaitContinue(timeoutMs: Long): Boolean {
        val d = synchronized(this) { CompletableDeferred<Unit>().also { waiting = it } }
        return try {
            withTimeoutOrNull(timeoutMs) { d.await() } != null
        } finally {
            synchronized(this) { if (waiting === d) waiting = null }
        }
    }

    /** Returns true if a hand-over was waiting and is now released. */
    @Synchronized
    fun signalContinue(): Boolean {
        val d = waiting ?: return false
        waiting = null
        return d.complete(Unit)
    }

    fun continueIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 8,
        Intent(context, ContinueReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class ContinueReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        UserSignals.signalContinue()
        runCatching {
            context.getSystemService(android.app.NotificationManager::class.java)?.cancel(Notifications.ID_HANDOFF)
        }
    }
}
