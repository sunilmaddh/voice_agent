package com.example.voice_assistant.gmail

interface GmailTokenAccess { fun accessToken(): String; fun invalidate() }

/** No disk persistence. Authentication UI remains in the unlocked Flutter activity. */
object ProcessGmailToken : GmailTokenAccess {
    @Volatile var token: String? = null
    override fun accessToken() = token ?: throw GmailFailure("GMAIL_AUTH_REQUIRED")
    override fun invalidate() { token = null }
}
