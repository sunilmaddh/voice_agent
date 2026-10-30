package com.example.voice_assistant.voice
import com.example.voice_assistant.benchmark.PerformanceMonitor
import com.example.voice_assistant.native_bridge.contracts.*

/** Real bridge handshake only. No microphone, inference, actions, or TTS are simulated. */
class FoundationCoordinator(
    private val performance: PerformanceMonitor,
    private val emit: (DiagnosticEvent) -> Unit
) {
    fun start(request: VoiceRequest): AssistantResponse {
        val started = performance.start()
        emit(DiagnosticEvent(request.requestId, DiagnosticCode.REQUEST_STARTED, DiagnosticLevel.INFO))
        emit(DiagnosticEvent(request.requestId, DiagnosticCode.CAPABILITY_NOT_IMPLEMENTED, DiagnosticLevel.WARNING))
        val message = "Android bridge connected. Speech recognition is not implemented in this foundation."
        val response = AssistantResponse(
            requestId = request.requestId, input = request.input,
            result = ActionResult(ActionStatus.unsupported, "STT_NOT_IMPLEMENTED", message),
            response = message,
            metrics = listOf("stt", "slm", "action", "tts").map(performance::notRun) +
                performance.finish("total", started)
        )
        emit(DiagnosticEvent(request.requestId, DiagnosticCode.REQUEST_COMPLETED, DiagnosticLevel.INFO))
        return response
    }
}
