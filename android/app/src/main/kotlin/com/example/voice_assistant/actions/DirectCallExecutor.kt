package com.example.voice_assistant.actions

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import com.example.voice_assistant.intent.CommandValidator
import com.example.voice_assistant.native_bridge.contracts.*
import kotlinx.coroutines.*

class DirectCallExecutor(private val activity: Activity, private val foreground: () -> Boolean) : ActionExecutor {
    private fun allowed() = activity.getSharedPreferences("assistant_privacy", 0).getBoolean("handsFreeCalls", false)
    override suspend fun execute(action: AssistantAction): ActionResult {
        fun denied(code: String, message: String) = ActionResult(ActionStatus.failed, code, message)
        if (action.name != "CALL_PHONE" || action.intent.kind != IntentKind.callPhone || CommandValidator().validate(action.intent) !is CapabilityResult.Available)
            return denied("INVALID_CALL", "Say one exact contact name or phone number.")
        if (!foreground() || !allowed()) return denied("HANDS_FREE_CALLING_DISABLED", "Enable hands-free calling in Aiko settings while unlocked.")
        if (activity.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            return denied("CALL_PERMISSION_REQUIRED", "Unlock Aiko and grant Phone permission in hands-free calling settings.")
        val recipient = action.intent.parameters.single().value as String
        var number = PhoneRecipient.number(recipient)
        if (number == null) {
            if (activity.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
                return denied("CONTACTS_PERMISSION_REQUIRED", "Grant Contacts permission while unlocked, or say the phone number.")
            val numbers = try { withContext(Dispatchers.IO) {
                val matches = mutableSetOf<String>()
                activity.contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.NUMBER),
                    "${Phone.DISPLAY_NAME} = ? COLLATE NOCASE", arrayOf(recipient), null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        ensureActive()
                        PhoneRecipient.number(cursor.getString(0).orEmpty())?.let(matches::add)
                        if (matches.size > 1) break
                    }
                } ?: return@withContext null
                matches
            } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (numbers == null) return denied("CONTACT_LOOKUP_FAILED", "Contacts could not be read. No call requested.")
            if (numbers.size != 1) return denied("CALL_RECIPIENT_UNRESOLVED", "No unique number matches. Say the phone number or a more specific saved contact name.")
            number = numbers.single()
        }
        if (!DirectCallPolicy.allowedNumber(number)) return denied("CALL_NUMBER_RESTRICTED", "Use the system phone app for short numbers or emergency calls.")
        @Suppress("DEPRECATION")
        if (PhoneNumberUtils.isEmergencyNumber(number)) return denied("CALL_NUMBER_RESTRICTED", "Use the system emergency dialer.")
        currentCoroutineContext().ensureActive()
        if (!foreground() || !allowed()) return denied("CALL_CANCELLED", "Assistant session ended or calling was disabled. No call requested.")
        return try {
            val telecom = activity.getSystemService(TelecomManager::class.java)
                ?: return denied("TELECOM_UNAVAILABLE", "Phone calling is unavailable on this device.")
            telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
            ActionResult(ActionStatus.succeeded, "CALL_REQUESTED", "Call requested through Android. Connection is not verified.",
                mapOf("androidMechanism" to "TelecomManager.placeCall", "unlockRequired" to false,
                    "userInteractionRequired" to "DEVICE_DEPENDENT", "connectionVerified" to false, "outcome" to "REQUEST_ONLY"))
        } catch (_: SecurityException) { denied("CALL_PERMISSION_DENIED", "Android denied the call request. Check Phone permission while unlocked.") }
        catch (_: Exception) { denied("CALL_REQUEST_FAILED", "The call request could not be verified. Check the phone app before trying again.") }
    }
}

object DirectCallPolicy {
    fun allowedNumber(number: String) = Regex("\\+?[0-9]{7,15}").matches(number)
}
