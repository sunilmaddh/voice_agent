package com.example.voice_assistant.communications

/** Short replies are meaningful only inside one prompted, still-ringing call session. */
enum class IncomingCallVoiceDecision {
    ACCEPT, CUT_AND_TEXT, CANCEL, UNRECOGNIZED;

    companion object {
        const val PROMPT = "Do you want to accept the call, or cut it and send your saved message? " +
            "After the beep, say accept or cut the call."
        const val RETRY_PROMPT = "I didn't catch a clear choice. After the beep, say accept or cut the call."

        fun shouldRetry(transcript: String, isFinal: Boolean, attempt: Int) =
            attempt == 0 && isFinal && parse(transcript, true) == UNRECOGNIZED

        fun parse(transcript: String, isFinal: Boolean): IncomingCallVoiceDecision {
            if (!isFinal) return UNRECOGNIZED
            val text = transcript.lowercase(java.util.Locale.ROOT).trim()
                .replace(Regex("[.!?,]+$"), "").trim().replace(Regex("\\s+"), " ")
                .replace(Regex("[,;:]"), " ").replace(Regex("\\s+"), " ")
                .replace(Regex("^(?:hey (?:aiko|indo)[, ]+)?(?:ok(?:ay)?[, ]+)?(?:please )?"), "")
                .replace(Regex("(?: please)?(?: thank you| thanks)?$"), "").trim()
            return when (text) {
                "accept", "accept call", "accept the call", "answer", "answer call", "answer the call",
                "yes accept", "yes accept the call", "accept it", "answer it", "pick up", "pick up the call",
                "yes answer the call", "yes answer", "yes accept call" -> ACCEPT
                "cut", "cut call", "cut the call", "decline", "decline call", "decline the call",
                "reject", "reject call", "reject the call", "please cut the call", "cut the call and send message",
                "cut the call and send a message", "cut it", "decline it", "reject it", "hang up",
                "hang up the call", "disconnect the call", "cut the call send message",
                "cut the call send a message", "cut the call and text", "yes cut the call" -> CUT_AND_TEXT
                "cancel", "stop", "keep ringing", "let it ring" -> CANCEL
                else -> UNRECOGNIZED
            }
        }
    }
}

interface IncomingCallVoicePort {
    fun isCurrent(): Boolean
    fun answer()
    fun cutAndText(): String
}

object PromptedCallReplyPolicy {
    fun allowed(body: String, saved: String, voiceRequested: Boolean, voiceEnabled: Boolean): Boolean =
        voiceRequested && voiceEnabled && body == saved &&
            runCatching { DrivingReply.validate(body) == body }.getOrDefault(false)
}

/** Consumed before effects; a stale or duplicate final result never acts on another call. */
class IncomingCallVoiceSession(private val port: IncomingCallVoicePort) {
    private var consumed = false

    fun respond(transcript: String, isFinal: Boolean): String {
        if (!isFinal) return "CALL_VOICE_FINAL_REQUIRED"
        if (consumed) return "CALL_VOICE_ALREADY_CONSUMED"
        consumed = true
        if (!port.isCurrent()) return "CALL_VOICE_CALL_CHANGED"
        return when (IncomingCallVoiceDecision.parse(transcript, true)) {
            IncomingCallVoiceDecision.ACCEPT -> {
                port.answer()
                "CALL_VOICE_ANSWER_REQUESTED"
            }
            IncomingCallVoiceDecision.CUT_AND_TEXT -> port.cutAndText()
            IncomingCallVoiceDecision.CANCEL -> "CALL_VOICE_CANCELLED_CALL_UNCHANGED"
            IncomingCallVoiceDecision.UNRECOGNIZED -> "CALL_VOICE_UNRECOGNIZED_CALL_UNCHANGED"
        }
    }
}
