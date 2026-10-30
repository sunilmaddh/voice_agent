package com.example.voice_assistant.communications

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.ToneGenerator
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.Call
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import com.example.voice_assistant.native_bridge.contracts.*
import com.example.voice_assistant.stt.AndroidSpeechToTextEngine
import com.example.voice_assistant.stt.SavedSpeechOptions
import kotlinx.coroutines.*

/** One announcement and optional prompted voice decision, bound to the same ringing call. */
@Suppress("DEPRECATION")
class CallerAnnouncer(private val service: AikoInCallService) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val attempted = mutableSetOf<String>()
    private var job: Job? = null
    private var currentId: String? = null
    companion object {
        private fun prefs(c: Context) = c.getSharedPreferences("caller_announcement", 0)
        fun voiceControl(c: Context) = prefs(c).getBoolean("voiceControl", false)
        fun promptedReplyEnabled(c: Context) = voiceControl(c) && prefs(c).getBoolean("enabled", false) &&
            (!c.getSystemService(KeyguardManager::class.java).isDeviceLocked || prefs(c).getBoolean("allowLocked", false))
        fun status(c: Context) = mapOf("enabled" to prefs(c).getBoolean("enabled", false),
            "allowLocked" to prefs(c).getBoolean("allowLocked", false),
            "voiceControl" to voiceControl(c),
            "silentVibrateAnnouncements" to true,
            "mediaVolume" to c.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_MUSIC),
            "replyMessage" to DrivingMode.message(c),
            "microphoneGranted" to (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED),
            "smsGranted" to (c.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED),
            "phoneStateGranted" to (c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED),
            "defaultPhone" to AikoInCallService.isDefault(c),
            "contactsGranted" to (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED),
            "lastEvent" to prefs(c).getString("lastEvent", "NOT_TESTED"),
            "lastEventAtMs" to prefs(c).getLong("lastEventAtMs", 0),
            "ringtoneOwner" to "AIKO_INCALL_SERVICE",
            "ringtoneStatus" to (AikoInCallService.instance?.callAudioStatus ?: "NO_CALL_SERVICE"),
            "phoneForeground" to (AikoInCallService.instance?.foregroundPhoneCall ?: false),
            "deviceValidation" to "PENDING_PHYSICAL_DEVICE_TEST")
        fun set(c: Context, enabled: Boolean, allowLocked: Boolean, voiceControl: Boolean? = null) {
            check(!c.getSystemService(KeyguardManager::class.java).isDeviceLocked)
            val edit = prefs(c).edit().putBoolean("enabled", enabled).putBoolean("allowLocked", allowLocked)
            voiceControl?.let { edit.putBoolean("voiceControl", it) }
            check(edit.commit())
            AikoInCallService.instance?.refreshCallerAnnouncement()
        }
    }
    private fun record(code: String) {
        prefs(service).edit().putString("lastEvent", code).putLong("lastEventAtMs", System.currentTimeMillis()).apply()
        if (code.startsWith("CALL_VOICE_") || code.startsWith("CALL_REPLY_") || code.startsWith("STT_"))
            service.reportCallerVoiceResult(code)
    }
    private fun restriction(id: String): String? {
        if (service.callerSilenced(id)) return "CALL_SILENCED"
        val audio = service.getSystemService(AudioManager::class.java)
        val quiet = service.getSystemService(NotificationManager::class.java).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        service.announcementAlertRestriction()?.let { return it }
        val blocked = CallerAnnouncementPolicy.blocked(prefs(service).getBoolean("enabled", false), AikoInCallService.isDefault(service),
            service.live[id]?.state == Call.STATE_RINGING && service.live.all { (other, call) -> other == id || call.state == Call.STATE_DISCONNECTED },
            service.getSystemService(KeyguardManager::class.java).isDeviceLocked, prefs(service).getBoolean("allowLocked", false),
            DrivingMode.enabled(service), quiet)
        return blocked ?: if (useMediaAudio() && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0)
            "ANNOUNCEMENT_MEDIA_VOLUME_ZERO" else null
    }
    private fun useMediaAudio(): Boolean {
        val audio = service.getSystemService(AudioManager::class.java)
        return CallerAnnouncementPolicy.useMediaAudio(audio.ringerMode == AudioManager.RINGER_MODE_NORMAL,
            audio.getStreamVolume(AudioManager.STREAM_RING))
    }
    fun changed() {
        currentId?.let { if (restriction(it) != null) job?.cancel() }
        attempted.retainAll(service.live.keys)
        val entry = service.live.entries.firstOrNull { it.value.state == Call.STATE_RINGING && it.key !in attempted } ?: return
        if (job?.isActive == true) return
        attempted += entry.key
        restriction(entry.key)?.let { record(it); return }
        currentId = entry.key
        job = scope.launch {
            var stoppedReason: String? = null
            var outcome = "ANNOUNCEMENT_COMPLETED"
            try {
                withTimeout(70000) {
                    // Give Telecom a short chance to populate contact/name details.
                    delay(600)
                    val call = entry.value
                    val originalHandle = call.details.handle
                    val originalPresentation = call.details.handlePresentation
                    val originalNamePresentation = call.details.callerDisplayNamePresentation
                    val originalAccount = call.details.accountHandle
                    val contact = lookup(call)
                    val requestedVoice = voiceControl(service)
                    val replyMessage = DrivingMode.message(service)
                    fun valid() = service.live[entry.key] === call && restriction(entry.key) == null && call.details.handle == originalHandle &&
                        call.details.accountHandle == originalAccount &&
                        call.details.handlePresentation == originalPresentation && call.details.callerDisplayNamePresentation == originalNamePresentation &&
                        (contact == null || service.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) &&
                        (!requestedVoice || (voiceControl(service) && DrivingMode.message(service) == replyMessage))
                    if (!valid()) { record("ANNOUNCEMENT_CALL_CHANGED"); return@withTimeout }
                    restriction(entry.key)?.let { record(it); return@withTimeout }
                    val details = call.details
                    val text = CallerAnnouncementPolicy.phrase(details.handlePresentation == TelecomManager.PRESENTATION_ALLOWED,
                        contact, details.callerDisplayNamePresentation == TelecomManager.PRESENTATION_ALLOWED, details.callerDisplayName)
                    coroutineScope {
                        val parent = currentCoroutineContext()[Job]!!
                        val monitor = launch { while (isActive) {
                            delay(100)
                            if (!valid()) {
                                stoppedReason = restriction(entry.key) ?: "ANNOUNCEMENT_CALL_CHANGED"
                                parent.cancel()
                            }
                        } }
                        try {
                            com.example.voice_assistant.voice.wakeword.ReplyMicrophonePause.use {
                                val captureBlocked = if (requestedVoice) service.beginCallerVoiceCapture(entry.key) else null
                                val offerVoice = requestedVoice && captureBlocked == null
                                withTimeout(if (offerVoice) 20000L else 12000L) {
                                    speak(entry.key, if (offerVoice) "$text ${IncomingCallVoiceDecision.PROMPT}" else text, ::valid)
                                }
                                if (offerVoice) {
                                    // Speech has finished and TTS is closed before opening the recognizer.
                                    delay(300)
                                    val speech = captureDecision(entry.key, ::valid)
                                    currentCoroutineContext().ensureActive()
                                    check(valid()) { "CALL_VOICE_CALL_CHANGED" }
                                    // Stop the guard before this explicit decision intentionally changes call state.
                                    monitor.cancel()
                                    currentId = null
                                    val decision = IncomingCallVoiceSession(object : IncomingCallVoicePort {
                                        override fun isCurrent() = valid()
                                        override fun answer() { call.answer(VideoProfile.STATE_AUDIO_ONLY) }
                                        override fun cutAndText(): String {
                                            CallReplySmsSender.restriction(service, call, voiceRequested = true)?.let { return it }
                                            CallReplySmsSender.send(service, entry.key, call, replyMessage,
                                                voiceRequested = true, promptedReply = true)
                                            return "CALL_REPLY_SMS_PENDING"
                                        }
                                    })
                                    outcome = decision.respond(speech.transcript, speech.isFinal)
                                } else if (captureBlocked != null) outcome = captureBlocked
                            }
                        } finally {
                            monitor.cancel()
                            service.endCallerVoiceCapture(entry.key)
                            service.resumeRingingAfterAnnouncement(entry.key)
                        }
                    }
                    record(outcome)
                }
            } catch (_: TimeoutCancellationException) { record("ANNOUNCEMENT_TIMEOUT") }
            catch (_: CancellationException) { record(stoppedReason ?: restriction(entry.key) ?: "ANNOUNCEMENT_STOPPED") }
            catch (e: Exception) { record(e.message?.takeIf { it.matches(Regex("[A-Z_]{3,60}")) } ?: "ANNOUNCEMENT_UNAVAILABLE") }
            finally { if (job === currentCoroutineContext()[Job]) currentId = null }
        }
    }
    private suspend fun captureDecision(callId: String, valid: () -> Boolean): SpeechRecognitionResult {
        for (attempt in 0..1) {
            val speech = try { listenForDecision(valid) }
            catch (failure: SpeechFailure) {
                if (failure.code !in setOf("STT_NO_MATCH", "STT_NO_SPEECH")) throw failure
                SpeechRecognitionResult("", true)
            }
            check(valid()) { "CALL_VOICE_CALL_CHANGED" }
            if (!IncomingCallVoiceDecision.shouldRetry(speech.transcript, speech.isFinal, attempt))
                return speech
            record("CALL_VOICE_RETRY_PROMPT")
            withTimeout(10000) { speak(callId, IncomingCallVoiceDecision.RETRY_PROMPT, valid) }
            delay(300)
        }
        error("CALL_VOICE_UNRECOGNIZED_CALL_UNCHANGED")
    }
    private suspend fun listenForDecision(valid: () -> Boolean): SpeechRecognitionResult {
        check(valid()) { "CALL_VOICE_CALL_CHANGED" }
        var readyTone: ToneGenerator? = null
        val stt = AndroidSpeechToTextEngine(service) { event ->
            if (event.code == DiagnosticCode.LISTENING && valid()) {
                record("CALL_VOICE_LISTENING")
                // Signal actual recognizer readiness, rather than the end of TTS.
                // Match announcement audio in quiet ringer modes; never change volume.
                runCatching {
                    readyTone?.release()
                    readyTone = ToneGenerator(if (useMediaAudio()) AudioManager.STREAM_MUSIC else AudioManager.STREAM_RING, 70)
                    readyTone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                }.onFailure { record("CALL_VOICE_READY_TONE_UNAVAILABLE") }
            }
        }
        try {
            record("CALL_VOICE_PREPARING_TO_LISTEN")
            val result = withTimeoutOrNull(15000) {
                stt.recognizeIncomingCall(VoiceRequest("req-${System.currentTimeMillis()}-0",
                    options = SavedSpeechOptions.read(service)))
            } ?: error("CALL_VOICE_TIMEOUT_CALL_UNCHANGED")
            return when (result) {
                is CapabilityResult.Available -> result.value
                is CapabilityResult.Unavailable -> error(result.code)
            }
        } finally { stt.close(); readyTone?.release() }
    }
    private suspend fun lookup(call: Call): String? {
        val details = call.details
        if (details.handlePresentation != TelecomManager.PRESENTATION_ALLOWED ||
            service.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val handle = details.handle?.takeIf { it.scheme == "tel" }?.schemeSpecificPart ?: return null
        return withContext(Dispatchers.IO) {
            try {
                service.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(handle)),
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    val names = mutableSetOf<String>()
                    while (cursor.moveToNext() && names.size < 2) cursor.getString(0)?.takeIf { it.isNotBlank() }?.let(names::add)
                    names.singleOrNull()
                }
            } catch (_: SecurityException) { null }
        }
    }
    private suspend fun speak(callId: String, text: String, valid: () -> Boolean) {
        val ready = CompletableDeferred<Boolean>()
        val tts = TextToSpeech(service) { ready.complete(it == TextToSpeech.SUCCESS) }
        try {
            record("ANNOUNCEMENT_TTS_PREPARING")
            check(ready.await()) { "ANNOUNCEMENT_TTS_INIT_FAILED" }
            val voice = tts.voices.orEmpty().filter { it.locale.language == "en" && !it.isNetworkConnectionRequired &&
                !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }.sortedBy { it.name }.firstOrNull()
                ?: error("ANNOUNCEMENT_OFFLINE_VOICE_REQUIRED")
            check(tts.setVoice(voice) == TextToSpeech.SUCCESS) { "ANNOUNCEMENT_TTS_VOICE_FAILED" }
            // Normal ringing uses the owned ring channel. Silent/vibrate announcements
            // use media volume, without changing ringer mode, volume, or Telecom focus.
            check(tts.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(if (useMediaAudio()) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()) == TextToSpeech.SUCCESS) {
                "ANNOUNCEMENT_AUDIO_ATTRIBUTES_FAILED"
            }
            check(valid()) { "ANNOUNCEMENT_CALL_CHANGED" }
            service.pauseRingingForAnnouncement(callId)
            delay(200) // Allow the owned notification ringtone to stop before speech.
            currentCoroutineContext().ensureActive()
            check(valid()) { "ANNOUNCEMENT_CALL_CHANGED" }
            val done = CompletableDeferred<Boolean>()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { Handler(Looper.getMainLooper()).post {
                    if (currentId == callId && job?.isActive == true && valid()) record("ANNOUNCEMENT_STARTED")
                } }
                override fun onDone(id: String?) { done.complete(true) }
                @Deprecated("Legacy Android callback") override fun onError(id: String?) { done.complete(false) }
                override fun onError(id: String?, errorCode: Int) { done.complete(false) }
            })
            check(tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "caller-announcement") == TextToSpeech.SUCCESS && done.await()) { "ANNOUNCEMENT_TTS_FAILED" }
        } finally {
            tts.stop(); tts.shutdown()
        }
    }
    fun close() { scope.cancel(); attempted.clear() }
}
