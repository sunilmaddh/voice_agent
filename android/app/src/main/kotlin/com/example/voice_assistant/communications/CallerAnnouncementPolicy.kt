package com.example.voice_assistant.communications

object CallerAnnouncementPolicy {
    fun useMediaAudio(ringerNormal: Boolean, ringVolume: Int): Boolean = !ringerNormal || ringVolume == 0
    fun alertBlocked(notificationsEnabled: Boolean, channelEnabled: Boolean): String? =
        if (!notificationsEnabled || !channelEnabled) "INCOMING_CHANNEL_BLOCKED" else null
    fun blocked(enabled: Boolean, defaultPhone: Boolean, ringingOnly: Boolean, locked: Boolean,
        allowLocked: Boolean, driving: Boolean, quiet: Boolean): String? = when {
        !enabled -> "DISABLED"
        !defaultPhone -> "DEFAULT_PHONE_REQUIRED"
        !ringingOnly -> "CALL_NOT_RINGING_OR_OTHER_CALL_ACTIVE"
        locked && !allowLocked -> "LOCKED_ANNOUNCEMENT_DISABLED"
        driving -> "DRIVING_MODE_SILENT"
        quiet -> "SILENT_OR_DND"
        else -> null
    }
    fun phrase(numberAllowed: Boolean, contact: String?, carrierNameAllowed: Boolean, carrier: String?): String {
        if (!numberAllowed) return "Incoming call from a private or unavailable number."
        fun clean(value: String?) = value?.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ")
            ?.replace(Regex("\\s+"), " ")?.trim()?.take(80)?.takeIf { it.isNotBlank() }
        val name = clean(contact) ?: if (carrierNameAllowed) clean(carrier) else null
        return if (name != null) "Incoming call from $name." else "Incoming call from an unknown caller."
    }
}
