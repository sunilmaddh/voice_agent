package com.example.voice_assistant.communications
import com.example.voice_assistant.native_bridge.contracts.ActionResult
/** Boundary for later SMS/WhatsApp adapters. No transport implementation in foundation. */
interface CommunicationService {
    suspend fun reply(conversationId: String, text: String): ActionResult
}
