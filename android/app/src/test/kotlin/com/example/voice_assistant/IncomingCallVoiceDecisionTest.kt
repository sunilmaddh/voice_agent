package com.example.voice_assistant

import com.example.voice_assistant.communications.*
import org.junit.Assert.*
import org.junit.Test

class IncomingCallVoiceDecisionTest {
    @Test fun retryIsBoundedAndDoesNotRepeatRecognizedDecisions() {
        assertTrue(IncomingCallVoiceDecision.shouldRetry("unclear", true, 0))
        assertFalse(IncomingCallVoiceDecision.shouldRetry("unclear", true, 1))
        assertFalse(IncomingCallVoiceDecision.shouldRetry("unclear", false, 0))
        for (text in listOf("accept", "cut", "cancel", "keep ringing"))
            assertFalse(IncomingCallVoiceDecision.shouldRetry(text, true, 0))
    }
    @Test fun naturalShortRepliesAndPoliteWrappersAreRecognized() {
        for (text in listOf("okay, cut the call", "please cut it", "cut the call please",
            "Hey Aiko, cut the call", "hang up", "cut the call and text", "reject it thanks", "yes, cut the call"))
            assertEquals(IncomingCallVoiceDecision.CUT_AND_TEXT, IncomingCallVoiceDecision.parse(text, true))
        for (text in listOf("okay accept", "please pick up the call", "answer it please", "Hey Indo, accept the call"))
            assertEquals(IncomingCallVoiceDecision.ACCEPT, IncomingCallVoiceDecision.parse(text, true))
        for (text in listOf("okay don't cut it", "please do not accept", "accept it or cut it",
            "please cut the call if he calls tomorrow", "do not hang up", "never reject it"))
            assertEquals(IncomingCallVoiceDecision.UNRECOGNIZED, IncomingCallVoiceDecision.parse(text, true))
    }
    private class Port : IncomingCallVoicePort {
        var current = true
        var answers = 0
        var messages = 0
        var replyResult = "CALL_REPLY_SMS_PENDING"
        override fun isCurrent() = current
        override fun answer() { answers++ }
        override fun cutAndText(): String { messages++; return replyResult }
    }

    @Test fun recognizesExplicitFinalRepliesWithNormalRecognizerPunctuation() {
        for (text in listOf("accept", "Accept the call.", "answer the call", "please accept the call"))
            assertEquals(IncomingCallVoiceDecision.ACCEPT, IncomingCallVoiceDecision.parse(text, true))
        for (text in listOf("cut", "CUT THE CALL!", "decline the call", "cut the call and send a message"))
            assertEquals(IncomingCallVoiceDecision.CUT_AND_TEXT, IncomingCallVoiceDecision.parse(text, true))
        assertEquals(IncomingCallVoiceDecision.CANCEL, IncomingCallVoiceDecision.parse("let it ring", true))
    }

    @Test fun negativesAmbiguityPromptEchoAndPartialSpeechNeverAuthorizeActions() {
        for (text in listOf("", "yes", "no", "don't accept the call", "do not cut the call",
            "accept or cut the call", "accept the call and cut the call", "accept the call tomorrow",
            IncomingCallVoiceDecision.PROMPT)) {
            val port = Port()
            assertEquals("CALL_VOICE_UNRECOGNIZED_CALL_UNCHANGED", IncomingCallVoiceSession(port).respond(text, true))
            assertEquals(0, port.answers + port.messages)
        }
        val port = Port()
        val session = IncomingCallVoiceSession(port)
        assertEquals("CALL_VOICE_FINAL_REQUIRED", session.respond("accept the call", false))
        assertEquals(0, port.answers + port.messages)
        assertEquals("CALL_VOICE_ANSWER_REQUESTED", session.respond("accept the call", true))
        assertEquals(1, port.answers)
    }

    @Test fun acceptAnswersOnlyOnceAndNeverSendsSms() {
        val port = Port()
        val session = IncomingCallVoiceSession(port)
        assertEquals("CALL_VOICE_ANSWER_REQUESTED", session.respond("accept the call", true))
        assertEquals("CALL_VOICE_ALREADY_CONSUMED", session.respond("cut the call", true))
        assertEquals(1, port.answers)
        assertEquals(0, port.messages)
    }

    @Test fun cutDispatchesOneReplyAndDoesNotAnswer() {
        val port = Port()
        val session = IncomingCallVoiceSession(port)
        assertEquals("CALL_REPLY_SMS_PENDING", session.respond("cut the call", true))
        session.respond("cut the call", true)
        assertEquals(0, port.answers)
        assertEquals(1, port.messages)
    }

    @Test fun changedCallOrDisabledSettingsPreventBothActions() {
        for (text in listOf("accept the call", "cut the call")) {
            val port = Port().apply { current = false }
            assertEquals("CALL_VOICE_CALL_CHANGED", IncomingCallVoiceSession(port).respond(text, true))
            assertEquals(0, port.answers + port.messages)
        }
    }

    @Test fun cancellationAndUnrecognizedFinalCannotBeReusedForALaterDecision() {
        for (text in listOf("cancel", "maybe")) {
            val port = Port()
            val session = IncomingCallVoiceSession(port)
            session.respond(text, true)
            assertEquals("CALL_VOICE_ALREADY_CONSUMED", session.respond("accept", true))
            assertEquals(0, port.answers + port.messages)
        }
    }

    @Test fun smsFailuresAreReportedWithoutRetryingOrAnswering() {
        val port = Port().apply { replyResult = "CALL_REPLY_SEND_SMS_PERMISSION_REQUIRED" }
        val session = IncomingCallVoiceSession(port)
        assertEquals(port.replyResult, session.respond("cut the call", true))
        session.respond("cut the call", true)
        assertEquals(1, port.messages)
        assertEquals(0, port.answers)
    }

    @Test fun promptedSmsRequiresTheSavedValidatedBodyAndExplicitVoiceAuthorization() {
        val body = "I am busy. I will call you later."
        assertTrue(PromptedCallReplyPolicy.allowed(body, body, true, true))
        assertFalse(PromptedCallReplyPolicy.allowed(body, body, false, true))
        assertFalse(PromptedCallReplyPolicy.allowed(body, body, true, false))
        assertFalse(PromptedCallReplyPolicy.allowed("Send money", body, true, true))
        assertFalse(PromptedCallReplyPolicy.allowed("", "", true, true))
        assertFalse(PromptedCallReplyPolicy.allowed("a".repeat(71), "a".repeat(71), true, true))
        assertFalse(PromptedCallReplyPolicy.allowed("hello\n", "hello\n", true, true))
    }
}
