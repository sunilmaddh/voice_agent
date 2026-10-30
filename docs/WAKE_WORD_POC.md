> Current wake phrase: **Hey Indo** (restored at user request). Sakura references below describe the temporary rename. Enable **Hey Indo** after updating.

# Hey Indo wake-word POC

## What is implemented

This is an **explicitly enabled, continuous listening service**, not an always-enabled system hotword. Open unlocked Aiko → Settings → English speech settings and benchmarks → **Hey Indo — wake-word POC**. Grant microphone and notification permissions, then press **Enable Hey Indo**. Wait for `LISTENING` before locking the phone. The foreground microphone notification includes **Stop listening**. Listening resumes after every interaction and has no application-imposed time limit. Stop listening disables it. Fatal service errors, Android stopping the process, updates, and reboot still stop the service; reopen and enable again in those cases.

Say **“Hey Indo” (in-doh)**, pause, then give the command after the app shows listening or after the spoken background acknowledgement finishes. “Hey Google” still belongs to Google. Detection does not identify/authenticate the speaker.

- If Aiko is resumed and unlocked with an idle voice controller, the detector releases its microphone and hands off to the existing STT → router → validated actions → TTS pipeline. Manual voice operations are blocked while the detector owns the microphone.
- If the app is backgrounded or the phone locked, a bounded offline English STT session follows the spoken acknowledgement. “What time is it?” receives a native TTS response. Other commands ask the user to unlock/open Aiko and repeat. No personal data, external activity, SMS, email, calendar action, or reminder is executed in this background path.
- The service does not turn on the screen, dismiss keyguard, or launch an activity from the background. The notification's Open action goes to the normal app through system interaction. No full-screen intent, overlay, accessibility workaround, boot receiver, battery exemption, or automatic restart is added.

This implements the requested opt-in locked/screen-off **software listening mechanism**. Actual spoken detection reliability, OEM survival, audio routing and power consumption remain physical-device acceptance tests. Installed offline English Android STT/TTS assets are still required for the command and response stages, independently of the bundled keyword model.

## Runtime and model

`WakeWordEngine` separates the detector from the Android service. `SherpaWakeWordEngine` uses the official **sherpa-onnx 1.13.8**, static ONNX Runtime Android AAR, with the **phonetic Zipformer KWS 3M (2025-12-20)** model. This model supports Chinese/English internally, but this app configures only English pronunciation paths for “Hey Indo”; no other language feature is enabled. There is no vendor account, access key, model download at app runtime, or cloud transcription for wake detection.

The bundled model uses chunk-8 INT8 encoder/joiner and FP32 decoder. English phoneme paths `HH EY1 IH1 N D OW0` and `HH EY1 IH1 N D OW1` cover “Hey Indo” with different final-syllable stress, mapped to `HEY_INDO`. The configuration uses threshold `0.1`, keyword score `3.0`, eight active paths, one inference thread, 16 kHz mono PCM and a 100 ms maximum read chunk. These settings require real-user accuracy/false-trigger tuning; there is no fabricated confidence score. The original English BPE model missed a synthetic “Hey Aiko” fixture on the Motorola phone, so it was replaced rather than treating successful loading as successful detection.

Runtime source and model references:

- [Official sherpa-onnx release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8)
- [Official keyword-spotting Android examples](https://k2-fsa.github.io/sherpa/onnx/kws/apk.html)
- [Keyword model downloads](https://github.com/k2-fsa/sherpa-onnx/releases/tag/kws-models)
- [Model configuration and keyword API](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/kotlin-api/KeywordSpotter.kt)

Bundled artifact hashes/sizes and upstream runtime digest are in `tooling/wake_word/artifacts.json`. The AAR checksum was verified against the GitHub release asset digest. Runtime licensing and the new model's original author card are preserved in `tooling/wake_word/LICENSE-sherpa-onnx` and `PHONETIC_MODEL_CARD.md` (Apache 2.0 declaration). `MODEL_CARD.md` preserves the old model's provenance. The new [author model repository](https://modelscope.cn/models/pkufool/icefall-kws-zipformer-zh-en-3M-2025-12-20) and [official deployment documentation](https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html) describe this model. Cached download/extraction files under tooling are not APK assets; only the four required model/token files plus the keyword definition are packaged under `assets/wake_word`.

## Android permissions and security

Permissions: `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` (API 34+), `WAKE_LOCK`, and notification permission on API 33+. The POC deliberately requires notifications to be visible even where Android technically permits a foreground service without notification consent. Disabled notification channels cause a recoverable start failure.

The service starts only from an explicit button in the resumed, unlocked activity. It uses microphone foreground-service type, a persistent generic notification and a timed partial CPU wake lock; the display is not kept on. No assistant role is needed for this software detector. The separate `ACTION_ASSIST` gesture still needs the default assistant selection.

[Android restricts background microphone-service starts](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start). Starting while the app is visible is essential; a default-assistant role does not automatically grant portable custom DSP hotword access. Android/OEM task killing, privacy microphone switches, resource contention and missing recognition models can still prevent operation. No unconditional screen-off success claim is made.

Audio samples are processed in memory only. No PCM or command transcript is stored by the wake service. Diagnostics contain state, engine/version, session limit, timestamps, model/audio startup duration, captured-audio duration until detection, cumulative decode wall time, observed lock/power state at detection, and STT/TTS timings/errors. Captured duration is **not detection latency**; true onset-to-trigger latency needs labelled audio. No token, OAuth credential or personal message content is logged.

## Current phrase change and unresolved live report

The user continued to report non-response and requested “Hey Indo”. The detector tokens, accepted label, notifications, UI and regression tests now use Indo. The app name remains Aiko. The device test checks synthesized “Hey Indo” and rejects “Hey Aiko”, “Hey Google”, “Hey Michael” and “Indo” alone. The earlier Aiko test results below are historical; they do not establish Indo detection or resolve the user's live-microphone report. At the start of this investigation adb reported no connected device.

Use **Copy wake diagnostics** to share the current state, native error, input level and silencing flag. The UI explicitly warns when listening is off or very little audio is arriving. Re-enable listening after an update or process termination; normal completed commands resume automatically. The old wake phrase is no longer configured.

The Motorola phone subsequently reconnected. Both current device tests passed with zero failures and zero skipped tests: the bundled vocabulary/model loaded, silence did not trigger, synthesized **Hey Indo** triggered, and synthesized **Hey Aiko**, **Hey Google**, **Hey Michael**, and **Indo** alone did not trigger. The updated APK was installed successfully. Flutter analysis and 26 Flutter tests passed; 79 Kotlin unit tests passed. Live microphone confirmation from the user is still pending: these synthetic results do not prove the reported non-response is fixed.

Diagnostics now also retain the stage where an error occurred, TTS engine/voice and acknowledgement completion, and media volume at acknowledgement. A completed TTS callback does not prove audible output; zero media volume is highlighted without changing device volume automatically.

## Validation and remaining device trials

The initial integration passed analysis, **26 Flutter tests**, **79 Kotlin unit tests**, compilation and lint, but had no connected device. During the subsequent non-response investigation, a **Motorola moto g35 5G / Android 15 (API 35)** was connected. Android showed the microphone foreground service running, and the original model passed a JNI/silence smoke test but **failed** a synthetic positive phrase test. Model-load success alone therefore did not establish wake detection.

### Diagnosing a non-response

The replacement was subsequently tested on that Motorola phone: both instrumented tests passed, with **zero failures and zero skipped tests**. The production model/configuration detected offline synthesized `Hey Aiko` and `Hey eye co`, rejected synthesized `Hey Google`, `Hey Michael`, and `Aiko` without the prefix, and produced no match for three seconds of generated silence. The sensitivity sweep found the `0.25` threshold missed the pronunciation fixture, while `0.1` with keyword score `3.0` detected it; that setting then passed the negative fixtures. Android lint completed without errors and the corrected debug APK was installed successfully. These are synthetic decoder regression tests, **not** live microphone recognition rates or proof of locked/screen-off reliability. False-activation rates still need a much larger real-audio benchmark.

The correction also adds the spoken wake acknowledgement before both foreground handoff and background recognition, and audio-level/silencing diagnostics. No new permission or automatic listening activation is introduced. After installing the update, reopen Aiko and enable a new session; the previous `LISTENING` session is not retained across an update.

While `LISTENING`, the debug controls now show microphone RMS level (dBFS), processed audio duration, and whether Android reports the recording client silenced. Level should rise during speech. These are audio statistics only; no recording or transcript is saved. A wake detection now gets the spoken acknowledgement in both foreground and background before command capture begins. If status changes to `ERROR`, inspect its code instead of assuming the detector failed; offline English STT/TTS assets are separate prerequisites. The service now loops after each completed interaction; command silence and command timeouts also return to detection.

Automated coverage includes keyword allowlisting, bounded-session policy, foreground/lock/busy/listener handoff gates, and the Flutter single-command handoff with duplicate wake events. `WakeModelDeviceTest` loads the actual JNI runtime and bundled assets on Android, verifies token validity, and decodes three seconds of generated silence without a wake. It does not open the microphone or validate positive detections.

Acceptance trials must explicitly record observed results, rather than deriving WORKS from the code or a successful model load:

| Trial | Required observation | Status |
|---|---|---|
| App visible/unlocked | “Hey Indo” triggers exactly one existing voice pipeline | Pending spoken device test |
| Screen ON, locked | After arming, detect phrase; answer time; reject personal actions until unlock | Pending spoken device test |
| Screen OFF, locked | Detector continues; response does not require screen wake or unlock bypass | Pending spoken device test |
| Headset/Bluetooth | Confirm actual microphone/output routing, not just connection | Pending hardware trial |
| Negative speech/TV/music | Count false triggers per hour | Pending measured benchmark |
| Quiet/room/noise/vehicle | Positive recall and labelled onset-to-trigger latency for repeated utterances | Pending measured benchmark |
| Stop/permission revocation/process kill | No automatic restart; microphone released | Pending interactive device test |
| Battery/thermal/RAM | Measure idle baseline versus continuous listening, including a 15-minute measurement window on target phone | Pending measured benchmark |

There is no longer a 15-minute cap. A partial CPU wake lock is held while the explicitly enabled service runs and released on stop/error/destruction. Continuous power/thermal costs remain unmeasured. Wake detection releases its recorder before command STT; a native completion handshake waits for foreground STT/actions/TTS before resuming detection. A missing Flutter consumer expires after five seconds without executing a command. A claimed handoff waits for actual completion or service cancellation, avoiding simultaneous microphone ownership.

## Main changed files

- `android/app/src/main/kotlin/com/example/voice_assistant/voice/wakeword/`: detector abstraction, sherpa adapter, session policy and foreground service.
- `android/app/src/main/kotlin/com/example/voice_assistant/native_bridge/AssistantChannels.kt`, `MainActivity.kt`: guarded service controls, permissions and foreground handoff.
- `android/app/src/main/AndroidManifest.xml`, `android/app/build.gradle.kts`: service declaration, permissions, pinned runtime and device-test runner.
- `lib/native_bridge/assistant_bridge.dart`, `lib/presentation/assistant_controller.dart`: wake-event channel and existing pipeline handoff.
- `lib/presentation/wake_word_controls.dart`, `speech_controls.dart`: opt-in controls/status.
- `android/app/src/main/assets/wake_word/`, `android/app/libs/`: real model/runtime assets.
- Native policy tests, Flutter handoff test, and Android model smoke test.
- Lock-screen capability wording updated to distinguish the optional service from gesture invocation.

## Continuous mode validation

Debug APK build, Kotlin unit tests (including handoff completion, unclaimed timeout, and cancellation), Flutter analysis, and all 26 Flutter tests passed. Live repeated microphone triggers, locked/screen-off endurance, headset routing and battery use remain pending. No new live detection success is claimed from these checks. Android foreground-service constraints still apply: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

## Hands-free system-session integration

The software Hey Indo detector can now run while Aiko is the selected system assistant. This supersedes earlier statements that system-assistant selection disables it. It is an explicitly enabled microphone foreground service, not DSP/hardware hotword support.

1. Select voice_assistant as Android's default digital assistant.
2. In unlocked Aiko Settings, expand Hey Indo — wake-word POC, grant its microphone/notification permissions and tap Enable Hey Indo.
3. Verify the persistent microphone notification and LISTENING state. Lock the phone, say Hey Indo, pause for the acknowledgement/session to open, then speak the command.
4. Completed native interactions close the surface and resume keyword detection. Authentication/review keeps detection paused until the session closes. Stop listening in the notification stops the service and any waiting handoff. A manually invoked assistant/preview still stops the wake service to prevent competing microphone sessions; re-enable it afterwards.

The detector closes its AudioRecord before handing off. A main-thread single-use in-memory ticket connects VoiceInteractionService.showSession to SystemAssistantActivity. No new router or action implementation is used. Failed or duplicate tickets cannot start a capture. The service waits up to 20 seconds for the native session and up to 180 seconds for its completion; a timeout stops listening instead of opening a second microphone. Session destruction releases the ticket only after STT/TTS cleanup. Service cancellation invalidates the ticket and closes its session. Android/OEM restrictions may prevent presenting the session while locked; that result must be measured, not bypassed.

Existing privacy and Android authentication gates remain. Private notification previews remain unavailable while locked even with personal readouts enabled. Recognition accuracy, physical locked invocation, battery use and audio routing are pending device execution. Enable must be repeated after reboot/process termination/error. No listening was enabled automatically as part of installation.

Wake phrase renamed to Hey Sakura: keyword phonemes use the bundled en.phone SAKURA entry (S AE0 K UH1 R AA2), output label HEY_SAKURA. Updated UI, notification text, policy, asset hash and positive/negative tests. Analysis and Flutter/native unit tests passed. Connected synthetic phrase test was SKIPPED because TTS initialization was unavailable; no acoustic success claimed. Live Hey Sakura detection remains pending. Connected-test cleanup removed the test deployment; the regular debug APK was reinstalled afterwards. Setup/assistant selection may need restoring.
