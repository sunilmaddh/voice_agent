package com.example.voice_assistant.communications

import android.Manifest
import android.app.*
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.telecom.TelecomManager
import android.text.InputType
import android.view.WindowManager
import android.widget.*

/** ACTION_DIAL only populates the field. Placing a call always requires an explicit button press. */
class AikoDialerActivity : Activity() {
    private lateinit var number: EditText
    private lateinit var status: TextView
    private fun unlocked() = !getSystemService(KeyguardManager::class.java).isDeviceLocked
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        status = TextView(this)
        number = EditText(this).apply {
            hint = "Phone number"; inputType = InputType.TYPE_CLASS_PHONE
            setText(intent?.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart?.take(80).orEmpty())
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        fun text(value: String) = content.addView(TextView(this).apply { text = value })
        fun button(title: String, action: () -> Unit) = content.addView(Button(this).apply {
            text = title; setOnClickListener {
                if (!unlocked()) { status.text = "Unlock first."; return@setOnClickListener }
                try { action() } catch (_: Exception) { status.text = "Android could not open or complete this operation." }
            }
        })
        text("Aiko Phone POC\n\nChoosing Aiko as default Phone replaces your incoming and ongoing call screen. Incoming calls offer a reviewed decline-and-SMS reply: I will call you later. Nothing is sent automatically. Physical-device validation is pending. This POC supplies audio-call controls; video/RTT UI is not implemented.")
        content.addView(status)
        button("Choose Aiko as default Phone") {
            if (Build.VERSION.SDK_INT >= 29) {
                val roles = getSystemService(RoleManager::class.java)
                if (roles.isRoleAvailable(RoleManager.ROLE_DIALER)) startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_DIALER), 731)
                else status.text = "Default Phone role is unavailable on this device."
            } else startActivityForResult(Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName), 731)
        }
        button("Allow call notifications") {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 732)
            else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        button("Allow incoming call full-screen display") {
            if (Build.VERSION.SDK_INT >= 34) startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            else status.text = "Android manages incoming call display on this version."
        }
        button("Restore / choose another Phone app") { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
        button("Allow Phone state for SIM selection") { requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 734) }
        button("Allow SMS sending for reviewed call replies") {
            if (AikoInCallService.isDefault(this))
                requestPermissions(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE), 735)
            else status.text = "Choose Aiko as default Phone first."
        }
        button("Show current call controls") { startActivity(Intent(this, PhoneCallActivity::class.java)) }
        content.addView(number)
        button("Call this number") {
            val value = com.example.voice_assistant.actions.PhoneRecipient.number(number.text.toString())
            when {
                value == null -> status.text = "Enter a valid phone number."
                checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED ->
                    requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 733)
                else -> getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", value, null), Bundle())
            }
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }
    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) status.text = "Default Phone: ${AikoInCallService.isDefault(this)}. Notifications: ${getSystemService(NotificationManager::class.java).areNotificationsEnabled()}.\nSMS send permission: ${checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED}\nLast call-reply SMS: ${CallReplySmsSender.lastStatus(this).ifEmpty { "No result yet" }}"
    }
}
