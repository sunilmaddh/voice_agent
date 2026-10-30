package com.example.voice_assistant.sms

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import android.service.voice.VoiceInteractionService
import com.example.voice_assistant.lockscreen.AikoVoiceInteractionService
import com.example.voice_assistant.native_bridge.contracts.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidSmsPort(private val activity: Activity, private val foreground: () -> Boolean) : SmsPort {
    override fun restriction(): ActionResult? = when {
        !foreground() || activity.isFinishing || activity.isDestroyed -> ActionResult(ActionStatus.failed, "APP_NOT_FOREGROUND", "Open Aiko to read or reply to SMS.")
        activity.getSystemService(KeyguardManager::class.java).isDeviceLocked -> ActionResult(ActionStatus.requiresConfirmation, "UNLOCK_REQUIRED", "Unlock to read or reply to SMS.")
        !isAssistant(activity) -> ActionResult(ActionStatus.unsupported, "SMS_ASSISTANT_ROLE_REQUIRED", "Select Aiko as your default assistant in System assistant settings before enabling SMS access.")
        activity.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED -> ActionResult(ActionStatus.unsupported, "SMS_PERMISSION_REQUIRED", "Open System assistant settings, then Configure SMS access, and grant SMS permission.")
        else -> null
    }
    override suspend fun latest() = query(null)
    override suspend fun find(id: Long) = query(id)
    private suspend fun query(id: Long?): SmsMessage? = withContext(Dispatchers.IO) {
        // Projection is deliberately limited. No inbox contents are written to logs or disk.
        val result = activity.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf("_id", "address", "body", "date"), if (id == null) null else "_id = ?",
            id?.let { arrayOf(it.toString()) }, "date DESC, _id DESC")
            ?: throw IllegalStateException("SMS_PROVIDER_UNAVAILABLE")
        result.use { cursor ->
            if (!cursor.moveToFirst()) null else SmsMessage(cursor.getLong(0), cursor.getString(1).orEmpty(),
                cursor.getString(2).orEmpty(), cursor.getLong(3))
        }
    }
    override fun compose(address: String, body: String): Boolean {
        if (restriction() != null) return false
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", address, null)).putExtra("sms_body", body)
        return try { activity.startActivity(intent); true }
        catch (_: android.content.ActivityNotFoundException) { false }
    }
    companion object {
        fun isAssistant(context: Context) = VoiceInteractionService.isActiveService(context,
            ComponentName(context, AikoVoiceInteractionService::class.java))
    }
}
