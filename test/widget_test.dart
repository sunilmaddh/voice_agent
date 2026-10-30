import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:voice_assistant/app/assistant_app.dart';
import 'package:voice_assistant/native_bridge/assistant_bridge.dart';
import 'package:voice_assistant/native_bridge/assistant_bridge_provider.dart';
import 'package:voice_assistant/native_bridge/contracts/models.dart';
import 'package:voice_assistant/native_bridge/contracts/speech_options.dart';
import 'package:voice_assistant/presentation/speech_settings.dart';

class UnavailableBridge implements AssistantBridge {
  @override
  Stream<DiagnosticEvent> get events => const Stream.empty();
  @override
  Future<AssistantResponse> startVoiceInteraction(VoiceRequest request) async =>
      throw const BridgeException('NATIVE_UNAVAILABLE', 'Android unavailable.');
}

class OfflineUnavailableBridge implements AssistantBridge {
  OfflineUnavailableBridge([this.code = 'OFFLINE_ENGLISH_UNAVAILABLE']);
  final String code;
  final requests = <VoiceRequest>[];
  @override
  Stream<DiagnosticEvent> get events => const Stream.empty();
  @override
  Future<AssistantResponse> startVoiceInteraction(VoiceRequest request) async {
    requests.add(request);
    return AssistantResponse(
      requestId: request.requestId,
      input: null,
      stt: null,
      intent: null,
      routingTier: RoutingTier.notRun,
      selectedModel: null,
      structuredOutput: null,
      action: null,
      result: ActionResult(
        status: ActionStatus.failed,
        code: code,
        message: 'Unavailable',
      ),
      response: '',
      executionMode: ExecutionMode.notRun,
      metrics: [],
    );
  }
}

void main() {
  testWidgets(
    'disconnected service offers recovery without enabling network silently',
    (tester) async {
      final bridge = OfflineUnavailableBridge('STT_SERVER_DISCONNECTED');
      await tester.pumpWidget(
        ProviderScope(
          overrides: [assistantBridgeProvider.overrideWithValue(bridge)],
          child: const AssistantApp(),
        ),
      );
      await tester.tap(find.byTooltip('Start Voice Interaction'));
      await tester.pumpAndSettle();
      expect(
        find.textContaining('The Android speech service disconnected'),
        findsOneWidget,
      );
      expect(find.text('Use system recognition and retry'), findsOneWidget);
      await tester.ensureVisible(find.text('Retry speech'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Retry speech'));
      await tester.pumpAndSettle();
      expect(bridge.requests.length, 2);
      expect(bridge.requests.last.options!.allowSystemRecognition, isFalse);
    },
  );
  testWidgets(
    'offline failure offers explicit system recognition opt-in and retries',
    (tester) async {
      final bridge = OfflineUnavailableBridge();
      await tester.pumpWidget(
        ProviderScope(
          overrides: [assistantBridgeProvider.overrideWithValue(bridge)],
          child: const AssistantApp(),
        ),
      );
      await tester.tap(find.byTooltip('Start Voice Interaction'));
      await tester.pumpAndSettle();
      expect(bridge.requests.single.options!.allowSystemRecognition, isFalse);
      final recovery = find.text('Use system recognition and retry');
      await tester.ensureVisible(recovery);
      await tester.pumpAndSettle();
      expect(find.textContaining('may send audio online'), findsOneWidget);
      await tester.tap(recovery);
      await tester.pumpAndSettle();
      expect(bridge.requests.length, 2);
      expect(bridge.requests.last.options!.allowSystemRecognition, isTrue);
      expect(bridge.requests.last.options!.preferSystemRecognition, isTrue);
      expect(bridge.requests.last.options!.offlineTtsOnly, isTrue);
      expect(tester.takeException(), isNull);
    },
  );
  for (final code in [
    'STT_LANGUAGE_NOT_INSTALLED',
    'STT_LANGUAGE_UNSUPPORTED',
  ]) {
    testWidgets('system language failure $code is not labelled offline', (
      tester,
    ) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            assistantBridgeProvider.overrideWithValue(
              OfflineUnavailableBridge(code),
            ),
            initialSpeechSettingsProvider.overrideWithValue(
              const SpeechSettings(
                options: SpeechOptions(
                  allowSystemRecognition: true,
                  preferSystemRecognition: true,
                ),
              ),
            ),
          ],
          child: const AssistantApp(),
        ),
      );
      await tester.tap(find.byTooltip('Start Voice Interaction'));
      await tester.pumpAndSettle();
      expect(find.text('Error: $code'), findsOneWidget);
      expect(
        find.textContaining('System recognition is already selected'),
        findsOneWidget,
      );
      expect(
        find.textContaining(
          'Offline English speech recognition is not available',
        ),
        findsNothing,
      );
      expect(find.text('Use system recognition and retry'), findsNothing);
      expect(tester.takeException(), isNull);
    });
  }
  testWidgets(
    'foundation shows fields and recoverable errors without fake results',
    (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            assistantBridgeProvider.overrideWithValue(UnavailableBridge()),
          ],
          child: const AssistantApp(),
        ),
      );
      expect(find.text('Aiko'), findsOneWidget);
      expect(find.text('Your AI Assistant'), findsOneWidget);
      expect(find.text('Tap to speak'), findsOneWidget);
      await tester.tap(find.byTooltip('Start Voice Interaction'));
      await tester.pumpAndSettle();
      expect(
        find.text('NATIVE_UNAVAILABLE: Android unavailable.'),
        findsOneWidget,
      );
      await tester.tap(find.text('Settings'));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(find.text('Technical dashboard'), 250);
      await Scrollable.ensureVisible(
        tester.element(find.text('Technical dashboard')),
        alignment: .4,
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Technical dashboard'));
      await tester.pumpAndSettle();
      expect(find.text('Start Voice Interaction'), findsOneWidget);
      expect(find.textContaining('English voice'), findsOneWidget);
      expect(find.text('STT Transcript'), findsOneWidget);
      await tester.tap(find.text('Start Voice Interaction'));
      await tester.pumpAndSettle();
      expect(
        find.text('NATIVE_UNAVAILABLE: Android unavailable.'),
        findsOneWidget,
      );
      expect(
        tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
        isNotNull,
      );
      final list = find
          .descendant(
            of: find.byType(ListView),
            matching: find.byType(Scrollable),
          )
          .first;
      await tester.scrollUntilVisible(
        find.byType(SwitchListTile),
        200,
        scrollable: list,
      );
      await tester.ensureVisible(find.byType(Switch));
      await tester.pumpAndSettle();
      await tester.tap(find.byType(Switch));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.text('LOCAL/CLOUD execution'),
        300,
        scrollable: list,
      );
      expect(find.text('NOT RUN'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'compact home shortcuts disclose availability and history has no fake entries',
    (tester) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            assistantBridgeProvider.overrideWithValue(UnavailableBridge()),
          ],
          child: const AssistantApp(),
        ),
      );
      await tester.ensureVisible(find.text('Call'));
      await tester.tap(find.text('Call'));
      await tester.pumpAndSettle();
      expect(
        find.textContaining('exact saved contact name'),
        findsOneWidget,
      );
      await tester.tap(find.text('Got it'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('History'));
      await tester.pumpAndSettle();
      expect(find.text('No conversations yet'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
}
