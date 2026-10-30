# Tier 2 local command extraction

Implemented: a real LiteRT-LM Android adapter, a model-independent CommandModel,
strict JSON-to-typed-intent validation, Tier 2 routing, and a device benchmark UI.
**Inference feasibility and accuracy remain PENDING DEVICE EXECUTION with model weights.**
No model output executes an external action. Tier 3 is still unconfigured.

## Model/runtime evaluation

| Candidate | Evidence and fit | Decision |
| --- | --- | --- |
| FunctionGemma 270M | Google's function-calling model and mobile-actions deployment demonstrate LiteRT-LM support. The published mobile-actions tool set does not match all six POC categories. Google describes task-specific fine-tuning as necessary for this small model. | Preferred specialist candidate for a schema-specific fine-tune. Not claimed to work with this POC schema out of the box. |
| Gemma 3 270M IT | Small instruction-tuned text model, with a LiteRT-LM conversion published by the LiteRT community. Can be evaluated through a text-to-JSON contract without registering executable tools. | Initial selectable JSON-output candidate. Compatibility at the artifact/runtime level is documented; accuracy, latency and suitability on our devices are unverified. |
| Heavy general SLM | Outside this milestone. | Not integrated. |

Primary references reviewed on 2026-09-28:

- [Google FunctionGemma mobile-actions guide](https://ai.google.dev/gemma/docs/mobile-actions)
- [FunctionGemma model card](https://huggingface.co/google/functiongemma-270m-it)
- [Gemma 3 270M IT model card](https://huggingface.co/google/gemma-3-270m-it)
- [LiteRT community conversion](https://huggingface.co/litert-community/gemma-3-270m-it)
- [Android LiteRT-LM API](https://developers.google.com/edge/litert-lm/android)
- [Pinned runtime source](https://github.com/google-ai-edge/LiteRT-LM/tree/v0.17.1)

LiteRT-LM **0.17.1** is pinned, using CPU inference, a 2048-token context limit and
384 output tokens. A fresh conversation is created for each command. GPU/NPU
acceleration is not claimed or configured. The downloaded AAR contains arm64-v8a
and x86_64 JNI binaries; Tier 2 is unavailable on a 32-bit-only process/device.
The application's merged minimum SDK remains 24. Building against that SDK does
not establish native execution compatibility on every supported Android version.

The current adapter expects strict JSON text. FunctionGemma's native function-call
syntax is deliberately not treated as JSON or silently repaired. A FunctionGemma
artifact must be specialized for this JSON contract, or a separate manual tool-call
adapter must be supplied behind CommandInferenceRuntime/CommandModel. Runtime tool
execution is disabled and no tool providers are registered.

Gemma artifacts use the Gemma license; review the model cards and linked terms for
the chosen artifact and distribution. We have not accepted gated download terms,
bundled weights or certified commercial suitability. The checksum records the exact
artifact tested, while quantization is explicitly **operator-declared**, not inferred.

## Architecture and validation

Final STT/text -> LocalIntentRouter -> complete Tier 1 match, blocked/ambiguous
request, Tier 3 request, or Tier 2 candidate -> CommandModel -> inference runtime
-> strict schema -> typed AssistantIntent -> independent CommandValidator -> inert
proposal/clarification -> TTS response.

Tier 1 matches and blocked commands never call a model. Tier 2 candidate detection
includes common polite requests and category words, but remains a bounded grammar.
Not every English paraphrase is guaranteed to reach the correct tier.

CommandModel returns CapabilityResult<AssistantIntent>. Runtime/model selection is
injected; the router has no LiteRT dependency. LocalCommandModel owns serialized
load/inference/unload work and publishes metadata. The only deployed runtime adapter
is LiteRtCommandRuntime. No backend service or cloud inference fallback is added.

The JSON envelope requires exactly intent, parameters, confidence and executionMode.
Allowed mode is LOCAL. The parser rejects duplicate/extra keys, arrays, nested
parameter objects, nulls, numeric strings, non-finite numbers, invalid ranges,
unknown intent names, comments and trailing content. A single complete json or unlabelled Markdown code block is accepted as a transport wrapper; its contents still undergo the same strict validation. Missing
required parameters fail; values are never guessed or repaired.

Supported model schemas:

| Intent | Exact parameters |
| --- | --- |
| NAVIGATE | destination: string |
| GOOGLE_SEARCH | query: string |
| CREATE_REMINDER | title: string, dueEpochMs: integer, timeZoneId: string |
| SEND_MESSAGE | recipientName: string, body: string, channel: SMS or UNSPECIFIED |
| QUERY_CALENDAR | startEpochMs: integer, endEpochMs: integer, timeZoneId: string |
| READ_NOTIFICATION | scope: LATEST or ALL_ACTIVE |
| OPEN_MAPS / OPEN_CALENDAR / STOP_NAVIGATION / UNKNOWN | empty object |

Other previously defined intents remain available to Tier 1/future adapters, but
are not silently accepted from this six-category command model. Reminder times
must be future relative to the supplied context; calendar ranges must be ordered.
Confidence is required in [0,1] and is **model self-report, not calibrated probability**.
The action-policy threshold remains 0.9. Messaging still requires recipient/thread
resolution and confirmation, so it never produces an executable action here.
Schema validity does not establish that extracted facts match the user's meaning.

Inferences have a 45-second deadline; cancellation explicitly calls the runtime's
cancelProcess before closing the conversation. Work is cancelled on app pause.
Model ownership is protected by a mutex, including unload and activity teardown.
JNI initialization is blocking on an IO worker: cancellation can wait for that
native call to return. There is no process-isolated protection against a native
crash or memory exhaustion. This remains a physical-device feasibility risk.

## Run the device harness

1. Build/install the Android debug app. Obtain a compatible text/JSON `.litertlm`
   artifact through its publisher's permitted distribution process. No particular
   artifact is silently downloaded, substituted or represented as tested.
2. Use Android Studio Device Explorer to create
   `/data/data/com.example.voice_assistant/files/command_models/` and upload the
   artifact as `command-model.litertlm`. This is app-private storage; no broad
   storage permission is requested. Device Explorer access requires the debug app.
3. Calculate the local artifact hash with PowerShell:
   `Get-FileHash -Algorithm SHA256 -LiteralPath 'C:\models\command-model.litertlm'`.
4. Open the flask icon (**Tier 2 model evaluation**), enter the filename, actual
   model ID, SHA-256 and declared quantization. Tap **Load local model**.
   Loading verifies the filename/path, file presence and hash before inference.
5. Use **Route command (no actions)** to inspect a typed result and routing decision.
   Exact Tier 1 commands still bypass inference. The voice screen shares the loaded
   model and shows SLM timing plus model measurements in debug mode.
6. Tap **Run command benchmark**. Keep the app foreground until completion, or use
   Cancel. Copy the displayed JSON report. Reports remain session-only unless copied;
   they are not uploaded or written to persistent application logs.
7. Repeat with the same artifact on representative devices and repeat warm runs.
   Unload/reload before an initialization trial. OS page caches can remain warm;
   a reload is not proof of a cold filesystem-cache measurement.

No microphone is required for typed extraction/benchmarking. Existing microphone
permissions apply only when entering through voice. No new Android role is needed.

## Measurements and scoring

The bundled dataset is `android/app/src/main/assets/command_benchmark_v1.json`:
38 cases, six required categories plus ambiguous/negated/unsupported controls.
Each case contains gold intent/parameters and a fixed date/time-zone context
(2030-01-15 09:00 Asia/Kolkata). These are test fixtures, not benchmark results.

The benchmark invokes CommandModel directly, bypassing deterministic routing. This
prevents Tier 1 successes being counted as model successes. No automatic warm-up is
performed; inferenceAttemptsSinceLoad and inferenceCountBeforeRun identify reused
engines. Rows include input, expected/actual intent, expected/actual parameters,
confidence, error, correct/incorrect intent, parameter failure and latency.

- Model size: exact local file bytes after checksum verification.
- Model load time: runtime initialization wall time; hashing is excluded.
- Inference time: fresh conversation, prompt processing, generation and schema
  validation wall time. Row latency additionally includes harness/model-call overhead.
- RAM: whole-app PSS in KiB before/after load, after inference and sampled every
  100 ms during inference. Includes Flutter, STT/TTS and other allocations. It is
  neither model-only RAM nor a guaranteed true peak; missing values remain null.
- Structured-output success: strict parser plus semantic date validation accepted.
- Intent accuracy: exact canonical intent matches / all completed cases, including
  invalid-output cases in the denominator.
- Parameter accuracy: correct intent and all exact parameter keys/values / all
  completed cases. Text comparisons ignore case and normalize whitespace; numeric
  values are exact. This is deliberately stricter than semantic equivalence.
- Incorrect intent: a valid parsed output with the wrong intent. Schema failures
  are reported separately. Parameter failures include matching intents with wrong
  values and invalid-parameter schema errors; these categories can overlap.

Self-reported confidence is retained even when too low for a proposal. Dataset
accuracy does not calibrate confidence. No fabricated scores, timings, percentiles,
model sizes, quantization or RAM values are seeded into reports. Unit tests use
explicitly labelled test doubles solely to validate reporting and safety logic.

## Evidence and remaining limitations

A read-only adb check on 2026-09-28 found a connected A103SH, SDK 33, arm64-v8a,
qcom hardware, reporting MemTotal 3,546,292 KiB. The app had no command_models
directory. This is device inventory evidence only, **not a model execution result**.
No model weights were loaded or inference benchmark executed during implementation.
The checked-in pending report contains null measurements.

Still pending: artifact-specific device execution, measured command accuracy,
schema-specialized FunctionGemma training/adapter evaluation, cold/warm comparison,
RAM headroom, timeout and native cancellation behavior on hardware, multi-device
compatibility, CPU/GPU/NPU utilization and battery measurements. The 38-case fixture
is a smoke benchmark, not a representative production accuracy study. There is no
claim that a 270M model reliably performs time arithmetic or obeys the full schema.

## Files changed

- `android/app/build.gradle.kts`: pinned LiteRT-LM/Gson dependencies.
- `ai/CommandModel.kt`, `CommandInferenceRuntime.kt`, `LocalCommandModel.kt`,
  `LiteRtCommandRuntime.kt`, `CommandOutputSchema.kt`: abstraction, runtime, lifecycle,
  prompt, schema and measurements.
- `intent/LocalIntentRouter.kt`, `IntentRouter.kt`, `TieredIntentRouter.kt`:
  candidate selection, orchestration and model diagnostics.
- `voice/SpeechCoordinator.kt`, `native_bridge/AssistantChannels.kt`: voice routing
  integration, foreground lifecycle, typed-text/model/benchmark bridge operations.
- `benchmark/CommandBenchmark.kt`, `assets/command_benchmark_v1.json`: device harness
  and gold dataset.
- `lib/native_bridge/assistant_bridge.dart`, `lib/presentation/assistant_screen.dart`,
  `command_model_screen.dart`: channel adapter, measurements and evaluation UI.
- `CommandModelTest.kt`, `test/bridge_test.dart`: parser, model lifecycle, routing,
  scoring and sanitized bridge errors.
- README, command-routing guide, this report and the pending benchmark report.

Native paths above are relative to
`android/app/src/main/kotlin/com/example/voice_assistant/` except assets and tests.

## Build verification

- `flutter analyze`: no issues.
- `flutter test`: 16 passed.
- `:app:testDebugUnitTest`: 31 passed, including 10 command-model tests.
- `:app:assembleDebug`: successful; debug APK at
  `build/app/outputs/flutter-apk/app-debug.apk` (227,959,572 bytes, no model weights).
- Android lint: 0 errors, 3 warnings (newer Gson/coroutines versions and an existing
  redundant drawable-v21 directory). Dependencies remain explicitly pinned.

These are software/build checks, not physical-device model performance results.
Single-command troubleshooting: after loading, enter a non-private sample such as
`Navigate to Bangalore Airport`, then tap **Diagnose one command (no actions)**.
This bypasses deterministic templates and returns model diagnostics plus up to
2048 characters of `rawModelOutput`, its length and truncation flag. Raw text is
returned only for this explicit operation, not kept in ordinary model diagnostics
or written to logs. A timeout may have no completed raw output. The same strict
command schema remains enforced; the diagnostic never executes an action.

