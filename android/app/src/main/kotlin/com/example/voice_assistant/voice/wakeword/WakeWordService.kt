package com.example.voice_assistant.voice.wakeword

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import com.example.voice_assistant.MainActivity
import com.example.voice_assistant.lockscreen.LockScreenPolicy
import com.example.voice_assistant.native_bridge.contracts.*
import com.example.voice_assistant.stt.AndroidSpeechToTextEngine
import com.example.voice_assistant.tts.AndroidTextToSpeechEngine
import com.example.voice_assistant.voice.SpeechAudioFocus
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

/** Explicitly enabled, repeating detection until stopped. Never restarts on boot or process death. */
class WakeWordService : Service() {
    companion object {
        const val STOP = "com.example.voice_assistant.STOP_WAKE_WORD"
        private const val CHANNEL = "wake_word_listening"
        private const val NOTIFICATION = 4810
        @Volatile var active = false; private set
        @Volatile var report: Map<String, Any?> = mapOf("state" to "STOPPED") ; private set
        // Main-thread callback, registered only by the live, resumed native bridge.
        var foregroundHandoff: (() -> Boolean)? = null
        @Volatile var enabled = false; private set
        var handoff: WakeHandoff? = null; private set
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var stt: AndroidSpeechToTextEngine
    private lateinit var tts: AndroidTextToSpeechEngine
    private lateinit var focus: SpeechAudioFocus
    private val audioEvidence = WakeAudioEvidence()
    private var lockedDetections = 0
    private var unlockedDetections = 0
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        stt = AndroidSpeechToTextEngine(applicationContext) { }
        tts = AndroidTextToSpeechEngine(applicationContext) { }
        focus = SpeechAudioFocus(applicationContext)
    }
    private fun update(state: String, fields: Map<String, Any?> = emptyMap()) {
        val failureStage = if (state == "ERROR") mapOf("errorStage" to report["state"]) else emptyMap()
        report = report + fields + failureStage + mapOf("state" to state, "timestampMs" to System.currentTimeMillis())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP || intent == null) { stopSelf(); return START_NOT_STICKY }
        if (job != null) return START_NOT_STICKY
        try {
            check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            val notifications = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) notifications.createNotificationChannel(
                NotificationChannel(CHANNEL, "Hey Indo listening", NotificationManager.IMPORTANCE_LOW))
            check(notifications.areNotificationsEnabled())
            if (Build.VERSION.SDK_INT >= 26) check(notifications.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE)
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION, notification("Starting offline wake-word detector"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION, notification("Starting offline wake-word detector"))
            active = true
            enabled = true
            report = mapOf("state" to "STARTING", "engine" to "sherpa-onnx 1.13.8 / phonetic Zipformer KWS 3M 2025-12-20",
                "phrase" to "Hey Indo", "executionMode" to "LOCAL", "sessionLimitMs" to null, "continuous" to true,
                "supportedPhrases" to listOf("Hey Indo", "Hey Aiko"),
                "keywordsScore" to SherpaWakeWordEngine.KEYWORDS_SCORE,
                "keywordsThreshold" to SherpaWakeWordEngine.KEYWORDS_THRESHOLD,
                "maxActivePaths" to 8, "numThreads" to 1,
                "accuracyEvidence" to "PENDING_PHYSICAL_DEVICE_TEST", "startedAtMs" to System.currentTimeMillis())
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aiko:WakeWord").apply {
                acquire() // Held only while this explicitly enabled foreground service runs.
            }
            job = scope.launch {
                val routeRecovery = WakeRouteRecovery()
                val detector: WakeWordEngine = SherpaWakeWordEngine(applicationContext)
                try {
                    while (isActive) {
                        if (ReplyMicrophonePause.requested) {
                            active = false
                            update("PAUSED_FOR_REPLY")
                            notifications.notify(NOTIFICATION, notification("Wake listening paused for reply review"))
                            while (ReplyMicrophonePause.requested) delay(50)
                            active = true
                            update("STARTING")
                        }
                        var captureReady = false
                        update("STARTING", mapOf("bluetoothMicrophoneVerified" to false, "actualInputDeviceType" to null))
                        val detected = try { detectUnlessReplyPaused(detector, onReady = { loadMs ->
                            captureReady = true
                            update("LISTENING", mapOf("modelAndAudioLoadMs" to loadMs))
                            notifications.notify(NOTIFICATION, notification("Say Hey Indo — tap Stop to end listening"))
                        }, onAudio = { fields ->
                            (fields["audioSecondsProcessed"] as? Double)?.let(routeRecovery::captured)
                            val locked = getSystemService(KeyguardManager::class.java).isDeviceLocked
                            val evidence = audioEvidence.record(locked, fields["audioRmsDbfs"] as? Double,
                                fields["microphoneSilencedByAndroid"] as? Boolean, System.currentTimeMillis())
                            val silenced = fields["microphoneSilencedByAndroid"] == true
                            val captureState = if (silenced) "MICROPHONE_SILENCED_BY_ANDROID" else if (captureReady) "LISTENING" else "STARTING"
                            if (report["state"] != captureState) notifications.notify(NOTIFICATION, notification(
                                if (silenced) "Android silenced voice input. Use the phone call controls."
                                else "Say Hey Indo — tap Stop to end listening"))
                            update(captureState, fields + evidence + mapOf(
                                "currentlyLocked" to locked,
                                "currentlyScreenInteractive" to getSystemService(PowerManager::class.java).isInteractive,
                                "lockedDetectionCount" to lockedDetections,
                                "unlockedDetectionCount" to unlockedDetections))
                        }) } catch (failure: SpeechFailure) {
                            if (!routeRecovery.retry(failure.code)) throw failure
                            update("AUDIO_ROUTE_CHANGED", mapOf("audioRouteError" to failure.code,
                                "audioRouteRetry" to routeRecovery.attempts, "bluetoothMicrophoneVerified" to false))
                            notifications.notify(NOTIFICATION, notification("Reconnecting voice microphone"))
                            delay(1000L * routeRecovery.attempts)
                            continue
                        } ?: continue
                        if (ReplyMicrophonePause.requested) continue
                        // awaitDetection closes AudioRecord before another engine acquires the mic.
                        if (getSystemService(KeyguardManager::class.java).isDeviceLocked) lockedDetections++ else unlockedDetections++
                        update("DETECTED", mapOf("capturedSeconds" to detected.audioSeconds, "decodeWallMs" to detected.decodeMs,
                            "lockedDetectionCount" to lockedDetections, "unlockedDetectionCount" to unlockedDetections,
                            "detectedAtMs" to System.currentTimeMillis(),
                            "screenInteractiveAtDetection" to getSystemService(PowerManager::class.java).isInteractive,
                            "lockedAtDetection" to getSystemService(KeyguardManager::class.java).isDeviceLocked))
                        active = false
                        if (android.service.voice.VoiceInteractionService.isActiveService(this@WakeWordService,
                                android.content.ComponentName(this@WakeWordService, com.example.voice_assistant.lockscreen.AikoVoiceInteractionService::class.java))) {
                            val ticket = SystemWakeHandoff.begin()
                            try {
                                update("SYSTEM_SESSION_REQUESTED")
                                notifications.notify(NOTIFICATION, notification("Handling your command — wake detector paused"))
                                if (!com.example.voice_assistant.lockscreen.AikoVoiceInteractionService.showWakeSession(ticket.id))
                                    throw SpeechFailure("SYSTEM_ASSISTANT_UNAVAILABLE")
                                withTimeout(20000) { ticket.claimed.await() }
                                update("SYSTEM_SESSION_ACTIVE")
                                withTimeout(180000) { ticket.finished.await() }
                            } finally { SystemWakeHandoff.end(ticket) }
                            active = true
                            delay(500)
                            continue
                        }
                        // System sessions prompt only after their Activity is ready.
                        awaitAcknowledgement()
                        val transfer = WakeHandoff()
                        handoff = transfer
                        if (foregroundHandoff?.invoke() == true) {
                            update("HANDED_TO_FOREGROUND_PIPELINE")
                            transfer.awaitCompletion()
                        }
                        else {
                            active = true
                            try { respondWithoutOpeningActivities() }
                            catch (_: TimeoutCancellationException) { reportCommandFailure("VOICE_TIMEOUT") }
                            catch (e: SpeechFailure) { reportCommandFailure(e.code) }
                            finally { stt.cancel(); tts.stop(); focus.release() }
                        }
                        handoff = null
                        active = true
                        delay(500)
                    }
                } catch (_: TimeoutCancellationException) { update("SESSION_EXPIRED") }
                catch (_: CancellationException) { update("STOPPED") }
                catch (e: SpeechFailure) { update("ERROR", mapOf("error" to e.code)) }
                catch (_: LinkageError) { update("ERROR", mapOf("error" to "WAKE_RUNTIME_UNAVAILABLE")) }
                catch (_: Exception) { update("ERROR", mapOf("error" to "WAKE_AUDIO_OR_MODEL_FAILED")) }
                finally {
                    withContext(NonCancellable + Dispatchers.Default) { detector.close() }
                    stt.cancel(); tts.stop(); focus.release()
                    active = false
                    enabled = false; handoff = null
                    wakeLock?.let { if (it.isHeld) it.release() }
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                }
            }
        } catch (_: Exception) {
            update("ERROR", mapOf("error" to "MICROPHONE_SERVICE_OR_NOTIFICATION_NOT_ALLOWED"))
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private suspend fun respondWithoutOpeningActivities() {
        val current = currentCoroutineContext()[Job]
        val id = "req-${System.currentTimeMillis()}-0"
        suspend fun say(text: String) {
            focus.acquire { current?.cancel() }
            val spoken = tts.speak(id, text)
            if (spoken is CapabilityResult.Unavailable) throw SpeechFailure(spoken.code)
        }
        val options = com.example.voice_assistant.stt.SavedSpeechOptions.read(applicationContext)
        update("COMMAND_STT", mapOf("lastCommandError" to null,
            "allowSystemRecognition" to options.allowSystemRecognition, "preferSystemRecognition" to options.preferSystemRecognition))
        val recognized = withTimeout(30000) { stt.recognize(VoiceRequest(id, options = options)) }
        if (recognized is CapabilityResult.Unavailable) throw SpeechFailure(recognized.code)
        val value = (recognized as CapabilityResult.Available).value
        val outcome = com.example.voice_assistant.lockscreen.LockedCommandResponder(
            com.example.voice_assistant.notifications.LocalReminderScheduler(applicationContext),
            com.example.voice_assistant.communications.VoiceCallReplyExecutor(applicationContext) {
                enabled && current?.isActive == true
            }, drivingMode = { on ->
                com.example.voice_assistant.communications.DrivingMode.set(applicationContext, on, voiceRequested = true)
            }).respond(value.transcript, value.isFinal)
        update("RESPONDING", mapOf("sttLatencyMs" to value.latencyMs))
        say(outcome.message)
        update(outcome.code, mapOf("ttsStartupMs" to tts.startupMs, "actionStatus" to outcome.status.name))
    }
    private suspend fun detectUnlessReplyPaused(detector: WakeWordEngine, onReady: (Double) -> Unit,
        onAudio: (Map<String, Any?>) -> Unit): WakeWordDetection? =
        detectionUntilReplyPause(detector, { ReplyMicrophonePause.requested }, onReady, onAudio)
    private suspend fun reportCommandFailure(code: String) {
        stt.cancel()
        update("COMMAND_FAILED", mapOf("lastCommandError" to code, "sttEngine" to stt.engine,
            "sttMode" to stt.mode, "sttDiagnostics" to stt.diagnostics))
        val message = when (code) {
            "OFFLINE_ENGLISH_UNAVAILABLE", "STT_LANGUAGE_NOT_INSTALLED", "STT_LANGUAGE_UNSUPPORTED" ->
                "English speech recognition is unavailable. Unlock Aiko and check speech settings."
            "STT_NO_MATCH", "STT_NO_SPEECH", "VOICE_TIMEOUT" -> "I could not recognize the command. Say Hey Indo and try again after the acknowledgement."
            else -> "Speech recognition failed. Unlock Aiko to check Hey Indo diagnostics."
        }
        try {
            val current = currentCoroutineContext()[Job]
            focus.acquire { current?.cancel() }
            tts.speak("req-${System.currentTimeMillis()}-0", message)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { update("COMMAND_FAILED", mapOf("errorFeedbackUnavailable" to true)) }
        finally { tts.stop(); focus.release() }
    }
    private suspend fun awaitAcknowledgement() {
        com.example.voice_assistant.communications.IncomingCallVoicePreparation.prepare(applicationContext)
        val current = currentCoroutineContext()[Job]
        focus.acquire { current?.cancel() }
        try {
            val audio = getSystemService(android.media.AudioManager::class.java)
            update("ACKNOWLEDGING", mapOf("mediaVolume" to audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)))
            val spoken = tts.speak("req-${System.currentTimeMillis()}-0", "Yes?")
            if (spoken is CapabilityResult.Unavailable) throw SpeechFailure(spoken.code)
            update("ACKNOWLEDGED", mapOf("acknowledgementCompleted" to true, "ttsEngine" to tts.engine,
                "selectedVoice" to tts.selectedVoice, "ttsStartupMs" to tts.startupMs))
        } finally { tts.stop(); focus.release() }
    }
    @Suppress("DEPRECATION")
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 4810, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 4811, Intent(this, WakeWordService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Aiko microphone is active")
            .setContentText(message).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, "Stop listening", stop).build()).build()
    }
    override fun onDestroy() {
        enabled = false
        if (report["state"] != "ERROR") update("STOPPED")
        job?.cancel(); scope.cancel(); stt.close(); tts.close(); focus.release()
        wakeLock?.let { if (it.isHeld) it.release() }
        // Detector cleanup finishes on its worker before another session may start.
        if (job == null || job?.isCompleted == true) active = false
        super.onDestroy()
    }
}
