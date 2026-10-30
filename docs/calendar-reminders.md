# Google Calendar and local reminders

This POC uses Android Calendar Provider to read visible Google calendars synced to the current Android profile (`ACCOUNT_TYPE=com.google`). It does not call the Google Calendar REST API, request OAuth tokens, or introduce a backend. Google account setup and Calendar sync must already be enabled on the phone. Empty results describe the local sync cache, not a verified Google server response.

## Setup and voice commands

Stop and restart the app after installing the native changes. In Aiko Settings → Calendar and reminders, tap **Enable calendar and reminders**. Allow calendar read access and notification permission. If Android no longer offers a permission prompt, enable permissions through Android app settings. Ensure the Assistant reminders notification channel is enabled. **Check access** refreshes permission status after returning from system settings.

Supported deterministic examples:

- “What meetings do I have tomorrow?”
- “Read meeting details today.”
- “Read calendar events tomorrow.”
- “Read upcoming calendar events.” (next seven days, including ongoing events)
- “Open Calendar.”
- “Remind me tomorrow at 10 AM to call Rahul.”
- “Remind me about my 4 PM meeting tomorrow.”
- “Create a calendar event Team review tomorrow from 4 PM to 5 PM.”

“Remind me about my 4 PM meeting” asks for the missing date and gives a complete example to repeat. The POC does not maintain a multi-turn clarification session. It does not guess a date, look up a matching event, assume an advance-notice interval, or silently roll a past time into tomorrow. Explicit meeting reminders notify at the stated time. Date grammars support today, tomorrow and YYYY-MM-DD; reminder-about-meeting syntax supports today/tomorrow. Times require AM/PM or 24-hour HH:mm. DST gaps/overlaps are rejected by the existing time parser.

## Native responsibilities and boundaries

- `AndroidCalendarService` queries CalendarContract.Instances off the main thread, expanding recurring events. It excludes canceled/declined events and includes title, beginning/end, location and a bounded description. Up to five events are read aloud, with a truncation notice if more exist. Times use the command's local time zone. All-day dates use UTC date semantics and an exclusive end date. Provider errors, missing permissions and no synced Google calendars are distinct outcomes.
- `CalendarActionExecutor` revalidates typed intents, confidence and temporal bounds independently of routing. Queries/reminders execute before TTS so the actual result is spoken. Calendar data is treated as display/speech content and never as instructions for a model or executor. Calendar reads require the unlocked foreground app.
- `AndroidCalendarLaunchPort` opens Google Calendar preferentially, with an installed calendar-handler fallback. `CREATE_CALENDAR_EVENT` opens an ACTION_INSERT draft containing title/start/end/time zone. The user must choose a writable Google calendar and save. `CALENDAR_EDITOR_OPENED` uses `requiresConfirmation`; diagnostics do not claim the event was saved or synced. No WRITE_CALENDAR permission is needed for the editor handoff. Opening the calendar completes after TTS/audio-focus release, preserving the existing external-app lifecycle behavior.
- `LocalReminderScheduler` saves up to 100 pending local reminders in private SharedPreferences and uses AlarmManager.setAndAllowWhileIdle. Immutable, unique PendingIntents avoid reminders replacing each other. The notification channel and notification permission are checked before accepting a reminder. Receiver delivery checks again; revoked/blocked notifications remain pending for a later app-start retry. Reminder text is private on the lock screen and absent from structured system logs.
- `ReminderReceiver` restores schedules after reboot/package replacement. App resume also restores pending schedules. Reminders missed by less than one day are rescheduled for delivery; older missed reminders are removed. This is a bounded POC retention policy, not a guarantee of delivery.
- `CalendarBridge` exposes read/request permission operations. Flutter Settings presents status and usage instructions; Android owns permission dialogs, providers, app launching and alarms. No Android role is required.

## Real limitations

Notifications are inexact and may arrive late under Doze, battery saver or manufacturer restrictions. No exact-alarm special access is requested. Force-stop can prevent alarms until the app is reopened; disabling notifications, clearing app data or uninstalling affects delivery/storage. No recurring reminders, cancellation/edit UI, Google Tasks synchronization, or server-side reminder delivery is implemented. Event drafts are controlled by the installed calendar application; Aiko cannot verify a save or guarantee its account selection. Work-profile calendars are not aggregated across profiles.

READ_CALENDAR and Android 13+ POST_NOTIFICATIONS are runtime permissions; RECEIVE_BOOT_COMPLETED restores schedules. Event creation relies on the user's calendar application's own permissions and writable calendar access. No new cloud AI call is used. Calendar sync itself may use the network through Android/Google services.

References: [Android Calendar Provider](https://developer.android.com/identity/providers/calendar-provider), [Alarm scheduling](https://developer.android.com/develop/background-work/services/alarms), [Notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

## Verification and pending device checklist

Automated coverage includes local query/event/reminder grammar, missing-date clarification, invalid/reversed/past timestamps, independent action validation, permission/sync error distinctions, meeting details/time-zone/all-day formatting, result truncation, locked-device refusal, draft confirmation status, result-before-TTS ordering, and permission bridge behavior.

Validation on 28 September 2026: Flutter analysis passed; 20 Flutter tests and 56 Kotlin tests passed; Android debug APK assembled. Android lint passed with non-blocking warnings (dependency updates, existing resource/API style suggestions, and synchronous preference commits performed on the IO dispatcher for persistence). Windows SDK drive-colon escaping in `android/local.properties` was corrected for lint; Flutter may regenerate that machine-local file.

Physical-device execution remains pending. Use a test Google calendar and verify:

1. Calendar permission granted, denied, permanently denied and revoked; notification permission and channel disabled/enabled; no synced Google account.
2. Tomorrow's timed event, recurring instance, canceled/declined event, all-day/multi-day event, time-zone differences, empty day and more than five events.
3. Create a draft, verify fields, choose Google calendar, save and sync; repeat and cancel without creating anything. Test read-only calendars and no installed editor.
4. Schedule two local reminders at distinct nearby future times; verify both notifications and privacy. Test app background, process death, reboot, force-stop/reopen, clock change, permission revocation and Doze. Record requested time vs actual delivery rather than fabricating latency.
5. Confirm voice cancellation and lock/background transitions never read calendar data aloud after cancellation.

## Files changed

Native: `calendar/{CalendarService,AndroidCalendarService,CalendarActionExecutor,AndroidCalendarLaunchPort}.kt`; `notifications/{LocalReminderScheduler,ReminderReceiver}.kt`; `actions/AndroidActionExecutor.kt`; `intent/{TypedCommand,LocalIntentRouter,CommandValidator}.kt`; `ai/CommandOutputSchema.kt`; `voice/SpeechCoordinator.kt`; `native_bridge/{AssistantChannels.kt,contracts/Models.kt}`; `MainActivity.kt`; `AndroidManifest.xml`.

Flutter: `lib/native_bridge/{assistant_bridge.dart,contracts/models.dart}`; `lib/presentation/{calendar_settings.dart,assistant_screen.dart}`. Tests: `CalendarIntegrationTest.kt`, `IntentRouterTest.kt`, `SpeechCoordinatorTest.kt`, `test/{bridge_test.dart,widget_test.dart}`. Documentation: this file, README and command-routing notes.
