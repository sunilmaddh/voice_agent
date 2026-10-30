package com.example.voice_assistant.calendar

import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.CalendarContract as C
import com.example.voice_assistant.native_bridge.contracts.*

class AndroidCalendarLaunchPort(private val activity: Activity, private val isForeground: () -> Boolean) : CalendarLaunchPort {
    override fun restriction(): ActionResult? = when {
        !isForeground() || activity.isFinishing || activity.isDestroyed -> ActionResult(ActionStatus.failed, "APP_NOT_FOREGROUND", "Open Aiko and try again.")
        activity.getSystemService(KeyguardManager::class.java).isDeviceLocked -> ActionResult(ActionStatus.requiresConfirmation, "UNLOCK_REQUIRED", "Unlock your phone to use calendar and reminders.")
        else -> null
    }
    override fun openCalendar() = launch(activity.packageManager.getLaunchIntentForPackage("com.google.android.calendar")
        ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR), false)
    override fun createEvent(title: String, start: Long, end: Long, zone: String) = launch(
        Intent(Intent.ACTION_INSERT, C.Events.CONTENT_URI).putExtra(C.Events.TITLE, title)
            .putExtra(C.EXTRA_EVENT_BEGIN_TIME, start).putExtra(C.EXTRA_EVENT_END_TIME, end)
            .putExtra(C.Events.EVENT_TIMEZONE, zone), true)
    private fun launch(intent: Intent, editing: Boolean): ActionResult {
        restriction()?.let { return it }
        val google = Intent(intent).setPackage("com.google.android.calendar")
        val target = if (google.resolveActivity(activity.packageManager) != null) google else intent
        if (target.resolveActivity(activity.packageManager) == null) return ActionResult(ActionStatus.unsupported, "CALENDAR_APP_UNAVAILABLE", "Install or enable a calendar application.")
        return try {
            activity.startActivity(target)
            ActionResult(if (editing) ActionStatus.requiresConfirmation else ActionStatus.succeeded,
                if (editing) "CALENDAR_EDITOR_OPENED" else "CALENDAR_LAUNCH_REQUESTED",
                if (editing) "Calendar editor opened. Choose a writable Google calendar and save the event. Aiko cannot verify whether you saved it."
                else "Calendar launch requested.")
        } catch (_: ActivityNotFoundException) { ActionResult(ActionStatus.unsupported, "CALENDAR_APP_UNAVAILABLE", "The calendar app is unavailable.") }
        catch (_: SecurityException) { ActionResult(ActionStatus.failed, "CALENDAR_LAUNCH_DENIED", "Android blocked opening the calendar.") }
    }
}
