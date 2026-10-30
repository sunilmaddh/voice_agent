import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../native_bridge/bill_bridge.dart';

class BillRemindersScreen extends StatefulWidget {
  const BillRemindersScreen({super.key});
  @override
  State<BillRemindersScreen> createState() => _BillRemindersScreenState();
}

class _BillRemindersScreenState extends State<BillRemindersScreen> {
  final _bridge = const BillBridge();
  final _text = TextEditingController();
  List<Map<String, Object?>> _rows = [];
  Map<String, Object?>? _result;
  String? _error;
  bool _busy = false;
  TimeOfDay _time = const TimeOfDay(hour: 9, minute: 0);

  @override
  void dispose() {
    _text.dispose();
    _bridge.clear().catchError((Object _) {});
    super.dispose();
  }

  Future<void> _run(Future<void> Function() work) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
      _result = null;
    });
    try {
      await work();
    } on PlatformException catch (e) {
      if (mounted) setState(() => _error = e.code);
    } catch (_) {
      if (mounted) setState(() => _error = 'BILL_OPERATION_UNAVAILABLE');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _scan(String method) => _run(() async {
    setState(() => _rows = []);
    final rows = await _bridge.extract(method, text: _text.text);
    if (mounted) setState(() => _rows = rows);
  });

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Smart bill reminders')),
    body: ListView(
      padding: const EdgeInsets.all(16),
      children: [
        const Text(
          'Extract bill and expiry details locally, review them, then create a local reminder. No payment app connection or payment execution.',
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _text,
          enabled: !_busy,
          maxLength: 4000,
          minLines: 2,
          maxLines: 5,
          decoration: const InputDecoration(
            labelText: 'Notification / SMS sample text',
            border: OutlineInputBorder(),
          ),
        ),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            FilledButton(
              onPressed: _busy ? null : () => _scan('extract'),
              child: const Text('Extract text'),
            ),
            OutlinedButton(
              onPressed: _busy ? null : () => _scan('samples'),
              child: const Text('Validate 3 samples'),
            ),
            OutlinedButton(
              onPressed: _busy ? null : () => _scan('notifications'),
              child: const Text('Scan active notifications'),
            ),
            OutlinedButton(
              onPressed: _busy ? null : () => _scan('sms'),
              child: const Text('Read latest SMS'),
            ),
          ],
        ),
        const Text(
          'Notification scan requires notification access. Latest SMS requires the existing assistant role and SMS permission. Configure access in Aiko Settings. Only visible content is available; scans run on request.',
        ),
        ListTile(
          contentPadding: EdgeInsets.zero,
          title: Text('Reminder time: ${_time.format(context)} on due date'),
          subtitle: const Text(
            'Device time zone at creation. Android may delay delivery. Past dates/times cannot be scheduled.',
          ),
          trailing: const Icon(Icons.schedule),
          onTap: _busy
              ? null
              : () async {
                  final value = await showTimePicker(
                    context: context,
                    initialTime: _time,
                  );
                  if (mounted && value != null) setState(() => _time = value);
                },
        ),
        const Text(
          'Merchant stays unknown unless explicitly stated. HIGH priority means due within 7 days; otherwise MEDIUM. Priority does not enable Do Not Disturb bypass.',
        ),
        if (_busy) const LinearProgressIndicator(),
        if (_error != null)
          SelectableText(
            'Error: $_error\nCheck source text, permissions and date, then retry.',
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        if (_result != null)
          Card(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(_result!['message']?.toString() ?? 'No result message'),
                  SelectableText(
                    const JsonEncoder.withIndent('  ').convert(_result),
                  ),
                ],
              ),
            ),
          ),
        if (!_busy && _rows.isEmpty)
          const Padding(
            padding: EdgeInsets.all(12),
            child: Text('No extracted notices. Choose a source above.'),
          ),
        ..._rows.map(
          (row) => Card(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    row['category']?.toString() ??
                        'Unrecognized / unavailable notice',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  Text('Source: ${row['source']}'),
                  if (row['input'] != null)
                    SelectableText(row['input'].toString()),
                  Text(
                    'Merchant: ${row['merchant'] ?? 'Unknown'}\nAmount: ${row['amount'] ?? 'Not stated'} ${row['currency'] ?? ''}\nDue: ${row['due_date'] ?? 'Unknown'}\nPriority: ${row['priority'] ?? 'Unknown'}',
                  ),
                  if (row['issues'] is List &&
                      (row['issues'] as List).isNotEmpty)
                    Text(
                      'Cannot schedule: ${(row['issues'] as List).join(', ')}',
                    ),
                  if (row['error'] != null)
                    Text('Unavailable: ${row['error']}'),
                  FilledButton(
                    onPressed: _busy || row['canCreateReminder'] != true
                        ? null
                        : () => _run(() async {
                            final result = await _bridge.schedule(
                              row['id'] as String,
                              _time.hour,
                              _time.minute,
                            );
                            if (mounted) setState(() => _result = result);
                          }),
                    child: const Text('Create local reminder'),
                  ),
                  ExpansionTile(
                    title: const Text('Structured output / diagnostics'),
                    children: [
                      SelectableText(
                        const JsonEncoder.withIndent('  ').convert(row),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ),
      ],
    ),
  );
}
