package com.jarvis.agent.tools.impl

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.jarvis.agent.brain.arr
import com.jarvis.agent.brain.int
import com.jarvis.agent.brain.long
import com.jarvis.agent.brain.str
import com.jarvis.agent.tools.BaseTool
import com.jarvis.agent.tools.RiskAssessment
import com.jarvis.agent.tools.RiskLevel
import com.jarvis.agent.tools.ToolContext
import com.jarvis.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SetAlarmTool : BaseTool(
    "set_alarm", "Set an alarm in the clock app.", RiskLevel.LOW,
    schemaBlock = {
        integer("hour", "0-23", min = 0, max = 23)
        integer("minute", "0-59", min = 0, max = 59)
        string("label", "Optional label", required = false)
        stringArray("days", "Optional repeat days, e.g. [\"monday\",\"friday\"]", required = false)
    },
) {
    override fun describe(input: JsonObject) = "Alarm at %02d:%02d".format(input.int("hour") ?: 0, input.int("minute") ?: 0)
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, input.int("hour") ?: 7)
            .putExtra(AlarmClock.EXTRA_MINUTES, input.int("minute") ?: 0)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        input.str("label")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        val days = input.arr("days")?.mapNotNull { dayNumber((it as? JsonPrimitive)?.content) }
        if (!days.isNullOrEmpty()) intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
        ctx.app.startActivity(intent)
        return ToolResult.ok("${describe(input)} set.")
    }

    private fun dayNumber(d: String?): Int? = when (d?.lowercase()?.take(3)) {
        "sun" -> Calendar.SUNDAY; "mon" -> Calendar.MONDAY; "tue" -> Calendar.TUESDAY; "wed" -> Calendar.WEDNESDAY
        "thu" -> Calendar.THURSDAY; "fri" -> Calendar.FRIDAY; "sat" -> Calendar.SATURDAY; else -> null
    }
}

class SetTimerTool : BaseTool(
    "set_timer", "Start a countdown timer.", RiskLevel.LOW,
    schemaBlock = {
        integer("seconds", "Duration in seconds", min = 1, max = 86_400)
        string("label", "Optional label", required = false)
    },
) {
    override fun describe(input: JsonObject) = "Timer ${input.int("seconds")}s"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, input.int("seconds") ?: 60)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        input.str("label")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        ctx.app.startActivity(intent)
        return ToolResult.ok("Timer started.")
    }
}

internal object Dates {
    private val formats = listOf("yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd")

    fun parse(s: String?): Date? {
        if (s.isNullOrBlank()) return null
        for (f in formats) {
            runCatching {
                val p = SimpleDateFormat(f, Locale.ENGLISH).apply { isLenient = false; timeZone = TimeZone.getDefault() }
                return p.parse(s.trim())
            }
        }
        return null
    }

    fun format(ms: Long): String = SimpleDateFormat("EEE d MMM yyyy HH:mm", Locale.ENGLISH).format(Date(ms))
}

class ReadCalendarTool : BaseTool(
    "read_calendar", "List calendar events in a date range (default: today and tomorrow).", RiskLevel.LOW, readOnly = true,
    {
        string("from", "Start, 'yyyy-MM-dd' or 'yyyy-MM-ddTHH:mm' (default now)", required = false)
        integer("days", "Number of days to include (default 2, max 31)", required = false, min = 1, max = 31)
    },
) {
    override fun describe(input: JsonObject) = "Read calendar"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        Contacts.require(ctx.app, Manifest.permission.READ_CALENDAR)
        val start = Dates.parse(input.str("from"))?.time ?: Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        }.timeInMillis
        val end = start + (input.int("days") ?: 2).coerceIn(1, 31) * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start)
            ContentUris.appendId(it, end)
        }.build()
        val out = StringBuilder()
        ctx.app.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY,
            ),
            null, null, "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val allDay = c.getInt(5) == 1
                out.append("[event_id=${c.getLong(0)}] ${Dates.format(c.getLong(2))}")
                if (!allDay) out.append(" – ").append(SimpleDateFormat("HH:mm", Locale.ENGLISH).format(Date(c.getLong(3)))) else out.append(" (all day)")
                out.append(": ").append(c.getString(1).orEmpty())
                c.getString(4)?.takeIf { it.isNotBlank() }?.let { out.append(" @ ").append(it) }
                out.append('\n')
            }
        }
        return ToolResult.untrusted("calendar", out.toString().ifEmpty { "No events in that range." })
    }
}

class AddCalendarEventTool : BaseTool(
    "add_calendar_event", "Create an event in the user's calendar (no invitations are sent).", RiskLevel.LOW,
    schemaBlock = {
        string("title", "Event title")
        string("start", "Start, 'yyyy-MM-ddTHH:mm' local time")
        integer("duration_minutes", "Duration (default 60)", required = false, min = 1, max = 10_080)
        string("location", "Optional location", required = false)
        string("notes", "Optional description", required = false)
    },
) {
    override fun describe(input: JsonObject) = "Add event '${input.str("title")}' at ${input.str("start")}"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        Contacts.require(ctx.app, Manifest.permission.WRITE_CALENDAR)
        val start = Dates.parse(input.str("start"))?.time ?: return ToolResult.error("Bad start time; use yyyy-MM-ddTHH:mm")
        val calId = primaryCalendarId(ctx) ?: return ToolResult.error("No writable calendar on this phone")
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, input.str("title"))
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, start + (input.int("duration_minutes") ?: 60) * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            input.str("location")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            input.str("notes")?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }
        val uri = ctx.app.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return ToolResult.error("Calendar insert failed")
        return ToolResult.ok("Event created (event_id=${ContentUris.parseId(uri)}) on ${Dates.format(start)}.")
    }

    private fun primaryCalendarId(ctx: ToolContext): Long? {
        ctx.app.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null,
        )?.use { c ->
            var first: Long? = null
            while (c.moveToNext()) {
                if (first == null) first = c.getLong(0)
                if (c.getInt(1) == 1) return c.getLong(0)
            }
            return first
        }
        return null
    }
}

class DeleteCalendarEventTool : BaseTool(
    "delete_calendar_event", "Delete a calendar event by event_id (from read_calendar). Deleting data is high risk.",
    RiskLevel.HIGH,
    schemaBlock = { integer("event_id", "The event_id shown by read_calendar") },
) {
    override fun describe(input: JsonObject) = "Delete calendar event ${input.long("event_id")}"

    override suspend fun assess(input: JsonObject, ctx: ToolContext): RiskAssessment {
        val id = input.long("event_id") ?: 0L
        var title = "event $id"
        runCatching {
            ctx.app.contentResolver.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
                arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART), null, null, null,
            )?.use { if (it.moveToFirst()) title = "'${it.getString(0)}' on ${Dates.format(it.getLong(1))}" }
        }
        return RiskAssessment(RiskLevel.HIGH, "Delete calendar event $title")
    }

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        Contacts.require(ctx.app, Manifest.permission.WRITE_CALENDAR)
        val id = input.long("event_id") ?: return ToolResult.error("event_id required")
        val n = ctx.app.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
        return if (n > 0) ToolResult.ok("Event deleted.") else ToolResult.error("No event with id $id")
    }
}
