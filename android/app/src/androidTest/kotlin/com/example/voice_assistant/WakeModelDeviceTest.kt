package com.example.voice_assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** No microphone, activity launch, credentials, role changes, or private device data. */
@RunWith(AndroidJUnit4::class)
class WakeModelDeviceTest {
    @Test fun bundledModelLoadsAndDecodesSilenceWithoutWake() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val vocabulary = assets.open("wake_word/tokens.txt").bufferedReader().useLines { lines ->
            lines.map { it.substringBeforeLast(' ') }.toSet()
        }
        val keywords = assets.open("wake_word/keywords.txt").bufferedReader().use { it.readText() }.trim()
        keywords.split(Regex("\\s+")).filterNot { it.startsWith('@') }.forEach { assertTrue("Unknown KWS token: $it", it in vocabulary) }
        val model = KeywordSpotter(assets, com.example.voice_assistant.voice.wakeword.SherpaWakeWordEngine.config())
        try {
            val stream = model.createStream()
            try {
                repeat(30) {
                    stream.acceptWaveform(FloatArray(1600), 16000)
                    while (model.isReady(stream)) {
                        model.decode(stream)
                        assertEquals("", model.getResult(stream).keyword)
                    }
                }
            } finally { stream.release() }
        } finally { model.release() }
    }
}
