package com.example.voice_assistant.lockscreen

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.view.WindowManager
import android.widget.*
import com.example.voice_assistant.MainActivity
import com.example.voice_assistant.actions.*
import com.example.voice_assistant.ai.AssistantRuntime
import com.example.voice_assistant.gmail.*
import com.example.voice_assistant.native_bridge.contracts.*
import com.example.voice_assistant.notifications.*
import com.example.voice_assistant.stt.*
import com.example.voice_assistant.tts.AndroidTextToSpeechEngine
import com.example.voice_assistant.voice.*
import com.example.voice_assistant.voice.wakeword.WakeWordService
import kotlinx.coroutines.*

/** Activity presentation owned by the system voice session; no Flutter engine needed. */
@android.annotation.SuppressLint("SetTextI18n")
class SystemAssistantActivity : Activity() {
    companion object { @Volatile var active = false; private set }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var text: TextView
    private lateinit var speak: Button
    private lateinit var tts: AndroidTextToSpeechEngine
    private lateinit var focus: SpeechAudioFocus
    private lateinit var coordinator: SpeechCoordinator
    private lateinit var executor: SessionActionExecutor
    private lateinit var whatsapp: WhatsAppReplySession
    private lateinit var gmail: GmailSession
    private var job: Job? = null
    private var replyVoiceJob: Job? = null
    private var replyDialog: AlertDialog? = null
    private var resumed = false
    private var started = false
    private var authenticating = false
    private var authenticated = false
    private var openAfterUnlock = false
    private var wakeTicket: com.example.voice_assistant.voice.wakeword.SystemWakeHandoff.Ticket? = null
    private val keyguard get() = getSystemService(KeyguardManager::class.java)
    private val probe get() = LockScreenProbe(this)
    private fun locked() = keyguard.isDeviceLocked || keyguard.isKeyguardLocked
    private fun personalReads() = getSharedPreferences("assistant_privacy", 0).getBoolean("lockedPersonalReads", false)
    private fun handsFreeCalls() = getSharedPreferences("assistant_privacy", 0).getBoolean("handsFreeCalls", false)
    private fun lockedReplies() = personalReads() && getSharedPreferences("assistant_privacy", 0).getBoolean("lockedWhatsAppReplies", false)
    private fun restriction(): ActionResult? = when {
        !resumed -> ActionResult(ActionStatus.failed, "SESSION_INACTIVE", "Assistant session ended.")
        locked() && !personalReads() -> ActionResult(ActionStatus.requiresConfirmation, "UNLOCK_REQUIRED", "Unlock to read personal information.")
        else -> null
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            probe.record("STATE_CHANGED", "SYSTEM_SESSION", probe.snapshot())
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                replyVoiceJob?.cancel(); replyDialog?.dismiss()
                job?.cancel(); coordinator.cancel(); executor.clear(); whatsapp.clear()
                text.text = "Session stopped. Invoke the assistant again."
                if (wakeTicket != null) finish()
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ticketId = intent.getStringExtra(com.example.voice_assistant.voice.wakeword.SystemWakeHandoff.EXTRA)
        if (ticketId != null) {
            wakeTicket = com.example.voice_assistant.voice.wakeword.SystemWakeHandoff.claim(ticketId)
            if (wakeTicket == null) { finish(); return }
            scope.launch { wakeTicket!!.finished.await(); finish() }
        }
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        text = TextView(this).apply { textSize = 20f; text = "Aiko system assistant. Personal content is not displayed while locked." }
        speak = Button(this).apply { text = "Speak"; setOnClickListener { listen() } }
        val unlock = Button(this).apply { text = "Unlock and continue"; setOnClickListener { unlock(false) } }
        val open = Button(this).apply { text = "Open Aiko settings"; setOnClickListener { executor.clear(); unlock(true) } }
        val dismiss = Button(this).apply { text = "Stop / close"; setOnClickListener { finish() } }
        val hangup = Button(this).apply { text = "End current phone call"; setOnClickListener {
            scope.launch {
                val action = com.example.voice_assistant.intent.TypedCommand.EndCall.toIntent(1.0)
                val outcome = EndCallExecutor(AndroidEndCallPort(this@SystemAssistantActivity) { resumed })
                    .execute(AssistantAction("END_CALL", action))
                this@SystemAssistantActivity.text.text = outcome.message
                probe.record(outcome.code, "SYSTEM_SESSION_CALL_CONTROL", probe.snapshot())
            }
        } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32)
            addView(text); addView(speak); addView(hangup); addView(unlock); addView(open); addView(dismiss)
        })
        val stt = AndroidSpeechToTextEngine(applicationContext) { event -> if (event.code == DiagnosticCode.LISTENING) text.text = "Listening…" }
        tts = AndroidTextToSpeechEngine(applicationContext) {}
        focus = SpeechAudioFocus(applicationContext)
        gmail = GmailSession(GmailApiService(ProcessGmailToken), ::restriction)
        whatsapp = WhatsAppReplySession({ AssistantNotificationListener.replyTarget(this) }, {
            if (!resumed || (locked() && !lockedReplies())) ActionResult(ActionStatus.requiresConfirmation, "UNLOCK_REQUIRED", "Unlock or enable locked WhatsApp replies in Aiko settings.") else null
        })
        val actions = AndroidActionExecutor(this, gmail, whatsapp, ::personalReads) { resumed }
        executor = SessionActionExecutor(object : ActionExecutor {
            override suspend fun execute(action: AssistantAction): ActionResult {
                if (action.intent.kind == IntentKind.callPhone && handsFreeCalls())
                    return DirectCallExecutor(this@SystemAssistantActivity) { resumed }.execute(action)
                if (action.intent.kind == IntentKind.createReminder) {
                    val p = action.intent.parameters.associate { it.name to it.value }
                    return LocalReminderScheduler(applicationContext).schedule(p["title"] as String, (p["dueEpochMs"] as Number).toLong(), p["timeZoneId"] as String)
                }
                if (action.intent.kind == IntentKind.replyEmail) return ActionResult(ActionStatus.requiresConfirmation, "OPEN_GMAIL_REVIEW", "Open Gmail in Aiko to select an email and review a reply. Nothing was sent.")
                val result = actions.execute(action)
                if (result.code == "WHATSAPP_REPLY_REVIEW") showReplyReview()
                return result
            }
        }, ::locked, ::personalReads, lockedReplies = ::lockedReplies, handsFreeCalls = ::handsFreeCalls,
            voiceHangup = { getSharedPreferences("assistant_privacy", 0).getBoolean("voiceHangup", false) })
        coordinator = SpeechCoordinator(stt, tts, focus, AssistantRuntime.router(applicationContext), executor) {}
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT); addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
        probe.record("NATIVE_SESSION_CREATED", "SYSTEM_SESSION", probe.snapshot())
        if (wakeTicket != null) scope.launch {
            delay(5000)
            if (!started) {
                probe.record("COMMAND_WINDOW_NOT_READY", "SYSTEM_SESSION", probe.snapshot())
                finish()
            }
        }
    }
    private fun listen() {
        if (!resumed || job != null || replyVoiceJob?.isActive == true) return
        started = true
        executor.clear(); whatsapp.clear(); speak.isEnabled = false
        job = scope.launch {
            try {
                if (wakeTicket == null) {
                    stopService(Intent(this@SystemAssistantActivity, WakeWordService::class.java))
                    withTimeout(3000) { while (WakeWordService.enabled || WakeWordService.active) delay(50) }
                }
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    say("Unlock Aiko and grant microphone permission first."); return@launch
                }
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                com.example.voice_assistant.communications.IncomingCallVoicePreparation.prepare(applicationContext)
                if (wakeTicket != null) say("Yes?")
                currentCoroutineContext().ensureActive()
                probe.record("COMMAND_CAPTURE_STARTING", "SYSTEM_SESSION", probe.snapshot())
                val result = coordinator.run(VoiceRequest("req-${System.currentTimeMillis()}-0", options = SavedSpeechOptions.read(applicationContext)), false)
                text.text = if (locked()) "Result: ${result.result.code}. Personal content is spoken only when enabled." else result.response.ifBlank { result.result.code }
                probe.record(result.result.code, "SYSTEM_SESSION", probe.snapshot() + mapOf(
                    "intent" to result.intent?.kind?.wireName,
                    "routingTier" to result.routingTier.name,
                    "executionMode" to result.executionMode.name,
                    "metrics" to result.metrics.map { it.toWire() },
                    "sttEngine" to result.speech["sttEngine"],
                    "sttMode" to result.speech["sttMode"],
                    "actionExecutionMode" to result.routing["actionExecutionMode"],
                    "ttsError" to result.speech["ttsError"],
                    "webTestCase" to result.result.evidence["webTestCase"],
                    "webEvidenceCount" to result.result.evidence["webEvidenceCount"]
                ), result.stt?.latencyMs, tts.startupMs)
                if (result.response.isBlank() && result.result.code != "CANCELLED") say("Speech failed: ${result.result.code.replace('_', ' ')}. Check speech settings in Aiko.")
                else if (result.result.code == "UNLOCK_REQUIRED") say("Unlock with the system prompt to continue this command.")
                if (wakeTicket != null && result.result.status != ActionStatus.requiresConfirmation) finish()
            } catch (_: TimeoutCancellationException) {
                text.text = "Microphone busy. Stop other listening and retry."
                probe.record("MICROPHONE_BUSY", "SYSTEM_SESSION", probe.snapshot())
                say(text.text.toString())
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                text.text = "Assistant failed. No completion verified."
                probe.record("SESSION_ERROR", "SYSTEM_SESSION", probe.snapshot())
                try { say(text.text.toString()) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            }
            finally {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                coordinator.cancel(); speak.isEnabled = true; job = null
                // The voice pipeline must release STT/TTS before keyguard takes focus.
                if (resumed && !isFinishing && executor.hasPendingNavigation()) speak.post {
                    if (resumed && !isFinishing && job == null && executor.hasPendingNavigation()) unlock(false)
                }
            }
        }
    }
    private suspend fun say(message: String) {
        val current = currentCoroutineContext()[Job]
        try {
            focus.acquire { current?.cancel() }
            when(val result = tts.speak("req-${System.currentTimeMillis()}-0", message)) {
                is CapabilityResult.Unavailable -> {
                    text.text = "Voice response unavailable: ${result.code}"
                    probe.record(result.code, "SYSTEM_SESSION_TTS", probe.snapshot())
                }
                else -> Unit
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            text.text = "Voice response unavailable. Check speech settings in Aiko."
            probe.record("TTS_UNAVAILABLE", "SYSTEM_SESSION_TTS", probe.snapshot())
        } finally { tts.stop(); focus.release() }
    }
    private fun unlock(openApp: Boolean) {
        if (job != null || authenticating) return
        openAfterUnlock = openApp
        if (!locked()) { authenticated = true; continueUnlocked(); return }
        if (Build.VERSION.SDK_INT < 26) { text.text = "Unlock with Android, then invoke Aiko again."; return }
        authenticating = true
        probe.record("UNLOCK_PROMPT_REQUESTED", "SYSTEM_SESSION", probe.snapshot())
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { authenticating = false; authenticated = true; continueUnlocked() }
            override fun onDismissCancelled() { authenticating = false; executor.clear(); text.text = "Unlock cancelled. Nothing executed."
                probe.record("UNLOCK_CANCELLED", "SYSTEM_SESSION", probe.snapshot()) }
            override fun onDismissError() { authenticating = false; executor.clear(); text.text = "Use Android's unlock screen and retry."
                probe.record("UNLOCK_FAILED", "SYSTEM_SESSION", probe.snapshot()) }
        })
    }
    private fun continueUnlocked() {
        if (!resumed || locked() || !authenticated || job != null) return
        authenticated = false
        if (openAfterUnlock) { startActivity(Intent(this, MainActivity::class.java)); finish(); return }
        job = scope.launch {
            try {
                val outcome = executor.resumeAfterUnlock()
                text.text = outcome.message
                if (resumed) say(outcome.message)
                probe.record(outcome.code, "SYSTEM_SESSION", probe.snapshot())
                if (wakeTicket != null && outcome.status != ActionStatus.requiresConfirmation) finish()
            } finally { job = null }
        }
    }
    private fun showReplyReview() {
        val d = whatsapp.review(); val token = d["token"] as? String ?: return
        replyVoiceJob?.cancel()
        replyDialog?.dismiss()
        val previous = job
        val dialog = AlertDialog.Builder(this).setTitle("Review WhatsApp reply")
            .setMessage("Conversation: ${d["recipient"]}\n${d["body"]}\n\nAfter the prompt, say Send reply or Cancel reply.")
            .setNegativeButton("Cancel") { _, _ -> replyVoiceJob?.cancel(); whatsapp.clear(); if (wakeTicket != null) finish() }
            .setPositiveButton("Send reply") { _, _ ->
                replyVoiceJob?.cancel()
                val outcome = whatsapp.confirm(token); text.text = outcome.message
                val confirmation = replyVoiceJob
                scope.launch { previous?.join(); confirmation?.join(); if (resumed) say(outcome.message); if (wakeTicket != null) finish() }
            }.setOnCancelListener { replyVoiceJob?.cancel(); whatsapp.clear(); if (wakeTicket != null) finish() }.create()
        replyDialog = dialog
        dialog.show()
        replyVoiceJob = scope.launch {
            previous?.join() // Original draft readout and audio cleanup must complete first.
            if (!resumed || !dialog.isShowing || whatsapp.review()["token"] != token) return@launch
            val confirmationStt = AndroidSpeechToTextEngine(applicationContext) {}
            try {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                say("To send this reviewed WhatsApp reply, say Send reply. To discard it, say Cancel reply. Listen now.")
                if (!resumed || !dialog.isShowing || whatsapp.review()["token"] != token) return@launch
                val recognized = withTimeout(20000) {
                    confirmationStt.recognize(VoiceRequest("req-${System.currentTimeMillis()}-0", options = SavedSpeechOptions.read(applicationContext)))
                }
                val speech = (recognized as? CapabilityResult.Available)?.value
                val decision = ReplyVoiceDecision.parse(speech?.transcript.orEmpty(), speech?.isFinal == true)
                currentCoroutineContext().ensureActive()
                if (!resumed || !dialog.isShowing || whatsapp.review()["token"] != token) return@launch
                val outcome = when (decision) {
                    ReplyVoiceDecision.SEND -> whatsapp.confirm(token)
                    ReplyVoiceDecision.CANCEL -> { whatsapp.clear(); ActionResult(ActionStatus.succeeded, "WHATSAPP_REPLY_CANCELLED", "Reply cancelled. Nothing was sent.") }
                    ReplyVoiceDecision.UNRECOGNIZED -> {
                        dialog.setMessage("Conversation: ${d["recipient"]}\n${d["body"]}\n\nNo explicit Send reply or Cancel reply was recognized. Nothing sent. Use the buttons or cancel and dictate again.")
                        probe.record("REPLY_VOICE_NOT_CONFIRMED", "SYSTEM_SESSION", probe.snapshot())
                        return@launch
                    }
                }
                dialog.dismiss()
                text.text = outcome.message
                probe.record(outcome.code, "SYSTEM_SESSION_REPLY_CONFIRMATION", probe.snapshot())
                say(outcome.message)
                if (wakeTicket != null) finish()
            } catch (_: TimeoutCancellationException) {
                if (dialog.isShowing) dialog.setMessage("Voice confirmation timed out. Nothing sent. Use Send reply or Cancel below.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (dialog.isShowing) dialog.setMessage("Voice confirmation unavailable. Nothing was automatically retried. Use the review buttons.")
            } finally {
                confirmationStt.close()
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
    override fun onResume() {
        super.onResume()
        if (!::coordinator.isInitialized) return
        probe.record("NATIVE_SESSION_RESUMED", "SYSTEM_SESSION", probe.snapshot())
        resumed = true; active = true; continueUnlocked()
        if (!started && intent.action == Intent.ACTION_ASSIST) speak.post {
            if (resumed && hasWindowFocus() && !started) listen()
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && resumed && !started && ::coordinator.isInitialized && intent.action == Intent.ACTION_ASSIST) listen()
    }
    override fun onPause() {
        if (!::coordinator.isInitialized) { super.onPause(); return }
        probe.record("NATIVE_SESSION_PAUSED", "SYSTEM_SESSION", probe.snapshot())
        resumed = false; active = false; replyVoiceJob?.cancel(); replyDialog?.dismiss()
        job?.cancel(); coordinator.cancel(); whatsapp.clear(); text.text = "Assistant idle."
        if (!authenticating && !authenticated) executor.clear()
        // A screen-off launch can pause before the window first receives focus.
        // Never capture while paused, but allow the bounded startup to resume.
        if (wakeTicket != null && started && !authenticating && !authenticated) finish()
        super.onPause()
    }
    override fun onDestroy() {
        if (::coordinator.isInitialized) {
            unregisterReceiver(receiver); executor.clear(); whatsapp.clear(); gmail.clear(); coordinator.close()
        }
        scope.cancel()
        AikoSessionService.completed(intent.getStringExtra(AikoSessionService.PRESENTATION))
        wakeTicket?.let { com.example.voice_assistant.voice.wakeword.SystemWakeHandoff.end(it) }
        super.onDestroy()
    }
}
