package com.example.voice_assistant

import com.example.voice_assistant.communications.IncomingRingState
import org.junit.Assert.*
import org.junit.Test

class IncomingRingStateTest {
    @Test fun announcementFailureResumesRingingButNeverUnsilencesCall() {
        val state = IncomingRingState()
        val ringing = setOf("one")
        assertEquals("one", state.alertId(ringing, false, false))
        state.pause("one")
        assertNull(state.alertId(ringing, false, false))
        state.resume("one") // Success or failure both return to ringing.
        assertEquals("one", state.alertId(ringing, false, false))
        state.pause("one"); state.silence(ringing); state.resume("one")
        assertNull(state.alertId(ringing, false, false))
        assertTrue(state.isSilenced("one"))
    }
    @Test fun answerEndAndNewCallsDoNotInheritPausedOrSilencedState() {
        val state = IncomingRingState()
        state.pause("one"); state.silence(setOf("one")); state.update(emptySet())
        state.resume("one")
        assertNull(state.alertId(emptySet(), false, false))
        assertEquals("two", state.alertId(setOf("two"), false, false))
        assertFalse(state.isSilenced("one"))
    }
    @Test fun drivingAndExistingCallsSuppressAudibleRinging() {
        val state = IncomingRingState()
        assertNull(state.alertId(setOf("one"), false, true))
        assertNull(state.alertId(setOf("one"), true, false))
        state.pause("one"); state.update(setOf("two")); state.resume("one")
        assertEquals("two", state.alertId(setOf("two"), false, false))
    }
}
