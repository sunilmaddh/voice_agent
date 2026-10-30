# POC foundation

Historical foundation milestone. The current app now implements the foreground
[English speech layer](speech-layer.md); the STT_NOT_IMPLEMENTED handshake below
describes the earlier milestone, not the current app.

Scope: architecture, typed contracts, Android bridge, Flutter diagnostics UI and tests.
No microphone capture, model inference, TTS playback, communications, permissions,
roles, backend or third-party actions are implemented in this milestone.

## Ownership

- `lib/app/`: composition root and Material application.
- `lib/presentation/`: Riverpod Notifier controller, immutable state and ConsumerWidget
  screen; no Android business logic.
- `lib/native_bridge/`: versioned MethodChannel/EventChannel client and Dart models.
- `lib/diagnostics/`: structured metadata-only logger.
- Native `voice/`: foundation request coordinator; future pipeline entry point.
- Native `stt/`, `tts/`, `intent/`, `ai/`, `actions/`, `notifications/`,
  `communications/`, `gmail/`, `calendar/`, `document_ai/`: independent capability
  interfaces, with no chosen model/runtime or integration adapter.
- Native `benchmark/`: injectable monotonic timer and explicit skipped metrics.
- Native `diagnostics/`: safe typed metadata logging.
- Native `native_bridge/`: channel lifecycle and wire contract serialization.

Native modules are logical Kotlin packages in the Android app module, as approved;
they are not separate Gradle libraries. Native domain interfaces contain no Flutter
or Android SDK dependencies. Suspend interfaces allow future asynchronous adapters.
The current tiny bridge handshake runs synchronously; future inference and I/O must
run off the Android main thread and return channel results on that thread.

## Channel contract

- Method channel: `voice_assistant/methods/v1`
- Event channel: `voice_assistant/events/v1`
- Method: `startVoiceInteraction`
- Request: `schemaVersion: 1`, generated `requestId`, nullable `input`.
- Typed response: request/input, STT, intent, routing tier, model, structured output,
  action/result, response, execution mode and stage performance metrics.
- Events: schema version, request ID, fixed code, fixed level, epoch timestamp.
- Invalid request -> `INVALID_REQUEST`; unexpected native failure -> sanitized
  `INTERNAL_ERROR`; unknown methods use the standard not-implemented envelope.
- Dart rejects unknown versions/enums and response/request mismatches. Missing
  native platform, timeout and malformed responses become safe, recoverable errors.
- No automatic retries: later actions may have side effects.

`Start Voice Interaction` currently proves the Flutter/Kotlin round trip only.
The result is `unsupported / STT_NOT_IMPLEMENTED`. No transcript, intent, model,
structured model output, action or spoken output is invented. Execution is `notRun`.
STT/SLM/action/TTS measurements are null with `not_run`, never fabricated zeroes.
Total is the measured native foundation request handling time (before serialization
and the final completion event), not end-to-end voice latency.

The nine requested domain models exist in both Dart and Kotlin. Interface ownership
is Kotlin because that is where the approved assistant pipeline will run. Dart owns
only an `AssistantBridge` abstraction for presentation injection/testing. Business
interfaces are not duplicated as empty Flutter services.

## Debugging and privacy

Developer mode defaults off and can be toggled in the screen. It shows all requested
debug fields, additional action latency/result, and up to 50 live metadata events.
Session data is memory-only; there is no automatic export or persistent input log.
Both loggers accept fixed metadata instead of arbitrary payloads. Request identifiers
are allowlisted; native exceptions and platform error details are never emitted.
Future adapters must keep credentials out of domain DTOs and model output, sanitize
exports, and never print exceptions containing HTTP/OAuth payloads.

The controller suppresses duplicate requests, ignores unrelated events, cancels its
event subscription on disposal, and handles late future completion safely. Native
channels are detached when the Flutter engine is cleaned up.

Flutter state management uses `flutter_riverpod` with a root `ProviderScope`, an
overridable `assistantBridgeProvider` and an auto-disposed
`assistantControllerProvider`. Each provider lifetime owns its event subscription;
responses from an invalidated/disposed lifetime cannot overwrite new state. Tests
inject bridges using provider overrides. No code generation is needed. The pattern
follows the [Riverpod provider guidance](https://riverpod.dev/docs/concepts2/providers).

## Validation commands

```text
dart format lib test
flutter analyze
flutter test
flutter build apk --debug
cd android
gradlew.bat testDebugUnitTest lintDebug
```

Dart tests exercise the channel envelope, malformed data, safe errors, controller
lifecycle and UI. Kotlin tests exercise the real foundation coordinator, malformed
requests, diagnostic allowlisting and truthful timing. Test doubles exist only in
tests and are not feasibility evidence. A physical Android launch is still required
to validate the device-side MethodChannel/EventChannel round trip.

## Verification results — 28 September 2026

- `flutter analyze`: no issues.
- `flutter test`: 10 tests passed after the Riverpod migration, including provider
  disposal, invalidation and immutable-state checks.
- `:app:testDebugUnitTest`: 5 tests passed, zero failures/errors/skips.
- `:app:lintDebug`: zero errors; one existing template warning about the redundant
  `drawable-v21` folder (minimum SDK is 24).
- Sequential clean Gradle `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`:
  BUILD SUCCESSFUL. Debug APK: `build/app/outputs/flutter-apk/app-debug.apk`.
- After the Riverpod migration, `flutter build apk --debug` also passed and rebuilt
  the debug APK. Flutter rewrites local SDK paths; their drive-letter escaping was
  restored afterward for compatibility with Android lint.
- Existing Flutter/Gradle Kotlin deprecation and SDK XML tooling warnings remain.
- No physical-device launch or feature feasibility benchmark was performed.

Run Android builds/tests sequentially against this workspace; concurrent Gradle
invocations can collide on generated resources and Kotlin incremental caches.

## Remaining work (requires a later milestone)

- Real STT/TTS adapters, audio/session lifecycle and permission handling.
- Semantic intent validator, capability checks and confirmation/unlock gates.
- Deterministic router, replaceable local models/runtime adapters and benchmarks.
- Android actions, reminders, assistant role and lock-screen service lifecycle.
- Notification listener, WhatsApp/SMS/call feasibility, Gmail OAuth/API and calendar.
- Camera/OCR and document questions/summaries.
- Physical-device matrix, battery/performance evidence and final POC reports.

No default permissions or role declarations were added for unimplemented features.
The existing Flutter Android SDK settings remain unchanged. No dependency on a
model/runtime, backend or cloud LLM has been introduced.

## Changed files

Flutter: `lib/main.dart`, `lib/app/assistant_app.dart`,
`lib/presentation/assistant_controller.dart`, `lib/presentation/assistant_screen.dart`,
`lib/native_bridge/assistant_bridge.dart`, `lib/native_bridge/contracts/models.dart`,
`lib/diagnostics/structured_logger.dart`.

Riverpod migration additionally adds `lib/presentation/assistant_state.dart` and
`lib/native_bridge/assistant_bridge_provider.dart`, updates `pubspec.yaml` and
`pubspec.lock`, and adapts the controller/widget tests to provider overrides.

Android: `android/app/build.gradle.kts`; under
`android/app/src/main/kotlin/com/example/voice_assistant/`:
`MainActivity.kt`, `native_bridge/AssistantChannels.kt`,
`native_bridge/contracts/Models.kt`, `voice/FoundationCoordinator.kt`,
`stt/SpeechToTextEngine.kt`, `tts/TextToSpeechEngine.kt`, `intent/IntentRouter.kt`,
`ai/CommandModel.kt`, `ai/GeneralAssistantModel.kt`, `actions/ActionExecutor.kt`,
`notifications/NotificationService.kt`, `communications/CommunicationService.kt`,
`gmail/GmailService.kt`, `calendar/CalendarService.kt`,
`document_ai/DocumentAiService.kt`, `benchmark/PerformanceMonitor.kt`,
`diagnostics/StructuredLogger.kt`.

Machine-local build configuration: `android/local.properties` Windows drive-letter
colons were escaped to satisfy Android lint. This ignored file must not be committed
or copied to other machines.

Tests: `test/widget_test.dart`, `test/bridge_test.dart`, `test/controller_test.dart`,
`android/app/src/test/kotlin/com/example/voice_assistant/FoundationTest.kt`.

Documentation: `README.md`, `docs/foundation.md`.
