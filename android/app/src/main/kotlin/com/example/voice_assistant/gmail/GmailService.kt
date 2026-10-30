package com.example.voice_assistant.gmail
import com.example.voice_assistant.native_bridge.contracts.*
data class EmailMessage(val id: String, val threadId: String, val sender: String, val subject: String, val body: String,
    val replyTo: String = "", val messageId: String = "", val truncated: Boolean = false)
interface GmailService {
    suspend fun list(scope: String): List<EmailMessage>
    suspend fun read(id: String): EmailMessage
    suspend fun reply(message: EmailMessage, text: String): String
}
class GmailFailure(val code: String) : Exception(code)
