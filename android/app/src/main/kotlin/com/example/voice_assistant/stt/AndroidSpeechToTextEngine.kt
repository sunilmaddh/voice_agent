package com.example.voice_assistant.stt

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.example.voice_assistant.native_bridge.contracts.*

/** All entry points and callbacks run on Android's main thread. */
class AndroidSpeechToTextEngine(
    private val context: Context, private val emit: (DiagnosticEvent) -> Unit
) : SpeechToTextEngine {
    private var recognizer: SpeechRecognizer? = null
    private var continuation: CancellableContinuation<CapabilityResult<SpeechRecognitionResult>>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    override var engine = "not_run"; private set
    override var mode = "not_run"; private set
    override var offlineAvailability = "unknown"; private set
    private var requestedLocale = "en-US"
    private var selectedLocale = "en-US"
    private var cachedCapabilities: Map<String, Any?>? = null
    private var capabilitiesCheckedAt = 0L
    override var diagnostics: Map<String, Any?> = emptyMap(); private set

    private fun recognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, selectedLocale)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // Voice commands should finish after a natural pause, rather than a provider's long dictation window.
        // These are hints: the provider may ignore them; only final results are used for actions.
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)

    override suspend fun capabilities(): Map<String, Any?> {
        val language = SavedSpeechOptions.read(context).languageTag
        if (language != requestedLocale) { requestedLocale = language; cachedCapabilities = null }
        return capabilitiesForLocale()
    }
    private suspend fun capabilitiesForLocale(): Map<String, Any?> {
        cachedCapabilities?.let { cached ->
            if (android.os.SystemClock.elapsedRealtime() - capabilitiesCheckedAt < 60_000L) {
                selectedLocale = cached["locale"] as String
                offlineAvailability = cached["englishOfflineAvailability"] as String
                return cached
            }
        }
        selectedLocale = requestedLocale
        var installedEnglish = emptyList<String>()
        val system = ExternalSpeechProvider.component(context) != null
        val local = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        var english = if (local) "unknown" else "unavailable"
        if (local && Build.VERSION.SDK_INT >= 33) {
            var probe: SpeechRecognizer? = null
            try {
                probe = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                val p = probe
                english = withTimeoutOrNull(3000) {
                    suspendCancellableCoroutine<String> { c ->
                        p.checkRecognitionSupport(recognitionIntent(), context.mainExecutor, object : RecognitionSupportCallback {
                            override fun onSupportResult(support: RecognitionSupport) {
                                if (!c.isActive) return
                                installedEnglish = support.installedOnDeviceLanguages.filter {
                                    EnglishRecognitionPolicy.selectInstalled(listOf(it), requestedLocale) != null
                                }
                                val selected = EnglishRecognitionPolicy.selectInstalled(installedEnglish, requestedLocale)
                                if (selected != null) selectedLocale = selected
                                c.resume(if (selected != null) "available" else "unavailable")
                            }
                            override fun onError(error: Int) { if (c.isActive) c.resume("unknown") }
                        })
                    }
                } ?: "unknown"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { english = "unknown" }
            finally { probe?.destroy() }
        }
        offlineAvailability = english
        val result = mapOf("systemRecognitionAvailable" to system, "onDeviceRecognizerAvailable" to local,
            "offlineAvailability" to english, "englishOfflineAvailability" to english, "locale" to selectedLocale,
            "installedEnglishLocales" to installedEnglish,
            "offlineUnavailableReason" to when {
                !local -> "ON_DEVICE_RECOGNIZER_UNAVAILABLE"
                english == "unavailable" -> when (requestedLocale) {
                    "hi-IN" -> "HINDI_MODEL_NOT_INSTALLED"
                    "kn-IN" -> "KANNADA_MODEL_NOT_INSTALLED"
                    else -> "ENGLISH_MODEL_NOT_INSTALLED"
                }
                english == "unknown" -> "SUPPORT_CHECK_INCONCLUSIVE"
                else -> null
            })
        // Cache only confirmed support. Missing/downloading models are checked again next time.
        cachedCapabilities = result.takeIf { english == "available" }
        capabilitiesCheckedAt = android.os.SystemClock.elapsedRealtime()
        return result
    }

    override suspend fun recognize(request: VoiceRequest): CapabilityResult<SpeechRecognitionResult> {
        var route: Map<String, Any?> = emptyMap()
        try {
            com.example.voice_assistant.communications.IncomingCallVoicePreparation.prepare(context)
            return com.example.voice_assistant.voice.BluetoothVoiceRoute.use(context, { route = it }) { recognizeOnRoute(request) }
        } finally { diagnostics = diagnostics + route }
    }

    /** The call service already pauses its ringtone and owns Telecom's audio route.
     * Do not permanently silence the call or attempt a competing Bluetooth route.
     */
    suspend fun recognizeIncomingCall(request: VoiceRequest): CapabilityResult<SpeechRecognitionResult> =
        recognizeOnRoute(request)

    private suspend fun recognizeOnRoute(request: VoiceRequest): CapabilityResult<SpeechRecognitionResult> {
        if (requestedLocale != request.options.languageTag) cachedCapabilities = null
        requestedLocale = request.options.languageTag
        selectedLocale = requestedLocale
        engine = "not_run"; mode = "not_run"
        diagnostics = emptyMap()
        offlineAvailability = "unknown"
        if (request.options.preferSystemRecognition) {
            if (!request.options.allowSystemRecognition) throw SpeechFailure("SYSTEM_STT_CONSENT_REQUIRED")
            if (ExternalSpeechProvider.component(context) == null) throw SpeechFailure("STT_UNAVAILABLE")
            selectedLocale = requestedLocale
            return SpeechRecognitionRecovery.run(false, true, true) { listen(request, false) }
        }
        val capabilities = capabilitiesForLocale()
        val local = capabilities["onDeviceRecognizerAvailable"] == true && offlineAvailability != "unavailable"
        if (!local && !request.options.allowSystemRecognition) throw SpeechFailure(when (requestedLocale) {
            "hi-IN" -> "OFFLINE_HINDI_UNAVAILABLE"
            "kn-IN" -> "OFFLINE_KANNADA_UNAVAILABLE"
            else -> "OFFLINE_ENGLISH_UNAVAILABLE"
        })
        if (!local && capabilities["systemRecognitionAvailable"] != true) throw SpeechFailure("STT_UNAVAILABLE")
        return SpeechRecognitionRecovery.run(local, request.options.allowSystemRecognition,
            capabilities["systemRecognitionAvailable"] == true) { useLocal ->
            if (!useLocal) selectedLocale = requestedLocale
            listen(request, useLocal)
        }
    }

    private suspend fun listen(request: VoiceRequest, local: Boolean): CapabilityResult<SpeechRecognitionResult> {
        engine = if (local) "Android on-device SpeechRecognizer" else "Android system SpeechRecognizer"
        mode = if (local) "LOCAL" else "UNKNOWN_SYSTEM"
        return withTimeout(30000) {
            suspendCancellableCoroutine { c ->
                continuation = c
                val started = System.nanoTime()
                diagnostics = mapOf("locale" to selectedLocale, "ready" to false, "speechDetected" to false)
                var ended: Long? = null
                val sr = try {
                    if (local && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    else SpeechRecognizer.createSpeechRecognizer(context, ExternalSpeechProvider.component(context) ?: throw SpeechFailure("STT_UNAVAILABLE"))
                } catch (_: Exception) {
                    if (continuation === c) continuation = null
                    c.resumeWithException(SpeechFailure("STT_UNAVAILABLE"))
                    return@suspendCancellableCoroutine
                }
                recognizer = sr
                var released = false
                fun release() {
                    if (released) return
                    released = true
                    diagnostics = diagnostics + ("attemptDurationMs" to (System.nanoTime() - started) / 1e6)
                    if (recognizer === sr) { recognizer = null; continuation = null }
                    // destroy() already shuts down recognition. Calling cancel() first
                    // queues a message that may run after destroy clears the connection.
                    sr.destroy()
                }
                c.invokeOnCancellation {
                    // Cancellation handlers may run on the thread cancelling the job.
                    if (Looper.myLooper() == Looper.getMainLooper()) release()
                    else mainHandler.post { release() }
                }
                if (!c.isActive) return@suspendCancellableCoroutine
                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        if (c.isActive) diagnostics = diagnostics + mapOf("ready" to true, "readyAfterMs" to (System.nanoTime() - started) / 1e6)
                        if (c.isActive) emit(DiagnosticEvent(request.requestId, DiagnosticCode.LISTENING, DiagnosticLevel.INFO))
                    }
                    override fun onBeginningOfSpeech() {
                        if (c.isActive) diagnostics = diagnostics + ("speechDetected" to true)
                    }
                    override fun onRmsChanged(rmsdB: Float) {
                        if (c.isActive && rmsdB.isFinite()) diagnostics = diagnostics +
                            ("peakRecognizerRmsDb" to maxOf(rmsdB, (diagnostics["peakRecognizerRmsDb"] as? Float) ?: rmsdB))
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { if (c.isActive) ended = System.nanoTime() }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onPartialResults(partialResults: Bundle?) {
                        if (!c.isActive) return
                        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                        emit(DiagnosticEvent(request.requestId, DiagnosticCode.STT_PARTIAL, DiagnosticLevel.INFO, transcript = text))
                    }
                    override fun onResults(results: Bundle?) {
                        if (!c.isActive) return
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                        val now = System.nanoTime()
                        release()
                        if (text.isNullOrEmpty()) { c.resumeWithException(SpeechFailure("STT_NO_MATCH")); return }
                        emit(DiagnosticEvent(request.requestId, DiagnosticCode.STT_FINAL, DiagnosticLevel.INFO, transcript = text))
                        c.resume(CapabilityResult.Available(SpeechRecognitionResult(text, true, engine, mode,
                            ended?.let { (now - it) / 1_000_000.0 }, (now - started) / 1_000_000.0)))
                    }
                    override fun onError(error: Int) {
                        if (!c.isActive) return
                        // Provider or language changes must be rechecked on the next request.
                        if (error in setOf(4, 11, 12, 13)) cachedCapabilities = null
                        diagnostics = diagnostics + ("androidErrorCode" to error)
                        release()
                        c.resumeWithException(SpeechFailure(errorCode(error)))
                    }
                })
                try { sr.startListening(recognitionIntent()) }
                catch (_: SecurityException) { release(); if (c.isActive) c.resumeWithException(SpeechFailure("MICROPHONE_PERMISSION_DENIED")) }
                catch (_: Exception) { release(); if (c.isActive) c.resumeWithException(SpeechFailure("STT_START_FAILED")) }
            }
        }
    }
    fun finishListening() { recognizer?.stopListening() }
    override fun cancel() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { cancel() }
            return
        }
        // The continuation owns recognizer teardown; don't destroy it a second time.
        continuation?.cancel()
    }
    override fun close() = cancel()
    companion object {
        fun errorCode(error: Int) = when(error) {
            1, 2 -> "STT_NETWORK_ERROR"
            3 -> "STT_AUDIO_ERROR"
            4 -> "STT_SERVER_ERROR"
            5 -> "STT_CLIENT_ERROR"
            6 -> "STT_NO_SPEECH"
            7 -> "STT_NO_MATCH"
            8 -> "STT_BUSY"
            9 -> "MICROPHONE_PERMISSION_DENIED"
            10 -> "STT_TOO_MANY_REQUESTS"
            11 -> "STT_SERVER_DISCONNECTED"
            12 -> "STT_LANGUAGE_UNSUPPORTED"
            13 -> "STT_LANGUAGE_NOT_INSTALLED"
            else -> "STT_ERROR"
        }
    }
}

