package com.example.voice_assistant.notifications

import com.example.voice_assistant.actions.ActionExecutor
import com.example.voice_assistant.intent.CommandValidator
import com.example.voice_assistant.native_bridge.contracts.*
import java.util.UUID

data class ReplyTarget(val recipient: String, val send: (String) -> ActionResult)

/** Main-thread, memory-only reply context. Instant mode is explicit and defaults off. */
class WhatsAppReplySession(private val target: () -> CapabilityResult<ReplyTarget>,
    private val restriction: () -> ActionResult?, private val now: () -> Long = System::currentTimeMillis,
    private val instantEnabled: () -> Boolean = { false },
    private val readTarget: (NotificationRecord) -> CapabilityResult<ReplyTarget> = { CapabilityResult.Unavailable("WHATSAPP_CONTEXT_REQUIRED") }) : ActionExecutor {
    private data class Draft(val token: String, val body: String, val target: ReplyTarget, val expires: Long)
    private var draft: Draft? = null
    private var lastRead: Pair<ReplyTarget, Long>? = null
    fun clear() { draft = null; lastRead = null }
    fun rememberRead(record: NotificationRecord?) {
        clear()
        if (record == null || restriction() != null) return
        val result = readTarget(record)
        if (result is CapabilityResult.Available) lastRead = result.value to (now() + 120000)
    }
    override suspend fun execute(action: AssistantAction): ActionResult {
        draft = null
        restriction()?.let { return it }
        if (action.name != "REPLY_WHATSAPP" || action.intent.kind != IntentKind.replyWhatsApp ||
            CommandValidator().validate(action.intent) !is CapabilityResult.Available)
            return ActionResult(ActionStatus.failed, "INVALID_WHATSAPP_REPLY", "Provide a clear WhatsApp reply.")
        val body = action.intent.parameters.single { it.name == "body" }.value as String
        if (instantEnabled()) {
            val pinned = lastRead
            lastRead = null // Consume even on failure; never retry or choose a newer conversation.
            if (pinned == null || now() >= pinned.second)
                return ActionResult(ActionStatus.failed, "WHATSAPP_CONTEXT_REQUIRED", "Read my latest WhatsApp message first, then dictate your reply within two minutes.")
            restriction()?.let { return it }
            return pinned.first.send(body).let { result -> result.copy(evidence = result.evidence + mapOf(
                "replyMode" to "INSTANT_VOICE", "userInteractionRequired" to false)) }
        }
        lastRead = null
        val selected = target()
        if (selected is CapabilityResult.Unavailable) return ActionResult(ActionStatus.unsupported, selected.code,
            when (selected.code) {
                "WHATSAPP_REPLY_AUTHENTICATION_REQUIRED" -> "This reply requires unlocking. Unlock your phone and dictate the reply again."
                "WHATSAPP_NOTIFICATION_HIDDEN" -> "This notification is hidden while locked. Unlock your phone to reply."
                "WHATSAPP_LOCKED_REPLY_DISABLED" -> "Unlock your phone or enable locked WhatsApp replies and personal readouts in Aiko settings."
                else -> "No usable WhatsApp reply notification is available. Enable notification access and check WhatsApp's active notification."
            })
        val recipient = (selected as CapabilityResult.Available).value
        draft = Draft(UUID.randomUUID().toString(), body, recipient, now() + 120000)
        return ActionResult(ActionStatus.requiresConfirmation, "WHATSAPP_REPLY_REVIEW",
            "Reply draft for ${recipient.recipient}: $body. Review and confirm in Aiko before sending. Nothing has been sent.")
    }
    fun review(): Map<String, Any?> {
        if (restriction() != null || draft?.let { now() >= it.expires } == true) clear()
        val d = draft ?: return emptyMap()
        return mapOf("token" to d.token, "recipient" to d.target.recipient, "body" to d.body)
    }
    fun confirm(token: String): ActionResult {
        val d = draft
        clear() // Single use, including failed or uncertain dispatch. Never automatically retry.
        restriction()?.let { return it }
        if (d == null || d.token != token || now() >= d.expires)
            return ActionResult(ActionStatus.failed, "WHATSAPP_REPLY_EXPIRED", "This reply review expired. Dictate a new reply.")
        return d.target.send(d.body)
    }
}
