package com.example.voice_assistant.calendar
import com.example.voice_assistant.native_bridge.contracts.*
data class CalendarMeeting(val id: String, val title: String, val startEpochMs: Long, val endEpochMs: Long,
    val location: String = "", val description: String = "", val allDay: Boolean = false)
interface CalendarService {
    suspend fun meetings(startEpochMs: Long, endEpochMs: Long): CapabilityResult<List<CalendarMeeting>>
}
