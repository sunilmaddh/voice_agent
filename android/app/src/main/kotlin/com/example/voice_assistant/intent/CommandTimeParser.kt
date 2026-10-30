package com.example.voice_assistant.intent

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Bounded grammar: today/tomorrow/ISO date, explicit AM/PM or HH:mm. No guessed times. */
class CommandTimeParser(private val now: () -> Long, zone: TimeZone) {
    private val zone = zone.clone() as TimeZone
    val zoneId: String get() = zone.id
    private fun format(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone; isLenient = false }
    private fun day(text: String): Calendar? { return try {
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = now() }
        when(text.lowercase(Locale.US)) {
            "today" -> Unit
            "tomorrow" -> calendar.add(Calendar.DAY_OF_MONTH, 1)
            else -> {
                if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(text)) return null
                calendar.time = format("yyyy-MM-dd").parse(text) ?: return null
            }
        }
        calendar
    } catch (_: IllegalArgumentException) { null } catch (_: java.text.ParseException) { null } }

    fun reminder(dayText: String, timeText: String): Long? {
        val date = day(dayText) ?: return null
        val meridiem = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*([ap]m)", RegexOption.IGNORE_CASE).matchEntire(timeText)
        val clock = Regex("(\\d{2}):(\\d{2})").matchEntire(timeText)
        val hour: Int
        val minute: Int
        if (meridiem != null) {
            val h = meridiem.groupValues[1].toInt()
            if (h !in 1..12) return null
            hour = h % 12 + if (meridiem.groupValues[3].equals("pm", true)) 12 else 0
            minute = meridiem.groupValues[2].ifEmpty { "0" }.toInt()
        } else if (clock != null) {
            hour = clock.groupValues[1].toInt(); minute = clock.groupValues[2].toInt()
        } else return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return try {
            date.isLenient = false
            date.set(Calendar.HOUR_OF_DAY, hour); date.set(Calendar.MINUTE, minute)
            date.set(Calendar.SECOND, 0); date.set(Calendar.MILLISECOND, 0)
            val instant = date.timeInMillis // rejects DST gaps
            val formatter = format("yyyy-MM-dd HH:mm")
            val label = formatter.format(Date(instant))
            // Reject repeated wall-clock minutes during common DST offset overlaps.
            if (listOf(-120, -90, -60, -30, 30, 60, 90, 120).any {
                formatter.format(Date(instant + it * 60000L)) == label
            }) return null
            instant.takeIf { it > now() }
        } catch (_: IllegalArgumentException) { null }
    }

    fun calendarRange(dayText: String): Pair<Long, Long>? { return try {
        val date = day(dayText) ?: return null
        date.set(Calendar.HOUR_OF_DAY, 0); date.set(Calendar.MINUTE, 0)
        date.set(Calendar.SECOND, 0); date.set(Calendar.MILLISECOND, 0)
        val start = date.timeInMillis
        date.add(Calendar.DAY_OF_MONTH, 1)
        start to date.timeInMillis
    } catch (_: IllegalArgumentException) { null } }
}
