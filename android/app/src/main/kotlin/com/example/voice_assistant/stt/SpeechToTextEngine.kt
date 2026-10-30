package com.example.voice_assistant.stt
import com.example.voice_assistant.native_bridge.contracts.*
interface SpeechToTextEngine {
    val diagnostics: Map<String, Any?> get() = emptyMap()
    val engine: String
    val mode: String
    val offlineAvailability: String
    suspend fun capabilities(): Map<String, Any?>
    suspend fun recognize(request: VoiceRequest): CapabilityResult<SpeechRecognitionResult>
    fun cancel()
    fun close()
}
