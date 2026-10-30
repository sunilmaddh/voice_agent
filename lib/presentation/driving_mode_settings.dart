import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class DrivingModeSettings extends StatefulWidget {
  const DrivingModeSettings({super.key});
  @override
  State<DrivingModeSettings> createState() => _DrivingModeSettingsState();
}

class _DrivingModeSettingsState extends State<DrivingModeSettings>
    with WidgetsBindingObserver {
  static const channel = MethodChannel('voice_assistant/methods/v1');
  Map<String, Object?> status = {};
  bool busy = false;
  String? error;
  final reply = TextEditingController();
  bool dirty = false;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    refresh();
  }

  @override
  void dispose() {
    reply.dispose();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) refresh();
  }

  Future<void> refresh([
    String method = 'drivingModeStatus',
    bool? enabled,
  ]) async {
    if (busy) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final result = await channel.invokeMapMethod<String, Object?>(
        method,
        enabled == null ? null : {'enabled': enabled},
      );
      if (mounted) {
        setState(() {
          status = result ?? {};
          if (!dirty) {
            reply.text =
                status['message'] as String? ??
                'I am driving right now. I will call you back later.';
          }
        });
      }
    } on PlatformException catch (e) {
      if (mounted) {
        setState(() => error = '${e.code}: ${e.message ?? "Unavailable"}');
      }
    } catch (_) {
      if (mounted) setState(() => error = 'Driving mode requires Android.');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> autoReply(bool enabled) async {
    if (enabled && dirty) {
      setState(
        () => error = 'Save your personal reply before enabling automatic SMS.',
      );
      return;
    }
    if (enabled) {
      final accepted = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Enable automatic driving SMS?'),
          content: Text(
            'While Driving Mode is on, including when your phone is locked, Aiko will send your saved reply: "${status['message'] ?? reply.text}" to eligible incoming callers, then decline only after Android reports SMS sent. No per-call review. Carrier charges may apply. Repeated calls can receive repeated replies. Delivery is not verified.',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Enable'),
            ),
          ],
        ),
      );
      if (accepted != true || !mounted) return;
    }
    await refresh('drivingModeAutoReply', enabled);
  }

  Future<void> editReply(bool dictate) async {
    if (busy) return;
    if (!dictate &&
        (reply.text.trim().isEmpty || reply.text.trim().length > 70)) {
      setState(() => error = 'Enter a personal reply of 1 to 70 characters.');
      return;
    }
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final result = await channel.invokeMapMethod<String, Object?>(
        dictate ? 'drivingReplyDictate' : 'drivingModeMessage',
        dictate ? null : {'message': reply.text.trim()},
      );
      if (!mounted) return;
      setState(() {
        if (dictate) {
          reply.text = result?['draft'] as String? ?? '';
          dirty = true;
        } else {
          status = result ?? {};
          reply.text = status['message'] as String? ?? reply.text;
          dirty = false;
        }
      });
    } on PlatformException catch (e) {
      if (mounted) {
        setState(() => error = '${e.code}: ${e.message ?? "Unavailable"}');
      }
    } catch (_) {
      if (mounted) setState(() => error = 'Could not update your reply.');
    } finally {
      if (mounted) setState(() => busy = false);
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
            'Driving mode',
            style: TextStyle(fontWeight: FontWeight.bold),
          ),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Silence incoming calls'),
            subtitle: const Text(
              'Say "I am driving" to enable silence, automatic SMS and decline, including while locked. Sends your saved reply; carrier charges may apply. Turning Driving Mode off disables automatic SMS.',
            ),
            value: status['enabled'] == true,
            onChanged: busy || status.isEmpty
                ? null
                : (v) => refresh('drivingModeSet', v),
          ),
          TextField(
            controller: reply,
            enabled: !busy && status.isNotEmpty,
            maxLength: 70,
            decoration: const InputDecoration(
              labelText: 'Personal driving reply',
              helperText: 'Type or dictate, review the text, then save.',
            ),
            onChanged: (_) => setState(() => dirty = true),
          ),
          Wrap(
            spacing: 8,
            children: [
              OutlinedButton(
                onPressed: busy || status.isEmpty
                    ? null
                    : () => editReply(true),
                child: Text(busy ? 'Please wait…' : 'Dictate reply'),
              ),
              FilledButton(
                onPressed: busy || !dirty ? null : () => editReply(false),
                child: const Text('Save reply'),
              ),
            ],
          ),
          if (dirty)
            const Text(
              'Unsaved draft. Automatic replies still use the saved message.',
            ),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Automatic driving SMS and decline'),
            subtitle: const Text(
              'Works while locked after enabling. Requires default Phone role and SMS permission. Unlock to edit settings.',
            ),
            value: status['automaticReply'] == true,
            onChanged: busy || status.isEmpty ? null : autoReply,
          ),
          if (status['defaultPhone'] != true)
            const Text(
              'Choose Aiko as default Phone using calling settings below. Call handling is unavailable until then.',
            ),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Voice-friendly incoming calls'),
            subtitle: const Text(
              'Vibration only for new incoming calls while Hey Indo listening is enabled. Works independently of Driving Mode. Android notification settings can override sound and vibration.',
            ),
            value: status['voiceFriendlyRinging'] == true,
            onChanged: busy || status.isEmpty
                ? null
                : (v) => refresh('voiceFriendlyRingingSet', v),
          ),
          const Text(
            'This silences incoming call ringing, not all app notifications. It does not speak a response into the caller’s phone or enable Hey Indo automatically.',
          ),
          Text('Last driving event: ${status['lastEvent'] ?? "Not loaded"}'),
          Text(
            'Latest call-reply SMS result: ${status['smsResult'] ?? "Not tested"}',
          ),
          const Text('Physical-device validation pending.'),
          if (error != null)
            Text(
              error!,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          TextButton(
            onPressed: busy ? null : () => refresh(),
            child: const Text('Refresh driving status'),
          ),
        ],
      ),
    ),
  );
}
