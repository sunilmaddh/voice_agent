import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:voice_assistant/native_bridge/assistant_bridge.dart';
import 'package:voice_assistant/native_bridge/assistant_bridge_provider.dart';
import 'package:voice_assistant/native_bridge/contracts/models.dart';
import 'package:voice_assistant/presentation/assistant_controller.dart';
import 'bridge_test.dart' show foundationResponse;
import 'package:voice_assistant/presentation/speech_settings.dart';

class PendingBridge implements AssistantBridge {
  final completion = Completer<AssistantResponse>();
  final eventController = StreamController<DiagnosticEvent>.broadcast();
  int calls = 0;
  String? requestId;
  @override
  Stream<DiagnosticEvent> get events => eventController.stream;
  @override
  Future<AssistantResponse> startVoiceInteraction(VoiceRequest request) {
    calls++;
    requestId = request.requestId;
    return completion.future;
  }
}

ProviderContainer containerFor(PendingBridge bridge) => ProviderContainer(
  overrides: [assistantBridgeProvider.overrideWithValue(bridge)],
);

class SpeechPendingBridge extends PendingBridge implements SpeechBridge {
  final cancellations = <String>[];
  @override
  Future<void> cancel(String requestId) async {
    cancellations.add(requestId);
  }

  @override
  Future<Map<String, Object?>> speechCapabilities() async => {};
  @override
  Future<AssistantResponse> speak(VoiceRequest request) =>
      startVoiceInteraction(request);
}

void main() {
  test(
    'partial text never becomes a response; cancellation and failed benchmark are retained',
    () async {
      final bridge = SpeechPendingBridge();
      final container = containerFor(bridge);
      container.listen(assistantControllerProvider, (_, _) {});
      container.read(speechSettingsProvider.notifier).update(benchmark: true);
      final controller = container.read(assistantControllerProvider.notifier);
      final pending = controller.start();
      bridge.eventController.add(
        DiagnosticEvent(
          requestId: bridge.requestId!,
          code: 'STT_PARTIAL',
          level: 'INFO',
          timestampMs: 1,
          transcript: 'open',
        ),
      );
      await Future<void>.delayed(Duration.zero);
      expect(
        container.read(assistantControllerProvider).liveTranscript,
        'open',
      );
      expect(container.read(assistantControllerProvider).response, isNull);
      await controller.cancel();
      expect(bridge.cancellations, [bridge.requestId]);
      bridge.completion.completeError(
        const BridgeException('CANCELLED', 'Voice interaction cancelled.'),
      );
      await pending;
      final trial = container.read(assistantControllerProvider).trials.single;
      expect(trial.outcome, 'CANCELLED');
      expect(trial.toMap()['wordErrorRate'], isNull);
      container.dispose();
      await bridge.eventController.close();
    },
  );
  test(
    'duplicate presses are suppressed and completion after disposal is safe',
    () async {
      final bridge = PendingBridge();
      final container = containerFor(bridge);
      container.listen(assistantControllerProvider, (_, _) {});
      final controller = container.read(assistantControllerProvider.notifier);
      final pending = controller.start();
      await controller.start();
      expect(bridge.calls, 1);
      expect(container.read(assistantControllerProvider).busy, isTrue);
      container.dispose();
      expect(bridge.eventController.hasListener, isFalse);
      bridge.completion.complete(
        AssistantResponse.fromMap(foundationResponse(bridge.requestId!)),
      );
      await pending;
      await bridge.eventController.close();
    },
  );

  test(
    'event failures are recoverable and do not leak error details',
    () async {
      final bridge = PendingBridge();
      final container = containerFor(bridge);
      container.listen(assistantControllerProvider, (_, _) {});
      bridge.eventController.addError(Exception('access_token=private'));
      await Future<void>.delayed(Duration.zero);
      final warning = container.read(assistantControllerProvider).eventWarning;
      expect(warning, isNotNull);
      expect(warning, isNot(contains('private')));
      container.dispose();
      await bridge.eventController.close();
    },
  );

  test(
    'state snapshots stay immutable and auto-dispose releases subscription',
    () async {
      final bridge = PendingBridge();
      final container = containerFor(bridge);
      final listener = container.listen(assistantControllerProvider, (_, _) {});
      final before = container.read(assistantControllerProvider);
      container.read(assistantControllerProvider.notifier).setDebugMode(true);
      expect(before.debugMode, isFalse);
      expect(container.read(assistantControllerProvider).debugMode, isTrue);
      expect(() => before.events.clear(), throwsUnsupportedError);
      listener.close();
      await container.pump();
      expect(bridge.eventController.hasListener, isFalse);
      container.dispose();
      await bridge.eventController.close();
    },
  );

  test('invalidated provider ignores old pending completion', () async {
    final bridge = PendingBridge();
    final container = containerFor(bridge);
    container.listen(assistantControllerProvider, (_, _) {});
    final pending = container
        .read(assistantControllerProvider.notifier)
        .start();
    container.invalidate(assistantControllerProvider);
    await container.pump();
    bridge.completion.complete(
      AssistantResponse.fromMap(foundationResponse(bridge.requestId!)),
    );
    await pending;
    expect(container.read(assistantControllerProvider).response, isNull);
    expect(container.read(assistantControllerProvider).busy, isFalse);
    container.dispose();
    await bridge.eventController.close();
  });
}
