package com.example.voice_assistant.communications

/** A sent receipt may decline only the reviewed call, never a later call. */
class SmsReplyCompletion(private val eligible: () -> Boolean, private val decline: () -> Unit) {
    private var consumed = false
    fun complete(sent: Boolean): String {
        if (consumed) return "CALL_REPLY_SMS_DUPLICATE_IGNORED"
        consumed = true
        if (!sent) return "CALL_REPLY_SMS_FAILED"
        return try {
            if (!eligible()) "CALL_REPLY_SMS_SENT_CALL_UNCHANGED"
            else {
                decline()
                "CALL_REPLY_SMS_SENT_DECLINE_REQUESTED"
            }
        } catch (_: Exception) { "CALL_REPLY_SMS_SENT_DECLINE_FAILED" }
    }
}
