package com.jarvis.agent.brain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Minimal Server-Sent Events parser (one line at a time). */
class SseParser(private val onEvent: (event: String?, data: String) -> Unit) {
    private var event: String? = null
    private val data = StringBuilder()

    fun feedLine(line: String) {
        if (line.isEmpty()) {
            dispatch()
            return
        }
        if (line.startsWith(":")) return
        val idx = line.indexOf(':')
        val field = if (idx < 0) line else line.substring(0, idx)
        val value = if (idx < 0) "" else line.substring(idx + 1).removePrefix(" ")
        when (field) {
            "event" -> event = value
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
        }
    }

    fun finish() = dispatch()

    private fun dispatch() {
        if (data.isNotEmpty()) onEvent(event, data.toString())
        event = null
        data.setLength(0)
    }
}

data class ToolCall(
    val id: String,
    val name: String,
    val input: JsonObject,
    val rawInput: String,
    /** Non-null when the streamed input was not a valid JSON object (never execute such a call). */
    val parseError: String?,
)

data class AssistantTurn(
    /** Content blocks exactly as they must be replayed in the next request. */
    val content: JsonArray,
    val text: String,
    val toolCalls: List<ToolCall>,
    val stopReason: String?,
    val stopDetails: JsonObject?,
)

interface StreamListener {
    fun onTextDelta(text: String) {}
    fun onToolUseStart(name: String) {}
}

class ApiException(
    val status: Int,
    val type: String,
    message: String,
    val retryAfterSec: Long? = null,
) : Exception(message) {
    val retryable: Boolean
        get() = status == 408 || status == 409 || status == 429 || status >= 500 ||
            type == "overloaded_error" || type == "api_error" || type == "rate_limit_error"
}

/**
 * Rebuilds the assistant message from Messages API stream events: text, tool_use (input_json_delta),
 * thinking (+signature) and any unknown block types (kept verbatim so they can be replayed).
 */
class MessageAccumulator(private val listener: StreamListener? = null) {

    private class Block(val base: JsonObject) {
        val type: String = base.str("type") ?: "unknown"
        val text = StringBuilder()
        val thinking = StringBuilder()
        var signature: String? = null
        val partialJson = StringBuilder()
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val blocks = sortedMapOf<Int, Block>()
    private var stopReason: String? = null
    private var stopDetails: JsonObject? = null

    fun onEvent(event: String?, data: String) {
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        when (obj.str("type") ?: event) {
            "content_block_start" -> {
                val index = obj.int("index") ?: return
                val block = Block(obj.obj("content_block") ?: return)
                blocks[index] = block
                if (block.type == "tool_use") listener?.onToolUseStart(block.base.str("name").orEmpty())
            }
            "content_block_delta" -> {
                val block = blocks[obj.int("index") ?: return] ?: return
                val delta = obj.obj("delta") ?: return
                when (delta.str("type")) {
                    "text_delta" -> delta.str("text")?.let {
                        block.text.append(it)
                        listener?.onTextDelta(it)
                    }
                    "input_json_delta" -> delta.str("partial_json")?.let { block.partialJson.append(it) }
                    "thinking_delta" -> delta.str("thinking")?.let { block.thinking.append(it) }
                    "signature_delta" -> block.signature = delta.str("signature")
                }
            }
            "message_delta" -> {
                obj.obj("delta")?.let { d ->
                    d.str("stop_reason")?.let { stopReason = it }
                    d.obj("stop_details")?.let { stopDetails = it }
                }
            }
            "error" -> {
                val err = obj.obj("error")
                throw ApiException(0, err?.str("type") ?: "stream_error", err?.str("message") ?: "Stream error")
            }
        }
    }

    fun build(): AssistantTurn {
        val content = mutableListOf<JsonElement>()
        val calls = mutableListOf<ToolCall>()
        val text = StringBuilder()
        for ((_, b) in blocks) {
            when (b.type) {
                "text" -> {
                    val full = (b.base.str("text") ?: "") + b.text
                    text.append(full)
                    content += b.base.with("text" to JsonPrimitive(full))
                }
                "thinking" -> {
                    val pairs = mutableListOf<Pair<String, JsonElement>>(
                        "thinking" to JsonPrimitive((b.base.str("thinking") ?: "") + b.thinking),
                    )
                    (b.signature ?: b.base.str("signature"))?.let { pairs += "signature" to JsonPrimitive(it) }
                    content += b.base.with(*pairs.toTypedArray())
                }
                "tool_use", "server_tool_use" -> {
                    val raw = b.partialJson.toString()
                    var error: String? = null
                    val input: JsonObject = if (raw.isBlank()) {
                        b.base.obj("input") ?: JsonObject(emptyMap())
                    } else {
                        try {
                            json.parseToJsonElement(raw) as? JsonObject ?: run {
                                error = "Tool input is not a JSON object"
                                JsonObject(emptyMap())
                            }
                        } catch (e: Exception) {
                            error = "Invalid JSON: ${e.message?.take(200)}"
                            JsonObject(emptyMap())
                        }
                    }
                    content += b.base.with("input" to input)
                    if (b.type == "tool_use") {
                        calls += ToolCall(
                            id = b.base.str("id").orEmpty(),
                            name = b.base.str("name").orEmpty(),
                            input = input,
                            rawInput = raw,
                            parseError = error,
                        )
                    }
                }
                else -> content += b.base
            }
        }
        return AssistantTurn(JsonArray(content), text.toString(), calls, stopReason, stopDetails)
    }
}
