import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../benchmark/speech_benchmark.dart';
import '../native_bridge/assistant_bridge.dart';
import '../native_bridge/assistant_bridge_provider.dart';
import '../native_bridge/contracts/speech_options.dart';
import '../native_bridge/speech_preferences.dart';

class SpeechSettings {
  const SpeechSettings({
    this.options = const SpeechOptions(),
    this.environment = SoundEnvironment.quiet,
    this.length = CommandLength.short,
    this.benchmark = false,
    this.loading = false,
    this.capabilities = const {},
    this.error,
  });
  final SpeechOptions options;
  final SoundEnvironment environment;
  final CommandLength length;
  final bool benchmark;
  final bool loading;
  final Map<String, Object?> capabilities;
  final String? error;
}

final initialSpeechSettingsProvider = Provider((ref) => const SpeechSettings());
final speechPreferencesProvider = Provider((ref) => const SpeechPreferences());
final speechSettingsProvider =
    NotifierProvider<SpeechSettingsController, SpeechSettings>(
      SpeechSettingsController.new,
    );

class SpeechSettingsController extends Notifier<SpeechSettings> {
  @override
  SpeechSettings build() => ref.read(initialSpeechSettingsProvider);
  void allowSystemRecognition() {
    final current = state.options;
    update(
      options: SpeechOptions(
        languageTag: current.languageTag,
        allowSystemRecognition: true,
        preferSystemRecognition: true,
        voiceName: current.voiceName,
        rate: current.rate,
        pitch: current.pitch,
        offlineTtsOnly: current.offlineTtsOnly,
      ),
    );
  }

  void update({
    SpeechOptions? options,
    SoundEnvironment? environment,
    CommandLength? length,
    bool? benchmark,
  }) {
    state = SpeechSettings(
      options: options ?? state.options,
      environment: environment ?? state.environment,
      length: length ?? state.length,
      benchmark: benchmark ?? state.benchmark,
      loading: state.loading,
      capabilities: state.capabilities,
      error: state.error,
    );
    if (options != null) {
      ref.read(speechPreferencesProvider).write(options).catchError((Object _) {
        if (!ref.mounted) return;
        state = SpeechSettings(
          options: state.options,
          environment: state.environment,
          length: state.length,
          benchmark: state.benchmark,
          loading: state.loading,
          capabilities: state.capabilities,
          error:
              'Speech settings apply now but could not be saved for restart.',
        );
      });
    }
  }

  Future<void> load() async {
    if (state.loading) return;
    state = SpeechSettings(
      options: state.options,
      environment: state.environment,
      length: state.length,
      benchmark: state.benchmark,
      capabilities: state.capabilities,
      loading: true,
    );
    try {
      final bridge = ref.read(assistantBridgeProvider);
      if (bridge is! SpeechBridge) {
        throw const BridgeException(
          'UNAVAILABLE',
          'Android speech unavailable.',
        );
      }
      final data = await (bridge as SpeechBridge).speechCapabilities();
      if (!ref.mounted) return;
      state = SpeechSettings(
        options: state.options,
        environment: state.environment,
        length: state.length,
        benchmark: state.benchmark,
        capabilities: Map.unmodifiable(data),
      );
    } catch (_) {
      if (!ref.mounted) return;
      state = SpeechSettings(
        options: state.options,
        environment: state.environment,
        length: state.length,
        benchmark: state.benchmark,
        capabilities: state.capabilities,
        error: 'Speech capabilities unavailable. Retry on Android.',
      );
    }
  }
}
