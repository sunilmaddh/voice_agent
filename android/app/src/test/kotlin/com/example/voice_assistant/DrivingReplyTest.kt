package com.example.voice_assistant

import com.example.voice_assistant.communications.DrivingReply
import org.junit.Assert.*
import org.junit.Test

class DrivingReplyTest {
    @Test fun personalReplyPreservesEnglishAndHindi() {
        assertEquals("I'm driving. I'll call after 6.", DrivingReply.validate("  I'm driving. I'll call after 6.  "))
        assertEquals("मैं गाड़ी चला रहा हूँ। बाद में कॉल करूँगा।", DrivingReply.validate("मैं गाड़ी चला रहा हूँ। बाद में कॉल करूँगा।"))
    }
    @Test fun invalidRepliesCannotBeSavedOrSent() {
        for (text in listOf("", "   ", "x".repeat(71), "Hello\nworld", "Hello\u0000")) {
            try { DrivingReply.validate(text); fail("Accepted invalid reply") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
