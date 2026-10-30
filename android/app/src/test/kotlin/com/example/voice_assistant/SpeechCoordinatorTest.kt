package com.example.voice_assistant

import com.example.voice_assistant.native_bridge.contracts.*
import com.example.voice_assistant.stt.SpeechToTextEngine
import com.example.voice_assistant.tts.TextToSpeechEngine
import com.example.voice_assistant.voice.SpeechCoordinator
import com.example.voice_assistant.voice.VoiceAudioFocus
import com.example.voice_assistant.actions.ActionExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Test doubles only; these do not validate microphone, engines, or device timing. */
class SpeechCoordinatorTest {
    class Stt : SpeechToTextEngine {
        override val engine = "test"
        override val mode = "LOCAL"
        override val offlineAvailability = "available"
        var failure: String? = null
        var final = true
        var calls = 0
        var transcript = "open maps"
        var onRecognize: () -> Unit = {}
        override suspend fun capabilities() = emptyMap<String, Any?>()
        override suspend fun recognize(request: VoiceRequest): CapabilityResult<SpeechRecognitionResult> {
            calls++
            onRecognize()
            failure?.let { throw SpeechFailure(it) }
            return CapabilityResult.Available(SpeechRecognitionResult(transcript, final, engine, mode, 25.0, 300.0))
        }
        override fun cancel() {}
        override fun close() {}
    }
    class Tts : TextToSpeechEngine {
        override var options = SpeechOptions()
        override val startupMs = 12.0
        override val initializationMs = 5.0
        override val selectedVoice = "test voice"
        override val mode = "OFFLINE_CAPABLE"
        override val engine = "test"
        var spoken: String? = null
        var failure = false
        var onSpeak: () -> Unit = {}
        override suspend fun capabilities() = emptyMap<String, Any?>()
        override suspend fun speak(requestId: String, text: String): CapabilityResult<Unit> {
            spoken = text
            onSpeak()
            if (failure) throw SpeechFailure("TTS_PLAYBACK_FAILED")
            return CapabilityResult.Available(Unit)
        }
        override fun stop() {}
        override fun close() {}
    }
    class Focus : VoiceAudioFocus {
        var released = false
        var held = false
        var acquisitions = 0
        override fun acquire(onLoss: () -> Unit) { held = true; acquisitions++ }
        override fun release() { released = true; held = false }
    }
    @Test fun playbackFocusIsNotHeldDuringRecognizerCapture() = runBlocking {
        val focus = Focus()
        val stt = Stt().apply { onRecognize = { assertFalse(focus.held); assertEquals(0, focus.acquisitions) } }
        val tts = Tts().apply { onSpeak = { assertTrue(focus.held) } }
        SpeechCoordinator(stt, tts, focus) {}.run(VoiceRequest("req-1-1"), false)
        assertEquals(1, focus.acquisitions)
        assertFalse(focus.held)
    }
    @Test fun finalTranscriptIsRoutedWithoutExecutingActionOrModel() = runBlocking {
        val stt = Stt(); val tts = Tts(); val focus = Focus()
        val response = SpeechCoordinator(stt, tts, focus) {}.run(VoiceRequest("req-1-1"), false)
        assertTrue(tts.spoken!!.contains("OPEN_MAPS"))
        assertTrue(tts.spoken!!.contains("nothing was executed"))
        assertEquals(ActionStatus.unsupported, response.result.status)
        assertNull(response.action); assertEquals(IntentKind.openMaps, response.intent?.kind); assertNull(response.selectedModel)
        assertEquals(false, response.routing["actionExecuted"])
        assertEquals(ExecutionMode.local, response.executionMode)
        assertEquals(25.0, response.metrics.first { it.stage == "stt" }.durationMs!!, .001)
        assertTrue(focus.released)
    }
    @Test fun recognitionFailureNeverSpeaksOrFabricatesTranscript() = runBlocking {
        val stt = Stt().apply { failure = "STT_NO_MATCH" }; val tts = Tts(); val focus = Focus()
        val response = SpeechCoordinator(stt, tts, focus) {}.run(VoiceRequest("req-1-1"), false)
        assertNull(tts.spoken); assertNull(response.stt)
        assertEquals("STT_NO_MATCH", response.result.code)
        assertEquals(0, focus.acquisitions)
        assertEquals("unavailable", response.metrics.first { it.stage == "stt" }.status)
        assertEquals("not_run", response.metrics.first { it.stage == "tts" }.status)
        assertTrue(focus.released)
    }
    @Test fun partialResultsCannotEnterPlayback() = runBlocking {
        val tts = Tts()
        val response = SpeechCoordinator(Stt().apply { final = false }, tts, Focus()) {}.run(VoiceRequest("req-1-1"), false)
        assertNull(tts.spoken)
        assertEquals("STT_FINAL_RESULT_REQUIRED", response.result.code)
    }
    @Test fun playbackFailurePreservesFinalTranscript() = runBlocking {
        val response = SpeechCoordinator(Stt(), Tts().apply { failure = true }, Focus()) {}.run(VoiceRequest("req-1-1"), false)
        assertEquals("open maps", response.stt?.transcript)
        assertEquals("TTS_PLAYBACK_FAILED", response.result.code)
    }
    @Test fun standalonePreviewDoesNotCaptureAudio() = runBlocking {
        val stt = Stt(); val tts = Tts()
        val response = SpeechCoordinator(stt, tts, Focus()) {}.run(VoiceRequest("req-1-1", "Hello"), true)
        assertEquals(0, stt.calls); assertEquals("Hello", tts.spoken)
        assertEquals("not_run", response.metrics.first().status)
    }
    @Test fun navigationLaunchesAfterSpeechAndFocusRelease() = runBlocking {
        val tts = Tts(); val focus = Focus(); var calls = 0
        val executor = object : ActionExecutor {
            override suspend fun execute(action: AssistantAction): ActionResult {
                calls++
                assertNotNull(tts.spoken); assertTrue(focus.released)
                assertEquals("NAVIGATE", action.name)
                assertEquals("bangalore airport", action.intent.parameters.single().value)
                return ActionResult(ActionStatus.succeeded, "MAPS_LAUNCH_REQUESTED", "Maps launch requested.")
            }
        }
        val result = SpeechCoordinator(Stt().apply { transcript = "take me to bangalore airport" },
            tts, focus, executor = executor) {}.run(VoiceRequest("req-1-1"), false)
        assertEquals(1, calls); assertEquals("MAPS_LAUNCH_REQUESTED", result.result.code)
        assertEquals(true, result.routing["actionExecuted"])
        assertNotNull(result.action)
        assertEquals("measured", result.metrics.first { it.stage == "action" }.status)
    }
    @Test fun missingMapsIsNotReportedAsSuccessfulExecution() = runBlocking {
        val executor = object : ActionExecutor {
            override suspend fun execute(action: AssistantAction) =
                ActionResult(ActionStatus.unsupported, "MAPS_UNAVAILABLE", "Google Maps unavailable.")
        }
        val result = SpeechCoordinator(Stt(), Tts(), Focus(), executor = executor) {}.run(VoiceRequest("req-1-1"), false)
        assertEquals("MAPS_UNAVAILABLE", result.result.code)
        assertEquals(false, result.routing["actionExecuted"])
        assertEquals(true, result.routing["actionAttempted"])
    }
    @Test fun unavailableTtsDoesNotBlockMapsAndKeepsTtsFailureDiagnostic() = runBlocking {
        val executor = object : ActionExecutor {
            override suspend fun execute(action: AssistantAction) =
                ActionResult(ActionStatus.succeeded, "MAPS_LAUNCH_REQUESTED", "Maps launch requested.")
        }
        val result = SpeechCoordinator(Stt(), Tts().apply { failure = true }, Focus(), executor = executor) {}.run(VoiceRequest("req-1-1"), false)
        assertEquals("MAPS_LAUNCH_REQUESTED", result.result.code)
        assertEquals("TTS_PLAYBACK_FAILED", result.speech["ttsError"])
    }
    @Test fun otherIntentsAndAmbiguousNavigationNeverReachExecutor() = runBlocking {
        val executor = object : ActionExecutor {
            override suspend fun execute(action: AssistantAction): ActionResult = error("Must not execute")
        }
        for (input in listOf("stop navigation", "take me to there", "do not open maps")) {
            val result = SpeechCoordinator(Stt().apply { transcript = input }, Tts(), Focus(), executor = executor) {}
                .run(VoiceRequest("req-1-1"), false)
            assertNull(result.action)
            assertEquals(false, result.routing["actionExecuted"])
        }
    }
}
