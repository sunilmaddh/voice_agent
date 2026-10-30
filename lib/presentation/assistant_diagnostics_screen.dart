import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../native_bridge/contracts/models.dart';
import 'assistant_controller.dart';
import 'speech_controls.dart';
import 'command_model_screen.dart';
import 'lock_screen_capability_screen.dart';

class AssistantDiagnosticsScreen extends ConsumerWidget {
  const AssistantDiagnosticsScreen({super.key});

  Widget field(String label, String? value) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 8),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: const TextStyle(fontWeight: FontWeight.w600)),
        const SizedBox(height: 4),
        SelectableText(value ?? 'Not run'),
      ],
    ),
  );
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final controller = ref.watch(assistantControllerProvider);
    final actions = ref.read(assistantControllerProvider.notifier);
    final result = controller.response;
    final structured = result?.structuredOutput;
    final output = structured == null
        ? null
        : const JsonEncoder.withIndent('  ').convert(structured);
    final execution = switch (result?.executionMode) {
      ExecutionMode.local => 'LOCAL',
      ExecutionMode.cloud => 'CLOUD',
      _ => 'NOT RUN',
    };
    String metric(String name) {
      final values = result?.metrics.where((m) => m.stage == name);
      if (values == null || values.isEmpty || values.first.durationMs == null) {
        return values != null &&
                values.isNotEmpty &&
                values.first.status == 'unavailable'
            ? 'Unavailable'
            : 'Not run';
      }
      return '${values.first.durationMs!.toStringAsFixed(2)} ms';
    }

    return Scaffold(
      appBar: AppBar(
        title: const Text('Technical dashboard'),
        actions: [
          IconButton(
            tooltip: 'Lock-screen capability tests',
            icon: const Icon(Icons.lock_outline),
            onPressed: controller.busy
                ? null
                : () => Navigator.of(context).push<void>(
                    MaterialPageRoute(
                      builder: (_) => const LockScreenCapabilityScreen(),
                    ),
                  ),
          ),
          IconButton(
            tooltip: 'Tier 2 model evaluation',
            icon: const Icon(Icons.science_outlined),
            onPressed: controller.busy
                ? null
                : () => Navigator.of(context).push<void>(
                    MaterialPageRoute(
                      builder: (_) => const CommandModelScreen(),
                    ),
                  ),
          ),
        ],
      ),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 850),
          child: ListView(
            padding: const EdgeInsets.all(20),
            children: [
              const Text(
                'English voice → text → local command routing. Validated commands can open Google Maps or Google Search. Tier 2 requires a loaded local model.',
              ),
              const SizedBox(height: 16),
              FilledButton.icon(
                onPressed: controller.busy ? null : actions.start,
                icon: const Icon(Icons.mic_none),
                label: Text(
                  controller.busy
                      ? 'Contacting Android…'
                      : 'Start Voice Interaction',
                ),
              ),
              if (controller.busy) const LinearProgressIndicator(),
              if (controller.busy)
                OutlinedButton(
                  onPressed: actions.cancel,
                  child: const Text('Cancel speech'),
                ),
              field('Speech status', controller.phase),
              const SpeechControls(),
              if (controller.error != null) field('Error', controller.error),
              field(
                'STT Transcript',
                result?.stt?.transcript ?? controller.liveTranscript,
              ),
              if (controller.phase == 'STT_PARTIAL')
                const Text('Partial transcript — may change.'),
              field('Detected Intent', result?.intent?.kind.wireName),
              field(
                'Validated action proposal',
                (result?.routing['proposedAction'] as Map?)?['name'] as String?,
              ),
              field('Selected Model', result?.selectedModel),
              field('Structured Output', output),
              field('Action attempted', result?.action?.name),
              field('Assistant Response', result?.response),
              if (result != null)
                field(
                  'Request Result',
                  '${result.result.status.name}: ${result.result.code}',
                ),
              const Divider(),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Developer / Debug Mode'),
                subtitle: const Text(
                  'Session information only; no credentials are logged.',
                ),
                value: controller.debugMode,
                onChanged: actions.setDebugMode,
              ),
              if (controller.debugMode) ...[
                field(
                  'Routing confidence',
                  result?.intent?.confidence?.toStringAsFixed(2),
                ),
                field('Routing reason', result?.routing['reason'] as String?),
                field(
                  'Model required',
                  result?.routing['modelRequired']?.toString(),
                ),
                field(
                  'Model execution',
                  result?.routing['modelExecutionMode'] as String?,
                ),
                field('Routing latency', metric('routing')),
                field(
                  'Model measurements',
                  result == null
                      ? null
                      : const JsonEncoder.withIndent(
                          '  ',
                        ).convert(result.routing['modelMetrics']),
                ),
                field('STT engine', result?.speech['sttEngine'] as String?),
                field(
                  'STT capture diagnostics',
                  result == null
                      ? null
                      : const JsonEncoder.withIndent(
                          '  ',
                        ).convert(result.speech['sttDiagnostics']),
                ),
                field(
                  'STT offline/online status',
                  result?.speech['sttMode'] as String?,
                ),
                field(
                  'English offline availability',
                  result?.speech['englishOfflineAvailability'] as String?,
                ),
                field('TTS engine', result?.speech['ttsEngine'] as String?),
                field(
                  'Selected voice',
                  result?.speech['selectedVoice'] as String?,
                ),
                field(
                  'TTS offline/online status',
                  result?.speech['ttsMode'] as String?,
                ),
                field('TTS startup latency', metric('tts')),
                field('STT capture duration', metric('sttCapture')),
                field(
                  'Technical/Debug Information',
                  result?.requestId ?? 'No request yet',
                ),
                field('Input', result?.input),
                field('STT result', result?.stt?.transcript),
                field('Intent', result?.intent?.kind.wireName),
                field(
                  'Routing tier',
                  result?.routingTier == RoutingTier.notRun
                      ? null
                      : result?.routingTier.name,
                ),
                field('Selected model', result?.selectedModel),
                field('Structured JSON', output),
                field(
                  'Action executed',
                  result?.routing['actionExecuted'] == true
                      ? result?.action?.name
                      : 'None',
                ),
                field('Action result', result?.result.message),
                field(
                  'Action success (reported outcome)',
                  result?.result.evidence['success']?.toString(),
                ),
                field(
                  'Action outcome',
                  result?.result.evidence['outcome'] as String?,
                ),
                field(
                  'Android mechanism',
                  result?.result.evidence['androidMechanism'] as String?,
                ),
                field(
                  'User interaction required',
                  result?.result.evidence['userInteractionRequired']
                      ?.toString(),
                ),
                field(
                  'Unlock required by POC',
                  result?.result.evidence['unlockRequired']?.toString(),
                ),
                field(
                  'Action error',
                  result?.result.evidence['error'] as String?,
                ),
                field(
                  'Action restriction',
                  result?.result.evidence['restriction'] as String?,
                ),
                field(
                  'Complete action evidence',
                  result == null
                      ? null
                      : const JsonEncoder.withIndent(
                          '  ',
                        ).convert(result.result.evidence),
                ),
                field(
                  'Action execution mode',
                  result?.routing['actionExecutionMode'] as String?,
                ),
                field(
                  'Summary method',
                  result?.routing['summaryMethod'] as String?,
                ),
                field('Response', result?.response),
                field('STT latency', metric('stt')),
                field('SLM latency', metric('slm')),
                field(
                  'Router latency (including SLM if used)',
                  metric('routing'),
                ),
                field('Action latency', metric('action')),
                field('TTS latency', metric('tts')),
                field(
                  'TTS call duration (including playback)',
                  metric('ttsPlayback'),
                ),
                field('Total latency', metric('total')),
                const Text(
                  'STT latency: speech-end callback to final text. TTS startup: speak call to onStart. Missing timings are not estimates. LOCAL/CLOUD refers to routing. Maps/Search success means Android accepted the launch; navigation and search results are not verified.',
                ),
                field('LOCAL/CLOUD execution', execution),
                if (controller.eventWarning != null)
                  field('Diagnostics warning', controller.eventWarning),
                field(
                  'Structured events',
                  controller.events.isEmpty
                      ? 'No events'
                      : controller.events
                            .map((e) => '${e.timestampMs} ${e.level} ${e.code}')
                            .join('\n'),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
