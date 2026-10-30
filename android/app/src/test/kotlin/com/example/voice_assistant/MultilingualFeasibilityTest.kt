package com.example.voice_assistant

import com.example.voice_assistant.intent.LocalIntentRouter
import com.example.voice_assistant.native_bridge.contracts.RoutingTier
import com.example.voice_assistant.native_bridge.contracts.IntentKind
import org.junit.Assert.*
import org.junit.Test

/** Text-only feasibility checks: no microphone, model inference or external actions. */
class MultilingualFeasibilityTest {
    private fun check(text: String, kind: IntentKind, payload: String? = null) {
        val route = LocalIntentRouter().decide(text)
        assertEquals(text, kind, route.intent.kind)
        assertEquals(text, RoutingTier.deterministic, route.tier)
        assertFalse(text, route.modelRequired)
        assertNotNull(text, route.proposedAction)
        if (payload != null) assertEquals(text, payload, route.intent.parameters.single().value)
    }
    @Test fun englishWeather() = check("weather in Bangalore", IntentKind.googleSearch, "weather in Bangalore")
    @Test fun hindiWeather() = check("बैंगलोर का मौसम बताओ", IntentKind.googleSearch, "weather in बैंगलोर")
    @Test fun hinglishWeather() = check("Bangalore ka weather batao", IntentKind.googleSearch, "weather in Bangalore")
    @Test fun englishCall() = check("call Rahul", IntentKind.callPhone, "Rahul")
    @Test fun hindiCall() = check("राहुल को कॉल करो", IntentKind.callPhone, "राहुल")
    @Test fun hinglishCall() = check("Rahul ko call karo", IntentKind.callPhone, "Rahul")
    @Test fun englishSearch() = check("search for Mount Everest", IntentKind.googleSearch, "Mount Everest")
    @Test fun hindiSearch() = check("माउंट एवरेस्ट खोजो", IntentKind.googleSearch, "माउंट एवरेस्ट")
    @Test fun hinglishSearch() = check("Mount Everest search karo", IntentKind.googleSearch, "Mount Everest")
    @Test fun englishNotifications() = check("read notifications", IntentKind.readNotification)
    @Test fun hindiNotifications() = check("मेरे नोटिफिकेशन पढ़ो", IntentKind.readNotification)
    @Test fun hinglishNotifications() = check("notifications padho", IntentKind.readNotification)
}
