package com.example.voice_assistant.communications

import android.app.Activity
import android.telecom.Call
import com.example.voice_assistant.actions.ActionExecutor
import com.example.voice_assistant.intent.CommandValidator
import com.example.voice_assistant.native_bridge.contracts.*

/** The complete voice command is authorization for this one fixed reply, not arbitrary SMS. */
class VoiceCallReplyExecutor(private val activity: android.content.Context, private val foreground: () -> Boolean) : ActionExecutor {
    override suspend fun execute(action: AssistantAction): ActionResult {
        if (action.name != "REPLY_TO_CALL" || action.intent.kind != IntentKind.replyToCall ||
            CommandValidator().validate(action.intent) !is CapabilityResult.Available)
            return ActionResult(ActionStatus.failed, "INVALID_CALL_REPLY", "Say decline the call and text I will call you later.")
        if (!foreground() || (activity is Activity && (activity.isFinishing || activity.isDestroyed)))
            return ActionResult(ActionStatus.failed, "APP_NOT_FOREGROUND", "The assistant session is no longer active.")
        if (!AikoInCallService.isDefault(activity)) {
            CallReplySmsSender.record(activity, "DEFAULT_PHONE_REQUIRED")
            return ActionResult(ActionStatus.unsupported, "DEFAULT_PHONE_REQUIRED", "Choose Aiko as the default Phone app in calling settings first.")
        }
        val entry = AikoInCallService.instance?.live?.entries?.filter { it.value.state == Call.STATE_RINGING }?.singleOrNull()
            ?: return ActionResult(ActionStatus.failed, "ONE_RINGING_CALL_REQUIRED", "There must be exactly one incoming ringing call. Nothing was sent.")
        val restriction = CallReplySmsSender.restriction(activity, entry.value, voiceRequested = true)
        if (restriction != null) {
            CallReplySmsSender.record(activity, restriction)
            val message = when (restriction) {
                "CALL_REPLY_SEND_SMS_PERMISSION_REQUIRED" -> "SMS permission is missing. Open Aiko's incoming call SMS setup and grant SMS permission."
                "CALL_REPLY_PHONE_STATE_PERMISSION_REQUIRED" -> "Phone state permission is missing. Open Aiko's incoming call SMS setup and grant phone permission."
                "CALL_REPLY_SIM_UNAVAILABLE" -> "Android could not identify the SIM for this incoming call. No SMS was sent."
                "CALL_REPLY_INVALID_NUMBER" -> "The caller number is hidden or not usable for SMS. No SMS was sent."
                "CALL_REPLY_NOT_RINGING" -> "The incoming call stopped ringing before the SMS request."
                else -> "Cannot send the call reply: $restriction."
            }
            return ActionResult(ActionStatus.failed, restriction, message)
        }
        return try {
            CallReplySmsSender.send(activity, entry.key, entry.value, IncomingCallReply.MESSAGE, voiceRequested = true)
            ActionResult(ActionStatus.succeeded, "CALL_REPLY_SMS_PENDING",
                "SMS requested to the incoming caller. The call will be declined only after Android confirms the SMS was sent.",
                mapOf("unlockRequired" to false, "userInteractionRequired" to false, "sent" to false,
                    "deliveryVerified" to false, "androidMechanism" to "SmsManager on incoming SIM; decline after sent receipt"))
        } catch (_: Exception) {
            if (CallReplySmsSender.lastStatus(activity) != "CALL_REPLY_SMS_OUTCOME_UNKNOWN_CALL_UNCHANGED")
                CallReplySmsSender.record(activity, "CALL_REPLY_NOT_CONFIRMED")
            ActionResult(ActionStatus.failed, "CALL_REPLY_NOT_CONFIRMED", "The SMS request was not confirmed. The call has not been declined by this command.")
        }
    }
}

