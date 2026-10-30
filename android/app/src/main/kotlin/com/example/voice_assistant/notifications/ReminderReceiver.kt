package com.example.voice_assistant.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val scheduler = LocalReminderScheduler(context.applicationContext)
                when (intent.action) {
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> scheduler.restore()
                    "com.example.voice_assistant.REMINDER" -> intent.data?.lastPathSegment?.let { scheduler.deliver(it) }
                }
            } catch (_: Exception) { /* No calendar content or reminder titles in system logs. */ }
            finally { pending.finish() }
        }
    }
}
