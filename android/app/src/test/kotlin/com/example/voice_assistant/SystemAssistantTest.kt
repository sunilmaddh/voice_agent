package com.example.voice_assistant

import com.example.voice_assistant.actions.ActionExecutor
import com.example.voice_assistant.intent.LocalIntentRouter
import com.example.voice_assistant.lockscreen.*
import com.example.voice_assistant.native_bridge.contracts.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SystemAssistantTest {
    @Test fun lockedCallingRequiresExplicitOptInAndDoesNotAllowOtherActions() = runBlocking {
        var enabled = false
        var requests = 0
        val gate = SessionActionExecutor(object : ActionExecutor {
            override suspend fun execute(action: AssistantAction): ActionResult {
                requests++
                return ActionResult(ActionStatus.succeeded, "CALL_REQUESTED", "Requested")
            }
        }, { true }, { false }, handsFreeCalls = { enabled })
        val call = LocalIntentRouter().decide("call Rahul").proposedAction!!
        assertEquals("UNLOCK_REQUIRED", gate.execute(call).code)
        enabled = true
        assertEquals("CALL_REQUESTED", gate.execute(call).code)
        assertEquals("UNLOCK_REQUIRED", gate.execute(LocalIntentRouter().decide("open maps").proposedAction!!).code)
        enabled = false
        assertEquals("UNLOCK_REQUIRED", gate.execute(call).code)
        assertEquals(1, requests)
    }
    @Test fun lockedReplyOptInOnlyAllowsWhatsAppAndRequiresReadoutConsent() = runBlocking {
        var replies = false
        var reads = true
        var calls = 0
        val gate = SessionActionExecutor(object : ActionExecutor {
            override suspend fun execute(action: AssistantAction): ActionResult {
                calls++
                return ActionResult(ActionStatus.requiresConfirmation, "WHATSAPP_REPLY_REVIEW", "Review")
            }
        }, { true }, { reads }, lockedReplies = { replies })
        val reply = LocalIntentRouter().decide("reply whatsapp I'll call you later").proposedAction!!
        assertEquals("UNLOCK_REQUIRED", gate.execute(reply).code)
        replies = true
        assertEquals("WHATSAPP_REPLY_REVIEW", gate.execute(reply).code)
        reads = false
        assertEquals("UNLOCK_REQUIRED", gate.execute(reply).code)
        reads = true
        assertEquals("UNLOCK_REQUIRED", gate.execute(LocalIntentRouter().decide("navigate to Bangalore Airport").proposedAction!!).code)
        assertEquals(1, calls)
    }
    @Test fun privateReadsRequireOptInButSendingAndLaunchesAlwaysRequireUnlock() {
        for (kind in SessionAccessPolicy.reads) {
            assertTrue(SessionAccessPolicy.requiresUnlock(kind, true, false))
            assertFalse(SessionAccessPolicy.requiresUnlock(kind, true, true))
        }
        for (kind in listOf(IntentKind.navigate, IntentKind.replyWhatsApp, IntentKind.replyEmail)) {
            assertTrue(SessionAccessPolicy.requiresUnlock(kind, true, true))
            assertFalse(SessionAccessPolicy.requiresUnlock(kind, false, false))
        }
        assertFalse(SessionAccessPolicy.requiresUnlock(IntentKind.createReminder, true, false))
    }
    @Test fun authHandoffPreservesExactActionAndCannotReplay() = runBlocking {
        var locked = true
        val expectedAction = LocalIntentRouter().decide("navigate to Bangalore Airport").proposedAction!!
        var calls = 0
        val gate = SessionActionExecutor(object : ActionExecutor {
            override suspend fun execute(action: AssistantAction): ActionResult {
                assertEquals(expectedAction, action); calls++
                return ActionResult(ActionStatus.succeeded, "HANDOFF", "Launched")
            }
        }, { locked }, { false })
        assertEquals("UNLOCK_REQUIRED", gate.execute(expectedAction).code)
        assertTrue(gate.hasPendingNavigation())
        assertEquals(0, calls)
        locked = false
        assertEquals("HANDOFF", gate.resumeAfterUnlock().code)
        assertFalse(gate.hasPendingNavigation())
        assertEquals("AUTHENTICATION_REQUIRED", gate.resumeAfterUnlock().code)
        assertEquals(1, calls)
    }
    @Test fun cancellationExpiryAndStillLockedNeverExecute() = runBlocking {
        var time = 0L
        var locked = true
        val gate = SessionActionExecutor(object : ActionExecutor {
            override suspend fun execute(action: AssistantAction): ActionResult = error("must not execute")
        }, { locked }, { false }, { time })
        val action = LocalIntentRouter().decide("read my latest email").proposedAction!!
        gate.execute(action); assertEquals("AUTHENTICATION_REQUIRED", gate.resumeAfterUnlock().code)
        gate.execute(action); gate.clear(); locked = false
        assertEquals("AUTHENTICATION_REQUIRED", gate.resumeAfterUnlock().code)
        locked = true; gate.execute(action); time = 120001; locked = false
        assertEquals("AUTHENTICATION_REQUIRED", gate.resumeAfterUnlock().code)
    }
    @Test fun navigationPromptExpiresAndDoesNotApplyToOtherCommands() = runBlocking {
        var time = 0L
        val gate = SessionActionExecutor(object : ActionExecutor {
            override suspend fun execute(action: AssistantAction): ActionResult = error("must not execute while locked")
        }, { true }, { false }, { time })
        gate.execute(LocalIntentRouter().decide("take me to Bangalore airport").proposedAction!!)
        assertTrue(gate.hasPendingNavigation())
        time = 120000
        assertFalse(gate.hasPendingNavigation())
        gate.execute(LocalIntentRouter().decide("open maps").proposedAction!!)
        assertTrue(gate.hasPendingNavigation())
        gate.clear()
        assertFalse(gate.hasPendingNavigation())
        gate.execute(LocalIntentRouter().decide("read my latest email").proposedAction!!)
        assertFalse(gate.hasPendingNavigation())
    }
}

