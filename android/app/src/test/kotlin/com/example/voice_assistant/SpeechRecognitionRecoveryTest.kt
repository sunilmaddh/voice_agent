package com.example.voice_assistant

import com.example.voice_assistant.stt.SpeechRecognitionRecovery
import com.example.voice_assistant.native_bridge.contracts.SpeechFailure
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SpeechRecognitionRecoveryTest {
    @Test fun reconnectsOnceWithoutChangingOfflineConsent() = runBlocking {
        val attempts = mutableListOf<Boolean>()
        val result = SpeechRecognitionRecovery.run(true, false, true, pause = {}) { local ->
            attempts += local
            if (attempts.size == 1) throw SpeechFailure("STT_SERVER_DISCONNECTED")
            "recognized"
        }
        assertEquals("recognized", result)
        assertEquals(listOf(true, true), attempts)
    }
    @Test fun optedInLocalFailureSwitchesToSystemAndStopsAfterSecondFailure() = runBlocking {
        val attempts = mutableListOf<Boolean>()
        try {
            SpeechRecognitionRecovery.run(true, true, true, pause = {}) { local ->
                attempts += local
                throw SpeechFailure("STT_SERVER_DISCONNECTED")
            }
            fail("Expected final provider failure")
        } catch (failure: SpeechFailure) { assertEquals("STT_SERVER_DISCONNECTED", failure.code) }
        assertEquals(listOf(true, false), attempts)
    }
    @Test fun systemRecognizerAlsoReconnectsOnlyOnce() = runBlocking {
        var attempts = 0
        SpeechRecognitionRecovery.run(false, true, true, pause = {}) { local ->
            assertFalse(local)
            if (++attempts == 1) throw SpeechFailure("STT_SERVER_DISCONNECTED")
        }
        assertEquals(2, attempts)
    }
    @Test fun cancellationPermissionsAndNoMatchDoNotRetry() = runBlocking {
        for (error in listOf(CancellationException(), SpeechFailure("MICROPHONE_PERMISSION_DENIED"), SpeechFailure("STT_NO_MATCH"))) {
            var attempts = 0
            try {
                SpeechRecognitionRecovery.run(true, true, true, pause = {}) { attempts++; throw error }
                fail("Expected error")
            } catch (actual: Exception) { assertSame(error, actual) }
            assertEquals(1, attempts)
        }
    }
}
