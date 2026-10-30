# Notification reading POC

Settings → Read notifications → Manage notification access opens Android's special-access screen. The user must enable **Aiko notification reader**. Refresh displays grant and connection separately; returning from Settings also refreshes. This is separate from permission to post reminder notifications.

Commands: `Read notifications` (at most five active previews), `Read my latest notification`, and `Read my latest WhatsApp message` / `Read my WhatsApp` (latest WhatsApp or WhatsApp Business preview).

The native listener waits for Android connection and reads a fresh active snapshot on demand. It excludes this app and group-summary duplicates, sorts by post time, and extracts the latest MessagingStyle message or text preview. No notification archive, background speech, dismissal, chat-history access, or cloud model is implemented. Notification text is untrusted content and is only read, never routed as a command. Reading requires the app foreground and the phone unlocked. No default-assistant role is required.

Notification content appears in the current session response/debug UI and TTS, but is not written to structured logs or disk. Android/source-app redaction, notification settings, disconnected listeners, managed profiles, and device restrictions can limit availability. Dismissed notifications are unavailable; a preview may not contain the complete message. Android 15 can redact sensitive notification content. No bypass is attempted.

Reference: https://developer.android.com/reference/android/service/notification/NotificationListenerService

Physical-device acceptance remains pending: grant/deny/revoke access; receive a WhatsApp notification; read via voice; dismiss and retry; verify hidden previews, grouping, lock/unlock and reconnect. The existing STT language failure must be resolved for a voice demonstration. Unit tests use supplied notification records and do not establish real WhatsApp/device success.

Confirmed notification replies are documented in [whatsapp-replies.md](whatsapp-replies.md).
