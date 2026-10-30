package com.example.voice_assistant.gmail

import com.google.gson.JsonObject
import java.nio.charset.Charset
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Pure MIME boundary: incoming content is data; outgoing headers never accept raw dictated text. */
@OptIn(ExperimentalEncodingApi::class)
object GmailMime {
    fun encodeUrl(bytes: ByteArray): String = Base64.UrlSafe.encode(bytes).trimEnd('=')
    fun decodeUrl(text: String): ByteArray = Base64.UrlSafe.decode(text.padEnd((text.length + 3) / 4 * 4, '='))
    fun address(raw: String): String {
        if (raw.any(Char::isISOControl) || raw.length > 512) throw GmailFailure("GMAIL_UNSAFE_RECIPIENT")
        val value = Regex("^[^<>]*<([^<>]+)>$").matchEntire(raw.trim())?.groupValues?.get(1) ?: raw.trim()
        if (!Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+").matches(value))
            throw GmailFailure("GMAIL_UNSAFE_RECIPIENT")
        return value
    }
    fun safeMessageId(value: String): String = value.takeIf {
        it.length <= 512 && Regex("<[^<>\\s\\p{Cntrl}]+@[^<>\\s\\p{Cntrl}]+>").matches(it)
    } ?: throw GmailFailure("GMAIL_REPLY_HEADERS_UNAVAILABLE")

    fun replyRaw(account: String, original: EmailMessage, body: String): String {
        if (original.subject.length > 1000) throw GmailFailure("GMAIL_REPLY_HEADERS_UNAVAILABLE")
        if (body.isBlank() || body.length > 1000 || body.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' })
            throw GmailFailure("GMAIL_INVALID_REPLY")
        val to = address(original.replyTo.ifBlank { original.sender })
        val parent = safeMessageId(original.messageId)
        val subject = original.subject.let { if (it.startsWith("Re:", true)) it else "Re: $it" }
        // Fold encoded words well below RFC line limits, preserving UTF-8 code points.
        val encodedSubject = subject.codePoints().toArray().toList().chunked(10).joinToString("\r\n ") {
            "=?UTF-8?B?${Base64.Default.encode(String(it.toIntArray(), 0, it.size).toByteArray(Charsets.UTF_8))}?="
        }
        val content = Base64.Default.encode(body.toByteArray(Charsets.UTF_8)).chunked(76).joinToString("\r\n")
        return encodeUrl(("From: ${address(account)}\r\nTo: $to\r\nSubject: $encodedSubject\r\n" +
            "In-Reply-To: $parent\r\nReferences: $parent\r\nMIME-Version: 1.0\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n$content\r\n").toByteArray(Charsets.UTF_8))
    }
    private fun headerText(value: String): String = Regex("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=").replace(value.replace(Regex("\\?=\\s+(?==\\?)"), "?=")) { m ->
        try {
            val bytes = if (m.groupValues[2].equals("b", true)) Base64.Default.decode(m.groupValues[3]) else {
                val text = m.groupValues[3].replace('_', ' '); val result = java.io.ByteArrayOutputStream(); var i = 0
                while (i < text.length) {
                    if (text[i] == '=' && i + 2 < text.length) { result.write(text.substring(i + 1, i + 3).toInt(16)); i += 3 }
                    else { result.write(text[i].code); i++ }
                }
                result.toByteArray()
            }
            String(bytes, Charset.forName(m.groupValues[1]))
        } catch (_: Exception) { m.value }
    }.replace(Regex("\\s+"), " ")

    fun parse(json: JsonObject, htmlToText: (String) -> String): EmailMessage {
        val payload = json.getAsJsonObject("payload") ?: throw GmailFailure("GMAIL_INVALID_MESSAGE")
        val headers = payload.getAsJsonArray("headers")?.associate { item ->
            val h = item.asJsonObject; h["name"].asString.lowercase(java.util.Locale.US) to h["value"].asString
        }.orEmpty()
        fun textPart(part: JsonObject, depth: Int = 0): String {
            if (depth > 12 || part["filename"]?.asString?.isNotEmpty() == true) return ""
            val children = part.getAsJsonArray("parts")?.map { it.asJsonObject }.orEmpty()
            if (children.isNotEmpty()) {
                if (part["mimeType"]?.asString == "multipart/alternative") {
                    val plain = children.firstOrNull { it["mimeType"]?.asString == "text/plain" }?.let { textPart(it, depth + 1) }
                    return plain?.takeIf { it.isNotBlank() } ?: children.joinToString("\n") { textPart(it, depth + 1) }
                }
                return children.joinToString("\n") { textPart(it, depth + 1) }
            }
            val type = part["mimeType"]?.asString ?: return ""
            if (type !in setOf("text/plain", "text/html")) return ""
            val data = part.getAsJsonObject("body")?.get("data")?.asString ?: return ""
            val contentType = part.getAsJsonArray("headers")?.firstOrNull {
                it.asJsonObject["name"].asString.equals("Content-Type", true)
            }?.asJsonObject?.get("value")?.asString.orEmpty()
            val charset = Regex("charset=[\"']?([^;\\s\"']+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1) ?: "UTF-8"
            val decoded = String(decodeUrl(data), runCatching { Charset.forName(charset) }.getOrDefault(Charsets.UTF_8))
            return if (type == "text/html") htmlToText(decoded) else decoded
        }
        val body = textPart(payload).replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), "").trim()
        return EmailMessage(json["id"].asString, json["threadId"].asString,
            headerText(headers["from"].orEmpty()).take(512), headerText(headers["subject"].orEmpty()),
            body.take(12000), headers["reply-to"] ?: headers["from"].orEmpty(), headers["message-id"].orEmpty(), body.length > 12000)
    }
}
