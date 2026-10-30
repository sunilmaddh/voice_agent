package com.example.voice_assistant

import com.example.voice_assistant.calendar.*
import com.example.voice_assistant.intent.*
import com.example.voice_assistant.native_bridge.contracts.*
import com.example.voice_assistant.voice.SpeechCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class CalendarIntegrationTest {
    private val now = 1790553600000L // 2026-09-28 00:00 UTC
    private val router = LocalIntentRouter({ now }, { TimeZone.getTimeZone("Asia/Kolkata") })
    private class Calendar : CalendarService {
        var calls = 0
        var result: CapabilityResult<List<CalendarMeeting>> = CapabilityResult.Available(emptyList())
        override suspend fun meetings(startEpochMs: Long, endEpochMs: Long): CapabilityResult<List<CalendarMeeting>> { calls++; return result }
    }
    private class Reminders : ReminderScheduler {
        var calls = 0
        override suspend fun schedule(title: String, dueEpochMs: Long, timeZoneId: String): ActionResult {
            calls++; return ActionResult(ActionStatus.succeeded, "REMINDER_SCHEDULED_INEXACT", "Reminder saved; Android may delay it.")
        }
    }
    private class Launcher : CalendarLaunchPort {
        var blocked: ActionResult? = null
        var calls = 0
        override fun restriction() = blocked
        override fun openCalendar(): ActionResult { calls++; return ActionResult(ActionStatus.succeeded, "CALENDAR_LAUNCH_REQUESTED", "Opened.") }
        override fun createEvent(title: String, start: Long, end: Long, zone: String): ActionResult {
            calls++; return ActionResult(ActionStatus.requiresConfirmation, "CALENDAR_EDITOR_OPENED", "Save the draft in Calendar.")
        }
    }
    @Test fun calendarGrammarsProduceValidatedLocalCommands() {
        for (input in listOf("What meetings do I have tomorrow?", "Read meeting details today", "Read upcoming calendar events")) {
            val decision = router.decide(input)
            assertEquals(IntentKind.queryCalendar, decision.intent.kind)
            assertFalse(decision.modelRequired); assertNotNull(decision.proposedAction)
        }
        val event = router.decide("Create a calendar event Team review tomorrow from 4 PM to 5 PM")
        val command = event.command as TypedCommand.CreateCalendarEvent
        assertEquals("Team review", command.title)
        assertEquals(3600000L, command.endEpochMs - command.startEpochMs)
        assertNotNull(event.proposedAction)
        assertNull(router.decide("Create a calendar event Review tomorrow from 5 PM to 4 PM").proposedAction)
    }
    @Test fun singularMeetingAndOmittedPronounRouteToSameLocalDateRange() {
        val expected = router.decide("What meetings do I have tomorrow?").command
        for (input in listOf("what meeting do have tomorrow.", "What meeting do I have tomorrow?",
            "what meetings do have tomorrow", "Please what meeting do have tomorrow!")) {
            val decision = router.decide(input)
            assertEquals(input, expected, decision.command)
            assertEquals(RoutingTier.deterministic, decision.tier)
            assertFalse(decision.modelRequired); assertNull(decision.modelId)
            assertEquals("QUERY_CALENDAR", decision.proposedAction?.name)
        }
        for (input in listOf("don't what meeting do have tomorrow", "what meeting do have tomorrow and open maps",
            "what meeting do have tomorrow or today", "what meeting do have", "what meeting do they have tomorrow")) {
            assertNull(input, router.decide(input).proposedAction)
        }
    }
    @Test fun missingMeetingDateRequiresClarificationWithoutModelOrSideEffect() {
        val decision = router.decide("Remind me about my 4 PM meeting.")
        assertEquals(RoutingStatus.CLARIFICATION_REQUIRED, decision.status)
        assertTrue(decision.reason.contains("Which day"))
        assertFalse(decision.modelRequired); assertNull(decision.proposedAction)
        assertNotNull(router.decide("Remind me about my 4 PM meeting tomorrow").proposedAction)
    }
    @Test fun permissionAndSyncFailuresAreNotEmptyCalendars() = runBlocking {
        val calendar = Calendar(); val reminders = Reminders(); val launcher = Launcher()
        val executor = CalendarActionExecutor(calendar, reminders, launcher, { now })
        val action = router.decide("What meetings do I have tomorrow?").proposedAction!!
        for (code in listOf("CALENDAR_PERMISSION_REQUIRED", "GOOGLE_CALENDAR_NOT_SYNCED", "CALENDAR_QUERY_FAILED")) {
            calendar.result = CapabilityResult.Unavailable(code)
            val result = executor.execute(action)
            assertEquals(code, result.code); assertNotEquals(ActionStatus.succeeded, result.status)
        }
    }
    @Test fun descriptionsContainTimesLocationAndBoundedDetails() {
        val event = CalendarMeeting("1", "Review", now, now + 3600000, "Room A", "Bring notes")
        val description = CalendarActionExecutor.describe(List(6) { event }, "Asia/Kolkata")
        assertTrue(description.contains("5:30 AM")); assertTrue(description.contains("6:30 AM"))
        assertTrue(description.contains("Room A")); assertTrue(description.contains("Bring notes"))
        assertTrue(description.contains("More events exist"))
        val allDay = CalendarActionExecutor.describe(listOf(event.copy(allDay = true, endEpochMs = now + 86400000)), "America/Los_Angeles")
        assertTrue(allDay.contains("All day, Mon, Sep 28, 2026 through Mon, Sep 28, 2026"))
    }
    @Test fun validatedReminderSchedulesAndInvalidOneDoesNot() = runBlocking {
        val reminders = Reminders(); val launcher = Launcher()
        val executor = CalendarActionExecutor(Calendar(), reminders, launcher, { now })
        val action = router.decide("Remind me tomorrow at 10 AM to call Rahul").proposedAction!!
        assertEquals("REMINDER_SCHEDULED_INEXACT", executor.execute(action).code)
        assertEquals(1, reminders.calls)
        assertEquals("INVALID_CALENDAR_ACTION", executor.execute(action.copy(name = "OPEN_MAPS")).code)
        val past = TypedCommand.CreateReminder("Past", now - 1, "UTC").toIntent(.99)
        assertEquals("INVALID_CALENDAR_ACTION", executor.execute(AssistantAction("CREATE_REMINDER", past)).code)
        assertEquals(1, reminders.calls)
        launcher.blocked = ActionResult(ActionStatus.requiresConfirmation, "UNLOCK_REQUIRED", "Unlock.")
        assertEquals("UNLOCK_REQUIRED", executor.execute(action).code)
        assertEquals(1, reminders.calls)
    }
    @Test fun eventEditorIsNotReportedAsSaved() = runBlocking {
        val launcher = Launcher()
        val executor = CalendarActionExecutor(Calendar(), Reminders(), launcher, { now })
        val action = router.decide("Create a meeting Review tomorrow from 4 PM to 5 PM").proposedAction!!
        val result = executor.execute(action)
        assertEquals(ActionStatus.requiresConfirmation, result.status)
        assertEquals("CALENDAR_EDITOR_OPENED", result.code); assertEquals(1, launcher.calls)
    }
    @Test fun calendarResultIsSpokenAfterRead() = runBlocking {
        val tts = SpeechCoordinatorTest.Tts()
        val calendar = Calendar().apply { result = CapabilityResult.Available(listOf(CalendarMeeting("1", "Team review", now, now + 3600000))) }
        val executor = CalendarActionExecutor(calendar, Reminders(), Launcher(), { now })
        val response = SpeechCoordinator(SpeechCoordinatorTest.Stt().apply { transcript = "What meetings do I have tomorrow?" },
            tts, SpeechCoordinatorTest.Focus(), router, executor) {}.run(VoiceRequest("req-1-1"), false)
        assertEquals(1, calendar.calls); assertTrue(tts.spoken!!.contains("Team review"))
        assertEquals("CALENDAR_READ", response.result.code)
        assertEquals(true, response.routing["actionExecuted"])
        assertEquals("measured", response.metrics.first { it.stage == "action" }.status)
    }
}
