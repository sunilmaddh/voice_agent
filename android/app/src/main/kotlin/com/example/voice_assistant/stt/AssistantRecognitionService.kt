package com.example.voice_assistant.stt

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.example.voice_assistant.native_bridge.contracts.*
import kotlinx.coroutines.*

/** Functional adapter required by VoiceInteractionService metadata, reusing the existing STT engine. */
class AssistantRecognitionService : RecognitionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var engine: AndroidSpeechToTextEngine? = null
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        if (job != null) { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY); return }
        val language = recognizerIntent.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE)
        if (language != null && !language.startsWith("en", true)) {
            listener.error(if (android.os.Build.VERSION.SDK_INT >= 31) SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED else SpeechRecognizer.ERROR_CLIENT)
            return
        }
        val stt = AndroidSpeechToTextEngine(applicationContext) { event ->
            if (event.code == DiagnosticCode.LISTENING) listener.readyForSpeech(Bundle())
            if (event.code == DiagnosticCode.STT_PARTIAL && event.transcript != null) {
                listener.partialResults(Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(event.transcript))
                })
            }
        }
        engine = stt
        job = scope.launch {
            try {
                when (val result = stt.recognize(VoiceRequest("req-${System.currentTimeMillis()}-0", options = SavedSpeechOptions.read(applicationContext)))) {
                    is CapabilityResult.Available -> listener.results(Bundle().apply {
                        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(result.value.transcript))
                    })
                    is CapabilityResult.Unavailable -> listener.error(SpeechRecognizer.ERROR_CLIENT)
                }
            } catch (_: CancellationException) { }
            catch (e: SpeechFailure) { listener.error(when(e.code) {
                "STT_NO_MATCH" -> SpeechRecognizer.ERROR_NO_MATCH
                "MICROPHONE_PERMISSION_DENIED" -> SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                "STT_NO_SPEECH" -> SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                else -> SpeechRecognizer.ERROR_CLIENT
            }) }
            catch (_: Exception) { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            finally { stt.close(); engine = null; job = null }
        }
    }
    override fun onStopListening(listener: Callback) { engine?.finishListening() }
    override fun onCancel(listener: Callback) { job?.cancel(); engine?.cancel() }
    override fun onDestroy() { scope.cancel(); engine?.close(); super.onDestroy() }
}
