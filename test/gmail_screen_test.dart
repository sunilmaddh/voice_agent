import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:voice_assistant/native_bridge/assistant_bridge.dart';
import 'package:voice_assistant/native_bridge/assistant_bridge_provider.dart';
import 'package:voice_assistant/native_bridge/contracts/models.dart';
import 'package:voice_assistant/presentation/gmail_screen.dart';

class GmailTestBridge implements AssistantBridge, GmailBridge {
  final calls = <String>[];
  bool sent = false;
  bool failStatus = false;
  bool failConnect = false;
  @override
  Stream<DiagnosticEvent> get events => const Stream.empty();
  @override
  Future<AssistantResponse> startVoiceInteraction(VoiceRequest request) =>
      throw UnimplementedError();
  @override
  Future<Map<String, Object?>> gmailOperation(
    String method, [
    Map<String, Object?> arguments = const {},
  ]) async {
    calls.add(method);
    if (failConnect && method == 'gmailConnect') {
      throw const BridgeException(
        'GMAIL_AUTH_FAILED',
        'Google authorization failed.',
      );
    }
    if (failStatus) {
      throw const BridgeException(
        'VOICE_BUSY',
        'Finish the current operation.',
      );
    }
    if (method == 'gmailConfirmReply') {
      expect(arguments, {'draftId': 'reviewed-draft'});
      sent = true;
    }
    return {
      'connected': true,
      'account': 'me@example.com',
      'messages': <Object?>[],
      'draft': sent
          ? null
          : {
              'id': 'reviewed-draft',
              'to': 'rahul@example.com',
              'subject': 'Meeting',
              'body': '4 PM works for me.',
            },
    };
  }
}

void main() {
  testWidgets('Refresh preserves a visible Google connection failure', (
    tester,
  ) async {
    final bridge = GmailTestBridge()..failConnect = true;
    await tester.pumpWidget(
      ProviderScope(
        overrides: [assistantBridgeProvider.overrideWithValue(bridge)],
        child: const MaterialApp(home: GmailScreen()),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Connect / reconnect Gmail'));
    await tester.pumpAndSettle();
    expect(
      find.text('GMAIL_AUTH_FAILED: Google authorization failed.'),
      findsOneWidget,
    );
    await tester.tap(find.text('Refresh'));
    await tester.pumpAndSettle();
    expect(
      find.text('GMAIL_AUTH_FAILED: Google authorization failed.'),
      findsOneWidget,
    );
  });
  testWidgets('Failed status check is not reported as disconnected', (
    tester,
  ) async {
    final bridge = GmailTestBridge()..failStatus = true;
    await tester.pumpWidget(
      ProviderScope(
        overrides: [assistantBridgeProvider.overrideWithValue(bridge)],
        child: const MaterialApp(home: GmailScreen()),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('Gmail not connected'), findsNothing);
    expect(find.text('Gmail connection status unavailable'), findsOneWidget);
    expect(
      find.text('VOICE_BUSY: Finish the current operation.'),
      findsOneWidget,
    );
  });
  testWidgets(
    'Gmail shows recipient and text and sends only after explicit confirmation',
    (tester) async {
      final bridge = GmailTestBridge();
      await tester.pumpWidget(
        ProviderScope(
          overrides: [assistantBridgeProvider.overrideWithValue(bridge)],
          child: const MaterialApp(home: GmailScreen()),
        ),
      );
      await tester.pumpAndSettle();
      expect(bridge.calls, ['gmailStatus']);
      await tester.scrollUntilVisible(find.text('Confirm send'), 300);
      expect(find.text('To: rahul@example.com'), findsOneWidget);
      expect(find.text('4 PM works for me.'), findsOneWidget);
      expect(bridge.sent, false);
      await tester.tap(find.text('Confirm send'));
      await tester.pumpAndSettle();
      expect(bridge.sent, true);
      expect(find.text('Confirm send'), findsNothing);
    },
  );
}
