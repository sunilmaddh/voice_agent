package com.example.voice_assistant.lockscreen

import android.os.Bundle
import android.content.Intent
import android.os.Build
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class AikoSessionService : VoiceInteractionSessionService() {
    companion object {
        const val PRESENTATION = "aiko.presentation"
        private var presentation: Pair<String, () -> Unit>? = null
        fun completed(id: String?) {
            val pending = presentation ?: return
            if (pending.first != id) return
            presentation = null
            pending.second()
        }
    }
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = object : VoiceInteractionSession(this) {
        override fun onCreate() {
            super.onCreate()
            if (Build.VERSION.SDK_INT >= 26) setUiEnabled(false)
            setKeepAwake(false)
        }
        override fun onPrepareShow(args: Bundle?, showFlags: Int) {
            super.onPrepareShow(args, showFlags)
            if (Build.VERSION.SDK_INT >= 26) setUiEnabled(false)
        }
        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            // Official assistant-activity presentation keeps feature modules' Activity ports reusable.
            val intent = Intent(this@AikoSessionService, SystemAssistantActivity::class.java).setAction(Intent.ACTION_ASSIST)
            val id = java.util.UUID.randomUUID().toString()
            intent.putExtra(PRESENTATION, id)
            presentation = id to { hide() }
            args?.getString(com.example.voice_assistant.voice.wakeword.SystemWakeHandoff.EXTRA)?.let {
                intent.putExtra(com.example.voice_assistant.voice.wakeword.SystemWakeHandoff.EXTRA, it)
            }
            try {
                if (Build.VERSION.SDK_INT >= 26) startAssistantActivity(intent)
                else this@AikoSessionService.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: RuntimeException) { completed(id) }
        }
        override fun onHide() { setKeepAwake(false); super.onHide() }
    }
}
