package com.example.voice_assistant.actions

import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import com.example.voice_assistant.intent.CommandValidator
import com.example.voice_assistant.native_bridge.contracts.*

/** Only foreground Maps launch; no arbitrary model text or arbitrary app intents. */
class MapsActionExecutor(private val activity: Activity, private val isForeground: () -> Boolean) : ActionExecutor {
    override suspend fun execute(action: AssistantAction): ActionResult {
        if (action.name != action.intent.kind.wireName || action.intent.kind !in setOf(IntentKind.navigate, IntentKind.openMaps) ||
            CommandValidator().validate(action.intent) !is CapabilityResult.Available)
            return ActionResult(ActionStatus.failed, "INVALID_MAPS_ACTION", "The Maps command could not be validated.")
        if (!isForeground() || activity.isFinishing || activity.isDestroyed)
            return ActionResult(ActionStatus.failed, "APP_NOT_FOREGROUND", "Open Aiko and try again.")
        if (activity.getSystemService(KeyguardManager::class.java).isDeviceLocked)
            return ActionResult(ActionStatus.requiresConfirmation, "UNLOCK_REQUIRED", "Unlock your phone to open Google Maps.")
        return try {
            val launch = if (action.intent.kind == IntentKind.navigate) {
                val destination = action.intent.parameters.single { it.name == "destination" }.value as String
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}"))
                    .setPackage("com.google.android.apps.maps")
            } else activity.packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
            if (launch == null || launch.resolveActivity(activity.packageManager) == null)
                ActionResult(ActionStatus.unsupported, "MAPS_UNAVAILABLE", "Google Maps is not installed or enabled on this device.")
            else {
                activity.startActivity(launch)
                ActionResult(ActionStatus.succeeded, "MAPS_LAUNCH_REQUESTED", "Google Maps launch requested. Check Maps for the destination and route.")
            }
        } catch (_: ActivityNotFoundException) {
            ActionResult(ActionStatus.unsupported, "MAPS_UNAVAILABLE", "Google Maps is not available on this device.")
        } catch (_: SecurityException) {
            ActionResult(ActionStatus.failed, "MAPS_LAUNCH_DENIED", "Android did not allow Google Maps to open.")
        }
    }
}
