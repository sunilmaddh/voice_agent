# SMS read and reply POC

Implemented: English deterministic READ_SMS and REPLY_SMS routing, native inbox
query through Telephony.Sms.Inbox, voice readout through the existing TTS pipeline,
and an ACTION_SENDTO reply draft in the installed messaging app.

## Setup and use

Open Aiko Settings > System assistant > Configure SMS access. Select Aiko as the
default assistant before granting READ_SMS. The role, permission, foreground state
and unlocked state are checked for every operation. Android/installer restrictions
can prevent this restricted permission from being granted; the app reports this
instead of treating it as an empty inbox.

Say “Read my latest SMS”, then, within two minutes in the same app process,
“Reply to SMS saying I will call you later”. Review the exact recipient and text
in the SMS app and tap Send. Aiko reports a draft handoff, never delivery success.
It does not request SEND_SMS or send messages automatically.
The separate incoming-call reply feature does request SEND_SMS after setup; it
does not change the manual SMS-inbox composer flow documented here.

## Boundaries

- Inbox SMS only: no MMS, RCS, WhatsApp, conversation history or unread filtering.
- Reads at most 1,000 characters aloud and discloses truncation.
- Replies bind to the exact read message. Expired, changed or deleted messages,
  alphanumeric senders, short codes and invalid numbers are rejected.
- New incoming messages do not retarget an existing reply.
- Reply context is memory-only, single-use, shared across native/Flutter assistant
  sessions and lost on process death. SMS contents are not written to diagnostic logs. The readout is
  personal data visible in the existing response/debug UI.
- This implementation requires unlocking for both reading and composing.
- Choosing Aiko as default assistant is not Google Play approval: SMS permission
  declarations and distribution eligibility must be assessed before publishing.

## Verification

Unit tests cover routing, permission restriction, context expiry, message changes,
empty inbox, sender validation, composer failure and single-use handoff semantics.
Physical-device permission grant, inbox access, STT/TTS, SMS app recipient/body
handling, multi-SIM selection and actual user-confirmed sending remain pending.
No real SMS was sent during development.

References:
- https://developer.android.com/guide/topics/permissions/default-handlers
- https://developer.android.com/guide/components/intents-common#Messaging
- https://developer.android.com/reference/android/provider/Telephony.Sms.Inbox
