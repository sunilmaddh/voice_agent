import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../native_bridge/assistant_bridge.dart';
import '../native_bridge/assistant_bridge_provider.dart';
import 'assistant_controller.dart';

class CalendarSettings extends ConsumerStatefulWidget {
  const CalendarSettings({super.key});
  @override
  ConsumerState<CalendarSettings> createState() => _CalendarSettingsState();
}

class _CalendarSettingsState extends ConsumerState<CalendarSettings> {
  Map<String, Object?> permissions = {};
  bool loading = false;
  String? error;
  Future<void> check(bool request) async {
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final bridge = ref.read(assistantBridgeProvider);
      if (bridge is! CalendarBridge) {
        throw const BridgeException(
          'UNAVAILABLE',
          'Calendar integration requires Android.',
        );
      }
      final result = await (bridge as CalendarBridge).calendarPermissions(
        request: request,
      );
      if (mounted) setState(() => permissions = result);
    } on BridgeException catch (e) {
      if (mounted) setState(() => error = e.message);
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final busy = loading || ref.watch(assistantControllerProvider).busy;
    String status(String key) => permissions.containsKey(key)
        ? (permissions[key] == true ? 'Enabled' : 'Not enabled')
        : 'Not checked';
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Calendar and reminders',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const Text(
              'Reads visible Google calendars synced to this phone. Event creation opens a draft for you to save. Reminder notifications stay on this device and may be delayed by Android.',
            ),
            Text('Calendar access: ${status('calendarRead')}'),
            Text('Reminder notifications: ${status('notifications')}'),
            Wrap(
              spacing: 8,
              children: [
                OutlinedButton(
                  onPressed: busy ? null : () => check(false),
                  child: const Text('Check access'),
                ),
                FilledButton(
                  onPressed: busy ? null : () => check(true),
                  child: const Text('Enable calendar and reminders'),
                ),
              ],
            ),
            if (error != null) Text(error!),
            const Text(
              'If access stays disabled, allow Calendar and Notifications in Android Settings → Apps → Aiko. Also enable the Assistant reminders notification channel.',
            ),
            const SizedBox(height: 8),
            const Text(
              'Try: “What meetings do I have tomorrow?”\n“Read meeting details today”\n“Read upcoming calendar events”\n“Remind me tomorrow at 4 PM to attend my meeting”\n“Create a calendar event Team review tomorrow from 4 PM to 5 PM”',
            ),
          ],
        ),
      ),
    );
  }
}
