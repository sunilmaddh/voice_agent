package com.example.voice_assistant

import com.example.voice_assistant.benchmark.MonotonicPerformanceMonitor
import com.example.voice_assistant.native_bridge.contracts.*
import com.example.voice_assistant.voice.FoundationCoordinator
import org.junit.Assert.*
import org.junit.Test

class FoundationTest {
    @Test fun foundationNeverClaimsSpeechOrActionExecution() {
        var nanos = 0L
        val events = mutableListOf<DiagnosticEvent>()
        val coordinator = FoundationCoordinator(MonotonicPerformanceMonitor { nanos += 1000000; nanos }) { events.add(it) }
        val result = coordinator.start(VoiceRequest("req-1-1"))
        assertEquals(ActionStatus.unsupported, result.result.status)
        assertEquals("STT_NOT_IMPLEMENTED", result.result.code)
        assertNull(result.stt)
        assertNull(result.intent)
        assertNull(result.action)
        assertNull(result.selectedModel)
        assertEquals(ExecutionMode.notRun, result.executionMode)
        assertTrue(result.metrics.take(4).all { it.durationMs == null && it.status == "not_run" })
        assertEquals(1.0, result.metrics.last().durationMs!!, 0.001)
        assertEquals(3, events.size)
        assertEquals(1, result.toWire()["schemaVersion"])
    }
    @Test fun rejectsInvalidWireRequests() {
        for (payload in listOf(null, mapOf("schemaVersion" to 2),
            mapOf("schemaVersion" to 1, "requestId" to "Bearer secret"),
            mapOf("schemaVersion" to 1, "requestId" to "req-1-1", "input" to 12),
            mapOf("schemaVersion" to 1, "requestId" to "req-1-1", "access_token" to "secret"))) {
            try { VoiceRequest.fromWire(payload); fail("Invalid payload accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun diagnosticMetadataCannotContainArbitraryIdentifiers() {
        val wire = DiagnosticEvent("Bearer private-token", DiagnosticCode.INTERNAL_ERROR, DiagnosticLevel.ERROR).toWire()
        assertEquals("redacted", wire["requestId"])
        assertFalse(wire.toString().contains("private-token"))
        assertEquals(setOf("schemaVersion", "requestId", "code", "level", "timestampMs"), wire.keys)
    }
    @Test(expected = IllegalArgumentException::class)
    fun parametersRejectWrongTypes() {
        IntentParameter("destination", ParameterType.text, 42)
    }
    @Test(expected = IllegalArgumentException::class)
    fun skippedMetricsCannotClaimMeasurements() {
        PerformanceMetric("stt", 0.0, "not_run")
    }
    @Test fun speechSettingsHaveOfflineDefaultsAndRejectUnsafeValues() {
        val defaults = SpeechOptions.fromWire(null)
        assertFalse(defaults.allowSystemRecognition)
        assertTrue(defaults.offlineTtsOnly)
        for (options in listOf(mapOf("rate" to Double.NaN), mapOf("pitch" to 8),
            mapOf("allowSystemRecognition" to "true"), mapOf("language" to "hi"))) {
            try { SpeechOptions.fromWire(options); fail("Invalid settings accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun transcriptsAreExcludedFromPersistentDiagnosticPayloads() {
        val event = DiagnosticEvent("req-1-1", DiagnosticCode.STT_PARTIAL, DiagnosticLevel.INFO, transcript = "private speech")
        assertFalse(event.toWire().toString().contains("private speech"))
        assertEquals("private speech", event.toSessionWire()["transcript"])
    }
}
