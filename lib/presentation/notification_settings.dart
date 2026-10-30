import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'instant_reply_setting.dart';

class NotificationSettings extends StatefulWidget {
  const NotificationSettings({super.key});
  @override
  State<NotificationSettings> createState() => _NotificationSettingsState();
}

class _NotificationSettingsState extends State<NotificationSettings>
    with WidgetsBindingObserver {
  static const _channel = MethodChannel('voice_assistant/methods/v1');
  String _status = 'Access not checked';
  bool _busy = false;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _check();
  }

  Future<void> _check({bool open = false}) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final status = await _channel.invokeMapMethod<String, Object?>(
        open ? 'notificationAccessSettings' : 'notificationAccessStatus',
      );
      if (!mounted) return;
      setState(
        () => _status = status?['granted'] != true
            ? 'Notification access is off'
            : status?['connected'] == true
            ? 'Notification reader connected'
            : 'Access granted; waiting for Android to connect',
      );
    } catch (_) {
      if (mounted) {
        setState(
          () => _status =
              'Could not check access. Keep Aiko open and unlocked, then retry.',
        );
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Read notifications',
            style: TextStyle(fontWeight: FontWeight.bold),
          ),
          const Text(
            'Allow “Aiko notification reader” in Android notification access. This permits access to other apps’ active notification previews. Reading requires an unlocked phone. No chat history or automatic replies.',
          ),
          Text(_status),
          const InstantReplySetting(),
          const Text(
            'To reply, say “Reply WhatsApp saying I will call later”, then review the conversation and text on Home and tap Send reply. Only active reply-capable notifications are supported.',
          ),
          const Text(
            'Say “Read notifications” or “Read my latest WhatsApp message”. Hidden or dismissed messages may be unavailable.',
          ),
          TextButton(
            onPressed: _busy ? null : () => _check(open: true),
            child: const Text('Manage notification access'),
          ),
          TextButton(
            onPressed: _busy ? null : _check,
            child: const Text('Refresh notification access'),
          ),
        ],
      ),
    ),
  );
}
