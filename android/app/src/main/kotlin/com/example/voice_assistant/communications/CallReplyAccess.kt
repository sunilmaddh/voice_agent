package com.example.voice_assistant.communications

/** Only previously enabled automatic driving replies may send while locked. */
object CallReplyAccess {
    fun permitted(locked: Boolean, driving: Boolean, enabled: Boolean, automatic: Boolean,
        voiceRequested: Boolean = false): Boolean =
        if (driving) enabled && automatic else voiceRequested || !locked
}
