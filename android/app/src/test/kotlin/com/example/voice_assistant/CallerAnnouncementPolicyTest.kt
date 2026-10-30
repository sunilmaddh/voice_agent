package com.example.voice_assistant

import com.example.voice_assistant.communications.CallerAnnouncementPolicy as Policy
import org.junit.Assert.*
import org.junit.Test

class CallerAnnouncementPolicyTest {
    @Test fun silentAndVibrateUseMediaWhileNormalRingingUsesRingAudio() {
        assertFalse(Policy.useMediaAudio(true, 5))
        assertTrue(Policy.useMediaAudio(false, 5))
        assertTrue(Policy.useMediaAudio(false, 0))
        assertTrue(Policy.useMediaAudio(true, 0))
        // Ringer mode no longer constitutes a policy block; DND still does.
        assertNull(Policy.blocked(true, true, true, false, false, false, false))
        assertEquals("SILENT_OR_DND", Policy.blocked(true, true, true, false, false, false, true))
    }
    @Test fun spokenRingtoneRespectsDisabledCallAlerts() {
        assertNull(Policy.alertBlocked(true, true))
        assertEquals("INCOMING_CHANNEL_BLOCKED", Policy.alertBlocked(false, true))
        assertEquals("INCOMING_CHANNEL_BLOCKED", Policy.alertBlocked(true, false))
        assertEquals("INCOMING_CHANNEL_BLOCKED", Policy.alertBlocked(false, false))
    }
    @Test fun contactNameWinsAndHiddenIdentityNeverLeaks() {
        assertEquals("Incoming call from Rahul.", Policy.phrase(true, "Rahul", true, "Carrier name"))
        assertEquals("Incoming call from a private or unavailable number.", Policy.phrase(false, "Rahul", true, "Carrier name"))
        assertEquals("Incoming call from an unknown caller.", Policy.phrase(true, null, false, "Hidden name"))
        assertEquals("Incoming call from Carrier name.", Policy.phrase(true, null, true, "Carrier name"))
        assertEquals("Incoming call from an unknown caller.", Policy.phrase(true, "   ", true, "\n"))
    }
    @Test fun namesAreBoundedAndControlsRemoved() {
        assertEquals("Incoming call from Rahul Kumar.", Policy.phrase(true, "Rahul\n\u202eKumar", false, null))
        assertTrue(Policy.phrase(true, "a".repeat(1000), false, null).length < 110)
    }
    @Test fun permissionsPrivacyAndCallStateGateAnnouncements() {
        assertNull(Policy.blocked(true, true, true, false, false, false, false))
        assertNull(Policy.blocked(true, true, true, true, true, false, false))
        assertEquals("DISABLED", Policy.blocked(false, true, true, false, false, false, false))
        assertEquals("DEFAULT_PHONE_REQUIRED", Policy.blocked(true, false, true, false, false, false, false))
        assertEquals("CALL_NOT_RINGING_OR_OTHER_CALL_ACTIVE", Policy.blocked(true, true, false, false, false, false, false))
        assertEquals("LOCKED_ANNOUNCEMENT_DISABLED", Policy.blocked(true, true, true, true, false, false, false))
        assertEquals("DRIVING_MODE_SILENT", Policy.blocked(true, true, true, false, false, true, false))
        assertEquals("SILENT_OR_DND", Policy.blocked(true, true, true, false, false, false, true))
    }
}
