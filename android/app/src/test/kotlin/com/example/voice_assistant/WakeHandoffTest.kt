package com.example.voice_assistant

import com.example.voice_assistant.voice.wakeword.WakeHandoff
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WakeHandoffTest {
    @Test fun doesNotResumeDetectionUntilPipelineFinishes() = runBlocking {
        val handoff = WakeHandoff()
        assertTrue(handoff.claim())
        assertFalse(handoff.claim())
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { handoff.awaitCompletion() }
        yield()
        assertFalse(waiting.isCompleted)
        handoff.finish()
        waiting.join()
        assertTrue(waiting.isCompleted)
    }
    @Test fun missingFlutterConsumerExpiresAndCannotClaimLate() = runBlocking {
        val handoff = WakeHandoff()
        withTimeout(1000) { handoff.awaitCompletion(10) }
        assertFalse(handoff.claim())
    }
    @Test fun disablingCancelsWaitWithoutWaitingForCommand() = runBlocking {
        val handoff = WakeHandoff()
        handoff.claim()
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { handoff.awaitCompletion() }
        waiting.cancelAndJoin()
        assertTrue(waiting.isCancelled)
    }
}
