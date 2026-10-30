package com.example.voice_assistant
import com.example.voice_assistant.stt.SavedSpeechOptions
import org.junit.Assert.*
import org.junit.Test
class SavedSpeechOptionsTest {
    @Test fun respectsAllConsentCombinations() {
        for (allowed in listOf(false, true)) for (preferred in listOf(false, true)) {
            val options = SavedSpeechOptions.fromConsent(allowed, preferred)
            assertEquals(allowed, options.allowSystemRecognition)
            assertEquals(allowed && preferred, options.preferSystemRecognition)
            assertTrue(options.offlineTtsOnly)
        }
    }
}
