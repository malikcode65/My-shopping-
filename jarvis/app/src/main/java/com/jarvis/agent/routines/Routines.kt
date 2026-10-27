package com.jarvis.agent.routines

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jarvis.agent.JarvisApp
import com.jarvis.agent.core.JarvisRuntime
import com.jarvis.agent.core.Notifications
import com.jarvis.agent.core.TaskOrigin
import com.jarvis.agent.memory.MemoryDao
import com.jarvis.agent.memory.Routine
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Pure scheduling math (unit tested). */
object RoutineTime {
    private val dayNames = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")

    /** Next time strictly after [now] that matches hour:minute and [daysMask] (0 = every day). */
    fun nextRun(now: Long, hour: Int, minute: Int, daysMask: Int, tz: TimeZone = TimeZone.getDefault()): Long {
        val cal = Calendar.getInstance(tz).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        repeat(8) {
            val dayBit = 1 shl (cal.get(Calendar.DAY_OF_WEEK) - 1)
            val dayOk = daysMask == 0 || daysMask == 0x7F || (daysMask and dayBit) != 0
            if (cal.timeInMillis > now && dayOk) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
        }
        return cal.timeInMillis
    }

    /** ["monday","fri"] → bit mask; unknown names are ignored; empty → 0 (every day). */
    fun maskFromDays(days: List<String>): Int = days.fold(0) { acc, d ->
        when (d.trim().lowercase()) {
            "weekdays", "weekday" -> return@fold acc or 0x3E
            "weekends", "weekend" -> return@fold acc or 0x41
            "daily", "everyday", "every day" -> return@fold acc or 0x7F
        }
        val idx = dayNames.indexOfFirst { it.startsWith(d.trim().lowercase().take(3)) && d.trim().length >= 3 }
        if (idx >= 0) acc or (1 shl idx) else acc
    }

    fun describeDays(mask: Int): String = when (mask) {
        0, 0x7F -> "every day"
        0x3E -> "weekdays"
        0x41 -> "weekends"
        else -> dayNames.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.joinToString(", ") { it.take(3) }
    }

    /** "07:30" / "7:30" / "7" → (7, 30); null if invalid. */
    fun parseTime(s: String): Pair<Int, Int>? {
        val m = Regex("^\\s*(\\d{1,2})(?::(\\d{2}))?\\s*$").find(s) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        return if (h in 0..23 && min in 0..59) h to min else null
    }
}

/** Keeps exactly one pending WorkManager job per enabled routine. */
class RoutineScheduler(private val context: Context, private val dao: () -> MemoryDao) {

    private fun workName(id: Long) = "routine_$id"

    fun schedule(r: Routine) {
        val wm = WorkManager.getInstance(context)
        if (!r.enabled) {
            wm.cancelUniqueWork(workName(r.id))
            return
        }
        val delay = (RoutineTime.nextRun(System.currentTimeMillis(), r.hour, r.minute, r.daysMask) - System.currentTimeMillis())
            .coerceAtLeast(0)
        val req = OneTimeWorkRequestBuilder<RoutineWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(RoutineWorker.KEY_ID to r.id))
            .addTag("routine")
            .build()
        wm.enqueueUniqueWork(workName(r.id), ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(id: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }

    suspend fun rescheduleAll() {
        dao().allRoutines().forEach { schedule(it) }
    }
}

/**
 * Runs a routine's prompt through the normal agent loop (same tools, same safety rules, same log),
 * speaks the result, posts it as a notification, then schedules the next run.
 */
class RoutineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = JarvisApp.graph
        val id = inputData.getLong(KEY_ID, -1L)
        val dao = graph.memoryDb.dao()
        val routine = dao.routine(id) ?: return Result.success()
        if (!routine.enabled) return Result.success()
        JarvisRuntime.update { it.copy(lastHeard = "⏰ ${routine.name}") }
        val reply = try {
            graph.tasks.track { graph.commandHandler.handle(routine.prompt, TaskOrigin.ROUTINE) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            "Routine stopped."
        } catch (e: Exception) {
            "Routine failed: ${e.message}"
        }
        Notifications.simpleAlert(applicationContext, (Notifications.ID_ROUTINE_BASE + id % 1000).toInt(), "Jarvis: ${routine.name}", reply)
        val updated = routine.copy(lastRunAt = System.currentTimeMillis(), lastResult = reply.take(300))
        dao.updateRoutine(updated)
        graph.routineScheduler.schedule(updated)
        return Result.success()
    }

    companion object {
        const val KEY_ID = "routine_id"
    }
}
