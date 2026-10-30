# English speech POC

Update: final transcripts now pass through [local command routing](command-routing.md)
and TTS speaks the routing outcome. The echo-only behavior described below is the
earlier speech milestone. Speech capture/settings/benchmark behavior is retained;
benchmark outcomes now also distinguish proposals, clarification and deferred models.

This milestone implements foreground English voice capture, transcription and spoken
readback. It does not route or execute commands. The response is explicitly
`You said: <final transcript>`; voice previews use fixed English text.

## Running it

1. Install the debug APK on an Android device with a recognizer and TTS engine.
2. Expand **English speech settings and benchmarks**, then load capabilities/voices.
3. Offline STT is preferred. System recognition is opt-in because it may send audio
   to its provider. Install English recognition data in the device's speech settings
   if unavailable. This app does not download language packs automatically.
4. Start Voice Interaction and grant the microphone permission. Wait for LISTENING,
   speak English, then pause for the recognizer's final result. Partial text is marked
   partial and never sent to TTS. Cancel stops recognition/playback.
5. Choose an English TTS voice, rate (0.5–2.0), pitch (0.5–2.0), and offline-only
   policy. Preview voices to identify male/female-sounding options available on that
   engine. Android exposes no standardized voice-gender property; gender is not
   inferred from pitch or opaque engine-specific IDs.

Denied microphone permission produces a retry/settings instruction. No permission is
requested for a TTS preview. The active request is cancelled on app pause, engine
cleanup, audio-focus loss or Flutter provider disposal. Permission dialogs are treated
separately: recognition waits until permission is granted and the activity is resumed.
Only one speech request or capability inspection may run at a time.

## Android implementation

- `stt/AndroidSpeechToTextEngine.kt` implements `SpeechToTextEngine` with Android
  SpeechRecognizer, English `en-US`, partial/final callbacks, stable error codes,
  cancellation, recognizer destruction and a 30-second recognition deadline.
- API 31+ uses `isOnDeviceRecognitionAvailable` and the explicitly on-device factory.
  API 33+ additionally probes installed English support with `checkRecognitionSupport`.
  Older engines, unavailable probes and probe timeouts report **unknown** English
  offline availability rather than claiming support. API <31 cannot use the dedicated
  on-device factory and requires opt-in system recognition.
- If installed English support is reported missing, offline-only capture is blocked.
  Unknown support is attempted through the on-device recognizer and may fail with a
  language-data error. There is no silent retry through a network recognizer after
  an on-device error.
- `tts/AndroidTextToSpeechEngine.kt` implements `TextToSpeechEngine`, enumerates
  English voices, filters known uninstalled voices, applies rate/pitch and waits for
  UtteranceProgressListener completion. Initialization is bounded to eight seconds;
  playback is bounded to 30 seconds. Initialization/playback failures remain failures.
- TTS offline status means **offline-capable according to the engine's metadata**.
  Voices marked network-required are blocked by default. Actual airplane-mode
  operation still requires a physical-device test.
- `voice/SpeechCoordinator.kt` orchestrates replaceable interfaces and releases audio
  focus/resources on success, failure or cancellation. No SLM/action implementation
  is connected. `SpeechAudioFocus` pauses competing audio and cancels on focus loss.
- MainActivity owns microphone permission/lifecycle callbacks; AssistantChannels owns
  the main-thread coroutine scope and exactly one pending MethodChannel result.

See [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer),
[TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech),
[Voice](https://developer.android.com/reference/android/speech/tts/Voice) and
[UtteranceProgressListener](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener).

## Bridge and diagnostics

The existing version-1 channel has additive fields/methods:

- `startVoiceInteraction`: microphone -> STT -> readback TTS.
- `speak`: English TTS only, used by voice preview.
- `speechCapabilities`: recognizer availability, English offline support, installed
  English voices, default TTS engine, initialization time and device identifiers.
- `cancelVoiceInteraction`: request-ID-scoped cancellation; stale IDs do not cancel
  another request.
- VoiceRequest optionally carries `options`: allowSystemRecognition, voiceName,
  rate, pitch and offlineTtsOnly. Native validation rejects invalid/non-finite settings.
- Session events contain partial/final transcripts. Persistent structured logs contain
  fixed event metadata only; transcript, OAuth, arbitrary errors and tokens are excluded.
- Responses contain `speech` metadata and measured/unavailable/not_run timing states.
  AI execution mode stays notRun. STT/TTS execution modes are reported independently.

Timing definitions (monotonic clocks):

| Measurement | Boundaries |
|---|---|
| STT latency | onEndOfSpeech to final onResults; unavailable if the provider omits speech-end |
| STT capture | startListening request setup to final result; includes utterance duration |
| TTS startup | speak submission to onStart callback; engine callback proxy, not acoustic loopback |
| TTS initialization | engine construction to initialization callback; shown independently |
| Total | native session start through completion/cancellation; excludes permission dialog time |

Recognizer and TTS errors, engine, selected voice and offline/network/unknown state
appear in the Flutter debug dashboard. A system recognizer's actual network use is
not exposed by Android, so it is never labelled definitively local or cloud.

## Benchmark procedure

Load capabilities first to attach device metadata. Enable benchmark recording and
select quiet, normalRoom, noisy or vehicle and short or long. Speak the displayed
reference exactly. Each Start invocation records a trial (including failures and
cancellations); voice preview does not count as an STT trial.

Up to 100 trials remain in memory. **View / copy benchmark JSON** is an explicit
export view containing reference text, actual transcript, outcome, device metadata,
engine/voice/status, settings, measured timings and word error rate. Nothing is
uploaded or written to application logs. App/provider disposal loses these trials;
copy them before leaving. Export contains personal speech if used outside the supplied
test prompts.

WER uses case/punctuation normalization and word-level edit distance; it does not
equate written digits and spoken number words. Missing recognition gets null WER,
never zero. Failures must remain in the denominator when assessing reliability.
Environments are operator labels, not detected noise levels. Repeat each condition
on each device, record network/airplane state separately and compare cold/warm trials.
Vehicle testing should use a parked vehicle or a passenger.

## Limits and remaining device validation

- No physical microphone, voice quality, airplane-mode or male/female voice validation
  has been performed by the automated tests. Do not treat test-double timings as evidence.
- OEM recognizers control endpointing, partial-result frequency, language data and
  service availability. Missing/revoked permissions, mic privacy toggles and unavailable
  engines must be tested on the target devices.
- Foreground only: no wake word, background listening, assistant role, lock-screen
  service or custom Bluetooth audio routing is added in this milestone.
- There is no standard gender selection contract across Android TTS engines. Users
  must audition voices; devices with only one English voice cannot provide both.
- TTS availability flags are provider metadata, not independent proof of installed
  functioning voice data or zero network traffic.
- Recognition/playback deadlines may cut off very long utterances. Flutter adds a
  90-second outer deadline (including permission waiting) with scoped cancellation.
- CPU/RAM, acoustic latency, battery, noise-level calibration and the 2–3 device
  compatibility report remain separate physical benchmark work.
- No Indian languages, translation, voice cloning, personalized voice synthesis,
  cloud LLM or assistant actions were implemented.

## Verification — 28 September 2026

- Flutter analysis: no issues.
- Flutter tests: 14 passed (wire/error handling, Riverpod lifecycle, cancellation,
  partial-result separation, benchmark outcomes/WER and widget behavior).
- Kotlin unit tests: 12 passed (7 contract/foundation tests and 5 injected-adapter
  speech orchestration tests); zero failures/errors.
- Debug APK assembled successfully: `build/app/outputs/flutter-apk/app-debug.apk`.
- Android lint: zero errors, two warnings (a newer coroutine release is available;
  the existing drawable-v21 folder is redundant). Existing Gradle template/toolchain
  deprecation warnings remain.
- Flutter regenerates unescaped SDK drive letters in local.properties. After building,
  restore escaped drive letters and rerun lint analysis against the built sources:
  `gradlew.bat :app:lintAnalyzeDebug --rerun :app:lintReportDebug --rerun :app:lintDebug -x :app:compileFlutterBuildDebug`.
  No application lint findings were suppressed. This skips only the already-completed
  Flutter compilation to avoid regenerating the machine-local configuration again.
- No real-device voice capture/playback or environment benchmark numbers are claimed.

## Changed files

Native: MainActivity, AssistantChannels, Models/SpeechOptions, STT/TTS interfaces and
Android adapters, SpeechCoordinator, SpeechAudioFocus, manifest and coroutine dependency.
Flutter: bridge/contracts, Riverpod session state/controller, speech settings/controls,
debug screen and `benchmark/speech_benchmark.dart`.
Tests: speech benchmark tests, updated widget tests, speech options/log privacy tests,
and injected-adapter SpeechCoordinator tests. FoundationCoordinator is retained only
as the earlier foundation fixture; the app now uses SpeechCoordinator.
# English recognition recovery update

Service-disconnection recovery now makes at most two recognition attempts per
request, with a cancellable 300 ms delay before reconnecting. An opted-in local
failure can switch to the system recognizer; otherwise it reconnects to the same
engine. A second failure is reported rather than retried indefinitely. Home now
offers explicit recovery for STT_SERVER_DISCONNECTED as well. Android defines this
as a disconnected recognition service, which can include provider crashes; it is
not proof of an internet outage. See the [Android error contract](https://developer.android.com/reference/android/speech/SpeechRecognizer#ERROR_SERVER_DISCONNECTED).

Offline selection now accepts installed English locale variants (including en-IN
and en-GB), preferring en-US when present. Recognition uses the selected locale;
capabilities expose that locale and the reason offline recognition is unavailable.
The selection uses Android's [installed language report](https://developer.android.com/reference/android/speech/RecognitionSupport#getInstalledOnDeviceLanguages()).
Missing offline support is not simulated. Home offers an explicit **Use system
recognition and retry** action, disclosing possible online audio processing. It
preserves TTS settings. After opt-in, local language/service capability failures
can retry the system recognizer once; cancellation, no-match and permission errors
do not trigger a fallback. The opt-in lasts for the current app session.

Verification: Flutter analysis clean, 18 Flutter tests and 34 Kotlin tests passed,
and debug APK assembly passed. Actual speech availability still needs a device run.


## STT_NO_MATCH regression investigation

On the connected Motorola phone, RECORD_AUDIO was allowed, the wake foreground service was stopped at inspection, and recent command-recognizer recording sessions were reported as not silenced. These observations do not prove that intelligible speech reached the recognizer. The cause of repeated STT_NO_MATCH remains unconfirmed.

The English speech settings now include an explicit **Use system STT instead of on-device STT** switch. It is off by default. Enabling it also grants the existing system-recognition opt-in; Android may send audio online. Turning off the network-capable recognition permission also turns off this preference. The locked/background wake command path continues to use default offline options. The old availability fallback does not force the system engine when the on-device engine reports available.

The debug dashboard exposes STT capture diagnostics on failed requests: selected locale, readiness, readiness delay, speech-begin callback, peak recognizer-reported RMS when supplied, numeric Android error and attempt duration. RMS is provider-reported and is not a calibrated microphone measurement; missing RMS is not proof of silence. No raw audio is stored. The bridge also blocks new microphone operations during wake-recorder shutdown until its cleanup completes.

Device comparison: stop Hey Indo, enable the explicit system-STT switch, tap the microphone, wait for LISTENING and speak a short command. Compare with the switch off. If both fail, inspect STT capture diagnostics. Live recognition recovery remains pending; automated tests do not validate microphone speech accuracy.

## Audio-focus conflict found during system-STT comparison

The device audio-focus history showed repeated Aiko playback-focus requests followed roughly 40 ms later by exclusive focus requests from com.google.android.tts (the configured system recognition service), then Aiko abandoning focus. The coordinator previously cancelled its recognition coroutine on playback-focus loss. This is a concrete cancellation path; it does not establish the cause of the separate on-device en-IN NO_MATCH result.

Playback focus is now acquired only around TTS, after STT completes, in foreground, wake-background and lock-screen paths. Released focus callbacks are invalidated so an old acknowledgement callback cannot cancel a later command. Explicit user cancellation and activity lifecycle cancellation remain. A regression test asserts no playback-focus acquisition during recognition, focus during TTS and release after completion. Live system-STT recovery still needs a spoken device trial.

## September 30 regression audit

The phone showed OFFLINE_ENGLISH_UNAVAILABLE, with the wake foreground service running. Its app update timestamp was newer than the previously failed installation attempt. The code still contained the audio-focus fix; this was not a confirmed rollback of navigation.

A confirmed app defect was that recognition choices existed only in Riverpod memory: restarting or replacing the app returned to offline-only defaults. Recognition consent and explicit system-engine selection are now saved through the native bridge into private SharedPreferences and restored before the first Flutter screen. Missing/invalid or unreadable preferences fall back to offline-only; no migration silently enables online STT. The setting must be selected once in this build to seed storage. Only two booleans are stored, not audio, transcripts or OAuth credentials.

The wake-listener gate now reports WAKE_LISTENING_ACTIVE rather than generic VOICE_BUSY for manual speech conflicts. Stop Hey Indo before a tap-to-speak comparison. Continuous listener handoff remains available for wake-triggered requests. Repeated device speech success, local model recovery, and end-to-end action execution remain pending; compilation and tests do not prove those outcomes.
# Hindi pilot

Select Hindi in Speech language and settings, then reload voices and capabilities. The saved `hi-IN` preference is shared with the native lock-screen assistant. System recognition remains opt-in; selecting Hindi never enables online recognition automatically. Offline Hindi needs provider support and installed model data. Hindi TTS needs an installed Hindi voice, or explicit permission to use network voices.

Supported initial templates include Hindi/Hinglish weather, calls, search, notification reading, maps, email reading and time. Payloads retain their original script. Negated/conditional requests are blocked; existing validation, contact resolution, unlock requirements and send confirmations still apply. Hindi weather/time and selected acknowledgements are localized. Arbitrary source text, many action responses, and reply-confirmation dialogues are not translated; English response text uses an English voice. Reminder grammar and general multilingual SLM accuracy are not covered by this pilot. The existing wake phrase is unchanged.

Rebuild/reinstall Android before testing. Test Hindi recognition and voice preview, then weather and notifications while unlocked and locked. Offline availability, mixed-language accuracy and contacts stored in a different script require physical-device testing. Existing English benchmarks are not Hindi accuracy measurements.

# Kannada

Select **ಕನ್ನಡ (Kannada)** in Speech language and settings. The saved `kn-IN` locale is used for recognition and installed Kannada TTS voices. Common command templates include `ಸಮಯ ಎಷ್ಟು`, `ಬೆಂಗಳೂರು ಹವಾಮಾನ ಹೇಳು`, `ರಾಹುಲ್ ಗೆ ಕರೆ ಮಾಡಿ`, `ನೋಟಿಫಿಕೇಶನ್ ಓದಿ`, and `ನಿಫ್ಟಿ 50 ಹುಡುಕಿ`. Negated and compound Kannada commands do not execute. Supported fixed responses and weather reports are localized; arbitrary source text is preserved rather than automatically translated. English source text uses an English voice. Kannada recognition is selected explicitly, as with Hindi; automatic language switching is not provided. On-device model and voice availability must be checked on the actual phone. System recognition requires the existing explicit opt-in.

