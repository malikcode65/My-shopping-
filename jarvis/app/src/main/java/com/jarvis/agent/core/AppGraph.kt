package com.jarvis.agent.core

import android.content.Context
import com.jarvis.agent.brain.AgentLoop
import com.jarvis.agent.brain.ClaudeClient
import com.jarvis.agent.brain.MemoryProvider
import com.jarvis.agent.brain.NoMemory
import com.jarvis.agent.brain.RateLimiter
import com.jarvis.agent.log.ActionLogDatabase
import com.jarvis.agent.log.ActionLogger
import com.jarvis.agent.safety.AndroidConfirmer
import com.jarvis.agent.safety.ConfirmationBroker
import com.jarvis.agent.tools.ToolCatalog
import com.jarvis.agent.tools.ToolRegistry
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

    val actionLog: ActionLogger by lazy { ActionLogger(ActionLogDatabase.create(context).dao(), appScope) }
    val claude: ClaudeClient by lazy { ClaudeClient(http, prefs, secure) }
    val tools: ToolRegistry by lazy { ToolCatalog.build() }
    val confirmer by lazy { AndroidConfirmer(context, voice) }

    /** Long-term memory; replaced by the Room-backed implementation when available. */
    @Volatile var memory: MemoryProvider = NoMemory

    val commandHandler: CommandHandler by lazy {
        AgentLoop(
            graph = this,
            claude = claude,
            registry = tools,
            confirmer = confirmer,
            log = actionLog,
            voice = voice,
            memory = { memory },
            rateLimiter = RateLimiter(maxPerWindow = { prefs.maxRequestsPerMinute }),
        )
    }

    init {
        KillSwitch.addListener { reason ->
            tasks.cancelAll()
            ConfirmationBroker.denyAll()
            voice.stopSpeaking()
            actionLog.log(0L, "KILL", "Kill switch: $reason")
        }
        actionLog.pruneOlderThanDays(30)
    }
}
