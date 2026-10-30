package com.example.voice_assistant.intent

import java.util.Locale
import java.util.TimeZone
import com.example.voice_assistant.native_bridge.contracts.*

/** No model or ActionExecutor dependency: Tier 1 cannot accidentally invoke either. */
class LocalIntentRouter(private val now: () -> Long = System::currentTimeMillis,
    private val timeZone: () -> TimeZone = TimeZone::getDefault,
    private val validator: IntentValidator = CommandValidator(now)) : IntentRouter {
    override suspend fun route(text: String): CapabilityResult<RoutedIntent> = CapabilityResult.Available(decide(text))

    fun decide(raw: String): RoutedIntent = decideNormalized(HindiCommands.normalize(KannadaCommands.normalize(raw)))
    private fun decideNormalized(raw: String): RoutedIntent {
        fun blocked(reason: String) = RoutedIntent(TypedCommand.Unknown, RoutingTier.deterministic,
            0.0, reason, RoutingStatus.CLARIFICATION_REQUIRED)
        if (raw.isBlank()) return blocked("Say one supported command.")
        if (raw.length > 2000 || raw.any(Char::isISOControl)) return blocked("Input is too long or contains control characters.")
        val input = raw.trim().replace(Regex("\\s+"), " ").trimEnd('.', '?', '!')
            .replace(Regex("^hey (?:aiko|indo)[, ]+", RegexOption.IGNORE_CASE), "")
            // Polite wrappers around explicit commands do not require model inference.
            .replace(Regex("^(?:please\\s+)?(?:can|could|would) you\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^please\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:cut|decline|reject) (?:the )?(?:incoming )?call and (?:send (?:an? )?(?:sms|message|text)(?: saying| that)?|text(?: saying)?)[:, ]+(?:i will|i'll) call (?:you )?(?:back )?late(?:r)?$", RegexOption.IGNORE_CASE),
                "decline call and text i will call you later")
            .replace(Regex("^(?:cut the call and (?:send(?: sms)?|text)|decline the call and send sms) i will call you later$", RegexOption.IGNORE_CASE),
                "decline call and text i will call you later")
        val lower = input.lowercase(Locale.US)
        // Exact POC phrase includes "don't disturb me"; do not relax general negation checks.
        val driving = when (lower.replace('\u2019', '\'')
            .replace(Regex("^hey (?:aiko|indo)[, ]+"), "")) {
            "i'm driving. don't disturb me", "i'm driving, don't disturb me", "i'm driving don't disturb me",
            "i am driving", "i'm driving", "enable driving mode", "turn on driving mode" -> TypedCommand.DrivingModeOn
            "i am driving don't disturb me", "i am driving. don't disturb me",
            "i am driving, don't disturb me", "i'm driving do not disturb me",
            "i am driving do not disturb me" -> TypedCommand.DrivingModeOn
            "disable driving mode", "turn off driving mode", "stop driving mode" -> TypedCommand.DrivingModeOff
            else -> null
        }
        if (driving != null) {
            val checked = validator.validate(driving.toIntent(1.0))
            if (checked is CapabilityResult.Available) return RoutedIntent(driving, RoutingTier.deterministic, 1.0,
                "Exact driving-mode command; no model required.", RoutingStatus.PROPOSAL_ONLY, proposedAction = checked.value)
        }
        if (Regex("\\b(don't|do not|never|maybe|unless)\\b|^if\\b|^cancel\\b", RegexOption.IGNORE_CASE).containsMatchIn(input))
            return blocked("Negated, conditional or uncertain commands need clarification.")
        if (Regex(";|\\band then\\b|\\bthen\\b|\\b(?:and|or) (?:open|navigate|take|search|send|read|reply|remind|stop|delete|create|schedule)\\b", RegexOption.IGNORE_CASE).containsMatchIn(input))
            return blocked("Multiple or alternative commands must be given separately.")
        fun match(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE).matchEntire(input)
        fun deterministic(command: TypedCommand, confidence: Double = .98): RoutedIntent {
            return when(val checked = validator.validate(command.toIntent(confidence))) {
                is CapabilityResult.Available -> RoutedIntent(command, RoutingTier.deterministic, confidence,
                    "Matched an explicit deterministic grammar and passed parameter validation.", RoutingStatus.PROPOSAL_ONLY,
                    proposedAction = checked.value)
                is CapabilityResult.Unavailable -> RoutedIntent(command, RoutingTier.deterministic, confidence,
                    when(checked.code) {
                        "COMMUNICATION_CONTEXT_REQUIRED" -> "Resolve the recipient or reply thread, choose a channel, and confirm the message before sending."
                        else -> "Parameters need clarification: ${checked.code}."
                    }, RoutingStatus.CLARIFICATION_REQUIRED)
            }
        }
        when(lower) {
            "reply to caller saying i will call you later", "decline call and text i will call you later",
            "decline the call and text i will call you later",
            "cut the call and send sms i will call you later", "cut the call and send i will call you later",
            "cut the call and text i will call you later", "decline the call and send sms i will call you later" -> return deterministic(TypedCommand.ReplyToCall, 1.0)
            "read sms", "read my sms", "read my latest sms", "read latest sms", "read my latest text message" -> return deterministic(TypedCommand.ReadSms, 1.0)
            "hang up", "hang up the call", "end call", "end the call", "end my call", "cut the call", "disconnect the call" -> return deterministic(TypedCommand.EndCall, 1.0)
            "open maps", "open google maps" -> return deterministic(TypedCommand.OpenMaps, 1.0)
            "open calendar", "open google calendar" -> return deterministic(TypedCommand.OpenCalendar, 1.0)
            "stop navigation" -> return deterministic(TypedCommand.StopNavigation, 1.0)
            "read notifications", "read my notifications" -> return deterministic(TypedCommand.ReadNotification(TypedCommand.ReadScope.ALL_ACTIVE), 1.0)
            "read my latest notification", "read latest notification" -> return deterministic(TypedCommand.ReadNotification(), 1.0)
            "read my latest whatsapp message", "read my whatsapp", "read whatsapp" -> return deterministic(TypedCommand.ReadWhatsApp, 1.0)
            "read my latest email", "read latest email", "read my email", "read latest gmail" -> return deterministic(TypedCommand.ReadEmail(), 1.0)
            "read latest emails", "read my latest emails", "read recent emails", "read my emails" -> return deterministic(TypedCommand.ReadEmail("RECENT"), 1.0)
            "read important emails", "read my important emails" -> return deterministic(TypedCommand.ReadEmail("IMPORTANT"), 1.0)
            "read relevant emails", "read my relevant emails" -> return deterministic(TypedCommand.ReadEmail("RELEVANT"), 1.0)
            "read selected email", "read email content" -> return deterministic(TypedCommand.ReadEmail("SELECTED"), 1.0)
            "summarize email", "summarize this email", "summarize my email" -> return deterministic(TypedCommand.ReadEmail("SUMMARY"), 1.0)
        }
        match("(?:navigate|take me) to (.+)")?.let { return deterministic(TypedCommand.Navigate(it.groupValues[1])) }
        match("(?:call|dial|phone) (.+)")?.let {
            val recipient = it.groupValues[1]
            if (Regex("\\b(whatsapp|and|or|him|her|them|someone)\\b", RegexOption.IGNORE_CASE).containsMatchIn(recipient))
                return blocked("Say one phone number or an exact contact name for a regular phone call.")
            return deterministic(TypedCommand.CallPhone(recipient))
        }
        match("search (?:google )?for (.+)")?.let { return deterministic(TypedCommand.GoogleSearch(it.groupValues[1])) }
        if (com.example.voice_assistant.actions.WeatherSearch.cityFrom(input) != null)
            return deterministic(TypedCommand.GoogleSearch(input))
        match("reply (?:to )?(?:sms|text message)(?: saying| that)?[: ]+(.+)")?.let { return deterministic(TypedCommand.ReplySms(it.groupValues[1])) }
        match("reply (?:on |to )?whatsapp(?: saying| that)?[: ]+(.+)")?.let { return deterministic(TypedCommand.ReplyWhatsApp(it.groupValues[1])) }
        match("reply (?:to )?(?:email|gmail)(?: saying| that)?[: ]+(.+)")?.let { return deterministic(TypedCommand.ReplyEmail(it.groupValues[1])) }
        match("send (?:an? )?(?:sms|text) to (.+?) (?:saying|that) (.+)")?.let {
            return deterministic(TypedCommand.SendMessage(it.groupValues[1], it.groupValues[2], TypedCommand.MessageChannel.SMS))
        }
        match("tell (.+?) that (.+)")?.let {
            return deterministic(TypedCommand.SendMessage(it.groupValues[1], it.groupValues[2], TypedCommand.MessageChannel.UNSPECIFIED))
        }
        match("tell (.+?) (i(?:'|’)?ll .+|i will .+)")?.let {
            return deterministic(TypedCommand.SendMessage(it.groupValues[1], it.groupValues[2], TypedCommand.MessageChannel.UNSPECIFIED))
        }
        val time = CommandTimeParser(now, timeZone())
        match("(?:create|schedule) (?:a |an )?(?:calendar event|meeting) (.+?) (today|tomorrow|\\d{4}-\\d{2}-\\d{2}) from (.+?) to (.+)")?.let {
            val start = time.reminder(it.groupValues[2], it.groupValues[3])
            val end = time.reminder(it.groupValues[2], it.groupValues[4])
            if (start == null || end == null || end <= start) return blocked("Specify a future date and start/end times, such as tomorrow from 4 PM to 5 PM.")
            return deterministic(TypedCommand.CreateCalendarEvent(it.groupValues[1], start, end, time.zoneId))
        }
        match("remind me about my (.+?) meeting(?: (today|tomorrow))?")?.let {
            if (it.groupValues[2].isBlank()) return blocked("Which day? Say: Remind me today at 4 PM to attend my meeting. The reminder will be at that time.")
            val due = time.reminder(it.groupValues[2], it.groupValues[1])
                ?: return blocked("Specify a future meeting date and an explicit AM or PM time.")
            return deterministic(TypedCommand.CreateReminder("Attend my meeting", due, time.zoneId))
        }
        match("remind me (today|tomorrow|\\d{4}-\\d{2}-\\d{2}) at (.+?) to (.+)")?.let {
            val due = time.reminder(it.groupValues[1], it.groupValues[2])
                ?: return blocked("Specify a valid future date and an unambiguous time, such as tomorrow at 10 AM.")
            return deterministic(TypedCommand.CreateReminder(it.groupValues[3], due, time.zoneId))
        }
        match("(?:what meetings? do (?:i )?have|show (?:my )?(?:meetings|calendar)|read (?:my )?(?:meetings|meeting details|calendar events|events)) (today|tomorrow|\\d{4}-\\d{2}-\\d{2})")?.let {
            val range = time.calendarRange(it.groupValues[1]) ?: return blocked("Calendar date could not be resolved.")
            return deterministic(TypedCommand.QueryCalendar(range.first, range.second, time.zoneId))
        }
        if (lower in setOf("read upcoming calendar events", "read upcoming events", "what is my next meeting", "what's my next meeting")) {
            return deterministic(TypedCommand.QueryCalendar(now(), now() + 7L * 86400000, time.zoneId))
        }
        if (Regex("^(explain|summarize) (this|the) document$|^document question[: ]|^(what|who|when|how).+\\b(this|the) document\\b", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            return RoutedIntent(TypedCommand.DocumentQuery(input), RoutingTier.generalModel, .95,
                "Document understanding requires document context and a general local model; neither is attached to this route.",
                RoutingStatus.MODEL_NOT_CONFIGURED, modelRequired = true)
        }
        if (Regex("^(navigate|search|remind|send|tell|reply|read|open|stop|set|create|show|take|find|look|message|text|schedule|get)\\b|\\b(directions|reminder|notifications|calendar|meetings)\\b|^(can|could|would) you (navigate|search|remind|send|tell|read|open|show|take|find|look|get)\\b", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            return RoutedIntent(TypedCommand.Unknown, RoutingTier.commandModel, 0.0,
                "No complete deterministic grammar matched. A command model is required but not configured.",
                RoutingStatus.MODEL_NOT_CONFIGURED, modelRequired = true)
        }
        if (Regex("^(what|who|when|where|why|how|explain|summarize)\\b", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            return RoutedIntent(TypedCommand.GeneralQuery(input), RoutingTier.generalModel, .7,
                "General question requires a general local model, which is not configured.",
                RoutingStatus.MODEL_NOT_CONFIGURED, modelRequired = true)
        }
        return RoutedIntent(TypedCommand.Unknown, RoutingTier.deterministic, 0.0,
            "No supported command or question matched.", RoutingStatus.UNKNOWN)
    }
}

