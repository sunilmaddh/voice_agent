package com.example.voice_assistant.benchmark

import com.example.voice_assistant.ai.*
import com.example.voice_assistant.native_bridge.contracts.*
import com.google.gson.Gson
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class CommandBenchmarkCase(val id: String, val category: String, val input: String,
    val expectedIntent: String, val expectedParameters: Map<String, Any>,
    val nowEpochMs: Long, val timeZoneId: String)

/** Calls the model directly, bypassing Tier 1, so deterministic success cannot inflate SLM accuracy. */
class CommandBenchmark(private val model: CommandModel) {
    suspend fun run(cases: List<CommandBenchmarkCase>, device: Map<String, Any?>): Map<String, Any?> {
        require(cases.isNotEmpty() && cases.size <= 100 && cases.map { it.id }.distinct().size == cases.size)
        if (model.diagnostics["ready"] != true) return mapOf("status" to "PENDING_DEVICE_EXECUTION",
            "reason" to "Load a compatible local model on the target Android device first.",
            "datasetSize" to cases.size, "rows" to emptyList<Any>(), "intentAccuracy" to null,
            "structuredOutputSuccessRate" to null, "parameterAccuracy" to null)
        val rows = mutableListOf<Map<String, Any?>>()
        val inferenceCountBeforeRun = model.diagnostics["inferenceAttemptsSinceLoad"]
        for (case in cases) {
            currentCoroutineContext().ensureActive()
            val start = System.nanoTime()
            val result = model.extract(case.input, CommandContext(case.nowEpochMs, case.timeZoneId))
            val intent = (result as? CapabilityResult.Available)?.value
            val error = (result as? CapabilityResult.Unavailable)?.code
            val correct = intent?.kind?.wireName == case.expectedIntent
            val parameters = intent?.parameters?.associate { it.name to it.value }
            val parameterCorrect = correct && parameters?.keys == case.expectedParameters.keys &&
                case.expectedParameters.all { (key, expected) -> equalParameter(expected, parameters[key]) }
            rows += mapOf("id" to case.id, "category" to case.category, "input" to case.input,
                "contextEpochMs" to case.nowEpochMs, "timeZoneId" to case.timeZoneId,
                "expectedIntent" to case.expectedIntent, "expectedParameters" to case.expectedParameters,
                "actualIntent" to intent?.kind?.wireName, "actualParameters" to parameters,
                "correctIntent" to correct, "incorrectIntent" to (intent != null && !correct),
                "parameterExtractionFailure" to ((correct && !parameterCorrect) || error == "INVALID_MODEL_PARAMETERS"),
                "parametersCorrect" to parameterCorrect, "structuredOutputSuccess" to (intent != null),
                "latencyMs" to (System.nanoTime() - start) / 1_000_000.0,
                "confidence" to intent?.confidence, "error" to error,
                "modelMetrics" to model.diagnostics)
        }
        fun count(key: String) = rows.count { it[key] == true }
        return mapOf("schemaVersion" to 1, "datasetVersion" to "command-v1", "status" to "MEASURED",
            "createdAtEpochMs" to System.currentTimeMillis(), "device" to device, "model" to model.diagnostics,
            "warmupPolicy" to "NO_AUTOMATIC_WARMUP_ENGINE_REUSED",
            "inferenceCountBeforeRun" to inferenceCountBeforeRun,
            "datasetSize" to cases.size, "completed" to rows.size,
            "correctIntent" to count("correctIntent"), "incorrectIntent" to count("incorrectIntent"),
            "parameterExtractionFailure" to count("parameterExtractionFailure"),
            "structuredOutputFailure" to rows.count { it["structuredOutputSuccess"] == false },
            "intentAccuracy" to count("correctIntent").toDouble() / rows.size,
            "parameterAccuracy" to count("parametersCorrect").toDouble() / rows.size,
            "structuredOutputSuccessRate" to count("structuredOutputSuccess").toDouble() / rows.size,
            "rows" to rows)
    }
    companion object {
        fun parseDataset(json: String): List<CommandBenchmarkCase> =
            Gson().fromJson(json, Array<CommandBenchmarkCase>::class.java).toList()
        private fun equalParameter(expected: Any, actual: Any?): Boolean = when {
            expected is Number && actual is Number -> expected.toDouble() == actual.toDouble()
            expected is String && actual is String -> expected.trim().replace(Regex("\\s+"), " ")
                .equals(actual.trim().replace(Regex("\\s+"), " "), ignoreCase = true)
            else -> expected == actual
        }
    }
}
