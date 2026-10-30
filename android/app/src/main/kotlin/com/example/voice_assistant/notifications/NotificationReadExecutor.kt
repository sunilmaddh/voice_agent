package com.example.voice_assistant.notifications

import com.example.voice_assistant.actions.ActionExecutor
import com.example.voice_assistant.intent.CommandValidator
import com.example.voice_assistant.native_bridge.contracts.*

class NotificationReadExecutor(private val service: NotificationService,
    private val restriction: () -> ActionResult?) : ActionExecutor {
    var onWhatsAppRead: (NotificationRecord?) -> Unit = {}
    override suspend fun execute(action: AssistantAction): ActionResult {
        onWhatsAppRead(null) // Any new notification read invalidates the previous reply context.
        if (action.name != action.intent.kind.wireName || action.intent.kind !in setOf(IntentKind.readNotification, IntentKind.readWhatsApp) ||
            CommandValidator().validate(action.intent) !is CapabilityResult.Available)
            return ActionResult(ActionStatus.failed, "INVALID_NOTIFICATION_ACTION", "Provide a valid notification command.")
        restriction()?.let { return it }
        val snapshot = service.latest()
        restriction()?.let { return it }
        if (snapshot is CapabilityResult.Unavailable) return ActionResult(ActionStatus.unsupported, snapshot.code,
            when(snapshot.code) {
                "NOTIFICATION_ACCESS_REQUIRED" -> "Enable notification access for Aiko in Settings, then try again."
                "NOTIFICATION_LISTENER_DISCONNECTED" -> "Notification access is not connected yet. Check notification access in Settings and try again."
                else -> "Android could not read notifications. Try again."
            })
        val whatsapp = action.intent.kind == IntentKind.readWhatsApp
        val records = (snapshot as CapabilityResult.Available).value.filter {
            !whatsapp || it.sourcePackage in setOf("com.whatsapp", "com.whatsapp.w4b")
        }
        val limit = if (action.intent.parameters.any { it.value == "ALL_ACTIVE" }) 5 else 1
        val selected = records.take(limit)
        if (whatsapp) onWhatsAppRead(selected.singleOrNull()?.takeIf { !it.text.isNullOrBlank() })
        val response = if (selected.isEmpty()) "No active ${if (whatsapp) "WhatsApp " else ""}notifications are available. Dismissed messages and chat history cannot be read."
            else selected.joinToString(" ") {
                val app = if (it.sourcePackage in setOf("com.whatsapp", "com.whatsapp.w4b")) "WhatsApp" else it.sourcePackage
                "$app: ${it.text?.take(1000) ?: restrictionMessage(it.restrictionCode)}"
            } + if (records.size > limit) " More notifications are available; this response reads only the latest $limit." else ""
        val unavailable = selected.isNotEmpty() && selected.all { it.text.isNullOrBlank() }
        return ActionResult(if (unavailable) ActionStatus.unsupported else ActionStatus.succeeded,
            if (unavailable) selected.first().restrictionCode ?: "NOTIFICATION_CONTENT_UNAVAILABLE" else "NOTIFICATIONS_READ", response,
            mapOf("notificationCount" to selected.size,
                "restrictionCodes" to selected.mapNotNull { it.restrictionCode }.distinct(),
                "restriction" to "Active notification previews only; content can be hidden or redacted by Android or the source app."))
    }

    private fun restrictionMessage(code: String?) = when (code) {
        "LOCK_SCREEN_NOTIFICATIONS_DISABLED" -> "Lock-screen notifications are disabled. Unlock to read this notification."
        "LOCK_SCREEN_PRIVATE_CONTENT_DISABLED" -> "Your lock-screen settings hide sensitive content. Unlock to read this notification."
        "NOTIFICATION_CHANNEL_PRIVATE" -> "This notification category hides content on the lock screen. Unlock to read it."
        "NOTIFICATION_SECRET" -> "This notification is marked hidden on the lock screen. Unlock to read it."
        "NOTIFICATION_PROFILE_LOCKED" -> "Notifications from another profile require unlocking."
        "NOTIFICATION_DEVICE_POLICY" -> "Device policy prevents this locked readout. Unlock to continue."
        "NOTIFICATION_VISIBILITY_UNKNOWN" -> "Lock-screen visibility could not be verified. Unlock to read this notification."
        else -> "Notification text is hidden or unavailable."
    }
}
