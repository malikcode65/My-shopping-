package com.jarvis.agent

import com.jarvis.agent.routines.RoutineTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RoutineTimeTest {
    private val tz = TimeZone.getTimeZone("Asia/Karachi")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long = Calendar.getInstance(tz).apply {
        clear(); set(y, mo - 1, d, h, mi, 0)
    }.timeInMillis

    @Test
    fun dailyLaterTodayOrTomorrow() {
        // Tue 27 Oct 2026, 06:00 → today 07:00
        assertEquals(at(2026, 10, 27, 7, 0), RoutineTime.nextRun(at(2026, 10, 27, 6, 0), 7, 0, 0, tz))
        // 07:00 exactly → tomorrow (strictly after)
        assertEquals(at(2026, 10, 28, 7, 0), RoutineTime.nextRun(at(2026, 10, 27, 7, 0), 7, 0, 0, tz))
    }

    @Test
    fun weekdaysSkipWeekend() {
        val weekdays = RoutineTime.maskFromDays(listOf("weekdays"))
        // Fri 30 Oct 2026 08:00, routine 07:00 weekdays → Mon 2 Nov
        assertEquals(at(2026, 11, 2, 7, 0), RoutineTime.nextRun(at(2026, 10, 30, 8, 0), 7, 0, weekdays, tz))
        assertEquals("weekdays", RoutineTime.describeDays(weekdays))
    }

    @Test
    fun specificDays() {
        val mask = RoutineTime.maskFromDays(listOf("monday", "Fri"))
        assertEquals("mon, fri", RoutineTime.describeDays(mask))
        // Tue 27 Oct → Fri 30 Oct
        assertEquals(at(2026, 10, 30, 21, 30), RoutineTime.nextRun(at(2026, 10, 27, 12, 0), 21, 30, mask, tz))
    }

    @Test
    fun parsesTimes() {
        assertEquals(7 to 0, RoutineTime.parseTime("7"))
        assertEquals(7 to 30, RoutineTime.parseTime("07:30"))
        assertEquals(23 to 59, RoutineTime.parseTime("23:59"))
        assertNull(RoutineTime.parseTime("24:00"))
        assertNull(RoutineTime.parseTime("7pm"))
    }
}
