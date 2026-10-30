import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Only this explicit review button invokes the native single-use confirmation.
class WhatsAppReplyReview extends StatefulWidget {
  const WhatsAppReplyReview({super.key});
  @override
  State<WhatsAppReplyReview> createState() => _WhatsAppReplyReviewState();
}

class _WhatsAppReplyReviewState extends State<WhatsAppReplyReview> {
  static const channel = MethodChannel('voice_assistant/methods/v1');
  bool busy = false;
  String? outcome;
  Future<void> review() async {
    setState(() => busy = true);
    try {
      final draft = await channel.invokeMapMethod<String, Object?>(
        'whatsappReplyReview',
      );
      if (!mounted) return;
      if (draft?['token'] is! String) {
        setState(
          () => outcome =
              'No active reply draft. Say “Reply WhatsApp saying …” again.',
        );
        return;
      }
      final confirm = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Confirm WhatsApp reply'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Conversation: ${draft!['recipient']}'),
                const SizedBox(height: 12),
                SelectableText('${draft['body']}'),
                const SizedBox(height: 12),
                const Text(
                  'Verify the conversation, including whether it is a group. Send hands this reply to WhatsApp; delivery cannot be verified.',
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Send reply'),
            ),
          ],
        ),
      );
      if (confirm != true || !mounted) {
        await channel.invokeMethod<void>('whatsappReplyCancel');
        if (mounted) {
          setState(() => outcome = 'Reply cancelled. Nothing was sent.');
        }
      } else {
        final result = await channel.invokeMapMethod<String, Object?>(
          'whatsappReplyConfirm',
          {'token': draft!['token']},
        );
        if (mounted) {
          setState(() => outcome = '${result?['code']}: ${result?['message']}');
        }
      }
    } catch (_) {
      if (mounted) {
        setState(
          () => outcome =
              'Reply operation could not be verified. Check WhatsApp before retrying.',
        );
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Column(
    children: [
      if (outcome != null) Text(outcome!),
      OutlinedButton(
        onPressed: busy ? null : review,
        child: const Text('Review WhatsApp reply'),
      ),
    ],
  );
}
