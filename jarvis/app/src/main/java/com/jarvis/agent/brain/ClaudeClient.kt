package com.jarvis.agent.brain

import com.jarvis.agent.core.Prefs
import com.jarvis.agent.core.SecureStore
import com.jarvis.agent.core.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Settings that shape one Messages API request. */
data class ModelConfig(
    val model: String,
    val effort: String,
    val directApi: Boolean,
) {
    /**
     * Claude Sonnet 5.5 accepts `between_tools`: no hidden reasoning, text between tool calls comes back as
     * plain text, and no thinking blocks are bound to history — so we may prune old screenshots safely.
     * Other models run adaptive thinking and the history is kept strictly append-only.
     */
    val thinkingBetweenTools: Boolean get() = model == "claude-sonnet-5-5"

    val supportsDefaultFallbacks: Boolean
        get() = directApi && model in setOf("claude-sonnet-5-5", "claude-opus-5-5", "claude-opus-5", "claude-fable-5-1")

    val canPruneHistory: Boolean get() = thinkingBetweenTools
}

object RequestFactory {
    const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

    fun build(
        config: ModelConfig,
        system: String,
        tools: JsonArray,
        messages: List<JsonObject>,
        maxTokens: Int = 16_000,
    ): JsonObject = buildJsonObject {
        put("model", config.model)
        put("max_tokens", maxTokens)
        put("stream", true)
        put(
            "system",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", "text")
                        put("text", system)
                        putJsonObject("cache_control") { put("type", "ephemeral") }
                    },
                ),
            ),
        )
        put("tools", tools)
        put("messages", JsonArray(messages))
        // Automatic caching of the growing conversation prefix.
        putJsonObject("cache_control") { put("type", "ephemeral") }
        putJsonObject("thinking") { put("type", if (config.thinkingBetweenTools) "between_tools" else "adaptive") }
        val effort = if (config.thinkingBetweenTools && config.effort !in setOf("low", "medium", "high")) "high" else config.effort
        putJsonObject("output_config") { put("effort", effort) }
        if (config.supportsDefaultFallbacks) put("fallbacks", "default")
    }

    fun textBlock(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    fun userMessage(blocks: List<JsonObject>): JsonObject = buildJsonObject {
        put("role", "user")
        put("content", JsonArray(blocks))
    }

    fun assistantMessage(content: JsonArray): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("content", content)
    }

    fun toolResult(toolUseId: String, text: String, isError: Boolean, imageJpegBase64: String? = null): JsonObject =
        buildJsonObject {
            put("type", "tool_result")
            put("tool_use_id", toolUseId)
            val parts = mutableListOf<JsonObject>(textBlock(text.ifBlank { "(empty)" }))
            if (imageJpegBase64 != null) {
                parts += buildJsonObject {
                    put("type", "image")
                    putJsonObject("source") {
                        put("type", "base64")
                        put("media_type", "image/jpeg")
                        put("data", imageJpegBase64)
                    }
                }
            }
            put("content", JsonArray(parts))
            if (isError) put("is_error", true)
        }

    /**
     * Replaces images in all but the most recent user message with a short note so long UI sessions
     * don't resend dozens of screenshots. Only used when [ModelConfig.canPruneHistory].
     */
    fun pruneOldImages(messages: MutableList<JsonObject>) {
        val lastUser = messages.indexOfLast { it.str("role") == "user" }
        for (i in messages.indices) {
            if (i == lastUser || messages[i].str("role") != "user") continue
            val content = messages[i].arr("content") ?: continue
            var changed = false
            val newContent = content.map { block ->
                val b = block as? JsonObject ?: return@map block
                if (b.str("type") != "tool_result") return@map b
                val inner = b.arr("content") ?: return@map b
                if (inner.none { (it as? JsonObject)?.str("type") == "image" }) return@map b
                changed = true
                b.with("content" to JsonArray(inner.map { part ->
                    if ((part as? JsonObject)?.str("type") == "image") textBlock("[older screenshot removed to save space]") else part
                }))
            }
            if (changed) messages[i] = messages[i].with("content" to JsonArray(newContent))
        }
    }
}

/**
 * Streams one Messages API call over raw HTTP (OkHttp + SSE) so it works the same against
 * api.anthropic.com or the user's own proxy. Cancelling the coroutine aborts the HTTP call immediately.
 */
class ClaudeClient(
    private val http: OkHttpClient,
    private val prefs: Prefs,
    private val secure: SecureStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun currentConfig() = ModelConfig(model = prefs.model, effort = prefs.effort, directApi = prefs.usesDirectApi)

    suspend fun stream(body: JsonObject, listener: StreamListener? = null): AssistantTurn {
        var attempt = 0
        while (true) {
            try {
                return streamOnce(body, listener)
            } catch (e: ApiException) {
                if (!e.retryable || attempt >= MAX_RETRIES) throw e
                delay(((e.retryAfterSec ?: 0L) * 1000L).coerceAtLeast(backoffMs(attempt)).coerceAtMost(20_000L))
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                if (attempt >= MAX_RETRIES) throw e
                delay(backoffMs(attempt))
            }
            attempt++
        }
    }

    private fun backoffMs(attempt: Int) = 1000L shl attempt.coerceAtMost(4)

    private suspend fun streamOnce(body: JsonObject, listener: StreamListener?): AssistantTurn = withContext(Dispatchers.IO) {
        val direct = prefs.usesDirectApi
        val apiKey = secure.get(SecureStore.Secret.ANTHROPIC_API_KEY)
        val proxyToken = secure.get(SecureStore.Secret.PROXY_TOKEN)
        if (direct && apiKey.isNullOrBlank()) {
            throw ApiException(401, "missing_api_key", "Claude API key set nahi hai. Settings mein daalein.")
        }
        val builder = Request.Builder()
            .url(prefs.apiBaseUrl + "/v1/messages")
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
            .header("anthropic-version", API_VERSION)
        if (!apiKey.isNullOrBlank()) builder.header("x-api-key", apiKey)
        if (!direct && !proxyToken.isNullOrBlank()) builder.header("authorization", "Bearer $proxyToken")
        if (body["fallbacks"] != null) builder.header("anthropic-beta", RequestFactory.FALLBACK_BETA)
        builder.post(body.toString().toRequestBody(JSON))

        val call = http.newCall(builder.build())
        val cancelHandle = coroutineContext.job.invokeOnCompletion { call.cancel() }
        try {
            call.await().use { resp ->
                if (!resp.isSuccessful) {
                    val raw = resp.body?.string().orEmpty()
                    val err = runCatching { json.parseToJsonElement(raw).jsonObject.obj("error") }.getOrNull()
                    throw ApiException(
                        status = resp.code,
                        type = err?.str("type") ?: "http_${resp.code}",
                        message = err?.str("message") ?: raw.take(300).ifBlank { "HTTP ${resp.code}" },
                        retryAfterSec = resp.header("retry-after")?.toLongOrNull(),
                    )
                }
                val source = resp.body?.source() ?: throw IOException("Empty response body")
                val acc = MessageAccumulator(listener)
                val sse = SseParser { event, data -> acc.onEvent(event, data) }
                while (true) {
                    coroutineContext.ensureActive()
                    val line = source.readUtf8Line() ?: break
                    sse.feedLine(line)
                }
                sse.finish()
                acc.build()
            }
        } finally {
            cancelHandle.dispose()
        }
    }

    companion object {
        const val API_VERSION = "2026-10-21"
        private const val MAX_RETRIES = 3
        private val JSON = "application/json".toMediaType()
    }
}
