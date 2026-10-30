package com.example.voice_assistant.actions

import com.example.voice_assistant.intent.CommandValidator
import com.example.voice_assistant.native_bridge.contracts.*
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

data class WebEvidence(val text: String, val url: String, val directAnswer: Boolean = false,
    val provider: String = "DUCKDUCKGO_INSTANT_ANSWERS")

/** Public information only; network text never becomes an assistant command. */
class WebAnswerExecutor(
    private val search: suspend (String) -> List<WebEvidence>,
    private val select: suspend (String, List<WebEvidence>) -> Int?,
    private val restriction: () -> ActionResult? = { null }
) : ActionExecutor {
    override suspend fun execute(action: AssistantAction): ActionResult {
        if (action.name != "GOOGLE_SEARCH" || action.intent.kind != IntentKind.googleSearch ||
            CommandValidator().validate(action.intent) !is CapabilityResult.Available)
            return ActionResult(ActionStatus.failed, "INVALID_SEARCH_ACTION", "Please provide a clear search query.")
        restriction()?.let { return it }
        val query = action.intent.parameters.single().value as String
        // Only public acceptance-case labels; never persist arbitrary spoken queries.
        val testCase = when (query.trim().trimEnd('.', '?', '!').lowercase(java.util.Locale.ROOT)) {
            "what is kotlin", "kotlin" -> "KOTLIN"
            "mount everest", "what is mount everest" -> "EVEREST"
            "weather in bangalore" -> "BANGALORE_WEATHER"
            "weather in bengaluru" -> "BENGALURU_WEATHER"
            else -> "OTHER"
        }
        var evidenceCount: Int? = null
        val result = try {
            withTimeout(60000) {
                val evidence = search(query).take(3).filter { it.text.isNotBlank() &&
                    it.text.length <= 1600 && runCatching { URI(it.url).scheme == "https" && URI(it.url).host != null }.getOrDefault(false) }
                    .map { it.copy(text = it.text.take(800)) }
                evidenceCount = evidence.size
                if (evidence.isEmpty()) return@withTimeout ActionResult(ActionStatus.unsupported,
                    "WEB_NO_ANSWER", if (WeatherSearch.cityFrom(query) != null)
                        "I couldn't get weather for that request. Say current weather in a city, such as weather in Bangalore. Forecasts for other dates aren't supported yet."
                    else "I couldn't find an instant answer. Try a specific factual question. This search doesn't cover all websites or live news.")
                val directSelection = evidence.indexOfFirst { it.directAnswer }.takeIf { it >= 0 }
                val modelSelection = if (directSelection != null) null
                    else select(query, evidence)?.takeIf { it in evidence.indices }
                // Only the provider's direct abstract may bypass model selection; related topics
                // can describe unrelated subjects and must never be picked arbitrarily.
                val selected = directSelection ?: modelSelection
                if (selected == null) return@withTimeout ActionResult(
                    ActionStatus.unsupported, "WEB_MODEL_UNAVAILABLE", "Search found related topics, but no direct answer. Try a more specific question or load the local model in Aiko's model settings.")
                val answer = evidence[selected]
                ActionResult(ActionStatus.succeeded, "WEB_ANSWER_READY", "According to ${URI(answer.url).host}, ${spokenSummary(answer.text)}",
                    mapOf("sourceUrl" to answer.url, "searchProvider" to answer.provider,
                        "answerMethod" to if (modelSelection != null) "LOCAL_SLM_EXTRACTIVE_SELECTION" else "PROVIDER_DIRECT_ANSWER",
                        "modelSelectionSucceeded" to (modelSelection != null), "networkRequired" to true))
            }
        } catch (_: TimeoutCancellationException) {
            ActionResult(ActionStatus.failed, "WEB_SEARCH_TIMEOUT", "The web search timed out. Try again.")
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { ActionResult(ActionStatus.failed, "WEB_SEARCH_FAILED", "I couldn't reach web search. Check your internet connection and try again.") }
        return result.copy(evidence = result.evidence + mapOf("webTestCase" to testCase,
            "webEvidenceCount" to evidenceCount))
    }
}

/** Short extractive summary: complete sentences, with no generated factual additions. */
fun spokenSummary(text: String): String {
    val sentences = Regex("(?<=[.!?])\\s+(?=[A-Z0-9])").split(text.trim())
    val selected = mutableListOf<String>()
    for (sentence in sentences) {
        if (selected.size >= 3 || selected.sumOf { it.length + 1 } + sentence.length > 600) break
        selected.add(sentence)
    }
    if (selected.isNotEmpty()) return selected.joinToString(" ")
    return text.take(600).substringBeforeLast(' ', text.take(600)) + if (text.length > 600) "..." else ""
}

/** Bounded HTTPS request. This provider offers instant answers, not a full web index. */
object InstantAnswerSearch {
    suspend fun search(query: String): List<WebEvidence> = withContext(Dispatchers.IO) {
        val connection = URI("https://api.duckduckgo.com/?format=json&no_html=1&skip_disambig=1&q=" +
            URLEncoder.encode(query, "UTF-8")).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000; connection.readTimeout = 8000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "AikoAssistant/1.0")
            check(connection.responseCode == 200)
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 262144)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            check(bytes.size <= 262144)
            currentCoroutineContext().ensureActive()
            val json = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
            val results = mutableListOf<WebEvidence>()
            fun add(text: String, url: String, direct: Boolean = false) {
                if (text.isNotBlank()) results.add(WebEvidence(text.take(1600), url, direct))
            }
            add(json.get("AbstractText")?.asString.orEmpty(), json.get("AbstractURL")?.asString.orEmpty(), true)
            json.getAsJsonArray("RelatedTopics")?.take(5)?.forEach { item ->
                val topic = item.asJsonObject
                add(topic.get("Text")?.asString.orEmpty(), topic.get("FirstURL")?.asString.orEmpty())
            }
            results
        } finally { connection.disconnect() }
    }
}
