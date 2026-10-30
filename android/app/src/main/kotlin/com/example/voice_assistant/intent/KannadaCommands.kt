package com.example.voice_assistant.intent

/** Complete command templates only; preserve names and queries verbatim. */
object KannadaCommands {
    fun normalize(raw: String): String {
        val text = raw.trim().trimEnd('.', '?', '!').replace(Regex("\\s+"), " ")
        val mixed = Regex("[\\u0C80-\\u0CFF]|\\b(?:maadu|madu|maadi|madi|torisu|torisi|hog[uia]|hogi|odu|odi|helu|heli|huduku|huduki|karedkondu|karedukondu|nannannu|beda|bedi|madbeda)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        if (!mixed) return raw
        if (Regex("ಬೇಡ|ಬೇಡಿ|ಮಾಡಬೇ|ಬೇಡವ|ಒಂದು ವೇಳೆ|ಬಹುಶಃ|ಮತ್ತು|ನಂತರ|ಹಾಗೂ|\\b(?:beda|bedi|madbeda|maadabeda|mattu|nantara|maybe|if|don't|do not|then|and|or)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "do not execute"
        val exact = mapOf(
            "ನೋಟಿಫಿಕೇಶನ್ ಓದು" to "read notifications", "ನೋಟಿಫಿಕೇಶನ್ ಓದಿ" to "read notifications",
            "ನನ್ನ ನೋಟಿಫಿಕೇಶನ್ ಓದು" to "read notifications", "ಅಧಿಸೂಚನೆಗಳನ್ನು ಓದಿ" to "read notifications",
            "ನನ್ನ ಇಮೇಲ್ ಓದು" to "read my latest email", "ನನ್ನ ಇಮೇಲ್ ಓದಿ" to "read my latest email",
            "ವಾಟ್ಸಾಪ್ ಓದು" to "read whatsapp", "ವಾಟ್ಸಾಪ್ ಓದಿ" to "read whatsapp",
            "ಮ್ಯಾಪ್ ತೆರೆ" to "open maps", "ಮ್ಯಾಪ್ಸ್ ತೆರೆ" to "open maps", "ನಕ್ಷೆ ತೆರೆಯಿರಿ" to "open maps",
            "ಕಾಲ್ ಕಟ್ ಮಾಡು" to "end call", "ಕರೆ ಕೊನೆಗೊಳಿಸಿ" to "end call",
            "ಸಮಯ ಹೇಳು" to "what time is it", "ಈಗ ಸಮಯ ಎಷ್ಟು" to "what time is it", "ಸಮಯ ಎಷ್ಟು" to "what time is it")
        exact[text]?.let { return it }
        fun match(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE).matchEntire(text)
        val polite = "(?:ದಯವಿಟ್ಟು |please )?"
        val me = "(?:ನನ್ನನ್ನು |ನನಗೆ |nannannu |nanage )?"
        val ending = "(?:ಕ್ಕೆ|ಗೆ| ge| kke)"
        match("${polite}${me}(.+?)$ending (?:ಕರೆದುಕೊಂಡು|ಕರ್ಕೊಂಡು|karedukondu|karedkondu|karkondu) (?:ಹೋಗು|ಹೋಗಿ|hogu|hogi)")
            ?.let { return "navigate to ${it.groupValues[1].trim()}" }
        match("${polite}(?:take me to|navigate to|directions to) (.+?)$ending")
            ?.let { return "navigate to ${it.groupValues[1].trim()}" }
        match("${polite}${me}(.+?)$ending (?:take me|navigate(?: maadu| maadi| ಮಾಡು| ಮಾಡಿ)?|directions (?:torisu|torisi|ತೋರಿಸು|ತೋರಿಸಿ))")
            ?.let { return "navigate to ${it.groupValues[1].trim()}" }
        match("${polite}(?:navigate|ನ್ಯಾವಿಗೇಟ್) (.+?)$ending (?:maadu|maadi|ಮಾಡು|ಮಾಡಿ)")
            ?.let { return "navigate to ${it.groupValues[1].trim()}" }
        match("(.+?)(?:$ending)? (?:call|phone|ಕಾಲ್|ಕರೆ) (?:maadu|madu|maadi|madi|ಮಾಡು|ಮಾಡಿ)")
            ?.let { return "call ${it.groupValues[1].trim()}" }
        match("(?:call|phone) (.+?)$ending")?.let { return "call ${it.groupValues[1].trim()}" }
        match("(.+?) (?:weather|ಹವಾಮಾನ) (?:helu|heli|ಹೇಳು|ಹೇಳಿ)")?.let { return "weather in ${it.groupValues[1]}" }
        match("(.+?) (?:search|ಸರ್ಚ್) (?:maadu|madu|maadi|madi|ಮಾಡು|ಮಾಡಿ)")?.let { return "search for ${it.groupValues[1]}" }
        match("(.+?) (?:huduku|huduki)")?.let { return "search for ${it.groupValues[1]}" }
        match("(?:ನನ್ನ |my |nanna )?(?:notifications?|ನೋಟಿಫಿಕೇಶನ್) (?:odu|odi|ಓದು|ಓದಿ)")?.let { return "read notifications" }
        match("(?:ನನ್ನ |my |nanna )?(?:email|ಇಮೇಲ್) (?:odu|odi|ಓದು|ಓದಿ)")?.let { return "read my latest email" }
        match("(?:maps?|ಮ್ಯಾಪ್ಸ್) (?:open maadu|open maadi|open ಮಾಡು|ತೆರೆ)")?.let { return "open maps" }
        // Kannada destination case endings may be attached or separated by STT.
        // Remove only the grammatical ending; retain the place name for Maps.
        match("(?:ದಯವಿಟ್ಟು )?(?:ನನ್ನನ್ನು |ನನಗೆ )?(.+?)(?:ಕ್ಕೆ|ಗೆ) (?:ಕರೆದುಕೊಂಡು|ಕರೆದೊಯ್ದು|ಕರ್ಕೊಂಡು|ಕೊಂಡು) (?:ಹೋಗು|ಹೋಗಿ|ಹೋಗಿರಿ|ಹೋಗುಪ್ಪ)")
            ?.let { return "navigate to ${it.groupValues[1].trim()}" }
        match("(?:ದಯವಿಟ್ಟು )?(?:ನನ್ನನ್ನು |ನನಗೆ )?(.+?)(?:ಕ್ಕೆ|ಗೆ) (?:ಕರೆದೊಯ್ಯು|ಕರೆದೊಯ್ಯಿ|ಕರೆದೊಯ್ಯಿರಿ)")
            ?.let { return "navigate to ${it.groupValues[1].trim()}" }
        match("(.+?)(?:ಕ್ಕೆ|ಗೆ) (?:ದಾರಿ|ಮಾರ್ಗ|ದಿಕ್ಕು|ದಾರಿಯನ್ನು|ಮಾರ್ಗವನ್ನು) (?:ತೋರಿಸು|ತೋರಿಸಿ|ಹೇಳು|ಹೇಳಿ)")
            ?.let { return "navigate to ${it.groupValues[1].trim()}" }
        match("(.+?)(?:ಕ್ಕೆ|ಗೆ) (?:ನ್ಯಾವಿಗೇಟ್|ನಾವಿಗೇಟ್|ನೆವಿಗೇಟ್) (?:ಮಾಡು|ಮಾಡಿ)")
            ?.let { return "navigate to ${it.groupValues[1].trim()}" }
        match("(.+?) (?:ಹವಾಮಾನ|ಹವಾಮಾನವನ್ನು) (?:ಹೇಳು|ಹೇಳಿ|ತಿಳಿಸಿ)")?.let { return "weather in ${it.groupValues[1]}" }
        match("(.+?) (?:ಗೆ|ಅವರಿಗೆ) (?:ಕಾಲ್|ಫೋನ್|ಕರೆ) (?:ಮಾಡು|ಮಾಡಿ)")?.let { return "call ${it.groupValues[1]}" }
        match("(.+?)(?:ಗೆ|ಅವರಿಗೆ) (?:ಕಾಲ್|ಫೋನ್|ಕರೆ) (?:ಮಾಡು|ಮಾಡಿ)")?.let { return "call ${it.groupValues[1]}" }
        match("(.+?) (?:ಹುಡುಕು|ಹುಡುಕಿ|ಹುಡುಕಿರಿ|ಸರ್ಚ್ ಮಾಡು|ಸರ್ಚ್ ಮಾಡಿ)")?.let { return "search for ${it.groupValues[1]}" }
        return raw
    }
}
