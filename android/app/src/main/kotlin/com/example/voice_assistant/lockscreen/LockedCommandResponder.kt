package com.example.voice_assistant.lockscreen

import com.example.voice_assistant.calendar.ReminderScheduler
import com.example.voice_assistant.intent.*
import com.example.voice_assistant.native_bridge.contracts.*
import java.text.DateFormat
import java.util.Date

/** Explicit locked-state allowlist. Never launches apps or reads private account content. */
class LockedCommandResponder(private val reminders: ReminderScheduler,
    private val callReply: com.example.voice_assistant.actions.ActionExecutor? = null,
    private val drivingMode: (suspend (Boolean) -> ActionResult)? = null) {
    suspend fun respond(text: String, isFinal: Boolean): ActionResult {
        if (!isFinal) return ActionResult(ActionStatus.failed, "STT_FINAL_RESULT_REQUIRED", "Please repeat the complete command.")
        if (LockScreenPolicy.isTime(text)) return ActionResult(ActionStatus.succeeded, "TIME_RESPONSE_COMPLETED",
            "The time is ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())}.")
        val decision = LocalIntentRouter().decide(text)
        val action = decision.proposedAction
        if (action?.intent?.kind in setOf(IntentKind.drivingModeOn, IntentKind.drivingModeOff) &&
            decision.tier == RoutingTier.deterministic && action != null &&
            CommandValidator().validate(action.intent) is CapabilityResult.Available && drivingMode != null)
            return drivingMode.invoke(action.intent.kind == IntentKind.drivingModeOn)
        if (action?.intent?.kind == IntentKind.replyToCall && decision.tier == RoutingTier.deterministic &&
            CommandValidator().validate(action.intent) is CapabilityResult.Available && callReply != null)
            return callReply.execute(action)
        if (action?.intent?.kind == IntentKind.createReminder &&
            decision.tier == RoutingTier.deterministic && CommandValidator().validate(action.intent) is CapabilityResult.Available) {
            val p = action.intent.parameters.associate { it.name to it.value }
            return reminders.schedule(p["title"] as String, (p["dueEpochMs"] as Number).toLong(), p["timeZoneId"] as String)
        }
        return ActionResult(ActionStatus.requiresConfirmation, "UNLOCK_REQUIRED",
            "Unlock and open Aiko for that command. While locked, you can ask the time or say: Remind me tomorrow at 10 AM to call Rahul. No action was executed.")
    }
}
