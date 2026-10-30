# Voice Assistant POC

Flutter Android foundation with native Kotlin contracts and Platform Channels.

Core native action execution and result evidence are described in
[core actions](docs/core-actions.md). Messaging opens a reviewable SMS composer;
named recipients must be selected by the user and no automatic send is claimed.

Start Voice Interaction now captures English speech, displays partial/final text,
and routes the final transcript locally before speaking the routing outcome with
Android Native TTS. Validated navigation and open-maps voice commands now launch
Google Maps. Voice commands such as "Search for the best restaurants near MG Road" open Google Search in the Google app or browser. Calendar and reminders are also available as described below. Other external actions remain unsupported. Offline STT is preferred;
system recognition is opt-in. See [Maps launch behavior](docs/maps-launch.md).

Run on Android with `flutter run`. Toggle Developer / Debug Mode for request
metadata and honest measured/not-run stage status.

Calendar queries now read and speak events from synced Google calendars. Enable
Calendar and reminder permissions in Settings. Voice reminders schedule local
notifications; creating events opens a calendar draft for you to save. See
[calendar commands, setup and limitations](docs/calendar-reminders.md).

The Email tile opens Gmail OAuth setup, inbox reading and reply review. Voice
commands read sender/subject/content, create local extractive summaries and dictate
replies; sending requires an explicit reviewed confirmation. Configure the Android
OAuth client and test account first: [Gmail setup and limitations](docs/gmail-integration.md).

See [foundation architecture, contracts, tests and TODOs](docs/foundation.md).
See [speech setup, benchmark procedure and actual limitations](docs/speech-layer.md).
See [command routing, typed schemas and safety boundaries](docs/command-routing.md).
See [Tier 2 model evaluation, setup and device benchmark harness](docs/tier2-command-model.md).
Settings → Local command model opens local model loading and the 38-case benchmark. Model weights
are not bundled; accuracy and inference performance remain pending execution with
a compatible artifact. Tier 3 remains disabled; Maps, Google Search, synced Google Calendar reads, calendar drafts and local reminders are supported. See [Google Search behavior](docs/google-search.md).

The Aiko home screen follows the supplied visual reference. Tap the central voice
button to start or cancel speech. Settings contains speech controls and the
Technical dashboard. History shows the latest session interaction and recorded
benchmarks; shortcut tiles disclose integrations that are not implemented yet.
