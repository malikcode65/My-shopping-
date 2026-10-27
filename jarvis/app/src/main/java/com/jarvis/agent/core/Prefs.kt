package com.jarvis.agent.core

import android.content.Context
import android.content.SharedPreferences

enum class SttEngine { ANDROID, WHISPER }
enum class TtsEngine { ANDROID, ELEVENLABS }

/** User preference for the language Jarvis listens in. AUTO lets the recognizer detect Urdu/English. */
enum class ListenLanguage(val tag: String) { AUTO("ur-PK"), URDU("ur-PK"), ENGLISH("en-US") }

/**
 * Non-secret settings. Secrets (API keys) live in [SecureStore], never here.
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

    var wakeWordEnabled: Boolean
        get() = sp.getBoolean("wake_word", true)
        set(v) = sp.edit().putBoolean("wake_word", v).apply()

    var wakeSensitivity: Float
        get() = sp.getFloat("wake_sensitivity", 0.6f)
        set(v) = sp.edit().putFloat("wake_sensitivity", v.coerceIn(0f, 1f)).apply()

    var overlayEnabled: Boolean
        get() = sp.getBoolean("overlay", true)
        set(v) = sp.edit().putBoolean("overlay", v).apply()

    var startOnBoot: Boolean
        get() = sp.getBoolean("start_on_boot", true)
        set(v) = sp.edit().putBoolean("start_on_boot", v).apply()

    var sttEngine: SttEngine
        get() = enumOr(sp.getString("stt", null), SttEngine.ANDROID)
        set(v) = sp.edit().putString("stt", v.name).apply()

    var ttsEngine: TtsEngine
        get() = enumOr(sp.getString("tts", null), TtsEngine.ANDROID)
        set(v) = sp.edit().putString("tts", v.name).apply()

    var listenLanguage: ListenLanguage
        get() = enumOr(sp.getString("listen_lang", null), ListenLanguage.AUTO)
        set(v) = sp.edit().putString("listen_lang", v.name).apply()

    var elevenLabsVoiceId: String
        get() = sp.getString("eleven_voice", null) ?: "21m00Tcm4TlvDq8ikWAM"
        set(v) = sp.edit().putString("eleven_voice", v.trim()).apply()

    /** Base URL of the Claude API, or of the user's own proxy server. */
    var apiBaseUrl: String
        get() = sp.getString("api_base", null) ?: DEFAULT_API_BASE
        set(v) = sp.edit().putString("api_base", v.trim().trimEnd('/').ifEmpty { DEFAULT_API_BASE }).apply()

    var model: String
        get() = sp.getString("model", null) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v.trim().ifEmpty { DEFAULT_MODEL }).apply()

    /** Effort for the agent loop. `between_tools` thinking accepts `high` or below. */
    var effort: String
        get() = sp.getString("effort", null) ?: "medium"
        set(v) = sp.edit().putString("effort", v).apply()

    var maxSteps: Int
        get() = sp.getInt("max_steps", 25)
        set(v) = sp.edit().putInt("max_steps", v.coerceIn(1, 25)).apply()

    var maxRequestsPerMinute: Int
        get() = sp.getInt("rpm", 20)
        set(v) = sp.edit().putInt("rpm", v.coerceIn(1, 60)).apply()

    var taskTimeoutSec: Int
        get() = sp.getInt("task_timeout", 300)
        set(v) = sp.edit().putInt("task_timeout", v.coerceIn(30, 900)).apply()

    var setupDone: Boolean
        get() = sp.getBoolean("setup_done", false)
        set(v) = sp.edit().putBoolean("setup_done", v).apply()

    val usesDirectApi: Boolean get() = apiBaseUrl == DEFAULT_API_BASE

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default

    companion object {
        const val DEFAULT_API_BASE = "https://api.anthropic.com"
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
    }
}
