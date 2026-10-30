package com.example.voice_assistant

import com.example.voice_assistant.communications.*
import org.junit.Assert.*
import org.junit.Test

class CallReplyAccessTest {
    @Test fun explicitVoiceReplyWorksLockedAndUnlockedButManualReplyStillRequiresUnlock() {
        assertTrue(CallReplyAccess.permitted(true, false, false, false, voiceRequested = true))
        assertTrue(CallReplyAccess.permitted(false, false, false, false, voiceRequested = true))
        assertFalse(CallReplyAccess.permitted(true, false, false, false))
    }
    @Test fun onlyEnabledAutomaticDrivingRepliesBypassLock() {
        for (locked in listOf(false, true)) for (driving in listOf(false, true))
            for (enabled in listOf(false, true)) for (automatic in listOf(false, true)) {
                assertEquals(if (driving) enabled && automatic else !locked,
                    CallReplyAccess.permitted(locked, driving, enabled, automatic))
            }
    }
    @Test fun lockingDuringDrivingSendStillAllowsOneDeclineAfterSuccess() {
        var locked = false
        var declines = 0
        val completion = SmsReplyCompletion({ CallReplyAccess.permitted(locked, true, true, true) }, { declines++ })
        locked = true
        assertEquals("CALL_REPLY_SMS_SENT_DECLINE_REQUESTED", completion.complete(true))
        completion.complete(true)
        assertEquals(1, declines)
    }
    @Test fun turningOffAutomaticReplyBeforeReceiptPreventsDecline() {
        var automatic = true
        var declines = 0
        val completion = SmsReplyCompletion({ CallReplyAccess.permitted(true, true, true, automatic) }, { declines++ })
        automatic = false
        assertEquals("CALL_REPLY_SMS_SENT_CALL_UNCHANGED", completion.complete(true))
        assertEquals(0, declines)
    }
}
