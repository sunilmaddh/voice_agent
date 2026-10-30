import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../native_bridge/assistant_bridge.dart';
import '../native_bridge/assistant_bridge_provider.dart';
import 'assistant_controller.dart';

class WakeWordControls extends ConsumerStatefulWidget {
  const WakeWordControls({super.key});
  @override
  ConsumerState<WakeWordControls> createState() => _WakeWordControlsState();
}

class _WakeWordControlsState extends ConsumerState<WakeWordControls> {
  Timer? timer;
  Map<String, Object?> status = const {};
  String? error;
  bool busy = false;
  @override
  void initState() {
    super.initState();
    timer = Timer.periodic(
      const Duration(seconds: 2),
      (_) => run('wakeWordStatus'),
    );
  }

  @override
  void dispose() {
    timer?.cancel();
    super.dispose();
  }

  Future<void> run(String method) async {
    if (busy || !mounted) return;
    final bridge = ref.read(assistantBridgeProvider);
    if (bridge is! WakeWordBridge) return;
    setState(() {
      busy = true;
      if (method != 'wakeWordStatus') error = null;
    });
    try {
      final data = await (bridge as WakeWordBridge).wakeWordOperation(method);
      if (mounted && method == 'wakeWordStatus') setState(() => status = data);
    } catch (e) {
      if (mounted) {
        setState(
          () => error = e is BridgeException
              ? '${e.code}: ${e.message}'
              : 'Wake-word service unavailable.',
        );
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final voiceBusy = ref.watch(
      assistantControllerProvider.select((s) => s.busy),
    );
    return ExpansionTile(
      title: const Text('Hey Indo / Hey Aiko — wake-word POC'),
      children: [
        const Padding(
          padding: EdgeInsets.all(12),
          child: Text(
            'Say Hey Aiko or Hey Indo, wait for the acknowledgement, then say "I am driving. Don\'t disturb me." Pause between the wake phrase and command.',
          ),
        ),
        const Padding(
          padding: EdgeInsets.all(12),
          child: Text(
            'Enable once to keep listening until you tap Stop listening. Detection resumes after each interaction. A microphone notification remains visible. Android stopping the app, a reboot, or a service error may require enabling again. Say “Hey Indo”, pause, then speak after listening starts. With Aiko selected as default assistant, detection opens the shared native voice pipeline, including while locked where Android permits. Personal reads require the lock-screen privacy setting; hidden notifications stay hidden and protected actions require unlocking. Without the assistant role, background commands are limited to time and reminders. Saved English STT preferences apply. Accuracy and battery use need device testing.',
          ),
        ),
        Text('State: ${status['state'] ?? 'Not checked'}'),
        if (status['state'] == 'STARTING')
          const Text(
            'Preparing the microphone. Wait until the state says LISTENING before saying the wake phrase.',
          ),
        if (status['keywordsThreshold'] != null)
          Text(
            'Wake threshold: ${status['keywordsThreshold']} · keyword boost: ${status['keywordsScore']}',
          ),
        if (status['active'] == false)
          const Text(
            'Listening is off. Enable wake listening for Hey Indo or Hey Aiko.',
          ),
        if (status['error'] != null)
          SelectableText(
            'Wake-word error at ${status['errorStage'] ?? 'startup'}: ${status['error']}',
          ),
        if (status['mediaVolume'] == 0)
          const Text(
            'Media volume was zero at acknowledgement. Check phone and headset output volume.',
          ),
        if (status['state'] == 'LISTENING' &&
            status['audioRmsDbfs'] is num &&
            (status['audioRmsDbfs'] as num) < -85)
          const Text(
            'Very little microphone audio is arriving. Speak near the phone and check that the level rises.',
          ),
        if (status['microphoneSilencedByAndroid'] == true)
          const Text(
            'Android is silencing the microphone. Check microphone privacy settings and close other recording apps.',
          ),
        if (status['audioRmsDbfs'] is num)
          Text(
            'Microphone level: ${(status['audioRmsDbfs'] as num).toStringAsFixed(1)} dBFS — should rise when you speak.',
          ),
        if (error != null) Text(error!),
        Wrap(
          spacing: 8,
          children: [
            OutlinedButton(
              onPressed: busy || voiceBusy || status['active'] == true
                  ? null
                  : () => run('wakeWordPermissions'),
              child: const Text('Grant permissions'),
            ),
            FilledButton(
              onPressed: busy || voiceBusy || status['active'] == true
                  ? null
                  : () => run('wakeWordStart'),
              child: const Text('Enable Hey Indo / Hey Aiko'),
            ),
            OutlinedButton(
              onPressed: busy ? null : () => run('wakeWordStop'),
              child: const Text('Stop listening'),
            ),
          ],
        ),
        SelectableText(const JsonEncoder.withIndent('  ').convert(status)),
        TextButton.icon(
          onPressed: status.isEmpty
              ? null
              : () async {
                  await Clipboard.setData(
                    ClipboardData(
                      text: const JsonEncoder.withIndent('  ').convert(status),
                    ),
                  );
                  if (context.mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('Wake diagnostics copied')),
                    );
                  }
                },
          icon: const Icon(Icons.copy),
          label: const Text('Copy wake diagnostics'),
        ),
      ],
    );
  }
}
