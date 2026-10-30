package com.example.voice_assistant.gmail

import android.app.Activity
import android.content.Intent
import com.google.android.gms.auth.api.identity.*
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException

/** Authorization tokens stay in native memory and never cross Flutter channels or logs. */
class GoogleGmailAuthorization(private val activity: Activity) : GmailTokenAccess {
    companion object { const val REQUEST = 4803 }
    private val client = Identity.getAuthorizationClient(activity)
    private val scopes = listOf("https://www.googleapis.com/auth/gmail.readonly", "https://www.googleapis.com/auth/gmail.send")
    private var token: String?
        get() = ProcessGmailToken.token
        set(value) { ProcessGmailToken.token = value }
    private var completion: ((String?) -> Unit)? = null
    override fun accessToken(): String = token ?: throw GmailFailure("GMAIL_AUTH_REQUIRED")
    fun connected() = token != null
    fun connect(done: (String?) -> Unit) {
        clear(); completion = done
        client.authorize(AuthorizationRequest.builder().setRequestedScopes(scopes.map(::Scope)).build())
            .addOnSuccessListener { result ->
                if (completion == null || activity.isFinishing || activity.isDestroyed) return@addOnSuccessListener
                if (result.hasResolution()) {
                    try { activity.startIntentSenderForResult(result.pendingIntent!!.intentSender, REQUEST, null, 0, 0, 0) }
                    catch (_: Exception) { finish("GMAIL_AUTH_FAILED") }
                } else accept(result)
            }.addOnFailureListener { finish(authError(it)) }
    }
    fun onActivityResult(resultCode: Int, data: Intent?) {
        if (completion == null) return
        if (data == null) { finish("GMAIL_AUTH_NO_RESULT"); return }
        // Google can return an ApiException in a non-OK activity result.
        // Parse that result before classifying the return as cancellation.
        try {
            val result = client.getAuthorizationResultFromIntent(data)
            if (resultCode == Activity.RESULT_OK) accept(result)
            else finish("GMAIL_AUTH_NOT_COMPLETED")
        } catch (error: Exception) { finish(authError(error)) }
    }
    private fun authError(error: Exception): String =
        if (error is ApiException) "GMAIL_GOOGLE_STATUS_${error.statusCode}"
        else "GMAIL_AUTH_FAILED"
    private fun accept(result: AuthorizationResult) {
        if (!result.grantedScopes.containsAll(scopes) || result.accessToken.isNullOrBlank()) { finish("GMAIL_SCOPES_REQUIRED"); return }
        token = result.accessToken; finish(null)
    }
    private fun finish(error: String?) { val callback = completion; completion = null; callback?.invoke(error) }
    fun clear() { token = null }
    override fun invalidate() {
        val old = token; token = null
        if (old != null) client.clearToken(ClearTokenRequest.builder().setToken(old).build())
    }
    fun close() { finish("GMAIL_AUTH_INTERRUPTED") }
}
