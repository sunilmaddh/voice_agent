package com.example.voice_assistant

import com.example.voice_assistant.actions.*
import com.example.voice_assistant.intent.TypedCommand
import com.example.voice_assistant.lockscreen.SessionAccessPolicy
import com.example.voice_assistant.native_bridge.contracts.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WebAnswerTest {
    private fun action(): AssistantAction {
        val intent = TypedCommand.GoogleSearch("what is Kotlin").toIntent(1.0)
        return AssistantAction("GOOGLE_SEARCH", intent)
    }
    @Test fun publicSearchWorksWithoutPersonalReadPermission() {
        assertFalse(SessionAccessPolicy.requiresUnlock(IntentKind.googleSearch, true, false))
        assertTrue(SessionAccessPolicy.requiresUnlock(IntentKind.readEmail, true, false))
    }
    @Test fun speaksOnlySelectedEvidenceWithSource() = runBlocking {
        val result = WebAnswerExecutor({ listOf(WebEvidence("Kotlin is a programming language.", "https://kotlinlang.org/")) },
            { _, _ -> 0 }).execute(action())
        assertEquals("WEB_ANSWER_READY", result.code)
        assertTrue(result.message.contains("kotlinlang.org"))
        assertEquals("https://kotlinlang.org/", result.evidence["sourceUrl"])
    }
    @Test fun noResultsNeverCallsModel() = runBlocking {
        val result = WebAnswerExecutor({ emptyList() }, { _, _ -> error("Must not infer without evidence") }).execute(action())
        assertEquals("WEB_NO_ANSWER", result.code)
        assertEquals("KOTLIN", result.evidence["webTestCase"])
        assertEquals(0, result.evidence["webEvidenceCount"])
    }
    @Test fun rejectsInvalidModelSelection() = runBlocking {
        val result = WebAnswerExecutor({ listOf(WebEvidence("Evidence", "https://example.com")) },
            { _, _ -> 99 }).execute(action())
        assertEquals("WEB_MODEL_UNAVAILABLE", result.code)
    }
    @Test fun directAnswerWorksWithoutModel() = runBlocking {
        val result = WebAnswerExecutor({ listOf(WebEvidence("Mount Everest is Earth's highest mountain.",
            "https://en.wikipedia.org/wiki/Mount_Everest", true)) }, { _, _ -> error("Direct answer must bypass model") }).execute(action())
        assertEquals("WEB_ANSWER_READY", result.code)
        assertTrue(result.message.contains("highest mountain"))
        assertEquals("PROVIDER_DIRECT_ANSWER", result.evidence["answerMethod"])
        assertEquals(false, result.evidence["modelSelectionSucceeded"])
    }
    @Test fun cancellationPropagates() = runBlocking {
        try {
            WebAnswerExecutor({ throw CancellationException() }, { _, _ -> 0 }).execute(action())
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
    }
}
