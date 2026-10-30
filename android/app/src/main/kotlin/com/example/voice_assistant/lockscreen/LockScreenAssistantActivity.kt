package com.example.voice_assistant.lockscreen

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.voice_assistant.MainActivity
import com.example.voice_assistant.native_bridge.contracts.*
import com.example.voice_assistant.stt.AndroidSpeechToTextEngine
import com.example.voice_assistant.tts.AndroidTextToSpeechEngine
import com.example.voice_assistant.voice.SpeechAudioFocus
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

/** Minimal ACTION_ASSIST surface. Never shows Flutter history or reads private data. */
@android.annotation.SuppressLint("SetTextI18n") // This technical POC is explicitly English only.
class LockScreenAssistantActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var text: TextView
    private lateinit var speak: Button
    private lateinit var stt: AndroidSpeechToTextEngine
    private lateinit var tts: AndroidTextToSpeechEngine
    private lateinit var focus: SpeechAudioFocus
    private var job: Job? = null
    private var initial: Map<String, Any?> = emptyMap()
    private var resumed = false
    private val probe get() = LockScreenProbe(this)
    private val source get() = if (intent.action == Intent.ACTION_ASSIST) "ACTION_ASSIST" else "IN_APP_OR_EXPLICIT_LAUNCH"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        initial = probe.snapshot()
        stt = AndroidSpeechToTextEngine(applicationContext) { }
        tts = AndroidTextToSpeechEngine(applicationContext) { }
        focus = SpeechAudioFocus(applicationContext)
        text = TextView(this).apply {
            text = "Aiko assistant POC\nTap to ask the time or create a local reminder. Other actions require opening Aiko after unlocking. Uses saved English speech settings; system recognition may use the network when enabled."
            textSize = 20f
        }
        speak = Button(this).apply { text = "Tap to speak"; setOnClickListener { listen() } }
        val open = Button(this).apply { text = "Unlock / open Aiko"; setOnClickListener { openApp() } }
        val cancel = Button(this).apply { text = "Cancel"; setOnClickListener { finish() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 32)
            addView(text); addView(speak); addView(open); addView(cancel)
        })
        probe.record("SURFACE_CREATED", source, initial)
    }
    private fun listen() {
        if (!resumed || job != null) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            text.text = "Open Aiko after unlocking and grant microphone permission using Start Voice Interaction, then retry."
            probe.record("MICROPHONE_PERMISSION_REQUIRED", source, initial)
            return
        }
        speak.isEnabled = false
        job = scope.launch {
            var latency: Double? = null
            try {
                val current = currentCoroutineContext()[Job]
                text.text = "Listening in English…"
                val id = "req-${System.currentTimeMillis()}-0"
                val recognition = stt.recognize(VoiceRequest(id, options = com.example.voice_assistant.stt.SavedSpeechOptions.read(applicationContext)))
                if (recognition is CapabilityResult.Unavailable) throw SpeechFailure(recognition.code)
                val result = (recognition as CapabilityResult.Available).value
                latency = result.latencyMs
                val outcome = LockedCommandResponder(com.example.voice_assistant.notifications.LocalReminderScheduler(applicationContext))
                    .respond(result.transcript, result.isFinal)
                val response = outcome.message
                text.text = response
                focus.acquire { current?.cancel() }
                when (val spoken = tts.speak(id, response)) {
                    is CapabilityResult.Unavailable -> throw SpeechFailure(spoken.code)
                    is CapabilityResult.Available -> probe.record(outcome.code,
                        source, initial, latency, tts.startupMs)
                }
            } catch (_: CancellationException) {
                probe.record("CANCELLED", source, initial, latency)
            } catch (e: SpeechFailure) {
                text.text = "Speech unavailable: ${e.code}. Open Aiko to inspect speech settings."
                probe.record(e.code, source, initial, latency)
            } catch (_: Exception) {
                text.text = "Speech failed. No action was executed."
                probe.record("SPEECH_FAILED", source, initial, latency)
            } finally { stt.cancel(); tts.stop(); focus.release(); speak.isEnabled = true; job = null }
        }
    }
    private fun openApp() {
        job?.cancel()
        val keyguard = getSystemService(KeyguardManager::class.java)
        fun launch() {
            if (!resumed || keyguard.isDeviceLocked || keyguard.isKeyguardLocked) return
            startActivity(Intent(this, MainActivity::class.java)); finish()
        }
        if (!keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) launch()
        else if (Build.VERSION.SDK_INT >= 26) keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { launch() }
            override fun onDismissCancelled() { text.text = "Unlock cancelled. No action was executed." }
            override fun onDismissError() { text.text = "Unlock using the system lock screen, then open Aiko." }
        }) else text.text = "Unlock using the system lock screen, then open Aiko."
    }
    override fun onResume() { super.onResume(); resumed = true }
    override fun onPause() { resumed = false; job?.cancel(); stt.cancel(); tts.stop(); focus.release(); super.onPause() }
    override fun onDestroy() { scope.cancel(); stt.close(); tts.close(); focus.release(); super.onDestroy() }
}
