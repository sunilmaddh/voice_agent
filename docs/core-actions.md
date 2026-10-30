# Core Android action execution

All five requested core commands are connected to native execution through the existing English speech pipeline. Command/model output passes schema and confidence validation, then `ActionReportingExecutor` revalidates before dispatching through `AndroidActionExecutor`. Raw model text is never executed.

| Command | Native mechanism | Actual completion boundary |
| --- | --- | --- |
| NAVIGATE | ACTION_VIEW `google.navigation:q=...`, restricted to Google Maps | Android accepted the launch; route/destination/navigation success remains unverified |
| GOOGLE_SEARCH | Google app ACTION_WEB_SEARCH, HTTPS Google Search fallback | App/browser handoff; loaded search results remain unverified |
| CREATE_REMINDER | Persistent local reminder + AlarmManager.setAndAllowWhileIdle + NotificationManager | Schedule accepted; notification delivery is inexact and pending |
| SEND_MESSAGE | ACTION_SENDTO `smsto:` with `sms_body` | SMS composer only; user selects/verifies recipient and taps Send |
| QUERY_CALENDAR | CalendarContract.Instances, READ_CALENDAR | Reads local synced Google calendar instances, including recurrence expansion |

The foreground/unlocked requirement is a **POC policy**, not a claim that every Android API requires an unlocked device. Calendar and notification access must be enabled in Settings. Missing apps, denied permissions, blocked notifications and absent synced calendars produce explicit restrictions. Existing [calendar/reminder](calendar-reminders.md), [Maps](maps-launch.md), and [Search](google-search.md) limitations still apply.

## Messaging

“Tell Rahul I'll call him after the meeting” routes locally to SEND_MESSAGE with recipientName=Rahul, the extracted body, and channel=UNSPECIFIED. For this POC, SMS is the supported compose destination, announced before opening the SMS app. “Send SMS to +91 98765 43210 saying Hello” can prefill a single explicit phone number.

Names are not silently resolved to contacts. For Rahul, the composer opens without a phone number; select the correct recipient in that app. URI punctuation or unsupported number formats cannot introduce another address or change the message body. These inputs require manual recipient selection. No READ_CONTACTS, SEND_SMS, accessibility service or default-SMS role is requested. No SMS is sent automatically, no sent/delivery receipt is invented, and the user may cancel the composer.

This follows Android's [supported SMS compose intents](https://developer.android.com/guide/components/intents-common#Messaging). Device confirmation is still required for each installed SMS application's behavior, including whether it preserves a prefilled body while selecting recipients and whether it offers SMS/RCS transport.

## Result contract

`ActionResult` retains status/code/message and adds an `evidence` object containing:

- success, actionType and typed input parameters;
- androidMechanism (the adapter mechanism/path; not proof the downstream app completed the task);
- userInteractionRequired and unlockRequired;
- error and restriction;
- outcome, such as HANDOFF_ONLY, SCHEDULED_NOT_DELIVERED, COMPLETED or USER_ACTION_REQUIRED.

For SMS, success=false with status=requiresConfirmation and outcome=HANDOFF_ONLY means the requested message has **not** been sent. Additional evidence includes resolvedPhoneNumber (null for a name), recipientResolution and deliveryVerified=false. Maps/Search launch success refers only to the handoff and carries an explicit downstream-verification restriction. Independent validation failure invokes no Android adapter.

## Pipeline and timings

Voice → final STT → deterministic/model router → typed intent → validation → native executor → result → TTS is used for local calendar/reminder results. External activities pause Aiko, so SMS/Maps/Search/calendar-editor commands speak a truthful acknowledgement first, release audio focus, then launch. The final handoff result is shown in Flutter diagnostics. Failed/unsupported launches that leave Aiko foreground are spoken afterwards; blocked unlock requests do not trigger this follow-up playback.

The dashboard shows transcript, tier/model, intent/confidence/reason, structured parameters, action attempts/results and complete evidence. It includes STT finalization/capture, routing (including any model time), separate SLM timing, action duration, TTS startup, total TTS call/playback duration and end-to-end timing. Routing and SLM durations overlap and should not be added together. Missing stages remain not_run; no benchmarks are fabricated.

## Validation

Automated tests cover exact message grammar, name vs explicit-number handling, URI injection resistance, missing SMS handler, locked/invalid inputs, complete result evidence for core intents, immutable Flutter evidence, SMS voice acknowledgement before handoff, and actual playback timing presence. Existing routing, Maps/Search, calendar/reminder and Gmail tests are retained.

Validation on 29 September 2026: Flutter analysis passed; 23 Flutter tests and 72 Kotlin tests passed; Android debug APK built. On the connected A103SH (Android SDK 33), read-only package-manager resolution found handlers for Maps navigation, Google Search, SMS composition (SoftBank +Message) and the Google Calendar editor. These checks did not launch activities or access message/calendar contents.

End-to-end physical-device validation remains pending. Run each supplied POC example on the target phones, verify actual launched apps, confirm the SMS recipient/body manually, and record cancellation, missing-handler, permission-denial and locked-device outcomes. For reminders record actual delivery time versus requested time; for calendar compare recurring/all-day events against the synced Google calendar. Do not mark any downstream outcome as validated solely from unit-test doubles or an accepted launch.

## Changed files

Native additions: `actions/ActionReportingExecutor.kt`, `actions/SmsComposeExecutor.kt`. Updates: `actions/AndroidActionExecutor.kt`, `intent/CommandValidator.kt`, `intent/LocalIntentRouter.kt`, `voice/SpeechCoordinator.kt`, `native_bridge/contracts/Models.kt`, `AndroidManifest.xml`.

Flutter: `native_bridge/contracts/models.dart`, `presentation/assistant_diagnostics_screen.dart`, `presentation/assistant_screen.dart`. Tests: `CoreActionsTest.kt`, `IntentRouterTest.kt`, `CommandModelTest.kt`, `test/bridge_test.dart`. Documentation: this file, README and command-routing notes.
