package com.jarvis.agent.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import java.util.concurrent.ConcurrentHashMap

/** Tracks every running agent task so the kill switch can cancel all of them at once. */
class TaskTracker {
    private val active = ConcurrentHashMap.newKeySet<Job>()

    val hasActiveTasks: Boolean get() = active.any { it.isActive }

    suspend fun <T> track(block: suspend () -> T): T = coroutineScope {
        val job = coroutineContext.job
        active.add(job)
        try {
            block()
        } finally {
            active.remove(job)
        }
    }

    fun cancelAll() {
        active.toList().forEach { it.cancel() }
        active.clear()
    }
}
