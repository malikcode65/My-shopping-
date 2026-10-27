package com.jarvis.agent.memory

import com.jarvis.agent.brain.MemoryProvider
import com.jarvis.agent.routines.RoutineTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Long-term memory. Before every task the agent automatically receives: known facts, contact nicknames,
 * scheduled routines and the last few conversations. After every task a short summary is stored.
 */
class MemoryManager(val dao: MemoryDao) : MemoryProvider {

    override suspend fun contextFor(userText: String): String = buildString {
        val facts = dao.recentFacts(MAX_FACTS)
        if (facts.isNotEmpty()) {
            append("Facts:\n")
            facts.forEach { append("- [fact_id=").append(it.id).append("] ").append(it.text).append('\n') }
        }
        val nicknames = dao.allNicknames()
        if (nicknames.isNotEmpty()) {
            append("Contact nicknames: ")
            append(nicknames.take(60).joinToString("; ") { "\"${it.nickname}\" = ${it.contactName}" })
            append('\n')
        }
        val routines = dao.allRoutines().filter { it.enabled }
        if (routines.isNotEmpty()) {
            append("Routines: ")
            append(routines.joinToString("; ") { "${it.name} at %02d:%02d (%s)".format(it.hour, it.minute, RoutineTime.describeDays(it.daysMask)) })
            append('\n')
        }
        val recent = dao.recentConversations(RECENT_CONVERSATIONS)
        if (recent.isNotEmpty()) {
            val fmt = SimpleDateFormat("d MMM HH:mm", Locale.ENGLISH)
            append("Recent conversations (oldest first):\n")
            recent.reversed().forEach {
                append("- ").append(fmt.format(Date(it.timestamp))).append(" user: \"").append(it.userText.take(160))
                    .append("\" → you: \"").append(it.reply.take(160)).append("\"\n")
            }
        }
    }

    override suspend fun onTaskFinished(userText: String, reply: String, steps: Int) {
        dao.insertConversation(ConversationSummary(userText = userText.take(500), reply = reply.take(500), steps = steps))
        dao.pruneConversations(KEEP_CONVERSATIONS)
    }

    suspend fun resolveNickname(name: String): String? = dao.nickname(normalize(name))?.contactName

    companion object {
        const val MAX_FACTS = 60
        const val RECENT_CONVERSATIONS = 5
        const val KEEP_CONVERSATIONS = 300

        fun normalize(nickname: String) = nickname.trim().lowercase()
    }
}
