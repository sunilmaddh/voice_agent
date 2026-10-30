package com.example.voice_assistant.lockscreen

import android.app.Activity
import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.content.pm.PackageManager
import org.json.JSONObject

class LockScreenProbe(private val activity: Activity) {
    fun snapshot(): Map<String, Any?> {
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        val audio = activity.getSystemService(AudioManager::class.java)
        val roleState = if (Build.VERSION.SDK_INT >= 29) {
            val role = activity.getSystemService(RoleManager::class.java)
            role.isRoleAvailable(RoleManager.ROLE_ASSISTANT) to role.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        } else false to false
        return mapOf("timestampMs" to System.currentTimeMillis(), "androidSdk" to Build.VERSION.SDK_INT,
            "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL,
            "deviceLocked" to keyguard.isDeviceLocked, "keyguardLocked" to keyguard.isKeyguardLocked,
            "screenInteractive" to activity.getSystemService(PowerManager::class.java).isInteractive,
            "assistantRoleAvailable" to roleState.first,
            "assistantRoleHeld" to roleState.second,
            "systemAssistantSelected" to android.service.voice.VoiceInteractionService.isActiveService(activity, android.content.ComponentName(activity, AikoVoiceInteractionService::class.java)),
            "lockedPersonalReads" to activity.getSharedPreferences("assistant_privacy", 0).getBoolean("lockedPersonalReads", false),
            "lockedWhatsAppReplies" to activity.getSharedPreferences("assistant_privacy", 0).getBoolean("lockedWhatsAppReplies", false),
            "deviceIdle" to activity.getSystemService(PowerManager::class.java).isDeviceIdleMode,
            "assistantActive" to SystemAssistantActivity.active,
            "microphonePermission" to (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED),
            "audioInputTypes" to audio.getDevices(AudioManager.GET_DEVICES_INPUTS).map { it.type },
            "audioOutputTypes" to audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type },
            "audioRouteEvidence" to "AVAILABLE_DEVICES_ONLY; active STT microphone and headset-button dispatch are unverified")
    }
    fun report(): Map<String, Any?> = mapOf("snapshot" to snapshot(),
        "capabilities" to LockScreenPolicy.matrix().map { it.toWire() },
        "lastAttempt" to activity.getSharedPreferences("lock_screen_poc", 0).getString("lastAttempt", null),
        "lastCommand" to activity.getSharedPreferences("lock_screen_poc", 0).getString("lastCommand", null))

    fun record(code: String, source: String, initial: Map<String, Any?>, sttMs: Double? = null, ttsMs: Double? = null) {
        // Metadata only: never persist the user's transcript, personal data, or screen content.
        val data = initial + mapOf("code" to code, "source" to source, "completedAtMs" to System.currentTimeMillis(),
            "completionSnapshot" to snapshot(),
            "sttLatencyMs" to sttMs, "ttsStartupMs" to ttsMs,
            "evidence" to "OBSERVED_APP_CALLBACK; audibility and recognition accuracy require human verification")
        val encoded = JSONObject(data).toString()
        val edit = activity.getSharedPreferences("lock_screen_poc", 0).edit().putString("lastAttempt", encoded)
        if (initial.containsKey("metrics")) edit.putString("lastCommand", encoded)
        edit.apply()
    }
    fun requestRole() {
        // Some role controllers finish a role request without displaying a chooser.
        // Open the user-controlled settings instead; never infer selection from launch.
        for (action in listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) {
            try {
                activity.startActivity(Intent(action))
                return
            } catch (_: android.content.ActivityNotFoundException) { }
            catch (_: SecurityException) { }
        }
        throw android.content.ActivityNotFoundException("Assistant settings unavailable")
    }
}
