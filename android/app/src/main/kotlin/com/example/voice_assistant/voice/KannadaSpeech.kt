package com.example.voice_assistant.voice

object KannadaSpeech {
    fun response(text: String): String {
        val weather = Regex("According to open-meteo.com, Weather for (.+?): ([\\d.-]+) degrees Celsius, (.+?)\\. Feels like ([\\d.-]+) degrees\\. Updated (.+)\\.").matchEntire(text)
        if (weather != null) {
            val (city, temperature, condition, feels, time) = weather.destructured
            val conditions = mapOf("clear skies" to "ಆಕಾಶ ಶುಭ್ರವಾಗಿದೆ", "mainly clear" to "ಆಕಾಶ ಬಹುತೇಕ ಶುಭ್ರವಾಗಿದೆ",
                "partly cloudy" to "ಭಾಗಶಃ ಮೋಡ ಕವಿದಿದೆ", "overcast" to "ಮೋಡ ಕವಿದಿದೆ", "rain" to "ಮಳೆಯಾಗಿದೆ",
                "rain showers" to "ಮಳೆಯ ತುಂತುರು", "drizzle" to "ತುಂತುರು ಮಳೆ", "foggy" to "ಮಂಜು ಕವಿದಿದೆ",
                "snow" to "ಹಿಮಪಾತ", "thunderstorms" to "ಗುಡುಗು ಸಹಿತ ಮಳೆ")
            return "ಓಪನ್ ಮೆಟಿಯೋ ಪ್ರಕಾರ, $city ತಾಪಮಾನ $temperature ಡಿಗ್ರಿ ಸೆಲ್ಸಿಯಸ್. ${conditions[condition] ?: condition}. " +
                "ಅನುಭವವಾಗುವ ತಾಪಮಾನ $feels ಡಿಗ್ರಿ. ಮಾಹಿತಿ ಸಮಯ $time."
        }
        return when (text) {
        "I will try your call request using your calling settings." -> "ನಿಮ್ಮ ಕರೆ ಸೆಟ್ಟಿಂಗ್‌ಗಳ ಪ್ರಕಾರ ಕರೆ ಮಾಡಲು ಪ್ರಯತ್ನಿಸುತ್ತೇನೆ."
        "Opening Maps." -> "ನಕ್ಷೆಯನ್ನು ತೆರೆಯುತ್ತಿದ್ದೇನೆ."
        "Google Maps launch requested. Check Maps for the destination and route." -> "ಗೂಗಲ್ ಮ್ಯಾಪ್ಸ್ ತೆರೆಯುತ್ತಿದ್ದೇನೆ. ಗಮ್ಯಸ್ಥಾನ ಮತ್ತು ಮಾರ್ಗವನ್ನು ಮ್ಯಾಪ್ಸ್‌ನಲ್ಲಿ ಪರಿಶೀಲಿಸಿ."
        "Unlock your phone to open Google Maps." -> "ಗೂಗಲ್ ಮ್ಯಾಪ್ಸ್ ತೆರೆಯಲು ನಿಮ್ಮ ಫೋನ್ ಅನ್‌ಲಾಕ್ ಮಾಡಿ."
        "Opening Calendar." -> "ಕ್ಯಾಲೆಂಡರ್ ತೆರೆಯುತ್ತಿದ್ದೇನೆ."
        "Unlock with the system prompt to continue this command." -> "ಮುಂದುವರಿಯಲು ನಿಮ್ಮ ಫೋನ್ ಅನ್‌ಲಾಕ್ ಮಾಡಿ."
        "Search results are opening in Aiko. Tap Speak summary to hear a report." -> "ಐಕೋದಲ್ಲಿ ಹುಡುಕಾಟದ ಫಲಿತಾಂಶಗಳನ್ನು ತೆರೆಯುತ್ತಿದ್ದೇನೆ. ವರದಿ ಕೇಳಲು ಸ್ಪೀಕ್ ಸಮ್ಮರಿ ಒತ್ತಿ."
        "I couldn't reach web search. Check your internet connection and try again." -> "ಹುಡುಕಾಟವನ್ನು ಸಂಪರ್ಕಿಸಲು ಸಾಧ್ಯವಾಗಲಿಲ್ಲ. ಇಂಟರ್ನೆಟ್ ಸಂಪರ್ಕ ಪರಿಶೀಲಿಸಿ ಮತ್ತೆ ಪ್ರಯತ್ನಿಸಿ."
        else -> text
        }
    }
}
