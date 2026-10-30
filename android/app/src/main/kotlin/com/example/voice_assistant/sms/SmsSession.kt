package com.example.voice_assistant.sms

import com.example.voice_assistant.actions.ActionExecutor
import com.example.voice_assistant.intent.CommandValidator
import com.example.voice_assistant.native_bridge.contracts.*

data class SmsMessage(val id: Long, val address: String, val body: String, val date: Long)
interface SmsPort {
    fun restriction(): ActionResult?
    suspend fun latest(): SmsMessage?
    suspend fun find(id: Long): SmsMessage?
    fun compose(address: String, body: String): Boolean
}

class SmsReplyContext {
    private var selected: Pair<SmsMessage, Long>? = null
    @Synchronized fun clear() { selected = null }
    @Synchronized fun save(message: SmsMessage, expires: Long) { selected = message to expires }
    @Synchronized fun take(): Pair<SmsMessage, Long>? = selected.also { selected = null }
    companion object { val inProcess = SmsReplyContext() }
}

/** Only the exact recently read SMS can become a reply recipient. Never sends SMS. */
class SmsSession(private val port: SmsPort, private val context: SmsReplyContext = SmsReplyContext(),
    private val now: () -> Long = System::currentTimeMillis) : ActionExecutor {
    override suspend fun execute(action: AssistantAction): ActionResult {
        if (action.name != action.intent.kind.wireName || action.intent.kind !in setOf(IntentKind.readSms, IntentKind.replySms) ||
            CommandValidator().validate(action.intent) !is CapabilityResult.Available)
            return ActionResult(ActionStatus.failed, "INVALID_SMS_ACTION", "Say one complete SMS command.")
        port.restriction()?.let { context.clear(); return it }
        if (action.intent.kind == IntentKind.readSms) {
            context.clear()
            val message = port.latest()
            port.restriction()?.let { return it }
            if (message == null) return ActionResult(ActionStatus.succeeded, "SMS_INBOX_EMPTY", "There are no SMS messages in your inbox.")
            if (message.body.isBlank()) return ActionResult(ActionStatus.unsupported, "SMS_CONTENT_UNAVAILABLE", "This SMS has no readable text.")
            context.save(message, now() + 120000)
            return ActionResult(ActionStatus.succeeded, "SMS_READ", "SMS from ${message.address}: ${message.body.take(1000)}" +
                if (message.body.length > 1000) " The remaining text was not read." else "",
                mapOf("androidMechanism" to "Telephony.Sms.Inbox / READ_SMS", "messageCount" to 1))
        }
        val saved = context.take() // Single-use, including unsuccessful handoffs.
        if (saved == null || now() >= saved.second)
            return ActionResult(ActionStatus.requiresConfirmation, "SMS_CONTEXT_REQUIRED", "Read your latest SMS first, then reply within two minutes.")
        if (port.find(saved.first.id) != saved.first)
            return ActionResult(ActionStatus.failed, "SMS_CHANGED", "That SMS is no longer available or has changed. Read it again before replying.")
        port.restriction()?.let { return it }
        if (now() >= saved.second)
            return ActionResult(ActionStatus.requiresConfirmation, "SMS_CONTEXT_REQUIRED", "The SMS reply context expired. Read the message again.")
        val address = replyAddress(saved.first.address)
            ?: return ActionResult(ActionStatus.unsupported, "SMS_SENDER_NOT_REPLYABLE", "This sender is not a regular phone number. Open your messaging app to check reply options.")
        val body = action.intent.parameters.single { it.name == "body" }.value as String
        return if (port.compose(address, body)) ActionResult(ActionStatus.requiresConfirmation, "SMS_COMPOSER_OPENED",
            "SMS reply draft opened. Verify the recipient and text, then tap Send in your messaging app. Nothing has been sent by Aiko.",
            mapOf("androidMechanism" to "ACTION_SENDTO smsto + sms_body", "sent" to false, "userInteractionRequired" to true, "unlockRequired" to true))
        else ActionResult(ActionStatus.unsupported, "SMS_APP_UNAVAILABLE", "No SMS app accepted the reply draft. Nothing was sent.")
    }
    companion object {
        fun replyAddress(raw: String): String? = raw.replace(Regex("[ ()-]"), "")
            .takeIf { Regex("\\+?[0-9]{7,15}").matches(it) }
    }
}
