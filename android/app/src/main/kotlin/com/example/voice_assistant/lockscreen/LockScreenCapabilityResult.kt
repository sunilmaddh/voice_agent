package com.example.voice_assistant.lockscreen

enum class LockScreenStatus { WORKS, SUPPORTED_WITH_RESTRICTIONS, NOT_SUPPORTED }
enum class LockScreenState { SCREEN_ON_UNLOCKED, SCREEN_ON_LOCKED, SCREEN_OFF_LOCKED, HEADSET_CONNECTED }

/** A capability assessment is not a measured successful test. */
data class LockScreenCapabilityResult(
    val command: String, val state: LockScreenState, val status: LockScreenStatus,
    val permissions: List<String>, val unlockRequired: Boolean, val restriction: String,
    val evidence: String = "PENDING_DEVICE_TEST"
) {
    fun toWire(): Map<String, Any?> = mapOf(
        "command" to command, "state" to state.name, "status" to status.name,
        "permissions" to permissions, "unlockRequired" to unlockRequired,
        "restriction" to restriction, "evidence" to evidence,
        "androidRole" to "android.app.role.ASSISTANT (API 29+); assist settings on earlier versions",
        "defaultAssistantRequired" to true, "screenOnRequired" to true,
        "wakeWordBehavior" to "Opt-in software Hey Indo detector can invoke the selected system assistant; persistent microphone notification and extra battery use. No hardware hotword claim; PENDING_DEVICE_TEST.",
        "androidVersionDependency" to "VoiceInteractionService API 21; assistant activity API 26; showWhenLocked API 27 (window flag below); keyguard dismissal API 26",
        "manufacturerDependency" to "OEM decides assistant gesture, lock-screen affordance and headset-button dispatch; connected audio device does not prove microphone routing."
    )
}

object LockScreenPolicy {
    val commands = listOf("What time is it?", "Navigate to Bangalore Airport.",
        "Read my latest message.", "What is my next meeting?", "Create a reminder.",
        "Read my latest notification.", "Read my latest WhatsApp message.", "Read my latest email.",
        "Search Google for Flutter Riverpod.")
    fun isTime(text: String): Boolean = text.trim().trimEnd('.', '?', '!')
        .equals("what time is it", ignoreCase = true)
    fun matrix(): List<LockScreenCapabilityResult> = LockScreenState.entries.flatMap { state ->
        commands.mapIndexed { index, command ->
            LockScreenCapabilityResult(command, state,
                if (index == 2) LockScreenStatus.NOT_SUPPORTED else LockScreenStatus.SUPPORTED_WITH_RESTRICTIONS,
                listOf("RECORD_AUDIO") + when (index) {
                    3 -> listOf("READ_CALENDAR")
                    4 -> listOf("POST_NOTIFICATIONS on API 33+")
                    5, 6 -> listOf("User-enabled Notification Listener access")
                    7 -> listOf("INTERNET", "Previously completed Gmail OAuth")
                    else -> emptyList()
                }, index != 0 && index != 4,
                when(index) {
                    0 -> "Short capture after system invocation; saved speech-provider preference and an installed English TTS voice required."
                    1 -> "Tap Unlock and continue; Android authenticates before the validated command resumes once within 120 seconds. Maps handoff is not proof of navigation starting."
                    2 -> "Message source unspecified: name WhatsApp explicitly; no general SMS inbox access."
                    3 -> "Unlock by default; optional personal-read setting permits speech while locked. Requires a synced calendar and READ_CALENDAR."
                    4 -> "Provide title, date and time. No unlock needed with previously granted notification permission. Bare commands do not schedule; delivery is inexact."
                    5, 6 -> "Unlock by default; optional personal-read setting permits only public notification content allowed by device/channel visibility. Hidden or absent messages remain unavailable."
                    7 -> "Unlock by default; optional personal-read setting allows Gmail API reads with existing in-process OAuth authorization and network access. Reconnect unlocked after expiry/process death."
                    else -> "Tap Unlock and continue; Android authenticates before handing the query to Google Search. No search-result scraping."
                } + if (state == LockScreenState.SCREEN_OFF_LOCKED) " No screen-off listener: device must dispatch an assistant affordance and show the screen." else "")
        }
    }
}
