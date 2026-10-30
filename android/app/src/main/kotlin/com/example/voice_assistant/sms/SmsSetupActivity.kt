package com.example.voice_assistant.sms

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class SmsSetupActivity : Activity() {
    override fun onCreate(state: Bundle?) { super.onCreate(state); render() }
    override fun onResume() { super.onResume(); render() }
    private fun unlocked() = !getSystemService(KeyguardManager::class.java).isDeviceLocked
    private fun render() {
        if (!unlocked()) { finish(); return }
        setContentView(ScrollView(this).apply { addView(LinearLayout(this@SmsSetupActivity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32)
            addView(TextView(this@SmsSetupActivity).apply { text = "SMS access\nRead the latest inbox SMS aloud while unlocked. Reply drafts open in your SMS app; you must review and tap Send there. MMS and RCS are not supported.\n\nDefault assistant: ${AndroidSmsPort.isAssistant(this@SmsSetupActivity)}\nSMS permission: ${checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED}\n\nTry: Read my latest SMS. Then: Reply to SMS saying I will call you later." })
            addView(Button(this@SmsSetupActivity).apply { text = "Choose default assistant"; setOnClickListener {
                if (unlocked()) try { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                catch (_: android.content.ActivityNotFoundException) { Toast.makeText(context, "Open Android default apps settings to choose Aiko.", Toast.LENGTH_LONG).show() }
            } })
            addView(Button(this@SmsSetupActivity).apply { text = "Grant SMS read permission"; setOnClickListener {
                if (unlocked() && AndroidSmsPort.isAssistant(this@SmsSetupActivity)) requestPermissions(arrayOf(Manifest.permission.READ_SMS), 601)
                else Toast.makeText(context, "First select Aiko as default assistant while unlocked.", Toast.LENGTH_LONG).show()
            } })
            addView(Button(this@SmsSetupActivity).apply { text = "App permissions / revoke SMS access"; setOnClickListener {
                if (unlocked()) startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
            } })
        }) })
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results); render()
        if (code == 601 && (results.isEmpty() || results[0] != PackageManager.PERMISSION_GRANTED))
            Toast.makeText(this, "SMS access was not granted. Check App permissions; Android or the installer may restrict this permission.", Toast.LENGTH_LONG).show()
    }
}
