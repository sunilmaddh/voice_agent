import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:voice_assistant/presentation/document_ai_screen.dart';
import 'package:voice_assistant/native_bridge/document_bridge.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(DocumentBridge.channel, null),
  );
  testWidgets(
    'capture displays OCR and missing model does not fabricate an answer',
    (tester) async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(DocumentBridge.channel, (call) async {
            if (call.method == 'ask') {
              throw PlatformException(code: 'GENERAL_MODEL_NOT_CONFIGURED');
            }
            return {
              'text': call.method == 'capture'
                  ? 'Sample invoice total: INR 100'
                  : '',
              'answer': '',
              'diagnostics': {},
            };
          });
      await tester.pumpWidget(const MaterialApp(home: DocumentAiScreen()));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Capture document'));
      await tester.pumpAndSettle();
      expect(find.text('Sample invoice total: INR 100'), findsOneWidget);
      await tester.scrollUntilVisible(
        find.text('Ask local model'),
        200,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.tap(find.text('Ask local model'));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.textContaining('GENERAL_MODEL_NOT_CONFIGURED'),
        -200,
        scrollable: find.byType(Scrollable).first,
      );
      expect(
        find.textContaining('GENERAL_MODEL_NOT_CONFIGURED'),
        findsOneWidget,
      );
      expect(find.textContaining('Document operation failed'), findsOneWidget);
    },
  );
}
