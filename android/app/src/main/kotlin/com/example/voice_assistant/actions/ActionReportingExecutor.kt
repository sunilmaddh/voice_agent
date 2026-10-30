package com.example.voice_assistant.actions

import com.example.voice_assistant.intent.CommandValidator
import com.example.voice_assistant.native_bridge.contracts.*
import kotlinx.coroutines.CancellationException

/** Revalidation and uniform execution evidence across native capability adapters. */
class ActionReportingExecutor(private val delegate: ActionExecutor) : ActionExecutor {
    override suspend fun execute(action: AssistantAction): ActionResult {
        val valid = action.name == action.intent.kind.wireName && CommandValidator().validate(action.intent) is CapabilityResult.Available
        val result = if (!valid) ActionResult(ActionStatus.failed, "INVALID_ACTION", "The action did not pass validation. Nothing was executed.") else try {
            delegate.execute(action)
        } catch (e: CancellationException) { throw e }
        catch (_: SecurityException) { ActionResult(ActionStatus.failed, "ACTION_PERMISSION_DENIED", "Android denied this action. Check the required permission.") }
        catch (_: Exception) { ActionResult(ActionStatus.failed, "ACTION_FAILED", "The action failed. Completion could not be verified.") }
        val mechanism = if (!valid) "NONE" else when (action.intent.kind) {
            IntentKind.drivingModeOn, IntentKind.drivingModeOff -> "Local driving preference / Telecom InCallService policy"
            IntentKind.replyToCall -> "SmsManager fixed voice-requested reply; decline after sent receipt"
            IntentKind.readSms -> "Telephony.Sms.Inbox / READ_SMS"
            IntentKind.replySms -> "ACTION_SENDTO smsto + sms_body (review required)"
            IntentKind.endCall -> "TelecomManager.endCall (deprecated public API)"
            IntentKind.callPhone -> "ACTION_DIAL tel; contact lookup and selection when needed"
            IntentKind.navigate -> "ACTION_VIEW google.navigation (Google Maps)"
            IntentKind.openMaps -> "PackageManager Google Maps launch intent"
            IntentKind.googleSearch -> "HTTPS instant answers / local SLM source selection / TTS"
            IntentKind.sendMessage -> "ACTION_SENDTO smsto + sms_body"
            IntentKind.createReminder -> "AlarmManager.setAndAllowWhileIdle + NotificationManager"
            IntentKind.queryCalendar -> "CalendarContract.Instances + READ_CALENDAR"
            IntentKind.createCalendarEvent -> "ACTION_INSERT CalendarContract.Events"
            IntentKind.openCalendar -> "Calendar app launch intent"
            IntentKind.readEmail -> "Gmail API / selected local email"
            IntentKind.readNotification, IntentKind.readWhatsApp -> "NotificationListenerService.getActiveNotifications"
            IntentKind.replyEmail -> "Local Gmail review draft (no send)"
            IntentKind.replyWhatsApp -> if (result.code == "WHATSAPP_REPLY_DISPATCHED") "RemoteInput + notification PendingIntent" else "Local WhatsApp notification reply preparation"
            else -> "NONE"
        }
        val handoff = result.code in setOf("MAPS_LAUNCH_REQUESTED", "GOOGLE_SEARCH_APP_LAUNCH_REQUESTED", "GOOGLE_SEARCH_BROWSER_LAUNCH_REQUESTED", "SMS_COMPOSER_OPENED", "CALENDAR_EDITOR_OPENED", "CALENDAR_LAUNCH_REQUESTED")
        val restriction = when {
            result.code == "SMS_COMPOSER_OPENED" -> result.message
            handoff -> "Android accepted the app handoff; completion inside that app is not verified."
            result.code == "REMINDER_SCHEDULED_INEXACT" -> "Notification delivery is inexact and can be delayed or blocked by Android settings."
            result.status != ActionStatus.succeeded -> result.message
            else -> null
        }
        return result.copy(evidence = mapOf(
            "success" to (result.status == ActionStatus.succeeded),
            "actionType" to action.intent.kind.wireName,
            "parameters" to action.intent.parameters.associate { it.name to it.value },
            "androidMechanism" to mechanism,
            "userInteractionRequired" to (handoff || result.status == ActionStatus.requiresConfirmation || result.code.contains("PERMISSION")),
            "unlockRequired" to (action.intent.kind != IntentKind.googleSearch),
            "error" to if (result.status in setOf(ActionStatus.failed, ActionStatus.unsupported)) result.code else null,
            "restriction" to restriction,
            "outcome" to when {
                handoff -> "HANDOFF_ONLY"
                result.code == "CALL_REPLY_SMS_PENDING" -> "REQUESTED_NOT_SENT_OR_DELIVERED"
                result.code == "REMINDER_SCHEDULED_INEXACT" -> "SCHEDULED_NOT_DELIVERED"
                result.status == ActionStatus.succeeded -> "COMPLETED"
                result.status == ActionStatus.requiresConfirmation -> "USER_ACTION_REQUIRED"
                else -> "NOT_COMPLETED"
            }
        ) + result.evidence)
    }
}

