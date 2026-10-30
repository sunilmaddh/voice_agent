import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class InstantReplySetting extends StatefulWidget {
  const InstantReplySetting({super.key});
  @override
  State<InstantReplySetting> createState() => _InstantReplySettingState();
}

class _InstantReplySettingState extends State<InstantReplySetting> {
  static const channel = MethodChannel('voice_assistant/methods/v1');
  bool enabled = false;
  bool busy = true;
  String? error;
  @override
  void initState() {
    super.initState();
    update();
  }

  Future<void> update([bool? value]) async {
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final saved = await channel.invokeMethod<bool>(
        value == null ? 'instantReplyRead' : 'instantReplyWrite',
        value == null ? null : {'enabled': value},
      );
      if (saved == null) throw const FormatException('Missing preference');
      if (mounted) setState(() => enabled = saved);
    } catch (_) {
      if (mounted) {
        setState(
          () => error =
              'Could not verify the setting. Keep Aiko unlocked and retry.',
        );
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Column(
    children: [
      SwitchListTile(
        title: const Text('Instant voice replies'),
        subtitle: const Text(
          'Send dictated WhatsApp replies without review. First read the WhatsApp notification, then say “Reply WhatsApp saying …” within two minutes. Requires an unlocked phone. May send incorrectly recognized words.',
        ),
        value: enabled,
        onChanged: busy || error != null ? null : (value) => update(value),
      ),
      if (error != null) ...[
        Text(error!),
        TextButton(
          onPressed: busy ? null : () => update(),
          child: const Text('Reload reply setting'),
        ),
      ],
    ],
  );
}
