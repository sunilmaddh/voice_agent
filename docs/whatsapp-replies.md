# WhatsApp notification reply POC

Enable notification access and keep Aiko foreground and unlocked. By default, say **Reply WhatsApp saying I will call later** to prepare a memory-only review draft for the latest active WhatsApp/WhatsApp Business notification, if it has one unambiguous free-form reply action. It never skips an unsupported latest notification to reply to an older conversation.

## Instant voice replies (optional)

Settings → Read notifications → **Instant voice replies** is off by default and persisted in native preferences. Enabling it explicitly permits dictated replies without a per-message review. First say **Read my latest WhatsApp message**, then **Reply WhatsApp saying I will call later** within two minutes. Only the reply target captured from that read is eligible; the app never falls back to a newer conversation. The target must still pass notification/access/action checks. Group conversation titles are included in notification reading when supplied by WhatsApp.

The context is consumed on dispatch attempt and expires after two minutes; new notification reads, backgrounding, closing, or changing the setting clear it. Missing, changed, dismissed, or unsupported targets stop sending. Turning the toggle off restores review. This does not auto-generate replies to incoming messages. STT mistakes can be sent as dictated text in instant mode. No actual message was sent by development tools. Physical-device testing is pending.

With instant mode off, tap **Review WhatsApp reply** on Home. Verify the conversation (which may be a group) and exact dictated text, then tap **Send reply**, or Cancel. Only that confirmation invokes RemoteInput and the notification's PendingIntent in review mode. No arbitrary contacts, chat history, Accessibility automation, or WhatsApp private API is used. In instant mode the explicit dictated reply command dispatches directly and TTS reports the result.

Draft tokens are single-use and expire after two minutes. Backgrounding, locking via activity pause, cancelling, or closing clears the draft. Native code checks foreground/unlocked state again, notification access, connection, notification key/post time/content, and action identity before dispatch. Changed/dismissed notifications require a new draft. No automatic retry occurs. A successful PendingIntent dispatch is reported as handed to WhatsApp, **not delivered**. No live message was sent during implementation/testing.

On Android 28+ the action must be marked SEMANTIC_ACTION_REPLY. Older Android requires exactly one free-form action owned by the WhatsApp package. Notifications without a readable conversation label or compatible action are rejected. OEM/app restrictions and redacted content may prevent replying. This feature does not resolve the existing device STT language issue.

Unit tests cover prepare-without-send, explicit UI confirmation/cancellation, one-use tokens, expiry, locked state, and missing notifications. Physical-device tests remain pending: real WhatsApp and Business actions, group labeling, notification updates/removal, access revocation, and delivery verification in WhatsApp.

Android API reference: https://developer.android.com/reference/android/app/RemoteInput#addResultsToIntent(android.app.RemoteInput[],android.content.Intent,android.os.Bundle)
