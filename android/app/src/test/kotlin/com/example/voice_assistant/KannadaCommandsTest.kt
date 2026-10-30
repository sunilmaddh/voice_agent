package com.example.voice_assistant

import com.example.voice_assistant.intent.*
import com.example.voice_assistant.voice.KannadaSpeech
import com.example.voice_assistant.stt.EnglishRecognitionPolicy
import com.example.voice_assistant.native_bridge.contracts.SpeechOptions
import org.junit.Assert.*
import org.junit.Test

class KannadaCommandsTest {
    @Test fun mixedKannadaEnglishAndRomanizedCommands() {
        val router = LocalIntentRouter()
        for (text in listOf("ನನ್ನನ್ನು Bangalore airport ಗೆ ಕರೆದುಕೊಂಡು ಹೋಗು",
            "Bangalore airport ಗೆ take me", "nannannu Bangalore airport ge karedukondu hogu",
            "navigate Bangalore airport ge maadi")) {
            val result = router.decide(text)
            assertEquals(text, "NAVIGATE", result.proposedAction?.name)
            assertEquals(text, "Bangalore airport", result.proposedAction!!.intent.parameters.single().value)
        }
        assertEquals("Rahul", router.decide("Rahul ge call maadu").proposedAction!!.intent.parameters.single().value)
        assertEquals("Rahul", router.decide("Rahul ಗೆ call ಮಾಡಿ").proposedAction!!.intent.parameters.single().value)
        assertEquals("Nifty 50 live price", router.decide("Nifty 50 live price search maadi").proposedAction!!.intent.parameters.single().value)
        assertEquals("READ_NOTIFICATION", router.decide("notifications ಓದಿ").proposedAction!!.name)
        assertEquals("weather in Bangalore", KannadaCommands.normalize("Bangalore weather helu"))
        for (text in listOf("Rahul ge call madbeda", "don't Rahul ಗೆ call ಮಾಡಿ", "Rahul ge call maadu and maps open maadu"))
            assertNull(text, router.decide(text).proposedAction)
    }
    @Test fun airportNavigationRecognizesNaturalKannadaAndPreservesPlace() {
        val router = LocalIntentRouter()
        for (text in listOf(
            "ನನ್ನನ್ನು ಬೆಂಗಳೂರು ವಿಮಾನ ನಿಲ್ದಾಣಕ್ಕೆ ಕರೆದುಕೊಂಡು ಹೋಗು",
            "ಬೆಂಗಳೂರು ವಿಮಾನ ನಿಲ್ದಾಣಕ್ಕೆ ಕರೆದುಕೊಂಡು ಹೋಗಿ",
            "ದಯವಿಟ್ಟು ನನ್ನನ್ನು ಬೆಂಗಳೂರು ವಿಮಾನ ನಿಲ್ದಾಣಕ್ಕೆ ಕರೆದೊಯ್ಯಿರಿ",
            "ಬೆಂಗಳೂರು ವಿಮಾನ ನಿಲ್ದಾಣಕ್ಕೆ ದಾರಿ ತೋರಿಸಿ",
            "ಬೆಂಗಳೂರು ವಿಮಾನ ನಿಲ್ದಾಣಕ್ಕೆ ನ್ಯಾವಿಗೇಟ್ ಮಾಡಿ")) {
            val result = router.decide(text)
            assertEquals(text, "NAVIGATE", result.proposedAction?.name)
            assertEquals(text, "ಬೆಂಗಳೂರು ವಿಮಾನ ನಿಲ್ದಾಣ", result.proposedAction!!.intent.parameters.single().value)
            assertFalse(result.modelRequired)
        }
        assertEquals("navigate to ಬೆಂಗಳೂರು", KannadaCommands.normalize("ಬೆಂಗಳೂರುಗೆ ಕರೆದುಕೊಂಡು ಹೋಗು"))
        assertEquals("navigate to Kempegowda airport", KannadaCommands.normalize("Kempegowda airport ಗೆ ಕರೆದುಕೊಂಡು ಹೋಗಿ"))
    }
    @Test fun incompleteNegatedAndCompoundNavigationDoesNotExecute() {
        for (text in listOf("ನನ್ನನ್ನು ಕರೆದುಕೊಂಡು ಹೋಗು", "ಬೆಂಗಳೂರು ವಿಮಾನ ನಿಲ್ದಾಣಕ್ಕೆ ಕರೆದುಕೊಂಡು ಹೋಗಬೇಡಿ",
            "ಬೆಂಗಳೂರು ವಿಮಾನ ನಿಲ್ದಾಣಕ್ಕೆ ಕರೆದುಕೊಂಡು ಹೋಗು ಮತ್ತು ರಾಹುಲ್ ಗೆ ಕರೆ ಮಾಡಿ"))
            assertNull(text, LocalIntentRouter().decide(text).proposedAction)
    }
    @Test fun commandsPreserveNamesAndQueries() {
        val router = LocalIntentRouter()
        assertEquals("ರಾಹುಲ್", router.decide("ರಾಹುಲ್ ಗೆ ಕರೆ ಮಾಡಿ").proposedAction!!.intent.parameters.single().value)
        assertEquals("ನಿಫ್ಟಿ 50", router.decide("ನಿಫ್ಟಿ 50 ಹುಡುಕಿ").proposedAction!!.intent.parameters.single().value)
        assertEquals("weather in ಬೆಂಗಳೂರು", router.decide("ಬೆಂಗಳೂರು ಹವಾಮಾನ ಹೇಳು").proposedAction!!.intent.parameters.single().value)
        assertEquals("READ_NOTIFICATION", router.decide("ನೋಟಿಫಿಕೇಶನ್ ಓದಿ").proposedAction!!.name)
        assertEquals("what time is it", KannadaCommands.normalize("ಸಮಯ ಎಷ್ಟು"))
    }
    @Test fun negatedCommandsCannotExecute() {
        for (text in listOf("ರಾಹುಲ್ ಗೆ ಕರೆ ಮಾಡಬೇಡಿ", "ಬಹುಶಃ ರಾಹುಲ್ ಗೆ ಕರೆ ಮಾಡಿ"))
            assertNull(LocalIntentRouter().decide(text).proposedAction)
    }
    @Test fun kannadaRequiresKannadaRecognitionModel() {
        assertEquals("kn-IN", SpeechOptions.fromWire(mapOf("languageTag" to "kn-IN")).languageTag)
        assertEquals("kn-IN", EnglishRecognitionPolicy.selectInstalled(listOf("en-US", "hi-IN", "kn-IN"), "kn-IN"))
        assertNull(EnglishRecognitionPolicy.selectInstalled(listOf("en-US", "hi-IN"), "kn-IN"))
        assertEquals("ನಕ್ಷೆಯನ್ನು ತೆರೆಯುತ್ತಿದ್ದೇನೆ.", KannadaSpeech.response("Opening Maps."))
        assertEquals("Source text", KannadaSpeech.response("Source text"))
    }
}
