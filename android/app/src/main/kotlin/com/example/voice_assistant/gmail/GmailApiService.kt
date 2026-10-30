package com.example.voice_assistant.gmail

import android.text.Html
import com.google.gson.*
import kotlinx.coroutines.*
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

class GmailApiService(private val authorization: GmailTokenAccess) : GmailService {
    var account: String? = null
        private set
    fun clear() { account = null }
    suspend fun profile(): String {
        val email = request("profile")["emailAddress"]?.asString ?: throw GmailFailure("GMAIL_PROFILE_FAILED")
        account = GmailMime.address(email); return email
    }
    private fun id(value: String) = value.takeIf { Regex("[a-zA-Z0-9_-]{1,200}").matches(it) }
        ?: throw GmailFailure("GMAIL_INVALID_MESSAGE")
    override suspend fun list(scope: String): List<EmailMessage> {
        val query = when (scope) {
            "IMPORTANT" -> "in:inbox is:important"
            "RELEVANT" -> "in:inbox is:important is:unread"
            else -> "in:inbox"
        }
        val count = if (scope == "LATEST") 1 else 5
        val response = request("messages?maxResults=$count&q=${URLEncoder.encode(query, "UTF-8")}")
        val items = response.getAsJsonArray("messages") ?: return emptyList()
        return items.map { read(it.asJsonObject["id"].asString) }
    }
    override suspend fun read(id: String): EmailMessage = withContext(Dispatchers.IO) {
        GmailMime.parse(request("messages/${id(id)}?format=full")) { html ->
            Html.fromHtml(html.replace(Regex("<(script|style)\\b[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), ""), Html.FROM_HTML_MODE_LEGACY).toString()
        }
    }
    override suspend fun reply(message: EmailMessage, text: String): String {
        val sender = account ?: throw GmailFailure("GMAIL_AUTH_REQUIRED")
        val raw = GmailMime.replyRaw(sender, message, text)
        val payload = JsonObject().apply { addProperty("raw", raw); addProperty("threadId", id(message.threadId)) }
        val response = request("messages/send", payload)
        return response["id"]?.asString?.takeIf { it.isNotBlank() } ?: throw GmailFailure("GMAIL_SEND_OUTCOME_UNKNOWN")
    }
    private suspend fun request(path: String, payload: JsonObject? = null): JsonObject = withContext(Dispatchers.IO) {
        val token = authorization.accessToken()
        currentCoroutineContext().ensureActive()
        val connection = URL("https://gmail.googleapis.com/gmail/v1/users/me/$path").openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 15000; connection.readTimeout = 15000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            if (payload != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status == 401) { authorization.invalidate(); account = null; throw GmailFailure("GMAIL_AUTH_REQUIRED") }
            if (status !in 200..299) throw GmailFailure(when {
                payload != null && (status >= 500 || status == 408) -> "GMAIL_SEND_OUTCOME_UNKNOWN"
                status == 403 -> "GMAIL_ACCESS_DENIED"
                status == 429 -> "GMAIL_RATE_LIMITED"
                status == 404 -> "GMAIL_MESSAGE_UNAVAILABLE"
                else -> "GMAIL_API_FAILED"
            })
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    if (out.size() + n > 1048576) throw GmailFailure(if (payload == null) "GMAIL_MESSAGE_TOO_LARGE" else "GMAIL_SEND_OUTCOME_UNKNOWN")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
        } catch (e: GmailFailure) { throw e }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { throw GmailFailure(if (payload == null) "GMAIL_NETWORK_FAILED" else "GMAIL_SEND_OUTCOME_UNKNOWN") }
        finally { connection.disconnect() }
    }
}
