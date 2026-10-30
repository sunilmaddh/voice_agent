import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class SystemAssistantSettings extends StatefulWidget {
  const SystemAssistantSettings({super.key});
  @override
  State<SystemAssistantSettings> createState() =>
      _SystemAssistantSettingsState();
}

class _SystemAssistantSettingsState extends State<SystemAssistantSettings>
    with WidgetsBindingObserver {
  static const channel = MethodChannel('voice_assistant/methods/v1');
  Map<String, Object?> report = {};
  bool busy = false;
  bool refreshPending = false;
  String? error;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) refresh();
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) refresh();
  }

  void refresh() {
    if (busy) {
      refreshPending = true;
    } else {
      run('systemAssistantStatus');
    }
  }

  Future<void> run(String method, [bool? enabled]) async {
    if (busy) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final value = await channel.invokeMapMethod<String, Object?>(
        method,
        enabled == null ? null : {'enabled': enabled},
      );
      if (mounted && value != null) setState(() => report = value);
    } catch (_) {
      if (mounted) {
        setState(
          () => error = method == 'requestAssistantRole'
              ? 'Could not open assistant settings. Finish voice interaction and unlock your phone, then retry. You can also open phone Settings and search for Default apps or Digital assistant app.'
              : 'Open and unlock Aiko, finish the current voice request, then retry.',
        );
      }
    } finally {
      if (mounted) {
        setState(() => busy = false);
        if (refreshPending) {
          refreshPending = false;
          refresh();
        }
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final snapshot = report['snapshot'] as Map?;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'System assistant',
              style: TextStyle(fontWeight: FontWeight.bold),
            ),
            const Text(
              'Choose Aiko as the default digital assistant, then use your device’s assistant gesture or supported headset button. Availability from the lock screen depends on the device. This replaces your current assistant; restore it in Android settings. For hands-free use, enable Hey Indo below while unlocked. It uses continuous local microphone listening with a persistent notification, not a hardware hotword detector.',
            ),
            Text(
              'Selected: ${snapshot?['systemAssistantSelected'] ?? (busy ? 'checking…' : 'unknown')}',
            ),
            TextButton(
              onPressed: busy ? null : () => run('requestAssistantRole'),
              child: const Text('Choose default assistant'),
            ),
            const Text(
              'On the Android screen that opens, tap Digital assistant app (or Default digital assistant app), then choose voice_assistant or Aiko. Return here and refresh to verify selection.',
            ),
            TextButton(
              onPressed: busy ? null : () => run('systemAssistantStatus'),
              child: const Text('Refresh system assistant'),
            ),
            TextButton(
              onPressed: busy ? null : () => run('previewLockScreenAssistant'),
              child: const Text('Preview native assistant'),
            ),
            if (snapshot == null)
              Text(
                busy
                    ? 'Loading saved personal-readout preference…'
                    : 'Saved personal-readout preference unavailable. Tap Refresh to retry.',
              ),
            if (snapshot != null)
              SwitchListTile(
                title: const Text('Allow personal readouts while locked'),
                subtitle: const Text(
                  'Anyone who invokes the assistant may hear calendar events, Gmail and available notification previews. Off by default. Hidden notifications stay hidden. Gmail replies and app launches require unlocking.',
                ),
                value: snapshot['lockedPersonalReads'] == true,
                onChanged: busy
                    ? null
                    : (value) => run('systemAssistantPrivacy', value),
              ),
            if (snapshot != null)
              SwitchListTile(
                title: const Text(
                  'Allow WhatsApp notification replies while locked',
                ),
                subtitle: const Text(
                  'Off by default. Also enable personal readouts above. Anyone holding your phone can dictate, review and send a reply without unlocking. In the native assistant, review the recipient and text, then say Send reply after the prompt or tap the button. Hidden notifications and actions requiring authentication remain blocked.',
                ),
                value: snapshot['lockedWhatsAppReplies'] == true,
                onChanged: busy
                    ? null
                    : (value) => run('systemAssistantLockedReplies', value),
              ),
            if (error != null) Text(error!),
            TextButton(
              onPressed: busy ? null : () => run('callingSettings'),
              child: const Text('Configure hands-free phone calls'),
            ),
            OutlinedButton(
              onPressed: busy ? null : () => run('smsSettings'),
              child: const Text('Configure SMS access'),
            ),
            if (snapshot != null)
              ExpansionTile(
                title: const Text('System session diagnostics'),
                children: [
                  SelectableText(
                    const JsonEncoder.withIndent('  ').convert({
                      'snapshot': snapshot,
                      'lastAttempt': report['lastAttempt'],
                      'lastCommand': report['lastCommand'],
                    }),
                  ),
                ],
              ),
          ],
        ),
      ),
    );
  }
}
