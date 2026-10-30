package com.example.voice_assistant

import com.example.voice_assistant.sms.*
import com.example.voice_assistant.intent.LocalIntentRouter
import com.example.voice_assistant.native_bridge.contracts.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SmsSessionTest {
    private class Port : SmsPort {
        var message: SmsMessage? = SmsMessage(1, "+919876543210", "Meeting at ten", 100)
        var blocked: ActionResult? = null
        var recipient: String? = null
        var body: String? = null
        var opens = 0
        var queries = 0
        var accepted = true
        override fun restriction() = blocked
        override suspend fun latest(): SmsMessage? { queries++; return message }
        override suspend fun find(id: Long) = message?.takeIf { it.id == id }
        override fun compose(address: String, body: String): Boolean {
            opens++; recipient = address; this.body = body; return accepted
        }
    }
    private fun action(input: String) = LocalIntentRouter().decide(input).proposedAction!!
    private val read get() = action("read my latest SMS")
    private val reply get() = action("reply to SMS saying I will call you later")
    @Test fun explicitRoutesAndNoAmbiguousSend() {
        assertEquals(IntentKind.readSms, read.intent.kind)
        assertEquals(IntentKind.replySms, reply.intent.kind)
        assertEquals("I will call you later", reply.intent.parameters.single().value)
        assertNull(LocalIntentRouter().decide("don't reply to SMS saying hello").proposedAction)
        assertNull(LocalIntentRouter().decide("reply to SMS").proposedAction)
    }
    @Test fun contextBoundSingleUseAndHandoffOnly() = runBlocking {
        val port = Port(); val session = SmsSession(port)
        assertEquals("SMS_CONTEXT_REQUIRED", session.execute(reply).code)
        assertEquals("SMS_READ", session.execute(read).code)
        val result = session.execute(reply)
        assertEquals("SMS_COMPOSER_OPENED", result.code)
        assertEquals(ActionStatus.requiresConfirmation, result.status)
        assertEquals(false, result.evidence["sent"])
        assertEquals("+919876543210", port.recipient)
        assertEquals("I will call you later", port.body)
        assertEquals("SMS_CONTEXT_REQUIRED", session.execute(reply).code)
        assertEquals(1, port.opens)
    }
    @Test fun expiredOrChangedMessageCannotBeRepliedTo() = runBlocking {
        var clock = 0L; val port = Port(); val session = SmsSession(port) { clock }
        session.execute(read); clock = 120000
        assertEquals("SMS_CONTEXT_REQUIRED", session.execute(reply).code)
        session.execute(read); port.message = port.message!!.copy(address = "+919999999999")
        assertEquals("SMS_CHANGED", session.execute(reply).code)
        session.execute(read); port.message = null
        assertEquals("SMS_CHANGED", session.execute(reply).code)
        assertEquals(0, port.opens)
    }
    @Test fun consecutiveAssistantSessionsShareOnlyExplicitContext() = runBlocking {
        val context = SmsReplyContext(); val port = Port()
        SmsSession(port, context).execute(read)
        assertEquals("SMS_COMPOSER_OPENED", SmsSession(port, context).execute(reply).code)
        assertEquals("SMS_CONTEXT_REQUIRED", SmsSession(port, context).execute(reply).code)
    }
    @Test fun permissionLossAndLockInvalidateContext() = runBlocking {
        val port = Port(); val session = SmsSession(port)
        session.execute(read)
        port.blocked = ActionResult(ActionStatus.unsupported, "SMS_PERMISSION_REQUIRED", "Denied")
        assertEquals("SMS_PERMISSION_REQUIRED", session.execute(reply).code)
        session.execute(read)
        assertEquals(1, port.queries)
        port.blocked = null
        assertEquals("SMS_CONTEXT_REQUIRED", session.execute(reply).code)
        assertEquals(0, port.opens)
    }
    @Test fun emptyInboxSenderRestrictionsAndMissingApp() = runBlocking {
        val port = Port(); val session = SmsSession(port)
        port.message = null
        assertEquals("SMS_INBOX_EMPTY", session.execute(read).code)
        port.message = SmsMessage(1, "BANK", "Text", 100)
        session.execute(read)
        assertEquals("SMS_SENDER_NOT_REPLYABLE", session.execute(reply).code)
        for (address in listOf("12345", "*123#", "+123456789;99999999", "1234567,9999999"))
            assertNull(SmsSession.replyAddress(address))
        port.message = port.message!!.copy(address = "+919876543210")
        port.accepted = false; session.execute(read)
        assertEquals("SMS_APP_UNAVAILABLE", session.execute(reply).code)
    }
}
