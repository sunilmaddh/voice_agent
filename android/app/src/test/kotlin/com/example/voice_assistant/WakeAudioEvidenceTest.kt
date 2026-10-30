package com.example.voice_assistant

import com.example.voice_assistant.voice.wakeword.WakeAudioEvidence
import org.junit.Assert.*
import org.junit.Test

class WakeAudioEvidenceTest {
    @Test fun unlockDoesNotEraseLockedAudioEvidence() {
        val meter = WakeAudioEvidence()
        meter.record(true, -55.0, true, 1)
        meter.record(true, -25.0, false, 2)
        val result = meter.record(false, -46.0, false, 3)
        val locked = result["latestLockedCapture"] as Map<*, *>
        assertEquals(2, locked["meterWindows"])
        assertEquals(-25.0, locked["maxWindowRmsDbfs"])
        assertEquals(1, locked["silencedWindows"])
        assertEquals(1, locked["unsilencedWindows"])
        val next = meter.record(true, null, null, 4)["latestLockedCapture"] as Map<*, *>
        assertEquals(1, next["meterWindows"])
        assertNull(next["maxWindowRmsDbfs"])
        assertEquals(0, next["unsilencedWindows"])
    }
}
