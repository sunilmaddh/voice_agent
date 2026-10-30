package com.example.voice_assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.example.voice_assistant.native_bridge.contracts.SpeechFailure

interface VoiceAudioFocus {
    fun acquire(onLoss: () -> Unit)
    fun release()
}
class SpeechAudioFocus(context: Context) : VoiceAudioFocus {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var request: AudioFocusRequest? = null
    private var listener: AudioManager.OnAudioFocusChangeListener? = null
    private var generation = 0L
    @Suppress("DEPRECATION")
    override fun acquire(onLoss: () -> Unit) {
        release()
        val currentGeneration = generation
        val callback = AudioManager.OnAudioFocusChangeListener {
            if (it < 0 && generation == currentGeneration) onLoss()
        }
        listener = callback
        val result = if (Build.VERSION.SDK_INT >= 26) {
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(callback).build()
            request = focus
            manager.requestAudioFocus(focus)
        } else manager.requestAudioFocus(callback, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) throw SpeechFailure("AUDIO_FOCUS_DENIED")
    }
    @Suppress("DEPRECATION")
    override fun release() {
        generation++ // Ignore callbacks queued before playback focus was released.
        if (Build.VERSION.SDK_INT >= 26) request?.let(manager::abandonAudioFocusRequest)
        else listener?.let(manager::abandonAudioFocus)
        request = null; listener = null
    }
}
