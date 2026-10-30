import 'package:flutter/services.dart';
import 'contracts/speech_options.dart';

class SpeechPreferences {
  const SpeechPreferences();
  static const _channel = MethodChannel('voice_assistant/methods/v1');
  Future<SpeechOptions> read() async {
    final value = await _channel.invokeMapMethod<String, Object?>(
      'speechPreferencesRead',
    );
    final allowed = value?['allowSystemRecognition'] == true;
    return SpeechOptions(
      languageTag: const ['en-US', 'hi-IN', 'kn-IN'].contains(value?['languageTag'])
          ? value!['languageTag'] as String : 'en-US',
      allowSystemRecognition: allowed,
      preferSystemRecognition:
          allowed && value?['preferSystemRecognition'] == true,
    );
  }

  Future<void> write(SpeechOptions options) =>
      _channel.invokeMethod<void>('speechPreferencesWrite', {
        'languageTag': options.languageTag,
        'allowSystemRecognition': options.allowSystemRecognition,
        'preferSystemRecognition':
            options.allowSystemRecognition && options.preferSystemRecognition,
      });
}
