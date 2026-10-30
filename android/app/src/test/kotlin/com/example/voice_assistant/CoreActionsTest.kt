package com.example.voice_assistant

import com.example.voice_assistant.actions.*
import com.example.voice_assistant.intent.*
import com.example.voice_assistant.native_bridge.contracts.*
import com.example.voice_assistant.voice.SpeechCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CoreActionsTest {
    private class Port : SmsComposePort {
        var number: String? = null; var body: String? = null; var calls = 0
        var available = true; var blocked: ActionResult? = null
        override fun restriction() = blocked
        override fun open(number: String?, body: String): Boolean { calls++; this.number = number; this.body = body; return available }
    }
    private fun message(recipient: String) = LocalIntentRouter().decide("Send SMS to $recipient saying Hello").proposedAction!!
    @Test fun exactPocMessageBypassesModelAndRequiresRecipientReview() = runBlocking {
        val route = LocalIntentRouter().decide("Tell Rahul I'll call him after the meeting.")
        assertFalse(route.modelRequired); assertEquals(IntentKind.sendMessage, route.intent.kind)
        val port = Port()
        val result = ActionReportingExecutor(SmsComposeExecutor(port)).execute(route.proposedAction!!)
        assertNull(port.number); assertEquals("I'll call him after the meeting", port.body)
        assertEquals(ActionStatus.requiresConfirmation, result.status)
        assertEquals(false, result.evidence["success"])
        assertEquals("HANDOFF_ONLY", result.evidence["outcome"])
        assertEquals(true, result.evidence["userInteractionRequired"])
        assertEquals("USER_SELECTION_REQUIRED", result.evidence["recipientResolution"])
    }
    @Test fun explicitPhoneIsPrefilledWithoutUriInjection() = runBlocking {
        val port = Port(); val executor = SmsComposeExecutor(port)
        executor.execute(message("+91 98765 43210"))
        assertEquals("+919876543210", port.number)
        executor.execute(message("123?body=override"))
        assertNull(port.number)
        assertEquals("Hello", port.body)
    }
    @Test fun unavailableLockedAndInvalidCommandsNeverClaimSent() = runBlocking {
        val port = Port(); val executor = ActionReportingExecutor(SmsComposeExecutor(port))
        port.available = false
        assertEquals("SMS_APP_UNAVAILABLE", executor.execute(message("Rahul")).evidence["error"])
        port.blocked = ActionResult(ActionStatus.requiresConfirmation, "UNLOCK_REQUIRED", "Unlock")
        assertEquals("UNLOCK_REQUIRED", executor.execute(message("Rahul")).code)
        val invalid = message("Rahul").copy(name = "NAVIGATE")
        assertEquals("INVALID_ACTION", executor.execute(invalid).code)
        assertEquals(1, port.calls)
    }
    @Test fun allCoreResultsContainEvidenceAndPreserveParameters() = runBlocking {
        val commands = listOf(TypedCommand.Navigate("Bangalore Airport"), TypedCommand.GoogleSearch("restaurants near MG Road"),
            TypedCommand.CreateReminder("call Rahul", System.currentTimeMillis() + 86400000, "UTC"),
            TypedCommand.QueryCalendar(2000000000000, 2000086400000, "UTC"))
        val delegate = object : ActionExecutor {
            override suspend fun execute(action: AssistantAction) = ActionResult(ActionStatus.failed, "TEST_DEVICE_UNAVAILABLE", "No device in this unit test.")
        }
        for (command in commands) {
            val result = ActionReportingExecutor(delegate).execute(AssistantAction(command.kind.wireName, command.toIntent(.99)))
            assertEquals(command.kind.wireName, result.evidence["actionType"])
            assertEquals(command.parameters.associate { it.name to it.value }, result.evidence["parameters"])
            assertNotNull(result.evidence["androidMechanism"])
            assertEquals(false, result.evidence["success"])
            assertEquals(command.kind != IntentKind.googleSearch, result.evidence["unlockRequired"])
            assertEquals("TEST_DEVICE_UNAVAILABLE", result.evidence["error"])
        }
    }
    @Test fun smsVoicePipelineAcknowledgesBeforeHandoffAndMeasuresPlayback() = runBlocking {
        val tts = SpeechCoordinatorTest.Tts(); val focus = SpeechCoordinatorTest.Focus(); val port = Port()
        val delegate = object : ActionExecutor {
            override suspend fun execute(action: AssistantAction): ActionResult {
                assertTrue(focus.released); assertTrue(tts.spoken!!.contains("SMS draft"))
                return ActionReportingExecutor(SmsComposeExecutor(port)).execute(action)
            }
        }
        val result = SpeechCoordinator(SpeechCoordinatorTest.Stt().apply { transcript = "Tell Rahul I'll call him after the meeting" }, tts, focus, executor = delegate) {}
            .run(VoiceRequest("req-1-1"), false)
        assertEquals("SMS_COMPOSER_OPENED", result.result.code)
        assertEquals(false, result.routing["actionExecuted"])
        assertEquals(true, result.routing["actionAttempted"])
        assertEquals("measured", result.metrics.first { it.stage == "ttsPlayback" }.status)
    }
}
