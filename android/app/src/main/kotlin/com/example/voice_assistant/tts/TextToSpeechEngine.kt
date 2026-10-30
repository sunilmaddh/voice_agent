package com.example.voice_assistant.tts
import com.example.voice_assistant.native_bridge.contracts.*
interface TextToSpeechEngine {
    var options: SpeechOptions
    val startupMs: Double?
    val initializationMs: Double?
    val selectedVoice: String?
    val mode: String
    val engine: String?
    suspend fun capabilities(): Map<String, Any?>
    suspend fun speak(requestId: String, text: String): CapabilityResult<Unit>
    fun stop()
    fun close()
}
