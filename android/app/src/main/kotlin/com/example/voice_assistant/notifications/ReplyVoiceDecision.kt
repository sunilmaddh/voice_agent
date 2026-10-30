package com.example.voice_assistant.notifications

enum class ReplyVoiceDecision { SEND, CANCEL, UNRECOGNIZED;
    companion object {
        fun parse(text: String, isFinal: Boolean): ReplyVoiceDecision {
            if (!isFinal) return UNRECOGNIZED
            return when (text.trim().lowercase(java.util.Locale.US).trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")) {
                "send reply", "send the reply" -> SEND
                "cancel reply", "cancel the reply" -> CANCEL
                else -> UNRECOGNIZED
            }
        }
    }
}
