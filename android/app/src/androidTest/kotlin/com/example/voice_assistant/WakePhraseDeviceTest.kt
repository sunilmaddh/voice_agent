package com.example.voice_assistant

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic offline TTS fixture, not a microphone/real-user accuracy benchmark. */
@RunWith(AndroidJUnit4::class)
class WakePhraseDeviceTest {
    @Test fun detectsSynthesizedHeyIndoButNotOtherPhrases() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ready = CountDownLatch(1)
        var initialized = false
        lateinit var tts: TextToSpeech
        instrumentation.runOnMainSync {
            tts = TextToSpeech(context) { initialized = it == TextToSpeech.SUCCESS; ready.countDown() }
        }
        try {
            assumeTrue("TTS initialization unavailable", ready.await(15, TimeUnit.SECONDS) && initialized)
            var hasVoice = false
            instrumentation.runOnMainSync {
                val voice = tts.voices.orEmpty().filter { it.locale.language == "en" && !it.isNetworkConnectionRequired &&
                    !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                    .sortedWith(compareBy({ if (it.locale.country == "US") 0 else 1 }, { it.name })).firstOrNull()
                if (voice != null) { tts.voice = voice; hasVoice = true }
            }
            assumeTrue("No installed offline English voice", hasVoice)
            val model = KeywordSpotter(context.assets, com.example.voice_assistant.voice.wakeword.SherpaWakeWordEngine.config())
            try {
                for ((phrase, expected) in listOf("Hey Indo" to "HEY_INDO", "Hey Sakura" to "", "Hey Google" to "", "Hey Aiko" to "HEY_AIKO", "Hey Michael" to "", "Indo" to "")) {
                    val wav = File.createTempFile("wake-fixture-", ".wav", context.cacheDir)
                    try {
                        val done = CountDownLatch(1)
                        var success = false
                        instrumentation.runOnMainSync {
                            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(id: String?) {}
                                override fun onDone(id: String?) { success = true; done.countDown() }
                                @Deprecated("Android callback") override fun onError(id: String?) { done.countDown() }
                            })
                            assertEquals(TextToSpeech.SUCCESS, tts.synthesizeToFile(phrase, null, wav, "fixture"))
                        }
                        assertTrue("Offline fixture synthesis failed", done.await(20, TimeUnit.SECONDS) && success)
                        val bytes = wav.readBytes()
                        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                        var offset = 12
                        var sampleRate = 0
                        var samples = FloatArray(0)
                        while (offset + 8 <= bytes.size) {
                            val name = String(bytes, offset, 4, Charsets.US_ASCII)
                            val size = buffer.getInt(offset + 4)
                            require(size >= 0 && offset + 8L + size <= bytes.size)
                            if (name == "fmt ") {
                                assertEquals(1, buffer.getShort(offset + 8).toInt())
                                assertEquals(1, buffer.getShort(offset + 10).toInt())
                                sampleRate = buffer.getInt(offset + 12)
                                assertEquals(16, buffer.getShort(offset + 22).toInt())
                            }
                            if (name == "data") samples = FloatArray(size / 2) { buffer.getShort(offset + 8 + it * 2) / 32768f }
                            offset += 8 + size + size % 2
                        }
                        assertTrue(sampleRate > 0 && samples.isNotEmpty())
                        val stream = model.createStream()
                        var detected = ""
                        try {
                            stream.acceptWaveform(samples + FloatArray(sampleRate), sampleRate)
                            while (model.isReady(stream)) {
                                model.decode(stream)
                                val keyword = model.getResult(stream).keyword
                                if (keyword.isNotEmpty()) detected = keyword
                            }
                        } finally { stream.release() }
                        if (expected.isNotEmpty() && detected.isEmpty()) {
                            val grid = mutableListOf<String>()
                            for (score in listOf(3f, 5f, 8f)) for (threshold in listOf(.25f, .1f)) {
                                val candidate = KeywordSpotter(context.assets, model.config.copy(keywordsScore = score, keywordsThreshold = threshold))
                                try {
                                    val diagnostic = candidate.createStream()
                                    var match = false
                                    try {
                                        diagnostic.acceptWaveform(samples + FloatArray(sampleRate), sampleRate)
                                        while (candidate.isReady(diagnostic)) {
                                            candidate.decode(diagnostic)
                                            if (candidate.getResult(diagnostic).keyword.isNotEmpty()) match = true
                                        }
                                    } finally { diagnostic.release() }
                                    grid.add("$score/$threshold=$match")
                                } finally { candidate.release() }
                            }
                            fail("Synthetic phrase '$phrase' missed; score/threshold diagnostic: $grid")
                        }
                        assertEquals("Synthetic phrase: $phrase", expected, detected)
                    } finally { wav.delete() }
                }
            } finally { model.release() }
        } finally { instrumentation.runOnMainSync { tts.shutdown() } }
    }
}
