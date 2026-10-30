package com.example.voice_assistant

import android.os.Build
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.voice_assistant.tts.AndroidTextToSpeechEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Capability metadata only: no spoken input, voice playback or model inference. */
@RunWith(AndroidJUnit4::class)
class MultilingualReadinessDeviceTest {
    @Test fun inspectSpeechCapabilityMetadata(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tts = AndroidTextToSpeechEngine(context) { }
        try {
            val capabilities = tts.capabilities()
            val voices = (capabilities["voices"] as List<*>).map { it as Map<*, *> }
                .filter { it["installed"] == true && it["requiresNetwork"] == false }
                .map { mapOf("name" to it["name"], "locale" to it["locale"]) }
            val data = mapOf("onDeviceRecognizerAvailable" to
                (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)),
                "ttsEngine" to capabilities["ttsEngine"], "installedOfflineVoices" to voices,
                "scope" to "CAPABILITY_METADATA_NOT_OFFLINE_EXECUTION_OR_AUDIBILITY")
            android.util.Log.i("MultilingualReadiness", JSONObject(data).toString())
        } finally { tts.close() }
    }
}
