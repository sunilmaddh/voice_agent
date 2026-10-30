package com.example.voice_assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.voice_assistant.actions.*
import com.example.voice_assistant.intent.TypedCommand
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Live Android HTTP/executor probe; not speech, locked lifecycle or audibility validation. */
@RunWith(AndroidJUnit4::class)
class WebLookupDeviceTest {
    @Test fun kotlinDirectAnswerOnAndroid() = runBlocking {
        val result = WebAnswerExecutor(SpokenWebSearch::search,
            { _, _ -> error("Direct abstract should not require a model") }).execute(
            com.example.voice_assistant.native_bridge.contracts.AssistantAction(
                "GOOGLE_SEARCH", TypedCommand.GoogleSearch("what is Kotlin").toIntent(1.0)))
        assertEquals(result.message, "WEB_ANSWER_READY", result.code)
        assertTrue(result.message.contains("programming language", ignoreCase = true))
        android.util.Log.i("WebLookupDeviceTest", "KOTLIN_DIRECT_ANSWER_PASS source=${result.evidence["sourceUrl"]}")
        Unit
    }

    @Test fun bothBangaloreAliasesResolveIndiaOnAndroid() = runBlocking {
        for (city in listOf("Bangalore", "Bengaluru")) {
            val evidence = WeatherSearch().search("weather in $city").single()
            assertTrue(evidence.text, evidence.text.contains("Bengaluru, India"))
            assertTrue(evidence.text, evidence.text.contains("Asia/Kolkata"))
            assertTrue(evidence.text, evidence.text.contains("Updated"))
            android.util.Log.i("WebLookupDeviceTest", "$city: ${evidence.text}")
        }
    }
}
