package com.example.voice_assistant

import com.example.voice_assistant.communications.*
import com.example.voice_assistant.intent.*
import com.example.voice_assistant.native_bridge.contracts.*
import org.junit.Assert.*
import org.junit.Test

class IncomingCallReplyTest {
    private class Port : IncomingReplyPort {
        var selected = IncomingReplyTarget("call-a", "+919876543210", true, true, true)
        var dialer = true; var unlocked = true; var throws = false; var calls = 0
        var blocked: String? = null
        override fun restriction(id: String) = blocked
        var sentText: String? = null
        override fun defaultDialer() = dialer
        override fun unlocked() = unlocked
        override fun target(id: String) = selected.takeIf { it.id == id }
        override fun sendReply(id: String, text: String) {
            calls++; sentText = text
            if (throws) throw SecurityException()
        }
    }
    @Test fun requiresExactExplicitRouting() {
        val router = LocalIntentRouter()
        for (phrase in listOf("reply to caller saying I will call you later", "decline call and text I will call you later"))
            assertEquals(IntentKind.replyToCall, router.decide(phrase).proposedAction!!.intent.kind)
        for (phrase in listOf("I will call you later", "do not reply to caller saying I will call you later",
                "reply to caller saying send money", "decline call and text I will call you later then call Rahul"))
            assertNull(router.decide(phrase).proposedAction)
    }
    @Test fun hiddenNumbersShortCodesAndUnsupportedCallsAreBlocked() {
        val port = Port(); val action = IncomingCallReply(port)
        val base = port.selected
        for (target in listOf(base.copy(addressAllowed = false), base.copy(address = ""),
            base.copy(address = "BANK"), base.copy(address = "12345"), base.copy(address = "1234567;7654321"),
            base.copy(supportsText = false), base.copy(ringing = false))) {
            port.selected = target; assertNull(action.prepare("call-a"))
        }
        assertEquals(0, port.calls)
    }
    @Test fun preparationDoesNotSendAndConfirmationIsSingleUse() {
        val port = Port(); val action = IncomingCallReply(port)
        val review = action.prepare("call-a")!!
        assertEquals(0, port.calls)
        assertEquals("CALL_REPLY_SMS_PENDING", action.confirm(review))
        assertEquals("I will call you later.", port.sentText)
        assertEquals("CALL_REPLY_REVIEW_REQUIRED", action.confirm(review))
        assertNull(action.prepare("call-a"))
        assertEquals(1, port.calls)
    }
    @Test fun callIdentityAndStateAreRecheckedWithoutRetargeting() {
        val port = Port(); val action = IncomingCallReply(port)
        val original = port.selected
        for (changed in listOf(original.copy(id = "call-b"), original.copy(address = "+919999999999"),
            original.copy(ringing = false), original.copy(supportsText = false))) {
            port.selected = original
            val review = action.prepare("call-a")!!
            port.selected = changed
            assertEquals("CALL_REPLY_TARGET_CHANGED", action.confirm(review))
        }
        assertEquals(0, port.calls)
    }
    @Test fun expiryLockAndRoleLossInvalidateReview() {
        val port = Port(); var clock = 0L
        val action = IncomingCallReply(port) { clock }
        var review = action.prepare("call-a")!!
        clock = 60000
        assertEquals("CALL_REPLY_EXPIRED_OR_RESTRICTED", action.confirm(review))
        review = action.prepare("call-a")!!; port.unlocked = false
        assertEquals("CALL_REPLY_EXPIRED_OR_RESTRICTED", action.confirm(review))
        assertNull(action.prepare("call-a")); port.unlocked = true
        review = action.prepare("call-a")!!; port.dialer = false
        assertEquals("CALL_REPLY_EXPIRED_OR_RESTRICTED", action.confirm(review))
        assertNull(action.prepare("call-a")); assertEquals(0, port.calls)
    }
    @Test fun cancellationAndFailureDoNotRetry() {
        val port = Port(); val action = IncomingCallReply(port)
        val cancelled = action.prepare("call-a")!!
        action.cancel()
        assertEquals("CALL_REPLY_REVIEW_REQUIRED", action.confirm(cancelled))
        port.throws = true
        assertEquals("CALL_REPLY_OUTCOME_UNKNOWN", action.confirm(action.prepare("call-a")!!))
        assertNull(action.prepare("call-a")); assertEquals(1, port.calls)
    }
    @Test fun onlyExplicitFinalSpeechCanSendTheReviewedReply() {
        val port = Port(); val action = IncomingCallReply(port)
        val review = action.prepare("call-a")!!
        assertTrue(action.isCurrent(review))
        for (text in listOf("", "yes", "don't send reply", "send reply later", "send reply and call Rahul"))
            assertEquals("CALL_REPLY_NOT_CONFIRMED", action.confirmSpoken(review, text, true))
        assertEquals("CALL_REPLY_NOT_CONFIRMED", action.confirmSpoken(review, "send reply", false))
        assertEquals(0, port.calls)
        assertEquals("CALL_REPLY_SMS_PENDING", action.confirmSpoken(review, "Send reply.", true))
        assertFalse(action.isCurrent(review))
        assertEquals("CALL_REPLY_REVIEW_REQUIRED", action.confirmSpoken(review, "send reply", true))
        assertEquals(1, port.calls)
    }
    @Test fun spokenCancelAndCallStateChangesPreventLaterSpokenSend() {
        val port = Port(); val action = IncomingCallReply(port)
        var review = action.prepare("call-a")!!
        assertEquals("CALL_REPLY_CANCELLED", action.confirmSpoken(review, "cancel reply", true))
        assertEquals("CALL_REPLY_REVIEW_REQUIRED", action.confirmSpoken(review, "send reply", true))
        review = action.prepare("call-a")!!
        port.selected = port.selected.copy(ringing = false)
        assertFalse(action.isCurrent(review))
        assertEquals("CALL_REPLY_TARGET_CHANGED", action.confirmSpoken(review, "send reply", true))
        assertEquals(0, port.calls)
    }
    @Test fun permissionOrSimFailurePreventsSmsSubmission() {
        val port = Port(); val action = IncomingCallReply(port)
        for (reason in listOf("CALL_REPLY_SEND_SMS_PERMISSION_REQUIRED", "CALL_REPLY_SIM_UNAVAILABLE")) {
            val review = action.prepare("call-a")!!
            port.blocked = reason
            assertEquals(reason, action.confirmSpoken(review, "send reply", true))
            assertEquals(0, port.calls)
        }
    }
}
