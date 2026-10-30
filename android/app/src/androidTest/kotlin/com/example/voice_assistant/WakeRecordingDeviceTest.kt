package com.example.voice_assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.voice_assistant.voice.wakeword.SherpaWakeWordEngine
import com.google.gson.GsonBuilder
import com.k2fsa.sherpa.onnx.KeywordSpotter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in offline replay of user-supplied audio. Does not capture the microphone or execute commands. */
@RunWith(AndroidJUnit4::class)
class WakeRecordingDeviceTest {
    @Test fun compareSettingsOnUserRecording() {
        val arguments = InstrumentationRegistry.getArguments()
        val path = arguments.getString("wakeAudioPath")
        assumeTrue("Pass wakeAudioPath to replay a supplied recording", path != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(path!!)
        require(file.isFile && file.length() in 44..10_000_000) { "Missing or oversized recording" }
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
        require(tag(0) == "RIFF" && tag(8) == "WAVE") { "Use PCM WAV audio" }
        var rate = 0
        var samples: FloatArray? = null
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val size = buffer.getInt(offset + 4)
            require(size >= 0 && offset + 8L + size <= bytes.size)
            when (tag(offset)) {
                "fmt " -> {
                    require(size >= 16 && buffer.getShort(offset + 8).toInt() == 1 &&
                        buffer.getShort(offset + 10).toInt() == 1 && buffer.getShort(offset + 22).toInt() == 16) {
                        "Use mono, 16-bit PCM WAV audio"
                    }
                    rate = buffer.getInt(offset + 12)
                }
                "data" -> {
                    require(size % 2 == 0)
                    samples = FloatArray(size / 2) { buffer.getShort(offset + 8 + 2 * it) / 32768f }
                }
            }
            offset += 8 + size + size % 2
        }
        require(rate in 8000..48000 && samples?.isNotEmpty() == true)
        val audio = samples!!
        val results = mutableListOf<Map<String, Any>>()
        // Include current settings first. Compare repeatability before choosing a production change.
        for ((score, threshold) in listOf(4f to .07f, 3f to .1f, 4f to .1f, 4f to .04f, 5f to .07f)) {
            val model = KeywordSpotter(context.assets, SherpaWakeWordEngine.config().copy(
                keywordsScore = score, keywordsThreshold = threshold))
            val detections = mutableListOf<Map<String, Any>>()
            val stream = model.createStream()
            val started = System.nanoTime()
            try {
                // Replay in live-sized chunks; reset after each wake to measure repeated phrases.
                val padded = audio + FloatArray(rate)
                val chunk = rate / 10
                var position = 0
                while (position < padded.size) {
                    val end = minOf(position + chunk, padded.size)
                    stream.acceptWaveform(padded.copyOfRange(position, end), rate)
                    while (model.isReady(stream)) {
                        model.decode(stream)
                        val keyword = model.getResult(stream).keyword
                        if (keyword.isNotEmpty()) {
                            detections.add(mapOf("keyword" to keyword, "processedAudioSeconds" to end.toDouble() / rate))
                            model.reset(stream)
                        }
                    }
                    position = end
                }
            } finally { stream.release(); model.release() }
            results.add(mapOf("score" to score, "threshold" to threshold,
                "decodeWallMs" to (System.nanoTime() - started) / 1e6, "detections" to detections))
        }
        val report = mapOf("audioDurationSeconds" to audio.size.toDouble() / rate,
            "sampleRate" to rate, "results" to results,
            "limitation" to "Manually compare detections with spoken phrases; counts alone do not measure accuracy or false wakes.")
        val output = File(context.getExternalFilesDir(null), "wake-recording-report.json")
        output.writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
        assertTrue(output.isFile)
        println("Wake recording report: ${output.absolutePath}")
    }
}
