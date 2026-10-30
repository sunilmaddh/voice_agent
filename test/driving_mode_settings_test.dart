import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:voice_assistant/presentation/driving_mode_settings.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('voice_assistant/methods/v1');
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null),
  );

  testWidgets(
    'automatic driving replies require opt-in and a successful save',
    (tester) async {
      var writes = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            if (call.method == 'drivingModeAutoReply') {
              writes++;
              throw PlatformException(code: 'DRIVING_SAVE_FAILED');
            }
            return {
              'enabled': false,
              'automaticReply': false,
              'defaultPhone': true,
            };
          });
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(child: DrivingModeSettings()),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final toggle = find.widgetWithText(
        SwitchListTile,
        'Automatic driving SMS and decline',
      );
      await tester.tap(toggle);
      await tester.pumpAndSettle();
      expect(writes, 0);
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      expect(writes, 0);
      await tester.tap(toggle);
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Enable'));
      await tester.pumpAndSettle();
      expect(writes, 1);
      expect(tester.widget<SwitchListTile>(toggle).value, false);
      expect(find.textContaining('DRIVING_SAVE_FAILED'), findsOneWidget);
    },
  );
  testWidgets(
    'dictation remains a draft until save and uses the saved personal reply',
    (tester) async {
      var saved = 'I am driving.';
      var writes = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            if (call.method == 'drivingReplyDictate') {
              return {'draft': 'Driving home. Call you at 6.'};
            }
            if (call.method == 'drivingModeMessage') {
              writes++;
              saved = (call.arguments as Map)['message'] as String;
            }
            return {
              'enabled': true,
              'automaticReply': false,
              'defaultPhone': true,
              'message': saved,
            };
          });
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(child: DrivingModeSettings()),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Dictate reply'));
      await tester.pumpAndSettle();
      expect(writes, 0);
      expect(saved, 'I am driving.');
      expect(
        find.text(
          'Unsaved draft. Automatic replies still use the saved message.',
        ),
        findsOneWidget,
      );
      await tester.tap(find.text('Save reply'));
      await tester.pumpAndSettle();
      expect(writes, 1);
      expect(saved, 'Driving home. Call you at 6.');
      final toggle = find.widgetWithText(
        SwitchListTile,
        'Automatic driving SMS and decline',
      );
      await tester.ensureVisible(toggle);
      await tester.tap(toggle);
      await tester.pumpAndSettle();
      expect(find.textContaining('Driving home. Call you at 6.'), findsWidgets);
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
    },
  );
}
