package com.example.voice_assistant.intent

import com.example.voice_assistant.native_bridge.contracts.*

/** Independently validates DTOs too, so future model output cannot bypass the schema. */
class CommandValidator(private val now: () -> Long = System::currentTimeMillis) : IntentValidator {
    override fun validate(intent: AssistantIntent): CapabilityResult<AssistantAction> {
        fun rejected(reason: String) = CapabilityResult.Unavailable(reason)
        if ((intent.confidence ?: 0.0) < .9) return rejected("LOW_CONFIDENCE")
        val shape: Map<String, ParameterType> = when(intent.kind) {
            IntentKind.drivingModeOn, IntentKind.drivingModeOff -> emptyMap()
            IntentKind.replyToCall -> mapOf("body" to ParameterType.text)
            IntentKind.readSms -> mapOf("scope" to ParameterType.text)
            IntentKind.replySms -> mapOf("body" to ParameterType.text)
            IntentKind.endCall -> emptyMap()
            IntentKind.callPhone -> mapOf("recipient" to ParameterType.text)
            IntentKind.navigate -> mapOf("destination" to ParameterType.text)
            IntentKind.googleSearch -> mapOf("query" to ParameterType.text)
            IntentKind.createReminder -> mapOf("title" to ParameterType.text, "dueEpochMs" to ParameterType.number, "timeZoneId" to ParameterType.text)
            IntentKind.queryCalendar -> mapOf("startEpochMs" to ParameterType.number, "endEpochMs" to ParameterType.number, "timeZoneId" to ParameterType.text)
            IntentKind.createCalendarEvent -> mapOf("title" to ParameterType.text, "startEpochMs" to ParameterType.number, "endEpochMs" to ParameterType.number, "timeZoneId" to ParameterType.text)
            IntentKind.openMaps, IntentKind.openCalendar, IntentKind.stopNavigation -> emptyMap()
            IntentKind.readNotification, IntentKind.readWhatsApp, IntentKind.readEmail -> mapOf("scope" to ParameterType.text)
            // Messaging requires identity resolution, thread context, channel and confirmation.
            IntentKind.replyWhatsApp -> mapOf("body" to ParameterType.text) // Preparation only; separate review token required to dispatch.
            IntentKind.sendMessage -> mapOf("recipientName" to ParameterType.text, "body" to ParameterType.text, "channel" to ParameterType.text)
            // REPLY_EMAIL only prepares a review draft; sending requires a separate single-use UI confirmation.
            IntentKind.replyEmail -> mapOf("body" to ParameterType.text)
            IntentKind.documentQuery, IntentKind.generalQuery, IntentKind.unknown -> return rejected("NO_EXECUTABLE_COMMAND")
        }
        if (intent.parameters.associate { it.name to it.type } != shape) return rejected("INVALID_PARAMETERS")
        val values = intent.parameters.associate { it.name to it.value }
        if (intent.kind == IntentKind.replyToCall && (intent.parameters.size != 1 || values["body"] != "I will call you later."))
            return rejected("INVALID_CALL_REPLY")
        if (intent.kind == IntentKind.callPhone && !com.example.voice_assistant.actions.PhoneRecipient.valid(values["recipient"] as? String ?: "")) return rejected("INVALID_CALL_RECIPIENT")
        if (intent.kind == IntentKind.sendMessage && values["channel"] !in setOf("SMS", "UNSPECIFIED")) return rejected("UNSUPPORTED_MESSAGE_CHANNEL")
        if (values.values.filterIsInstance<String>().any { it.isBlank() || it.length > 1000 || it.any(Char::isISOControl) }) return rejected("INVALID_TEXT")
        if (values["destination"]?.toString()?.lowercase() in setOf("there", "here", "it", "that place")) return rejected("DESTINATION_REQUIRED")
        val scopes = when (intent.kind) {
            IntentKind.readNotification -> setOf("LATEST", "ALL_ACTIVE")
            IntentKind.readEmail -> setOf("LATEST", "RECENT", "IMPORTANT", "RELEVANT", "SELECTED", "SUMMARY")
            else -> setOf("LATEST")
        }
        if (shape.containsKey("scope") && values["scope"] !in scopes) return rejected("INVALID_SCOPE")
        if (shape.containsKey("timeZoneId") && values["timeZoneId"] !in java.util.TimeZone.getAvailableIDs().toSet()) return rejected("INVALID_TIME_ZONE")
        fun epoch(name: String): Long? {
            val value = values[name] as? Number ?: return null
            return value.toDouble().takeIf { it >= 0 && it <= 32503680000000L && it % 1.0 == 0.0 }?.toLong()
        }
        if (intent.kind == IntentKind.createReminder && (epoch("dueEpochMs") ?: 0) <= now()) return rejected("FUTURE_TIME_REQUIRED")
        if (intent.kind in setOf(IntentKind.queryCalendar, IntentKind.createCalendarEvent)) {
            val start = epoch("startEpochMs") ?: return rejected("INVALID_TIME")
            val end = epoch("endEpochMs") ?: return rejected("INVALID_TIME")
            if (end <= start) return rejected("INVALID_RANGE")
            if (end - start > 31L * 86400000) return rejected("RANGE_TOO_LARGE")
            if (intent.kind == IntentKind.createCalendarEvent && start <= now()) return rejected("FUTURE_TIME_REQUIRED")
        }
        return CapabilityResult.Available(AssistantAction(intent.kind.wireName, intent))
    }
}
