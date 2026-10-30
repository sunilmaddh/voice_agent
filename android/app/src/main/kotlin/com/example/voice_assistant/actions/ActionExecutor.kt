package com.example.voice_assistant.actions
import com.example.voice_assistant.native_bridge.contracts.*
interface ActionExecutor {
    /** Must reject unvalidated, unauthorized, or unsupported actions. Never accepts raw model text. */
    suspend fun execute(action: AssistantAction): ActionResult
}
