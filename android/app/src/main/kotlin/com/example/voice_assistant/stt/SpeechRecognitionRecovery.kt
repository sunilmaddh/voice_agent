package com.example.voice_assistant.stt

import com.example.voice_assistant.native_bridge.contracts.SpeechFailure
import kotlinx.coroutines.delay

/** At most two attempts; the second failure propagates. Cancellation never retries. */
object SpeechRecognitionRecovery {
    suspend fun <T> run(local: Boolean, allowSystem: Boolean, systemAvailable: Boolean,
        pause: suspend () -> Unit = { delay(300) }, recognize: suspend (Boolean) -> T): T {
        try { return recognize(local) }
        catch (failure: SpeechFailure) {
            val fallback = local && EnglishRecognitionPolicy.canRetrySystem(failure.code, allowSystem, systemAvailable)
            if (!fallback && failure.code != "STT_SERVER_DISCONNECTED") throw failure
            // The failed recognizer has been destroyed before this coroutine resumes.
            // Give the provider a chance to release the old session before reconnecting.
            pause()
            return recognize(if (fallback) false else local)
        }
    }
}
