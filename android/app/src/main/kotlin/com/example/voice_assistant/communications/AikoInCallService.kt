package com.example.voice_assistant.communications

import android.app.*
import android.content.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.media.AudioManager
import android.telecom.*
import java.util.UUID

/** Telecom owns calls. The default dialer owns incoming ringing via a system notification channel. */
@Suppress("DEPRECATION")
class AikoInCallService : InCallService() {
    companion object {
        var instance: AikoInCallService? = null; private set
        const val CHANNEL = "aiko_phone_calls"
        private const val NOTICE = 5830
        private const val RING_NOTICE = 5831
        private const val RING_CHANNEL = "aiko_incoming_ringing_v2"
        private const val VOICE_RING_CHANNEL = "aiko_incoming_voice_v1"
        fun isDefault(context: Context) =
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage == context.packageName
    }
    val live = linkedMapOf<String, Call>()
    val observers = linkedSetOf<() -> Unit>()
    private val callbacks = mutableMapOf<String, Call.Callback>()
    private val announcer by lazy { CallerAnnouncer(this) }
    private val ringState = IncomingRingState()
    private var ringingNoticeId: String? = null
    private var phoneNotice: Notification? = null
    private var callerVoiceCaptureId: String? = null
    private var destroying = false
    val callerVoiceCaptureActive: Boolean get() = callerVoiceCaptureId != null
    var callAudioStatus = "NOT_TESTED"; private set
    var foregroundPhoneCall = false; private set
    fun beginCallerVoiceCapture(id: String): String? {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return "CALL_VOICE_MICROPHONE_PERMISSION_REQUIRED"
        if (destroying || instance !== this || !isDefault(this) || live[id]?.state != Call.STATE_RINGING ||
            live.any { (other, call) -> other != id && call.state != Call.STATE_DISCONNECTED })
            return "CALL_VOICE_CALL_CHANGED"
        val notice = phoneNotice ?: return "CALL_VOICE_FOREGROUND_REQUIRED"
        return try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTICE, notice,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTICE, notice)
            foregroundPhoneCall = true
            callerVoiceCaptureId = id
            observers.toList().forEach { it() }
            null
        } catch (_: RuntimeException) { "CALL_VOICE_MICROPHONE_FOREGROUND_UNAVAILABLE" }
    }
    fun endCallerVoiceCapture(id: String) {
        if (callerVoiceCaptureId != id) return
        callerVoiceCaptureId = null
        if (!destroying && live.isNotEmpty()) changed()
        else observers.toList().forEach { it() }
    }
    fun callerSilenced(id: String) = ringState.isSilenced(id)
    fun announcementAlertRestriction(): String? {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = if (VoiceFriendlyRinging.vibrationOnly(DrivingMode.voiceFriendlyRinging(this),
            com.example.voice_assistant.voice.wakeword.WakeWordService.enabled)) VOICE_RING_CHANNEL else RING_CHANNEL
        return CallerAnnouncementPolicy.alertBlocked(manager.areNotificationsEnabled(),
            Build.VERSION.SDK_INT < 26 || manager.getNotificationChannel(channel)?.importance?.let {
                it != NotificationManager.IMPORTANCE_NONE
            } == true)
    }
    fun pauseRingingForAnnouncement(id: String) {
        check(live[id]?.state == Call.STATE_RINGING && !callerSilenced(id)) { "ANNOUNCEMENT_CALL_CHANGED" }
        ringState.pause(id); updateRinging(false)
    }
    fun resumeRingingAfterAnnouncement(id: String) { ringState.resume(id); updateRinging(false) }
    fun silenceIncomingCalls() {
        ringState.silence(live.filterValues { it.state == Call.STATE_RINGING }.keys)
        changed()
    }
    override fun onSilenceRinger() { super.onSilenceRinger(); silenceIncomingCalls() }
    fun refreshCallerAnnouncement() { announcer.changed() }
    lateinit var reply: IncomingCallReply; private set
    var lastResult = ""; private set
    fun reportCallerVoiceResult(code: String) {
        lastResult = code
        observers.toList().forEach { it() }
    }
    override fun onCreate() {
        super.onCreate(); instance = this
        reply = IncomingCallReply(object : IncomingReplyPort {
            override fun restriction(id: String) = CallReplySmsSender.restriction(this@AikoInCallService, live[id])
            override fun defaultDialer() = isDefault(this@AikoInCallService)
            override fun unlocked() = !getSystemService(KeyguardManager::class.java).isDeviceLocked
            override fun target(id: String) = live[id]?.let { call ->
                val details = call.details
                IncomingReplyTarget(id, details.handle?.takeIf { it.scheme == "tel" }?.schemeSpecificPart.orEmpty(),
                    call.state == Call.STATE_RINGING, details.handlePresentation == TelecomManager.PRESENTATION_ALLOWED,
                    details.can(Call.Details.CAPABILITY_RESPOND_VIA_TEXT))
            }
            override fun sendReply(id: String, text: String) {
                CallReplySmsSender.send(this@AikoInCallService, id, live.getValue(id), text)
            }
        })
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Aiko phone calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming and ongoing call controls"; lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null) // Ongoing controls; incoming ringing uses a separate channel.
            })
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(RING_CHANNEL, "Incoming call ringtone", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Ringtone and vibration while Aiko is the default Phone app"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
                    android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(true)
            })
    }
    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val id = UUID.randomUUID().toString()
        live[id] = call
        val cb = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) { changed() }
            override fun onDetailsChanged(call: Call, details: Call.Details) { changed() }
            override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: MutableList<Call>) { changed() }
        }
        callbacks[id] = cb
        call.registerCallback(cb, Handler(Looper.getMainLooper()))
        changed(call.state == Call.STATE_RINGING)
        // One attempt per onCallAdded, never from repeated details/state callbacks.
        DrivingMode.incoming(this, id, call)
        if (call.state != Call.STATE_RINGING) openUi()
    }
    override fun onCallRemoved(call: Call) {
        live.entries.firstOrNull { it.value === call }?.key?.let { id ->
            callbacks.remove(id)?.let(call::unregisterCallback)
            live.remove(id); reply.forget(id); CallReplySmsSender.forgetCall(id)
        }
        changed(); super.onCallRemoved(call)
    }
    override fun onBringToForeground(showDialpad: Boolean) { openUi() }
    override fun onCallAudioStateChanged(audioState: CallAudioState) { super.onCallAudioStateChanged(audioState); changed() }
    fun confirm(review: IncomingCallReply.Review) {
        lastResult = reply.confirm(review)
        changed()
    }
    fun confirmSpoken(review: IncomingCallReply.Review, transcript: String, isFinal: Boolean) {
        lastResult = reply.confirmSpoken(review, transcript, isFinal)
        changed()
    }
    fun openUi() {
        try { startActivity(Intent(this, PhoneCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        catch (_: Exception) { lastResult = "CALL_UI_NOTIFICATION_REQUIRED" }
    }
    fun smsResultChanged() { lastResult = CallReplySmsSender.lastStatus(this); changed() }
    private fun changed(incoming: Boolean = false) {
        ringState.update(live.filterValues { it.state == Call.STATE_RINGING }.keys)
        observers.toList().forEach { it() }
        val manager = getSystemService(NotificationManager::class.java)
        if (live.isEmpty()) {
            stopForeground(true); foregroundPhoneCall = false
            phoneNotice = null
            manager.cancel(NOTICE); manager.cancel(RING_NOTICE); ringingNoticeId = null
            announcer.changed(); return
        }
        val ringing = live.values.any { it.state == Call.STATE_RINGING }
        val pending = PendingIntent.getActivity(this, NOTICE, Intent(this, PhoneCallActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        builder.setSmallIcon(android.R.drawable.sym_action_call).setContentTitle(if (ringing) "Incoming call" else "Aiko phone call")
            .setContentText("Tap for call controls").setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(pending)
        val notice = builder.build()
        phoneNotice = notice
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTICE, notice,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                    if (Build.VERSION.SDK_INT >= 30 && callerVoiceCaptureActive)
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            else startForeground(NOTICE, notice)
            foregroundPhoneCall = true
        } catch (_: RuntimeException) {
            foregroundPhoneCall = false; callAudioStatus = "PHONE_FOREGROUND_UNAVAILABLE"
            try { manager.notify(NOTICE, notice) } catch (_: SecurityException) { lastResult = "CALL_NOTIFICATION_BLOCKED" }
        }
        updateRinging(incoming)
        announcer.changed()
    }
    private fun updateRinging(incoming: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        val ringing = live.filterValues { it.state == Call.STATE_RINGING }.keys
        val otherActive = live.values.any { it.state !in setOf(Call.STATE_RINGING, Call.STATE_DISCONNECTED) }
        val id = ringState.alertId(ringing, otherActive, DrivingMode.enabled(this))
        if (id == null) {
            manager.cancel(RING_NOTICE); ringingNoticeId = null; return
        }
        if (id == ringingNoticeId) return // Do not restart ringtone on details/state refreshes.
        manager.cancel(RING_NOTICE)
        val pending = PendingIntent.getActivity(this, RING_NOTICE, Intent(this, PhoneCallActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val vibrationOnly = VoiceFriendlyRinging.vibrationOnly(DrivingMode.voiceFriendlyRinging(this),
            com.example.voice_assistant.voice.wakeword.WakeWordService.enabled)
        val ringChannel = if (vibrationOnly) VOICE_RING_CHANNEL else RING_CHANNEL
        if (Build.VERSION.SDK_INT >= 26 && vibrationOnly) manager.createNotificationChannel(
            NotificationChannel(VOICE_RING_CHANNEL, "Voice-friendly incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Vibration without ringtone while wake listening is enabled"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(true)
            })
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ringChannel) else Notification.Builder(this)
        builder.setSmallIcon(android.R.drawable.sym_action_call).setContentTitle("Incoming call")
            .setContentText("Tap to answer or decline").setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(true).setContentIntent(pending)
        if (Build.VERSION.SDK_INT < 26) {
            builder.setPriority(Notification.PRIORITY_MAX).setDefaults(Notification.DEFAULT_VIBRATE)
            if (!vibrationOnly) builder.setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE), AudioManager.STREAM_RING)
        }
        if (incoming && (Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent())) builder.setFullScreenIntent(pending, true)
        val notification = builder.build().apply { flags = flags or Notification.FLAG_INSISTENT }
        try {
            manager.notify(RING_NOTICE, notification); ringingNoticeId = id
            callAudioStatus = if (!manager.areNotificationsEnabled() || (Build.VERSION.SDK_INT >= 26 &&
                manager.getNotificationChannel(ringChannel)?.importance == NotificationManager.IMPORTANCE_NONE))
                "INCOMING_CHANNEL_BLOCKED" else if (vibrationOnly) "VOICE_FRIENDLY_VIBRATION_REQUESTED" else "INCOMING_ALERT_POSTED_NOT_AUDIBILITY_VERIFIED"
        } catch (_: SecurityException) { callAudioStatus = "INCOMING_ALERT_BLOCKED" }
    }
    override fun onDestroy() {
        destroying = true
        announcer.close()
        callbacks.forEach { (id, cb) -> live[id]?.unregisterCallback(cb) }
        callbacks.clear(); live.clear(); reply.cancel()
        observers.toList().forEach { it() }; observers.clear()
        getSystemService(NotificationManager::class.java).cancel(NOTICE)
        getSystemService(NotificationManager::class.java).cancel(RING_NOTICE)
        stopForeground(true); foregroundPhoneCall = false
        instance = null; super.onDestroy()
    }
}
