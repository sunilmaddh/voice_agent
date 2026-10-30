# Phone calling POC

Regular phone calls now support deterministic CALL_PHONE intents with a recipient parameter. Examples: Call Rahul, Call Rahul Sharma, Dial +91 98765 43210. No model is required.

An explicit number opens ACTION_DIAL with a tel URI. A name opens an internal, non-exported contact-selection Activity. READ_CONTACTS is requested only for name lookup; denial leaves number-based calling available. Only exact case-insensitive saved display names match; all matching numbers are offered for user selection. No guessed, partial or model-generated identity resolution. Contact data is not persisted or logged. The call shortcut explains this flow.

The user must press Call in the phone dialer. Results explicitly report handoff, not a placed/connected call. No CALL_PHONE permission or default-dialer role is needed. Service codes, URI payloads and multi-command requests are rejected. Spoken number words are not normalized; STT must produce digits. Phone dialer absence is reported. Locked requests retain the existing unlock-and-continue gate; no keyguard bypass. WhatsApp calling, incoming-call handling and driving mode are not implemented here.

Physical tests pending: contact permission accept/deny, multiple matching numbers, nonexistent name, missing dialer, locked handoff and actual dialer display. No test call is placed automatically.

Reference: https://developer.android.com/guide/components/intents-common#Phone

## Optional hands-free calling, including while locked

A separate off-by-default opt-in is available under Settings > System assistant > Configure hands-free phone calls. While unlocked, grant Phone permission (Contacts optional), then explicitly tap Enable. Permission grant alone does not enable calling. Disable from the same screen. Anyone invoking the assistant can request a SIM call while this setting is enabled; there is no speaker identity verification.

The native executor uses TelecomManager.placeCall with CALL_PHONE permission. It accepts explicit numbers or a single distinct number from an exact contact-name match. Missing/ambiguous contacts are rejected rather than selecting a number. Settings and foreground state are rechecked after contact lookup, and runtime permission denial is handled. No automatic retry or call-connected claim. Short numbers, service codes and recognized emergency numbers are excluded from this POC; use the system phone app for those.

This supersedes the dialer-only behavior above only when enabled. Android/carrier/SIM restrictions and SIM-selection UI can still prevent a hands-free call. Android and the default dialer own call UI and keyguard behavior. No default-dialer role, lock bypass, incoming-call management or WhatsApp calling is added.

Physical locked-call test is PENDING. No real call was made during automated validation. Test with an explicitly approved recipient: screen on/off locked, exact name/number, missing permission, multiple numbers, dual SIM, no SIM and cancelled assistant session.

Reference: https://developer.android.com/reference/android/telecom/TelecomManager#placeCall(android.net.Uri,android.os.Bundle)

## Experimental voice hang-up

Configure hands-free phone calls now has separate Grant call-control permission, Enable voice hang-up and Disable voice hang-up buttons. This is independent of outgoing calling and defaults off. Android 9/API 28+ and ANSWER_PHONE_CALLS are required. Permission grant alone does not enable it. Anyone who can invoke Aiko may request hang-up while enabled, including while locked.

Exact deterministic commands: Hang up, Hang up the call, End call, End the call, End my call, Cut the call, Disconnect the call. END_CALL has no parameters and is validated before dispatch. Native assistant also provides End current phone call for a manual fallback. The action runs before TTS. It checks foreground session, opt-in and permission immediately before invocation and never retries automatically.

The POC uses the public TelecomManager.endCall API (introduced API 28, deprecated API 29). This narrowly scoped implementation deliberately does not replace the user's default phone application; a production default-dialer alternative needs a complete incoming/ongoing call UI and InCallService. Android chooses the foreground call. Do not assume a particular call is selected in call waiting/conference scenarios. The API cannot end ongoing emergency calls. No WhatsApp-specific call control is claimed.

A true return is reported as CALL_END_ACCEPTED, with disconnectObserved=false. A false return is CALL_END_NOT_ACCEPTED, not success. Permission denial, unsupported versions and disabled opt-in are explicit failures. No call state observer, recorded audio or live-call termination is used in automated tests.

**Physical test pending:** non-emergency foreground call; locked/unlocked; true/false/denied result; permission revocation; call waiting; Bluetooth and speakerphone. Voice activation is NOT guaranteed during a call: Android can silence another recorder, the STT provider may not capture, and BluetoothVoiceRoute does not take over an existing call's communication mode. The native button or the system phone/headset controls remain necessary in that case. No microphone/security bypass is attempted.

Reference: https://developer.android.com/reference/android/telecom/TelecomManager#endCall()
