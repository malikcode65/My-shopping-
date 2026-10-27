package com.jarvis.agent.core

import android.content.Context
import com.jarvis.agent.voice.AndroidSpeechInput
import com.jarvis.agent.voice.AndroidTts
import com.jarvis.agent.voice.ElevenLabsTts
import com.jarvis.agent.voice.SwitchingSpeechOutput
import com.jarvis.agent.voice.VoiceIO
import com.jarvis.agent.voice.WhisperSpeechInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-written dependency graph (no DI framework needed for an app this size). */
class AppGraph(val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = Prefs(context)
    val secure = SecureStore(context)
    val http = Http.newClient()
    val tasks = TaskTracker()

    val voice: VoiceIO by lazy {
        val androidTts = AndroidTts(context)
        VoiceIO(
            prefs = prefs,
            androidStt = AndroidSpeechInput(context),
            whisperStt = WhisperSpeechInput(http, secure),
            tts = SwitchingSpeechOutput(prefs, androidTts, ElevenLabsTts(context, http, secure, prefs, androidTts)),
        )
    }

    /** Milestone 1: echo back what was heard. Replaced by the Claude agent in milestone 2. */
    val commandHandler: CommandHandler by lazy {
        CommandHandler { text, _ ->
            val reply = "Main ne suna: $text"
            voice.speak(reply)
            reply
        }
    }

    init {
        KillSwitch.addListener {
            tasks.cancelAll()
            voice.stopSpeaking()
        }
    }
}
