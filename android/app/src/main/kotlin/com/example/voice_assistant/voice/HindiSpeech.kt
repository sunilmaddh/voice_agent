package com.example.voice_assistant.voice

object HindiSpeech {
    fun response(text: String): String {
        val weather = Regex("According to open-meteo.com, Weather for (.+?): ([\\d.-]+) degrees Celsius, (.+?)\\. Feels like ([\\d.-]+) degrees\\. Updated (.+)\\.").matchEntire(text)
        if (weather != null) {
            val (city, temperature, condition, feels, time) = weather.destructured
            val conditions = mapOf("clear skies" to "आसमान साफ़ है", "mainly clear" to "आसमान लगभग साफ़ है",
                "partly cloudy" to "कुछ बादल हैं", "overcast" to "बादल छाए हैं", "rain" to "बारिश है",
                "rain showers" to "बारिश की बौछारें हैं", "drizzle" to "हल्की बूंदाबांदी है", "foggy" to "कोहरा है",
                "snow" to "बर्फ़बारी है", "thunderstorms" to "गरज के साथ बारिश है")
            return "ओपन मेटियो के अनुसार, $city में तापमान $temperature डिग्री सेल्सियस है। ${conditions[condition] ?: condition}। " +
                "महसूस होने वाला तापमान $feels डिग्री है। जानकारी का समय $time।"
        }
        return when (text) {
            "I will try your call request using your calling settings." -> "आपकी कॉल सेटिंग के अनुसार कॉल करने की कोशिश करूँगी।"
            "Opening Maps." -> "मैप खोल रही हूँ।"
            "Opening Calendar." -> "कैलेंडर खोल रही हूँ।"
            "Unlock with the system prompt to continue this command." -> "यह काम करने के लिए फ़ोन अनलॉक करें।"
            "I couldn't reach web search. Check your internet connection and try again." -> "वेब से जानकारी नहीं मिल सकी। इंटरनेट कनेक्शन जाँचें और फिर कोशिश करें।"
            else -> text // Preserve source content; never pretend to translate arbitrary text.
        }
    }
}
