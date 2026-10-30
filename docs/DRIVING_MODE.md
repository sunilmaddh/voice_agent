Automatic personalized driving replies now run while the phone is locked when Driving Mode and automatic SMS were previously enabled. Configure/enable while unlocked. Manual call-reply flows still require unlock. The sent receipt rechecks driving settings before declining; locking alone does not cancel an enabled driving reply. Test after normal screen lock, with the screen off, and after a process restart. Before the first unlock after reboot, credential-protected settings are not supported.

# Driving mode POC

Scope follows section 6 of Technical POC Task_Personal_AI_Assistance.docx. This is basic call handling, not an AI call receptionist.

## Section 6 verification: implemented with restrictions, validation incomplete

Source review confirms the mode toggle, Telecom/InCallService integration, incoming ringing check and optional canned SMS path. This is not yet a physical-device acceptance sign-off.

- **Exact invocation:** the software keyword configuration now accepts **Hey Aiko** as well as **Hey Indo**. Say either phrase, wait for the acknowledgement/command listening, then say "I'm driving. Don't disturb me." The router also accepts the complete POC text when captured through tap-to-speak, with common punctuation/apostrophe variations. The wake detector releases audio before separate STT starts, so a single uninterrupted wake-plus-command utterance is not reliably supported. Hey Aiko acoustic accuracy and regression testing of Hey Indo remain pending physical-device testing; unit routing tests do not establish keyword accuracy.
- **Current canned message:** "I am driving right now. I will call you back later." This is an SMS, not a spoken announcement to the caller, and it does not personalize the name Bhabani. Automatic SMS must be separately enabled; mode activation alone only enables ringtone silencing.
- **Call-state edge case:** driving handling currently runs once from `AikoInCallService.onCallAdded` and requires the call already to be RINGING. `onStateChanged` updates the UI but does not re-run driving handling. A call added in another state and later becoming RINGING can be missed. This needs a guarded state-transition implementation/test before claiming robust call-state handling.
- **Lock screen:** the current app policy requires unlock to change driving settings or automatically send this SMS. An already enabled mode can attempt ringtone silencing while locked. These unlock checks are POC implementation choices, not a claim that Android universally forbids locked SMS.
- **Role and permissions:** default Phone/dialer role is required for this integration; default assistant alone is insufficient. Automatic SMS also checks SEND_SMS and READ_PHONE_STATE, Android 11+, visible telephone number and mapped SIM. Voice entry requires microphone permission; optional wake listening requires its existing foreground-service/notification setup. No CallScreeningService or screening role is installed by this feature.
- **Restrictions versus impossibility:** there is no implementation of TTS injection into a regular cellular call and no supported normal-app path is claimed here. This is distinct from a local TTS acknowledgement or sending SMS. Do not describe missing implementation, an app policy restriction, or untested OEM behavior as a universal Android prohibition.

Outstanding validation: actual voice activation, incoming-call state transitions, ringtone suppression, successful and failed SMS receipts, correct SIM/caller, locked and screen-off behavior, and manufacturer/version comparison. Until these are recorded, section 6 is **partially satisfied**, not fully validated. This verification update changes documentation only.

## Use

Open Settings > Driving mode. Enable Silence incoming calls. Alternatively say "I am driving", "Enable driving mode", or "Hey Aiko, I'm driving. Don't disturb me." Disable with "Turn off driving mode". Settings persist until disabled. Activation does not enable wake listening or change Android roles or permissions. Use the existing microphone button or previously enabled Hey Indo.

Choose Aiko as default Phone in System assistant > Configure hands-free phone calls > Incoming call SMS reply / default Phone setup. The default assistant role alone is insufficient. Driving mode without the Phone role saves the preference but explicitly reports call handling unavailable.

Incoming ringing calls use TelecomManager.silenceRinger. Other app notifications and global Android Do Not Disturb settings are unchanged. Aiko does not answer and play TTS to the remote caller. No call audio injection or receptionist is implemented.

Optional Automatic driving SMS and decline is OFF by default. Its activation dialog explains the exact message, automatic sending without per-call review, repeat-call charges, and unlock requirement. The fixed text is "I am driving right now. I will call you back later." Voice activation never turns this option on.

Automatic SMS requires Android 11+, Aiko default Phone, SEND_SMS and READ_PHONE_STATE, an unlocked phone, a visible normal telephone number, and a valid subscription mapped to the incoming call. The existing SIM-specific SmsManager sender reports sent/failure via a PendingIntent. A successful sent receipt requests decline of only the same still-ringing call; recipient delivery is not verified. Failure, timeout, process loss, disabled driving settings, or lock before callback leave the call unchanged. The SMS cannot be recalled once submitted. Manual and automatic reply attempts share a once-per-live-call guard. Repeated separate calls can receive separate messages; no durable per-caller throttling is claimed.

## Feasibility and validation

| Capability | Implementation status | Device evidence |
|---|---|---|
| Voice toggle to typed intent and local action | Implemented; requires unlock | Routing tests; live speech pending |
| Saved driving settings | Implemented | Restart test pending |
| Incoming-call detection and silence | Implemented through InCallService/default Phone | Physical-device test pending |
| Canned SMS then decline | Implemented with explicit opt-in and sent receipt | Real caller/SIM test pending |
| Locked incoming call | Silence attempted; automatic SMS blocked by current POC policy | Locked/screen-off test pending |
| Spoken response to remote caller | Not implemented | No supported audio-injection path claimed |
| Automatic driving detection, Android Auto UI, global DND | Outside this change | Not implemented |

CallScreeningService was considered: screening/rejection alone would not provide the existing live call controls and SMS result tracking. This POC reuses its InCallService instead of requesting an additional screening role. OEM ringtone/audio behavior, permissions, carrier SMS behavior, and lock state require physical validation. Bluetooth wake capture remains separately unverified; this feature does not resolve the reported low microphone levels.

Tests to run on each target device: toggle on/off and restart; Phone role absent; SMS permission absent; normal incoming call with auto reply off; opted-in reply on each SIM; hidden/short-code caller; SMS failure/timeout; caller ends before receipt; locked/screen-off; disable before callback; manual reply racing automatic reply. Test only with consenting recipients. No real SMS or calls are generated by automated tests.

Diagnostics: Settings shows enabled, automatic reply, Phone-role status, last driving event and latest call-reply SMS code. Native status includes event time and pending-device-validation marker. No caller numbers or SMS contents are logged. All commands use Tier 1 and LOCAL orchestration; carrier transport is still required for SMS.

Sources: [TelecomManager](https://developer.android.com/reference/android/telecom/TelecomManager), [default Phone application](https://developer.android.com/develop/connectivity/telecom/dialer-app), [SmsManager](https://developer.android.com/reference/android/telephony/SmsManager).

## Changed files

- Native policy: communications/DrivingMode.kt, AikoInCallService.kt, CallReplySmsSender.kt
- Routing/contracts: intent/LocalIntentRouter.kt, TypedCommand.kt, CommandValidator.kt, native_bridge/contracts/Models.kt, lib/native_bridge/contracts/models.dart
- Pipeline/bridge: actions/AndroidActionExecutor.kt, ActionReportingExecutor.kt, voice/SpeechCoordinator.kt, native_bridge/AssistantChannels.kt
- Flutter: presentation/driving_mode_settings.dart, assistant_screen.dart
- Tests: DrivingModeRoutingTest.kt, IntentRouterTest.kt, test/bridge_test.dart, test/driving_mode_settings_test.dart

Automated validation: Android debug APK built; 155 Android unit tests passed; Android lint passed; Flutter analysis reported no issues; 38 existing Flutter tests and the new driving-mode opt-in widget test passed. These checks do not validate real ringing, SMS delivery, or manufacturer-specific behavior. No device benchmark values are fabricated.
# Personalized driving replies

Driving settings now include a saved personal SMS response. Type a message or select Dictate reply, review the transcription, and tap Save reply. Dictation uses the selected English/Hindi speech settings and existing system-recognition consent; dictated content is a draft, never a routed command. No SLM is required. Voice capture pauses wake-word recording and cancels when the native session is cancelled.

Automatic driving SMS remains explicitly enabled in settings. The confirmation displays the saved message. Editing a draft does not change the active reply until saving succeeds. Saving does not enable automatic SMS or Driving Mode. Replies are limited to 70 UTF-16 characters to fit a single Unicode SMS, including Hindi; line breaks/control characters are rejected.

Incoming eligible calls use the saved response instead of the fixed default. Existing restrictions remain: Android 11+, Aiko as default Phone, SEND_SMS/READ_PHONE_STATE permissions, valid visible caller number and a usable SIM. The same call is attempted once; a separate repeat call can receive another reply. Android's SMS sent callback must succeed before declining a still-ringing call. Delivery is not verified. SMS failure leaves the call unchanged apart from driving-mode ringing suppression.

Physical-device verification remains pending: save and restart; dictate English/Hindi; test eligible incoming calls, permission denial, locked-phone automatic replies, hidden caller, SMS failure and repeated callbacks. Configure the response before driving. This feature sends SMS and does not inject speech into caller audio or detect driving automatically.



Driving Mode activation now saves enabled=true and automaticReply=true together. Deactivation saves both false. A complete validated voice command can activate/deactivate while locked through both system-assistant and wake-service fallback paths. Settings/message editing still require unlock. Automatic replies use the saved personal message and existing default Phone, permission, visible-number and SIM restrictions. Switching automatic SMS off separately pauses sending until Driving Mode is next enabled.
