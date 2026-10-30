package com.example.voice_assistant.actions

import com.example.voice_assistant.intent.CommandValidator
import com.example.voice_assistant.native_bridge.contracts.*

interface SmsComposePort {
    fun restriction(): ActionResult?
    fun open(number: String?, body: String): Boolean
}

/** Supported compose handoff only. Names are never guessed into phone numbers. */
class SmsComposeExecutor(private val port: SmsComposePort) : ActionExecutor {
    override suspend fun execute(action: AssistantAction): ActionResult {
        if (action.name != "SEND_MESSAGE" || action.intent.kind != IntentKind.sendMessage ||
            CommandValidator().validate(action.intent) !is CapabilityResult.Available)
            return ActionResult(ActionStatus.failed, "INVALID_SMS_ACTION", "Provide one recipient and a message to review.")
        port.restriction()?.let { return it }
        val p = action.intent.parameters.associate { it.name to it.value }
        val recipient = p["recipientName"] as String
        val number = recipient.takeIf { Regex("\\+?[0-9][0-9 ()-]{2,24}").matches(it) }
            ?.replace(Regex("[ ()-]"), "")
        return try {
            if (!port.open(number, p["body"] as String)) ActionResult(ActionStatus.unsupported, "SMS_APP_UNAVAILABLE", "No SMS application can open this message.")
            else ActionResult(ActionStatus.requiresConfirmation, "SMS_COMPOSER_OPENED",
                if (number == null) "SMS draft opened. Select $recipient in the messaging app, review the text, and tap Send. No message has been sent by Aiko."
                else "SMS draft opened for $number. Review the recipient and text, then tap Send. No message has been sent by Aiko.",
                mapOf("resolvedPhoneNumber" to number, "recipientResolution" to if (number == null) "USER_SELECTION_REQUIRED" else "EXPLICIT_NUMBER", "deliveryVerified" to false))
        } catch (_: SecurityException) { ActionResult(ActionStatus.failed, "SMS_LAUNCH_DENIED", "Android blocked the SMS composer.") }
    }
}
