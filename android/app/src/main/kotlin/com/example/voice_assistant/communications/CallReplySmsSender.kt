package com.example.voice_assistant.communications

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.telecom.Call
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.util.UUID

/** Explicit SMS dispatch, never a fallback after Telecom's unobservable reject-with-message path. */
@Suppress("DEPRECATION")
object CallReplySmsSender {
    private val attemptedCalls = mutableSetOf<String>()
    fun forgetCall(id: String) { attemptedCalls.remove(id) }
    private val pending = mutableMapOf<String, SmsReplyCompletion>()
    private fun prefs(context: Context) = context.getSharedPreferences("call_reply_sms_result", 0)
    fun lastStatus(context: Context) = prefs(context).getString("lastCode", "").orEmpty()
    fun restriction(context: Context, call: Call?, driving: Boolean = false, voiceRequested: Boolean = false): String? = when {
        Build.VERSION.SDK_INT < 30 -> "CALL_REPLY_REQUIRES_ANDROID_11"
        !AikoInCallService.isDefault(context) -> "DEFAULT_PHONE_REQUIRED"
        !CallReplyAccess.permitted(context.getSystemService(KeyguardManager::class.java).isDeviceLocked,
            driving, DrivingMode.enabled(context), DrivingMode.automaticReply(context), voiceRequested) ->
            if (driving) "DRIVING_REPLY_DISABLED" else "UNLOCK_REQUIRED"
        context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED -> "CALL_REPLY_SEND_SMS_PERMISSION_REQUIRED"
        context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED -> "CALL_REPLY_PHONE_STATE_PERMISSION_REQUIRED"
        call == null || call.state != Call.STATE_RINGING -> "CALL_REPLY_NOT_RINGING"
        call.details.handlePresentation != android.telecom.TelecomManager.PRESENTATION_ALLOWED ||
            call.details.handle?.scheme != "tel" ||
            !Regex("\\+?[0-9]{7,15}").matches(call.details.handle?.schemeSpecificPart.orEmpty()) -> "CALL_REPLY_INVALID_NUMBER"
        subscription(context, call) == null -> "CALL_REPLY_SIM_UNAVAILABLE"
        else -> null
    }
    private fun subscription(context: Context, call: Call): Int? {
        if (Build.VERSION.SDK_INT < 30) return null
        val account = call.details.accountHandle ?: return null
        return try {
            context.getSystemService(TelephonyManager::class.java).getSubscriptionId(account)
                .takeIf { SubscriptionManager.isValidSubscriptionId(it) }
        } catch (_: Exception) { null }
    }
    fun send(context: Context, id: String, call: Call, body: String, driving: Boolean = false,
        voiceRequested: Boolean = false, promptedReply: Boolean = false) {
        check(restriction(context, call, driving, voiceRequested) == null)
        check(if (driving) body == DrivingMode.message(context) && DrivingReply.validate(body) == body && DrivingMode.enabled(context) && DrivingMode.automaticReply(context)
            else if (promptedReply) PromptedCallReplyPolicy.allowed(body, DrivingMode.message(context),
                voiceRequested, CallerAnnouncer.promptedReplyEnabled(context))
            else body == IncomingCallReply.MESSAGE)
        check(AikoInCallService.instance?.live?.get(id) === call) { "CALL_REPLY_NOT_RINGING" }
        val address = call.details.handle?.takeIf { it.scheme == "tel" }?.schemeSpecificPart ?: error("INVALID_CALL")
        check(call.details.handlePresentation == android.telecom.TelecomManager.PRESENTATION_ALLOWED &&
            Regex("\\+?[0-9]{7,15}").matches(address))
        val subId = subscription(context, call) ?: error("SIM_UNAVAILABLE")
        val originalHandle = call.details.handle
        val originalAccount = call.details.accountHandle
        check(attemptedCalls.add(id)) { "CALL_REPLY_ALREADY_ATTEMPTED" }
        val token = UUID.randomUUID().toString()
        val sent = PendingIntent.getBroadcast(context, 0, Intent(context, CallReplySmsReceiver::class.java)
            .setAction("com.example.voice_assistant.CALL_REPLY_SMS_SENT")
            .setData(Uri.parse("aiko-call-sms:$token")), PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        check(prefs(context).edit().putBoolean("pending_$token", true).commit())
        val app = context.applicationContext
        val deadline = SystemClock.elapsedRealtime() + 60000
        pending[token] = SmsReplyCompletion(eligible = {
            val current = AikoInCallService.instance?.live?.get(id)
            current === call && current.state == Call.STATE_RINGING &&
                current.details.handle == originalHandle && current.details.accountHandle == originalAccount &&
                current.details.handlePresentation == android.telecom.TelecomManager.PRESENTATION_ALLOWED &&
                current.details.handle?.schemeSpecificPart == address &&
                SystemClock.elapsedRealtime() < deadline && AikoInCallService.isDefault(app) &&
                CallReplyAccess.permitted(app.getSystemService(KeyguardManager::class.java).isDeviceLocked,
                    driving, DrivingMode.enabled(app), DrivingMode.automaticReply(app), voiceRequested) &&
                (!promptedReply || PromptedCallReplyPolicy.allowed(body, DrivingMode.message(app),
                    voiceRequested, CallerAnnouncer.promptedReplyEnabled(app)))
        }, decline = {
            AikoInCallService.instance!!.live.getValue(id).reject(false, null)
        })
        record(context, "CALL_REPLY_SMS_PENDING")
        try {
            SmsManager.getSmsManagerForSubscriptionId(subId).sendTextMessage(address, null, body, sent, null)
        } catch (failure: Exception) {
            pending.remove(token); prefs(context).edit().remove("pending_$token").apply(); sent.cancel()
            record(context, "CALL_REPLY_SMS_OUTCOME_UNKNOWN_CALL_UNCHANGED")
            throw failure
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (pending.remove(token) != null && prefs(app).getBoolean("pending_$token", false))
                record(app, "CALL_REPLY_SMS_TIMEOUT_CALL_UNCHANGED")
        }, 60000)
    }
    fun onSent(context: Context, token: String, result: Int) {
        if (!prefs(context).getBoolean("pending_$token", false)) return
        // Consume before acting. Callback redelivery cannot disconnect a second call.
        if (!prefs(context).edit().remove("pending_$token").commit()) return
        val saved = pending.remove(token)
        if (result != Activity.RESULT_OK) {
            saved?.complete(false)
            record(context, "CALL_REPLY_SMS_FAILED_$result", result); return
        }
        record(context, saved?.complete(true) ?: "CALL_REPLY_SMS_SENT_CALL_UNCHANGED", result)
    }
    fun record(context: Context, code: String, result: Int? = null) {
        prefs(context).edit().putString("lastCode", code).putLong("timestampMs", System.currentTimeMillis()).apply()
        android.util.Log.i("AssistantPOC", org.json.JSONObject(mapOf("code" to code,
            "smsResultCode" to result, "deliveryVerified" to false)).toString())
        AikoInCallService.instance?.smsResultChanged()
    }
}

class CallReplySmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.example.voice_assistant.CALL_REPLY_SMS_SENT" || intent.data?.scheme != "aiko-call-sms") return
        val token = intent.data?.schemeSpecificPart ?: return
        if (!Regex("[a-f0-9-]{36}").matches(token)) return
        CallReplySmsSender.onSent(context, token, resultCode)
    }
}
