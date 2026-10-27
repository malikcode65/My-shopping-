package com.jarvis.agent.tools.impl

import com.jarvis.agent.brain.arr
import com.jarvis.agent.brain.bool
import com.jarvis.agent.brain.long
import com.jarvis.agent.brain.str
import com.jarvis.agent.memory.ContactNickname
import com.jarvis.agent.memory.MemoryFact
import com.jarvis.agent.memory.MemoryManager
import com.jarvis.agent.memory.Routine
import com.jarvis.agent.routines.RoutineTime
import com.jarvis.agent.safety.SensitiveInputGuard
import com.jarvis.agent.tools.BaseTool
import com.jarvis.agent.tools.RiskAssessment
import com.jarvis.agent.tools.RiskLevel
import com.jarvis.agent.tools.ToolContext
import com.jarvis.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun ToolContext.dao() = graph.memoryDb.dao()

private val secretWords = Regex("password|passcode|\\bpin\\b|\\botp\\b|cvv|پاس ?ورڈ", RegexOption.IGNORE_CASE)

class RememberTool : BaseTool(
    "remember",
    "Save a lasting fact about the user (names, relationships, preferences, addresses, habits). Never secrets.",
    RiskLevel.LOW,
    schemaBlock = {
        string("fact", "One self-contained sentence, e.g. 'User's mother is saved in contacts as Shabana Khan'")
        string("category", "person, preference, place, work, health, other", required = false)
    },
) {
    override fun describe(input: JsonObject) = "Remember: ${input.str("fact")}"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val fact = input.str("fact").orEmpty().trim()
        if (fact.isEmpty()) return ToolResult.error("Empty fact")
        if (SensitiveInputGuard.checkOutgoingText(fact) != null || secretWords.containsMatchIn(fact)) {
            return ToolResult.error("BLOCKED_SECRET: Jarvis never stores passwords, PINs, OTPs or card numbers.")
        }
        val id = ctx.dao().insertFact(MemoryFact(text = fact.take(500), category = input.str("category") ?: "general"))
        return ToolResult.ok("Saved (fact_id=$id).")
    }
}

class RecallTool : BaseTool(
    "recall", "Search long-term memory (facts and past conversations) for a word or name.", RiskLevel.LOW, readOnly = true,
    { string("query", "Word or name to search for") },
) {
    override fun describe(input: JsonObject) = "Recall ${input.str("query")}"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val q = input.str("query").orEmpty().trim()
        val facts = ctx.dao().searchFacts(q)
        val convs = ctx.dao().searchConversations(q)
        val out = buildString {
            facts.forEach { append("[fact_id=${it.id}] ${it.text}\n") }
            convs.forEach { append("[conversation] user: ${it.userText} → you: ${it.reply}\n") }
        }
        return ToolResult.ok(out.ifEmpty { "Nothing found for '$q'." })
    }
}

class ForgetTool : BaseTool(
    "forget", "Delete a remembered fact by fact_id. Deleting data needs fingerprint confirmation.", RiskLevel.HIGH,
    schemaBlock = { integer("fact_id", "fact_id from memory/recall", min = 1) },
) {
    override fun describe(input: JsonObject) = "Forget fact ${input.long("fact_id")}"
    override suspend fun assess(input: JsonObject, ctx: ToolContext): RiskAssessment {
        val f = ctx.dao().fact(input.long("fact_id") ?: 0)
        return RiskAssessment(RiskLevel.HIGH, "Delete memory", f?.text ?: "fact ${input.long("fact_id")}")
    }

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult =
        if (ctx.dao().deleteFact(input.long("fact_id") ?: 0) > 0) ToolResult.ok("Forgotten.") else ToolResult.error("No such fact")
}

class SaveNicknameTool : BaseTool(
    "save_contact_nickname",
    "Remember what the user calls a contact (e.g. 'Ammi' = contact 'Shabana Khan'), used automatically for calls/SMS.",
    RiskLevel.LOW,
    schemaBlock = {
        string("nickname", "What the user says, e.g. 'Ammi', 'boss', 'Bhai'")
        string("contact_name", "Exact contact name from search_contacts")
    },
) {
    override fun describe(input: JsonObject) = "Nickname ${input.str("nickname")} = ${input.str("contact_name")}"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val nick = MemoryManager.normalize(input.str("nickname").orEmpty())
        val name = input.str("contact_name").orEmpty().trim()
        if (nick.isEmpty() || name.isEmpty()) return ToolResult.error("nickname and contact_name required")
        ctx.dao().upsertNickname(ContactNickname(nick, name))
        return ToolResult.ok("Saved: \"$nick\" means $name.")
    }
}

class CreateRoutineTool : BaseTool(
    "create_routine",
    "Schedule a recurring task, e.g. every day at 07:00 'tell me my schedule for today'. The prompt runs " +
        "through Jarvis with the same safety rules; risky steps still need confirmation.",
    RiskLevel.MEDIUM,
    schemaBlock = {
        string("name", "Short name, e.g. 'Morning briefing'")
        string("time", "24h time HH:mm, e.g. '07:00'")
        string("prompt", "What Jarvis should do each time, written as the user's command")
        stringArray("days", "Optional days: ['monday','friday'] or ['weekdays']; omit for every day", required = false)
    },
) {
    override fun describe(input: JsonObject) = "Routine '${input.str("name")}' at ${input.str("time")}"

    override suspend fun assess(input: JsonObject, ctx: ToolContext): RiskAssessment {
        val days = days(input)
        return RiskAssessment(
            RiskLevel.MEDIUM,
            "Create routine '${input.str("name")}' at ${input.str("time")} (${RoutineTime.describeDays(days)})",
            input.str("prompt").orEmpty(),
        )
    }

    private fun days(input: JsonObject) =
        RoutineTime.maskFromDays(input.arr("days")?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList())

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val (h, m) = RoutineTime.parseTime(input.str("time").orEmpty()) ?: return ToolResult.error("time must be HH:mm")
        val r = Routine(
            name = input.str("name").orEmpty().take(80),
            prompt = input.str("prompt").orEmpty().take(1000),
            hour = h, minute = m, daysMask = days(input),
        )
        val id = ctx.dao().insertRoutine(r)
        ctx.graph.routineScheduler.schedule(r.copy(id = id))
        return ToolResult.ok("Routine created (routine_id=$id): ${r.name} at %02d:%02d, ${RoutineTime.describeDays(r.daysMask)}.".format(h, m))
    }
}

class ListRoutinesTool : BaseTool(
    "list_routines", "List scheduled routines.", RiskLevel.LOW, readOnly = true,
) {
    override fun describe(input: JsonObject) = "List routines"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val all = ctx.dao().allRoutines()
        return ToolResult.ok(
            all.joinToString("\n") {
                "[routine_id=${it.id}] ${it.name} at %02d:%02d (%s)%s: %s".format(
                    it.hour, it.minute, RoutineTime.describeDays(it.daysMask), if (it.enabled) "" else " [paused]", it.prompt,
                )
            }.ifEmpty { "No routines." },
        )
    }
}

class UpdateRoutineTool : BaseTool(
    "set_routine_enabled", "Pause or resume a routine.", RiskLevel.MEDIUM,
    schemaBlock = {
        integer("routine_id", "routine_id from list_routines", min = 1)
        boolean("enabled", "true = active, false = paused")
    },
) {
    override fun describe(input: JsonObject) = (if (input.bool("enabled") == true) "Resume" else "Pause") + " routine ${input.long("routine_id")}"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val r = ctx.dao().routine(input.long("routine_id") ?: 0) ?: return ToolResult.error("No such routine")
        val updated = r.copy(enabled = input.bool("enabled") == true)
        ctx.dao().updateRoutine(updated)
        ctx.graph.routineScheduler.schedule(updated)
        return ToolResult.ok(describe(input) + " done.")
    }
}

class DeleteRoutineTool : BaseTool(
    "delete_routine", "Delete a routine. Deleting data needs fingerprint confirmation.", RiskLevel.HIGH,
    schemaBlock = { integer("routine_id", "routine_id from list_routines", min = 1) },
) {
    override fun describe(input: JsonObject) = "Delete routine ${input.long("routine_id")}"
    override suspend fun assess(input: JsonObject, ctx: ToolContext): RiskAssessment {
        val r = ctx.dao().routine(input.long("routine_id") ?: 0)
        return RiskAssessment(RiskLevel.HIGH, "Delete routine '${r?.name ?: input.long("routine_id")}'", r?.prompt.orEmpty())
    }

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val id = input.long("routine_id") ?: 0
        ctx.graph.routineScheduler.cancel(id)
        return if (ctx.dao().deleteRoutine(id) > 0) ToolResult.ok("Routine deleted.") else ToolResult.error("No such routine")
    }
}
