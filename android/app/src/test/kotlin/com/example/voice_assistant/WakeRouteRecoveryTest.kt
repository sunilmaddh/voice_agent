package com.example.voice_assistant

import com.example.voice_assistant.voice.wakeword.WakeRouteRecovery
import org.junit.Assert.*
import org.junit.Test

class WakeRouteRecoveryTest {
    @Test fun repeatedConnectionFailuresStopAfterThreeRetries() {
        val recovery = WakeRouteRecovery()
        repeat(3) { assertTrue(recovery.retry("BLUETOOTH_MIC_NOT_ROUTED")) }
        assertFalse(recovery.retry("BLUETOOTH_ROUTE_TIMEOUT"))
    }
    @Test fun briefConnectionsDoNotCreateInfiniteReconnectLoop() {
        val recovery = WakeRouteRecovery()
        repeat(3) {
            assertTrue(recovery.retry("BLUETOOTH_ROUTE_LOST"))
            recovery.captured(1.0)
        }
        assertFalse(recovery.retry("BLUETOOTH_ROUTE_LOST"))
        recovery.captured(10.0)
        assertTrue(recovery.retry("BLUETOOTH_ROUTE_CHANGED"))
    }
    @Test fun permissionAndCallConflictsAreNotRetried() {
        val recovery = WakeRouteRecovery()
        listOf("AUDIO_COMMUNICATION_BUSY", "MICROPHONE_PERMISSION_REQUIRED", "WAKE_RUNTIME_UNAVAILABLE")
            .forEach { assertFalse(recovery.retry(it)) }
        assertEquals(0, recovery.attempts)
    }
}
