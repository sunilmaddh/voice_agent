import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:voice_assistant/presentation/instant_reply_setting.dart';

void main() {
  testWidgets(
    'instant replies default off, persist explicit toggle and restore',
    (tester) async {
      const channel = MethodChannel('voice_assistant/methods/v1');
      var saved = false;
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
        call,
      ) async {
        if (call.method == 'instantReplyWrite') {
          saved = (call.arguments as Map)['enabled'] as bool;
        }
        return saved;
      });
      addTearDown(
        () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          channel,
          null,
        ),
      );
      Widget app() =>
          const MaterialApp(home: Scaffold(body: InstantReplySetting()));
      await tester.pumpWidget(app());
      await tester.pumpAndSettle();
      expect(
        tester.widget<SwitchListTile>(find.byType(SwitchListTile)).value,
        false,
      );
      await tester.tap(find.byType(Switch));
      await tester.pumpAndSettle();
      expect(saved, true);
      await tester.pumpWidget(const SizedBox());
      await tester.pumpWidget(app());
      await tester.pumpAndSettle();
      expect(
        tester.widget<SwitchListTile>(find.byType(SwitchListTile)).value,
        true,
      );
      await tester.tap(find.byType(Switch));
      await tester.pumpAndSettle();
      expect(saved, false);
    },
  );
}
