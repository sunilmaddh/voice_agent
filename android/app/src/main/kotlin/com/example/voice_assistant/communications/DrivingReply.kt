package com.example.voice_assistant.communications

object DrivingReply {
    // One SMS even for Hindi/Unicode. Multipart delivery is deliberately unsupported.
    const val MAX_LENGTH = 70
    fun validate(value: String): String {
        require(!value.any(Char::isISOControl))
        val text = value.trim()
        require(text.isNotBlank() && text.length <= MAX_LENGTH && !text.any(Char::isISOControl))
        return text
    }
}
