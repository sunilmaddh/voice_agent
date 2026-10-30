package com.example.voice_assistant

import com.example.voice_assistant.notifications.*
import com.example.voice_assistant.intent.LocalIntentRouter
import com.example.voice_assistant.native_bridge.contracts.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class InstantReplyTest {
    private val action get() = LocalIntentRouter().decide("reply whatsapp saying I will call later").proposedAction!!
    @Test fun usesReadTargetOnceAndNeverFallsBackToLatest() = runBlocking {
        var sends = 0
        val pinned = ReplyTarget("Rahul") { sends++; ActionResult(ActionStatus.succeeded, "WHATSAPP_REPLY_DISPATCHED", "Handed off") }
        val session = WhatsAppReplySession({ error("must not select latest") }, { null }, instantEnabled = { true },
            readTarget = { assertEquals("read-id", it.id); CapabilityResult.Available(pinned) })
        assertEquals("WHATSAPP_CONTEXT_REQUIRED", session.execute(action).code)
        session.rememberRead(NotificationRecord("read-id", "com.whatsapp", "Hello"))
        assertEquals("WHATSAPP_REPLY_DISPATCHED", session.execute(action).code)
        assertEquals(1, sends)
        assertEquals("WHATSAPP_CONTEXT_REQUIRED", session.execute(action).code)
        assertTrue(session.review().isEmpty())
    }
    @Test fun expiredClearedOrChangedContextNeverSends() = runBlocking {
        var time = 0L
        val session = WhatsAppReplySession({ error("no fallback") }, { null }, { time }, { true },
            { CapabilityResult.Available(ReplyTarget("Rahul") { ActionResult(ActionStatus.failed, "WHATSAPP_NOTIFICATION_CHANGED", "Changed") }) })
        val record = NotificationRecord("read-id", "com.whatsapp", "Hello")
        session.rememberRead(record); time = 120001
        assertEquals("WHATSAPP_CONTEXT_REQUIRED", session.execute(action).code)
        session.rememberRead(record); session.clear()
        assertEquals("WHATSAPP_CONTEXT_REQUIRED", session.execute(action).code)
        session.rememberRead(record)
        assertEquals("WHATSAPP_NOTIFICATION_CHANGED", session.execute(action).code)
        assertEquals("WHATSAPP_CONTEXT_REQUIRED", session.execute(action).code)
    }
}
