package com.example.voice_assistant.gmail

import com.example.voice_assistant.actions.ActionExecutor
import com.example.voice_assistant.intent.CommandValidator
import com.example.voice_assistant.native_bridge.contracts.*
import kotlinx.coroutines.*
import java.util.UUID

/** A session owns the exact selected message and single-use confirmation draft. No model can send. */
class GmailSession(private val service: GmailService, private val restriction: () -> ActionResult?,
    private val summarizer: EmailSummarizer = ExtractiveEmailSummarizer(), private val now: () -> Long = { System.nanoTime() / 1_000_000 }) : ActionExecutor {
    private var messages = emptyList<EmailMessage>()
    private var selected: EmailMessage? = null
    private data class Draft(val id: String, val message: EmailMessage, val body: String, val created: Long)
    private var draft: Draft? = null
    private var lastResult: ActionResult? = null
    private var sendLatencyMs: Double? = null
    fun clear() { messages = emptyList(); selected = null; draft = null; lastResult = null; sendLatencyMs = null }
    fun cancelDraft() { draft = null }
    fun status(): Map<String, Any?> {
        restriction()?.let { return mapOf("restricted" to it.code) }
        val d = draft?.takeIf { now() - it.created < 300000 }
        if (d == null) draft = null
        fun message(m: EmailMessage) = mapOf("id" to m.id, "sender" to m.sender, "subject" to m.subject)
        return mapOf("messages" to messages.map(::message), "selected" to selected?.let { message(it) + mapOf("body" to it.body, "truncated" to it.truncated) },
            "draft" to d?.let { mapOf("id" to it.id, "to" to GmailMime.address(it.message.replyTo.ifBlank { it.message.sender }), "subject" to it.message.subject, "body" to it.body) },
            "lastResult" to lastResult?.toWire(), "sendLatencyMs" to sendLatencyMs, "sendExecutionMode" to "CLOUD_GMAIL_API", "summaryMethod" to "LOCAL_EXTRACTIVE")
    }
    suspend fun select(id: String): ActionResult {
        restriction()?.let { return it }
        // Only message IDs in the current reviewed list can become reply context.
        val item = messages.firstOrNull { it.id == id } ?: return failure("GMAIL_MESSAGE_UNAVAILABLE")
        draft = null; selected = item
        return ActionResult(ActionStatus.succeeded, "GMAIL_SELECTED", "Email selected. Say read selected email, summarize email, or reply email that followed by your reply.")
    }
    override suspend fun execute(action: AssistantAction): ActionResult {
        restriction()?.let { return it }
        if (action.name != action.intent.kind.wireName || action.intent.kind !in setOf(IntentKind.readEmail, IntentKind.replyEmail) ||
            CommandValidator().validate(action.intent) !is CapabilityResult.Available) return failure("GMAIL_INVALID_REQUEST")
        val p = action.intent.parameters.associate { it.name to it.value }
        val result = try {
            if (action.intent.kind == IntentKind.replyEmail) prepare(p["body"] as String)
            else read(p["scope"] as String)
        } catch (e: GmailFailure) { if (e.code == "GMAIL_AUTH_REQUIRED") clear(); failure(e.code) }
        lastResult = result; return result
    }
    private suspend fun read(scope: String): ActionResult {
        draft = null
        if (scope !in setOf("SELECTED", "SUMMARY")) {
            selected = null; messages = emptyList()
            val fetched = service.list(scope)
            currentCoroutineContext().ensureActive()
            restriction()?.let { return it }
            messages = fetched
            if (scope == "LATEST") selected = fetched.firstOrNull()
        }
        if (scope in setOf("SUMMARY", "SELECTED") && selected == null) return failure("GMAIL_SELECT_EMAIL_FIRST")
        if (scope == "SUMMARY") return ActionResult(ActionStatus.succeeded, "GMAIL_EXTRACTIVE_SUMMARY",
            summarizer.summarize(selected!!.body) + if (selected!!.truncated) " This summary covers only the loaded part of the email." else "")
        if (scope in setOf("LATEST", "SELECTED")) {
            val m = selected ?: return ActionResult(ActionStatus.succeeded, "GMAIL_EMPTY", "No inbox email was found.")
            val body = if (m.body.isBlank()) "No readable inline text is available. Attachments and encrypted content are not read." else m.body.take(2300)
            return ActionResult(ActionStatus.succeeded, "GMAIL_READ", "From ${m.sender.take(180)}. Subject ${m.subject.take(220)}. $body" +
                if (m.truncated || m.body.length > 2300) " Email content was shortened for speech. Open Gmail for the full message." else "")
        }
        val description = when (scope) { "IMPORTANT" -> "Gmail important inbox emails"; "RELEVANT" -> "Unread Gmail important inbox emails"; else -> "Recent inbox emails" }
        return ActionResult(ActionStatus.succeeded, "GMAIL_LIST", "$description. " + if (messages.isEmpty()) "None found." else
            messages.mapIndexed { i, m -> "${i + 1}. From ${m.sender.take(100)}. Subject ${m.subject.take(150)}." }.joinToString(" ") + " Select an email on the Gmail screen before replying.")
    }
    private fun prepare(body: String): ActionResult {
        val m = selected ?: return failure("GMAIL_SELECT_EMAIL_FIRST")
        GmailMime.address(m.replyTo.ifBlank { m.sender }); GmailMime.safeMessageId(m.messageId)
        draft = Draft(UUID.randomUUID().toString(), m, body, now())
        return ActionResult(ActionStatus.requiresConfirmation, "GMAIL_REPLY_DRAFT", "Reply draft: $body. Review the recipient and message on the Gmail screen and tap Confirm send. Nothing has been sent.")
    }
    suspend fun confirm(draftId: String): ActionResult {
        restriction()?.let { return it }
        val pending = draft ?: return failure("GMAIL_DRAFT_EXPIRED")
        if (pending.id != draftId || now() - pending.created >= 300000) { draft = null; return failure("GMAIL_DRAFT_EXPIRED") }
        draft = null // Consume before network I/O; duplicate taps never resend.
        // Record the outcome within the uncancellable boundary, even when the UI goes away.
        return withContext(NonCancellable) {
            val started = System.nanoTime()
            val result = try {
                service.reply(pending.message, pending.body)
                ActionResult(ActionStatus.succeeded, "GMAIL_REPLY_SENT", "Gmail accepted your reply for sending.")
            } catch (e: GmailFailure) { failure(e.code) }
            catch (_: Exception) { failure("GMAIL_SEND_OUTCOME_UNKNOWN") }
            sendLatencyMs = (System.nanoTime() - started) / 1_000_000.0
            lastResult = result
            result
        }
    }
    companion object {
        fun failure(code: String) = ActionResult(ActionStatus.failed, code, when (code) {
            "GMAIL_AUTH_REQUIRED" -> "Connect or reconnect Gmail from the Gmail screen."
            "GMAIL_SELECT_EMAIL_FIRST" -> "Read the latest email or select an email on the Gmail screen first."
            "GMAIL_DRAFT_EXPIRED" -> "The reply draft expired or was already used. Dictate a new reply."
            "GMAIL_SEND_OUTCOME_UNKNOWN" -> "The send outcome is unknown. Check Gmail Sent before creating another reply. Aiko will not retry automatically."
            "GMAIL_ACCESS_DENIED" -> "Gmail access was denied. Check consent scopes, Gmail API setup and account restrictions."
            "GMAIL_UNSAFE_RECIPIENT", "GMAIL_REPLY_HEADERS_UNAVAILABLE" -> "This email cannot be safely replied to here. Open Gmail to reply."
            "GMAIL_RATE_LIMITED" -> "Gmail rate limit reached. Try again later."
            "GMAIL_MESSAGE_TOO_LARGE" -> "This email exceeds the POC reading limit. Open Gmail to read it."
            "GMAIL_MESSAGE_UNAVAILABLE" -> "This email is no longer available in this session. Read emails again."
            else -> "Gmail could not complete the request. Check connectivity and Gmail authorization, then try again."
        })
    }
}
