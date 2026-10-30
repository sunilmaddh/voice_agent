package com.example.voice_assistant

import com.example.voice_assistant.intent.*
import com.example.voice_assistant.stt.EnglishRecognitionPolicy
import com.example.voice_assistant.native_bridge.contracts.SpeechOptions
import org.junit.Assert.*
import org.junit.Test

class HindiCommandsTest {
    @Test fun commonCommandsPreservePayload() {
        val router = LocalIntentRouter()
        assertEquals("weather in बैंगलोर", router.decide("बैंगलोर का मौसम बताओ").proposedAction!!.intent.parameters.single().value)
        assertEquals("weather in Bangalore", router.decide("Bangalore ka weather batao").proposedAction!!.intent.parameters.single().value)
        assertEquals("राहुल", router.decide("राहुल को कॉल करो").proposedAction!!.intent.parameters.single().value)
        assertEquals("READ_NOTIFICATION", router.decide("मेरे नोटिफिकेशन पढ़ो").proposedAction!!.name)
        assertEquals("माउंट एवरेस्ट", router.decide("माउंट एवरेस्ट खोजो").proposedAction!!.intent.parameters.single().value)
    }
    @Test fun negationAndCompoundCommandsNeverExecute() {
        val router = LocalIntentRouter()
        for (text in listOf("राहुल को कॉल मत करो", "Rahul ko call nahi karo", "अगर राहुल को कॉल करो", "राहुल को कॉल करो और मैप खोलो"))
            assertNull(router.decide(text).proposedAction)
    }
    @Test fun hindiNeverSelectsEnglishOfflineModel() {
        assertEquals("hi-IN", EnglishRecognitionPolicy.selectInstalled(listOf("en-US", "hi-IN"), "hi-IN"))
        assertNull(EnglishRecognitionPolicy.selectInstalled(listOf("en-US"), "hi-IN"))
        assertEquals("hi-IN", SpeechOptions.fromWire(mapOf("languageTag" to "hi-IN")).languageTag)
        assertEquals("en-US", SpeechOptions.fromWire(null).languageTag)
    }
}
