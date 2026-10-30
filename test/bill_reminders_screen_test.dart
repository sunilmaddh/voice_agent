import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:voice_assistant/presentation/bill_reminders_screen.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('voice_assistant/bills/v1');
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null),
  );

  testWidgets(
    'sample extraction never schedules until requested and displays permission failure',
    (tester) async {
      final calls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            calls.add(call);
            if (call.method == 'samples') {
              return [
                {
                  'id': 'test-only',
                  'source': 'SYNTHETIC_SAMPLE',
                  'category': 'Electricity Bill',
                  'merchant': null,
                  'amount': 2450,
                  'currency': 'INR',
                  'due_date': '2026-10-05',
                  'priority': 'HIGH',
                  'issues': [],
                  'canCreateReminder': true,
                },
              ];
            }
            if (call.method == 'schedule') {
              return {
                'status': 'unsupported',
                'code': 'NOTIFICATION_PERMISSION_REQUIRED',
                'message': 'Enable reminder notifications.',
              };
            }
            return null;
          });
      await tester.pumpWidget(const MaterialApp(home: BillRemindersScreen()));
      await tester.tap(find.text('Validate 3 samples'));
      await tester.pumpAndSettle();
      expect(calls.where((c) => c.method == 'schedule'), isEmpty);
      await tester.scrollUntilVisible(
        find.text('Create local reminder'),
        250,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.textContaining('Merchant: Unknown'), findsOneWidget);
      await tester.ensureVisible(find.text('Create local reminder'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Create local reminder'));
      await tester.pumpAndSettle();
      expect(calls.singleWhere((c) => c.method == 'schedule').arguments, {
        'id': 'test-only',
        'hour': 9,
        'minute': 0,
      });
      await tester.scrollUntilVisible(
        find.text('Enable reminder notifications.'),
        -200,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.text('Enable reminder notifications.'), findsOneWidget);
      expect(find.textContaining('REMINDER_SCHEDULED_INEXACT'), findsNothing);
    },
  );

  testWidgets('ambiguous output disables reminder creation', (tester) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          channel,
          (call) async => call.method == 'samples'
              ? [
                  {
                    'category': 'Electricity Bill',
                    'source': 'SYNTHETIC_SAMPLE',
                    'issues': ['AMBIGUOUS_OR_INVALID_AMOUNT'],
                    'canCreateReminder': false,
                  },
                ]
              : null,
        );
    await tester.pumpWidget(const MaterialApp(home: BillRemindersScreen()));
    await tester.tap(find.text('Validate 3 samples'));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
      find.text('Create local reminder'),
      250,
      scrollable: find.byType(Scrollable).first,
    );
    final button = tester.widget<FilledButton>(
      find.widgetWithText(FilledButton, 'Create local reminder'),
    );
    expect(button.onPressed, isNull);
  });
}
