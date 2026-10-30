package com.example.voice_assistant.communications

object VoiceFriendlyRinging {
    fun vibrationOnly(enabled: Boolean, wakeListening: Boolean) = enabled && wakeListening
}
