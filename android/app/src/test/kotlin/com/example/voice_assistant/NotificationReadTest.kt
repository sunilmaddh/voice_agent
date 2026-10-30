package com.example.voice_assistant

import com.example.voice_assistant.notifications.*
import com.example.voice_assistant.intent.LocalIntentRouter
import com.example.voice_assistant.native_bridge.contracts.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NotificationReadTest {
    private fun action(text: String) = LocalIntentRouter().decide(text).proposedAction!!
    private fun service(result: CapabilityResult<List<NotificationRecord>>) = object : NotificationService {
        override suspend fun latest() = result
    }
    @Test fun filtersWhatsAppAndReadsLatestPreview() = runBlocking {
        val reader = NotificationReadExecutor(service(CapabilityResult.Available(listOf(
            NotificationRecord("a", "other.app", "Other content"),
            NotificationRecord("b", "com.whatsapp.w4b", "Rahul. Hello"),
            NotificationRecord("c", "com.whatsapp", "Older"))))) { null }
        val result = reader.execute(action("read my latest whatsapp message"))
        assertEquals(ActionStatus.succeeded, result.status)
        assertTrue(result.message.contains("Rahul. Hello"))
        assertFalse(result.message.contains("Other content"))
        assertFalse(result.message.contains("Older"))
    }
    @Test fun deniedAccessIsNotEmptySuccess() = runBlocking {
        val reader = NotificationReadExecutor(service(CapabilityResult.Unavailable("NOTIFICATION_ACCESS_REQUIRED"))) { null }
        assertEquals(ActionStatus.unsupported, reader.execute(action("read notifications")).status)
    }
    @Test fun lockedDeviceNeverReadsService() = runBlocking {
        val reader = NotificationReadExecutor(object : NotificationService {
            override suspend fun latest(): CapabilityResult<List<NotificationRecord>> = error("must not read")
        }) { ActionResult(ActionStatus.requiresConfirmation, "UNLOCK_REQUIRED", "Unlock") }
        assertEquals("UNLOCK_REQUIRED", reader.execute(action("read notifications")).code)
    }
    @Test fun emptyAndHiddenContentAreHonest() = runBlocking {
        val empty = NotificationReadExecutor(service(CapabilityResult.Available(emptyList()))) { null }
        assertTrue(empty.execute(action("read notifications")).message.contains("No active"))
        val hidden = NotificationReadExecutor(service(CapabilityResult.Available(listOf(NotificationRecord("a", "com.whatsapp", null))))) { null }
        assertTrue(hidden.execute(action("read my whatsapp")).message.contains("hidden or unavailable"))
    }
    @Test fun pipelineSpeaksNotificationResult() = runBlocking {
        val reader = NotificationReadExecutor(service(CapabilityResult.Available(listOf(NotificationRecord("a", "com.whatsapp", "Rahul. Hello"))))) { null }
        val tts = SpeechCoordinatorTest.Tts()
        val result = com.example.voice_assistant.voice.SpeechCoordinator(
            SpeechCoordinatorTest.Stt().apply { transcript = "read my whatsapp" },
            tts, SpeechCoordinatorTest.Focus(), executor = reader) {}.run(VoiceRequest("req-123-1"), false)
        assertEquals("NOTIFICATIONS_READ", result.result.code)
        assertTrue(result.response.contains("Rahul. Hello"))
        assertEquals(result.response, tts.spoken)
    }
    @Test fun hiddenReadReportsRestrictionWithoutEstablishingReplyContext() = runBlocking {
        val reader = NotificationReadExecutor(service(CapabilityResult.Available(listOf(
            NotificationRecord("a", "com.whatsapp", null, restrictionCode = "NOTIFICATION_CHANNEL_PRIVATE"))))) { null }
        var context: NotificationRecord? = null
        reader.onWhatsAppRead = { context = it }
        val result = reader.execute(action("read my whatsapp"))
        assertEquals(ActionStatus.unsupported, result.status)
        assertEquals("NOTIFICATION_CHANNEL_PRIVATE", result.code)
        assertTrue(result.message.contains("category hides content"))
        assertNull(context)
    }
}
