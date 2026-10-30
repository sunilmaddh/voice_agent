package com.example.voice_assistant.voice.wakeword

/** Retry transport setup only, never replay a command or retry an Android action. */
class WakeRouteRecovery {
    var attempts = 0; private set
    fun retry(code: String): Boolean {
        if (code !in setOf("BLUETOOTH_ROUTE_CHANGED", "BLUETOOTH_ROUTE_LOST",
                "BLUETOOTH_ROUTE_TIMEOUT", "BLUETOOTH_ROUTE_REJECTED", "BLUETOOTH_MIC_NOT_ROUTED")) return false
        if (attempts >= 3) return false
        attempts++
        return true
    }
    fun captured(seconds: Double) { if (seconds >= 10.0) attempts = 0 }
}
