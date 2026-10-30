package com.example.voice_assistant.actions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.telecom.Call
import com.example.voice_assistant.communications.AikoInCallService
import com.example.voice_assistant.intent.CommandValidator
import com.example.voice_assistant.native_bridge.contracts.*

interface EndCallPort {
    val mechanism: String get() = "TelecomManager.endCall"
    fun restriction(): String?
    fun end(): Boolean
}

class EndCallExecutor(private val port: EndCallPort) : ActionExecutor {
    override suspend fun execute(action: AssistantAction): ActionResult {
        if (action.name != "END_CALL" || action.intent.kind != IntentKind.endCall ||
            CommandValidator().validate(action.intent) !is CapabilityResult.Available)
            return ActionResult(ActionStatus.failed, "INVALID_END_CALL", "Say end the call as a separate command.")
        val blocked = port.restriction()
        if (blocked != null) return ActionResult(ActionStatus.unsupported, blocked, when(blocked) {
            "HANGUP_PERMISSION_REQUIRED" -> "Unlock Aiko and grant call-control permission in calling settings."
            "VOICE_HANGUP_DISABLED" -> "Enable voice hang-up in calling settings while unlocked."
            "HANGUP_API_UNAVAILABLE" -> "This Android version does not support this hang-up method. Use the phone app."
            else -> "Call control is unavailable in this session. Use your phone app or headset button."
        })
        return try {
            val accepted = port.end()
            ActionResult(if (accepted) ActionStatus.succeeded else ActionStatus.unsupported,
                if (accepted) "CALL_END_ACCEPTED" else "CALL_END_NOT_ACCEPTED",
                if (accepted) "The hang-up request was submitted to Android." else "No single eligible call could be ended. Use the call screen to select the call.",
                mapOf("androidMechanism" to port.mechanism, "requestSubmitted" to accepted,
                    "disconnectObserved" to false, "unlockRequired" to false,
                    "userInteractionRequired" to false, "deprecatedApi" to (port.mechanism == "TelecomManager.endCall")))
        } catch (_: SecurityException) {
            ActionResult(ActionStatus.failed, "CALL_END_PERMISSION_DENIED", "Android denied call control. Check permission or use the phone app.")
        } catch (_: Exception) {
            ActionResult(ActionStatus.failed, "CALL_END_FAILED", "Call ending could not be verified. Check the phone app. Nothing was retried.")
        }
    }
}

class AndroidEndCallPort(private val context: Context, private val foreground: () -> Boolean) : EndCallPort {
    override var mechanism = "NONE"; private set
    override fun restriction(): String? = when {
        !foreground() -> "APP_NOT_FOREGROUND"
        !context.getSharedPreferences("assistant_privacy", 0).getBoolean("voiceHangup", false) -> "VOICE_HANGUP_DISABLED"
        AikoInCallService.isDefault(context) -> null
        Build.VERSION.SDK_INT < 28 -> "HANGUP_API_UNAVAILABLE"
        context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED -> "HANGUP_PERMISSION_REQUIRED"
        else -> null
    }
    @Suppress("DEPRECATION")
    override fun end(): Boolean {
        // Recheck permission and opt-in immediately before the irreversible operation.
        mechanism = "NONE"
        if (restriction() != null) return false
        if (AikoInCallService.isDefault(context)) {
            val service = AikoInCallService.instance ?: return false
            // Never guess between call waiting, held calls, or separate conversations.
            val call = singleHangupTarget(service.live.values.filter { it.parent == null }.map { it to it.state }) ?: return false
            if (service.live.values.none { it === call }) return false
            if (call.state == Call.STATE_RINGING) {
                mechanism = "Call.reject"
                call.reject(false, null)
            } else {
                mechanism = "Call.disconnect"
                call.disconnect()
            }
            return true // Submission only; disconnect is asynchronous.
        }
        if (Build.VERSION.SDK_INT < 28) return false
        mechanism = "TelecomManager.endCall"
        return context.getSystemService(TelecomManager::class.java)?.endCall() ?: false
    }
}

internal fun <T> singleHangupTarget(calls: List<Pair<T, Int>>): T? = calls.filter {
    it.second in setOf(Call.STATE_RINGING, Call.STATE_ACTIVE, Call.STATE_HOLDING,
        Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT)
}.singleOrNull()?.first
