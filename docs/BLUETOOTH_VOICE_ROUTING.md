# Bluetooth voice routing POC

Android 12/API 31+ wake capture, STT and TTS share a serialized route owner. It selects a connected SCO/BLE communication headset and waits up to five seconds for selection. Successful stages retain the route for up to three seconds so the following stage can reuse the Bluetooth transport; idle timeout, failure or cancellation releases it. No Bluetooth scanning or device identifiers are logged. MODIFY_AUDIO_SETTINGS is declared as a normal permission.

Wake capture uses VOICE_COMMUNICATION with the headset. Diagnostics report actualInputDeviceType and bluetoothMicrophoneVerified from AudioRecord.routedDevice. Recording stops with BLUETOOTH_MIC_NOT_ROUTED if the requested headset microphone is not used after two seconds of audio. STT diagnostics report communication-device selection separately from actual microphone verification: the external SpeechRecognizer provider still controls its capture. TTS uses voice-communication audio attributes with a headset.

Connect/disconnect and route loss cancel the current stage. Wake listening restarts with the new route; in-flight commands and responses are cancelled rather than silently continued. Existing call/communication mode is not intentionally taken over. Android 24–30 retains system-default routing with an explicit unsupported diagnostic. A media-only A2DP device is not treated as a microphone.

Physical investigation on September 30: Android reported Aiko's selected SCO headset route and an unsilenced 16 kHz recording. Audio service history showed repeated route teardown/reconnection between stages. The handoff now retains the route briefly instead of repeatedly disconnecting it. This is evidence of routing and a handoff defect, not proof of successful wake recognition or audible acknowledgement; these remain pending retest. Diagnostics expose bluetoothRouteReused.

Continuous headset capture may affect music quality and battery usage. Actual provider behavior, range, battery consumption and route-switch latency require physical-device measurement.

Pending physical tests:
- Enable headset call audio, start Hey Indo and verify bluetoothMicrophoneVerified is true.
- Speak away from the phone: wake phrase, then time command; repeat locked and unlocked.
- Check acknowledgement and response audio through the headset.
- Disconnect during wake capture, STT and TTS; verify capture restarts or the current request ends with a route error.
- Reconnect during wake listening and verify headset capture resumes.
- Stop/cancel and verify media routing restores; test an incoming call and competing communication app.

Reference: [Android AudioManager routing API](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)). Selecting a communication output also selects the matching input; external recognition-provider behavior still needs testing.
