package com.example.voice_assistant

import com.example.voice_assistant.notifications.*
import com.example.voice_assistant.intent.LocalIntentRouter
import com.example.voice_assistant.native_bridge.contracts.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WhatsAppReplyTest {
    private val action get() = LocalIntentRouter().decide("reply whatsapp saying I will call later").proposedAction!!
    @Test fun preparationNeverSendsAndConfirmationIsSingleUse() = runBlocking {
        var sends = 0
        val session = WhatsAppReplySession({ CapabilityResult.Available(ReplyTarget("Rahul") { body ->
            assertEquals("I will call later", body); sends++
            ActionResult(ActionStatus.succeeded, "WHATSAPP_REPLY_DISPATCHED", "Handed off")
        }) }, { null })
        assertEquals(ActionStatus.requiresConfirmation, session.execute(action).status)
        assertEquals(0, sends)
        val token = session.review()["token"] as String
        session.confirm(token); session.confirm(token)
        assertEquals(1, sends)
    }
    @Test fun expiredWrongCancelledAndLockedDraftsCannotSend() = runBlocking {
        var time = 0L
        var locked = false
        val session = WhatsAppReplySession({ CapabilityResult.Available(ReplyTarget("Rahul") { error("must not send") }) },
            { if (locked) ActionResult(ActionStatus.requiresConfirmation, "UNLOCK_REQUIRED", "Unlock") else null }, { time })
        session.execute(action)
        session.confirm("wrong-token")
        assertTrue(session.review().isEmpty())
        session.execute(action)
        var token = session.review()["token"] as String
        time = 120001
        assertEquals("WHATSAPP_REPLY_EXPIRED", session.confirm(token).code)
        session.execute(action)
        token = session.review()["token"] as String
        session.clear()
        assertEquals("WHATSAPP_REPLY_EXPIRED", session.confirm(token).code)
        session.execute(action)
        token = session.review()["token"] as String
        locked = true
        assertEquals("UNLOCK_REQUIRED", session.confirm(token).code)
    }
    @Test fun missingNotificationDoesNotCreateDraft() = runBlocking {
        val session = WhatsAppReplySession({ CapabilityResult.Unavailable("WHATSAPP_REPLY_UNAVAILABLE") }, { null })
        assertEquals(ActionStatus.unsupported, session.execute(action).status)
        assertTrue(session.review().isEmpty())
    }
}
