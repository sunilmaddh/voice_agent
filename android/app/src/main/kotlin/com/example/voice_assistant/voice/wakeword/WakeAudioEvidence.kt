package com.example.voice_assistant.voice.wakeword

/** Numeric metering only. Retains the latest locked interval after the user unlocks to copy diagnostics. */
class WakeAudioEvidence {
    private var previousLocked: Boolean? = null
    private data class Interval(var windows: Int = 0, var peakRms: Double? = null,
        var silenced: Int = 0, var unsilenced: Int = 0, var lastAtMs: Long = 0) {
        fun wire() = mapOf("meterWindows" to windows, "maxWindowRmsDbfs" to peakRms,
            "silencedWindows" to silenced, "unsilencedWindows" to unsilenced, "lastSampleAtMs" to lastAtMs)
    }
    private var locked = Interval()
    private var unlocked = Interval()
    fun record(isLocked: Boolean, rms: Double?, silenced: Boolean?, atMs: Long): Map<String, Any?> {
        if (previousLocked != isLocked) {
            if (isLocked) locked = Interval() else unlocked = Interval()
            previousLocked = isLocked
        }
        val interval = if (isLocked) locked else unlocked
        interval.windows++
        if (rms != null && rms.isFinite()) interval.peakRms = maxOf(interval.peakRms ?: rms, rms)
        if (silenced == true) interval.silenced++
        if (silenced == false) interval.unsilenced++
        interval.lastAtMs = atMs
        return mapOf("latestLockedCapture" to locked.wire(), "latestUnlockedCapture" to unlocked.wire())
    }
}
