package com.jarvis.agent.tools

import com.jarvis.agent.brain.arr
import com.jarvis.agent.brain.obj
import com.jarvis.agent.brain.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Tiny JSON-Schema builder for tool inputs. */
class SchemaBuilder {
    private val props = LinkedHashMap<String, JsonObject>()
    private val required = mutableListOf<String>()

    private fun add(name: String, required: Boolean, schema: JsonObject) {
        props[name] = schema
        if (required) this.required += name
    }

    fun string(name: String, description: String, required: Boolean = true, enum: List<String>? = null) =
        add(name, required, buildJsonObject {
            put("type", "string")
            put("description", description)
            if (enum != null) put("enum", JsonArray(enum.map { JsonPrimitive(it) }))
        })

    fun integer(name: String, description: String, required: Boolean = true, min: Long? = null, max: Long? = null) =
        add(name, required, buildJsonObject {
            put("type", "integer")
            put("description", description)
            if (min != null) put("minimum", min)
            if (max != null) put("maximum", max)
        })

    fun number(name: String, description: String, required: Boolean = true) =
        add(name, required, buildJsonObject {
            put("type", "number")
            put("description", description)
        })

    fun boolean(name: String, description: String, required: Boolean = true) =
        add(name, required, buildJsonObject {
            put("type", "boolean")
            put("description", description)
        })

    fun stringArray(name: String, description: String, required: Boolean = true) =
        add(name, required, buildJsonObject {
            put("type", "array")
            put("description", description)
            put("items", buildJsonObject { put("type", "string") })
        })

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props))
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
        put("additionalProperties", false)
    }
}

fun inputSchema(block: SchemaBuilder.() -> Unit = {}): JsonObject = SchemaBuilder().apply(block).build()

/**
 * Validates tool input against the subset of JSON Schema produced by [SchemaBuilder]. Every tool call is
 * validated before it runs (inputs are streamed eagerly, so the API does not validate them for us).
 */
object SchemaValidator {
    fun validate(schema: JsonObject, input: JsonObject): List<String> {
        val errors = mutableListOf<String>()
        val props = schema.obj("properties") ?: JsonObject(emptyMap())
        schema.arr("required")?.forEach { r ->
            val key = (r as? JsonPrimitive)?.content ?: return@forEach
            val v = input[key]
            if (v == null || v is JsonNull) errors += "missing required field '$key'"
        }
        val closed = (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false
        for ((key, value) in input) {
            val p = props[key] as? JsonObject
            if (p == null) {
                if (closed) errors += "unknown field '$key'"
                continue
            }
            if (value is JsonNull) continue
            checkType(key, p, value)?.let { errors += it }
        }
        return errors
    }

    private fun checkType(key: String, p: JsonObject, value: JsonElement): String? {
        val type = p.str("type") ?: return null
        val prim = value as? JsonPrimitive
        when (type) {
            "string" -> {
                if (prim == null || !prim.isString) return "'$key' must be a string"
                val allowed = p.arr("enum")?.mapNotNull { (it as? JsonPrimitive)?.content }
                if (allowed != null && prim.content !in allowed) return "'$key' must be one of $allowed"
            }
            "integer" -> {
                val n = prim?.takeIf { !it.isString }?.longOrNull
                    ?: prim?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it % 1.0 == 0.0 }?.toLong()
                    ?: return "'$key' must be an integer"
                (p["minimum"] as? JsonPrimitive)?.longOrNull?.let { if (n < it) return "'$key' must be >= $it" }
                (p["maximum"] as? JsonPrimitive)?.longOrNull?.let { if (n > it) return "'$key' must be <= $it" }
            }
            "number" -> if (prim == null || prim.isString || prim.doubleOrNull == null) return "'$key' must be a number"
            "boolean" -> if (prim == null || prim.isString || prim.booleanOrNull == null) return "'$key' must be a boolean"
            "array" -> if (value !is JsonArray) return "'$key' must be an array"
            "object" -> if (value !is JsonObject) return "'$key' must be an object"
        }
        return null
    }
}
