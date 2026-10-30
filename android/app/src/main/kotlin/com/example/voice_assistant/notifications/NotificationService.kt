package com.example.voice_assistant.notifications
import com.example.voice_assistant.native_bridge.contracts.*
data class NotificationRecord(val id: String, val sourcePackage: String, val text: String?, val postTime: Long = 0,
    val restrictionCode: String? = null)
interface NotificationService {
    suspend fun latest(): CapabilityResult<List<NotificationRecord>>
}
