package com.example.voice_assistant

import com.example.voice_assistant.native_bridge.contracts.SpeechOptions
import org.junit.Assert.*
import org.junit.Test

class SpeechOptionsConsentTest {
    @Test fun localRemainsDefault() {
        assertFalse(SpeechOptions.fromWire(null).preferSystemRecognition)
        assertFalse(SpeechOptions.fromWire(null).allowSystemRecognition)
    }
    @Test(expected = IllegalArgumentException::class)
    fun cannotSelectSystemWithoutConsent() {
        SpeechOptions.fromWire(mapOf("preferSystemRecognition" to true))
    }
    @Test fun explicitSystemChoiceSurvivesBridge() {
        assertTrue(SpeechOptions.fromWire(mapOf("preferSystemRecognition" to true,
            "allowSystemRecognition" to true)).preferSystemRecognition)
    }
}
