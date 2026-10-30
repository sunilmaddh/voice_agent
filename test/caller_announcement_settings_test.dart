import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:voice_assistant/presentation/caller_announcement_settings.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('voice_assistant/methods/v1');
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null),
  );
  testWidgets(
    'caller announcement saves explicit preferences and does not show failed saves as enabled',
    (tester) async {
      Map<String, Object?> saved = {
        'enabled': false,
        'allowLocked': false,
        'defaultPhone': true,
      };
      var failSave = false;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            if (call.method == 'callerAnnouncementSet') {
              if (failSave) {
                throw PlatformException(code: 'CALLER_SETTING_FAILED');
              }
              saved = {
                ...saved,
                ...Map<String, Object?>.from(call.arguments as Map),
              };
            }
            return saved;
          });
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(child: CallerAnnouncementSettings()),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final enabled = find.widgetWithText(
        SwitchListTile,
        'Announce incoming caller',
      );
      final locked = find.widgetWithText(
        SwitchListTile,
        'Announce while phone is locked',
      );
      expect(tester.widget<SwitchListTile>(enabled).value, false);
      await tester.tap(enabled);
      await tester.pumpAndSettle();
      expect(saved['enabled'], true);
      expect(saved['allowLocked'], false);
      failSave = true;
      await tester.tap(locked);
      await tester.pumpAndSettle();
      expect(tester.widget<SwitchListTile>(locked).value, false);
      expect(find.text('CALLER_SETTING_FAILED'), findsOneWidget);
    },
  );
  testWidgets(
    'voice call opt-in is saved separately and failed changes stay off',
    (tester) async {
      Map<String, Object?> saved = {
        'enabled': true,
        'allowLocked': true,
        'voiceControl': false,
        'defaultPhone': true,
        'replyMessage': 'I am busy. I will call you later.',
        'microphoneGranted': true,
        'smsGranted': true,
        'phoneStateGranted': true,
      };
      var failSave = true;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            if (call.method == 'callerAnnouncementSet') {
              if (failSave) {
                throw PlatformException(code: 'CALLER_SETTING_FAILED');
              }
              saved = {
                ...saved,
                ...Map<String, Object?>.from(call.arguments as Map),
              };
            }
            return saved;
          });
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(child: CallerAnnouncementSettings()),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final voice = find.widgetWithText(
        SwitchListTile,
        'Ask to accept or cut the call',
      );
      await tester.ensureVisible(voice);
      await tester.tap(voice);
      await tester.pumpAndSettle();
      expect(tester.widget<SwitchListTile>(voice).value, false);
      failSave = false;
      await tester.ensureVisible(voice);
      await tester.tap(voice);
      await tester.pumpAndSettle();
      expect(saved['voiceControl'], true);
      expect(saved['enabled'], true);
      expect(saved['allowLocked'], true);
      expect(
        find.text('Saved SMS: I am busy. I will call you later.'),
        findsOneWidget,
      );
    },
  );
}
