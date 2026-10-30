# Gmail POC integration

Implemented: Google OAuth authorization, Gmail API reads, sender/subject/content readout, latest/recent/important/relevant inbox views, local extractive summaries, voice-dictated reply drafts, explicit confirmation and Gmail API sending. Gmail is the only supported email provider. No production backend, client secret, cloud LLM, Outlook or Yahoo integration is added.

## Required Google configuration

Code and automated tests are complete; live Google authorization and mailbox delivery still require configuration and device execution. No real mailbox or successful send was simulated.

1. In your Google Cloud project, enable **Gmail API**.
2. Configure Google Auth Platform branding/consent and audience. For a testing project, add the Gmail accounts you will test with as test users.
3. Create an **Android OAuth client** for package `com.example.voice_assistant` with the signing certificate SHA-1. For the debug keystore currently used in this workspace, it is:

   `84:A1:37:23:CB:10:1A:D0:D8:4E:18:11:2D:34:34:15:82:48:F4:64`

   Recheck with `android/gradlew.bat :app:signingReport` when changing machines, signing keys or build variants. Release/distribution certificates must be registered separately.
4. Configure consent for `https://www.googleapis.com/auth/gmail.readonly` and `https://www.googleapis.com/auth/gmail.send`. The app requests both when the user taps Connect and checks that both were granted. Partial consent does not enable this POC session.
5. Stop and restart the Android application after rebuilding. On a Google Play services device, open **Email** on the home screen, or **Settings → Gmail**, and tap **Connect / reconnect Gmail**. Select/authorize the account through Google's UI. The app fetches `/users/me/profile` to show the actual Gmail account.

The native Google Identity `AuthorizationClient` handles the OAuth account/consent flow for Gmail data access; this app does not create a separate backend login session. Android package/signing registration identifies this client; no web client secret or refresh token is embedded. See [Google's Android authorization setup](https://developer.android.com/identity/authorization).

`gmail.readonly` is restricted and `gmail.send` is sensitive. Testing access and public distribution have different consent/verification requirements; review [Google's scope documentation](https://developers.google.com/workspace/gmail/api/auth/scopes) before publishing. Organizational policy or a missing/incorrect OAuth registration may deny access even when the app builds correctly.

## Voice and review workflow

- “Read my latest email” fetches the latest inbox message and speaks sender, subject and bounded inline content. That message becomes reply context.
- “Read latest emails” reads a list of up to five recent inbox messages. Select a message in the Gmail screen before reading its content or replying.
- “Read important emails” uses `in:inbox is:important`.
- “Read relevant emails” uses `in:inbox is:important is:unread`. This is an explicit filter, not personalized relevance inference.
- “Read selected email” speaks the selected message's loaded body.
- “Summarize email” selects up to three original sentences from the selected email locally. The result is labeled **Extractive summary**; it is not an SLM paraphrase. The replaceable `EmailSummarizer` abstraction permits a future validated local model without coupling Gmail to a runtime.
- “Reply email that 4 PM works for me” uses the existing English STT pipeline, validates the body, and prepares a draft against the selected message. The assistant reads the draft and tells you to review it.
- Open the Gmail screen, check **To**, **Subject**, and the exact dictated body, then tap **Confirm send**. Or discard the draft and dictate again. Model output and spoken “send” commands cannot call this confirmation path.

Drafts are local session objects, not Gmail server drafts. Confirmation uses a random single-use ID tied to an immutable message/body snapshot and expires after five minutes of monotonic time. Reading another list, changing selection, reconnecting, disconnecting or recreating the native activity clears drafts. Duplicate taps cannot reuse a consumed draft.

The native sender constructs RFC MIME text, UTF-8 encoded subject/body, `In-Reply-To` and `References`, plus Gmail `threadId`, then calls `messages.send`. It sends only to a validated single Reply-To/From address, not reply-all or a dictated arbitrary recipient. Recipient/header injection, missing Message-ID and unsupported recipients are rejected. Success means Gmail returned a sent-message ID, not proof of delivery or recipient reading. See [Gmail sending documentation](https://developers.google.com/workspace/gmail/api/guides/sending).

## Architecture, privacy and failures

- Flutter `GmailScreen` presents account status, message selection, voice interaction and confirmation. `GmailBridge` exposes only allowlisted operations. Raw native exception messages never reach the UI.
- `GoogleGmailAuthorization` owns native tokens, consent resolution and invalid-token clearing. Tokens are never returned to Flutter, persisted by app code, or logged. Reconnect renews access via Google Play services; the POC has no background refresh daemon. Google Play services manages its own authorization cache.
- `GmailApiService` uses HTTPS to a fixed Gmail API host, disables redirects and bounds response size. Network calls and MIME parsing run on the IO dispatcher. No email content or OAuth token is written to structured logs. Local debug/session views deliberately show user input and action responses, as other POC flows do.
- `GmailSession` owns selected-message context, drafts and results. Email content is display/TTS/summary input only; it is never interpreted as an executable assistant instruction. Locked/background access is rejected; read operations cancel with the existing activity lifecycle.
- Sending consumes the confirmation before network I/O. A send already accepted by the user cannot be undone by cancelling speech or backgrounding the app. The in-progress native send finishes once and records its outcome while the process lives. Network ambiguity/server errors produce **GMAIL_SEND_OUTCOME_UNKNOWN**, instructing the user to check Gmail Sent. There is no automatic retry.
- `Disconnect locally` clears the app's account, token, emails, draft and latest assistant response. It does not revoke the Google-side consent grant; use Google Account → Third-party connections for revocation. Explicitly recorded speech benchmark trials remain under the benchmark user's control.
- OAuth denial, missing scopes, token expiry/revocation, 403 access failures, rate limits, message deletion, oversized messages and network failures have explicit error paths. The app never substitutes sample emails on failure.

Diagnostics distinguish local command routing from `CLOUD_GMAIL_API` reads. Selected-message playback, drafts and extractive summaries are local session operations. Confirmation reports measured API send latency on the Gmail screen. No SLM inference latency or synthetic accuracy is reported for the extractive summarizer.

## POC limits and pending device verification

### Authorization returning before consent

The activity callback now parses Google's returned intent before classifying a non-OK result. Google `ApiException` status numbers are exposed as `GMAIL_GOOGLE_STATUS_<number>`; raw exception messages, intent extras and tokens are never exposed. A null result is `GMAIL_AUTH_NO_RESULT`, a parsed but non-OK return is `GMAIL_AUTH_NOT_COMPLETED`, and destruction of the native session is `GMAIL_AUTH_INTERRUPTED`. None of these alone proves that the user pressed Cancel. The Gmail screen preserves the error across status refreshes and displays it beside connection status.

The user reports that the Google window closes before displaying consent. The root cause and live authorization success remain unverified. Confirm the Cloud configuration above and inspect the returned status; do not infer successful authorization from account selection or successful compilation. See [AuthorizationClient result parsing](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationClient).

This is foreground, unlocked, English-only Gmail access. Inbox lists contain up to five messages; no pagination, general search UI, background synchronization, attachment download, encrypted message decoding, reply-all, new-message composition or editing of server drafts is implemented. HTML is converted to text without loading remote images. MIME alternatives prefer plain text. Non-inline body attachments are not fetched; the app reports when inline text is absent.

Responses larger than 1 MiB are rejected. Loaded inline bodies are bounded to 12,000 characters; voice body readout is bounded to 2,300 with an explicit shortening notice. Subject/sender spoken lengths are also bounded. Summaries cover loaded content and can miss context; they preserve selected original sentences rather than generating new facts. Internationalized recipient addresses and unusually long/malformed reply headers require replying in Gmail itself.

TTS playback now has a text-length/rate-dependent watchdog (30 seconds to 8 minutes), and the bridge has a 10-minute outer ceiling to accommodate longer readings and network access. These are limits, not measured performance. Cancel still stops ongoing voice playback. Actual timing depends on the configured engine/rate and must be measured on device.

Device checks still required with configured test accounts:

1. OAuth consent success, cancellation, missing scopes, revoked access, expired token, wrong package/SHA and unavailable Play services.
2. Sender/subject/inline body against Gmail for plain text, HTML, multipart alternatives, Unicode subjects, empty inbox, Important and unread Important filters, large/attachment-only messages.
3. Dictated reply reviewed before sending; correct Reply-To, account, subject and thread in Sent/recipient inbox; discarded/expired/reused drafts and changed selection.
4. Offline read/send, 401/403/429, network interruption after send starts, app background/rotation/process death. Check Sent before retrying uncertain sends.
5. TTS completion/cancellation at slow/fast rates and long-body truncation; verify no tokens or raw API error bodies appear in logs.

## Files changed

Validation on 28 September 2026: Flutter analysis passed, all 22 Flutter tests and 67 Kotlin tests passed, and the Android debug APK built. Android lint completed with non-blocking warnings. Tests use explicit doubles for Gmail and do not claim live OAuth, mailbox reading or delivery. The final Gmail screen change was also checked with its focused confirmation widget test.

Native additions under `gmail/`: `GoogleGmailAuthorization.kt`, `GmailApiService.kt`, `GmailMime.kt`, `GmailSession.kt`, `EmailSummarizer.kt`; updated `GmailService.kt`. Wiring changes: `MainActivity.kt`, `native_bridge/AssistantChannels.kt`, `actions/AndroidActionExecutor.kt`, `intent/{TypedCommand,LocalIntentRouter,CommandValidator}.kt`, `ai/CommandOutputSchema.kt`, `voice/SpeechCoordinator.kt`, `tts/AndroidTextToSpeechEngine.kt`, `android/app/build.gradle.kts`.

Flutter: `presentation/gmail_screen.dart`, `assistant_screen.dart`, `assistant_controller.dart`, `assistant_diagnostics_screen.dart`, `native_bridge/assistant_bridge.dart`. Tests: `GmailTest.kt`, updates to `IntentRouterTest.kt` and `SpeechCoordinatorTest.kt`; `test/gmail_screen_test.dart`, `bridge_test.dart` and `widget_test.dart`. `android/local.properties` contains the machine-local SDK escaping correction needed by Android lint.
