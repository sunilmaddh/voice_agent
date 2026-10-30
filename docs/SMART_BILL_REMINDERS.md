# Smart notification / bill reminder POC

Open **Settings > Smart bill reminders**. Choose **Validate 3 samples**, paste a notification/SMS, scan up to 50 current active notifications, or read the latest SMS. Review the extracted fields, choose a time (default 09:00 on the due date in the device's current time zone), and select **Create local reminder**. Extraction alone does not schedule anything. This is an on-demand validation workflow, not an always-on background inbox monitor.

## Implemented pipeline

Notification listener snapshot / permission-gated latest SMS / sample text → `BillExtractor` → typed `BillCandidate` → eligibility and future-time validation → `BillReminderPlan` → existing `LocalReminderScheduler` → local notification.

The Flutter screen uses a separate versioned Platform Channel. Android owns source access, validation and scheduling. No Google Pay, payment SDK, bank connection, cloud LLM, or payment execution is included. All extraction is deterministic and local; no model is required. Source content cannot invoke arbitrary Android actions. Raw notices are not logged or archived by this module; extracted candidates live in memory until replaced or the screen closes. A scheduled reminder's title/date persist in the existing private reminder store.

## Supported extraction and limits

- English electricity bills, credit card payments and mobile-plan expiry notices.
- Dates must explicitly follow `due [on/by]` or `expires [on]`, in `dd-MMM-yyyy` or `yyyy-MM-dd` form. Ambiguous numeric dates, missing years, relative dates, multiple date tokens and invalid dates are rejected. Past dates/times are rejected; samples with fixed October 2026 dates become stale after those dates.
- INR amounts marked with ₹, INR or Rs, including conventional Indian/Western comma grouping and up to two decimal places. Multiple amounts, zero, unsupported currency or a missing payment amount prevent scheduling. Plan expiry may legitimately have no amount/currency.
- Merchant is only recognized in explicit `BESCOM: ...` or `from BESCOM of ...` style attribution. Missing/unrecognized merchant is **null**, not inferred from app name or bill category. The supplied electricity sample does not say BESCOM, so its merchant is unknown.
- Type: `BILL_PAYMENT` or `PLAN_EXPIRY`. Priority is a local policy: HIGH when due within seven days, otherwise MEDIUM. Priority does not change notification-channel importance or bypass DND. This is a rule, not inferred sender urgency.
- Paid/cancelled/refunded notices and ambiguous categories are rejected. This narrow parser cannot reliably interpret all promotional, multilingual or adversarial text; review the extracted fields against the source before creating a reminder. Edit pasted text and extract again to correct unsupported wording.

Expected sample extraction using **2026-10-01** as the test date:

| Sample | Category | Merchant | Amount / currency | Due | Type | Priority |
|---|---|---|---|---|---|---|
| Electricity | Electricity Bill | null | 2450 / INR | 2026-10-05 | BILL_PAYMENT | HIGH |
| Credit card | Credit Card Payment | null | 18500 / INR | 2026-10-10 | BILL_PAYMENT | MEDIUM |
| Mobile | Mobile Plan | null | null / null | 2026-10-12 | PLAN_EXPIRY | MEDIUM |

These are unit-test expectations, not captured live notification results. Runtime priority uses the actual device date.

## Permissions and reminder behavior

- Notification scan uses existing notification-listener access and only currently active, readable previews. Hidden, dismissed, truncated or unavailable content cannot be reconstructed. Configure access through Aiko Settings > Read notifications.
- Latest SMS uses the existing assistant-role + READ_SMS flow, requires the app foreground/unlocked, and reads only one message. Configure SMS access in System assistant settings. No SEND_SMS permission or message transmission is added.
- Reminder creation requires notifications enabled, including POST_NOTIFICATIONS on Android 13+ and the reminder channel enabled. Existing Calendar/reminder settings expose permission setup. Permission denial is returned as failure/restriction, never as a scheduled reminder.
- Uses `AlarmManager.setAndAllowWhileIdle`, an **inexact** alarm. It can be delayed by Android/OEM power policies; it is not proof of delivery. No exact-alarm role/permission is requested. Existing reboot/package-replacement restoration is reused, including its one-day missed-reminder retention policy. Force-stop or disabled notifications can prevent delivery until the app is reopened/permissions restored.
- Duplicate pending bills share a stable ID based on category, merchant, amount, currency, date and type. Repeating creation retains the original reminder time. Without account/invoice identifiers, two genuinely distinct bills with identical extracted fields may be merged; differently worded notices with different merchant extraction can remain distinct. This is POC deduplication, not account reconciliation.
- The scheduled epoch is fixed at creation; changing the device time zone later does not reinterpret it as a new local 09:00. A due date without a time uses the user-selected reminder time, not an invented payment deadline.
- Creation requires the foreground/unlocked app. A scheduled private notification can arrive while locked, subject to Android privacy and delivery policy. No voice readout or automatic payment is added.

## Diagnostics and validation

Implementation checks: Flutter analysis passed; all 167 native unit tests passed; 40 existing Flutter tests passed, and both new bill-screen tests passed after correcting their test scroll handling. Android debug assembly and lint completed successfully (lint warnings remain). The APK is `build/app/outputs/apk/debug/app-debug.apk`. These are automated checks, not observed reminder delivery.

Each extraction shows source, input, structured fields, eligibility/issues, local execution, deterministic tier, selected model NONE, reason and measured extraction latency. Scheduling shows status/code, Android mechanism, requested epoch/time zone, action latency and `PENDING_DEVICE_DELIVERY`. A returned scheduling success means the alarm was registered, not that it was delivered.

Automated tests cover the three samples, explicit merchant, grouped amounts, malformed/ambiguous/stale notices, time-zone conversion, stable IDs, future-time gating, and UI separation between extraction and scheduling. Test doubles are only test fixtures. Physical notification/SMS ingestion, permission denial, reboot restoration, deduplication and actual locked/unlocked reminder delivery remain pending device validation.

Device test: grant required source/notification permissions; scan a test notification/SMS with an explicit future due date; inspect every field; schedule a near-future time on that date; repeat and verify only one pending reminder; lock the phone and observe delivery; repeat with permissions denied and after reboot. Do not record success without observing the result. No live notification or SMS was generated as part of implementation.

Source files: `notifications/BillExtractor.kt`, `notifications/BillChannels.kt`, `notifications/LocalReminderScheduler.kt`, `MainActivity.kt`, `lib/native_bridge/bill_bridge.dart`, `lib/presentation/bill_reminders_screen.dart`, the Settings entry in `assistant_screen.dart`, `BillExtractorTest.kt`, and `test/bill_reminders_screen_test.dart`.

Android reference: [Schedule alarms](https://developer.android.com/develop/background-work/services/alarms).
