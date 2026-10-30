package com.example.voice_assistant.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract as C
import com.example.voice_assistant.native_bridge.contracts.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads locally synced Google calendars only; Instances expands recurring events. */
class AndroidCalendarService(private val context: Context) : CalendarService {
    override suspend fun meetings(startEpochMs: Long, endEpochMs: Long): CapabilityResult<List<CalendarMeeting>> = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            return@withContext CapabilityResult.Unavailable("CALENDAR_PERMISSION_REQUIRED")
        try {
            val resolver = context.contentResolver
            val ids = mutableListOf<String>()
            val calendars = resolver.query(C.Calendars.CONTENT_URI, arrayOf(C.Calendars._ID),
                "${C.Calendars.ACCOUNT_TYPE} = ? AND ${C.Calendars.VISIBLE} = 1", arrayOf("com.google"), null)
                ?: return@withContext CapabilityResult.Unavailable("CALENDAR_QUERY_FAILED")
            calendars.use { while (it.moveToNext()) ids.add(it.getLong(0).toString()) }
            if (ids.isEmpty()) return@withContext CapabilityResult.Unavailable("GOOGLE_CALENDAR_NOT_SYNCED")
            val uri = C.Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, startEpochMs); ContentUris.appendId(it, endEpochMs)
            }.build()
            val result = mutableListOf<CalendarMeeting>()
            val projection = arrayOf(C.Instances.EVENT_ID, C.Instances.TITLE, C.Instances.BEGIN, C.Instances.END,
                C.Instances.EVENT_LOCATION, C.Instances.DESCRIPTION, C.Instances.ALL_DAY)
            val cursor = resolver.query(uri, projection,
                "${C.Instances.CALENDAR_ID} IN (${ids.joinToString(",") { "?" }}) AND ${C.Instances.STATUS} != ? AND ${C.Instances.SELF_ATTENDEE_STATUS} != ? AND ${C.Instances.BEGIN} < ? AND ${C.Instances.END} > ?",
                (ids + listOf(C.Events.STATUS_CANCELED.toString(), C.Attendees.ATTENDEE_STATUS_DECLINED.toString(), endEpochMs.toString(), startEpochMs.toString())).toTypedArray(),
                "${C.Instances.BEGIN} ASC, ${C.Instances.EVENT_ID} ASC")
                ?: return@withContext CapabilityResult.Unavailable("CALENDAR_QUERY_FAILED")
            cursor.use {
                // One extra row allows an honest truncation notice without unbounded TTS.
                while (result.size < 6 && it.moveToNext()) result.add(CalendarMeeting(it.getLong(0).toString(),
                    it.getString(1).orEmpty().take(120), it.getLong(2), it.getLong(3),
                    it.getString(4).orEmpty().take(100), it.getString(5).orEmpty().take(160), it.getInt(6) != 0))
            }
            CapabilityResult.Available(result)
        } catch (_: SecurityException) { CapabilityResult.Unavailable("CALENDAR_PERMISSION_REQUIRED") }
        catch (_: Exception) { CapabilityResult.Unavailable("CALENDAR_QUERY_FAILED") }
    }
}
