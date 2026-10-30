package com.example.voice_assistant.native_bridge.contracts

enum class ExecutionMode { local, cloud, notRun }
enum class RoutingTier { deterministic, commandModel, generalModel, notRun }
enum class ActionStatus { succeeded, unsupported, failed, requiresConfirmation }
enum class ParameterType { text, number, boolean }
enum class IntentKind(val wireName: String) {
    replyToCall("REPLY_TO_CALL"),
    drivingModeOn("DRIVING_MODE_ON"), drivingModeOff("DRIVING_MODE_OFF"),
    readSms("READ_SMS"), replySms("REPLY_SMS"),
    endCall("END_CALL"), callPhone("CALL_PHONE"), navigate("NAVIGATE"), googleSearch("GOOGLE_SEARCH"), createReminder("CREATE_REMINDER"),
    sendMessage("SEND_MESSAGE"), queryCalendar("QUERY_CALENDAR"), createCalendarEvent("CREATE_CALENDAR_EVENT"), openMaps("OPEN_MAPS"),
    openCalendar("OPEN_CALENDAR"), stopNavigation("STOP_NAVIGATION"), readNotification("READ_NOTIFICATION"),
    readWhatsApp("READ_WHATSAPP"), replyWhatsApp("REPLY_WHATSAPP"), readEmail("READ_EMAIL"),
    replyEmail("REPLY_EMAIL"), documentQuery("DOCUMENT_QUERY"), generalQuery("GENERAL_QUERY"), unknown("UNKNOWN")
}
data class VoiceRequest(val requestId: String, val input: String? = null, val options: SpeechOptions = SpeechOptions()) {
    init { require(Regex("^req-[0-9]+-[0-9]+$").matches(requestId) && requestId.length <= 80) }
    companion object {
        fun fromWire(raw: Any?): VoiceRequest {
            val map = raw as? Map<*, *> ?: throw IllegalArgumentException()
            require(map["schemaVersion"] == 1)
            require(map.keys.all { it in setOf("schemaVersion", "requestId", "input", "options") })
            val input = map["input"]
            require(input == null || input is String && input.length <= 4096)
            return VoiceRequest(map["requestId"] as? String ?: throw IllegalArgumentException(), input, SpeechOptions.fromWire(map["options"]))
        }
    }
}
data class SpeechRecognitionResult(val transcript: String, val isFinal: Boolean,
    val engine: String = "unknown", val mode: String = "unknown",
    val latencyMs: Double? = null, val captureMs: Double? = null) {
    fun toWire() = mapOf("transcript" to transcript, "isFinal" to isFinal)
}
data class IntentParameter(val name: String, val type: ParameterType, val value: Any) {
    init {
        require(name.isNotBlank())
        require(when (type) {
            ParameterType.text -> value is String
            ParameterType.number -> value is Number && value.toDouble().isFinite()
            ParameterType.boolean -> value is Boolean
        })
    }
    fun toWire() = mapOf("name" to name, "type" to type.name, "value" to value)
}
data class AssistantIntent(
    val kind: IntentKind, val parameters: List<IntentParameter>, val confidence: Double? = null
) {
    init {
        require(confidence == null || confidence.isFinite() && confidence in 0.0..1.0)
        require(parameters.map { it.name }.distinct().size == parameters.size)
    }
    fun toWire() = mapOf("kind" to kind.wireName, "parameters" to parameters.map { it.toWire() }, "confidence" to confidence)
}
/** A candidate action, never proof of semantic validation or execution authorization. */
data class AssistantAction(val name: String, val intent: AssistantIntent) {
    fun toWire() = mapOf("name" to name, "intent" to intent.toWire())
}
data class ActionResult(val status: ActionStatus, val code: String, val message: String,
    val evidence: Map<String, Any?> = emptyMap()) {
    fun toWire() = mapOf("status" to status.name, "code" to code, "message" to message, "evidence" to evidence)
}
data class PerformanceMetric(val stage: String, val durationMs: Double?, val status: String) {
    init {
        require(status in setOf("measured", "not_run", "unavailable"))
        require(if (status != "measured") durationMs == null else durationMs != null && durationMs.isFinite() && durationMs >= 0)
    }
    fun toWire() = mapOf("stage" to stage, "durationMs" to durationMs, "status" to status)
}
enum class DiagnosticCode { REQUEST_STARTED, CAPABILITY_NOT_IMPLEMENTED, REQUEST_COMPLETED, INTERNAL_ERROR, LISTENING, STT_PARTIAL, STT_FINAL, TTS_STARTED, CANCELLED }
enum class DiagnosticLevel { INFO, WARNING, ERROR }
data class DiagnosticEvent(
    val requestId: String, val code: DiagnosticCode, val level: DiagnosticLevel,
    val timestampMs: Long = System.currentTimeMillis(), val transcript: String? = null
) {
    fun toWire(): Map<String, Any> = mapOf("schemaVersion" to 1,
        "requestId" to if (Regex("^req-[0-9]+-[0-9]+$").matches(requestId) && requestId.length <= 80) requestId else "redacted",
        "code" to code.name, "level" to level.name, "timestampMs" to timestampMs)
    fun toSessionWire(): Map<String, Any?> = toWire() + mapOf("transcript" to transcript)
}
data class AssistantResponse(
    val requestId: String, val input: String?, val stt: SpeechRecognitionResult? = null,
    val intent: AssistantIntent? = null, val routingTier: RoutingTier = RoutingTier.notRun,
    val selectedModel: String? = null, val structuredOutput: Map<String, Any?>? = null,
    val action: AssistantAction? = null, val result: ActionResult, val response: String,
    val executionMode: ExecutionMode = ExecutionMode.notRun, val metrics: List<PerformanceMetric>,
    val speech: Map<String, Any?> = emptyMap(), val routing: Map<String, Any?> = emptyMap()
) {
    fun toWire(): Map<String, Any?> = mapOf("schemaVersion" to 1, "requestId" to requestId,
        "input" to input, "stt" to stt?.toWire(), "intent" to intent?.toWire(),
        "routingTier" to routingTier.name, "selectedModel" to selectedModel,
        "structuredOutput" to structuredOutput, "action" to action?.toWire(),
        "result" to result.toWire(), "response" to response, "executionMode" to executionMode.name,
        "metrics" to metrics.map { it.toWire() }, "speech" to speech, "routing" to routing)
}
sealed interface CapabilityResult<out T> {
    data class Available<T>(val value: T) : CapabilityResult<T>
    data class Unavailable(val code: String) : CapabilityResult<Nothing>
}
