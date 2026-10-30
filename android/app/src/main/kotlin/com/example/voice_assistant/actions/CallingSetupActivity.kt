package com.example.voice_assistant.actions

import android.Manifest
import android.app.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*

/** Explicit, unlocked opt-in. Never enables itself merely because Android granted permission. */
class CallingSetupActivity : Activity() {
    private lateinit var status: TextView
    private fun unlocked() = !getSystemService(KeyguardManager::class.java).isDeviceLocked
    private val prefs get() = getSharedPreferences("assistant_privacy", 0)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!unlocked()) { finish(); return }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        status = TextView(this).apply { textSize = 18f }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 32)
            addView(TextView(this@CallingSetupActivity).apply {
                text = "Hands-free phone calls, including while locked\n\nAnyone who invokes Aiko can request a call using your SIM. Carrier charges may apply. Phone permission is required; Contacts is optional for names. Android may still require SIM selection. No voice identity verification."
            })
            addView(status)
            addView(Button(this@CallingSetupActivity).apply {
                text = "Incoming call SMS reply / default Phone setup"
                setOnClickListener { if (unlocked()) startActivity(android.content.Intent(this@CallingSetupActivity,
                    com.example.voice_assistant.communications.AikoDialerActivity::class.java)) }
            })
            addView(TextView(this@CallingSetupActivity).apply {
                text = "Voice hang-up (experimental, Android 9+): ends the foreground Telecom call when supported. Anyone who invokes Aiko may end it, including while locked. Android may prevent microphone capture during calls; use your headset or phone button then. Uses a deprecated public API; no emergency-call termination."
            })
            addView(Button(this@CallingSetupActivity).apply { text = "Grant call-control permission"; setOnClickListener {
                if (unlocked() && android.os.Build.VERSION.SDK_INT >= 28)
                    requestPermissions(arrayOf(Manifest.permission.ANSWER_PHONE_CALLS), 712)
                else status.text = "Unlock first. Android 9 or newer is required."
            } })
            addView(Button(this@CallingSetupActivity).apply { text = "Enable voice hang-up"; setOnClickListener {
                if (unlocked() && android.os.Build.VERSION.SDK_INT >= 28 && checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    prefs.edit().putBoolean("voiceHangup", true).apply(); refresh()
                } else status.text = "Unlock and grant call-control permission first."
            } })
            addView(Button(this@CallingSetupActivity).apply { text = "Disable voice hang-up"; setOnClickListener {
                if (unlocked()) { prefs.edit().putBoolean("voiceHangup", false).apply(); refresh() }
            } })
            addView(Button(this@CallingSetupActivity).apply { text = "Grant Phone and Contacts access"; setOnClickListener {
                if (unlocked()) requestPermissions(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS), 711)
            } })
            addView(Button(this@CallingSetupActivity).apply { text = "Enable hands-free calls"; setOnClickListener {
                if (unlocked() && checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    prefs.edit().putBoolean("handsFreeCalls", true).apply(); refresh()
                } else status.text = "Unlock and grant Phone permission first."
            } })
            addView(Button(this@CallingSetupActivity).apply { text = "Disable hands-free calls"; setOnClickListener {
                if (unlocked()) { prefs.edit().putBoolean("handsFreeCalls", false).apply(); refresh() }
            } })
        }
        setContentView(ScrollView(this).apply { addView(content) })
        refresh()
    }
    private fun refresh() { status.text = "Hands-free calls: ${prefs.getBoolean("handsFreeCalls", false)}. Voice hang-up: ${prefs.getBoolean("voiceHangup", false)}" }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code == 711) status.text = "Phone permission: ${checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED}. Tap Enable to opt in."
        if (code == 712) status.text = "Call-control permission: ${checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED}. Tap Enable voice hang-up to opt in."
    }
}
