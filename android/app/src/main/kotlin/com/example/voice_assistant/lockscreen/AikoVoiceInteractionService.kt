package com.example.voice_assistant.lockscreen

import android.content.Intent
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import com.example.voice_assistant.voice.wakeword.WakeWordService

class AikoVoiceInteractionService : VoiceInteractionService() {
    companion object {
        private var ready: AikoVoiceInteractionService? = null
        fun showWakeSession(ticket: String): Boolean {
            val service = ready ?: return false
            return try {
                service.showSession(android.os.Bundle().apply {
                    putString(com.example.voice_assistant.voice.wakeword.SystemWakeHandoff.EXTRA, ticket)
                }, 0)
                true
            } catch (_: RuntimeException) { false }
        }
    }
    override fun onReady() {
        super.onReady()
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
        ready = this
    }
    override fun onShutdown() {
        if (ready === this) ready = null
        stopService(Intent(this, WakeWordService::class.java))
        super.onShutdown()
    }
    override fun onDestroy() { if (ready === this) ready = null; super.onDestroy() }
    override fun onLaunchVoiceAssistFromKeyguard() {
        startActivity(Intent(this, SystemAssistantActivity::class.java)
            .setAction(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
