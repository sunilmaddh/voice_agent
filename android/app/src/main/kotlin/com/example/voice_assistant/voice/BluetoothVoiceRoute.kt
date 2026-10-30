package com.example.voice_assistant.voice

import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.example.voice_assistant.native_bridge.contracts.SpeechFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One owner across wake capture, provider STT and TTS; no Bluetooth names/addresses logged. */
object BluetoothVoiceRoute {
    private val mutex = Mutex()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var retainedAudio: AudioManager? = null
    private var retainedDeviceId: Int? = null
    private var pendingRelease: Runnable? = null

    private fun releaseRetained() {
        pendingRelease?.let(main::removeCallbacks)
        pendingRelease = null
        val audio = retainedAudio
        retainedAudio = null
        retainedDeviceId = null
        if (Build.VERSION.SDK_INT >= 31 && audio != null) {
            try { audio.clearCommunicationDevice() }
            finally {
                if (audio.mode == AudioManager.MODE_IN_COMMUNICATION) audio.mode = AudioManager.MODE_NORMAL
            }
        }
    }
    fun isHeadset(type: Int) = type == 7 || type == 26 // SCO or BLE_HEADSET; never media-only A2DP.

    suspend fun <T> use(context: Context, report: (Map<String, Any?>) -> Unit = {},
        block: suspend (Boolean) -> T): T = mutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            val audio = context.getSystemService(AudioManager::class.java)
            pendingRelease?.let(main::removeCallbacks)
            pendingRelease = null
            if (Build.VERSION.SDK_INT < 31) {
                report(mapOf("bluetoothRouting" to "UNSUPPORTED_BELOW_API_31"))
                return@withContext block(false)
            }
            val target = audio.availableCommunicationDevices.firstOrNull { isHeadset(it.type) }
            if (target == null) {
                releaseRetained()
                report(mapOf("bluetoothRouting" to "SYSTEM_DEFAULT_NO_COMMUNICATION_HEADSET"))
                return@withContext coroutineScope {
                    val monitor = launch {
                        while (isActive) {
                            delay(500)
                            if (audio.availableCommunicationDevices.any { isHeadset(it.type) })
                                throw SpeechFailure("BLUETOOTH_ROUTE_CHANGED")
                        }
                    }
                    try { block(false) } finally { monitor.cancel() }
                }
            }
            // Never take over a call or another app's communication session.
            val reuse = retainedAudio != null && retainedDeviceId == target.id &&
                audio.mode == AudioManager.MODE_IN_COMMUNICATION && audio.communicationDevice?.id == target.id
            if (!reuse) releaseRetained()
            if (!reuse && audio.mode != AudioManager.MODE_NORMAL) throw SpeechFailure("AUDIO_COMMUNICATION_BUSY")
            var completed = false
            try {
                if (!reuse) {
                    retainedAudio = audio
                    audio.mode = AudioManager.MODE_IN_COMMUNICATION
                    if (!audio.setCommunicationDevice(target)) throw SpeechFailure("BLUETOOTH_ROUTE_REJECTED")
                    retainedDeviceId = target.id
                }
                val ready = withTimeoutOrNull(5000) {
                    while (audio.communicationDevice?.id != target.id) delay(50)
                    true
                } ?: false
                if (!ready) throw SpeechFailure("BLUETOOTH_ROUTE_TIMEOUT")
                report(mapOf("bluetoothRouting" to "COMMUNICATION_DEVICE_SELECTED",
                    "bluetoothRouteReused" to reuse,
                    "communicationDeviceType" to target.type,
                    "sttMicrophoneVerification" to "PROVIDER_CONTROLLED_PENDING_DEVICE_TEST"))
                val result = coroutineScope {
                    val monitor = launch {
                        while (isActive) {
                            delay(250)
                            if (audio.availableCommunicationDevices.none { it.id == target.id } ||
                                audio.communicationDevice?.id != target.id)
                                throw SpeechFailure("BLUETOOTH_ROUTE_LOST")
                        }
                    }
                    try { block(true) } finally { monitor.cancel() }
                }
                completed = true
                result
            } finally {
                // Preserve SCO briefly across wake -> acknowledgement -> STT -> TTS.
                // Tearing it down at every boundary races the asynchronous Bluetooth transport.
                if (completed) {
                    val release = Runnable { releaseRetained() }
                    pendingRelease = release
                    main.postDelayed(release, 3000)
                } else {
                    releaseRetained()
                }
            }
        }
    }
}
