package com.example.voice_assistant.voice.wakeword

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Main-thread ownership transfer: never re-open the microphone while STT owns it. */
class WakeHandoff {
    private val started = CompletableDeferred<Unit>()
    private val completed = CompletableDeferred<Unit>()
    private var accepting = true
    fun claim(): Boolean {
        if (!accepting) return false
        accepting = false
        started.complete(Unit)
        return true
    }
    fun finish() { completed.complete(Unit) }
    suspend fun awaitCompletion(startTimeoutMs: Long = 5000) {
        val claimed = withTimeoutOrNull(startTimeoutMs) { started.await(); true } == true
        accepting = false
        if (claimed || started.isCompleted) completed.await()
    }
}
