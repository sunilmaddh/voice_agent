package com.example.voice_assistant.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import com.example.voice_assistant.native_bridge.contracts.*

/** Reads a fresh active snapshot only on request; no notification archive or content logging. */
class AssistantNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { connected = this }
    override fun onListenerDisconnected() { if (connected === this) connected = null }
    override fun onDestroy() { if (connected === this) connected = null; super.onDestroy() }

    companion object {
        @Volatile private var connected: AssistantNotificationListener? = null
        fun granted(context: Context): Boolean {
            val component = ComponentName(context, AssistantNotificationListener::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 27) return context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
            return android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true
        }
        fun ready() = connected != null
        fun replyTarget(context: Context, key: String? = null, postTime: Long? = null): CapabilityResult<ReplyTarget> {
            if (!granted(context)) return CapabilityResult.Unavailable("NOTIFICATION_ACCESS_REQUIRED")
            val listener = connected ?: return CapabilityResult.Unavailable("NOTIFICATION_LISTENER_DISCONNECTED")
            return try {
                val item = listener.activeNotifications.orEmpty().filter {
                    it.packageName in setOf("com.whatsapp", "com.whatsapp.w4b") &&
                        it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
                        (key == null || (it.key == key && it.postTime == postTime))
                }.maxByOrNull { it.postTime } ?: return CapabilityResult.Unavailable("WHATSAPP_NOTIFICATION_UNAVAILABLE")
                fun replyAction(n: Notification) = n.actions.orEmpty().filter {
                    it.actionIntent?.creatorPackage == item.packageName &&
                        it.remoteInputs?.count { input -> input.allowFreeFormInput } == 1 &&
                        (android.os.Build.VERSION.SDK_INT < 28 || it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY)
                }.singleOrNull()
                val action = replyAction(item.notification) ?: return CapabilityResult.Unavailable("WHATSAPP_REPLY_UNAVAILABLE")
                fun lockedRestriction(candidate: Notification.Action): String? {
                    val guard = context.getSystemService(android.app.KeyguardManager::class.java)
                    if (!guard.isDeviceLocked && !guard.isKeyguardLocked) return null
                    val prefs = context.getSharedPreferences("assistant_privacy", 0)
                    if (!prefs.getBoolean("lockedWhatsAppReplies", false) || !prefs.getBoolean("lockedPersonalReads", false))
                        return "WHATSAPP_LOCKED_REPLY_DISABLED"
                    if (android.os.Build.VERSION.SDK_INT < 31 || candidate.isAuthenticationRequired)
                        return "WHATSAPP_REPLY_AUTHENTICATION_REQUIRED"
                    val visible = snapshot(context)
                    if (visible !is CapabilityResult.Available || visible.value.none {
                            it.id == item.key && it.postTime == item.postTime && !it.text.isNullOrBlank()
                        }) return "WHATSAPP_NOTIFICATION_HIDDEN"
                    return null
                }
                lockedRestriction(action)?.let { return CapabilityResult.Unavailable(it) }
                fun content(n: Notification): List<String?> = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_CONVERSATION_TITLE,
                    Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT).map { n.extras.getCharSequence(it)?.toString() }
                val originalContent = content(item.notification)
                val recipient = item.notification.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                    ?: item.notification.extras.getCharSequence(Notification.EXTRA_TITLE)
                    ?: return CapabilityResult.Unavailable("WHATSAPP_RECIPIENT_UNAVAILABLE")
                val label = recipient.toString().replace(Regex("\\s+"), " ").take(200)
                if (label.isBlank()) return CapabilityResult.Unavailable("WHATSAPP_RECIPIENT_UNAVAILABLE")
                CapabilityResult.Available(ReplyTarget(label) { body ->
                    try {
                        val current = if (granted(context) && connected === listener)
                            listener.activeNotifications.orEmpty().firstOrNull { it.key == item.key } else null
                        val currentAction = current?.let { replyAction(it.notification) }
                        if (current == null || current.postTime != item.postTime || currentAction?.actionIntent != action.actionIntent ||
                            content(current.notification) != originalContent) {
                            ActionResult(ActionStatus.failed, "WHATSAPP_NOTIFICATION_CHANGED", "The notification changed or access was lost. Dictate and review a new reply.")
                        } else {
                            val blocked = lockedRestriction(currentAction)
                            if (blocked != null) return@ReplyTarget ActionResult(ActionStatus.unsupported, blocked,
                                "Unlock your phone to reply. Notification privacy or authentication prevents this locked reply.")
                            val inputs = currentAction.remoteInputs.filter { it.allowFreeFormInput }.toTypedArray()
                            val intent = android.content.Intent()
                            val results = android.os.Bundle().apply { putCharSequence(inputs.single().resultKey, body) }
                            android.app.RemoteInput.addResultsToIntent(inputs, intent, results)
                            currentAction.actionIntent.send(context, 0, intent)
                            ActionResult(ActionStatus.succeeded, "WHATSAPP_REPLY_DISPATCHED", "Reply handed to WhatsApp. Delivery is not verified.",
                                mapOf("androidMechanism" to "RemoteInput + notification PendingIntent", "deliveryVerified" to false))
                        }
                    } catch (_: android.app.PendingIntent.CanceledException) {
                        ActionResult(ActionStatus.failed, "WHATSAPP_REPLY_CANCELLED", "The WhatsApp reply action expired. Nothing was retried.")
                    } catch (_: Exception) {
                        ActionResult(ActionStatus.failed, "WHATSAPP_REPLY_FAILED", "Reply dispatch could not be verified. Check WhatsApp before retrying.")
                    }
                })
            } catch (_: Exception) { CapabilityResult.Unavailable("WHATSAPP_REPLY_UNAVAILABLE") }
        }
        fun snapshot(context: Context): CapabilityResult<List<NotificationRecord>> {
            if (!granted(context)) return CapabilityResult.Unavailable("NOTIFICATION_ACCESS_REQUIRED")
            val listener = connected ?: return CapabilityResult.Unavailable("NOTIFICATION_LISTENER_DISCONNECTED")
            return try {
                val records = listener.activeNotifications.orEmpty()
                    .filter { it.packageName != context.packageName && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
                    .sortedByDescending { it.postTime }
                    .map { item ->
                        // Never reveal hidden notification content through a locked assistant.
                        val locked = context.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked
                        val ranking = android.service.notification.NotificationListenerService.Ranking()
                        val channelVisibility = if (android.os.Build.VERSION.SDK_INT >= 26 && listener.currentRanking.getRanking(item.key, ranking)) ranking.channel?.lockscreenVisibility else null
                        fun settingEnabled(name: String) =
                            android.provider.Settings.Secure.getInt(context.contentResolver, name, 0) == 1
                        val policyAllows = try {
                            val disabled = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
                                .getKeyguardDisabledFeatures(null)
                            disabled and (android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS or
                                android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS) == 0
                        } catch (_: Exception) { false }
                        val blocked = LockedNotificationPolicy.restriction(
                            locked, item.user == android.os.Process.myUserHandle(),
                            settingEnabled("lock_screen_show_notifications"),
                            settingEnabled("lock_screen_allow_private_notifications"),
                            item.notification.visibility, channelVisibility, policyAllows)
                        if (blocked != null) {
                            return@map NotificationRecord(item.key, item.packageName, null, item.postTime, blocked)
                        }
                        val extras = item.notification.extras
                        val messages = if (android.os.Build.VERSION.SDK_INT >= 30)
                            Notification.MessagingStyle.Message.getMessagesFromBundleArray(extras.getParcelableArray(Notification.EXTRA_MESSAGES))
                            else emptyList()
                        val latest = messages.maxByOrNull { it.timestamp }
                        val body = latest?.text ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.lastOrNull()
                        val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                            ?: (if (android.os.Build.VERSION.SDK_INT >= 28) latest?.senderPerson?.name else latest?.sender)
                            ?: extras.getCharSequence(Notification.EXTRA_TITLE)
                        val text = listOfNotNull(title, body).joinToString(". ") { it.toString().replace(Regex("\\s+"), " ").take(500) }
                        NotificationRecord(item.key, item.packageName, text.takeIf { it.isNotBlank() }, item.postTime)
                    }
                CapabilityResult.Available(records)
            } catch (_: SecurityException) { CapabilityResult.Unavailable("NOTIFICATION_ACCESS_REQUIRED") }
            catch (_: Exception) { CapabilityResult.Unavailable("NOTIFICATION_READ_FAILED") }
        }
    }
}
