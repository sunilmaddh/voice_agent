package com.example.voice_assistant.communications

import android.app.*
import android.os.*
import android.telecom.*
import android.telephony.TelephonyManager
import android.view.WindowManager
import android.widget.*
import com.example.voice_assistant.native_bridge.contracts.*
import com.example.voice_assistant.notifications.ReplyVoiceDecision
import com.example.voice_assistant.stt.AndroidSpeechToTextEngine
import com.example.voice_assistant.stt.SavedSpeechOptions
import com.example.voice_assistant.tts.AndroidTextToSpeechEngine
import com.example.voice_assistant.voice.SpeechAudioFocus
import com.example.voice_assistant.voice.wakeword.ReplyMicrophonePause
import kotlinx.coroutines.*

/** Native UI stays available independently of Flutter and the wake/STT microphone pipeline. */
@Suppress("DEPRECATION")
class PhoneCallActivity : Activity() {
    private var service: AikoInCallService? = null
    private var reviewDialog: AlertDialog? = null
    private var pendingReview: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var replyVoiceJob: Job? = null
    private var activeReview: IncomingCallReply.Review? = null
    private var resumed = false
    private val observer: () -> Unit = {
        activeReview?.let { if (service?.reply?.isCurrent(it) != true) {
            replyVoiceJob?.cancel(); reviewDialog?.dismiss(); activeReview = null
        } }
        render()
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }
    override fun onResume() {
        super.onResume(); resumed = true; service = AikoInCallService.instance; service?.observers?.add(observer); render()
        intent.getStringExtra("reviewCallId")?.let { id ->
            intent.removeExtra("reviewCallId")
            pendingReview = id
        }
        presentPendingReview()
    }
    override fun onNewIntent(value: android.content.Intent) {
        super.onNewIntent(value); setIntent(value)
        value.getStringExtra("reviewCallId")?.let { id ->
            value.removeExtra("reviewCallId")
            pendingReview = id; presentPendingReview()
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) presentPendingReview() }
    private fun presentPendingReview() {
        if (!hasWindowFocus() || isFinishing || isDestroyed) return
        pendingReview?.let { id -> pendingReview = null; review(id) }
    }
    override fun onPause() {
        resumed = false; pendingReview = null; replyVoiceJob?.cancel(); activeReview = null
        reviewDialog?.dismiss(); reviewDialog = null; service?.reply?.cancel()
        service?.live?.values?.forEach { call -> execute { call.stopDtmfTone() } }
        service?.observers?.remove(observer); super.onPause()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private fun execute(action: () -> Unit) {
        try { action() } catch (_: Exception) { Toast.makeText(this, "Android could not complete this call action.", Toast.LENGTH_LONG).show() }
    }
    private fun render() {
        val current = service
        val ui = PhoneCallViews(this)
        val layout = ui.column().apply { setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(24)) }
        fun label(value: String) = layout.addView(ui.text(value, 14f, ui.muted))
        fun button(title: String, action: () -> Unit) = layout.addView(ui.button(title) { execute(action) })
        label("AIKO  •  PHONE")
        if (current?.callerVoiceCaptureActive == true)
            label("After Aiko's prompt, say Accept the call or Cut the call. Cut sends your saved SMS before declining.")
        if (current == null || current.live.isEmpty()) {
            ui.identity(layout, "No current call", null, "Call ended")
            button("Close") { finish() }
        }
        current?.live?.toMap()?.entries?.sortedBy { if (it.value.state == Call.STATE_RINGING) 0 else 1 }?.forEach { (id, call) ->
            val details = call.details
            val locked = getSystemService(KeyguardManager::class.java).isDeviceLocked
            val caller = if (!locked && details.handlePresentation == TelecomManager.PRESENTATION_ALLOWED)
                details.handle?.schemeSpecificPart ?: "Caller" else "Caller"
            val displayName = if (!locked && details.handlePresentation == TelecomManager.PRESENTATION_ALLOWED &&
                details.callerDisplayNamePresentation == TelecomManager.PRESENTATION_ALLOWED)
                details.callerDisplayName?.takeIf { it.isNotBlank() } else null
            ui.identity(layout, displayName ?: caller, caller, stateName(call.state))
            fun stillLive() = current.live[id] === call
            if (call.state == Call.STATE_RINGING) {
                if (locked) label("Caller details hidden while locked")
                ui.row(layout,
                    ui.button("Decline", ui.red) { execute { if (stillLive() && call.state == Call.STATE_RINGING) call.reject(false, null) } },
                    ui.button("Answer", ui.green) { execute { if (stillLive() && call.state == Call.STATE_RINGING) call.answer(VideoProfile.STATE_AUDIO_ONLY) } })
                button("Message • I'll call you later") { review(id) }
            } else if (call.state != Call.STATE_DISCONNECTED && call.state != Call.STATE_DISCONNECTING) {
                layout.addView(ui.button("End call", ui.red) { execute { if (stillLive()) call.disconnect() } })
                if (call.state == Call.STATE_ACTIVE && details.can(Call.Details.CAPABILITY_HOLD))
                    button("Hold") { if (stillLive() && call.state == Call.STATE_ACTIVE) call.hold() }
                if (call.state == Call.STATE_HOLDING)
                    button("Resume") { if (stillLive() && call.state == Call.STATE_HOLDING) call.unhold() }
                if (call.state == Call.STATE_SELECT_PHONE_ACCOUNT) {
                    val accounts = try { getSystemService(TelecomManager::class.java).callCapablePhoneAccounts }
                        catch (_: SecurityException) { emptyList<PhoneAccountHandle>() }
                    if (accounts.isEmpty()) label("No selectable accounts. Unlock and grant Phone state permission in Aiko Phone setup, or cancel this call.")
                    accounts.forEachIndexed { index, account -> button("Use SIM/account ${index + 1}") {
                        if (stillLive() && call.state == Call.STATE_SELECT_PHONE_ACCOUNT) call.phoneAccountSelected(account, false)
                    } }
                }
                if (call.state == Call.STATE_ACTIVE) {
                    label("Keypad")
                    for (digits in "123456789*0#".chunked(3)) {
                        val keys = digits.map { digit -> ui.button(digit.toString()) {
                            if (stillLive() && call.state == Call.STATE_ACTIVE) execute {
                                call.playDtmfTone(digit)
                                window.decorView.postDelayed({ execute { if (stillLive()) call.stopDtmfTone() } }, 150)
                            }
                        } }.toTypedArray()
                        ui.row(layout, *keys)
                    }
                    for (other in call.conferenceableCalls.toList()) button("Merge with another call") {
                        if (stillLive() && other in call.conferenceableCalls) call.conference(other)
                    }
                }
            }
        }
        current?.callAudioState?.takeIf { current.live.values.any { it.state == Call.STATE_ACTIVE || it.state == Call.STATE_HOLDING } }?.let { audio ->
            button(if (audio.isMuted) "Unmute" else "Mute") { current.setMuted(!audio.isMuted) }
            listOf(CallAudioState.ROUTE_EARPIECE to "Earpiece", CallAudioState.ROUTE_SPEAKER to "Speaker",
                CallAudioState.ROUTE_BLUETOOTH to "Bluetooth", CallAudioState.ROUTE_WIRED_HEADSET to "Wired headset").forEach { (route, name) ->
                if (audio.supportedRouteMask and route != 0) button(if (audio.route == route) "$name • selected" else name) { current.setAudioRoute(route) }
            }
        }
        if (!current?.lastResult.isNullOrEmpty()) label(when (current!!.lastResult) {
            "CALL_VOICE_PREPARING_TO_LISTEN" -> "Preparing to listen. Wait until Aiko has finished speaking."
            "CALL_VOICE_LISTENING" -> "Listening: say Accept the call or Cut the call."
            "CALL_VOICE_ANSWER_REQUESTED" -> "Answer requested."
            "CALL_VOICE_CANCELLED_CALL_UNCHANGED", "CALL_VOICE_UNRECOGNIZED_CALL_UNCHANGED",
            "CALL_VOICE_TIMEOUT_CALL_UNCHANGED", "STT_NO_MATCH", "STT_NO_SPEECH" ->
                "No clear accept or cut command. The call is unchanged. Use the buttons to answer or decline."
            "CALL_REPLY_SMS_PENDING" -> "Waiting for the SMS send result. The call will only be declined after a successful send result."
            else -> current.lastResult
        })
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(android.graphics.Color.rgb(24, 43, 67), android.graphics.Color.rgb(9, 17, 31)))
            addView(layout)
        }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        window.statusBarColor = android.graphics.Color.rgb(24, 43, 67)
        window.navigationBarColor = android.graphics.Color.rgb(9, 17, 31)
        window.decorView.systemUiVisibility = 0
        setContentView(scroll)
        scroll.requestApplyInsets()
    }
    private fun review(id: String) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isDeviceLocked) {
            if (Build.VERSION.SDK_INT >= 26) keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { pendingReview = id; presentPendingReview() }
            })
            else Toast.makeText(this, "Unlock before replying.", Toast.LENGTH_LONG).show()
            return
        }
        val current = service ?: return
        CallReplySmsSender.restriction(this, current.live[id])?.let { code ->
            Toast.makeText(this, "$code. Check SMS sending and Phone state permissions in Aiko Phone setup. Call unchanged.", Toast.LENGTH_LONG).show()
            return
        }
        replyVoiceJob?.cancel()
        reviewDialog?.dismiss()
        activeReview = null
        val value = current.reply.prepare(id)
        if (value == null) {
            Toast.makeText(this, "Reply unavailable: call must still be ringing, with a visible phone number and provider text-reply support.", Toast.LENGTH_LONG).show()
            return
        }
        activeReview = value
        val description = "To: ${value.target.address}\n\n${IncomingCallReply.MESSAGE}\n\nSend SMS using the incoming call's SIM, then decline only after a successful send result. Carrier charges may apply. After the prompt, say Send reply or Cancel reply."
        val dialog = AlertDialog.Builder(this).setTitle("Decline call and send SMS?")
            .setMessage(description)
            .setNegativeButton("Cancel") { _, _ -> current.reply.cancel() }
            .setPositiveButton("Decline and send SMS") { _, _ ->
                replyVoiceJob?.cancel()
                if (resumed) current.confirm(value)
            }
            .create()
        dialog.setOnDismissListener {
            replyVoiceJob?.cancel(); current.reply.cancel(); activeReview = null
        }
        reviewDialog = dialog
        dialog.show()
        val previous = replyVoiceJob
        replyVoiceJob = scope.launch {
            previous?.join()
            if (!resumed || !dialog.isShowing || !current.reply.isCurrent(value)) return@launch
            val stt = AndroidSpeechToTextEngine(applicationContext) {}
            val tts = AndroidTextToSpeechEngine(applicationContext) {}
            val focus = SpeechAudioFocus(applicationContext)
            try {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    throw SpeechFailure("MICROPHONE_PERMISSION_REQUIRED")
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                ReplyMicrophonePause.use {
                    withTimeout(45000) {
                        coroutineScope {
                            val guard = launch {
                                while (isActive) {
                                    if (!resumed || !dialog.isShowing || !current.reply.isCurrent(value))
                                        throw SpeechFailure("CALL_REPLY_REVIEW_CHANGED_OR_EXPIRED")
                                    delay(100)
                                }
                            }
                            try {
                                // Silence only the ringtone after the user requests this review. Do not answer the call.
                                IncomingCallVoicePreparation.prepare(applicationContext)
                                focus.acquire { replyVoiceJob?.cancel() }
                                tts.options = SavedSpeechOptions.read(applicationContext)
                                val spoken = tts.speak("req-${System.currentTimeMillis()}-0",
                                    "Reply to the caller ending ${value.target.address.takeLast(4).toList().joinToString(" ")}: I will call you later. This will decline the call. Say Send reply to confirm, or Cancel reply. Listen now.")
                                if (spoken is CapabilityResult.Unavailable) throw SpeechFailure(spoken.code)
                                dialog.setMessage("$description\n\nListening for Send reply or Cancel reply.")
                                val recognized = withTimeout(20000) {
                                    stt.recognize(VoiceRequest("req-${System.currentTimeMillis()}-0",
                                        options = SavedSpeechOptions.read(applicationContext)))
                                }
                                if (recognized is CapabilityResult.Unavailable) throw SpeechFailure(recognized.code)
                                val speech = (recognized as CapabilityResult.Available).value
                                currentCoroutineContext().ensureActive()
                                if (!resumed || !dialog.isShowing || !current.reply.isCurrent(value)) return@coroutineScope
                                when (ReplyVoiceDecision.parse(speech.transcript, speech.isFinal)) {
                                    ReplyVoiceDecision.SEND -> {
                                        // Stop the guard before the confirmation intentionally consumes the review.
                                        guard.cancel()
                                        activeReview = null
                                        current.confirmSpoken(value, speech.transcript, speech.isFinal)
                                        dialog.dismiss()
                                    }
                                    ReplyVoiceDecision.CANCEL -> {
                                        guard.cancel(); activeReview = null
                                        current.confirmSpoken(value, speech.transcript, speech.isFinal); dialog.dismiss()
                                    }
                                    ReplyVoiceDecision.UNRECOGNIZED -> dialog.setMessage("$description\n\nNo explicit confirmation recognized. Nothing sent. Use the buttons or cancel and try again.")
                                }
                            } finally { guard.cancel() }
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                if (dialog.isShowing) dialog.setMessage("$description\n\nVoice confirmation timed out. Nothing sent. Use the buttons or cancel.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: SpeechFailure) {
                if (dialog.isShowing) dialog.setMessage("$description\n\nVoice confirmation unavailable: ${failure.code}. Nothing was sent. Use the buttons or cancel.")
            } catch (_: Exception) {
                if (dialog.isShowing) dialog.setMessage("$description\n\nVoice confirmation unavailable. Nothing was sent or retried.")
            } finally {
                stt.close(); tts.close(); focus.release()
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
    private fun stateName(state: Int) = when (state) {
        Call.STATE_RINGING -> "Incoming"; Call.STATE_ACTIVE -> "Active"; Call.STATE_HOLDING -> "On hold"
        Call.STATE_DIALING -> "Dialing"; Call.STATE_CONNECTING -> "Connecting"
        Call.STATE_SELECT_PHONE_ACCOUNT -> "Choose account"; Call.STATE_DISCONNECTING -> "Ending"
        Call.STATE_DISCONNECTED -> "Ended"; else -> "Connecting / updating"
    }
}
