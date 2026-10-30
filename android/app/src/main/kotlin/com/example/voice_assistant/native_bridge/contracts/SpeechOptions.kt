package com.example.voice_assistant.native_bridge.contracts

data class SpeechOptions(val allowSystemRecognition: Boolean = false,
    val voiceName: String? = null, val rate: Float = 1f, val pitch: Float = 1f,
    val offlineTtsOnly: Boolean = true, val preferSystemRecognition: Boolean = false, val languageTag: String = "en-US") {
    init {
        require(languageTag in setOf("en-US", "hi-IN", "kn-IN"))
        require(rate.isFinite() && rate in 0.5f..2f)
        require(pitch.isFinite() && pitch in 0.5f..2f)
        require(voiceName == null || voiceName.length in 1..256)
        require(!preferSystemRecognition || allowSystemRecognition)
    }
    companion object {
        fun fromWire(raw: Any?): SpeechOptions {
            if (raw == null) return SpeechOptions()
            val map = raw as? Map<*, *> ?: throw IllegalArgumentException()
            require(map.keys.all { it in setOf("allowSystemRecognition", "preferSystemRecognition", "voiceName", "rate", "pitch", "offlineTtsOnly", "languageTag") })
            fun bool(key: String, default: Boolean): Boolean = if (map.containsKey(key)) map[key] as? Boolean ?: throw IllegalArgumentException() else default
            fun number(key: String): Float = if (map.containsKey(key)) (map[key] as? Number)?.toFloat() ?: throw IllegalArgumentException() else 1f
            require(map["voiceName"] == null || map["voiceName"] is String)
            return SpeechOptions(bool("allowSystemRecognition", false), map["voiceName"] as String?, number("rate"), number("pitch"), bool("offlineTtsOnly", true), bool("preferSystemRecognition", false), if (map.containsKey("languageTag")) map["languageTag"] as? String ?: throw IllegalArgumentException() else "en-US")
        }
    }
}

class SpeechFailure(val code: String) : Exception(code)
