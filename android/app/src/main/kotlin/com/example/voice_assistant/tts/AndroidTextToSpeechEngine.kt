package com.example.voice_assistant.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.example.voice_assistant.native_bridge.contracts.*

class AndroidTextToSpeechEngine(private val context: Context,
    private val emit: (DiagnosticEvent) -> Unit) : TextToSpeechEngine {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var initialization: CompletableDeferred<Boolean>? = null
    private var pending: CancellableContinuation<CapabilityResult<Unit>>? = null
    private var utterance: String? = null
    private var started = 0L
    override var options = SpeechOptions()
    override var startupMs: Double? = null; private set
    override var initializationMs: Double? = null; private set
    override var selectedVoice: String? = null; private set
    override var mode = "not_run"; private set
    override val engine: String? get() = tts?.defaultEngine

    private suspend fun ready(): TextToSpeech {
        if (initialization == null) {
            val init = CompletableDeferred<Boolean>()
            initialization = init
            val start = System.nanoTime()
            tts = TextToSpeech(context) { status ->
                main.post {
                    initializationMs = (System.nanoTime() - start) / 1_000_000.0
                    init.complete(status == TextToSpeech.SUCCESS)
                }
            }
        }
        if (!withTimeout(8000) { initialization!!.await() }) {
            tts?.shutdown(); tts = null; initialization = null
            throw SpeechFailure("TTS_INITIALIZATION_FAILED")
        }
        return tts ?: throw SpeechFailure("TTS_UNAVAILABLE")
    }
    override suspend fun capabilities(): Map<String, Any?> {
        val service = ready()
        val voices = service.voices.orEmpty().filter { it.locale.language in setOf("en", "hi", "kn") }.sortedBy { it.name }
        return mapOf("ttsEngine" to engine, "ttsInitializationMs" to initializationMs,
            "voices" to voices.map { mapOf("name" to it.name, "locale" to it.locale.toLanguageTag(),
                "requiresNetwork" to it.isNetworkConnectionRequired, "gender" to "unspecified",
                "installed" to !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) })
    }
    override suspend fun speak(requestId: String, text: String): CapabilityResult<Unit> =
        com.example.voice_assistant.voice.BluetoothVoiceRoute.use(context) { bluetooth -> speakOnRoute(requestId, text, bluetooth) }

    private suspend fun speakOnRoute(requestId: String, text: String, bluetooth: Boolean): CapabilityResult<Unit> {
        startupMs = null; selectedVoice = null; mode = "not_run"
        val service = ready()
        if (text.isBlank() || text.length >= TextToSpeech.getMaxSpeechInputLength()) throw SpeechFailure("TTS_TEXT_TOO_LONG_OR_EMPTY")
        val voices = service.voices.orEmpty().filter {
            it.locale.language == java.util.Locale.forLanguageTag(options.languageTag).language && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }
        val voice = if (options.voiceName != null) voices.firstOrNull { it.name == options.voiceName }
            else voices.filter { !options.offlineTtsOnly || !it.isNetworkConnectionRequired }
                .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name })).firstOrNull()
        if (voice == null) throw SpeechFailure(when (options.languageTag) {
            "hi-IN" -> "TTS_HINDI_VOICE_UNAVAILABLE"
            "kn-IN" -> "TTS_KANNADA_VOICE_UNAVAILABLE"
            else -> "TTS_ENGLISH_VOICE_UNAVAILABLE"
        })
        if (options.offlineTtsOnly && voice.isNetworkConnectionRequired) throw SpeechFailure("TTS_VOICE_REQUIRES_NETWORK")
        if (service.setVoice(voice) != TextToSpeech.SUCCESS ||
            service.setSpeechRate(options.rate) != TextToSpeech.SUCCESS ||
            service.setPitch(options.pitch) != TextToSpeech.SUCCESS) throw SpeechFailure("TTS_CONFIGURATION_FAILED")
        selectedVoice = voice.name
        service.setAudioAttributes(android.media.AudioAttributes.Builder()
            .setUsage(if (bluetooth) android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION else if (android.os.Build.VERSION.SDK_INT >= 26) android.media.AudioAttributes.USAGE_ASSISTANT else android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
        mode = if (voice.isNetworkConnectionRequired) "NETWORK_REQUIRED" else "OFFLINE_CAPABLE"
        // Email/calendar responses can exceed a short command acknowledgement. This is a
        // bounded watchdog, not a reported latency estimate; onDone still completes promptly.
        val playbackBudgetMs = (10000L + (text.length * 100.0 / options.rate).toLong()).coerceIn(30000L, 480000L)
        return withTimeout(playbackBudgetMs) {
            suspendCancellableCoroutine { c ->
                pending = c; utterance = requestId
                started = System.nanoTime()
                service.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { val callbackTime = System.nanoTime(); main.post {
                        if (utterance == id && c.isActive) {
                            startupMs = (callbackTime - started) / 1_000_000.0
                            emit(DiagnosticEvent(requestId, DiagnosticCode.TTS_STARTED, DiagnosticLevel.INFO))
                        }
                    } }
                    override fun onDone(id: String?) { main.post {
                        if (utterance == id && c.isActive) {
                            utterance = null; pending = null; c.resume(CapabilityResult.Available(Unit))
                        }
                    } }
                    @Deprecated("Android legacy callback")
                    override fun onError(id: String?) = fail(id)
                    override fun onError(id: String?, errorCode: Int) = fail(id)
                    override fun onStop(id: String?, interrupted: Boolean) = fail(id)
                    private fun fail(id: String?) { main.post {
                        if (utterance == id && c.isActive) {
                            utterance = null; pending = null; c.resumeWithException(SpeechFailure("TTS_PLAYBACK_FAILED"))
                        }
                    } }
                })
                c.invokeOnCancellation { utterance = null; pending = null; service.stop() }
                if (service.speak(text, TextToSpeech.QUEUE_FLUSH, null, requestId) == TextToSpeech.ERROR && c.isActive) {
                    utterance = null; pending = null
                    c.resumeWithException(SpeechFailure("TTS_SPEAK_FAILED"))
                }
            }
        }
    }
    override fun stop() { pending?.cancel(); pending = null; utterance = null; tts?.stop() }
    override fun close() { stop(); initialization?.complete(false); tts?.shutdown(); tts = null; initialization = null }
}

