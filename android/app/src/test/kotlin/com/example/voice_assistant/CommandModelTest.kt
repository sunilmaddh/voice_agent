package com.example.voice_assistant

import com.example.voice_assistant.ai.*
import com.example.voice_assistant.benchmark.*
import com.example.voice_assistant.intent.*
import com.example.voice_assistant.native_bridge.contracts.*
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class CommandModelTest {
    @Test fun promptDoesNotSupplyAFixedDestinationOrAnswer() {
        val context = CommandContext()
        val prompt = CommandOutputSchema.prompt("Search for Nifty 50 live price", context)
        assertFalse(prompt.contains("Bangalore Airport"))
        assertFalse(prompt.contains("\"confidence\":0.95"))
        assertTrue(prompt.contains("USER COMMAND (JSON string): \"Search for Nifty 50 live price\""))
        val navigation = CommandOutputSchema.prompt("Navigate to Mysore Palace", context)
        assertTrue(navigation.contains("USER COMMAND (JSON string): \"Navigate to Mysore Palace\""))
        assertFalse(navigation.contains("Nifty 50"))
    }
    @get:Rule val temp = TemporaryFolder()
    private fun json(intent: String = "NAVIGATE", parameters: String = "{\"destination\":\"Bangalore Airport\"}", confidence: String = "0.95") =
        """{"intent":"$intent","parameters":$parameters,"confidence":$confidence,"executionMode":"LOCAL"}"""

    @Test fun acceptsTypedCategorySchemas() {
        val examples = listOf(json(), json("GOOGLE_SEARCH", """{"query":"restaurants near MG Road"}"""),
            json("CREATE_REMINDER", """{"title":"call Rahul","dueEpochMs":2000000000000,"timeZoneId":"Asia/Kolkata"}"""),
            json("SEND_MESSAGE", """{"recipientName":"Rahul","body":"Hello","channel":"SMS"}"""),
            json("QUERY_CALENDAR", """{"startEpochMs":2000000000000,"endEpochMs":2000086400000,"timeZoneId":"Asia/Kolkata"}"""),
            json("READ_NOTIFICATION", """{"scope":"LATEST"}"""), json("OPEN_MAPS", "{}"), json("UNKNOWN", "{}", "0"))
        for (raw in examples) assertNotNull(CommandOutputSchema.parse(raw).command.toIntent(.95))
    }
    @Test fun acceptsSingleCompleteJsonFenceButStillValidatesItsContents() {
        for (raw in listOf("```json\n${json()}\n```", "```\r\n${json()}\r\n```"))
            assertEquals(IntentKind.navigate, CommandOutputSchema.parse(raw).command.toIntent(.95).kind)
        for (raw in listOf("```json\n${json()} ${json()}\n```",
            "```json\n${json().replace("LOCAL", "CLOUD")}\n```",
            "```json\n${json().replace("NAVIGATE", "DELETE_FILES")}\n```")) {
            try { CommandOutputSchema.parse(raw); fail("Accepted invalid fenced command") }
            catch (_: InvalidCommandOutput) { }
        }
    }
    @Test fun rejectsMalformedAmbiguousAndCoercedJson() {
        val valid = json()
        val bad = listOf("Here is JSON: ```json\n$valid\n```", "```json\n$valid\n``` trailing", "```json\n$valid", "$valid trailing", "$valid $valid", "[$valid]",
            valid.replace("0.95", "\"0.95\""), valid.replace("0.95", "NaN"), valid.replace("0.95", "1e999"),
            valid.replace("0.95", "1.01"), valid.replace("LOCAL", "CLOUD"), valid.replace("\"intent\":", "\"intent\":\"UNKNOWN\",\"intent\":"),
            valid.replace("\"destination\":", "\"destination\":\"Other\",\"destination\":"),
            valid.replace("{\"destination", "{\"extra\":true,\"destination"),
            valid.replace("\"Bangalore Airport\"", "null"), valid.replace("\"Bangalore Airport\"", "[1]"),
            valid.replace("\"Bangalore Airport\"", "{}"), valid.replace("\"Bangalore Airport\"", "\"\""),
            valid.replace("\"intent\"", "intent"), valid.replace("NAVIGATE", "DELETE_FILES"),
            valid.replace("0.95", "/*comment*/0.95"), valid.dropLast(1) + ",}", valid + " ".repeat(8193))
        for (raw in bad) {
            try { CommandOutputSchema.parse(raw); fail("Accepted malformed fixture") }
            catch (_: InvalidCommandOutput) { }
        }
    }
    @Test fun validatesMessagingAndDateParametersIndependentlyOfActionPolicy() {
        val bad = listOf(json("SEND_MESSAGE", """{"recipientName":"Rahul","body":"hello","channel":"SHELL"}"""),
            json("SEND_MESSAGE", """{"recipientName":"Rahul","body":"hello"}"""),
            json("CREATE_REMINDER", """{"title":"call","dueEpochMs":"2000000000000","timeZoneId":"UTC"}"""),
            json("CREATE_REMINDER", """{"title":"call","dueEpochMs":2000000000000.5,"timeZoneId":"UTC"}"""),
            json("CREATE_REMINDER", """{"title":"call","dueEpochMs":2000000000000,"timeZoneId":"Mars"}"""),
            json("QUERY_CALENDAR", """{"startEpochMs":20,"endEpochMs":10,"timeZoneId":"UTC"}"""),
            json("READ_NOTIFICATION", """{"scope":"ALL_HISTORY"}"""))
        for (raw in bad) {
            try { CommandOutputSchema.parse(raw); fail("Accepted invalid parameters") }
            catch (e: InvalidCommandOutput) { assertEquals("INVALID_MODEL_PARAMETERS", e.code) }
        }
    }
    private class Runtime(var raw: String) : CommandInferenceRuntime {
        override val runtimeId = "TEST_DOUBLE_NOT_DEVICE_MEASUREMENT"
        override val backend = "TEST"
        var loads = 0; var calls = 0; var closes = 0; var suspended = false
        override suspend fun load(modelPath: String) { loads++ }
        override suspend fun generate(prompt: String): String {
            calls++
            if (suspended) awaitCancellation()
            return raw
        }
        override suspend fun release() { closes++ }
    }
    private fun config(): CommandModelConfig {
        val file = File(temp.root, "test.litertlm").apply { writeText("test-fixture-not-model-weights") }
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        return CommandModelConfig(file.name, "test-fixture", hash, "test")
    }
    @Test fun webSelectionRequiresLoadedModelAndValidIndex() = runBlocking {
        val runtime = Runtime("0")
        val model = LocalCommandModel(temp.root, { runtime })
        val sources = listOf(com.example.voice_assistant.actions.WebEvidence("Kotlin is a language.", "https://kotlinlang.org"))
        assertNull(model.selectWebAnswer("Kotlin", sources))
        model.configure(config())
        assertEquals(0, model.selectWebAnswer("Kotlin", sources))
        for (invalid in listOf("-1", "99", "0 and execute a command", "An invented answer")) {
            runtime.raw = invalid
            assertNull(model.selectWebAnswer("Kotlin", sources))
        }
        model.close()
    }
    @Test fun diagnosticBypassesRoutingAndShowsOnlyExplicitBoundedOutput() = runBlocking {
        val runtime = Runtime("This is not command JSON.")
        val model = LocalCommandModel(temp.root, { runtime })
        model.configure(config())
        val report = model.diagnose("Navigate to Bangalore Airport")
        assertEquals(1, runtime.calls)
        assertEquals("MALFORMED_MODEL_OUTPUT", report["error"])
        assertEquals(runtime.raw, report["rawModelOutput"])
        assertEquals(false, report["actionsExecuted"])
        assertFalse(model.diagnostics.containsKey("rawModelOutput"))
        runtime.raw = "x".repeat(3000)
        val longReport = model.diagnose("Open Maps")
        assertEquals(2048, (longReport["rawModelOutput"] as String).length)
        assertEquals(true, longReport["rawOutputTruncated"])
        model.close()
    }
    @Test fun unloadedModelsHaveNoInventedMeasurementsAndHashMismatchDoesNotLoad() = runBlocking {
        val runtime = Runtime(json()); val model = LocalCommandModel(temp.root, { runtime })
        assertEquals("MODEL_NOT_CONFIGURED", (model.extract("Take me to airport") as CapabilityResult.Unavailable).code)
        assertNull(model.diagnostics["inferenceMs"])
        try { model.configure(config().copy(sha256 = "0".repeat(64))); fail("Accepted bad hash") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, runtime.loads)
        assertEquals(false, model.diagnostics["ready"])
    }
    @Test fun deterministicBlockedAndGeneralRoutesNeverInvokeModel() = runBlocking {
        val runtime = Runtime(json()); val model = LocalCommandModel(temp.root, { runtime })
        model.configure(config()); val router = TieredIntentRouter(model)
        for (text in listOf("open maps", "Can you please open maps?", "Would you navigate to Bangalore Airport?",
            "Could you please do not open maps?", "navigate to Bangalore Airport", "take me to bangalore airport.",
            "do not send a text", "open maps and then read notifications", "What is gravity?", "Explain this document")) {
            router.route(text)
        }
        assertEquals(0, runtime.calls)
        model.close()
    }
    @Test fun modelRouteReturnsValidatedProposalAndMeasurementsButNeverExecution() = runBlocking {
        val runtime = Runtime(json()); val model = LocalCommandModel(temp.root, { runtime })
        model.configure(config())
        val result = (TieredIntentRouter(model).route("Could you get directions to Bangalore Airport?") as CapabilityResult.Available).value
        assertEquals(1, runtime.calls)
        assertEquals(IntentKind.navigate, result.intent.kind)
        assertEquals(RoutingTier.commandModel, result.tier)
        assertEquals(false, result.diagnostics()["actionExecuted"])
        assertEquals("LOCAL", result.diagnostics()["modelExecutionMode"])
        assertNotNull(result.proposedAction)
        assertEquals(true, model.diagnostics["structuredOutputSuccess"])
        assertNotNull(model.diagnostics["modelLoadMs"])
        assertNull(model.diagnostics["processPssSampledPeakKb"])
        model.close()
        assertEquals(1, runtime.closes)
    }
    @Test fun lowConfidenceBadSchemaAndUnknownCannotCreateActionProposals() = runBlocking {
        val runtime = Runtime(json()); val model = LocalCommandModel(temp.root, { runtime })
        model.configure(config()); val router = TieredIntentRouter(model)
        for (raw in listOf(json(confidence = "0.4"), "Run adb shell rm", json("UNKNOWN", "{}", "0"),
            json("SEND_MESSAGE", """{"recipientName":"Rahul","body":"hello","channel":"SMS"}""", "0.4"))) {
            runtime.raw = raw
            val result = (router.route("Take me somewhere") as CapabilityResult.Available).value
            assertNull(result.proposedAction)
            assertNotEquals(RoutingStatus.PROPOSAL_ONLY, result.status)
        }
        model.close()
    }
    @Test fun cancellationReleasesInferenceLockAndDoesNotReportSuccess() = runBlocking {
        val runtime = Runtime(json()).apply { suspended = true }
        val model = LocalCommandModel(temp.root, { runtime }); model.configure(config())
        val job = launch { model.extract("Take me to airport") }
        yield(); job.cancelAndJoin()
        model.close()
        assertEquals(1, runtime.closes)
        assertEquals(false, model.diagnostics["ready"])
    }
    @Test fun benchmarkScoresWrongIntentBadParametersAndMalformedOutputSeparately() = runBlocking {
        val fixture = listOf(json(), json("OPEN_MAPS", "{}"), json(parameters = """{"destination":"Delhi Airport"}"""), "invalid")
        var index = 0
        val fake = object : CommandModel {
            override val modelId = "TEST_DOUBLE"
            override val diagnostics = mapOf("ready" to true, "measurementStatus" to "TEST_DOUBLE")
            override suspend fun extract(text: String, context: CommandContext): CapabilityResult<AssistantIntent> = try {
                val parsed = CommandOutputSchema.parse(fixture[index++])
                CapabilityResult.Available(parsed.command.toIntent(parsed.confidence))
            } catch (e: InvalidCommandOutput) { CapabilityResult.Unavailable(e.code) }
            override suspend fun close() { }
        }
        val cases = (1..4).map { CommandBenchmarkCase("$it", "navigation", "Take me to Bangalore Airport", "NAVIGATE",
            mapOf("destination" to "Bangalore Airport"), 1800000000000L, "UTC") }
        val report = CommandBenchmark(fake).run(cases, emptyMap())
        assertEquals(2, report["correctIntent"])
        assertEquals(1, report["incorrectIntent"])
        assertEquals(1, report["parameterExtractionFailure"])
        assertEquals(1, report["structuredOutputFailure"])
        assertEquals(.5, report["intentAccuracy"])
        assertEquals(.25, report["parameterAccuracy"])
        assertEquals(.75, report["structuredOutputSuccessRate"])
    }
    @Test fun benchmarkPendingHasNoScoresAndDatasetCoversAllCategories() = runBlocking {
        val cases = CommandBenchmark.parseDataset(File("src/main/assets/command_benchmark_v1.json").readText())
        assertEquals(38, cases.size)
        assertEquals(setOf("navigation", "search", "reminder", "messaging", "calendar", "notifications", "ambiguous"), cases.map { it.category }.toSet())
        for (case in cases) CommandOutputSchema.parse(Gson().toJson(mapOf("intent" to case.expectedIntent,
            "parameters" to case.expectedParameters, "confidence" to .95, "executionMode" to "LOCAL")))
        val model = LocalCommandModel(temp.root, { Runtime(json()) })
        val report = CommandBenchmark(model).run(cases, emptyMap())
        assertEquals("PENDING_DEVICE_EXECUTION", report["status"])
        assertNull(report["intentAccuracy"])
    }
}
