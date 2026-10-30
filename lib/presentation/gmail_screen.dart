import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../native_bridge/assistant_bridge.dart';
import '../native_bridge/assistant_bridge_provider.dart';
import '../native_bridge/contracts/models.dart';
import 'assistant_controller.dart';

class GmailScreen extends ConsumerStatefulWidget {
  const GmailScreen({super.key});
  @override
  ConsumerState<GmailScreen> createState() => _GmailScreenState();
}

class _GmailScreenState extends ConsumerState<GmailScreen>
    with WidgetsBindingObserver {
  Map<String, Object?> state = {};
  bool loading = false;
  bool active = true;
  String? error;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) operation('gmailStatus');
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState lifecycle) {
    if (!mounted) return;
    if (lifecycle == AppLifecycleState.resumed) {
      setState(() => active = true);
      if (!loading && !ref.read(assistantControllerProvider).busy) {
        operation('gmailStatus');
      }
    } else {
      // Hide cached email and recipient data while the application is not active.
      setState(() {
        state = {};
        active = false;
      });
    }
  }

  Future<void> operation(
    String method, [
    Map<String, Object?> arguments = const {},
  ]) async {
    if (loading) return;
    if (method == 'gmailConnect' || method == 'gmailDisconnect') {
      ref.read(assistantControllerProvider.notifier).clearResult();
    }
    setState(() {
      loading = true;
      // A lifecycle/status refresh must not erase a failed Google sign-in.
      if (method != 'gmailStatus') error = null;
      if (method == 'gmailConnect' || method == 'gmailDisconnect') state = {};
    });
    try {
      final bridge = ref.read(assistantBridgeProvider);
      if (bridge is! GmailBridge) {
        throw const BridgeException(
          'GMAIL_UNAVAILABLE',
          'Gmail requires Android.',
        );
      }
      final result = await (bridge as GmailBridge).gmailOperation(
        method,
        arguments,
      );
      if (mounted && active) setState(() => state = result);
    } on BridgeException catch (e) {
      if (mounted) setState(() => error = '${e.code}: ${e.message}');
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final session = ref.watch(assistantControllerProvider);
    final controller = ref.read(assistantControllerProvider.notifier);
    ref.listen(assistantControllerProvider, (previous, next) {
      if (previous?.busy == true && !next.busy && mounted && active) {
        operation('gmailStatus');
      }
    });
    if (!active) {
      return const Scaffold(
        body: Center(child: Text('Return to Aiko to view Gmail.')),
      );
    }
    final disabled = loading || session.busy;
    final selected = state['selected'] is Map
        ? objectMap(state['selected'])
        : null;
    final draft = state['draft'] is Map ? objectMap(state['draft']) : null;
    final result = state['operationResult'] ?? state['lastResult'];
    return Scaffold(
      appBar: AppBar(title: const Text('Gmail')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          const Text(
            'Gmail only. Read and send access uses Google OAuth and requires internet. Reply drafts are sent only after you confirm below.',
          ),
          Text(
            state['connected'] == true
                ? 'Connected: ${state['account'] ?? 'Verifying account'}'
                : state['connected'] == false
                ? 'Gmail not connected'
                : loading
                ? 'Checking Gmail connection...'
                : 'Gmail connection status unavailable',
          ),
          if (error != null) SelectableText(error!),
          if (state['connected'] == false)
            const Text(
              'Tap Connect / reconnect Gmail below to authorize this app. Being signed in to the Gmail app does not connect Aiko.',
            ),
          Wrap(
            spacing: 8,
            children: [
              FilledButton(
                onPressed: disabled ? null : () => operation('gmailConnect'),
                child: const Text('Connect / reconnect Gmail'),
              ),
              OutlinedButton(
                onPressed: disabled ? null : () => operation('gmailStatus'),
                child: const Text('Refresh'),
              ),
              TextButton(
                onPressed: disabled ? null : () => operation('gmailDisconnect'),
                child: const Text('Disconnect locally'),
              ),
            ],
          ),
          const Text(
            'Disconnect clears this session. To revoke Google consent, use your Google Account → Third-party connections. Initial OAuth setup is required for this POC build.',
          ),
          const Divider(),
          Wrap(
            spacing: 8,
            children: [
              for (final entry in {
                'LATEST': 'Latest email',
                'RECENT': 'Recent emails',
                'IMPORTANT': 'Important',
                'RELEVANT': 'Relevant',
              }.entries)
                OutlinedButton(
                  onPressed: disabled || state['connected'] != true
                      ? null
                      : () => operation('gmailRead', {'scope': entry.key}),
                  child: Text(entry.value),
                ),
            ],
          ),
          const Text(
            'Important = Gmail Important label. Relevant = unread Important inbox emails. Lists contain up to five messages. Select one before replying.',
          ),
          for (final item in (state['messages'] as List? ?? []))
            ListTile(
              title: Text(
                objectMap(item)['subject'] as String? ?? '(No subject)',
              ),
              subtitle: Text(objectMap(item)['sender'] as String? ?? ''),
              selected: objectMap(item)['id'] == selected?['id'],
              onTap: disabled
                  ? null
                  : () =>
                        operation('gmailSelect', {'id': objectMap(item)['id']}),
            ),
          if (selected != null) ...[
            Text('Selected: ${selected['subject']}'),
            SelectableText(selected['body'] as String? ?? ''),
            if (selected['truncated'] == true)
              const Text('Content shortened. Open Gmail for the full message.'),
            OutlinedButton(
              onPressed: disabled
                  ? null
                  : () => operation('gmailRead', {'scope': 'SUMMARY'}),
              child: const Text('Extractive summary'),
            ),
          ],
          const Divider(),
          FilledButton.icon(
            onPressed: loading
                ? null
                : session.busy
                ? controller.cancel
                : controller.start,
            icon: Icon(session.busy ? Icons.stop : Icons.mic),
            label: Text(session.busy ? 'Cancel voice' : 'Speak Gmail command'),
          ),
          const Text(
            'Say “Read my latest email”, “Read selected email”, “Summarize email”, or “Reply email that 4 PM works for me”. Summaries select original sentences locally; no general AI model is used.',
          ),
          if (session.liveTranscript != null) Text(session.liveTranscript!),
          if (session.response != null) Text(session.response!.response),
          if (draft != null)
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('To: ${draft['to']}'),
                    Text('Subject: ${draft['subject']}'),
                    SelectableText(draft['body'] as String? ?? ''),
                    const Text(
                      'Check the recipient and dictated text. Confirmation expires after five minutes.',
                    ),
                    FilledButton(
                      onPressed: disabled
                          ? null
                          : () => operation('gmailConfirmReply', {
                              'draftId': draft['id'],
                            }),
                      child: const Text('Confirm send'),
                    ),
                    TextButton(
                      onPressed: disabled
                          ? null
                          : () => operation('gmailCancelReply'),
                      child: const Text('Discard draft'),
                    ),
                  ],
                ),
              ),
            ),
          if (result is Map)
            Text(objectMap(result)['message'] as String? ?? ''),
          if (state['sendLatencyMs'] != null)
            Text(
              'Gmail API send latency: ${state['sendLatencyMs']} ms · CLOUD_GMAIL_API',
            ),
          if (loading) const LinearProgressIndicator(),
        ],
      ),
    );
  }
}
