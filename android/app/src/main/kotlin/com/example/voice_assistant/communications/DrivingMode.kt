package com.example.voice_assistant.communications

import android.app.KeyguardManager
import android.content.Context
import android.telecom.Call
import android.telecom.TelecomManager
import com.example.voice_assistant.native_bridge.contracts.*

/** Basic POC call policy; no caller audio injection, DND changes or automatic driving detection. */
object DrivingMode {
    const val MESSAGE = "I am driving right now. I will call you back later."
    private fun prefs(c: Context) = c.getSharedPreferences("driving_mode", 0)
    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun automaticReply(c: Context) = prefs(c).getBoolean("automaticReply", false)
    fun voiceFriendlyRinging(c: Context) = prefs(c).getBoolean("voiceFriendlyRinging", false)
    fun setVoiceFriendlyRinging(c: Context, value: Boolean) {
        check(!c.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        check(prefs(c).edit().putBoolean("voiceFriendlyRinging", value).commit())
    }
    fun message(c: Context) = prefs(c).getString("message", MESSAGE)?.let {
        runCatching { DrivingReply.validate(it) }.getOrNull()
    } ?: MESSAGE
    fun setMessage(c: Context, value: String) {
        check(!c.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        val text = DrivingReply.validate(value)
        check(prefs(c).edit().putString("message", text).commit())
    }
    fun status(c: Context): Map<String, Any?> = mapOf("enabled" to enabled(c),
        "voiceFriendlyRinging" to voiceFriendlyRinging(c),
        "automaticReply" to automaticReply(c), "message" to message(c), "messageMaxLength" to DrivingReply.MAX_LENGTH,
        "defaultPhone" to AikoInCallService.isDefault(c),
        "lastEvent" to prefs(c).getString("lastEvent", "NOT_TESTED"),
        "lastEventAtMs" to prefs(c).getLong("lastEventAtMs", 0),
        "smsResult" to CallReplySmsSender.lastStatus(c),
        "deviceValidation" to "PENDING_PHYSICAL_DEVICE_TEST",
        "restriction" to "Automatic driving SMS works while locked when previously enabled. Requires Android 11+, default Phone role, SMS and phone permissions. Caller delivery is not verified.")
    fun set(c: Context, value: Boolean, voiceRequested: Boolean = false): ActionResult {
        if (!voiceRequested && c.getSystemService(KeyguardManager::class.java).isDeviceLocked)
            return ActionResult(ActionStatus.requiresConfirmation, "UNLOCK_REQUIRED", "Unlock to change driving mode.")
        if (!prefs(c).edit().putBoolean("enabled", value).putBoolean("automaticReply", value).commit())
            return ActionResult(ActionStatus.failed, "DRIVING_SAVE_FAILED", "Driving mode could not be saved.")
        if (value) AikoInCallService.instance?.silenceIncomingCalls()
        return ActionResult(ActionStatus.succeeded, if (value) "DRIVING_MODE_ON" else "DRIVING_MODE_OFF",
            if (!value) "Driving mode is off." else if (!AikoInCallService.isDefault(c))
                "Driving mode is on. Choose Aiko as default Phone to handle incoming calls."
            else "Driving mode is on. Incoming calls will be silenced. " +
                if (automaticReply(c)) "Automatic SMS is enabled, including while locked, where permissions allow." else "Automatic SMS is off.")
    }
    fun setAutomaticReply(c: Context, value: Boolean) {
        check(!c.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        check(prefs(c).edit().putBoolean("automaticReply", value).commit())
    }
    fun record(c: Context, code: String) {
        prefs(c).edit().putString("lastEvent", code).putLong("lastEventAtMs", System.currentTimeMillis()).apply()
        android.util.Log.i("AssistantPOC", org.json.JSONObject(mapOf("code" to code, "executionMode" to "LOCAL")).toString())
    }
    @Suppress("DEPRECATION")
    fun incoming(c: Context, id: String, call: Call) {
        if (!enabled(c) || !AikoInCallService.isDefault(c) || call.state != Call.STATE_RINGING) return
        try { AikoInCallService.instance?.silenceIncomingCalls(); c.getSystemService(TelecomManager::class.java).silenceRinger() }
        catch (_: SecurityException) { record(c, "DRIVING_SILENCE_DENIED"); return }
        record(c, "DRIVING_RINGTONE_SILENCED")
        if (!automaticReply(c)) return
        val restriction = CallReplySmsSender.restriction(c, call, driving = true)
        if (restriction != null) { record(c, "DRIVING_$restriction"); return }
        try {
            CallReplySmsSender.send(c, id, call, message(c), driving = true)
            record(c, "DRIVING_SMS_REQUESTED")
        } catch (_: Exception) { record(c, "DRIVING_SMS_NOT_CONFIRMED"); }
    }
}
