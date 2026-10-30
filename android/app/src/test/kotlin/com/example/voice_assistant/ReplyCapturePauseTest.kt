package com.example.voice_assistant

import com.example.voice_assistant.voice.wakeword.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ReplyCapturePauseTest {
    @Test fun pauseWaitsForRecorderCleanup() = runBlocking {
        var paused = false; var closed = false
        val engine = object : WakeWordEngine {
            override suspend fun awaitDetection(onReady: (Double) -> Unit, onAudio: (Map<String, Any?>) -> Unit): WakeWordDetection {
                try { onReady(1.0); awaitCancellation() }
                finally { withContext(NonCancellable) { delay(20); closed = true } }
            }
        }
        val result = withTimeout(2000) { detectionUntilReplyPause(engine, { paused }, { paused = true }, {}) }
        assertNull(result); assertTrue(closed)
    }
    @Test fun normalDetectionIsPreserved() = runBlocking {
        val expected = WakeWordDetection(2.0, 3.0)
        val engine = object : WakeWordEngine {
            override suspend fun awaitDetection(onReady: (Double) -> Unit, onAudio: (Map<String, Any?>) -> Unit) = expected
        }
        assertEquals(expected, detectionUntilReplyPause(engine, { false }, {}, {}))
    }
    @Test fun withdrawnPauseDoesNotStopTheWakeService() = runBlocking {
        var paused = false
        val engine = object : WakeWordEngine {
            override suspend fun awaitDetection(onReady: (Double) -> Unit, onAudio: (Map<String, Any?>) -> Unit): WakeWordDetection {
                try { onReady(0.0); awaitCancellation() } finally { paused = false }
            }
        }
        assertNull(withTimeout(2000) { detectionUntilReplyPause(engine, { paused }, { paused = true }, {}) })
        assertTrue(currentCoroutineContext().isActive)
    }
    @Test fun stoppingServiceStillPropagatesCancellation() = runBlocking {
        var closed = false
        val ready = CompletableDeferred<Unit>()
        val engine = object : WakeWordEngine {
            override suspend fun awaitDetection(onReady: (Double) -> Unit, onAudio: (Map<String, Any?>) -> Unit): WakeWordDetection {
                try { ready.complete(Unit); awaitCancellation() } finally { closed = true }
            }
        }
        val job = launch { detectionUntilReplyPause(engine, { false }, {}, {}) }
        ready.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled); assertTrue(closed)
    }
}
