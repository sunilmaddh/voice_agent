package com.example.voice_assistant.intent

import com.example.voice_assistant.native_bridge.contracts.*

enum class RoutingStatus { PROPOSAL_ONLY, CLARIFICATION_REQUIRED, MODEL_NOT_CONFIGURED, MODEL_OUTPUT_REJECTED, UNKNOWN }

data class RoutedIntent(
    val command: TypedCommand,
    val tier: RoutingTier,
    val confidence: Double,
    val reason: String,
    val status: RoutingStatus,
    val modelRequired: Boolean = false,
    val modelId: String? = null,
    val proposedAction: AssistantAction? = null,
    val modelDiagnostics: Map<String, Any?> = emptyMap()
) {
    val intent: AssistantIntent get() = command.toIntent(confidence)
    val response: String get() = when(status) {
        RoutingStatus.PROPOSAL_ONLY -> "Recognized ${intent.kind.wireName}. External actions are not implemented; nothing was executed."
        RoutingStatus.CLARIFICATION_REQUIRED -> "Please clarify the command. $reason Nothing was executed."
        RoutingStatus.MODEL_NOT_CONFIGURED -> "This request needs a local model that is not configured. Nothing was executed."
        RoutingStatus.MODEL_OUTPUT_REJECTED -> "The local model could not produce a valid command. Please rephrase. Nothing was executed."
        RoutingStatus.UNKNOWN -> "I could not identify a supported command. Nothing was executed."
    }
    fun diagnostics(): Map<String, Any?> = mapOf("tier" to tier.name, "intent" to intent.kind.wireName,
        "confidence" to confidence, "reason" to reason, "status" to status.name,
        "modelRequired" to modelRequired, "modelSelected" to modelId,
        "executionMode" to "LOCAL", "modelExecutionMode" to (modelDiagnostics["modelExecutionMode"] ?: "NOT_RUN"), "actionExecuted" to false,
        "modelMetrics" to modelDiagnostics, "proposedAction" to proposedAction?.toWire())
    fun structured(): Map<String, Any?> = mapOf("intent" to intent.kind.wireName,
        "parameters" to command.parameters.associate { it.name to it.value },
        "confidence" to confidence, "executionMode" to "LOCAL")
}

interface IntentRouter { suspend fun route(text: String): CapabilityResult<RoutedIntent> }
interface IntentValidator { fun validate(intent: AssistantIntent): CapabilityResult<AssistantAction> }
