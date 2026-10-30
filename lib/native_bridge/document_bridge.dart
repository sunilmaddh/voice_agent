import 'package:flutter/services.dart';

/// Document payloads are session data, never executable assistant actions.
class DocumentBridge {
  const DocumentBridge();
  static const channel = MethodChannel('voice_assistant/documents/v1');
  Future<Map<String, Object?>> run(
    String method, [
    Map<String, Object?>? args,
  ]) async =>
      await channel.invokeMapMethod<String, Object?>(method, args) ?? {};
}
