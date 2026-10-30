import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'app/assistant_app.dart';
import 'native_bridge/speech_preferences.dart';
import 'native_bridge/contracts/speech_options.dart';
import 'presentation/speech_settings.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  var options = const SpeechOptions();
  String? warning;
  try {
    options = await const SpeechPreferences().read().timeout(
      const Duration(seconds: 3),
    );
  } catch (_) {
    warning =
        'Saved speech settings could not be loaded. Offline-only defaults are active.';
  }
  runApp(
    ProviderScope(
      overrides: [
        initialSpeechSettingsProvider.overrideWithValue(
          SpeechSettings(options: options, error: warning),
        ),
      ],
      child: const AssistantApp(),
    ),
  );
}
