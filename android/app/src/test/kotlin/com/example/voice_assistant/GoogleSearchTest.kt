package com.example.voice_assistant

import com.example.voice_assistant.actions.*
import com.example.voice_assistant.intent.*
import com.example.voice_assistant.native_bridge.contracts.*
import com.example.voice_assistant.voice.SpeechCoordinator
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GoogleSearchTest {
    private class Launcher : SearchLaunchPort {
        var appAvailable = true
        var browserAvailable = true
        var restriction: ActionResult? = null
        var denied = false
        val queries = mutableListOf<String>()
        val urls = mutableListOf<String>()
        override fun restriction() = restriction
        override fun openGoogleApp(query: String): Boolean {
            queries.add(query)
            if (denied) throw SecurityException()
            return appAvailable
        }
        override fun openBrowser(url: String): Boolean { urls.add(url); return browserAvailable }
    }
    private fun action(query: String) = AssistantAction("GOOGLE_SEARCH", TypedCommand.GoogleSearch(query).toIntent(.98))

    @Test fun examplesRouteWithoutModelAndPreserveQuery() {
        for ((input, query) in mapOf(
            "Search Google for today's weather in Bangalore." to "today's weather in Bangalore",
            "Search for Nifty 50 live price." to "Nifty 50 live price",
            "Search for the best restaurants near MG Road." to "the best restaurants near MG Road")) {
            val decision = LocalIntentRouter().decide(input)
            assertEquals(RoutingTier.deterministic, decision.tier)
            assertFalse(decision.modelRequired)
            assertEquals(query, (decision.command as TypedCommand.GoogleSearch).query)
            assertNotNull(decision.proposedAction)
        }
        for (input in listOf("search for", "don't search for restaurants", "search for weather and open maps")) {
            assertNull(LocalIntentRouter().decide(input).proposedAction)
        }
    }
    @Test fun googleAppIsPreferred() = runBlocking {
        val launcher = Launcher()
        val result = GoogleSearchActionExecutor(launcher).execute(action("weather"))
        assertEquals("GOOGLE_SEARCH_APP_LAUNCH_REQUESTED", result.code)
        assertEquals(listOf("weather"), launcher.queries)
        assertTrue(launcher.urls.isEmpty())
    }
    @Test fun browserFallbackEncodesQueryAsData() = runBlocking {
        val launcher = Launcher().apply { appAvailable = false }
        val query = "Bangalore cafés & q=override # + ?"
        val result = GoogleSearchActionExecutor(launcher).execute(action(query))
        assertEquals("GOOGLE_SEARCH_BROWSER_LAUNCH_REQUESTED", result.code)
        val uri = URI(launcher.urls.single())
        assertEquals("https", uri.scheme); assertEquals("www.google.com", uri.host)
        assertNull(uri.fragment)
        assertEquals(query, URLDecoder.decode(uri.rawQuery.removePrefix("q="), "UTF-8"))
        assertFalse(uri.rawQuery.contains("&"))
    }
    @Test fun unavailableAndDeniedLaunchesReportFailure() = runBlocking {
        val launcher = Launcher().apply { appAvailable = false; browserAvailable = false }
        assertEquals(ActionStatus.unsupported, GoogleSearchActionExecutor(launcher).execute(action("weather")).status)
        launcher.denied = true
        assertEquals("GOOGLE_SEARCH_LAUNCH_DENIED", GoogleSearchActionExecutor(launcher).execute(action("weather")).code)
    }
    @Test fun invalidActionsAndLockedDeviceDoNotLaunch() = runBlocking {
        val launcher = Launcher()
        val executor = GoogleSearchActionExecutor(launcher)
        for (candidate in listOf(action(""), action("weather").copy(name = "NAVIGATE"),
            AssistantAction("GOOGLE_SEARCH", TypedCommand.GoogleSearch("weather").toIntent(.2)),
            AssistantAction("GOOGLE_SEARCH", TypedCommand.Navigate("airport").toIntent(.98)))) {
            assertEquals("INVALID_SEARCH_ACTION", executor.execute(candidate).code)
        }
        launcher.restriction = ActionResult(ActionStatus.requiresConfirmation, "UNLOCK_REQUIRED", "Unlock phone.")
        assertEquals("UNLOCK_REQUIRED", executor.execute(action("weather")).code)
        assertTrue(launcher.queries.isEmpty()); assertTrue(launcher.urls.isEmpty())
    }
    @Test fun voiceSearchSpeaksRetrievedAnswerAfterExecution() = runBlocking {
        val tts = SpeechCoordinatorTest.Tts(); val focus = SpeechCoordinatorTest.Focus()
        var calls = 0
        val executor = object : ActionExecutor {
            override suspend fun execute(action: AssistantAction): ActionResult {
                calls++
                assertNull(tts.spoken)

                assertEquals("GOOGLE_SEARCH", action.name)
                assertEquals("today's weather in Bangalore", action.intent.parameters.single().value)
                return ActionResult(ActionStatus.succeeded, "WEB_ANSWER_READY", "Retrieved factual answer.")
            }
        }
        val result = SpeechCoordinator(SpeechCoordinatorTest.Stt().apply {
            transcript = "Search Google for today's weather in Bangalore."
        }, tts, focus, executor = executor) {}.run(VoiceRequest("req-1-1"), false)
        assertEquals(1, calls); assertEquals(true, result.routing["actionExecuted"])
        assertEquals("Retrieved factual answer.", tts.spoken)
        assertTrue(focus.released)
        assertNull(result.selectedModel)
    }
}

