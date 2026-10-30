package com.example.voice_assistant

import com.example.voice_assistant.actions.ActionExecutor
import com.example.voice_assistant.intent.LocalIntentRouter
import com.example.voice_assistant.lockscreen.SessionActionExecutor
import com.example.voice_assistant.native_bridge.contracts.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VoiceCallReplyTest {
    @Test fun smsIsRequestedBeforePlaybackSoSlowSpeechCannotDelaySend() = runBlocking {
        val tts = SpeechCoordinatorTest.Tts()
        var requests = 0
        val executor = object : ActionExecutor {
            override suspend fun execute(action: AssistantAction): ActionResult {
                assertNull(tts.spoken)
                requests++
                return ActionResult(ActionStatus.succeeded, "CALL_REPLY_SMS_PENDING", "SMS requested.")
            }
        }
        val result = com.example.voice_assistant.voice.SpeechCoordinator(
            SpeechCoordinatorTest.Stt().apply { transcript = "cut the call and send sms I will call you later" },
            tts, SpeechCoordinatorTest.Focus(), executor = executor) {}.run(VoiceRequest("req-1-1"), false)
        assertEquals(1, requests)
        assertEquals("CALL_REPLY_SMS_PENDING", result.result.code)
        assertEquals("SMS requested.", tts.spoken)
    }
    @Test fun completeFixedReplyCommandsRouteButIncompleteAndNegatedCommandsDoNot() {
        val router = LocalIntentRouter()
        for (text in listOf("cut the call and send sms I will call you later", "Hey Aiko, cut the call and text I will call you later",
            "cut call and send a message saying I'll call you back later", "reject incoming call and send SMS I will call later",
            "Cut the call and send SMS I will call you late",
            "decline the call and text I will call you later"))
            assertEquals(IntentKind.replyToCall, router.decide(text).proposedAction!!.intent.kind)
        for (text in listOf("do not decline the call and text I will call you later", "cut the call and send money",
            "cut the call and text I will call you later then call Rahul"))
            assertNull(router.decide(text).proposedAction)
    }
    @Test fun validatedReplyReachesDelegateInBothLockStates() = runBlocking {
        for (locked in listOf(true, false)) {
            var calls = 0
            val gate = SessionActionExecutor(object : ActionExecutor {
                override suspend fun execute(action: AssistantAction): ActionResult {
                    calls++
                    return ActionResult(ActionStatus.succeeded, "CALL_REPLY_SMS_PENDING", "Requested")
                }
            }, { locked }, { false })
            val action = LocalIntentRouter().decide("decline the call and text I will call you later").proposedAction!!
            assertEquals("CALL_REPLY_SMS_PENDING", gate.execute(action).code)
            assertEquals(1, calls)
        }
    }
}
