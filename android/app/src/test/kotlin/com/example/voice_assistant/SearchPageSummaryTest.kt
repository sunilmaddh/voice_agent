package com.example.voice_assistant

import com.example.voice_assistant.actions.SearchPageSummary
import org.junit.Assert.*
import org.junit.Test

class SearchPageSummaryTest {
    @Test fun preservesDisplayedNumbersAndDisclosesDelay() {
        val report = SearchPageSummary.summarize("Nifty 50 index is 25,123.45, up 0.4 percent.\nQuote timestamp: 13 October, 10:30 IST.")
        assertTrue(report.contains("25,123.45"))
        assertTrue(report.contains("13 October"))
        assertTrue(report.contains("not a verified live market quote"))
    }
    @Test fun refusesBlockedOrEmptyResults() {
        for (text in listOf("", "Before you continue, please accept cookies to use Google search.",
            "Our systems have detected unusual traffic from your computer network.")) {
            assertTrue(SearchPageSummary.summarize(text).contains("couldn't read"))
        }
    }
}
