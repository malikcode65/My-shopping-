package com.jarvis.agent.service

import android.content.Context
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import com.jarvis.agent.core.Prefs
import com.jarvis.agent.core.SecureStore
import java.io.File

/**
 * "Hey Jarvis" detection with Picovoice Porcupine. Runs fully on-device, including with the screen off
 * (the foreground service holds a partial wake lock).
 *
 * If `assets/hey-jarvis_android.ppn` exists (trained for free in the Picovoice Console) it is used;
 * otherwise the built-in "Jarvis" keyword is used, which also fires on "Hey Jarvis".
 */
class WakeWordEngine(
    private val context: Context,
    private val prefs: Prefs,
    private val secure: SecureStore,
    private val onWake: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private var manager: PorcupineManager? = null
    @Volatile private var listening = false

    val isListening: Boolean get() = listening

    fun start(): Boolean {
        if (listening) return true
        val m = manager ?: build() ?: return false
        return try {
            m.start()
            listening = true
            true
        } catch (e: PorcupineException) {
            onError("Wake word start failed: ${e.message}")
            false
        }
    }

    fun stop() {
        if (!listening) return
        listening = false
        try {
            manager?.stop()
        } catch (_: PorcupineException) {
        }
    }

    fun release() {
        stop()
        manager?.delete()
        manager = null
    }

    private fun build(): PorcupineManager? {
        val key = secure.get(SecureStore.Secret.PICOVOICE_ACCESS_KEY)
        if (key.isNullOrBlank()) {
            onError("Picovoice AccessKey missing (Settings → Keys). Push-to-talk still works.")
            return null
        }
        return try {
            val builder = PorcupineManager.Builder()
                .setAccessKey(key)
                .setSensitivity(prefs.wakeSensitivity)
                .setErrorCallback { e -> onError("Wake word error: ${e.message}") }
            val custom = customKeywordFile()
            if (custom != null) builder.setKeywordPath(custom.absolutePath)
            else builder.setKeyword(Porcupine.BuiltInKeyword.JARVIS)
            builder.build(context) { _ -> onWake() }.also { manager = it }
        } catch (e: PorcupineException) {
            onError("Wake word init failed: ${e.message}")
            null
        }
    }

    private fun customKeywordFile(): File? = try {
        val out = File(context.filesDir, CUSTOM_KEYWORD_ASSET)
        if (!out.exists()) {
            context.assets.open(CUSTOM_KEYWORD_ASSET).use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        out
    } catch (_: Exception) {
        null
    }

    companion object {
        const val CUSTOM_KEYWORD_ASSET = "hey-jarvis_android.ppn"
    }
}
