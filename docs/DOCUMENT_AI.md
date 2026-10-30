# Document AI POC

The implemented screen supports **camera capture → bundled local OCR → reviewed text/excerpt → local instruction model → answer with source excerpts → Android TTS**. Open the Documents shortcut or Settings > Document AI. This is a one-page feasibility workflow, not a production document scanner.

## What is implemented

- Capture uses the installed Android camera app with a temporary, narrowly scoped FileProvider URI. It captures a full image, not the thumbnail extra. The application does not request a new CAMERA or storage permission because capture is delegated. Missing camera and cancellation are explicit failures.
- Latin/English text recognition uses bundled ML Kit `text-recognition:16.0.1`. The image stays local. Input is limited to 20 MiB, decoded with downsampling to at most 2,400 pixels on its longest edge, and EXIF orientation is applied. No detected text and over-20,000-character OCR output are rejected. Downsampling can reduce small-print recognition accuracy.
- The temporary capture is deleted after OCR/cancellation and on module teardown. The next Activity instance also clears the fixed module-owned cache file after interrupted/process-death capture. OCR text and answers are session-only. The document Activity window is marked secure while the screen is in use. No image, document, question, answer, model raw output or token is written to logs.
- Users review/correct OCR before asking. Local model context is bounded to 3,000 characters; longer text requires selecting an excerpt explicitly. There is no silent document truncation, multi-page aggregation, PDF support, automatic crop/perspective correction or retrieval/chunking yet.
- Typed question, six preset prompts, or Speak a question use the dedicated document pipeline. English STT uses saved preferences and requests microphone permission if absent; grant and tap again. System STT can use its provider network only under the existing saved opt-in. OCR/model processing remains local. Home/lock-screen document routing is not attached to this private screen's document session.
- Successful model answers are automatically spoken through Android TTS; Read answer aloud can repeat them. Wake capture pauses while this screen owns STT/TTS. Leaving the Activity cancels work/playback. The current answer can remain displayed if TTS fails; diagnostics show `DOCUMENT_TTS_UNAVAILABLE`.

## Local model setup and evidence

`GeneralAssistantModel` now has a real document implementation, `LocalGeneralAssistantModel`. OCR and generation are replaceable behind `OcrEngine`, `DocumentAiService`, `GeneralAssistantModel` and `TextGenerationRuntime`. The POC adapter reuses pinned LiteRT-LM 0.17.1 CPU text generation; automatic tool calling is disabled. It is not the command extractor and it never calls ActionExecutor.

1. Obtain an instruction/chat `.litertlm` artifact compatible with the pinned runtime and permitted by its publisher's license. Weights are **not bundled or automatically downloaded**. Use a general text model, not a function-only artifact.
2. In Android Studio Device Explorer, create `files/document_models` under this debug app's private data directory and place the model there, for example `document-model.litertlm`.
3. Calculate its SHA-256 with `Get-FileHash -Algorithm SHA256 -LiteralPath 'C:\models\document-model.litertlm'`.
4. Expand Local model setup and enter filename, actual model ID, expected checksum and operator-declared quantization. Load local document model.
5. Loading checks the private path and checksum. It unloads the process command model first to reduce simultaneous memory use; reload that command model later if needed. The document model is unloaded when the document screen closes. Configuration is not restored automatically.

Google's LiteRT-LM overview lists Gemma 3n E2B and smaller Gemma 3 1B/Qwen chat candidates. That is candidate compatibility information, not validation of any specific artifact with our pinned adapter, context size or target phone. A 3n E2B artifact may exceed memory headroom on the existing low-RAM device; do not claim suitability without measuring. Final model choice, exact licensing/distribution review, load/inference success and semantic quality remain pending device execution. No weights or benchmarks were fabricated.

The existing adapter has a 2,048-token context and 384-token output cap; this module imposes a conservative character bound but characters are not tokens, so unusual input may still exceed the model context and fail. Generation has a 60-second deadline. Native model initialization is blocking on an IO worker and can take time to cancel; the app cannot guarantee survival of a native out-of-memory crash. Only CPU is selected; GPU/NPU feasibility is not claimed.

## Grounding and data boundaries

The prompt treats document/question as untrusted JSON data and requests a short answer plus verbatim evidence quotes. Strict parsing rejects duplicate/unknown keys, non-string answers, malformed JSON, fences/trailing text and evidence not found in the selected document. Empty evidence produces a fixed abstention instead of speaking the ungrounded generated assertion. This checks syntax and quote presence, **not entailment or semantic truth**: an answer can still misinterpret valid quotes. Users must verify amounts, dates, issuer and important clauses against the source.

Even adversarial document text cannot dispatch Android actions through this path: there are no tools or action executors connected. Do not interpret a model answer as a payment, message, reminder or other completed action.

## Five-document validation harness

`android/app/src/main/assets/document_validation_v1.json` contains synthetic, non-personal electricity bill, invoice, bank statement, insurance and agreement documents. Load sample through OCR renders each into an image and runs the real OCR engine. It is explicitly labeled `SYNTHETIC_FIXTURE_NOT_CAMERA` and does not establish camera feasibility.

Run 5 documents × 6 questions uses these prompts:

1. Explain this document.
2. Summarize this.
3. What is the amount?
4. What is the due date?
5. Who issued this document?
6. What are the important points?

The bank statement intentionally has no payment due date. Documents with multiple amounts test whether the model distinguishes premium, limit, total, balance and line items. The harness records 30 actual model attempts, OCR exact normalized-text comparison, questions, answers/errors, expected amount/due/issuer reference fields, model metadata and timings. Summary/explanation/amount ambiguity requires human review; **semantic accuracy remains MANUAL_REVIEW_PENDING**. A model response with a matching quote is not automatically scored correct. Reports stay in memory and are selectable in Technical diagnostics; cancel then Refresh retains completed rows. Clear/close removes rows. No run has been represented as completed in the checked-in pending report.

The benchmark does not run the camera or STT/TTS. Separately photograph each printed synthetic document, check OCR against the fixture, speak all six questions, and verify audibility/content on the phone. Add realistic scans only with authorization. Record failures, omissions, timing and actual model identity per device. Tests with bills/statements are synthetic and are not real financial transactions.

## Measurements and pending acceptance

Diagnostics expose OCR latency/length/source, edited-excerpt status, selected model/hash/declared quantization, runtime/backend, model bytes/load time, process PSS before/after load and after inference, inference time, strict-output acceptance, question, document intent/Tier 3, STT engine/mode/latency, TTS engine/voice/startup/playback latency and local model execution mode. PSS is whole-process samples, not isolated model RAM or a true peak. No battery, sustained CPU, GPU or NPU measurements are claimed.

| Acceptance | Implementation | Validation |
|---|---|---|
| Camera capture | External camera + scoped temporary output | Physical-device execution pending |
| OCR/text extraction | Bundled ML Kit, orientation/bounded decode, editable text | Real-device fixture and camera OCR pending |
| Local SLM processing | Loadable real LiteRT-LM adapter behind interfaces | Artifact staging/load/inference pending |
| QA/summarization/explanation | Prompt + strict evidence-bearing answer schema | Semantic accuracy/manual review pending |
| Voice questions/responses | Existing English STT/native TTS, mic pause, cancellation | Device and headset tests pending |
| Five document categories | Synthetic fixtures and 30-question harness | No fabricated results; execution pending |

Implementation is present; the POC's required live demonstration is **not yet accepted**. No physical camera, real model or voice benchmark was run by automated unit/widget tests.

## Source and tests

Automated verification for this change: Flutter analysis passed; all 40 Flutter tests and 162 native unit tests passed; Android debug assembly and lint completed successfully. These checks do not validate camera capture, OCR accuracy, model compatibility or spoken answers on a physical device. The debug APK is available at `build/app/outputs/apk/debug/app-debug.apk`.

OCR cancellation waits for the in-flight ML Kit task to finish before releasing its image and recognizer; cancellation is not guaranteed to be immediate.

- `document_ai/`: DocumentChannels, OcrEngine/MlKitOcrEngine, LocalDocumentAiService, DocumentAnswerSchema; existing DocumentAiService retained.
- `ai/`: LocalGeneralAssistantModel; GeneralAssistantModel lifecycle and shared TextGenerationRuntime boundary.
- MainActivity, AndroidManifest, document_paths.xml and Gradle dependencies: lifecycle/camera/provider integration.
- Flutter: document_bridge.dart, document_ai_screen.dart, Documents/Settings entry points.
- `DocumentAiTest.kt`: schema/quote validation, abstention, missing model, checksum/loading/release and replaceable OCR/model boundaries. All inference doubles are explicitly test-only.
- `document_ai_screen_test.dart`: captured OCR display and honest model-unavailable error.

References: [ML Kit OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android), [camera intents](https://developer.android.com/media/camera/camera-intents), [LiteRT-LM overview and model candidates](https://developers.google.com/edge/litert-lm/overview). No third-party benchmark numbers are used as this application's results.
