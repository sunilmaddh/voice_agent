package com.example.voice_assistant
import com.example.voice_assistant.communications.VoiceFriendlyRinging
import org.junit.Assert.*
import org.junit.Test
class VoiceFriendlyRingingTest {
    @Test fun vibrationNeedsExplicitPreferenceAndWakeListening() {
        assertFalse(VoiceFriendlyRinging.vibrationOnly(false, false))
        assertFalse(VoiceFriendlyRinging.vibrationOnly(false, true))
        assertFalse(VoiceFriendlyRinging.vibrationOnly(true, false))
        assertTrue(VoiceFriendlyRinging.vibrationOnly(true, true))
    }
}
