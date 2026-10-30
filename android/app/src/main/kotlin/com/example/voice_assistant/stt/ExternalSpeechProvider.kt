package com.example.voice_assistant.stt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.speech.RecognitionService
import android.provider.Settings

/** Exclude our delegator: choosing Aiko as assistant must never recurse into itself. */
object ExternalSpeechProvider {
    fun component(context: Context): ComponentName? {
        val services = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo }.filter { it.packageName != context.packageName && it.exported && it.enabled }
        val current = ComponentName.unflattenFromString(Settings.Secure.getString(context.contentResolver, "voice_recognition_service") ?: "")
        return services.firstOrNull { ComponentName(it.packageName, it.name) == current }?.let { ComponentName(it.packageName, it.name) }
            ?: services.sortedBy { it.packageName + it.name }.firstOrNull()?.let { ComponentName(it.packageName, it.name) }
    }
}
