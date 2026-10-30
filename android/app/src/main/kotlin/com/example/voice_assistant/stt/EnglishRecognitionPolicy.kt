package com.example.voice_assistant.stt

import java.util.Locale

/** An installed English variant is usable; downloadable/pending models are not installed. */
object EnglishRecognitionPolicy {
    fun selectInstalled(languages: List<String>, requested: String = "en-US"): String? = languages
        .map { Locale.forLanguageTag(it.replace('_', '-')) }
        .filter { it.language == Locale.forLanguageTag(requested).language }
        .map { it.toLanguageTag() }.distinct()
        .sortedWith(compareBy<String> { if (it == requested) 0 else 1 }.thenBy { it })
        .firstOrNull()

    fun canRetrySystem(code: String, allowed: Boolean, available: Boolean) = allowed && available &&
        code in setOf("STT_UNAVAILABLE", "STT_LANGUAGE_UNSUPPORTED", "STT_LANGUAGE_NOT_INSTALLED", "STT_SERVER_DISCONNECTED")
}
