package com.example.voice_assistant.actions

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import com.example.voice_assistant.intent.CommandValidator
import com.example.voice_assistant.native_bridge.contracts.*

object PhoneRecipient {
    fun number(value: String): String? = value.trim().takeIf {
        Regex("\\+?[0-9][0-9 ()-]{1,24}").matches(it)
    }?.replace(Regex("[ ()-]"), "")?.takeIf { it.count(Char::isDigit) in 2..15 }
    fun valid(value: String) = number(value) != null ||
        (value.length in 1..100 && value.any(Char::isLetter) && value.all {
            it.isLetter() || Character.getType(it) in setOf(Character.NON_SPACING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt()) || it in " '-."
        })
}

class PhoneCallExecutor(private val activity: Activity, private val foreground: () -> Boolean) : ActionExecutor {
    override suspend fun execute(action: AssistantAction): ActionResult {
        if (action.name != "CALL_PHONE" || action.intent.kind != IntentKind.callPhone ||
            CommandValidator().validate(action.intent) !is CapabilityResult.Available)
            return ActionResult(ActionStatus.failed, "INVALID_CALL", "Say one phone number or contact name.")
        if (!foreground()) return ActionResult(ActionStatus.failed, "APP_NOT_FOREGROUND", "Open Aiko to call.")
        if (activity.getSystemService(KeyguardManager::class.java).isDeviceLocked)
            return ActionResult(ActionStatus.requiresConfirmation, "UNLOCK_REQUIRED", "Unlock to open the phone dialer.")
        val recipient = action.intent.parameters.single().value as String
        if (!PhoneRecipient.valid(recipient)) return ActionResult(ActionStatus.failed, "INVALID_CALL_RECIPIENT", "Say a phone number or exact contact name. Service codes are not supported.")
        return try {
            val number = PhoneRecipient.number(recipient)
            val intent = if (number != null) Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
                else Intent(activity, ContactDialActivity::class.java).putExtra("recipient", recipient)
            activity.startActivity(intent)
            ActionResult(ActionStatus.requiresConfirmation, "PHONE_DIALER_HANDOFF", "Phone call preparation opened. Verify the recipient and tap Call. No call has been placed by Aiko.",
                mapOf("userInteractionRequired" to true, "callPlaced" to false, "outcome" to "HANDOFF_ONLY"))
        } catch (_: android.content.ActivityNotFoundException) {
            ActionResult(ActionStatus.unsupported, "DIALER_UNAVAILABLE", "No phone dialer is available.")
        } catch (_: SecurityException) {
            ActionResult(ActionStatus.failed, "CALL_HANDOFF_DENIED", "Android blocked the phone dialer.")
        }
    }
}
