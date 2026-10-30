import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:voice_assistant/presentation/whatsapp_reply_review.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('voice_assistant/methods/v1');
  for (final send in [false, true]) {
    testWidgets('WhatsApp review requires explicit confirmation: $send', (
      tester,
    ) async {
      final calls = <String>[];
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
        call,
      ) async {
        calls.add(call.method);
        if (call.method == 'whatsappReplyReview') {
          return {
            'token': 'review-token',
            'recipient': 'Rahul',
            'body': 'I will call later',
          };
        }
        if (call.method == 'whatsappReplyConfirm') {
          expect(call.arguments, {'token': 'review-token'});
          return {
            'code': 'WHATSAPP_REPLY_DISPATCHED',
            'message': 'Delivery not verified',
          };
        }
        return null;
      });
      addTearDown(
        () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          channel,
          null,
        ),
      );
      await tester.pumpWidget(
        const MaterialApp(home: Scaffold(body: WhatsAppReplyReview())),
      );
      await tester.tap(find.text('Review WhatsApp reply'));
      await tester.pumpAndSettle();
      expect(find.text('Conversation: Rahul'), findsOneWidget);
      expect(find.text('I will call later'), findsOneWidget);
      expect(calls, ['whatsappReplyReview']);
      await tester.tap(find.text(send ? 'Send reply' : 'Cancel'));
      await tester.pumpAndSettle();
      expect(calls.last, send ? 'whatsappReplyConfirm' : 'whatsappReplyCancel');
    });
  }
}
