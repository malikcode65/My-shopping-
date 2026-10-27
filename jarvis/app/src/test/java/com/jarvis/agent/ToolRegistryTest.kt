package com.jarvis.agent

import com.jarvis.agent.brain.obj
import com.jarvis.agent.brain.str
import com.jarvis.agent.tools.RiskLevel
import com.jarvis.agent.tools.ToolCatalog
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {
    private val registry = ToolCatalog.build()

    @Test
    fun everyToolHasAValidDefinition() {
        val defs = registry.apiDefinitions(eagerInputStreaming = true)
        assertEquals(registry.all.size, defs.size)
        for (d in defs) {
            d as JsonObject
            assertTrue(d.str("name")!!.matches(Regex("^[a-z_]{3,64}$")))
            assertTrue(d.str("description")!!.length > 10)
            assertEquals("object", d.obj("input_schema")?.str("type"))
            assertEquals("true", d.str("eager_input_streaming"))
        }
    }

    @Test
    fun riskLevelsMatchThePolicy() {
        fun risk(n: String) = registry[n]!!.baseRisk
        listOf("send_sms", "make_call", "send_email", "reply_notification").forEach { assertEquals(it, RiskLevel.MEDIUM, risk(it)) }
        listOf("delete_file", "delete_calendar_event").forEach { assertEquals(it, RiskLevel.HIGH, risk(it)) }
        listOf("read_screen", "screenshot", "get_time", "read_notifications").forEach {
            assertEquals(it, RiskLevel.LOW, risk(it))
            assertTrue(it, registry[it]!!.readOnly)
        }
        // UI actions start LOW but are escalated per element by the UI risk classifier
        assertEquals(RiskLevel.LOW, risk("tap"))
    }

    @Test
    fun noDuplicateNames() {
        assertEquals(registry.all.size, registry.all.map { it.name }.toSet().size)
    }
}
