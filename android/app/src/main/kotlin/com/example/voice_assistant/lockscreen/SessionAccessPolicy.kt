package com.example.voice_assistant.lockscreen

import com.example.voice_assistant.native_bridge.contracts.IntentKind

object SessionAccessPolicy {
    val reads = setOf(IntentKind.queryCalendar, IntentKind.readNotification, IntentKind.readWhatsApp, IntentKind.readEmail)
    fun requiresUnlock(kind: IntentKind, locked: Boolean, personalReads: Boolean): Boolean =
        locked && kind !in setOf(IntentKind.drivingModeOn, IntentKind.drivingModeOff, IntentKind.createReminder, IntentKind.googleSearch) && !(personalReads && kind in reads)
}
