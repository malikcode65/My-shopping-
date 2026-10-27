package com.jarvis.agent

import com.jarvis.agent.brain.ApiException
import com.jarvis.agent.brain.MessageAccumulator
import com.jarvis.agent.brain.ModelConfig
import com.jarvis.agent.brain.RateLimiter
import com.jarvis.agent.brain.RequestFactory
import com.jarvis.agent.brain.SseParser
import com.jarvis.agent.brain.StreamListener
import com.jarvis.agent.brain.arr
import com.jarvis.agent.brain.obj
import com.jarvis.agent.brain.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainTest {

    private fun run(stream: String, listener: StreamListener? = null) =
        MessageAccumulator(listener).also { acc ->
            val sse = SseParser { e, d -> acc.onEvent(e, d) }
            stream.lines().forEach(sse::feedLine)
            sse.finish()
        }.build()

    private val toolStream = """
event: message_start
data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[],"usage":{"input_tokens":10,"output_tokens":1}}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Opening "}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"WhatsApp"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: ping
data: {"type":"ping"}

event: content_block_start
data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"launch_app","input":{}}}

event: content_block_delta
data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"app\": \"Wha"}}

event: content_block_delta
data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"tsApp\"}"}}

event: content_block_stop
data: {"type":"content_block_stop","index":1}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":30}}

event: message_stop
data: {"type":"message_stop"}
""".trimIndent()

    @Test
    fun accumulatesTextAndToolUse() {
        val deltas = StringBuilder()
        val tools = mutableListOf<String>()
        val turn = run(toolStream, object : StreamListener {
            override fun onTextDelta(text: String) { deltas.append(text) }
            override fun onToolUseStart(name: String) { tools += name }
        })
        assertEquals("tool_use", turn.stopReason)
        assertEquals("Opening WhatsApp", turn.text)
        assertEquals("Opening WhatsApp", deltas.toString())
        assertEquals(listOf("launch_app"), tools)
        assertEquals(1, turn.toolCalls.size)
        val call = turn.toolCalls[0]
        assertEquals("toolu_1", call.id)
        assertEquals("WhatsApp", call.input.str("app"))
        assertNull(call.parseError)
        // replayable content keeps both blocks with the final values
        assertEquals(2, turn.content.size)
        assertEquals("WhatsApp", (turn.content[1] as JsonObject).obj("input")?.str("app"))
    }

    @Test
    fun invalidToolJsonIsFlaggedNotExecuted() {
        val s = """
data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t","name":"send_sms","input":{}}}

data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"to\": \"Ali\", \"message\": \"hi"}}

data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"}}
""".trimIndent()
        val turn = run(s)
        assertEquals("max_tokens", turn.stopReason)
        assertNotNull(turn.toolCalls[0].parseError)
        assertEquals(0, turn.toolCalls[0].input.size)
    }

    @Test
    fun keepsThinkingAndUnknownBlocks() {
        val s = """
data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}

data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm"}}

data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig123"}}

data: {"type":"content_block_start","index":1,"content_block":{"type":"fallback","model":"x"}}

data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}
""".trimIndent()
        val turn = run(s)
        val thinking = turn.content[0] as JsonObject
        assertEquals("hmm", thinking.str("thinking"))
        assertEquals("sig123", thinking.str("signature"))
        assertEquals("fallback", (turn.content[1] as JsonObject).str("type"))
    }

    @Test(expected = ApiException::class)
    fun streamErrorEventThrows() {
        run("""data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""" + "\n")
    }

    @Test
    fun requestHasSafeDefaultsForSonnet55() {
        val cfg = ModelConfig("claude-sonnet-5-5", "medium", directApi = true)
        val body = RequestFactory.build(cfg, "sys", JsonArray(emptyList()), listOf(RequestFactory.userMessage(listOf(RequestFactory.textBlock("hi")))))
        assertEquals("claude-sonnet-5-5", body.str("model"))
        assertEquals("between_tools", body.obj("thinking")?.str("type"))
        assertEquals("medium", body.obj("output_config")?.str("effort"))
        assertEquals("default", body.str("fallbacks"))
        assertEquals("true", body.str("stream"))
        assertNull(body["temperature"])
        assertEquals("ephemeral", (body.arr("system")!![0] as JsonObject).obj("cache_control")?.str("type"))
    }

    @Test
    fun proxyAndOtherModelsDropUnsupportedFields() {
        val proxy = RequestFactory.build(ModelConfig("claude-sonnet-5-5", "low", directApi = false), "s", JsonArray(emptyList()), emptyList())
        assertNull(proxy["fallbacks"])
        val opus = ModelConfig("claude-opus-5-5", "high", directApi = true)
        assertFalse(opus.canPruneHistory)
        assertEquals("adaptive", RequestFactory.build(opus, "s", JsonArray(emptyList()), emptyList()).obj("thinking")?.str("type"))
    }

    @Test
    fun prunesOnlyOlderScreenshots() {
        fun shot(id: String) = RequestFactory.userMessage(listOf(RequestFactory.toolResult(id, "screen", false, "AAAA")))
        val msgs = mutableListOf(shot("a"), RequestFactory.assistantMessage(JsonArray(emptyList())), shot("b"))
        RequestFactory.pruneOldImages(msgs)
        fun hasImage(m: JsonObject) = m.toString().contains("\"image\"")
        assertFalse(hasImage(msgs[0]))
        assertTrue(msgs[0].toString().contains("older screenshot removed"))
        assertTrue(hasImage(msgs[2]))
    }

    @Test
    fun toolResultCarriesErrorFlagAndImage() {
        val r = RequestFactory.toolResult("id1", "boom", isError = true, imageJpegBase64 = "QQ==")
        assertEquals("true", r.str("is_error"))
        assertEquals(2, r.arr("content")!!.size)
    }

    @Test
    fun rateLimiterBlocksAfterLimit() {
        val rl = RateLimiter(maxPerWindow = { 2 }, windowMs = 1000)
        assertEquals(0, rl.delayNeeded(0)); rl.record(0)
        assertEquals(0, rl.delayNeeded(100)); rl.record(100)
        assertEquals(900, rl.delayNeeded(100))
        assertEquals(0, rl.delayNeeded(1000))
    }
}
