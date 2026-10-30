package com.example.voice_assistant

import com.example.voice_assistant.voice.wakeword.WakeWordPolicy
import org.junit.Assert.*
import org.junit.Test

class WakeWordPolicyTest {
    @Test fun onlyConfiguredKeywordCanWake() {
        assertTrue(WakeWordPolicy.accepts("HEY_INDO"))
        assertTrue(WakeWordPolicy.accepts("HEY_AIKO"))
        listOf("", "HEY_SAKURA", "HEY_GOOGLE", "HEY_SIRI", "HEY_INDO OPEN_MAPS", "HEY_AIKO SEND_REPLY").forEach { assertFalse(WakeWordPolicy.accepts(it)) }
    }
    @Test fun noForegroundActionHandoffWhileLockedBackgroundBusyOrUnobserved() {
        assertTrue(WakeWordPolicy.mayHandoff(true, true, false, true))
        assertFalse(WakeWordPolicy.mayHandoff(false, true, false, true))
        assertFalse(WakeWordPolicy.mayHandoff(true, false, false, true))
        assertFalse(WakeWordPolicy.mayHandoff(true, true, true, true))
        assertFalse(WakeWordPolicy.mayHandoff(true, true, false, false))
    }
}
