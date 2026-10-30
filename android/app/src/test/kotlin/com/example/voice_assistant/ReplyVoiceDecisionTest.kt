package com.example.voice_assistant

import com.example.voice_assistant.notifications.*
import com.example.voice_assistant.intent.LocalIntentRouter
import com.example.voice_assistant.native_bridge.contracts.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReplyVoiceDecisionTest {
    @Test fun exactFinalConfirmationRequired() {
        assertEquals(ReplyVoiceDecision.SEND, ReplyVoiceDecision.parse("Send reply.", true))
        assertEquals(ReplyVoiceDecision.CANCEL, ReplyVoiceDecision.parse("cancel the reply", true))
        for (text in listOf("send reply", "send the reply")) assertEquals(ReplyVoiceDecision.UNRECOGNIZED, ReplyVoiceDecision.parse(text, false))
        for (text in listOf("yes", "don't send reply", "send reply later", "maybe send reply", "send reply and call Rahul", ""))
            assertEquals(ReplyVoiceDecision.UNRECOGNIZED, ReplyVoiceDecision.parse(text, true))
    }
    @Test fun confirmationRemainsBoundToSingleUseDraftAndExpiry() = runBlocking {
        var now = 0L
        var sends = 0
        val session = WhatsAppReplySession({ CapabilityResult.Available(ReplyTarget("Test recipient") {
            sends++; ActionResult(ActionStatus.succeeded, "TEST_DISPATCH", "Test only")
        }) }, { null }, { now })
        val action = LocalIntentRouter().decide("reply whatsapp I'll call later").proposedAction!!
        session.execute(action)
        val token = session.review()["token"] as String
        assertEquals("TEST_DISPATCH", session.confirm(token).code)
        assertEquals("WHATSAPP_REPLY_EXPIRED", session.confirm(token).code)
        session.execute(action)
        val expired = session.review()["token"] as String
        now = 120001
        assertEquals("WHATSAPP_REPLY_EXPIRED", session.confirm(expired).code)
        assertEquals(1, sends)
    }
}
