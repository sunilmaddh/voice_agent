package com.example.voice_assistant.actions

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.widget.TextView
import kotlinx.coroutines.*

/** Foreground-only contact selection; no automatic call and no persisted contact data. */
class ContactDialActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var status: TextView
    private fun locked() = getSystemService(KeyguardManager::class.java).isDeviceLocked
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        status = TextView(this).apply { textSize = 20f; setPadding(32, 64, 32, 32) }
        setContentView(status)
        if (locked()) { status.text = "Unlock and repeat the call command."; return }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Contact access is needed to find the requested person. You can also call by number without this permission."
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 710)
        } else lookup()
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code == 710 && grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) lookup()
        else status.text = "Contact access denied. Go back and say Call followed by a phone number."
    }
    private fun lookup() {
        val name = intent.getStringExtra("recipient") ?: return
        if (!PhoneRecipient.valid(name) || locked()) return
        status.text = "Finding the exact contact name…"
        scope.launch {
            try {
                val choices = withContext(Dispatchers.IO) {
                    val rows = mutableListOf<Pair<String, String>>()
                    contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER),
                        "${Phone.DISPLAY_NAME} = ? COLLATE NOCASE", arrayOf(name), null)?.use { cursor ->
                        while (cursor.moveToNext() && rows.size < 50) {
                            val number = PhoneRecipient.number(cursor.getString(1).orEmpty()) ?: continue
                            rows.add(cursor.getString(0).orEmpty() to number)
                        }
                    }
                    rows.distinct()
                }
                if (isFinishing || locked()) return@launch
                if (choices.isEmpty()) { status.text = "No exact contact match. Go back and say the full saved name or phone number."; return@launch }
                status.text = "Select the recipient, then tap Call in the phone dialer."
                AlertDialog.Builder(this@ContactDialActivity).setTitle("Choose number to call")
                    .setItems(choices.map { "${it.first}: ${it.second}" }.toTypedArray()) { _, index ->
                        if (!locked()) try {
                            startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", choices[index].second, null)))
                            finish()
                        } catch (_: Exception) { status.text = "Android could not open the dialer. No call was placed." }
                    }.setNegativeButton("Cancel") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { status.text = "Contacts could not be read. Go back and call by number." }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
