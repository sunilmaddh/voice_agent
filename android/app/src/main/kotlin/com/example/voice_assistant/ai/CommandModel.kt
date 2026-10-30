package com.example.voice_assistant.ai
import com.example.voice_assistant.native_bridge.contracts.*
interface CommandModel {
    val modelId: String
    val diagnostics: Map<String, Any?> get() = emptyMap()
    suspend fun extract(text: String, context: CommandContext = CommandContext()): CapabilityResult<AssistantIntent>
    suspend fun close()
}
