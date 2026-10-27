package com.jarvis.agent.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Ordered set of tools. Order is kept stable so the tools prefix of every request stays cacheable. */
class ToolRegistry {
    private val tools = LinkedHashMap<String, Tool>()

    fun register(vararg t: Tool): ToolRegistry {
        for (tool in t) {
            require(tool.name.matches(Regex("^[a-zA-Z0-9_-]{1,64}$"))) { "Bad tool name ${tool.name}" }
            require(tool.name !in tools) { "Duplicate tool ${tool.name}" }
            tools[tool.name] = tool
        }
        return this
    }

    operator fun get(name: String): Tool? = tools[name]

    val all: Collection<Tool> get() = tools.values

    /** Tool definitions for the Messages API. */
    fun apiDefinitions(eagerInputStreaming: Boolean): JsonArray = JsonArray(
        tools.values.map { t ->
            buildJsonObject {
                put("name", t.name)
                put("description", t.description + " [risk: ${t.baseRisk.name.lowercase()}]")
                put("input_schema", t.schema)
                if (eagerInputStreaming) put("eager_input_streaming", true)
            }
        },
    )
}
