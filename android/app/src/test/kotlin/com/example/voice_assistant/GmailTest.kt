package com.example.voice_assistant

import com.example.voice_assistant.gmail.*
import com.example.voice_assistant.intent.*
import com.example.voice_assistant.native_bridge.contracts.*
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GmailTest {
    private val email = EmailMessage("abc", "thread1", "Rahul <rahul@example.com>", "Meeting", "Meet tomorrow at 4 PM.", "rahul@example.com", "<message@example.com>")
    private class Service(val email: EmailMessage) : GmailService {
        var sends = 0
        var readFailure: String? = null
        var sendFailure: String? = null
        override suspend fun list(scope: String): List<EmailMessage> { readFailure?.let { throw GmailFailure(it) }; return listOf(email) }
        override suspend fun read(id: String) = email
        override suspend fun reply(message: EmailMessage, text: String): String { sends++; sendFailure?.let { throw GmailFailure(it) }; return "sent1" }
    }
    private fun action(command: TypedCommand) = AssistantAction(command.kind.wireName, command.toIntent(.99))
    @Test fun voiceCommandsRouteWithoutModelButCannotConfirmSend() {
        val router = LocalIntentRouter()
        for (input in listOf("read my latest email", "read latest emails", "read important emails", "read relevant emails", "summarize email", "reply email that 4 PM works for me")) {
            val result = router.decide(input)
            assertFalse(result.modelRequired); assertNotNull(result.proposedAction)
        }
        assertNull(router.decide("send this email now").proposedAction)
        assertNull(router.decide("do not reply email that yes").proposedAction)
    }
    @Test fun readingThenDictatingOnlyCreatesSingleUseDraft() = runBlocking {
        val service = Service(email); val session = GmailSession(service, { null })
        assertEquals("GMAIL_SELECT_EMAIL_FIRST", session.execute(action(TypedCommand.ReplyEmail("Yes"))).code)
        assertEquals("GMAIL_READ", session.execute(action(TypedCommand.ReadEmail())).code)
        assertEquals("GMAIL_REPLY_DRAFT", session.execute(action(TypedCommand.ReplyEmail("4 PM works"))).code)
        assertEquals(0, service.sends)
        val draft = session.status()["draft"] as Map<*, *>
        assertEquals("rahul@example.com", draft["to"])
        assertEquals("GMAIL_REPLY_SENT", session.confirm(draft["id"] as String).code)
        assertEquals("GMAIL_DRAFT_EXPIRED", session.confirm(draft["id"] as String).code)
        assertEquals(1, service.sends)
    }
    @Test fun wrongExpiredAndCancelledConfirmationsNeverSend() = runBlocking {
        val service = Service(email); var clock = 1000L
        val session = GmailSession(service, { null }, now = { clock })
        suspend fun prepare(): String {
            session.execute(action(TypedCommand.ReadEmail()))
            session.execute(action(TypedCommand.ReplyEmail("Yes")))
            return (session.status()["draft"] as Map<*, *>)["id"] as String
        }
        prepare(); assertEquals("GMAIL_DRAFT_EXPIRED", session.confirm("wrong").code)
        val expired = prepare(); clock += 300001
        assertEquals("GMAIL_DRAFT_EXPIRED", session.confirm(expired).code)
        val cancelled = prepare(); session.cancelDraft()
        assertEquals("GMAIL_DRAFT_EXPIRED", session.confirm(cancelled).code)
        assertEquals(0, service.sends)
    }
    @Test fun readingListClearsRecipientAndRequiresExplicitSelection() = runBlocking {
        val service = Service(email); val session = GmailSession(service, { null })
        session.execute(action(TypedCommand.ReadEmail()))
        session.execute(action(TypedCommand.ReadEmail("IMPORTANT")))
        assertEquals("GMAIL_SELECT_EMAIL_FIRST", session.execute(action(TypedCommand.ReplyEmail("Yes"))).code)
        assertEquals("GMAIL_MESSAGE_UNAVAILABLE", session.select("not-listed").code)
        session.select(email.id)
        assertEquals("GMAIL_REPLY_DRAFT", session.execute(action(TypedCommand.ReplyEmail("Yes"))).code)
    }
    @Test fun authFailureIsNotAnEmptyInboxAndClearsOldContext() = runBlocking {
        val service = Service(email); val session = GmailSession(service, { null })
        session.execute(action(TypedCommand.ReadEmail()))
        service.readFailure = "GMAIL_AUTH_REQUIRED"
        assertEquals("GMAIL_AUTH_REQUIRED", session.execute(action(TypedCommand.ReadEmail())).code)
        assertNull(session.status()["selected"])
    }
    @Test fun unknownSendOutcomeIsNeverRetried() = runBlocking {
        val service = Service(email).apply { sendFailure = "GMAIL_SEND_OUTCOME_UNKNOWN" }
        val session = GmailSession(service, { null })
        session.execute(action(TypedCommand.ReadEmail())); session.execute(action(TypedCommand.ReplyEmail("Yes")))
        val id = (session.status()["draft"] as Map<*, *>)["id"] as String
        assertEquals("GMAIL_SEND_OUTCOME_UNKNOWN", session.confirm(id).code)
        assertEquals("GMAIL_DRAFT_EXPIRED", session.confirm(id).code)
        assertEquals(1, service.sends)
    }
    @Test fun lockedDeviceCannotReadOrConfirm() = runBlocking {
        val service = Service(email); var locked = false
        val session = GmailSession(service, { if (locked) ActionResult(ActionStatus.failed, "UNLOCK_REQUIRED", "Unlock") else null })
        session.execute(action(TypedCommand.ReadEmail())); session.execute(action(TypedCommand.ReplyEmail("Yes")))
        val id = (session.status()["draft"] as Map<*, *>)["id"] as String
        locked = true
        assertEquals("UNLOCK_REQUIRED", session.confirm(id).code)
        assertEquals("UNLOCK_REQUIRED", session.execute(action(TypedCommand.ReadEmail())).code)
        assertNull(session.status()["selected"]); assertEquals(0, service.sends)
    }
    @Test fun replyMimeEncodesBodyAndRejectsHeaderInjectionAndMultipleRecipients() {
        val wire = String(GmailMime.decodeUrl(GmailMime.replyRaw("me@example.com", email, "Yes\r\nBcc: attacker@example.com")))
        assertTrue(wire.contains("To: rahul@example.com\r\n"))
        assertTrue(wire.contains("In-Reply-To: <message@example.com>"))
        assertFalse(wire.contains("\r\nBcc:"))
        for (recipient in listOf("a@example.com,b@example.com", "a@example.com\r\nBcc: b@example.com")) {
            try { GmailMime.replyRaw("me@example.com", email.copy(replyTo = recipient), "Yes"); fail() }
            catch (e: GmailFailure) { assertEquals("GMAIL_UNSAFE_RECIPIENT", e.code) }
        }
    }
    @Test fun mimePrefersPlainAlternativeAndSkipsAttachments() {
        val plain = GmailMime.encodeUrl("Hello plain email.".toByteArray())
        val json = JsonParser.parseString("""{"id":"abc","threadId":"thread1","payload":{"mimeType":"multipart/mixed","headers":[{"name":"Subject","value":"=?UTF-8?B?TWVldGluZw==?="}],"parts":[{"mimeType":"multipart/alternative","parts":[{"mimeType":"text/plain","body":{"data":"$plain"}},{"mimeType":"text/html","body":{"data":"$plain"}}]},{"mimeType":"text/plain","filename":"secret.txt","body":{"data":"$plain"}}]}}""").asJsonObject
        val parsed = GmailMime.parse(json) { error("HTML should not be used") }
        assertEquals("Meeting", parsed.subject); assertEquals("Hello plain email.", parsed.body)
    }
    @Test fun summaryIsExplicitlyExtractiveAndDoesNotGenerateFacts() {
        val body = "The team meeting is tomorrow. Bring the project notes. The meeting starts at four."
        val summary = ExtractiveEmailSummarizer().summarize(body)
        assertTrue(summary.startsWith("Extractive summary"))
        for (sentence in summary.substringAfter(": ").split(Regex("(?<=[.!?])\\s+"))) assertTrue(body.contains(sentence))
    }
}
