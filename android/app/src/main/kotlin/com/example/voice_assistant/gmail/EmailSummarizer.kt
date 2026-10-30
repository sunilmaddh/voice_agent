package com.example.voice_assistant.gmail

interface EmailSummarizer { fun summarize(body: String): String }

/** Lightweight extractive summary; never claims to be a generative model. */
class ExtractiveEmailSummarizer : EmailSummarizer {
    override fun summarize(body: String): String {
        val sentences = body.split(Regex("(?<=[.!?])\\s+|\\n+")).map(String::trim)
            .filter { it.length >= 15 && !it.startsWith(">") && !it.contains("unsubscribe", true) }.take(100)
        if (sentences.isEmpty()) return "Not enough readable text for an extractive summary."
        val stop = setOf("this", "that", "with", "from", "have", "your", "will", "please", "there", "would", "about")
        fun terms(s: String) = Regex("[a-z]{4,}").findAll(s.lowercase()).map { it.value }.filter { it !in stop }.toSet()
        val frequencies = sentences.flatMap { terms(it) }.groupingBy { it }.eachCount()
        val chosen = sentences.indices.sortedByDescending { i ->
            terms(sentences[i]).sumOf { frequencies[it] ?: 0 }.toDouble() / maxOf(1, terms(sentences[i]).size)
        }.take(3).sorted()
        return "Extractive summary, selected sentences: " + chosen.joinToString(" ") { sentences[it].take(500) }
    }
}
