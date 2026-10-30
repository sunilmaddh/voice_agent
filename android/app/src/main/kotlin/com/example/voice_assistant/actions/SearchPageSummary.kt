package com.example.voice_assistant.actions

/** Extract only displayed result text; never infer a quote or treat page text as commands. */
object SearchPageSummary {
    fun summarize(text: String): String {
        val clean = text.replace(Regex("[\\t ]+"), " ").trim()
        if (clean.length < 40 || Regex("before you continue|unusual traffic|verify you are human|not a robot|access denied", RegexOption.IGNORE_CASE).containsMatchIn(clean))
            return "I couldn't read search results from this page. Try opening the results in your browser."
        val lines = clean.lines().map(String::trim).filter { it.length >= 25 }
        if (lines.isEmpty()) return "There isn't enough readable result text to summarize."
        val excerpt = spokenSummary(lines.distinct().joinToString(". ").take(4000))
        return "From the visible Google search results: $excerpt. Search results may be delayed; this is not a verified live market quote."
    }
}
