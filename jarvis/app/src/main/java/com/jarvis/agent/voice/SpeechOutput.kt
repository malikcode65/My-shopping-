package com.jarvis.agent.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.jarvis.agent.core.Prefs
import com.jarvis.agent.core.SecureStore
import com.jarvis.agent.core.TtsEngine
import com.jarvis.agent.core.await
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

interface SpeechOutput {
    /** Speaks [text] and suspends until finished. Cancelling the coroutine stops speech. */
    suspend fun speak(text: String, language: SpokenLanguage)

    /** Stops speech immediately (barge-in / kill switch). */
    fun stop()
}

private val speechAttributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANT)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

class AndroidTts(context: Context) : SpeechOutput {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, () -> Unit>()
    private val ids = AtomicInteger()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setAudioAttributes(speechAttributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.invoke() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.invoke() }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                utteranceId?.let { pending.remove(it)?.invoke() }
            }
        })
    }

    override suspend fun speak(text: String, language: SpokenLanguage) {
        if (text.isBlank()) return
        val ok = withTimeoutOrNull(5000) { ready.await() } ?: false
        if (!ok) return
        tts.language = localeFor(language)
        for (chunk in chunks(text)) speakChunk(chunk)
    }

    private suspend fun speakChunk(chunk: String) = suspendCancellableCoroutine { cont ->
        val id = "u${ids.incrementAndGet()}"
        pending[id] = { if (cont.isActive) cont.resume(Unit) }
        cont.invokeOnCancellation { pending.remove(id); tts.stop() }
        val r = tts.speak(chunk, TextToSpeech.QUEUE_ADD, null, id)
        if (r != TextToSpeech.SUCCESS) pending.remove(id)?.invoke()
    }

    private fun localeFor(language: SpokenLanguage): Locale {
        val wanted = when (language) {
            SpokenLanguage.URDU -> Locale("ur", "PK")
            // An Indian-English voice pronounces Roman Urdu far better than en-US.
            SpokenLanguage.ROMAN_URDU -> Locale("en", "IN")
            SpokenLanguage.ENGLISH -> Locale.US
        }
        return if (tts.isLanguageAvailable(wanted) >= TextToSpeech.LANG_AVAILABLE) wanted else Locale.US
    }

    private fun chunks(text: String): List<String> {
        val max = (TextToSpeech.getMaxSpeechInputLength() - 10).coerceAtLeast(200)
        if (text.length <= max) return listOf(text)
        return text.chunked(max)
    }

    override fun stop() {
        tts.stop()
        val callbacks = pending.values.toList()
        pending.clear()
        callbacks.forEach { it() }
    }
}

/** ElevenLabs multilingual voice (natural Urdu + English). Falls back to Android TTS on any failure. */
class ElevenLabsTts(
    private val context: Context,
    private val http: OkHttpClient,
    private val secure: SecureStore,
    private val prefs: Prefs,
    private val fallback: SpeechOutput,
) : SpeechOutput {
    @Volatile private var player: MediaPlayer? = null

    override suspend fun speak(text: String, language: SpokenLanguage) {
        if (text.isBlank()) return
        val key = secure.get(SecureStore.Secret.ELEVENLABS_API_KEY)
        if (key == null) return fallback.speak(text, language)
        val file = runCatching { synthesize(key, text) }.getOrNull()
        if (file == null) return fallback.speak(text, language)
        try {
            play(file)
        } finally {
            file.delete()
        }
    }

    private suspend fun synthesize(key: String, text: String): File? = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("text", text)
            put("model_id", "eleven_multilingual_v2")
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/${prefs.elevenLabsVoiceId}?output_format=mp3_44100_128")
            .header("xi-api-key", key)
            .header("accept", "audio/mpeg")
            .post(body)
            .build()
        http.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val f = File.createTempFile("tts", ".mp3", context.cacheDir)
            resp.body?.byteStream()?.use { input -> f.outputStream().use { input.copyTo(it) } }
            f
        }
    }

    private suspend fun play(file: File) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val mp = MediaPlayer()
            player = mp
            fun done() {
                runCatching { mp.release() }
                if (player === mp) player = null
                if (cont.isActive) cont.resume(Unit)
            }
            mp.setAudioAttributes(speechAttributes)
            mp.setOnCompletionListener { done() }
            mp.setOnErrorListener { _, _, _ -> done(); true }
            cont.invokeOnCancellation { runCatching { mp.stop() }; runCatching { mp.release() } }
            try {
                mp.setDataSource(file.absolutePath)
                mp.prepare()
                mp.start()
            } catch (e: Exception) {
                done()
            }
        }
    }

    override fun stop() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        fallback.stop()
    }
}

/** Chooses the configured engine at call time so settings changes apply immediately. */
class SwitchingSpeechOutput(
    private val prefs: Prefs,
    private val android: SpeechOutput,
    private val eleven: SpeechOutput,
) : SpeechOutput {
    override suspend fun speak(text: String, language: SpokenLanguage) =
        if (prefs.ttsEngine == TtsEngine.ELEVENLABS) eleven.speak(text, language) else android.speak(text, language)

    override fun stop() {
        android.stop()
        eleven.stop()
    }
}
