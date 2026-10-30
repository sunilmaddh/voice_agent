import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:voice_assistant/native_bridge/assistant_bridge.dart';
import 'package:voice_assistant/native_bridge/assistant_bridge_provider.dart';
import 'package:voice_assistant/native_bridge/contracts/models.dart';
import 'package:voice_assistant/presentation/assistant_controller.dart';
import 'controller_test.dart' show PendingBridge;
import 'bridge_test.dart' show foundationResponse;

class WakeBridge extends PendingBridge implements WakeWordBridge {
  final wakes = StreamController<void>.broadcast();
  @override
  Stream<void> get wakeDetections => wakes.stream;
  @override
  Future<Map<String, Object?>> wakeWordOperation(String method) async => {};
}

void main() {
  test(
    'wake hands off once to existing pipeline and duplicate wakes do not overlap',
    () async {
      final bridge = WakeBridge();
      final container = ProviderContainer(
        overrides: [assistantBridgeProvider.overrideWithValue(bridge)],
      );
      container.listen(assistantControllerProvider, (_, _) {});
      bridge.wakes.add(null);
      await Future<void>.delayed(Duration.zero);
      expect(bridge.calls, 1);
      bridge.wakes.add(null);
      await Future<void>.delayed(Duration.zero);
      expect(bridge.calls, 1);
      bridge.completion.complete(
        AssistantResponse.fromMap(foundationResponse(bridge.requestId!)),
      );
      await Future<void>.delayed(Duration.zero);
      container.dispose();
      bridge.wakes.add(null);
      await Future<void>.delayed(Duration.zero);
      expect(bridge.calls, 1);
      await bridge.wakes.close();
      await bridge.eventController.close();
    },
  );
}
