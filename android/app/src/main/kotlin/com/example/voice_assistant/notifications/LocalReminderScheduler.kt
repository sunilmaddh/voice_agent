package com.example.voice_assistant.notifications

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.example.voice_assistant.MainActivity
import com.example.voice_assistant.calendar.ReminderScheduler
import com.example.voice_assistant.native_bridge.contracts.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/** Local, durable one-shot reminders. Deliberately uses inexact alarms, not restricted exact alarms. */
class LocalReminderScheduler(private val context: Context) : ReminderScheduler {
    private val store = context.getSharedPreferences("local_reminders_v1", Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    companion object { const val CHANNEL = "assistant_reminders"; private val scheduling = Mutex() }
    fun notificationsEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Assistant reminders", NotificationManager.IMPORTANCE_DEFAULT))
            if (notifications.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return notifications.areNotificationsEnabled()
    }
    private fun pending(id: String) = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setAction("com.example.voice_assistant.REMINDER")
            .setData(Uri.parse("aiko-reminder://local/$id")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    override suspend fun schedule(title: String, dueEpochMs: Long, timeZoneId: String): ActionResult =
        scheduling.withLock { save(title, dueEpochMs, timeZoneId, UUID.randomUUID().toString()) }

    suspend fun scheduleBill(plan: BillReminderPlan): ActionResult = scheduling.withLock {
        require(Regex("bill-[a-f0-9]{64}").matches(plan.id))
        save(plan.title, plan.dueEpochMs, plan.zone, plan.id)
    }

    private suspend fun save(title: String, dueEpochMs: Long, timeZoneId: String, id: String): ActionResult = withContext(Dispatchers.IO) {
        if (title.isBlank() || title.length > 1000 || dueEpochMs <= System.currentTimeMillis() || timeZoneId !in TimeZone.getAvailableIDs())
            return@withContext ActionResult(ActionStatus.failed, "INVALID_REMINDER", "Please specify a future reminder date and time.")
        if (!notificationsEnabled()) return@withContext ActionResult(ActionStatus.unsupported, "NOTIFICATION_PERMISSION_REQUIRED",
            "Enable reminder notifications in Aiko Settings and Android notification settings, then retry.")
        if (store.contains(id)) return@withContext ActionResult(ActionStatus.succeeded, "REMINDER_ALREADY_SCHEDULED",
            "This bill already has a pending local reminder. Its original time was retained.")
        if (store.all.size >= 100) return@withContext ActionResult(ActionStatus.failed, "REMINDER_LIMIT", "The POC supports up to 100 pending reminders.")
        val value = JSONObject().put("title", title).put("due", dueEpochMs).put("zone", timeZoneId).toString()
        if (!store.edit().putString(id, value).commit()) return@withContext ActionResult(ActionStatus.failed, "REMINDER_SAVE_FAILED", "The reminder could not be saved.")
        try {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueEpochMs, pending(id))
            val date = SimpleDateFormat("EEE, MMM d, yyyy 'at' h:mm a z", Locale.US).apply { timeZone = TimeZone.getTimeZone(timeZoneId) }.format(Date(dueEpochMs))
            ActionResult(ActionStatus.succeeded, "REMINDER_SCHEDULED_INEXACT", "Reminder saved for $date: $title. Android may delay this notification. This is a local reminder, not a Google Calendar event.")
        } catch (_: RuntimeException) {
            store.edit().remove(id).commit()
            ActionResult(ActionStatus.failed, "REMINDER_SCHEDULE_FAILED", "Android could not schedule the reminder.")
        }
    }
    fun restore() {
        val now = System.currentTimeMillis()
        for ((id, raw) in store.all) {
            try {
                val due = JSONObject(raw as String).getLong("due")
                // Deliver recently missed reminders after reboot, discard those older than one day.
                if (due < now - 86400000L) store.edit().remove(id).commit()
                else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(due, now + 1000), pending(id))
            } catch (_: Exception) { /* Retain entry so a later app start can retry. */ }
        }
    }
    fun deliver(id: String) {
        val raw = store.getString(id, null) ?: return
        val item = JSONObject(raw)
        val due = item.getLong("due")
        if (due > System.currentTimeMillis()) { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending(id)); return }
        if (!notificationsEnabled()) return // Retained; app startup can retry after permission is restored.
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else Notification.Builder(context)
        val title = item.getString("title")
        notifications.notify(id, 1, builder.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Aiko reminder").setContentText(title).setStyle(Notification.BigTextStyle().bigText(title))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true).build())
        store.edit().remove(id).commit()
        pending(id).cancel()
    }
}
