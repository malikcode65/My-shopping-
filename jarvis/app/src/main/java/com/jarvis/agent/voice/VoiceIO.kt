package com.jarvis.agent.voice

import android.media.AudioManager
import android.media.ToneGenerator
import com.jarvis.agent.core.AgentPhase
import com.jarvis.agent.core.JarvisRuntime
import com.jarvis.agent.core.ListenLanguage
import com.jarvis.agent.core.Prefs
import com.jarvis.agent.core.SttEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single entry point for listening and speaking. Speech is serialised (one sentence at a time) and
 * always interruptible via [stopSpeaking].
 */
class VoiceIO(
    private val prefs: Prefs,
    private val androidStt: SpeechInput,
    private val whisperStt: SpeechInput,
    private val tts: SpeechOutput,
) {
    private val speakLock = Mutex()

    /** Hook used by the service to release the microphone (wake word) while recognising speech. */
    @Volatile var micGuard: MicGuard? = null

    @Volatile var isSpeaking: Boolean = false
        private set

    suspend fun listen(language: ListenLanguage = prefs.listenLanguage, beep: Boolean = true): String? {
        val guard = micGuard
        guard?.releaseMic()
        try {
            JarvisRuntime.phase(AgentPhase.LISTENING)
            if (beep) beep()
            val text = when (prefs.sttEngine) {
                SttEngine.WHISPER -> whisperStt.listen(language) ?: androidStt.listen(language)
                SttEngine.ANDROID -> androidStt.listen(language)
            }
            return text?.trim()?.ifEmpty { null }
        } finally {
            guard?.reacquireMic()
        }
    }

    suspend fun speak(text: String, language: SpokenLanguage = LanguageDetector.detect(text)) {
        if (text.isBlank()) return
        speakLock.withLock {
            val previous = JarvisRuntime.state.value.phase
            isSpeaking = true
            JarvisRuntime.phase(AgentPhase.SPEAKING)
            try {
                tts.speak(text, language)
            } finally {
                isSpeaking = false
                JarvisRuntime.phase(previous)
            }
        }
    }

    fun stopSpeaking() {
        tts.stop()
    }

    private fun beep() {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            Thread.sleep(140)
            tg.release()
        }
    }

    interface MicGuard {
        fun releaseMic()
        fun reacquireMic()
    }
}
