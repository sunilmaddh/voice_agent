package com.example.voice_assistant

import com.example.voice_assistant.actions.*
import com.example.voice_assistant.intent.LocalIntentRouter
import com.example.voice_assistant.native_bridge.contracts.*
import com.example.voice_assistant.lockscreen.SessionActionExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EndCallTest {
    @Test fun selectsOnlyOneLiveCallWithoutGuessingBetweenConversations() {
        assertEquals("active", singleHangupTarget(listOf("active" to android.telecom.Call.STATE_ACTIVE,
            "old" to android.telecom.Call.STATE_DISCONNECTED)))
        assertEquals("ring", singleHangupTarget(listOf("ring" to android.telecom.Call.STATE_RINGING)))
        assertNull(singleHangupTarget(listOf("active" to android.telecom.Call.STATE_ACTIVE,
            "waiting" to android.telecom.Call.STATE_RINGING)))
        assertNull(singleHangupTarget(listOf("ending" to android.telecom.Call.STATE_DISCONNECTING)))
    }
    @Test fun reportsNativeMechanismWithoutClaimingCallEnded() = runBlocking {
        val result = EndCallExecutor(object : EndCallPort {
            override val mechanism = "Call.disconnect"
            override fun restriction(): String? = null
            override fun end() = true
        }).execute(action)
        assertEquals("Call.disconnect", result.evidence["androidMechanism"])
        assertEquals(false, result.evidence["deprecatedApi"])
        assertEquals(false, result.evidence["disconnectObserved"])
    }
    private val action get() = LocalIntentRouter().decide("end the call").proposedAction!!
    @Test fun deterministicAndUnambiguousOnly() {
        for (input in listOf("hang up", "cut the call", "end my call", "disconnect the call"))
            assertEquals(IntentKind.endCall, LocalIntentRouter().decide(input).proposedAction!!.intent.kind)
        for (input in listOf("don't end the call", "maybe hang up", "end the call and then call Rahul"))
            assertNull(LocalIntentRouter().decide(input).proposedAction)
    }
    @Test fun restrictionsPreventDispatchAndFalseIsNotSuccess() = runBlocking {
        var blocked: String? = "VOICE_HANGUP_DISABLED"
        var calls = 0
        val executor = EndCallExecutor(object : EndCallPort {
            override fun restriction() = blocked
            override fun end(): Boolean { calls++; return false }
        })
        assertEquals("VOICE_HANGUP_DISABLED", executor.execute(action).code)
        assertEquals(0, calls)
        blocked = null
        assertEquals(ActionStatus.unsupported, executor.execute(action).status)
        assertEquals(1, calls)
    }
    @Test fun acceptedResultDoesNotClaimObservedDisconnection() = runBlocking {
        val executor = EndCallExecutor(object : EndCallPort {
            override fun restriction(): String? = null
            override fun end() = true
        })
        val result = executor.execute(action)
        assertEquals("CALL_END_ACCEPTED", result.code)
        assertEquals(false, result.evidence["disconnectObserved"])
    }
    @Test fun permissionDeniedIsReportedWithoutRetry() = runBlocking {
        var calls = 0
        val executor = EndCallExecutor(object : EndCallPort {
            override fun restriction(): String? = null
            override fun end(): Boolean { calls++; throw SecurityException() }
        })
        assertEquals("CALL_END_PERMISSION_DENIED", executor.execute(action).code)
        assertEquals(1, calls)
    }
    @Test fun lockedOptInIsIndependentOfOutgoingCalls() = runBlocking {
        var enabled = false
        var calls = 0
        val gate = SessionActionExecutor(object : ActionExecutor {
            override suspend fun execute(action: AssistantAction): ActionResult {
                calls++; return ActionResult(ActionStatus.succeeded, "ACCEPTED", "Accepted")
            }
        }, { true }, { false }, handsFreeCalls = { true }, voiceHangup = { enabled })
        assertEquals("UNLOCK_REQUIRED", gate.execute(action).code)
        enabled = true
        assertEquals("ACCEPTED", gate.execute(action).code)
        enabled = false
        assertEquals("UNLOCK_REQUIRED", gate.execute(action).code)
        assertEquals(1, calls)
    }
}
