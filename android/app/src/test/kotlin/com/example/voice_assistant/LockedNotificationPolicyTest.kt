package com.example.voice_assistant

import com.example.voice_assistant.notifications.LockedNotificationPolicy
import org.junit.Assert.*
import org.junit.Test

class LockedNotificationPolicyTest {
    private fun check(locked: Boolean = true, sameUser: Boolean = true,
        show: Boolean = true, privateContent: Boolean = true, visibility: Int = 0,
        channel: Int? = -1000, devicePolicy: Boolean = true) =
        LockedNotificationPolicy.restriction(locked, sameUser, show, privateContent,
            visibility, channel, devicePolicy)

    @Test fun privatePreviewAllowedWhenUserAllowsSensitiveContent() { assertNull(check()) }
    @Test fun privatePreviewHiddenWhenSensitiveContentDisabled() {
        assertEquals("LOCK_SCREEN_PRIVATE_CONTENT_DISABLED", check(privateContent = false))
    }
    @Test fun channelRestrictionWinsOverGlobalPreference() {
        assertEquals("NOTIFICATION_CHANNEL_PRIVATE", check(channel = 0))
    }
    @Test fun secretIsNeverReadLocked() {
        assertEquals("NOTIFICATION_SECRET", check(visibility = -1))
        assertEquals("NOTIFICATION_SECRET", check(channel = -1))
    }
    @Test fun missingChannelAndOtherProfileFailClosed() {
        assertEquals("NOTIFICATION_VISIBILITY_UNKNOWN", check(channel = null))
        assertEquals("NOTIFICATION_PROFILE_LOCKED", check(sameUser = false))
    }
    @Test fun systemAndAdminRestrictionsWin() {
        assertEquals("LOCK_SCREEN_NOTIFICATIONS_DISABLED", check(show = false))
        assertEquals("NOTIFICATION_DEVICE_POLICY", check(devicePolicy = false))
    }
    @Test fun publicDoesNotNeedSensitiveContentEnabled() {
        assertNull(check(visibility = 1, privateContent = false))
    }
    @Test fun unlockedReadIsUnaffected() {
        assertNull(check(locked = false, show = false, privateContent = false, channel = null))
    }
}
