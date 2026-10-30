package com.example.voice_assistant.document_ai
import com.example.voice_assistant.native_bridge.contracts.*
interface DocumentAiService {
    suspend fun extractText(documentUri: String): CapabilityResult<String>
    suspend fun answer(documentText: String, question: String): CapabilityResult<String>
}
