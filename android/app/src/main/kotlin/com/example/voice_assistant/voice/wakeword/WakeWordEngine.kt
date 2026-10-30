package com.example.voice_assistant.voice.wakeword

/** Owns capture until it returns; release microphone before handing off to STT. */
interface WakeWordEngine {
    suspend fun awaitDetection(onReady: (Double) -> Unit, onAudio: (Map<String, Any?>) -> Unit = {}): WakeWordDetection
    fun close() {}
}
data class WakeWordDetection(val audioSeconds: Double, val decodeMs: Double)

object WakeWordPolicy {
    fun accepts(keyword: String) = keyword in setOf("HEY_INDO", "HEY_AIKO")
    fun mayHandoff(resumed: Boolean, unlocked: Boolean, busy: Boolean, hasListener: Boolean) =
        resumed && unlocked && !busy && hasListener
}
