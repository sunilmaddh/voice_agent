package com.example.voice_assistant.communications

data class IncomingReplyTarget(val id: String, val address: String, val ringing: Boolean,
    val addressAllowed: Boolean, val supportsText: Boolean)

interface IncomingReplyPort {
    fun restriction(id: String): String? = null
    fun defaultDialer(): Boolean
    fun unlocked(): Boolean
    fun target(id: String): IncomingReplyTarget?
    fun sendReply(id: String, text: String)
}

/** Bound to a specific call and single-use; submits one SMS, with decline deferred to its sent receipt. */
class IncomingCallReply(private val port: IncomingReplyPort, private val now: () -> Long = System::currentTimeMillis) {
    data class Review(val target: IncomingReplyTarget, val expires: Long)
    private var review: Review? = null
    private val attempted = mutableSetOf<String>()
    fun prepare(id: String): Review? {
        review = null
        if (!port.defaultDialer() || !port.unlocked() || id in attempted) return null
        val target = port.target(id)?.takeIf(::eligible) ?: return null
        return Review(target, now() + 60000).also { review = it }
    }
    fun isCurrent(value: Review): Boolean = review == value && now() < value.expires &&
        port.defaultDialer() && port.unlocked() && port.target(value.target.id) == value.target &&
        value.target.id !in attempted
    fun cancel() { review = null }
    fun confirmSpoken(value: Review, transcript: String, isFinal: Boolean): String =
        when (com.example.voice_assistant.notifications.ReplyVoiceDecision.parse(transcript, isFinal)) {
            com.example.voice_assistant.notifications.ReplyVoiceDecision.SEND -> confirm(value)
            com.example.voice_assistant.notifications.ReplyVoiceDecision.CANCEL -> {
                cancel(); "CALL_REPLY_CANCELLED"
            }
            com.example.voice_assistant.notifications.ReplyVoiceDecision.UNRECOGNIZED -> "CALL_REPLY_NOT_CONFIRMED"
        }
    fun forget(id: String) { if (review?.target?.id == id) cancel(); attempted.remove(id) }
    fun confirm(value: Review): String {
        if (review != value) return "CALL_REPLY_REVIEW_REQUIRED"
        review = null
        if (now() >= value.expires || !port.defaultDialer() || !port.unlocked()) return "CALL_REPLY_EXPIRED_OR_RESTRICTED"
        val current = port.target(value.target.id)
        if (current == null || current != value.target || !eligible(current)) return "CALL_REPLY_TARGET_CHANGED"
        port.restriction(current.id)?.let { return it }
        if (!attempted.add(current.id)) return "CALL_REPLY_ALREADY_REQUESTED"
        return try {
            port.sendReply(current.id, MESSAGE)
            "CALL_REPLY_SMS_PENDING"
        } catch (_: Exception) {
            // The call might already have been rejected. An automatic retry could send twice.
            "CALL_REPLY_OUTCOME_UNKNOWN"
        }
    }
    companion object {
        const val MESSAGE = "I will call you later."
        fun eligible(target: IncomingReplyTarget): Boolean = target.ringing && target.addressAllowed &&
            target.supportsText && Regex("\\+?[0-9]{7,15}").matches(target.address)
    }
}
