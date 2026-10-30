import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'assistant_bridge.dart';

/// Override at the ProviderScope boundary for tests or another bridge adapter.
final assistantBridgeProvider = Provider<AssistantBridge>(
  (ref) => PlatformAssistantBridge(),
);
