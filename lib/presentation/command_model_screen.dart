import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../native_bridge/assistant_bridge.dart';
import '../native_bridge/assistant_bridge_provider.dart';

class CommandModelScreen extends ConsumerStatefulWidget {
  const CommandModelScreen({super.key});
  @override
  ConsumerState<CommandModelScreen> createState() => _CommandModelScreenState();
}

class _CommandModelScreenState extends ConsumerState<CommandModelScreen> {
  final file = TextEditingController(text: 'command-model.litertlm');
  final model = TextEditingController(text: 'gemma-3-270m-it');
  final hash = TextEditingController();
  final quantization = TextEditingController(text: 'unknown');
  final input = TextEditingController(
    text: 'Could you get directions to Bangalore Airport?',
  );
  bool busy = false;
  String output = 'PENDING DEVICE EXECUTION. No local model measurements yet.';
  String? error;
  CommandModelBridge? get bridge {
    final value = ref.read(assistantBridgeProvider);
    return value is CommandModelBridge ? value as CommandModelBridge : null;
  }

  Future<void> run(
    String method, [
    Map<String, Object?> args = const {},
  ]) async {
    final native = bridge;
    if (native == null) {
      setState(() => error = 'Command model bridge unavailable.');
      return;
    }
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final result = await native.commandOperation(method, args);
      if (mounted) {
        setState(
          () => output = const JsonEncoder.withIndent('  ').convert(result),
        );
      }
    } on BridgeException catch (failure) {
      if (mounted) setState(() => error = failure.message);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> cancel() async {
    try {
      await bridge?.cancelCommandWork();
    } catch (_) {
      if (mounted) setState(() => error = 'Could not request cancellation.');
    }
  }

  @override
  void dispose() {
    for (final controller in [file, model, hash, quantization, input]) {
      controller.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: !busy,
    child: Scaffold(
      appBar: AppBar(title: const Text('Tier 2 model evaluation')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          const Text(
            'Stage a compatible .litertlm model in the app-private command_models directory using adb. '
            'See docs/tier2-command-model.md. Model selection is provisional; no weights are bundled.',
          ),
          TextField(
            controller: file,
            enabled: !busy,
            decoration: const InputDecoration(labelText: 'Model filename'),
          ),
          TextField(
            controller: model,
            enabled: !busy,
            decoration: const InputDecoration(labelText: 'Model ID'),
          ),
          TextField(
            controller: hash,
            enabled: !busy,
            decoration: const InputDecoration(labelText: 'Expected SHA-256'),
          ),
          TextField(
            controller: quantization,
            enabled: !busy,
            decoration: const InputDecoration(
              labelText: 'Declared quantization (not measured)',
            ),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              FilledButton(
                onPressed: busy
                    ? null
                    : () => run('configureCommandModel', {
                        'fileName': file.text.trim(),
                        'modelId': model.text.trim(),
                        'sha256': hash.text.trim(),
                        'quantization': quantization.text.trim(),
                      }),
                child: const Text('Load local model'),
              ),
              OutlinedButton(
                onPressed: busy ? null : () => run('commandModelStatus'),
                child: const Text('Model status'),
              ),
              OutlinedButton(
                onPressed: busy ? null : () => run('unloadCommandModel'),
                child: const Text('Unload'),
              ),
            ],
          ),
          const Divider(),
          TextField(
            controller: input,
            enabled: !busy,
            maxLength: 2000,
            decoration: const InputDecoration(
              labelText: 'Natural-language command',
            ),
          ),
          FilledButton(
            onPressed: busy
                ? null
                : () => run('extractCommand', {'input': input.text}),
            child: const Text('Route command (no actions)'),
          ),
          const SizedBox(height: 12),
          OutlinedButton(
            onPressed: busy ? null : () => run('diagnoseCommandModel', {'input': input.text}),
            child: const Text('Diagnose one command (no actions)'),
          ),
          const Text('Diagnostic runs the model directly and displays its raw response. It does not execute commands. Use a sample command without private information.'),
          const Text(
            'Benchmark: 38 fixed cases across six command categories and ambiguity controls. '
            'Calls the model directly, bypassing Tier 1. Keep the app foreground. '
            'Report includes per-case intent/parameter results, confidence, latency and process RAM.',
          ),
          OutlinedButton(
            onPressed: busy ? null : () => run('benchmarkCommands'),
            child: const Text('Run command benchmark'),
          ),
          if (busy) const LinearProgressIndicator(),
          if (busy)
            TextButton(
              onPressed: cancel,
              child: const Text('Cancel model operation'),
            ),
          if (error != null) Text(error!),
          const SizedBox(height: 16),
          SelectableText(output),
          TextButton(
            onPressed: busy
                ? null
                : () => Clipboard.setData(ClipboardData(text: output)),
            child: const Text('Copy displayed JSON/report'),
          ),
        ],
      ),
    ),
  );
}
