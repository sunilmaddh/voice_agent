package com.example.voice_assistant

import com.example.voice_assistant.lockscreen.LockedCommandResponder
import com.example.voice_assistant.calendar.ReminderScheduler
import com.example.voice_assistant.native_bridge.contracts.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LockedCommandResponderTest {
    @Test fun drivingVoiceFallbackHandlesOnOffButNotPartialOrNegatedSpeech() = runBlocking {
        val changes = mutableListOf<Boolean>()
        val responder = LockedCommandResponder(object : ReminderScheduler {
            override suspend fun schedule(title: String, dueEpochMs: Long, timeZoneId: String) =
                ActionResult(ActionStatus.failed, "UNEXPECTED", "Unexpected")
        }, drivingMode = { on ->
            changes.add(on)
            ActionResult(ActionStatus.succeeded, "DRIVING_UPDATED", "Updated")
        })
        assertEquals("DRIVING_UPDATED", responder.respond("I am driving", true).code)
        assertEquals("DRIVING_UPDATED", responder.respond("turn off driving mode", true).code)
        responder.respond("I am driving", false)
        responder.respond("do not turn on driving mode", true)
        assertEquals(listOf(true, false), changes)
        assertFalse(com.example.voice_assistant.lockscreen.SessionAccessPolicy.requiresUnlock(IntentKind.drivingModeOn, true, false))
    }
    @Test fun wakeFallbackDispatchesOnlyCompleteFinalCallReply() = runBlocking {
        var requests = 0
        val responder = LockedCommandResponder(object : ReminderScheduler {
            override suspend fun schedule(title: String, dueEpochMs: Long, timeZoneId: String) =
                ActionResult(ActionStatus.failed, "UNEXPECTED", "Unexpected reminder")
        }, object : com.example.voice_assistant.actions.ActionExecutor {
            override suspend fun execute(action: AssistantAction): ActionResult {
                requests++
                assertEquals(IntentKind.replyToCall, action.intent.kind)
                return ActionResult(ActionStatus.succeeded, "CALL_REPLY_SMS_PENDING", "Requested")
            }
        })
        assertEquals("CALL_REPLY_SMS_PENDING", responder.respond("cut the call and send sms I will call you later", true).code)
        responder.respond("cut the call and send sms I will call you later", false)
        responder.respond("do not decline the call and text I will call you later", true)
        responder.respond("send sms to Rahul saying hello", true)
        assertEquals(1, requests)
    }
    @Test fun schedulesOnlyCompleteReminderAndPropagatesPermissionFailure() = runBlocking {
        var calls = 0
        val responder = LockedCommandResponder(object : ReminderScheduler {
            override suspend fun schedule(title: String, dueEpochMs: Long, timeZoneId: String): ActionResult {
                calls++
                assertEquals("call Rahul", title)
                assertTrue(dueEpochMs > System.currentTimeMillis())
                return ActionResult(ActionStatus.unsupported, "NOTIFICATION_PERMISSION_REQUIRED", "Enable notifications")
            }
        })
        assertEquals("NOTIFICATION_PERMISSION_REQUIRED", responder.respond("Remind me tomorrow at 10 AM to call Rahul", true).code)
        for (input in listOf("Create a reminder", "Read my WhatsApp", "Reply WhatsApp saying hello", "Navigate to Bangalore Airport", "Read my email", "What meetings do I have tomorrow?")) {
            assertEquals("UNLOCK_REQUIRED", responder.respond(input, true).code)
        }
        responder.respond("Remind me tomorrow at 10 AM to call Rahul", false)
        assertEquals(1, calls)
        assertEquals("TIME_RESPONSE_COMPLETED", responder.respond("What time is it?", true).code)
    }
}
