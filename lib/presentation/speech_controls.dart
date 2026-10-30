import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../benchmark/speech_benchmark.dart';
import '../native_bridge/contracts/models.dart';
import '../native_bridge/contracts/speech_options.dart';
import 'assistant_controller.dart';
import 'speech_settings.dart';
import 'wake_word_controls.dart';

class SpeechControls extends ConsumerWidget {
  const SpeechControls({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final settings = ref.watch(speechSettingsProvider);
    final actions = ref.read(speechSettingsProvider.notifier);
    final session = ref.watch(assistantControllerProvider);
    final controller = ref.read(assistantControllerProvider.notifier);
    final options = settings.options;
    final disabled = session.busy || settings.loading;
    final voices = (settings.capabilities['voices'] as List? ?? [])
        .map(objectMap)
        .where(
          (v) => (v['locale'] as String? ?? '').startsWith(
            options.languageTag.substring(0, 2),
          ),
        )
        .toList();
    void change({
      String? language,
      bool? system,
      bool? preferSystem,
      String? voice,
      bool resetVoice = false,
      double? rate,
      double? pitch,
      bool? offline,
    }) {
      actions.update(
        options: SpeechOptions(
          languageTag: language ?? options.languageTag,
          allowSystemRecognition: system ?? options.allowSystemRecognition,
          preferSystemRecognition: system == false
              ? false
              : preferSystem ?? options.preferSystemRecognition,
          voiceName: resetVoice ? null : voice ?? options.voiceName,
          rate: rate ?? options.rate,
          pitch: pitch ?? options.pitch,
          offlineTtsOnly: offline ?? options.offlineTtsOnly,
        ),
      );
    }

    return ExpansionTile(
      title: const Text('Speech language and settings'),
      children: [
        const WakeWordControls(),
        const Text('Kannada: select ಕನ್ನಡ below. Try ಸಮಯ ಎಷ್ಟು, ನೋಟಿಫಿಕೇಶನ್ ಓದಿ, or ನಿಫ್ಟಿ 50 ಹುಡುಕಿ. Install Kannada speech recognition and a Kannada TTS voice on your phone. Source answers may remain in their original language.'),
        DropdownButton<String>(
          value: options.languageTag,
          items: const [
            DropdownMenuItem(value: 'en-US', child: Text('English')),
            DropdownMenuItem(value: 'kn-IN', child: Text('ಕನ್ನಡ (Kannada)')),
            DropdownMenuItem(value: 'hi-IN', child: Text('हिन्दी (Hindi)')),
          ],
          onChanged: disabled
              ? null
              : (value) => change(language: value, resetVoice: true),
        ),
        const Text(
          'Choose English, Hindi, or Kannada. Offline recognition needs an installed language model. Try: बैंगलोर का मौसम बताओ, राहुल को कॉल करो, नोटिफिकेशन पढ़ो. Source answers may remain in English.',
        ),
        OutlinedButton(
          onPressed: disabled ? null : actions.load,
          child: Text(
            settings.loading
                ? 'Checking engines…'
                : 'Load voices and capabilities',
          ),
        ),
        if (settings.error != null) Text(settings.error!),
        Text(
          'Selected language offline STT: ${settings.capabilities['englishOfflineAvailability'] ?? 'Not checked'}',
        ),
        Text(
          'Recognition locale: ${settings.capabilities['locale'] ?? 'Not checked'}',
        ),
        if (settings.capabilities['offlineUnavailableReason'] != null)
          Text(
            'Offline availability: ${settings.capabilities['offlineUnavailableReason']}',
          ),
        Text(
          'TTS engine: ${settings.capabilities['ttsEngine'] ?? settings.capabilities['ttsError'] ?? 'Not checked'}',
        ),
        SwitchListTile(
          title: const Text(
            'Allow system recognition when on-device is unavailable',
          ),
          subtitle: const Text(
            'The system recognizer may send audio online. Actual network use is not exposed by Android.',
          ),
          value: options.allowSystemRecognition,
          onChanged: disabled ? null : (value) => change(system: value),
        ),
        SwitchListTile(
          title: const Text('Use offline-capable TTS voices only'),
          value: options.offlineTtsOnly,
          onChanged: disabled
              ? null
              : (value) => change(offline: value, resetVoice: true),
        ),
        SwitchListTile(
          title: const Text('Use system STT instead of on-device STT'),
          subtitle: const Text(
            'Applies while unlocked and to commands after Hey Indo while locked. May send speech to Google servers. Disable to return to on-device recognition.',
          ),
          value: options.preferSystemRecognition,
          onChanged: disabled
              ? null
              : (value) =>
                    change(preferSystem: value, system: value ? true : null),
        ),
        DropdownButton<String>(
          isExpanded: true,
          hint: const Text('Automatic voice for selected language'),
          value: voices.any((v) => v['name'] == options.voiceName)
              ? options.voiceName
              : null,
          items: voices
              .map(
                (v) => DropdownMenuItem<String>(
                  value: v['name'] as String,
                  enabled:
                      v['installed'] == true &&
                      (!options.offlineTtsOnly ||
                          v['requiresNetwork'] == false),
                  child: Text(
                    '${v['name']} · ${v['locale']} · ${v['requiresNetwork'] == true ? 'network' : 'offline capable'}',
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
              )
              .toList(),
          onChanged: disabled ? null : (value) => change(voice: value),
        ),
        const Text(
          'Android does not expose voice gender reliably. Audition available voices to select male/female-sounding voices; pitch is not a gender selector.',
        ),
        Text('Speech rate: ${options.rate.toStringAsFixed(1)}'),
        Slider(
          value: options.rate,
          min: .5,
          max: 2,
          divisions: 15,
          onChanged: disabled ? null : (value) => change(rate: value),
        ),
        Text('Pitch: ${options.pitch.toStringAsFixed(1)}'),
        Slider(
          value: options.pitch,
          min: .5,
          max: 2,
          divisions: 15,
          onChanged: disabled ? null : (value) => change(pitch: value),
        ),
        OutlinedButton(
          onPressed: disabled ? null : controller.preview,
          child: const Text('Preview selected voice'),
        ),
        SwitchListTile(
          title: const Text('Record benchmark trials'),
          subtitle: const Text(
            'Up to 100 in-memory trials. Export includes spoken transcripts; nothing is uploaded.',
          ),
          value: settings.benchmark,
          onChanged: disabled
              ? null
              : (value) => actions.update(benchmark: value),
        ),
        if (settings.benchmark) ...[
          DropdownButton<SoundEnvironment>(
            value: settings.environment,
            isExpanded: true,
            items: SoundEnvironment.values
                .map((e) => DropdownMenuItem(value: e, child: Text(e.name)))
                .toList(),
            onChanged: disabled
                ? null
                : (value) => actions.update(environment: value),
          ),
          DropdownButton<CommandLength>(
            value: settings.length,
            isExpanded: true,
            items: CommandLength.values
                .map(
                  (e) => DropdownMenuItem(
                    value: e,
                    child: Text('${e.name} command'),
                  ),
                )
                .toList(),
            onChanged: disabled
                ? null
                : (value) => actions.update(length: value),
          ),
          SelectableText('Say exactly: ${benchmarkPrompt(settings.length)}'),
          const Text(
            'Select the actual test environment yourself. Vehicle trials should be performed while parked or by a passenger.',
          ),
        ],
        Text('Recorded trials: ${session.trials.length}'),
        OutlinedButton(
          onPressed: session.trials.isEmpty
              ? null
              : () => showDialog<void>(
                  context: context,
                  builder: (context) => AlertDialog(
                    title: const Text('Benchmark JSON (contains transcripts)'),
                    content: SizedBox(
                      width: 650,
                      child: SingleChildScrollView(
                        child: SelectableText(
                          exportSpeechTrials(session.trials),
                        ),
                      ),
                    ),
                    actions: [
                      TextButton(
                        onPressed: () => Navigator.pop(context),
                        child: const Text('Close'),
                      ),
                    ],
                  ),
                ),
          child: const Text('View / copy benchmark JSON'),
        ),
        TextButton(
          onPressed: disabled || session.trials.isEmpty
              ? null
              : controller.clearTrials,
          child: const Text('Clear trials'),
        ),
      ],
    );
  }
}
