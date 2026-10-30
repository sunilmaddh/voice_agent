# Google Maps voice action

Final English transcript -> typed NAVIGATE or OPEN_MAPS -> validated action ->
spoken acknowledgement -> foreground Google Maps launch.

Say `take me to Bangalore Airport`, `navigate to Bangalore Airport`, or `open maps`.
The first two pass the URI-encoded destination to the explicit Google Maps package
using `google.navigation:q=...`. OPEN_MAPS launches the Maps app itself. This follows
the [Google Maps Android intent contract](https://developer.android.com/guide/components/google-maps-intents).
No Maps API key or new location permission is needed by Aiko for this handoff.

MapsActionExecutor revalidates the action name, typed parameters and confidence.
It requires Aiko to be foreground and the phone unlocked. Missing/disabled Maps
returns MAPS_UNAVAILABLE; denied launches return MAPS_LAUNCH_DENIED. No browser or
other app is silently substituted. Other actions, including STOP_NAVIGATION,
remain unsupported.

Speech finishes and audio focus is released before launching Maps. A reported TTS
capability/playback failure is retained in speech.ttsError and does not prevent
the Maps handoff; cancellation still prevents execution. This ordering avoids the
normal Activity pause caused by opening Maps cancelling unfinished acknowledgement.

MAPS_LAUNCH_REQUESTED means Android accepted startActivity. It does not prove that
Maps resolved the right place or started turn-by-turn navigation. Maps handles
its own onboarding, location permission, connectivity, route selection and errors.
The result exposes actionAttempted, actionExecuted, the action DTO and action latency.

The typed-command evaluation screen and model benchmarks remain extraction-only.
Device verification: rebuild/reinstall, speak a destination, verify Maps opens,
then return to Aiko and inspect the result. Also test Maps unavailable, locked
device, cancellation and ambiguous commands. Automated tests cover orchestration;
actual Maps navigation and device lifecycle behavior remain pending physical testing.
