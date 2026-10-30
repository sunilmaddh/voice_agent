# Incoming call SMS reply POC

The implemented behavior is **review, send “I will call you later.”, then decline
the same ringing call only after Android reports successful SMS sending**.
It does not auto-reply to incoming calls. A send failure or timeout leaves the call
unchanged (unless the user or caller ends it).

## Setup

Settings > System assistant > Configure hands-free phone calls >
Incoming call SMS reply / default Phone setup.

Select Aiko Phone POC through Android's role chooser. This is separate from the
default assistant role and replaces the incoming/ongoing Phone UI. Role selection
is optional and never performed silently. Enable call notifications and, on
Android 14+, full-screen call notifications where allowed. READ_PHONE_STATE is
used for SIM/account selection; CALL_PHONE is needed for explicit outgoing calls.
Android 11+ is required for the call-to-SIM mapping used by this reply path.
Tap “Allow SMS sending for reviewed call replies” and grant SEND_SMS and
READ_PHONE_STATE. SMS permission remains subject to Android/installer restrictions.
Google Play distribution requires separate SMS-permission eligibility review.
The setup screen also links to Android default apps to restore the original Phone app.

## Use

For an incoming call, open Aiko's call screen from its notification. Tap “Reply:
I will call you later”. Unlock if required. Check the caller and message, then
say “Send reply” after Aiko finishes its spoken prompt, or say “Cancel reply”.
The manual buttons remain available if speech is unavailable. Opening voice review
silences the ringtone; cancellation does not decline the call or send SMS, but
the ringtone may remain silenced for that call.

When STT is available, “Reply to caller saying I will call you later” opens the
same review for exactly one ringing call. Multiple ringing calls require selection
on the call screen. Confirmation uses Android TTS followed by one English STT
attempt with the existing saved offline/system-recognition preference. Only final
“Send reply”/“Send the reply” confirms; “Cancel reply” cancels. Partial results,
silence, ambiguous speech and recognition errors never send. Microphone availability
during ringing/active calls still requires device testing.

## Implementation and restrictions

- Native InCallService receives Telecom calls. It exposes incoming/ongoing UI,
  answer/decline/end, hold/resume, mute/audio routes, DTMF, account selection and
  a basic conference merge control. ACTION_DIAL populates a dial field; only an
  explicit Call button invokes TelecomManager.placeCall. Emergency call handling
  remains owned by Android Telecom.
- The call must expose CAPABILITY_RESPOND_VIA_TEXT
  is present and the caller has a visible tel number (7–15 digits, optional +).
  Hidden callers, short codes, non-phone handles and unsupported providers are
  rejected before dispatch. No recipient is inferred from contacts or call history.
- The reviewed call ID, address, state and capability must still match. The review
  expires after 60 seconds and is consumed once. Role loss, locking, cancellation,
  call removal and call-state changes prevent stale sending. No automatic retries,
  fallback sending or accessibility automation is used.
- SmsManager sends one fixed SMS with an explicit subscription obtained from the
  reviewed call's PhoneAccountHandle. It never guesses a SIM or silently uses a
  default. Permission and SIM checks run before dispatch. The explicit,
  non-exported sent-receipt receiver records result codes without message text
  or recipient numbers. Only RESULT_OK can trigger Call.reject(false, null).
  This replaces the prior reject(true, message) path; it never retries that
  earlier operation or combines the two send mechanisms.
- Receipts are single-use. Call replacement, answering, lock, role loss, timeout
  or process death prevent a late callback from declining a call. No automatic
  retries occur. A pending send cannot be recalled once submitted to Android.
- Hey Indo capture pauses and releases its recorder before confirmation begins,
  then resumes if the user has kept listening enabled. A busy handoff times out
  instead of opening competing microphones. Review speech stops on pause, call
  state/identity changes, role loss or expiry. TTS must complete before STT starts.
- Spoken confirmation uses the same single-use reviewed-call validation as the
  button. Requesting reply review never itself sends SMS or declines the call.
- CALL_REPLY_SMS_PENDING means waiting for Android's sent receipt.
  CALL_REPLY_SMS_FAILED_<number> preserves the Android error code.
  CALL_REPLY_SMS_SENT_DECLINE_REQUESTED means Android reported send success and
  the call-decline request was issued. SMS delivery to the recipient is **not**
  verified. Some carriers disallow sending while ringing; the call then remains
  unchanged and the failure is reported. Carrier charges may apply.
- Caller numbers and message bodies are not written to structured logs. Call
  notifications and locked UI omit caller identity. Review requires unlock.
- The POC does not supply video surfaces, RTT, call history or a production dialer.
  It must not be represented as a fully validated replacement Phone app.
- Android/OEM notification restrictions can prevent full-screen presentation.
  SMS reply availability depends on the SIM, carrier and Android SMS support.

## Validation

Unit coverage: deterministic routing; no send during preparation; hidden/invalid
numbers; missing capability; expiry; role/lock changes; changed call identity;
call answered/ended before confirmation; cancellation; duplicate confirmation;
exceptions with no retry.
Spoken-confirmation tests additionally cover partial/ambiguous input, explicit
cancellation, final-only sending, stale-call rejection and single-use confirmation.
Wake handoff tests verify that capture cleanup completes before pause is reported,
normal detection is preserved, service cancellation propagates, and withdrawing
a pause does not accidentally stop wake listening.
Send-result tests cover failure without decline, duplicate receipts, stale-call
receipts, decline exceptions and missing send permission/SIM.

Device evidence on 2026-10-01: Android 13; Aiko held default Phone role; the user
reported normal SMS works but the older Telecom reject-with-message path declined
without an SMS. No corresponding SMS error was available in captured log buffers.
The exact underlying OEM/carrier failure is unconfirmed.

**Pending physical-device testing:** role acceptance; incoming-call notification
and ringtone; screen-on/off and locked/unlocked UI; caller presentation; real SMS
dispatch/delivery; SIM selection; Bluetooth/audio; DTMF; conference/call waiting;
restore-original-dialer behavior. No real calls were rejected and no real SMS
were sent during automated verification.

Official API references:
- https://developer.android.com/reference/android/telecom/InCallService
- https://developer.android.com/reference/android/telecom/Call#reject(boolean,java.lang.String)
- https://developer.android.com/reference/android/telecom/Call.Details#CAPABILITY_RESPOND_VIA_TEXT

## Incoming voice audio handoff

When the user invokes Aiko during a ringing call, the default-dialer integration now requests `TelecomManager.silenceRinger()` before the initial spoken acknowledgement and command recognition, rather than waiting until the reply review. A short cancellable wait allows asynchronous ringtone shutdown. The same preparation runs before the review prompt. It is not run by idle keyword detection, and it does not answer, reject, or send anything. Active/held/outgoing calls, including call waiting alongside an established call, are excluded.

This addresses a possible early `AUDIO_FOCUS_DENIED` failure observed before any SMS attempt. Android may still deny focus or microphone capture; successful incoming-call voice review and SMS sending require a physical-device test. Ringtone silencing lasts for that incoming call even if the user cancels the assistant. No automatic ringtone restart is attempted.


Voice shortcut: Say 'decline the call and text I will call you later' or 'cut the call and send SMS I will call you later'. A complete validated command now authorizes the fixed SMS without opening review, in locked and unlocked active assistant sessions. Exactly one ringing call is required. SMS uses that call's visible number and SIM. Default Phone role, Android 11+, SEND_SMS and READ_PHONE_STATE remain required. Hidden/invalid numbers, multiple ringing calls and missing SIM are blocked. Pending is not proof of send or delivery. Decline follows successful sent receipt for the same still-ringing call. Manual call-screen review still requires unlock. Wake activation remains Hey Indo; Hey Aiko is accepted as a transcript prefix only. Test screen-off wake activation and actual carrier SMS on a physical phone after rebuilding.

Voice-friendly incoming calls: enable the optional switch in Driving Mode settings while unlocked. With wake listening enabled, new calls use the separate Voice-friendly incoming calls notification channel, configured for vibration and no sound. Driving Mode need not be on. Disabled wake listening or disabled preference uses the normal ringtone channel. The current ringing notification is not restarted on setting changes; configure before the next call. Android channel customization, DND and vibration settings can override defaults. This mitigates ringtone masking, not Android microphone restrictions. Physical-phone wake/SMS tests remain required.
