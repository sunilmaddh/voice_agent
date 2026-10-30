package com.example.voice_assistant

import com.example.voice_assistant.intent.*
import com.example.voice_assistant.native_bridge.contracts.*
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class IntentRouterTest {
    private fun epoch(value: String): Long = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.parse(value)!!.time
    private val now = epoch("2026-09-28 04:00")
    private val router = LocalIntentRouter({ now }, { TimeZone.getTimeZone("Asia/Kolkata") })

    @Test fun allRequiredIntentsHaveStableUniqueWireNames() {
        assertEquals(setOf("DRIVING_MODE_ON", "DRIVING_MODE_OFF", "REPLY_TO_CALL", "END_CALL", "CALL_PHONE", "NAVIGATE", "GOOGLE_SEARCH", "CREATE_REMINDER", "SEND_MESSAGE", "QUERY_CALENDAR", "CREATE_CALENDAR_EVENT",
            "OPEN_MAPS", "OPEN_CALENDAR", "STOP_NAVIGATION", "READ_NOTIFICATION", "READ_WHATSAPP", "REPLY_WHATSAPP",
            "READ_SMS", "REPLY_SMS", "READ_EMAIL", "REPLY_EMAIL", "DOCUMENT_QUERY", "GENERAL_QUERY", "UNKNOWN"), IntentKind.entries.map { it.wireName }.toSet())
    }
    @Test fun explicitCommandsAlwaysBypassModels() {
        val examples = mapOf(" Please OPEN Maps! " to IntentKind.openMaps, "open google calendar" to IntentKind.openCalendar,
            "stop navigation" to IntentKind.stopNavigation, "read notifications" to IntentKind.readNotification,
            "read my latest WhatsApp message" to IntentKind.readWhatsApp, "read my latest email" to IntentKind.readEmail,
            "Navigate to Bangalore Airport." to IntentKind.navigate, "Search Google for restaurants near MG Road." to IntentKind.googleSearch)
        for ((input, kind) in examples) {
            val route = router.decide(input)
            assertEquals(input, kind, route.intent.kind)
            assertEquals(RoutingTier.deterministic, route.tier)
            assertFalse(route.modelRequired); assertNull(route.modelId)
            assertEquals(RoutingStatus.PROPOSAL_ONLY, route.status)
            assertNotNull(route.proposedAction)
            assertEquals(false, route.diagnostics()["actionExecuted"])
        }
        assertEquals("Bangalore Airport", (router.decide("Navigate to Bangalore Airport").command as TypedCommand.Navigate).destination)
    }
    @Test fun takeMeToRoutesLocallyButAmbiguousVariantsRemainBlocked() {
        val route = router.decide("take me to bangalore airport.")
        assertEquals(RoutingTier.deterministic, route.tier)
        assertEquals("bangalore airport", (route.command as TypedCommand.Navigate).destination)
        assertFalse(route.modelRequired)
        assertEquals(RoutingStatus.PROPOSAL_ONLY, route.status)
        for (input in listOf("take me to there", "do not take me to airport",
            "take me to airport and take me to station", "take me to airport or take me to station")) {
            assertEquals(input, RoutingStatus.CLARIFICATION_REQUIRED, router.decide(input).status)
            assertNull(router.decide(input).proposedAction)
        }
    }
    @Test fun politeCommandsBypassInferenceWithoutBypassingValidation() {
        for (text in listOf("Can you open maps?", "Could you please open maps?", "Please would you open maps!")) {
            val result = router.decide(text)
            assertEquals(IntentKind.openMaps, result.intent.kind)
            assertEquals(RoutingTier.deterministic, result.tier)
            assertFalse(result.modelRequired)
            assertNotNull(result.proposedAction)
        }
        for (text in listOf("Could you please do not open maps?", "Can you open maps and read notifications?",
            "Would you navigate to there?", "Could you remind me tomorrow to call Rahul?")) {
            assertNull(text, router.decide(text).proposedAction)
        }
    }
    @Test fun structuredRemindersAndCalendarUseExplicitLocalDates() {
        val reminder = router.decide("Remind me tomorrow at 10 AM to call Rahul").command as TypedCommand.CreateReminder
        assertEquals("call Rahul", reminder.title)
        assertEquals(epoch("2026-09-29 04:30"), reminder.dueEpochMs)
        assertEquals("Asia/Kolkata", reminder.timeZoneId)
        val calendar = router.decide("What meetings do I have tomorrow?").command as TypedCommand.QueryCalendar
        assertEquals(epoch("2026-09-28 18:30"), calendar.startEpochMs)
        assertEquals(epoch("2026-09-29 18:30"), calendar.endEpochMs)
    }
    @Test fun invalidPastAndAmbiguousTimesHaveNoAction() {
        for (text in listOf("Remind me today at 8 AM to call Rahul", "Remind me tomorrow at 10 to call Rahul",
            "Remind me tomorrow at 25:00 to call Rahul", "Remind me 2026-02-30 at 10 AM to call Rahul")) {
            val result = router.decide(text)
            assertNull(text, result.proposedAction)
            assertEquals(RoutingStatus.CLARIFICATION_REQUIRED, result.status)
        }
    }
    @Test fun dstGapAndOverlapAreNotGuessed() {
        val parser = CommandTimeParser({ epoch("2026-01-01 00:00") }, TimeZone.getTimeZone("America/New_York"))
        assertNull(parser.reminder("2026-03-08", "02:30"))
        assertNull(parser.reminder("2026-11-01", "01:30"))
        val range = parser.calendarRange("2026-03-08")!!
        assertEquals(23 * 3600000L, range.second - range.first)
    }
    @Test fun communicationsRequireResolvedContextAndConfirmation() {
        for ((text, kind) in mapOf("Reply WhatsApp that I will join at four" to IntentKind.replyWhatsApp)) {
            val result = router.decide(text)
            assertEquals(kind, result.intent.kind)
            assertEquals(RoutingStatus.PROPOSAL_ONLY, result.status)
            assertNotNull(result.proposedAction); assertFalse(result.modelRequired)
        }
    }
    @Test fun ambiguousNegatedCompoundAndUnknownInputsHaveNoAction() {
        for (text in listOf("don't open maps", "if I leave open maps", "open maps and send sms to Rahul saying hi",
            "open maps; read email", "navigate to there", "reply that yes", "navigate to", "delete all emails",
            "\"open maps\"", "", "open\nmaps", "x".repeat(2001))) {
            assertNull(text, router.decide(text).proposedAction)
        }
    }
    @Test fun tierTwoAndThreeAreExplicitlyDeferredWithoutInventedModels() {
        val command = router.decide("remind me next Friday to call Rahul")
        val document = router.decide("Explain this document")
        val general = router.decide("Why is the sky blue?")
        assertEquals(RoutingTier.commandModel, command.tier)
        assertEquals(IntentKind.unknown, command.intent.kind)
        assertEquals(RoutingTier.generalModel, document.tier)
        assertEquals(IntentKind.documentQuery, document.intent.kind)
        assertEquals(IntentKind.generalQuery, general.intent.kind)
        for (route in listOf(command, document, general)) {
            assertTrue(route.modelRequired); assertNull(route.modelId); assertNull(route.proposedAction)
            assertEquals(RoutingStatus.MODEL_NOT_CONFIGURED, route.status)
            assertEquals("NOT_RUN", route.diagnostics()["modelExecutionMode"])
        }
    }
    @Test fun validatorRejectsLowConfidenceMissingExtraAndWrongParameters() {
        val validator = CommandValidator { now }
        val candidates = listOf(TypedCommand.Navigate("Airport").toIntent(.5),
            AssistantIntent(IntentKind.navigate, emptyList(), .99),
            AssistantIntent(IntentKind.navigate, listOf(IntentParameter("destination", ParameterType.number, 42)), .99),
            AssistantIntent(IntentKind.openMaps, listOf(IntentParameter("execute", ParameterType.boolean, true)), .99),
            TypedCommand.ReadNotification().toIntent(0.0), TypedCommand.Unknown.toIntent(1.0),
            TypedCommand.CreateReminder("call", now - 1, "Asia/Kolkata").toIntent(.99))
        for (candidate in candidates) assertTrue(validator.validate(candidate) is CapabilityResult.Unavailable)
    }
}
