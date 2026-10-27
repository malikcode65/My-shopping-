package com.jarvis.agent.brain

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Sliding-window limiter for model requests (protects the user's bill from runaway loops). */
class RateLimiter(
    private val maxPerWindow: () -> Int,
    private val windowMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val stamps = ArrayDeque<Long>()
    private val mutex = Mutex()

    /** Milliseconds to wait before another request is allowed (0 = now). Pure, for tests. */
    fun delayNeeded(now: Long): Long {
        while (stamps.isNotEmpty() && now - stamps.first() >= windowMs) stamps.removeFirst()
        return if (stamps.size < maxPerWindow()) 0L else windowMs - (now - stamps.first())
    }

    fun record(now: Long) {
        stamps.addLast(now)
    }

    /** Waits for a slot. Throws if the wait would be longer than [maxWaitMs]. */
    suspend fun acquire(maxWaitMs: Long = 30_000L) {
        mutex.withLock {
            val wait = delayNeeded(clock())
            if (wait > maxWaitMs) throw RateLimitedException(wait)
            if (wait > 0) delay(wait)
            record(clock())
        }
    }
}

class RateLimitedException(val waitMs: Long) : Exception("Rate limit: wait ${waitMs / 1000}s")
