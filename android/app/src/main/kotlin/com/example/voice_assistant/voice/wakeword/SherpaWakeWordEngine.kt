package com.example.voice_assistant.voice.wakeword

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*

/** No speech transcripts, disk audio, network calls or continuous SpeechRecognizer loop. */
class SherpaWakeWordEngine(private val context: Context) : WakeWordEngine {
    // Reuse model weights between commands; each capture still owns a fresh stream and microphone.
    private var spotter: KeywordSpotter? = null
    override fun close() {
        spotter?.release()
        spotter = null
    }
    companion object {
        // More tolerant starting point; real speech and false wakes still need device measurement.
        const val KEYWORDS_SCORE = 4f
        const val KEYWORDS_THRESHOLD = 0.07f
        fun config() = KeywordSpotterConfig(
            modelConfig = OnlineModelConfig(transducer = OnlineTransducerModelConfig(
                encoder = "wake_word/encoder.onnx", decoder = "wake_word/decoder.onnx", joiner = "wake_word/joiner.onnx"),
                tokens = "wake_word/tokens.txt", modelType = "zipformer2", modelingUnit = "cjkchar", numThreads = 1, debug = false),
            keywordsFile = "wake_word/keywords.txt", keywordsScore = KEYWORDS_SCORE,
            keywordsThreshold = KEYWORDS_THRESHOLD, maxActivePaths = 8)
    }
    @android.annotation.SuppressLint("MissingPermission") // Rechecked by the service before entry; revocation throws.
    override suspend fun awaitDetection(onReady: (Double) -> Unit, onAudio: (Map<String, Any?>) -> Unit): WakeWordDetection = com.example.voice_assistant.voice.BluetoothVoiceRoute.use(context, onAudio) { bluetooth -> detect(onReady, onAudio, bluetooth) }

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun detect(onReady: (Double) -> Unit, onAudio: (Map<String, Any?>) -> Unit, bluetooth: Boolean): WakeWordDetection = withContext(Dispatchers.Default) {
        val start = System.nanoTime()
        val spotter = this@SherpaWakeWordEngine.spotter ?: KeywordSpotter(context.assets, config()).also {
            this@SherpaWakeWordEngine.spotter = it
        }
        run {
            val stream = spotter.createStream()
            try {
                val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(min > 0)
                val recorder = AudioRecord(if (bluetooth) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
                try {
                    check(recorder.state == AudioRecord.STATE_INITIALIZED)
                    if (bluetooth) {
                        val audio = context.getSystemService(android.media.AudioManager::class.java)
                        val inputs = audio.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                            .filter { com.example.voice_assistant.voice.BluetoothVoiceRoute.isHeadset(it.type) }
                        // Do not guess between several microphones. Android still owns final routing.
                        if (inputs.size == 1) recorder.setPreferredDevice(inputs.single())
                    }
                    recorder.startRecording()
                    check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    val captureStarted = System.nanoTime()
                    var ready = false
                    val buffer = ShortArray(1600)
                    var samples = 0L
                    var decodeNs = 0L
                    var metered = 0L
                    var energy = 0.0
                    while (currentCoroutineContext().isActive) {
                        val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                        check(count >= 0)
                        val routed = !bluetooth || recorder.routedDevice?.type
                            ?.let(com.example.voice_assistant.voice.BluetoothVoiceRoute::isHeadset) == true
                        if (!routed || (!ready && count == 0)) {
                            if (System.nanoTime() - captureStarted >= 5_000_000_000L || ready)
                                throw com.example.voice_assistant.native_bridge.contracts.SpeechFailure(
                                    if (bluetooth) "BLUETOOTH_MIC_NOT_ROUTED" else "WAKE_AUDIO_NO_SAMPLES")
                            delay(20)
                            continue // Never decode phone-microphone audio as verified Bluetooth input.
                        }
                        if (count == 0) { delay(20); continue }
                        if (!ready) {
                            ready = true
                            withContext(Dispatchers.Main.immediate) { onReady((System.nanoTime() - start) / 1e6) }
                        }
                        samples += count
                        for (i in 0 until count) { val normalized = buffer[i] / 32768.0; energy += normalized * normalized }
                        if (samples - metered >= 16000) {
                            val rmsDbfs = 10 * kotlin.math.log10((energy / (samples - metered)).coerceAtLeast(1e-12))
                            val silenced = if (android.os.Build.VERSION.SDK_INT >= 29) recorder.activeRecordingConfiguration?.isClientSilenced else null
                            withContext(Dispatchers.Main.immediate) { onAudio(mapOf("audioRmsDbfs" to rmsDbfs,
                                "audioSecondsProcessed" to samples / 16000.0, "microphoneSilencedByAndroid" to silenced, "actualInputDeviceType" to recorder.routedDevice?.type,
                                "bluetoothMicrophoneVerified" to (recorder.routedDevice?.type?.let(com.example.voice_assistant.voice.BluetoothVoiceRoute::isHeadset) == true))) }
                            metered = samples; energy = 0.0
                        }
                        val decodeStart = System.nanoTime()
                        stream.acceptWaveform(FloatArray(count) { buffer[it] / 32768f }, 16000)
                        while (spotter.isReady(stream)) {
                            currentCoroutineContext().ensureActive()
                            spotter.decode(stream)
                            if (WakeWordPolicy.accepts(spotter.getResult(stream).keyword)) {
                                decodeNs += System.nanoTime() - decodeStart
                                return@withContext WakeWordDetection(samples / 16000.0, decodeNs / 1e6)
                            }
                        }
                        decodeNs += System.nanoTime() - decodeStart
                    }
                    throw CancellationException()
                } finally {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                    recorder.release()
                }
            } finally { stream.release() }
        }
    }
}
