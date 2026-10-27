package com.jarvis.agent.tools

import android.content.Context
import com.jarvis.agent.core.AppGraph
import com.jarvis.agent.core.TaskOrigin
import kotlinx.serialization.json.JsonObject

/**
 * LOW: runs immediately. MEDIUM: spoken + on-screen confirmation showing the exact content.
 * HIGH: on-screen confirmation unlocked with biometric / device credential only.
 */
enum class RiskLevel { LOW, MEDIUM, HIGH }

fun maxRisk(a: RiskLevel, b: RiskLevel) = if (a.ordinal >= b.ordinal) a else b

data class RiskAssessment(
    val level: RiskLevel,
    /** One line, shown and spoken in the confirmation ("Send SMS to Ammi (0300…)"). */
    val summary: String,
    /** The exact content that will be sent/posted/deleted, shown verbatim on screen. */
    val details: String = "",
)

data class ToolResult(
    val text: String,
    val isError: Boolean = false,
    val imageJpegBase64: String? = null,
    /** Set when [text] came from a third party (screen, notification, SMS, web…): it is wrapped as data. */
    val untrustedSource: String? = null,
) {
    companion object {
        fun ok(text: String) = ToolResult(text)
        fun error(text: String) = ToolResult(text, isError = true)
        fun untrusted(source: String, text: String) = ToolResult(text, untrustedSource = source)
    }
}

class ToolContext(
    val app: Context,
    val graph: AppGraph,
    val origin: TaskOrigin,
    val taskId: Long,
)

/**
 * One capability Jarvis can use. Add a new tool by implementing this interface and registering it in
 * [ToolCatalog]. The agent loop validates input against [schema], applies the risk policy, logs every
 * call and enforces [timeoutMs].
 */
interface Tool {
    val name: String
    val description: String
    val schema: JsonObject
    val baseRisk: RiskLevel

    /** True if the tool only reads information (used for prompt-injection escalation). */
    val readOnly: Boolean get() = false
    val timeoutMs: Long get() = 30_000L

    /** May raise (never lower) the risk for a concrete input, and describes the exact action. */
    suspend fun assess(input: JsonObject, ctx: ToolContext): RiskAssessment = RiskAssessment(baseRisk, describe(input))

    fun describe(input: JsonObject): String = "$name $input"

    suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult
}

/** Convenience base class: most tools are a name, a description, a schema and execute(). */
abstract class BaseTool(
    override val name: String,
    override val description: String,
    override val baseRisk: RiskLevel,
    override val readOnly: Boolean = false,
    schemaBlock: SchemaBuilder.() -> Unit = {},
) : Tool {
    override val schema: JsonObject = inputSchema(schemaBlock)
}
