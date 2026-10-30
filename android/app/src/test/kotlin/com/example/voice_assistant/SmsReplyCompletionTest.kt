package com.example.voice_assistant

import com.example.voice_assistant.communications.SmsReplyCompletion
import org.junit.Assert.*
import org.junit.Test

class SmsReplyCompletionTest {
    @Test fun sendFailureNeverDeclinesAndCannotBeReplayedAsSuccess() {
        var declines = 0
        val completion = SmsReplyCompletion({ true }, { declines++ })
        assertEquals("CALL_REPLY_SMS_FAILED", completion.complete(false))
        assertEquals("CALL_REPLY_SMS_DUPLICATE_IGNORED", completion.complete(true))
        assertEquals(0, declines)
    }
    @Test fun sendsMustSucceedBeforeExactlyOneDecline() {
        var declines = 0
        val completion = SmsReplyCompletion({ true }, { declines++ })
        assertEquals(0, declines)
        assertEquals("CALL_REPLY_SMS_SENT_DECLINE_REQUESTED", completion.complete(true))
        completion.complete(true)
        assertEquals(1, declines)
    }
    @Test fun lateReceiptCannotDeclineAChangedCall() {
        var eligible = true; var declines = 0
        val completion = SmsReplyCompletion({ eligible }, { declines++ })
        eligible = false // Answered/removed call, deadline expiry, role loss or lock.
        assertEquals("CALL_REPLY_SMS_SENT_CALL_UNCHANGED", completion.complete(true))
        assertEquals(0, declines)
    }
    @Test fun declineFailureDoesNotPretendSmsFailedOrRetry() {
        var declines = 0
        val completion = SmsReplyCompletion({ true }, { declines++; throw SecurityException() })
        assertEquals("CALL_REPLY_SMS_SENT_DECLINE_FAILED", completion.complete(true))
        completion.complete(true)
        assertEquals(1, declines)
    }
}
