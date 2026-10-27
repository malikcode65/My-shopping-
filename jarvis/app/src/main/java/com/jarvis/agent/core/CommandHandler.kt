package com.jarvis.agent.core

/** Where a task came from. Routines run with nobody watching, so confirmations behave differently. */
enum class TaskOrigin { VOICE, TYPED, ROUTINE }

/** Receives a user command (already transcribed) and returns the final one-sentence reply. */
fun interface CommandHandler {
    suspend fun handle(text: String, origin: TaskOrigin): String
}
