package com.example.voice_assistant

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import com.example.voice_assistant.native_bridge.AssistantChannels

class MainActivity : FlutterActivity() {
    private var channels: AssistantChannels? = null
    private var documents: com.example.voice_assistant.document_ai.DocumentChannels? = null
    private var bills: com.example.voice_assistant.notifications.BillChannels? = null
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        channels?.close()
        channels = AssistantChannels(this, flutterEngine.dartExecutor.binaryMessenger)
        documents?.close()
        documents = com.example.voice_assistant.document_ai.DocumentChannels(this, flutterEngine.dartExecutor.binaryMessenger)
        bills?.close()
        bills = com.example.voice_assistant.notifications.BillChannels(this, flutterEngine.dartExecutor.binaryMessenger)
    }
    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        channels?.close()
        channels = null
        documents?.close(); documents = null
        bills?.close(); bills = null
        super.cleanUpFlutterEngine(flutterEngine)
    }
    override fun onResume() { super.onResume(); channels?.resume(); documents?.resume(); bills?.resume() }
    override fun onPause() { bills?.pause(); documents?.pause(); channels?.pause(); super.onPause() }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == com.example.voice_assistant.document_ai.DocumentChannels.CAMERA) documents?.cameraFinished(resultCode)
        if (requestCode == com.example.voice_assistant.gmail.GoogleGmailAuthorization.REQUEST)
            channels?.gmailAuthorizationResult(resultCode, data)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AssistantChannels.CALENDAR_REQUEST) channels?.calendarPermissionResult()
        if (requestCode == AssistantChannels.WAKE_REQUEST) channels?.wakePermissionResult()
        if (requestCode == AssistantChannels.MICROPHONE_REQUEST) channels?.permissionResult(
            grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
}
