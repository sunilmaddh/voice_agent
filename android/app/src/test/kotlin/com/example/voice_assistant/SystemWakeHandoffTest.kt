package com.example.voice_assistant

import com.example.voice_assistant.voice.wakeword.SystemWakeHandoff
import org.junit.Assert.*
import org.junit.Test

class SystemWakeHandoffTest {
    @Test fun onlyExactLiveTicketCanClaimMicrophoneHandoffOnce() {
        val ticket = SystemWakeHandoff.begin()
        try {
            assertNull(SystemWakeHandoff.claim("untrusted"))
            assertFalse(ticket.claimed.isCompleted)
            assertSame(ticket, SystemWakeHandoff.claim(ticket.id))
            assertNull(SystemWakeHandoff.claim(ticket.id))
            assertFalse(ticket.finished.isCompleted)
        } finally { SystemWakeHandoff.end(ticket) }
        assertTrue(ticket.finished.isCompleted)
        assertNull(SystemWakeHandoff.claim(ticket.id))
    }
    @Test fun stoppingBeforeActivityArrivesRejectsStaleHandoffAndOldCleanupCannotClearNewOne() {
        val old = SystemWakeHandoff.begin()
        SystemWakeHandoff.end(old)
        val next = SystemWakeHandoff.begin()
        try {
            SystemWakeHandoff.end(old)
            assertNull(SystemWakeHandoff.claim(old.id))
            assertSame(next, SystemWakeHandoff.claim(next.id))
        } finally { SystemWakeHandoff.end(next) }
    }
}
