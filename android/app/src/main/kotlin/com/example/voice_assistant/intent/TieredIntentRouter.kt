package com.example.voice_assistant.intent

import com.example.voice_assistant.ai.CommandModel
import com.example.voice_assistant.native_bridge.contracts.*

/** Only incomplete command routes reach inference; blocked Tier 1 and Tier 3 never do. */
class TieredIntentRouter(private val model: CommandModel,
    private val deterministic: LocalIntentRouter = LocalIntentRouter(),
    private val validator: IntentValidator = CommandValidator()) : IntentRouter {
    override suspend fun route(text: String): CapabilityResult<RoutedIntent> {
        val initial = deterministic.decide(text)
        if (initial.tier != RoutingTier.commandModel) return CapabilityResult.Available(initial)
        val extracted = model.extract(text)
        val diagnostics = model.diagnostics
        if (extracted is CapabilityResult.Unavailable) return CapabilityResult.Available(initial.copy(
            reason = extracted.code, status = if (extracted.code == "MODEL_NOT_CONFIGURED")
                RoutingStatus.MODEL_NOT_CONFIGURED else RoutingStatus.MODEL_OUTPUT_REJECTED,
            modelId = diagnostics["modelSelected"] as? String, modelDiagnostics = diagnostics))
        val intent = (extracted as CapabilityResult.Available).value
        // Convert through the strict domain schema even for a different CommandModel implementation.
        val parsed = try {
            com.example.voice_assistant.ai.CommandOutputSchema.parse(com.google.gson.Gson().toJson(mapOf(
                "intent" to intent.kind.wireName, "parameters" to intent.parameters.associate { it.name to it.value },
                "confidence" to intent.confidence, "executionMode" to "LOCAL")))
        } catch (_: IllegalArgumentException) {
            return CapabilityResult.Available(initial.copy(status = RoutingStatus.MODEL_OUTPUT_REJECTED,
                reason = "Model returned an invalid command schema.", modelId = model.modelId, modelDiagnostics = diagnostics))
        }
        val checked = validator.validate(intent)
        return CapabilityResult.Available(RoutedIntent(parsed.command, RoutingTier.commandModel, parsed.confidence,
            if (checked is CapabilityResult.Available) "Local model extraction passed schema and parameter checks; proposal only."
            else "Clarification required: ${(checked as CapabilityResult.Unavailable).code}.",
            if (checked is CapabilityResult.Available) RoutingStatus.PROPOSAL_ONLY else RoutingStatus.CLARIFICATION_REQUIRED,
            modelRequired = true, modelId = model.modelId,
            proposedAction = (checked as? CapabilityResult.Available)?.value, modelDiagnostics = diagnostics))
    }
}
