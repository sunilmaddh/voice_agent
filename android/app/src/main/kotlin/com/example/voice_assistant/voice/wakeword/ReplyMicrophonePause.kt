package com.example.voice_assistant.voice.wakeword

import kotlinx.coroutines.*

/** Main-thread lease. Wake capture must close before a reply review opens another recorder. */
object ReplyMicrophonePause {
    private var owners = 0
    val requested: Boolean get() = owners > 0
    suspend fun <T> use(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) {
        owners++
        try {
            withTimeout(5000) {
                while ((WakeWordService.enabled || WakeWordService.active) &&
                    WakeWordService.report["state"] != "PAUSED_FOR_REPLY") delay(50)
            }
            block()
        } finally { owners-- }
    }
}

/** Does not report a pause until the detector's cancellation cleanup has completed. */
internal suspend fun detectionUntilReplyPause(detector: WakeWordEngine, paused: () -> Boolean,
    onReady: (Double) -> Unit, onAudio: (Map<String, Any?>) -> Unit): WakeWordDetection? = coroutineScope {
    var cancelledForReply = false
    val detection = async { detector.awaitDetection(onReady, onAudio) }
    val monitor = launch {
        while (isActive) {
            if (paused()) { cancelledForReply = true; detection.cancel(); return@launch }
            delay(50)
        }
    }
    try { detection.await() }
    catch (cancelled: CancellationException) {
        currentCoroutineContext().ensureActive()
        if (cancelledForReply) null else throw cancelled
    } finally { monitor.cancel() }
}
