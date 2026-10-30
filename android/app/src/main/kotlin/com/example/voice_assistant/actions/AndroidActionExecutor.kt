package com.example.voice_assistant.actions

import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.voice_assistant.native_bridge.contracts.*

class AndroidActionExecutor(private val activity: Activity, private val gmail: ActionExecutor? = null, private val whatsapp: com.example.voice_assistant.notifications.WhatsAppReplySession? = null,
    private val allowLockedReads: () -> Boolean = { false }, private val isForeground: () -> Boolean) : ActionExecutor {
    private fun readRestriction(): ActionResult? = when {
        !isForeground() || activity.isFinishing || activity.isDestroyed -> ActionResult(ActionStatus.failed, "APP_NOT_FOREGROUND", "Assistant session is not active.")
        activity.getSystemService(KeyguardManager::class.java).isDeviceLocked && !allowLockedReads() -> ActionResult(ActionStatus.requiresConfirmation, "UNLOCK_REQUIRED", "Unlock to read personal information.")
        else -> null
    }
    private val notificationReader = com.example.voice_assistant.notifications.NotificationReadExecutor(
        object : com.example.voice_assistant.notifications.NotificationService {
            override suspend fun latest() = com.example.voice_assistant.notifications.AssistantNotificationListener.snapshot(activity)
        }) {
        readRestriction()
    }
    init { notificationReader.onWhatsAppRead = { whatsapp?.rememberRead(it) } }
    private val sms = SmsComposeExecutor(object : SmsComposePort {
        override fun restriction(): ActionResult? = when {
            !isForeground() || activity.isFinishing || activity.isDestroyed -> ActionResult(ActionStatus.failed, "APP_NOT_FOREGROUND", "Open Aiko and try again.")
            activity.getSystemService(KeyguardManager::class.java).isDeviceLocked -> ActionResult(ActionStatus.requiresConfirmation, "UNLOCK_REQUIRED", "Unlock your phone to compose an SMS.")
            else -> null
        }
        override fun open(number: String?, body: String): Boolean {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${number.orEmpty()}"))
                .putExtra("sms_body", body)
            if (intent.resolveActivity(activity.packageManager) == null) return false
            return try { activity.startActivity(intent); true } catch (_: ActivityNotFoundException) { false }
        }
    })
    private val calendar = com.example.voice_assistant.calendar.CalendarActionExecutor(
        com.example.voice_assistant.calendar.AndroidCalendarService(activity.applicationContext),
        com.example.voice_assistant.notifications.LocalReminderScheduler(activity.applicationContext),
        com.example.voice_assistant.calendar.AndroidCalendarLaunchPort(activity, isForeground), readRestriction = ::readRestriction)
    private val maps = MapsActionExecutor(activity, isForeground)
    private val smsSession = com.example.voice_assistant.sms.SmsSession(com.example.voice_assistant.sms.AndroidSmsPort(activity, isForeground),
        com.example.voice_assistant.sms.SmsReplyContext.inProcess)
    private val webAnswer = WebAnswerExecutor(SpokenWebSearch::search,
        com.example.voice_assistant.ai.AssistantRuntime.commandModel(activity)::selectWebAnswer) {
        if (!isForeground() || activity.isFinishing || activity.isDestroyed)
            ActionResult(ActionStatus.failed, "APP_NOT_FOREGROUND", "Assistant session is not active.") else null
    }
    private val reporting = ActionReportingExecutor(object : ActionExecutor {
        override suspend fun execute(action: AssistantAction) = dispatch(action)
    })
    override suspend fun execute(action: AssistantAction): ActionResult = reporting.execute(action)
    private suspend fun dispatch(action: AssistantAction): ActionResult = when (action.intent.kind) {
        IntentKind.drivingModeOn, IntentKind.drivingModeOff -> com.example.voice_assistant.communications.DrivingMode.set(activity,
            action.intent.kind == IntentKind.drivingModeOn, voiceRequested = true)
        IntentKind.replyToCall -> com.example.voice_assistant.communications.VoiceCallReplyExecutor(activity, isForeground).execute(action)
        IntentKind.readSms, IntentKind.replySms -> smsSession.execute(action)
        IntentKind.endCall -> EndCallExecutor(AndroidEndCallPort(activity, isForeground)).execute(action)
        IntentKind.callPhone -> if (activity.getSharedPreferences("assistant_privacy", 0).getBoolean("handsFreeCalls", false))
            DirectCallExecutor(activity, isForeground).execute(action) else PhoneCallExecutor(activity, isForeground).execute(action)
        IntentKind.replyWhatsApp -> whatsapp?.execute(action) ?: ActionResult(ActionStatus.unsupported, "WHATSAPP_REPLY_UNAVAILABLE", "WhatsApp reply is unavailable.")
        IntentKind.readNotification, IntentKind.readWhatsApp -> notificationReader.execute(action)
        IntentKind.sendMessage -> sms.execute(action)
        IntentKind.navigate, IntentKind.openMaps -> maps.execute(action)
        IntentKind.googleSearch -> if (activity.getSystemService(KeyguardManager::class.java).isDeviceLocked)
            webAnswer.execute(action)
        else withContext(Dispatchers.Main.immediate) {
            if (com.example.voice_assistant.intent.CommandValidator().validate(action.intent) !is CapabilityResult.Available)
                ActionResult(ActionStatus.failed, "INVALID_SEARCH_ACTION", "Please provide a clear search query.")
            else if (!isForeground() || activity.isFinishing || activity.isDestroyed)
                ActionResult(ActionStatus.failed, "APP_NOT_FOREGROUND", "Assistant session is not active.")
            else {
                activity.startActivity(Intent(activity, SearchReportActivity::class.java)
                    .putExtra("query", action.intent.parameters.single().value as String))
                ActionResult(ActionStatus.succeeded, "SEARCH_REPORT_OPENED", "Search results are opening in Aiko. Tap Speak summary to hear a report.")
            }
        }
        IntentKind.readEmail, IntentKind.replyEmail -> gmail?.execute(action)
            ?: ActionResult(ActionStatus.unsupported, "GMAIL_AUTH_REQUIRED", "Connect Gmail first.")
        IntentKind.queryCalendar, IntentKind.createReminder, IntentKind.createCalendarEvent, IntentKind.openCalendar -> calendar.execute(action)
        else -> ActionResult(ActionStatus.unsupported, "ACTION_NOT_IMPLEMENTED", "This action is not implemented.")
    }
}


