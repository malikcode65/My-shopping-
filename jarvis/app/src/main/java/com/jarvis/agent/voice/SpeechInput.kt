package com.jarvis.agent.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.jarvis.agent.core.ListenLanguage
import com.jarvis.agent.core.SecureStore
import com.jarvis.agent.core.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.math.sqrt

interface SpeechInput {
    /** Listens for one utterance. Returns null on silence, error or cancellation. */
    suspend fun listen(language: ListenLanguage): String?
}

/** On-device / Google speech recognition. Supports ur-PK and en-US (auto-detect on Android 14+). */
class AndroidSpeechInput(private val context: Context) : SpeechInput {
    private val main = Handler(Looper.getMainLooper())

    override suspend fun listen(language: ListenLanguage): String? = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return@withContext null
        suspendCancellableCoroutine { cont ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            fun finish(result: String?) {
                main.post { runCatching { recognizer.destroy() } }
                if (cont.isActive) cont.resume(result)
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                }

                override fun onError(error: Int) = finish(null)
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                if (language == ListenLanguage.AUTO && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                        arrayListOf("ur-PK", "en-US", "en-IN"),
                    )
                }
            }
            cont.invokeOnCancellation {
                main.post { runCatching { recognizer.cancel(); recognizer.destroy() } }
            }
            recognizer.startListening(intent)
        }
    }
}

/**
 * Records with a simple energy-based end-of-speech detector and transcribes with OpenAI Whisper,
 * which handles Urdu, Roman Urdu and code-switched English well.
 */
class WhisperSpeechInput(
    private val http: OkHttpClient,
    private val secure: SecureStore,
) : SpeechInput {

    override suspend fun listen(language: ListenLanguage): String? = withContext(Dispatchers.IO) {
        val key = secure.get(SecureStore.Secret.OPENAI_API_KEY) ?: return@withContext null
        val pcm = record() ?: return@withContext null
        val wav = Wav.fromPcm16Mono(pcm, SAMPLE_RATE)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1")
            .apply {
                when (language) {
                    ListenLanguage.URDU -> addFormDataPart("language", "ur")
                    ListenLanguage.ENGLISH -> addFormDataPart("language", "en")
                    ListenLanguage.AUTO -> Unit
                }
            }
            .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()
        http.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val json = Json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            json["text"]?.jsonPrimitive?.content?.trim()?.ifEmpty { null }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun record(): ByteArray? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME * 4),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) return null
        val detector = EndOfSpeechDetector()
        val out = java.io.ByteArrayOutputStream()
        val frame = ShortArray(FRAME)
        try {
            recorder.startRecording()
            while (true) {
                coroutineContext.ensureActive()
                val n = recorder.read(frame, 0, FRAME)
                if (n <= 0) break
                for (i in 0 until n) {
                    out.write(frame[i].toInt() and 0xFF)
                    out.write((frame[i].toInt() shr 8) and 0xFF)
                }
                when (detector.onFrame(rms(frame, n), FRAME_MS)) {
                    EndOfSpeechDetector.State.CONTINUE -> Unit
                    EndOfSpeechDetector.State.DONE -> return out.toByteArray()
                    EndOfSpeechDetector.State.NO_SPEECH -> return null
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        return null
    }

    private fun rms(buf: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
        return sqrt(sum / n)
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val FRAME = SAMPLE_RATE * FRAME_MS / 1000
    }
}

/** Pure-logic voice activity end-pointing so it can be unit tested. */
class EndOfSpeechDetector(
    private val calibrationMs: Int = 300,
    private val trailingSilenceMs: Int = 1200,
    private val noSpeechTimeoutMs: Int = 6000,
    private val maxUtteranceMs: Int = 15000,
) {
    enum class State { CONTINUE, DONE, NO_SPEECH }

    private var elapsed = 0
    private var noiseFloor = 0.0
    private var calibrationFrames = 0
    private var speechStarted = false
    private var silenceMs = 0

    fun onFrame(rms: Double, frameMs: Int): State {
        elapsed += frameMs
        if (elapsed <= calibrationMs) {
            noiseFloor = (noiseFloor * calibrationFrames + rms) / (calibrationFrames + 1)
            calibrationFrames++
            return State.CONTINUE
        }
        val threshold = maxOf(400.0, noiseFloor * 3)
        if (rms > threshold) {
            speechStarted = true
            silenceMs = 0
        } else if (speechStarted) {
            silenceMs += frameMs
        }
        return when {
            speechStarted && silenceMs >= trailingSilenceMs -> State.DONE
            speechStarted && elapsed >= maxUtteranceMs -> State.DONE
            !speechStarted && elapsed >= noSpeechTimeoutMs -> State.NO_SPEECH
            else -> State.CONTINUE
        }
    }
}

object Wav {
    fun fromPcm16Mono(pcm: ByteArray, sampleRate: Int): ByteArray {
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        header.putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(pcm.size)
        return header.array() + pcm
    }
}
