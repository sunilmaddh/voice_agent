import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:voice_assistant/presentation/system_assistant_settings.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('voice_assistant/methods/v1');
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null),
  );

  testWidgets('privacy remains off until native setting is saved', (
    tester,
  ) async {
    var enabled = false;
    var rejectSave = true;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'systemAssistantPrivacy') {
            if (rejectSave) throw PlatformException(code: 'SAVE_FAILED');
            enabled = (call.arguments as Map)['enabled'] == true;
          }
          return {
            'snapshot': {
              'lockedPersonalReads': enabled,
              'systemAssistantSelected': true,
            },
          };
        });
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(child: SystemAssistantSettings()),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(
      tester
          .widget<SwitchListTile>(
            find.widgetWithText(
              SwitchListTile,
              'Allow personal readouts while locked',
            ),
          )
          .value,
      false,
    );
    await tester.tap(
      find.widgetWithText(
        SwitchListTile,
        'Allow personal readouts while locked',
      ),
    );
    await tester.pumpAndSettle();
    expect(
      tester
          .widget<SwitchListTile>(
            find.widgetWithText(
              SwitchListTile,
              'Allow personal readouts while locked',
            ),
          )
          .value,
      false,
    );
    rejectSave = false;
    await tester.tap(
      find.widgetWithText(
        SwitchListTile,
        'Allow personal readouts while locked',
      ),
    );
    await tester.pumpAndSettle();
    expect(
      tester
          .widget<SwitchListTile>(
            find.widgetWithText(
              SwitchListTile,
              'Allow personal readouts while locked',
            ),
          )
          .value,
      true,
    );
    await tester.tap(
      find.widgetWithText(
        SwitchListTile,
        'Allow personal readouts while locked',
      ),
    );
    await tester.pumpAndSettle();
    expect(enabled, false);
  });

  testWidgets(
    'reopening settings reloads Android selection and saved privacy',
    (tester) async {
      var reads = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            expect(call.method, 'systemAssistantStatus');
            reads++;
            return {
              'snapshot': {
                'systemAssistantSelected': true,
                'lockedPersonalReads': true,
              },
            };
          });
      const screen = MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(child: SystemAssistantSettings()),
        ),
      );
      await tester.pumpWidget(screen);
      await tester.pumpAndSettle();
      expect(find.text('Selected: true'), findsOneWidget);
      expect(
        tester
            .widget<SwitchListTile>(
              find.widgetWithText(
                SwitchListTile,
                'Allow personal readouts while locked',
              ),
            )
            .value,
        true,
      );
      await tester.pumpWidget(const SizedBox());
      await tester.pumpWidget(screen);
      await tester.pumpAndSettle();
      expect(reads, 2);
      expect(find.text('Selected: true'), findsOneWidget);
      expect(
        tester
            .widget<SwitchListTile>(
              find.widgetWithText(
                SwitchListTile,
                'Allow personal readouts while locked',
              ),
            )
            .value,
        true,
      );
    },
  );
}
