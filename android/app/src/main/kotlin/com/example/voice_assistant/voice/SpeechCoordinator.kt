package com.example.voice_assistant.voice

import android.content.Context
import kotlinx.coroutines.*
import com.example.voice_assistant.native_bridge.contracts.*
import com.example.voice_assistant.stt.AndroidSpeechToTextEngine
import com.example.voice_assistant.tts.AndroidTextToSpeechEngine
import com.example.voice_assistant.stt.SpeechToTextEngine
import com.example.voice_assistant.tts.TextToSpeechEngine
import com.example.voice_assistant.intent.*
import com.example.voice_assistant.actions.ActionExecutor

class SpeechCoordinator(private val stt: SpeechToTextEngine, private val tts: TextToSpeechEngine,
    private val audioFocus: VoiceAudioFocus, private val router: IntentRouter = LocalIntentRouter(),
    private val executor: ActionExecutor? = null,
    private val emit: (DiagnosticEvent) -> Unit) {
    constructor(context: Context, router: IntentRouter = LocalIntentRouter(), executor: ActionExecutor? = null, emit: (DiagnosticEvent) -> Unit) : this(
        AndroidSpeechToTextEngine(context, emit), AndroidTextToSpeechEngine(context, emit), SpeechAudioFocus(context), router, executor, emit)
    suspend fun capabilities(): Map<String, Any?> {
        val speech = stt.capabilities()
        val synthesis = try { tts.capabilities() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mapOf("ttsError" to "TTS_UNAVAILABLE", "voices" to emptyList<Any>()) }
        return speech + synthesis + mapOf("device" to android.os.Build.MODEL, "androidSdk" to android.os.Build.VERSION.SDK_INT)
    }
    suspend fun run(request: VoiceRequest, ttsOnly: Boolean): AssistantResponse {
        val start = System.nanoTime()
        var recognition: SpeechRecognitionResult? = null
        var decision: RoutedIntent? = null
        var routingMs: Double? = null
        var actionMs: Double? = null
        var attemptedAction: AssistantAction? = null
        var actionResult: ActionResult? = null
        var ttsError: String? = null
        var response = ""
        var ttsAttempted = false
        var ttsPlaybackMs: Double? = null
        suspend fun speakResponse(text: String) {
            val spokenText = when (request.options.languageTag) {
                "hi-IN" -> HindiSpeech.response(text)
                "kn-IN" -> KannadaSpeech.response(text)
                else -> text
            }
            // English source text must use an English voice rather than Hindi pronunciation.
            val nativeScript = when (request.options.languageTag) {
                "hi-IN" -> Regex("[\\u0900-\\u097F]")
                "kn-IN" -> Regex("[\\u0C80-\\u0CFF]")
                else -> null
            }
            tts.options = if (nativeScript != null && !nativeScript.containsMatchIn(spokenText))
                request.options.copy(languageTag = "en-US", voiceName = null) else request.options
            ttsAttempted = true
            val started = System.nanoTime()
            try {
                val playbackJob = currentCoroutineContext()[Job]
                audioFocus.acquire { playbackJob?.cancel() }
                val spoken = tts.speak(request.requestId, spokenText)
                if (spoken is CapabilityResult.Unavailable) throw SpeechFailure(spoken.code)
            } finally {
                tts.stop(); audioFocus.release()
                ttsPlaybackMs = (ttsPlaybackMs ?: 0.0) + (System.nanoTime() - started) / 1_000_000.0
            }
        }
        var code = "VOICE_COMPLETED"
        var status = ActionStatus.succeeded
        emit(DiagnosticEvent(request.requestId, DiagnosticCode.REQUEST_STARTED, DiagnosticLevel.INFO))
        try {
            // The Android recognizer manages capture focus. Taking playback focus
            // here makes its own focus request cancel our recognition coroutine.
            if (ttsOnly) response = request.input ?: throw SpeechFailure("TTS_TEXT_REQUIRED")
            else {
                when(val result = stt.recognize(request)) {
                    is CapabilityResult.Available -> recognition = result.value
                    is CapabilityResult.Unavailable -> throw SpeechFailure(result.code)
                }
                if (!recognition.isFinal) { recognition = null; throw SpeechFailure("STT_FINAL_RESULT_REQUIRED") }
                if (com.example.voice_assistant.lockscreen.LockScreenPolicy.isTime(HindiCommands.normalize(KannadaCommands.normalize(recognition.transcript)))) {
                    response = if (request.options.languageTag == "hi-IN") "अभी समय है ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())}।"
                    else if (request.options.languageTag == "kn-IN") "ಈಗ ಸಮಯ ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, java.util.Locale.forLanguageTag("kn-IN")).format(java.util.Date())}."
                    else "The time is ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())}."
                    code = "TIME_RESPONSE_COMPLETED"; status = ActionStatus.succeeded
                } else {
                val routingStarted = System.nanoTime()
                decision = when(val routed = router.route(recognition.transcript)) {
                    is CapabilityResult.Available -> routed.value
                    is CapabilityResult.Unavailable -> throw SpeechFailure("ROUTING_UNAVAILABLE")
                }
                routingMs = (System.nanoTime() - routingStarted) / 1_000_000.0
                response = decision.response
                code = decision.status.name
                status = if (decision.status == RoutingStatus.CLARIFICATION_REQUIRED) ActionStatus.requiresConfirmation else ActionStatus.unsupported
                }
            }
            tts.options = request.options
            val localAction = decision?.takeIf { it.status == RoutingStatus.PROPOSAL_ONLY }
                ?.proposedAction?.takeIf { executor != null && it.intent.kind in setOf(IntentKind.replyToCall, IntentKind.drivingModeOn, IntentKind.drivingModeOff, IntentKind.googleSearch, IntentKind.readSms, IntentKind.endCall, IntentKind.replyWhatsApp, IntentKind.readNotification, IntentKind.readWhatsApp, IntentKind.queryCalendar, IntentKind.createReminder, IntentKind.readEmail, IntentKind.replyEmail) }
            if (localAction != null) {
                currentCoroutineContext().ensureActive()
                attemptedAction = localAction
                val started = System.nanoTime()
                try { actionResult = executor!!.execute(localAction) }
                finally { actionMs = (System.nanoTime() - started) / 1_000_000.0 }
                response = actionResult.message; code = actionResult.code; status = actionResult.status
            }
            val externalAction = decision?.takeIf { it.status == RoutingStatus.PROPOSAL_ONLY }
                ?.proposedAction?.takeIf { executor != null && it.intent.kind in setOf(IntentKind.replySms, IntentKind.callPhone, IntentKind.navigate, IntentKind.openMaps, IntentKind.openCalendar, IntentKind.createCalendarEvent, IntentKind.sendMessage) }
            if (externalAction != null) response = when (externalAction.intent.kind) {
                IntentKind.replyToCall -> "I'll send I will call you later to the incoming caller, then decline after Android confirms the SMS was sent."
                IntentKind.replySms -> "I will try opening a reply draft to the SMS I just read. Review it and tap Send in your messaging app."
                IntentKind.callPhone -> "I will try your call request using your calling settings."
                IntentKind.sendMessage -> "I'll open an SMS draft. Select or verify ${externalAction.intent.parameters.first { it.name == "recipientName" }.value}, review the text, and tap Send in your messaging app."
                IntentKind.googleSearch -> "Opening Google Search."
                IntentKind.openCalendar -> "Opening Calendar."
                IntentKind.createCalendarEvent -> "I'll open a calendar draft. Check the details, choose a writable Google calendar, and save it."
                else -> "Opening Maps."
            }
            try {
                speakResponse(response)
            } catch (failure: SpeechFailure) {
                if (externalAction == null && localAction == null) throw failure
                ttsError = failure.code // A missing TTS voice must not prevent a valid external launch.
            }
            if (externalAction != null) {
                currentCoroutineContext().ensureActive()
                // Finish speech first: The launched app will pause this Activity and cancel active voice work.
                tts.stop(); audioFocus.release()
                attemptedAction = externalAction
                val actionStarted = System.nanoTime()
                try { actionResult = executor!!.execute(externalAction) }
                finally { actionMs = (System.nanoTime() - actionStarted) / 1_000_000.0 }
                response = actionResult.message
                code = actionResult.code; status = actionResult.status
                if ((status in setOf(ActionStatus.failed, ActionStatus.unsupported) ||
                        externalAction.intent.kind == IntentKind.replySms && actionResult.code != "SMS_COMPOSER_OPENED" ||
                        externalAction.intent.kind == IntentKind.replyToCall && actionResult.code != "CALL_REPLY_SMS_PENDING") &&
                    actionResult.code != "APP_NOT_FOREGROUND") {
                    try {
                        speakResponse(response)
                    } catch (failure: SpeechFailure) { ttsError = failure.code }
                }
            }
        } catch (_: TimeoutCancellationException) {
            code = "VOICE_TIMEOUT"; status = ActionStatus.failed
        } catch (_: CancellationException) {
            code = "CANCELLED"; status = ActionStatus.failed
        } catch (failure: SpeechFailure) {
            code = failure.code; status = ActionStatus.failed
        } catch (_: SecurityException) {
            code = "MICROPHONE_PERMISSION_DENIED"; status = ActionStatus.failed
        } catch (_: Exception) {
            code = "VOICE_ERROR"; status = ActionStatus.failed
        } finally { stt.cancel(); tts.stop(); audioFocus.release() }
        fun metric(stage: String, ms: Double?, attempted: Boolean = false) = PerformanceMetric(stage, ms,
            if (ms != null) "measured" else if (attempted) "unavailable" else "not_run")
        emit(DiagnosticEvent(request.requestId, DiagnosticCode.REQUEST_COMPLETED, DiagnosticLevel.INFO))
        return AssistantResponse(requestId = request.requestId, input = request.input ?: recognition?.transcript, stt = recognition,
            intent = decision?.intent, routingTier = decision?.tier ?: RoutingTier.notRun,
            selectedModel = decision?.modelId, structuredOutput = decision?.structured(),
            routing = (decision?.diagnostics() ?: emptyMap()) + mapOf(
                "actionAttempted" to (attemptedAction != null),
                "actionExecuted" to (actionResult?.status == ActionStatus.succeeded),
                "actionExecutionMode" to when {
                    attemptedAction?.intent?.kind == IntentKind.googleSearch -> "NETWORK_SEARCH_LOCAL_SLM"
                    attemptedAction == null -> "NOT_RUN"
                    attemptedAction.intent.kind == IntentKind.readEmail && attemptedAction.intent.parameters.any {
                        it.name == "scope" && it.value in setOf("LATEST", "RECENT", "IMPORTANT", "RELEVANT")
                    } -> "CLOUD_GMAIL_API"
                    else -> "LOCAL"
                },
                "summaryMethod" to if (actionResult?.code == "GMAIL_EXTRACTIVE_SUMMARY") "LOCAL_EXTRACTIVE" else null),
            action = attemptedAction,
            executionMode = if (decision == null) ExecutionMode.notRun else ExecutionMode.local,
            result = actionResult ?: ActionResult(status, code, if (status == ActionStatus.succeeded) "Speech playback completed. No assistant action executed." else code),
            response = response, metrics = listOf(metric("stt", recognition?.latencyMs, !ttsOnly),
                metric("sttCapture", recognition?.captureMs, !ttsOnly), metric("routing", routingMs),
                metric("slm", decision?.modelDiagnostics?.get("inferenceMs") as? Double), metric("action", actionMs, attemptedAction != null),
                metric("tts", if (ttsAttempted) tts.startupMs else null, ttsAttempted),
                metric("ttsPlayback", ttsPlaybackMs, ttsAttempted),
                metric("total", (System.nanoTime() - start) / 1_000_000.0)),
            speech = mapOf("sttEngine" to if (ttsOnly) "not_run" else stt.engine,
                "sttDiagnostics" to if (ttsOnly) emptyMap<String, Any?>() else stt.diagnostics,
                "sttMode" to if (ttsOnly) "not_run" else stt.mode,
                "englishOfflineAvailability" to stt.offlineAvailability,
                "ttsEngine" to tts.engine, "selectedVoice" to if (response.isEmpty()) null else tts.selectedVoice,
                "ttsMode" to if (response.isEmpty()) "not_run" else tts.mode,
                "ttsInitializationMs" to tts.initializationMs, "ttsError" to ttsError))
    }
    fun cancel() { stt.cancel(); tts.stop() }
    fun close() { stt.close(); tts.close() }
}



