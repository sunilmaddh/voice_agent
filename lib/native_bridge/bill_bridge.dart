import 'package:flutter/services.dart';

class BillBridge {
  const BillBridge();
  static const _channel = MethodChannel('voice_assistant/bills/v1');

  Future<List<Map<String, Object?>>> extract(
    String method, {
    String? text,
  }) async {
    if (!{'extract', 'samples', 'notifications', 'sms'}.contains(method)) {
      throw ArgumentError('Unsupported bill source');
    }
    final rows = await _channel.invokeListMethod<Object?>(method, {
      'text': text,
    });
    return (rows ?? [])
        .map((e) => Map<String, Object?>.from(e as Map))
        .toList();
  }

  Future<Map<String, Object?>> schedule(
    String id,
    int hour,
    int minute,
  ) async =>
      await _channel.invokeMapMethod<String, Object?>('schedule', {
        'id': id,
        'hour': hour,
        'minute': minute,
      }) ??
      {};

  Future<void> clear() => _channel.invokeMethod<void>('clear');
}
