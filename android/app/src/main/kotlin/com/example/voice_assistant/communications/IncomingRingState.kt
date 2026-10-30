package com.example.voice_assistant.communications

/** Per-call silence persists through notification updates and announcement fallback. */
class IncomingRingState {
    private val silenced = mutableSetOf<String>()
    private var paused: String? = null
    fun update(ringing: Set<String>) { silenced.retainAll(ringing); if (paused !in ringing) paused = null }
    fun silence(ringing: Set<String>) { silenced.addAll(ringing) }
    fun isSilenced(id: String) = id in silenced
    fun pause(id: String) { paused = id }
    fun resume(id: String) { if (paused == id) paused = null }
    fun alertId(ringing: Set<String>, otherCallActive: Boolean, driving: Boolean): String? =
        if (otherCallActive || driving) null else ringing.firstOrNull { it !in silenced && it != paused }
}
