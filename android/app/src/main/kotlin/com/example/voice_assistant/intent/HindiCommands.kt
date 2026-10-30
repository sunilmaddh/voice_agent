package com.example.voice_assistant.intent

/** Translate only complete command templates. Preserve payloads and reject negation first. */
object HindiCommands {
    fun normalize(raw: String): String {
        val text = raw.trim().trimEnd('।', '.', '?', '!').replace(Regex("\\s+"), " ")
        if (Regex("(?:मत|नहीं|नही|अगर|शायद)|\\b(?:mat|nahi|nahin|agar|shayad)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text))
            return "do not execute"
        val exact = mapOf("नोटिफिकेशन पढ़ो" to "read notifications", "मेरे नोटिफिकेशन पढ़ो" to "read notifications",
            "मेरी ईमेल पढ़ो" to "read my latest email", "मेरा ईमेल पढ़ो" to "read my latest email",
            "व्हाट्सएप पढ़ो" to "read whatsapp", "मैप खोलो" to "open maps", "मैप्स खोलो" to "open maps",
            "कॉल काटो" to "end call", "समय बताओ" to "what time is it",
            "क्या समय हुआ है" to "what time is it", "कितने बजे हैं" to "what time is it",
            "notification padho" to "read notifications", "notifications padho" to "read notifications")
        exact[text.lowercase()]?.let { return it }
        fun match(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE).matchEntire(text)
        match("(.+?) का मौसम (?:बताओ|बताइए)")?.let { return "weather in ${it.groupValues[1]}" }
        match("(.+?) (?:ka mausam|ka weather) (?:batao|bataiye)")?.let { return "weather in ${it.groupValues[1]}" }
        match("(.+?) को (?:कॉल|फोन) (?:करो|करिए|लगाओ)")?.let { return "call ${it.groupValues[1]}" }
        match("(.+?) ko (?:call|phone) (?:karo|lagao)")?.let { return "call ${it.groupValues[1]}" }
        match("(.+?) (?:खोजो|सर्च करो)")?.let { return "search for ${it.groupValues[1]}" }
        match("(.+?) (?:search karo|khojo)")?.let { return "search for ${it.groupValues[1]}" }
        return raw
    }
}
