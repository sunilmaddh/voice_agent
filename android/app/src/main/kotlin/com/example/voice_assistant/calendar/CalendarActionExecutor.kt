package com.example.voice_assistant.calendar

import com.example.voice_assistant.actions.ActionExecutor
import com.example.voice_assistant.intent.CommandValidator
import com.example.voice_assistant.native_bridge.contracts.*
import java.text.SimpleDateFormat
import java.util.*

interface ReminderScheduler { suspend fun schedule(title: String, dueEpochMs: Long, timeZoneId: String): ActionResult }
interface CalendarLaunchPort {
    fun restriction(): ActionResult?
    fun openCalendar(): ActionResult
    fun createEvent(title: String, start: Long, end: Long, zone: String): ActionResult
}

class CalendarActionExecutor(private val calendar: CalendarService, private val reminders: ReminderScheduler,
    private val launcher: CalendarLaunchPort, private val now: () -> Long = System::currentTimeMillis,
    private val readRestriction: (() -> ActionResult?)? = null) : ActionExecutor {
    override suspend fun execute(action: AssistantAction): ActionResult {
        if (action.name != action.intent.kind.wireName || CommandValidator(now).validate(action.intent) !is CapabilityResult.Available)
            return ActionResult(ActionStatus.failed, "INVALID_CALENDAR_ACTION", "Please give a clear calendar command with a valid date and time.")
        fun restriction() = if (action.intent.kind == IntentKind.queryCalendar && readRestriction != null) readRestriction.invoke() else launcher.restriction()
        restriction()?.let { return it }
        val p = action.intent.parameters.associate { it.name to it.value }
        fun time(key: String) = (p[key] as Number).toLong()
        return when (action.intent.kind) {
            IntentKind.openCalendar -> launcher.openCalendar()
            IntentKind.createCalendarEvent -> launcher.createEvent(p["title"] as String, time("startEpochMs"), time("endEpochMs"), p["timeZoneId"] as String)
            IntentKind.createReminder -> reminders.schedule(p["title"] as String, time("dueEpochMs"), p["timeZoneId"] as String)
            IntentKind.queryCalendar -> when (val result = calendar.meetings(time("startEpochMs"), time("endEpochMs"))) {
                is CapabilityResult.Unavailable -> ActionResult(ActionStatus.unsupported, result.code, when (result.code) {
                    "CALENDAR_PERMISSION_REQUIRED" -> "Enable calendar access in Aiko Settings, then try again."
                    "GOOGLE_CALENDAR_NOT_SYNCED" -> "No visible synced Google calendar was found. Add your Google account and enable Calendar sync in Android settings."
                    else -> "Calendar could not be read. Check calendar sync and permissions, then try again."
                })
                is CapabilityResult.Available -> {
                    // Permission/activity state can change while the provider query is running.
                    restriction()?.let { return it }
                    ActionResult(ActionStatus.succeeded, "CALENDAR_READ", describe(result.value, p["timeZoneId"] as String))
                }
            }
            else -> ActionResult(ActionStatus.unsupported, "ACTION_NOT_IMPLEMENTED", "Unsupported calendar action.")
        }
    }
    companion object {
        fun describe(events: List<CalendarMeeting>, zone: String): String {
            if (events.isEmpty()) return "No events were found in your synced Google calendars for that period."
            fun format(ms: Long, allDay: Boolean) = SimpleDateFormat(if (allDay) "EEE, MMM d, yyyy" else "EEE, MMM d, yyyy 'at' h:mm a z", Locale.US).apply {
                timeZone = TimeZone.getTimeZone(if (allDay) "UTC" else zone)
            }.format(Date(ms))
            fun clean(value: String, limit: Int) = value.replace(Regex("\\s+"), " ").take(limit)
            return events.take(5).joinToString(" ") { e ->
                "${clean(e.title.ifBlank { "Untitled event" }, 120)}. " +
                    (if (e.allDay) "All day, ${format(e.startEpochMs, true)} through ${format(e.endEpochMs - 1, true)}. "
                     else "${format(e.startEpochMs, false)} until ${format(e.endEpochMs, false)}. ") +
                    (if (e.location.isNotBlank()) "Location: ${clean(e.location, 100)}. " else "") +
                    (if (e.description.isNotBlank()) "Details: ${clean(e.description, 160)}. " else "")
            } + if (events.size > 5) " More events exist. Open Calendar for the complete list." else ""
        }
    }
}
