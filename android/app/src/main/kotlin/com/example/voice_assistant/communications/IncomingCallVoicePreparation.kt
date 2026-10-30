package com.example.voice_assistant.communications

import android.content.Context
import android.telecom.Call
import android.telecom.TelecomManager
import com.example.voice_assistant.native_bridge.contracts.SpeechFailure
import kotlinx.coroutines.delay

/** Called only after voice interaction is invoked, never by the idle wake-word loop. */
object IncomingCallVoicePreparation {
    @Suppress("DEPRECATION")
    suspend fun prepare(context: Context) {
        val service = AikoInCallService.instance ?: return
        if (!AikoInCallService.isDefault(context)) return
        val states = service.live.values.map { it.state }
        // Do not interfere with an established call (including call waiting).
        if (Call.STATE_RINGING !in states || states.any {
                it != Call.STATE_RINGING && it != Call.STATE_DISCONNECTED
            }) return
        try {
            service.silenceIncomingCalls()
            context.getSystemService(TelecomManager::class.java).silenceRinger()
        } catch (_: SecurityException) {
            throw SpeechFailure("CALL_RINGTONE_SILENCE_DENIED")
        }
        android.util.Log.i("AssistantPOC", "{\"code\":\"CALL_RINGTONE_SILENCE_REQUESTED\"}")
        // Telecom stops ringtone playback asynchronously. Focus can still be refused;
        // never force an audio mode or proceed as if capture succeeded in that case.
        delay(300)
    }
}
