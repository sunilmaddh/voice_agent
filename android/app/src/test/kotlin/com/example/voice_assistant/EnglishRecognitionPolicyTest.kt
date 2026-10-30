package com.example.voice_assistant

import com.example.voice_assistant.stt.EnglishRecognitionPolicy as Policy
import org.junit.Assert.*
import org.junit.Test

class EnglishRecognitionPolicyTest {
    @Test fun acceptsInstalledEnglishVariantsAndNormalizesProviderTags() {
        assertEquals("en-IN", Policy.selectInstalled(listOf("ja-JP", "en_IN")))
        assertEquals("en-GB", Policy.selectInstalled(listOf("en-GB")))
        assertEquals("en-US", Policy.selectInstalled(listOf("en-GB", "en_us")))
        assertEquals("en", Policy.selectInstalled(listOf("en")))
    }
    @Test fun neverSubstitutesNonEnglishOrMissingModels() {
        assertNull(Policy.selectInstalled(emptyList()))
        assertNull(Policy.selectInstalled(listOf("hi-IN", "ja-JP", "", "english")))
    }
    @Test fun fallbackRequiresOptInAndOnlyRetriesCapabilityFailures() {
        assertTrue(Policy.canRetrySystem("STT_LANGUAGE_NOT_INSTALLED", true, true))
        assertFalse(Policy.canRetrySystem("STT_LANGUAGE_NOT_INSTALLED", false, true))
        assertFalse(Policy.canRetrySystem("STT_LANGUAGE_NOT_INSTALLED", true, false))
        for (code in listOf("CANCELLED", "STT_NO_MATCH", "MICROPHONE_PERMISSION_DENIED", "STT_BUSY"))
            assertFalse(Policy.canRetrySystem(code, true, true))
    }
}
