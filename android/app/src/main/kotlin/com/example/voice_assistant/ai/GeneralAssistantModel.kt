package com.example.voice_assistant.ai
import com.example.voice_assistant.native_bridge.contracts.*
interface GeneralAssistantModel {
    val modelId: String
    suspend fun answer(question: String, context: String?): CapabilityResult<String>
    suspend fun close()
}
