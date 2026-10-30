import 'dart:convert';
import 'dart:developer' as developer;

enum LogCode {
  requestStarted,
  requestCompleted,
  requestFailed,
  eventStreamFailed,
}

/// Only fixed codes and locally generated IDs enter logs. No payloads or errors.
class StructuredLogger {
  const StructuredLogger();
  void record(LogCode code, String requestId) {
    final safeId = RegExp(r'^req-[0-9]+-[0-9]+$').hasMatch(requestId)
        ? requestId
        : 'redacted';
    developer.log(
      jsonEncode({
        'schemaVersion': 1,
        'code': code.name,
        'requestId': safeId,
        'timestampMs': DateTime.now().millisecondsSinceEpoch,
      }),
      name: 'voice_assistant',
    );
  }
}
