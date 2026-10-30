> Current wake phrase: **Hey Indo** (restored at user request). Sakura references below describe the temporary rename. Enable **Hey Indo** after updating.

# Android system / lock-screen assistant POC

## Status and architecture assessment

The system-assistant path is implemented; physical lock-screen speech, OEM invocation, Bluetooth routing and battery results are **PENDING_DEVICE_TEST**. No capability is marked WORKS solely because it compiles. This report supersedes earlier activity-only assessments.

Flutter uses Riverpod for presentation and settings. MethodChannel `voice_assistant/methods/v1` exposes native features; diagnostic and legacy wake events use EventChannels. Kotlin already owns SpeechCoordinator, Android STT/TTS, TieredIntentRouter, typed command validation, Android action executors, CalendarContract reads, notification access, RemoteInput replies and Gmail API operations. Reusing those components avoids a second router or a Flutter engine on the lock screen.

Previously, the dedicated locked surface only handled time and reminders; general executors required a resumed, unlocked Flutter Activity. Gmail authorization was owned by that Activity. Screen-off/background restrictions and microphone ownership also limited the experimental Hey Indo service. An assistant role cannot by itself remove these restrictions.

## Implementation sequence and resulting architecture

1. Register VoiceInteractionService, session service and voice-interaction metadata, plus a functional RecognitionService adapter.
2. Share the existing command model/runtime between Flutter and native sessions. Explicitly exclude our recognition adapter when selecting an external STT provider, preventing recursion after default-assistant selection.
3. Present SystemAssistantActivity through VoiceInteractionSession.startAssistantActivity (API 26+) or the documented keyguard entry point. This small native surface reuses existing feature executors without starting Flutter.
4. Wrap action execution with SessionAccessPolicy and SessionActionExecutor. Validate commands again; hold protected actions only in memory, once, for up to 120 seconds while Android authenticates.
5. Reuse calendar/notification reads with a scoped privacy policy and separate Gmail API token access from OAuth UI. Add Flutter setup and metadata-only diagnostics.
6. Test policy, single-use authentication handoff, expiration and UI preference persistence; build, analyze and lint. Device acceptance remains separate.

Flow: Android invocation -> AikoVoiceInteractionService / AikoSessionService -> SystemAssistantActivity -> existing STT -> shared TieredIntentRouter -> validated SessionActionExecutor -> existing feature executor -> native TTS. Time uses the existing exact-time helper. No cloud LLM or new model is added.

The [VoiceInteractionService API](https://developer.android.com/reference/android/service/voice/VoiceInteractionService) provides the keyguard callback when declared in metadata. [VoiceInteractionSession](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession) supports an assistant Activity presentation. OEMs still control available lock-screen/button affordances. Selecting the assistant does not establish hotword availability.

## Invocation, lifecycle and audio

- Choose Aiko as the default digital assistant through Android's consent UI. Invoke using the device's assistant gesture, hardware button or supported headset assistant button. In-app Preview is only a surface test; it does not prove OS dispatch.
- **No system "Hey Indo" / "Hey Assistant" hotword detector is implemented.** The opt-in software wake service can now invoke the selected system assistant (see the Current hands-free extension below and WAKE_WORD_POC.md). No new continuous microphone loop was added.
- Invocation presents a screen-on native surface above keyguard; one short capture starts automatically. Speak allows retry. Saved STT settings retain explicit online consent; offline-only remains the default. Missing English assets still produce a real error, not a simulated transcript.
- Capture stops on screen-off, pause, cancellation and destruction. The existing recognizer timeout bounds listening. STT controls capture focus; TTS acquires/releases playback focus and stops on interruption. Legacy mic capture is stopped before session recognition; timeout reports microphone busy.
- The new session requests no custom wake lock, no battery exemption and no microphone foreground service. It releases the session keep-awake request. Turning the screen off ends the active interaction; another OS invocation is needed.
- Lock/keyguard, interactive screen, Doze, active-session state and available audio-device types are reported. Available devices are not evidence of the actual STT/TTS route. Bluetooth/wired disconnection and audibility need physical testing.
- After process death the OS may rebind the selected assistant, but grants/setup must already exist. No automatic microphone restart after boot or force-stop is promised. First unlock after reboot is required for credential-protected app data. Gmail in-memory authorization is lost on process death.

## Privacy and authentication

Settings -> System assistant -> Refresh system assistant exposes **Allow personal readouts while locked**, off by default. Enabling it authorizes spoken calendar, Gmail and eligible notification content to anyone able to invoke the assistant; it does not authenticate the speaker. Changing the setting requires unlocked Aiko. Personal text is not displayed on the locked native surface; FLAG_SECURE protects its window.

With the setting off, personal reads require Android unlock. Maps/search, message sending and replies require unlock regardless of this setting. Tap **Unlock and continue**, complete Android authentication, and the validated pending action resumes once. Cancellation, expiry, unrelated pause or screen-off clears it. API 24-25 users unlock manually and repeat. [KeyguardManager](https://developer.android.com/reference/android/app/KeyguardManager#requestDismissKeyguard(android.app.Activity,%20android.app.KeyguardManager.KeyguardDismissCallback)) controls authentication; no PIN/biometric bypass exists.

Locked notification reads require the existing personal-readout opt-in and system lock-screen notifications enabled. PRIVATE previews can now be read when the current user's `lock_screen_allow_private_notifications` setting is enabled and the notification channel does not force PRIVATE/SECRET visibility. Previously, the app incorrectly treated every PRIVATE notification as unconditionally hidden. SECRET notifications, restricted channels, device-policy restrictions, other profiles and unknown channel visibility still require unlocking. Missing/unreadable settings fail closed. Android/source-app redactions are never reconstructed; no notification archive, private database access or Accessibility automation is added.

This is a conservative POC policy using available settings and channel metadata, not a complete replica of every manufacturer's lock-screen UI. API 24â€“25 cannot provide channel metadata and therefore require unlocking. OEM-specific privacy controls and actual WhatsApp readouts still require physical-device verification. The action result now reports specific restriction codes and does not report a wholly unavailable preview as successfully read. Existing personal-readout and foreground checks remain in place.

Policy reference: [AOSP lock-screen redaction logic](https://android.googlesource.com/platform/frameworks/base/+/49419d93e57e/packages/SystemUI/src/com/android/systemui/statusbar/NotificationLockscreenUserManagerImpl.java). PRIVATE visibility is evaluated together with user privacy settings; a channel restriction is separately enforced. New unit tests cover sensitive-content settings, channel overrides, SECRET, unknown visibility, other profiles, device policy and unchanged unlocked behavior. Device execution of this change is pending.

WhatsApp replies reuse available RemoteInput/PendingIntent actions and the existing single-use review token. This native surface always requires unlocked review before sending; the Flutter instant-reply toggle does not remove that boundary. Dispatch is not a delivery receipt. Gmail replies direct the user to unlocked Aiko's Gmail review screen. Gmail reads reuse valid in-process OAuth authorization; expiry, revocation, process death or missing authorization requires reconnecting unlocked. Tokens, transcripts and message bodies are not persisted in session diagnostics.

## Capability assessment (all physical results pending)

The debug model contains 36 command/state rows: screen-on unlocked, screen-on locked, screen-off locked and headset-connected. `unlockRequired` reflects the default privacy policy; optional exceptions appear in each restriction. SUPPORTED_WITH_RESTRICTIONS describes code/API feasibility, not measured success.

| Capability | Assessment | Implemented boundary |
| --- | --- | --- |
| Time | SUPPORTED_WITH_RESTRICTIONS | Short English capture and native TTS; speech provider must work |
| Calendar | SUPPORTED_WITH_RESTRICTIONS | READ_CALENDAR, synced visible calendar; unlock or personal-read opt-in |
| Notification / WhatsApp read | SUPPORTED_WITH_RESTRICTIONS | Notification access, active notification; locked reads only permitted public content |
| Gmail read | SUPPORTED_WITH_RESTRICTIONS | Existing OAuth token, network, unlock or personal-read opt-in |
| Maps / Google Search | SUPPORTED_WITH_RESTRICTIONS | Authenticated handoff to installed handler; not proof of navigation/results |
| Complete reminder | SUPPORTED_WITH_RESTRICTIONS | Existing local inexact scheduler, prior notification grant; incomplete requests clarify |
| WhatsApp reply | SUPPORTED_WITH_RESTRICTIONS | Unlock, review, usable notification reply action; no delivery guarantee |
| Unspecified latest message | NOT_SUPPORTED | Specify WhatsApp; no generic SMS inbox reader |
| System hotword | NOT_SUPPORTED | No enrolled/validated system hotword detector |
| Screen-off / headset invocation | SUPPORTED_WITH_RESTRICTIONS | OEM must dispatch assistant affordance and present session; pending hardware test |

## Permissions and setup

| Requirement | How used / granted |
| --- | --- |
| RECORD_AUDIO | Grant while unlocked using existing voice setup; native session reports missing grant |
| READ_CALENDAR | Existing calendar setup; reads device-synced calendars |
| Notification Listener access | Explicit Android special-access settings approval; not a runtime permission |
| POST_NOTIFICATIONS (API 33+) | Existing reminder notification setup; reminders cannot promise visible delivery when denied |
| INTERNET | Gmail API, search/maps handlers, explicitly allowed system STT; no cloud LLM |
| Gmail OAuth scopes | Existing Gmail Connect flow while unlocked; Google configuration/test-user access still required |
| Default assistant | Android role on API 29+; voice-input settings earlier; user selects service |
| BIND_VOICE_INTERACTION / BIND_SPEECH_RECOGNITION_SERVICE | Protect exported system services; not runtime grants requested from users |
| Existing foreground service, microphone FGS, WAKE_LOCK | Retained only for legacy wake POC; new system-session path does not use them |
| RECEIVE_BOOT_COMPLETED | Existing reminder rescheduling; not continuous listening startup |

No location grant is needed to pass a destination to Maps. No WRITE_CALENDAR, READ_SMS, SEND_SMS, contacts or exact-alarm privilege is added. Calendar creation continues using a user-reviewed calendar draft.

Android 12+ background-start/microphone restrictions remain applicable; this design uses OS assistant invocation and a visible native surface. Android 13 notification permission affects reminders. Android 14+ microphone FGS restrictions apply to the legacy service, not a newly invented exemption. Android 15/16 and OEM notification redaction may remove content. Project minSdk is 24 and targetSdk 36; modern APIs have guards. Supported SDK compilation is not physical compatibility certification.

## Exact device test procedure

1. Install `build/app/outputs/apk/debug/app-debug.apk`; open Aiko unlocked at least once.
2. Grant microphone access and verify tap-to-speak/STT/TTS first. Install English speech/TTS assets if supported; use system recognition only with the existing online consent setting.
3. Enable notification access; grant calendar permission and verify calendar sync. Grant reminder notification permission. Connect Gmail through the existing unlocked OAuth flow. Receive a test WhatsApp notification using your own test account.
4. Open Settings -> System assistant -> Choose default assistant. Select **Aiko system assistant**; Refresh and verify `systemAssistantSelected: true`. For gesture testing stop Hey Indo listening; for hands-free testing enable it explicitly using the instructions below. Restore the previous assistant in Android settings after testing if desired.
5. Keep personal readouts off initially. Preview native assistant, then test actual OS gesture invocation separately.
6. Lock the phone, invoke the device's assistant affordance, and wait for Listening. If the OEM does not dispatch while locked, record NOT_SUPPORTED on that device; do not replace this observation with a tap preview.
7. Test time; navigation; today's meetings; latest notification; latest WhatsApp message; latest email; Google Search; and a complete reminder. Verify denied reads speak an unlock instruction. Tap Unlock and continue; cancel once, then retry and authenticate. Cancellation must execute nothing.
8. Optionally enable personal readouts while unlocked, repeat locked read tests, and verify private/hidden notification content is still withheld. Disable it again and verify reads require authentication.
9. Test WhatsApp reply with a test notification, explicitly review before Send, and record dispatch separately from actual recipient delivery. Do not use personal recipients for automated tests.
10. Refresh system-session diagnostics in Flutter. Capture result code, role, lock/screen/Doze state, STT latency, TTS startup, provider settings and observed audibility. Mark WORKS only after the actual capability succeeds.

## Execution matrix / failure tests

Run each of the eight feature categories above across these combinations; every unexecuted combination stays PENDING_DEVICE_TEST. Preserve device/Android/OEM/version, feature, grants, invocation, expected restriction, observed code, actual audio route and evidence in the test record.

| Dimension | Required cases | Expected restriction / acceptance |
| --- | --- | --- |
| Display / lock | Screen on unlocked; screen on locked; screen off locked | Correct privacy policy; screen-off invocation only when OS dispatches |
| Power | Doze; normal power | No continuous capture; OS dispatch may vary; inexact reminder delay documented |
| Process | Recently used; process reclaimed; rebooted; never opened after boot; force-stopped | No fabricated success; setup/first unlock and Gmail reconnect as necessary |
| Network | Wi-Fi; mobile; airplane/no internet | Local commands with offline assets work without cloud LLM; Gmail/search network failures reported |
| Audio | Speaker; wired headset; Bluetooth; disconnect mid-command | Correct observed route; interruption stops safely; no route inferred from connection alone |
| Permissions | Mic denied/revoked; calendar denied; notification access off; notifications denied | Action fails honestly and reports required unlocked setup |
| Dependencies | Maps absent; Gmail expired; no WhatsApp notification/reply action; no English assets/model | Clear unavailable/error response, no invented action |
| Authentication | Success; cancel; expiry >120s; screen off during prompt; repeated continuation | At most one validated action; cancelled/expired commands never execute |
| Privacy | Opt-in off/on/revoked; private/secret notification; hidden channel | Personal-read gate enforced and hidden content never spoken locked |
| Speech | No match; microphone busy; TTS voice missing; audio focus interruption | Bounded capture; spoken error when TTS works; visual/code fallback when it cannot |

## Validation and changed-file map

Automated checks are recorded after the final run below. Physical voice invocation, locked readouts, audio routes, reboot/Doze behavior and battery measurements remain pending; this report does not certify them.

- Native entry/policy: `lockscreen/AikoVoiceInteractionService.kt`, `AikoSessionService.kt`, `SystemAssistantActivity.kt`, `SessionAccessPolicy.kt`, `SessionActionExecutor.kt`, `LockScreenProbe.kt`, `LockScreenCapabilityResult.kt`.
- Registration: AndroidManifest.xml and `res/xml/voice_interaction_service.xml`.
- Shared execution: `ai/AssistantRuntime.kt`, `voice/SpeechCoordinator.kt`, `actions/AndroidActionExecutor.kt`, `calendar/CalendarActionExecutor.kt`, `native_bridge/AssistantChannels.kt`.
- Speech adapter/provider: `stt/AssistantRecognitionService.kt`, `ExternalSpeechProvider.kt`, `AndroidSpeechToTextEngine.kt`.
- Account/privacy: `gmail/GmailTokenAccess.kt`, `GoogleGmailAuthorization.kt`, `GmailApiService.kt`, `notifications/AssistantNotificationListener.kt`.
- Flutter: `presentation/system_assistant_settings.dart`, `assistant_screen.dart`, `lock_screen_capability_screen.dart`.
- Tests: `SystemAssistantTest.kt`, `LockScreenPolicyTest.kt`, `test/system_assistant_settings_test.dart`.

### Validation performed (2026-09-30)

- `flutter analyze`: no issues.
- `flutter test`: 37 passed, including native privacy-setting save failure/revocation UI coverage.
- `:app:assembleDebug`: passed; final APK at `build/app/outputs/apk/debug/app-debug.apk`.
- `:app:testDebugUnitTest`: 100 tests, 0 failures/errors, including locked-access policy and single-use/expired/cancelled authentication handoff.
- `:app:lintDebug`: 0 errors, 19 warnings (dependency updates, existing resource/API style suggestions and synchronous persistence). New API-level errors fixed. Final native build/lint used `-x :app:compileFlutterBuildDebug` after the full Flutter build to preserve escaped Windows local.properties paths.
- Connected device: Motorola moto_g35_5G. Read-only secure-settings check showed Google GsaVoiceInteractionService selected, not Aiko. No default assistant setting, privacy opt-in or security setting was changed by tooling.
- No physical locked voice capture, TTS audibility, outgoing message, headset, reboot, Doze or battery result is claimed.
- Final APK installed successfully on the connected Motorola. Android package inspection confirms AikoVoiceInteractionService and AssistantRecognitionService registration with their binding permissions; Google remains selected. Registration/install is not evidence of lock-screen command execution.

### Assistant selection button correction

The Choose default assistant button now opens ACTION_VOICE_INPUT_SETTINGS directly, falling back to ACTION_MANAGE_DEFAULT_APPS_SETTINGS when unavailable or blocked, instead of relying on an unobserved role-request dialog. Both are public [Android Settings intents](https://developer.android.com/reference/android/provider/Settings). Selection remains user-controlled and is checked on return/Refresh.

On the connected Motorola, the settings activity opened successfully. Its Default digital assistant app row opened a chooser containing None, Google and voice_assistant. No option was selected by tooling. Flutter analysis, the settings widget test and Android build/unit tests passed after this correction.

### Settings-tab state correction

Recreating the system-assistant card now automatically queries native status. Returning from Android settings queues a refresh if a previous bridge call is still running. Until the saved preference is loaded, the card shows a loading/unknown state rather than displaying the privacy switch as off. Screen navigation does not write assistant selection or the privacy preference. A regression test verifies selection and enabled privacy are reloaded after disposing/reopening the card. Flutter analysis and all 38 Flutter tests passed. During investigation, the connected phone reported no assistant role holder and an empty voice_interaction_service; the cause of that Android selection state was not established by the UI test.

## Current hands-free extension

The optional software Hey Indo service is now integrated with the selected system assistant. Earlier statements in this report that it is disabled in system mode are superseded by this section. There is still no hardware/system hotword detector. See [hands-free setup and lifecycle](WAKE_WORD_POC.md#hands-free-system-session-integration).

Enable Hey Indo explicitly while unlocked. Its visible microphone foreground service releases capture after detection and requests the native session through the bound VoiceInteractionService. A single-use handoff keeps the detector paused until native microphone/TTS cleanup. Existing read privacy, authentication, structured-intent validation and external-action restrictions remain unchanged. Stop cancels the handoff. Failure to open within 20 seconds or finish within 180 seconds stops the service without claiming success. Manual gesture/preview invocation stops the wake service; re-enable afterwards.

Test on the physical phone: (1) enabled/listening notification; (2) screen-off locked Hey Indo -> time response; (3) repeat twice without re-enabling; (4) WhatsApp with public versus hidden notification; (5) privacy opt-in off; (6) Stop while waiting/listening/in session; (7) unlock cancellation; (8) incoming call/microphone conflict; (9) OEM session launch denial; (10) process kill/reboot; (11) idle battery and false activations. All new physical results remain pending. Code changes: SystemWakeHandoff, WakeWordService, AikoVoiceInteractionService, AikoSessionService, SystemAssistantActivity, AssistantChannels, capability text, Flutter wake/system settings and SystemWakeHandoffTest.

Automated verification for the hands-free integration: Flutter analysis clean; 38 Flutter tests passed; 102 Kotlin unit tests passed; debug APK build and Android lint passed. Physical wake accuracy, locked invocation and battery results remain pending; no microphone was enabled by the implementation tools.

### Locked wake handoff lifecycle correction

Device logs on 2026-09-30 showed SystemAssistantActivity created and resumed while locked, then immediately paused/finished without a completed capture. The session implementation incorrectly called hide() immediately after startAssistantActivity(). The [VoiceInteractionSession API](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession#hide()) describes hiding when user interaction is done.

The session now stays open until its own native presentation is destroyed; a presentation ID prevents an older Activity's cleanup from hiding a newer presentation. The assistant window is disabled in onPrepareShow as recommended for assistant-Activity presentation. The wake-service acknowledgement was moved into the resumed native Activity before command STT, so it no longer invites speech before the handoff opens. A screen-on window flag is held only during the active command; explicit screen-off/pause still cancels capture. Metadata-only resume/pause/capture-start markers aid device diagnosis. End-to-end spoken locked retest is pending after installation.

Validation after lifecycle correction: native compilation, 102 Kotlin unit tests and debug APK build passed. Android lint passed after correcting escaped Windows SDK paths and refreshing stale lint analysis. A physical locked spoken retest is still required; lifecycle log evidence establishes the prior premature closure, not success of the corrected flow.

### Wake capture diagnostics by lock state

A repeated user snapshot advanced audioSecondsProcessed from 115.84 to 392.6499375 with the same detectedAtMs and lockedAtDetection=false. This proves continued capture metering without a newer recorded wake detection; it does not prove speech audibility while locked. The instantaneous RMS/silencing values could have been sampled after unlocking.

Wake diagnostics now retain latestLockedCapture and latestUnlockedCapture separately (meterWindows, maxWindowRmsDbfs, silencedWindows, unsilencedWindows, lastSampleAtMs), currentlyLocked/currentlyScreenInteractive, and locked/unlocked detection counts. Each bucket represents its most recent lock/unlock interval, not total wall time; transitional one-second windows can span a state change. Numeric audio level cannot establish intelligibility or intent. No audio or transcripts are saved. lastCommand is retained separately from lifecycle markers so pause does not erase the command outcome. Detection thresholds are unchanged pending actual locked-input evidence.

### Confirmed locked detections and screen-off launch correction

User diagnostics recorded three locked detections (lockedDetectionCount=3, unlockedDetectionCount=0), latest detection with screenInteractiveAtDetection=false, and 36 unsilenced locked meter windows (maximum RMS approximately -19 dBFS). This establishes real wake detection with the screen off for that trial, not command completion. Android event logs show each native Activity launch scheduled to pause for sleep before its onCreate ran; the latest record was NATIVE_SESSION_PAUSED with the screen still off and no command result.

SystemAssistantActivity now declares android:showWhenLocked and android:turnScreenOn in the manifest so ActivityTaskManager has the visibility/wake flags at launch, in addition to existing runtime flags. These are [supported Activity attributes](https://developer.android.com/reference/android/app/Activity#setTurnScreenOn(boolean)); keyguard is not dismissed. Screen-off command response must be retested after installing this change. No wake sensitivity/model changes were made.

### Transient startup pause handling

The next device log still showed ActivityTaskManager scheduling a sleep pause immediately at screen-off launch. The screen became interactive, but the app itself finished on that early pause before command capture. SystemAssistantActivity now starts automatic recognition only after it is resumed and has window focus. An initial pause before interaction begins cancels any work but does not immediately finish the wake presentation; startup is bounded to five seconds and records COMMAND_WINDOW_NOT_READY if focus never arrives. Once a command starts, pause still cancels and closes the wake session. No background microphone capture or security bypass is introduced. Graphics allocator messages did not establish the voice failure; no renderer setting was changed. Locked spoken retest remains required.

### Navigation initiated while locked

The native system assistant now automatically requests Android's unlock prompt after a validated NAVIGATE/OPEN_MAPS command receives UNLOCK_REQUIRED. Previously this required tapping Unlock and continue. STT/TTS completes before the prompt requests focus. The original validated command is retained in memory for at most 120 seconds and consumed once after successful unlock. Cancellation, expiry, session destruction, or a still-locked device prevents execution. Prompt/cancellation/error events are recorded without destination details.

This supports initiating navigation while locked, followed by authentication and the supported Google Maps navigation intent. It does not display Maps through a secure keyguard or bypass authentication. API 24–25 requires manual unlock and reinvocation; requestDismissKeyguard requires API 26+. Physical-device prompt, cancellation, and Maps-launch tests remain pending. Unit coverage includes exact destination preservation, single execution, expiry, cancellation and navigation-only automatic prompting.

Reference: https://developer.android.com/reference/android/app/KeyguardManager#requestDismissKeyguard(android.app.Activity,android.app.KeyguardManager.KeyguardDismissCallback)

### Opt-in locked WhatsApp notification replies

Settings > System assistant now offers Allow WhatsApp notification replies while locked, off by default and editable only while unlocked. Both this and personal readouts must be enabled. In the native system assistant, say "Reply WhatsApp I'll call you later". A review dialog identifies the latest active WhatsApp notification recipient and dictated text; tap Send reply or Cancel. Anyone holding the locked phone can use this opt-in. This does not enable instant sending, Gmail replies, arbitrary new conversations or hidden previews.

The notification listener checks consent, active notification visibility and Notification.Action.isAuthenticationRequired both when obtaining the target and immediately before RemoteInput dispatch. API 31+ is required for locked replies; older versions require unlock. A changed/dismissed notification, revoked access or authentication-required action is rejected. Existing draft expiry and single-use confirmation remain. A successful PendingIntent dispatch means handed to WhatsApp, not verified delivery.

Physical testing remains pending: eligible WhatsApp and Business replies, authentication-required action, hidden notification, changed target, opt-in revocation, cancellation and expiry. No test messages have been sent automatically.

### Optional direct phone calls

Hands-free calling can be explicitly enabled while unlocked under System assistant > Configure hands-free phone calls after granting CALL_PHONE. The session gate then permits only CALL_PHONE through to the checked TelecomManager.placeCall executor while locked. Personal-readout opt-in is independent. Exact contact lookup needs READ_CONTACTS and one distinct number; missing/ambiguous matches are rejected. Calling settings default off and disclose that anyone invoking the assistant can request SIM calls. Carrier charges and system SIM-selection prompts may apply. Emergency/short-code calls remain outside this POC.

Status: implementation complete; physical locked-call success PENDING. No live call was placed during validation. Android owns call UI/authentication and can reject requests. Turning this option off restores the existing unlock-and-dialer flow. No keyguard bypass or speaker authentication is implemented.

### Experimental END_CALL control

The independent voiceHangup opt-in permits a validated END_CALL action in a locked native assistant session, subject to Android 9+ and ANSWER_PHONE_CALLS permission. Uses TelecomManager.endCall, a deprecated public API; does not replace the default phone app. Native assistant includes a manual End current phone call button. An accepted Android return is not presented as an observed disconnect. Permission/opt-in denial and false API returns never report success.

Voice recognition DURING a call remains PENDING physical-device testing, and may be unavailable because of Android microphone arbitration or the existing Bluetooth communication-mode guard. This feature does not establish continuous Hey Indo availability during a call. No live call was terminated by automated tests. See PHONE_CALLS.md for supported commands, setup, API restrictions and test matrix.

### Spoken confirmation for native WhatsApp reply review

The native system assistant now follows its draft readout with a bounded voice-confirmation prompt. Wait for the prompt, then say "Send reply" or "Cancel reply" (no additional wake phrase). Only exact final recognition is accepted; partial results, negation, extra words and timeout cannot dispatch. The same reviewed single-use draft token is checked before listening and after recognition. Existing expiry, foreground state, locked-reply preferences, notification-change checks, and Android/WhatsApp authentication restrictions remain enforced.

Confirmation starts after the original pipeline's TTS and cleanup finish. Leaving the Activity or screen-off cancels recognition and clears the draft. Buttons remain available if voice recognition fails. No confirmation transcripts or message bodies are added to diagnostics. Existing Flutter review UI retains its manual buttons; this automatic follow-up is in the native system-assistant flow used while locked. Physical speech/RemoteInput testing is pending. No real message was sent in automated tests.

## SMS inbox and reply addition
READ_SMS and REPLY_SMS require unlocking in this POC, even when locked personal readout is enabled. READ_SMS also requires Aiko selected as default assistant and SMS permission. Reply opens the messaging app for review and manual Send; it does not send through SmsManager. Physical-device testing is pending. See SMS.md.


## Incoming call SMS reply
Aiko Phone POC now provides a Telecom InCallService call UI and reviewed decline-with-SMS request. Default Phone role is separate from default assistant. Caller identity is omitted while locked; SMS confirmation requires unlock. Real device validation is pending. See INCOMING_CALL_REPLY.md. Voice listening during an active call remains unverified.


Incoming-call reply confirmation now accepts final Send reply / Cancel reply after the TTS prompt, while unlocked. Wake capture pauses during review. Android may deny microphone/audio focus during ringing; no sending occurs on recognition failure. Physical-device confirmation tests remain pending.


## Bluetooth wake microphone recovery

Wake capture now requests the sole available Bluetooth input when unambiguous and verifies AudioRecord.routedDevice before decoding audio or reporting LISTENING. A preferred-device request alone is not proof of routing. Bluetooth setup gets five seconds for microphone routing and samples. Temporary route rejection, loss, timeout, and wrong-microphone failures get at most three reconnect attempts with backoff; ten seconds of captured audio resets the retry budget. Permission errors and another communication session are not retried. No command or external action is replayed.

Connected-device inspection showed the wake service running with no preferred communication device and SCO inactive. This snapshot does not establish why the headset disconnected. Headset testing outside calls, across reconnects, and while locked remains pending. System STT microphone selection remains provider controlled. The existing Bluetooth integration requires Android 12+ communication routing.
Reference: https://developer.android.com/reference/android/media/AudioRouting#getRoutedDevice()