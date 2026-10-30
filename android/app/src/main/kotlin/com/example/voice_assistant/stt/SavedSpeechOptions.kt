package com.example.voice_assistant.stt
import android.content.Context
import com.example.voice_assistant.native_bridge.contracts.SpeechOptions

object SavedSpeechOptions {
    fun fromConsent(allowSystem: Boolean, preferSystem: Boolean) = SpeechOptions(
        allowSystemRecognition = allowSystem, preferSystemRecognition = allowSystem && preferSystem)
    fun read(context: Context): SpeechOptions {
        val prefs = context.getSharedPreferences("speech_preferences", Context.MODE_PRIVATE)
        return fromConsent(prefs.getBoolean("allowSystemRecognition", false), prefs.getBoolean("preferSystemRecognition", false)).copy(
            languageTag = prefs.getString("languageTag", "en-US").takeIf { it in setOf("en-US", "hi-IN", "kn-IN") } ?: "en-US")
    }
}
