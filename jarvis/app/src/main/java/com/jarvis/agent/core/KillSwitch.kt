package com.jarvis.agent.core

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Global emergency stop. Triggered by volume-down x3, the notification STOP button, a long-press on the
 * floating bubble or the in-app button. Every running component registers a listener and must stop
 * immediately (cancel agent jobs, stop speech, deny pending confirmations, stop the service).
 */
object KillSwitch {
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    @Volatile
    var lastTriggerAt: Long = 0L
        private set

    fun addListener(l: (String) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (String) -> Unit) {
        listeners.remove(l)
    }

    fun trigger(reason: String) {
        lastTriggerAt = System.currentTimeMillis()
        for (l in listeners) runCatching { l(reason) }
    }
}

/**
 * Detects N presses of a key within a time window (pure logic so it can be unit tested).
 * Used for "volume down three times = stop everything".
 */
class MultiPressDetector(
    private val presses: Int = 3,
    private val windowMs: Long = 1500L,
) {
    private val times = ArrayDeque<Long>()

    /** Records a press at [now]; returns true when the pattern completes. */
    fun onPress(now: Long): Boolean {
        times.addLast(now)
        while (times.isNotEmpty() && now - times.first() > windowMs) times.removeFirst()
        if (times.size >= presses) {
            times.clear()
            return true
        }
        return false
    }
}
