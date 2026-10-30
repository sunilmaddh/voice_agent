package com.example.voice_assistant.intent

import com.example.voice_assistant.native_bridge.contracts.*

/** Domain command constructors define the legal parameter shape for every intent. */
sealed class TypedCommand(val kind: IntentKind) {
    data object DrivingModeOn : TypedCommand(IntentKind.drivingModeOn) { override val parameters = emptyList<IntentParameter>() }
    data object DrivingModeOff : TypedCommand(IntentKind.drivingModeOff) { override val parameters = emptyList<IntentParameter>() }
    data object ReplyToCall : TypedCommand(IntentKind.replyToCall) {
        override val parameters = listOf(text("body", "I will call you later."))
    }
    data object ReadSms : TypedCommand(IntentKind.readSms) { override val parameters = listOf(text("scope", "LATEST")) }
    data class ReplySms(val body: String) : TypedCommand(IntentKind.replySms) { override val parameters = listOf(text("body", body)) }
    data object EndCall : TypedCommand(IntentKind.endCall) { override val parameters = emptyList<IntentParameter>() }
    data class CallPhone(val recipient: String) : TypedCommand(IntentKind.callPhone) {
        override val parameters = listOf(text("recipient", recipient))
    }
    abstract val parameters: List<IntentParameter>
    fun toIntent(confidence: Double) = AssistantIntent(kind, parameters, confidence)
    protected fun text(name: String, value: String) = IntentParameter(name, ParameterType.text, value)
    protected fun number(name: String, value: Long) = IntentParameter(name, ParameterType.number, value)

    data class Navigate(val destination: String) : TypedCommand(IntentKind.navigate) {
        override val parameters = listOf(text("destination", destination))
    }
    data class GoogleSearch(val query: String) : TypedCommand(IntentKind.googleSearch) {
        override val parameters = listOf(text("query", query))
    }
    data class CreateReminder(val title: String, val dueEpochMs: Long, val timeZoneId: String) : TypedCommand(IntentKind.createReminder) {
        override val parameters = listOf(text("title", title), number("dueEpochMs", dueEpochMs), text("timeZoneId", timeZoneId))
    }
    enum class MessageChannel { SMS, UNSPECIFIED }
    data class SendMessage(val recipientName: String, val body: String, val channel: MessageChannel) : TypedCommand(IntentKind.sendMessage) {
        override val parameters = listOf(text("recipientName", recipientName), text("body", body), text("channel", channel.name))
    }
    data class QueryCalendar(val startEpochMs: Long, val endEpochMs: Long, val timeZoneId: String) : TypedCommand(IntentKind.queryCalendar) {
        override val parameters = listOf(number("startEpochMs", startEpochMs), number("endEpochMs", endEpochMs), text("timeZoneId", timeZoneId))
    }
    data class CreateCalendarEvent(val title: String, val startEpochMs: Long, val endEpochMs: Long, val timeZoneId: String) : TypedCommand(IntentKind.createCalendarEvent) {
        override val parameters = listOf(text("title", title), number("startEpochMs", startEpochMs), number("endEpochMs", endEpochMs), text("timeZoneId", timeZoneId))
    }
    data object OpenMaps : TypedCommand(IntentKind.openMaps) { override val parameters = emptyList<IntentParameter>() }
    data object OpenCalendar : TypedCommand(IntentKind.openCalendar) { override val parameters = emptyList<IntentParameter>() }
    data object StopNavigation : TypedCommand(IntentKind.stopNavigation) { override val parameters = emptyList<IntentParameter>() }
    enum class ReadScope { LATEST, ALL_ACTIVE }
    data class ReadNotification(val scope: ReadScope = ReadScope.LATEST) : TypedCommand(IntentKind.readNotification) {
        override val parameters = listOf(text("scope", scope.name))
    }
    data object ReadWhatsApp : TypedCommand(IntentKind.readWhatsApp) { override val parameters = listOf(text("scope", "LATEST")) }
    data class ReplyWhatsApp(val body: String) : TypedCommand(IntentKind.replyWhatsApp) {
        override val parameters = listOf(text("body", body)) // conversation context intentionally unresolved
    }
    data class ReadEmail(val scope: String = "LATEST") : TypedCommand(IntentKind.readEmail) { override val parameters = listOf(text("scope", scope)) }
    data class ReplyEmail(val body: String) : TypedCommand(IntentKind.replyEmail) {
        override val parameters = listOf(text("body", body)) // message/thread identity intentionally unresolved
    }
    data class DocumentQuery(val question: String) : TypedCommand(IntentKind.documentQuery) {
        override val parameters = listOf(text("question", question))
    }
    data class GeneralQuery(val question: String) : TypedCommand(IntentKind.generalQuery) {
        override val parameters = listOf(text("question", question))
    }
    data object Unknown : TypedCommand(IntentKind.unknown) { override val parameters = emptyList<IntentParameter>() }
}
