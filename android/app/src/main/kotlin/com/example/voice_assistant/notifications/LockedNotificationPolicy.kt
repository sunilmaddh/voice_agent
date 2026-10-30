package com.example.voice_assistant.notifications

/** Conservative readout policy; the caller separately enforces the personal-readout opt-in. */
object LockedNotificationPolicy {
    fun restriction(locked: Boolean, sameUser: Boolean, showNotifications: Boolean,
        allowPrivate: Boolean, visibility: Int, channelVisibility: Int?,
        devicePolicyAllows: Boolean): String? {
        if (!locked) return null
        if (!sameUser) return "NOTIFICATION_PROFILE_LOCKED"
        if (!devicePolicyAllows) return "NOTIFICATION_DEVICE_POLICY"
        if (!showNotifications) return "LOCK_SCREEN_NOTIFICATIONS_DISABLED"
        if (visibility == -1 || channelVisibility == -1) return "NOTIFICATION_SECRET"
        if (channelVisibility == null) return "NOTIFICATION_VISIBILITY_UNKNOWN"
        if (channelVisibility == 0) return "NOTIFICATION_CHANNEL_PRIVATE"
        if (visibility == 0 && !allowPrivate) return "LOCK_SCREEN_PRIVATE_CONTENT_DISABLED"
        if (visibility !in 0..1) return "NOTIFICATION_VISIBILITY_UNKNOWN"
        return null
    }
}
