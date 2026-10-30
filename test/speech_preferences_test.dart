import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:voice_assistant/native_bridge/speech_preferences.dart';
import 'package:voice_assistant/native_bridge/contracts/speech_options.dart';
import 'package:voice_assistant/presentation/speech_settings.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('voice_assistant/methods/v1');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  var saved = <String, Object?>{};
  setUp(() {
    saved = {};
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'speechPreferencesWrite') {
        saved = Map<String, Object?>.from(call.arguments as Map);
        return null;
      }
      return saved;
    });
  });
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));
  test('Explicit engine consent survives a fresh settings container', () async {
    const store = SpeechPreferences();
    await store.write(
      const SpeechOptions(
        allowSystemRecognition: true,
        preferSystemRecognition: true,
      ),
    );
    final restored = await store.read();
    final container = ProviderContainer(
      overrides: [
        initialSpeechSettingsProvider.overrideWithValue(
          SpeechSettings(options: restored),
        ),
      ],
    );
    addTearDown(container.dispose);
    expect(
      container.read(speechSettingsProvider).options.preferSystemRecognition,
      true,
    );
    await store.write(const SpeechOptions());
    expect((await store.read()).allowSystemRecognition, false);
    expect((await store.read()).preferSystemRecognition, false);
  });
  test(
    'Missing or inconsistent preferences cannot opt into online STT',
    () async {
      const store = SpeechPreferences();
      expect((await store.read()).allowSystemRecognition, false);
      saved = {'preferSystemRecognition': true};
      expect((await store.read()).preferSystemRecognition, false);
    },
  );
  test(
    'Hindi preference survives restart without enabling online recognition',
    () async {
      const store = SpeechPreferences();
      await store.write(const SpeechOptions(languageTag: 'hi-IN'));
      final restored = await store.read();
      expect(restored.languageTag, 'hi-IN');
      expect(restored.allowSystemRecognition, false);
      expect(restored.toMap()['languageTag'], 'hi-IN');
    },
  );
  test('Kannada preference survives restart without online recognition', () async {
    const store = SpeechPreferences();
    await store.write(const SpeechOptions(languageTag: 'kn-IN'));
    final restored = await store.read();
    expect(restored.languageTag, 'kn-IN');
    expect(restored.allowSystemRecognition, false);
    expect(restored.toMap()['languageTag'], 'kn-IN');
  });
}
