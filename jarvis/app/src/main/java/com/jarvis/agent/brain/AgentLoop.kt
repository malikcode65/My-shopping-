package com.jarvis.agent.brain

import com.jarvis.agent.core.AgentPhase
import com.jarvis.agent.core.AppGraph
import com.jarvis.agent.core.CommandHandler
import com.jarvis.agent.core.JarvisRuntime
import com.jarvis.agent.core.TaskOrigin
import com.jarvis.agent.log.ActionLogger
import com.jarvis.agent.safety.ConfirmationRequest
import com.jarvis.agent.safety.Confirmer
import com.jarvis.agent.safety.Decision
import com.jarvis.agent.safety.InjectionGuard
import com.jarvis.agent.tools.RiskLevel
import com.jarvis.agent.tools.SchemaValidator
import com.jarvis.agent.tools.ToolContext
import com.jarvis.agent.tools.ToolRegistry
import com.jarvis.agent.tools.ToolResult
import com.jarvis.agent.tools.maxRisk
import com.jarvis.agent.voice.LanguageDetector
import com.jarvis.agent.voice.SpokenLanguage
import com.jarvis.agent.voice.VoiceIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** Long-term memory hook (implemented by the memory module). */
interface MemoryProvider {
    suspend fun contextFor(userText: String): String
    suspend fun onTaskFinished(userText: String, reply: String, steps: Int)
}

object NoMemory : MemoryProvider {
    override suspend fun contextFor(userText: String) = ""
    override suspend fun onTaskFinished(userText: String, reply: String, steps: Int) {}
}

/**
 * The agent loop: plan → act (tool) → observe → verify → continue, until Claude ends its turn or a limit
 * is hit. Limits: max steps (≤ 25), a timeout per model call, per tool and per task, and a request rate
 * limit. Every tool call passes schema validation, the risk gate and the action log.
 */
class AgentLoop(
    private val graph: AppGraph,
    private val claude: ClaudeClient,
    private val registry: ToolRegistry,
    private val confirmer: Confirmer,
    private val log: ActionLogger,
    private val voice: VoiceIO,
    private val memory: () -> MemoryProvider,
    private val rateLimiter: RateLimiter,
) : CommandHandler {

    /** One task at a time: two agents driving the same screen would fight each other. */
    private val oneTaskAtATime = Mutex()

    override suspend fun handle(text: String, origin: TaskOrigin): String = oneTaskAtATime.withLock { handleLocked(text, origin) }

    private suspend fun handleLocked(text: String, origin: TaskOrigin): String {
        val taskId = System.currentTimeMillis()
        val language = LanguageDetector.detect(text)
        log.log(taskId, "TASK", text, detail = "origin=$origin")
        val reply = try {
            withTimeout(graph.prefs.taskTimeoutSec * 1000L) { runTask(taskId, text, origin, language) }
        } catch (e: TimeoutCancellationException) {
            Messages.timeLimit(language)
        } catch (e: CancellationException) {
            log.log(taskId, "KILL", "Task cancelled")
            throw e
        } catch (e: ApiException) {
            log.log(taskId, "ERROR", "Claude API: ${e.type}", detail = e.message)
            Messages.apiError(language, e)
        } catch (e: RateLimitedException) {
            Messages.rateLimited(language)
        } catch (e: Exception) {
            log.log(taskId, "ERROR", e.javaClass.simpleName, detail = e.message)
            Messages.genericError(language)
        }
        log.log(taskId, "REPLY", reply)
        JarvisRuntime.update { it.copy(lastReply = reply, currentAction = "") }
        voice.speak(reply, LanguageDetector.detect(reply).takeIf { it != SpokenLanguage.ENGLISH } ?: language)
        return reply
    }

    private suspend fun runTask(taskId: Long, text: String, origin: TaskOrigin, language: SpokenLanguage): String {
        val config = claude.currentConfig()
        val tools = registry.apiDefinitions(eagerInputStreaming = config.directApi)
        val ctx = ToolContext(graph.context, graph, origin, taskId)
        val mem = memory()
        val messages = mutableListOf(
            RequestFactory.userMessage(
                listOf(
                    RequestFactory.textBlock(SystemPrompt.taskContext(language, origin, runCatching { mem.contextFor(text) }.getOrDefault(""))),
                    RequestFactory.textBlock(text),
                ),
            ),
        )
        var suspiciousDataSeen = false
        val maxSteps = graph.prefs.maxSteps.coerceIn(1, MAX_STEPS)

        for (step in 1..maxSteps) {
            JarvisRuntime.update { it.copy(currentStep = step, phase = AgentPhase.THINKING) }
            rateLimiter.acquire()
            if (config.canPruneHistory) RequestFactory.pruneOldImages(messages)
            val body = RequestFactory.build(config, SystemPrompt.TEXT, tools, messages)
            val turn = withTimeoutOrNull(MODEL_TIMEOUT_MS) { claude.stream(body) }
                ?: return Messages.modelTimeout(language)
            messages += RequestFactory.assistantMessage(turn.content)

            when (turn.stopReason) {
                "refusal" -> {
                    log.log(taskId, "ERROR", "Model declined", detail = turn.stopDetails?.toString())
                    return Messages.refused(language)
                }
                "pause_turn" -> continue
                "tool_use", "max_tokens" -> {
                    if (turn.toolCalls.isEmpty()) return finish(taskId, text, turn.text, step, language)
                    val truncated = turn.stopReason == "max_tokens"
                    val results = turn.toolCalls.map { call ->
                        if (truncated) {
                            RequestFactory.toolResult(call.id, "OUTPUT_TRUNCATED: the tool input was cut off. Retry with shorter input.", true)
                        } else {
                            val (block, suspicious) = runTool(call, ctx, language, suspiciousDataSeen)
                            if (suspicious) suspiciousDataSeen = true
                            block
                        }
                    }
                    messages += RequestFactory.userMessage(results)
                }
                else -> return finish(taskId, text, turn.text, step, language)
            }
        }
        return Messages.stepLimit(language, maxSteps)
    }

    private suspend fun finish(taskId: Long, userText: String, modelText: String, steps: Int, language: SpokenLanguage): String {
        val reply = modelText.trim().ifEmpty { Messages.done(language) }
        runCatching { memory().onTaskFinished(userText, reply, steps) }
        log.log(taskId, "RESULT", "Finished in $steps step(s)")
        return reply
    }

    /** Validates, gates, executes and logs one tool call. Returns the tool_result block. */
    private suspend fun runTool(
        call: ToolCall,
        ctx: ToolContext,
        language: SpokenLanguage,
        suspiciousDataSeen: Boolean,
    ): Pair<JsonObject, Boolean> {
        fun error(msg: String) = RequestFactory.toolResult(call.id, msg, isError = true) to false

        val tool = registry[call.name] ?: return error("UNKNOWN_TOOL: ${call.name}")
        if (call.parseError != null) return error("INVALID_JSON: ${call.parseError}. Raw input: ${call.rawInput.take(500)}")
        val problems = SchemaValidator.validate(tool.schema, call.input)
        if (problems.isNotEmpty()) return error("INVALID_INPUT: ${problems.joinToString("; ")}")

        val assessment = try {
            tool.assess(call.input, ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return error("ERROR: ${e.message ?: e.javaClass.simpleName}")
        }
        var level = maxRisk(tool.baseRisk, assessment.level)
        // After instruction-like third-party content, nothing with side effects runs without the user.
        if (suspiciousDataSeen && !tool.readOnly && level == RiskLevel.LOW) level = RiskLevel.MEDIUM

        JarvisRuntime.update { it.copy(phase = AgentPhase.ACTING, currentAction = assessment.summary) }
        log.log(ctx.taskId, "TOOL", assessment.summary, toolName = tool.name, risk = level.name, detail = call.input.toString())

        if (level != RiskLevel.LOW) {
            val decision = confirmer.confirm(
                ConfirmationRequest(
                    level = level,
                    toolName = tool.name,
                    summary = assessment.summary,
                    details = assessment.details,
                    origin = ctx.origin,
                    language = language,
                ),
            )
            log.log(ctx.taskId, "CONFIRM", assessment.summary, toolName = tool.name, risk = level.name, outcome = decision.name)
            if (decision != Decision.APPROVED) {
                return error(
                    if (decision == Decision.TIMED_OUT) "TIMED_OUT: the user did not confirm in time. Do not retry; tell the user."
                    else "USER_DENIED: the user rejected this action. Do not retry it; tell the user it was cancelled.",
                )
            }
        }

        val result: ToolResult = try {
            withTimeoutOrNull(tool.timeoutMs) { tool.execute(call.input, ctx) }
                ?: ToolResult.error("TIMEOUT: ${tool.name} took longer than ${tool.timeoutMs / 1000}s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            ToolResult.error("PERMISSION_MISSING: ${e.message}. Tell the user to grant it in Jarvis → Permissions.")
        } catch (e: Exception) {
            ToolResult.error("ERROR: ${e.message ?: e.javaClass.simpleName}")
        }
        JarvisRuntime.update { it.copy(phase = AgentPhase.THINKING) }

        var suspicious = false
        val text = if (result.untrustedSource != null) {
            suspicious = InjectionGuard.isSuspicious(result.text)
            InjectionGuard.wrap(result.untrustedSource, result.text)
        } else {
            result.text
        }
        log.log(
            ctx.taskId, "RESULT", if (result.isError) "Failed: ${tool.name}" else "Done: ${tool.name}",
            toolName = tool.name, outcome = result.text.take(300) + if (suspicious) " [instruction-like content flagged]" else "",
        )
        return RequestFactory.toolResult(call.id, text, result.isError, result.imageJpegBase64) to suspicious
    }

    companion object {
        const val MAX_STEPS = 25
        const val MODEL_TIMEOUT_MS = 90_000L
    }
}

/** Fixed spoken messages for failures (the model is not available to phrase these). */
object Messages {
    private fun ur(l: SpokenLanguage) = l != SpokenLanguage.ENGLISH

    fun done(l: SpokenLanguage) = if (ur(l)) "Ho gaya." else "Done."
    fun timeLimit(l: SpokenLanguage) = if (ur(l)) "Waqt ki had poori ho gayi, is liye main ne kaam rok diya." else "I stopped because the time limit was reached."
    fun modelTimeout(l: SpokenLanguage) = if (ur(l)) "Jawab aane mein bohot der lagi, dobara koshish karein." else "The model took too long to answer, please try again."
    fun stepLimit(l: SpokenLanguage, n: Int) = if (ur(l)) "Main ne $n qadam le liye magar kaam mukammal nahi hua." else "I used all $n steps without finishing the task."
    fun refused(l: SpokenLanguage) = if (ur(l)) "Maazrat, main yeh kaam nahi kar sakta." else "Sorry, I can't help with that."
    fun rateLimited(l: SpokenLanguage) = if (ur(l)) "Bohot zyada requests ho gayi hain, thori der baad koshish karein." else "Too many requests right now, please try again shortly."
    fun genericError(l: SpokenLanguage) = if (ur(l)) "Maazrat, kuch masla ho gaya." else "Sorry, something went wrong."
    fun apiError(l: SpokenLanguage, e: ApiException) = when {
        e.type == "missing_api_key" -> if (ur(l)) "Claude ki API key set nahi hai, Settings mein daalein." else "The Claude API key is not set; add it in Settings."
        e.status == 401 || e.status == 403 -> if (ur(l)) "Claude API key ghalat lag rahi hai." else "The Claude API key looks invalid."
        else -> if (ur(l)) "Claude se raabta nahi ho saka." else "I couldn't reach Claude."
    }
}
