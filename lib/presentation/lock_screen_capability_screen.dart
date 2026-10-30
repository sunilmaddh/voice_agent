import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../native_bridge/assistant_bridge.dart';
import '../native_bridge/assistant_bridge_provider.dart';
import '../native_bridge/contracts/lock_screen_capability_result.dart';

class LockScreenCapabilityScreen extends ConsumerStatefulWidget {
  const LockScreenCapabilityScreen({super.key});
  @override
  ConsumerState<LockScreenCapabilityScreen> createState() =>
      _LockScreenCapabilityScreenState();
}

class _LockScreenCapabilityScreenState
    extends ConsumerState<LockScreenCapabilityScreen> {
  Map<String, Object?>? report;
  String? error;
  bool busy = false;
  @override
  void initState() {
    super.initState();
    Future.microtask(() => run('lockScreenReport'));
  }

  Future<void> run(String method) async {
    if (!mounted || busy) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final bridge = ref.read(assistantBridgeProvider);
      if (bridge is! LockScreenBridge) {
        throw const BridgeException(
          'UNAVAILABLE',
          'Android lock-screen bridge unavailable.',
        );
      }
      final value = await (bridge as LockScreenBridge).lockScreenOperation(
        method,
      );
      if (mounted) setState(() => report = value);
    } catch (_) {
      if (mounted) {
        setState(
          () => error =
              'Could not read capabilities or open assistant settings. Keep Aiko unlocked and finish other operations.',
        );
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final results = ((report?['capabilities'] as List?) ?? const [])
        .map(
          (e) => LockScreenCapabilityResult.fromMap(
            Map<String, Object?>.from(e as Map),
          ),
        )
        .toList();
    return Scaffold(
      appBar: AppBar(title: const Text('Lock-screen POC')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          const Text(
            'Capability assessment ≠ completed device test. Choose Aiko as your assistant only if you want to test system invocation. This changes your default assistant. You can restore it in Android settings.',
          ),
          const SizedBox(height: 12),
          OutlinedButton(
            onPressed: busy ? null : () => run('requestAssistantRole'),
            child: const Text('Choose default assistant'),
          ),
          OutlinedButton(
            onPressed: busy ? null : () => run('previewLockScreenAssistant'),
            child: const Text('Preview assistant surface (not a lock test)'),
          ),
          OutlinedButton(
            onPressed: busy ? null : () => run('lockScreenReport'),
            child: const Text('Refresh device evidence'),
          ),
          if (busy) const LinearProgressIndicator(),
          if (error != null) Text(error!),
          const Text(
            'Use Android’s assistant gesture. Screen-off/headset invocation is device-dependent and requires testing. The system assistant uses short listening sessions, not a hotword loop. Grant microphone access from Home and configure personal readouts in Settings first.',
          ),
          const SizedBox(height: 12),
          const Text('Current device snapshot / most recent native attempt'),
          SelectableText(
            const JsonEncoder.withIndent('  ').convert({
              'snapshot': report?['snapshot'],
              'lastAttempt': report?['lastAttempt'],
            }),
          ),
          for (final result in results)
            ExpansionTile(
              title: Text(result.command),
              subtitle: Text(
                '${result.state}\n${result.details['status']} · ${result.evidence}',
              ),
              children: [
                Padding(
                  padding: const EdgeInsets.all(12),
                  child: SelectableText(
                    const JsonEncoder.withIndent('  ').convert(result.details),
                  ),
                ),
              ],
            ),
        ],
      ),
    );
  }
}
