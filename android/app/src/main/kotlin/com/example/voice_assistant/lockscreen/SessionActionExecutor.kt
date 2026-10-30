package com.example.voice_assistant.lockscreen
import com.example.voice_assistant.actions.ActionExecutor
import com.example.voice_assistant.intent.CommandValidator
import com.example.voice_assistant.native_bridge.contracts.*

class SessionActionExecutor(private val delegate: ActionExecutor, private val locked: () -> Boolean,
    private val personalReads: () -> Boolean, private val now: () -> Long = System::currentTimeMillis,
    private val lockedReplies: () -> Boolean = { false },
    private val handsFreeCalls: () -> Boolean = { false },
    private val voiceHangup: () -> Boolean = { false }) : ActionExecutor {
    private var pending: Pair<AssistantAction, Long>? = null
    fun clear() { pending = null }
    fun hasPendingNavigation(): Boolean = pending?.let {
        now() < it.second && it.first.intent.kind in setOf(IntentKind.navigate, IntentKind.openMaps)
    } == true
    override suspend fun execute(action: AssistantAction): ActionResult {
        clear()
        if (action.name != action.intent.kind.wireName || CommandValidator().validate(action.intent) !is CapabilityResult.Available)
            return ActionResult(ActionStatus.failed, "INVALID_ACTION", "Please repeat a complete command.")
        val permittedReply = action.intent.kind == IntentKind.replyWhatsApp && personalReads() && lockedReplies()
        val permittedCall = action.intent.kind == IntentKind.callPhone && handsFreeCalls()
        val permittedHangup = action.intent.kind == IntentKind.endCall && voiceHangup()
        val permittedCallReply = action.intent.kind == IntentKind.replyToCall
        if (!permittedCallReply && !permittedHangup && !permittedCall && !permittedReply && SessionAccessPolicy.requiresUnlock(action.intent.kind, locked(), personalReads())) {
            pending = action to (now() + 120000)
            return ActionResult(ActionStatus.requiresConfirmation, "UNLOCK_REQUIRED", "Unlock with the system prompt to continue this command.")
        }
        return delegate.execute(action)
    }
    suspend fun resumeAfterUnlock(): ActionResult {
        val saved = pending; clear()
        if (locked() || saved == null || now() >= saved.second)
            return ActionResult(ActionStatus.failed, "AUTHENTICATION_REQUIRED", "Unlock failed or the command expired. Nothing was executed.")
        return execute(saved.first)
    }
}
