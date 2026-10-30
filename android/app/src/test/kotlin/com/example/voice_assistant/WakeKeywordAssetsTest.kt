package com.example.voice_assistant

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class WakeKeywordAssetsTest {
    @Test fun pronunciationVariantsUseValidTokensAndRequireHey() {
        val vocabulary = File("src/main/assets/wake_word/tokens.txt").readLines()
            .map { it.substringBeforeLast(' ') }.toSet()
        val phrases = File("src/main/assets/wake_word/keywords.txt").readLines()
            .filter { it.isNotBlank() }.map { it.trim().split(Regex("\\s+")) }
        assertEquals(phrases.size, phrases.distinct().size)
        assertEquals(setOf("@HEY_INDO", "@HEY_AIKO"), phrases.map { it.last() }.toSet())
        for (phrase in phrases) {
            assertEquals(listOf("HH", "EY1"), phrase.take(2))
            phrase.dropLast(1).forEach { assertTrue("Unknown phonetic token: $it", it in vocabulary) }
        }
        val indo = phrases.filter { it.last() == "@HEY_INDO" }
        assertTrue(indo.any { it[2] == "IH1" })
        assertTrue(indo.any { it[2] == "IY1" })
        val aiko = phrases.filter { it.last() == "@HEY_AIKO" }
        assertTrue(aiko.any { it[2] == "AY1" })
        assertTrue(aiko.any { it[2] == "EY1" })
    }
}
