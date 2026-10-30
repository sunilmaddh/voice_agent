import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:voice_assistant/native_bridge/assistant_bridge.dart';
import 'package:voice_assistant/native_bridge/contracts/models.dart';

Map<String, Object?> foundationResponse(String id) => {
  'schemaVersion': 1,
  'requestId': id,
  'input': null,
  'stt': null,
  'intent': null,
  'routingTier': 'notRun',
  'selectedModel': null,
  'structuredOutput': null,
  'action': null,
  'result': {
    'status': 'unsupported',
    'code': 'STT_NOT_IMPLEMENTED',
    'message': 'Unavailable',
  },
  'response': 'Unavailable',
  'executionMode': 'notRun',
  'metrics': [
    for (final stage in ['stt', 'slm', 'action', 'tts'])
      {'stage': stage, 'durationMs': null, 'status': 'not_run'},
    {'stage': 'total', 'durationMs': 1.5, 'status': 'measured'},
  ],
};

void main() {
  test(
    'Gmail preserves numeric Google status without exposing exception data',
    () async {
      TestWidgetsFlutterBinding.ensureInitialized();
      const channel = MethodChannel('voice_assistant/methods/v1');
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
      messenger.setMockMethodCallHandler(channel, (_) async {
        throw PlatformException(
          code: 'GMAIL_GOOGLE_STATUS_10',
          message: 'Bearer secret-token',
        );
      });
      await expectLater(
        PlatformAssistantBridge().gmailOperation('gmailConnect'),
        throwsA(
          isA<BridgeException>()
              .having((e) => e.code, 'status', 'GMAIL_GOOGLE_STATUS_10')
              .having(
                (e) => e.message,
                'safe message',
                isNot(contains('secret-token')),
              ),
        ),
      );
    },
  );
  test('action evidence preserves handoff restrictions across the bridge', () {
    final result = ActionResult.fromMap({
      'status': 'requiresConfirmation',
      'code': 'SMS_COMPOSER_OPENED',
      'message': 'Review and send.',
      'evidence': {
        'success': false,
        'actionType': 'SEND_MESSAGE',
        'parameters': {'recipientName': 'Rahul'},
        'androidMechanism': 'ACTION_SENDTO smsto + sms_body',
        'userInteractionRequired': true,
        'unlockRequired': true,
        'outcome': 'HANDOFF_ONLY',
        'restriction': 'Not sent',
        'error': null,
      },
    });
    expect(result.evidence['success'], false);
    expect(result.evidence['outcome'], 'HANDOFF_ONLY');
    expect(result.evidence['parameters'], {'recipientName': 'Rahul'});
  });
  test(
    'Gmail bridge allowlist rejects arbitrary methods and hides native error details',
    () async {
      TestWidgetsFlutterBinding.ensureInitialized();
      const channel = MethodChannel('voice_assistant/methods/v1');
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
      messenger.setMockMethodCallHandler(channel, (call) async {
        throw PlatformException(
          code: 'secret-token',
          message: 'Bearer secret-token',
        );
      });
      final bridge = PlatformAssistantBridge();
      await expectLater(
        bridge.gmailOperation('executeArbitraryText'),
        throwsA(isA<BridgeException>()),
      );
      await expectLater(
        bridge.gmailOperation('gmailConnect'),
        throwsA(
          isA<BridgeException>().having(
            (e) => '${e.code} ${e.message}',
            'safe error',
            isNot(contains('secret-token')),
          ),
        ),
      );
    },
  );
  test(
    'calendar bridge keeps denied access visible and requests only on demand',
    () async {
      TestWidgetsFlutterBinding.ensureInitialized();
      const channel = MethodChannel('voice_assistant/methods/v1');
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      final calls = <String>[];
      addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call.method);
        return {'calendarRead': false, 'notifications': true};
      });
      final bridge = PlatformAssistantBridge();
      expect((await bridge.calendarPermissions())['calendarRead'], false);
      expect(calls, ['calendarPermissions']);
      await bridge.calendarPermissions(request: true);
      expect(calls.last, 'requestCalendarPermissions');
    },
  );
  test(
    'command bridge preserves pending measurements and sanitizes native errors',
    () async {
      TestWidgetsFlutterBinding.ensureInitialized();
      const channel = MethodChannel('voice_assistant/methods/v1');
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'benchmarkCommands') {
          return {
            'status': 'PENDING_DEVICE_EXECUTION',
            'intentAccuracy': null,
            'rows': <Object?>[],
          };
        }
        throw PlatformException(
          code: 'SENSITIVE_RUNTIME_FAILURE',
          message: 'secret OAuth token',
        );
      });
      final bridge = PlatformAssistantBridge();
      final report = await bridge.commandOperation('benchmarkCommands');
      expect(report['status'], 'PENDING_DEVICE_EXECUTION');
      expect(report['intentAccuracy'], isNull);
      await expectLater(
        bridge.commandOperation('configureCommandModel'),
        throwsA(
          isA<BridgeException>().having(
            (e) => e.message,
            'safe error',
            isNot(contains('token')),
          ),
        ),
      );
      await expectLater(
        bridge.commandOperation('executeAction'),
        throwsA(isA<BridgeException>()),
      );
    },
  );
  test(
    'canonical command names and routing decisions cross the bridge without executing actions',
    () {
      final payload = foundationResponse('req-1-1')
        ..['intent'] = {
          'kind': 'OPEN_MAPS',
          'parameters': <Object?>[],
          'confidence': 1.0,
        }
        ..['routingTier'] = 'deterministic'
        ..['executionMode'] = 'local'
        ..['routing'] = {
          'reason': 'Explicit grammar',
          'modelRequired': false,
          'actionExecuted': false,
          'proposedAction': {'name': 'OPEN_MAPS'},
        };
      final response = AssistantResponse.fromMap(payload);
      expect(response.intent!.kind, IntentKind.openMaps);
      expect(response.intent!.kind.wireName, 'OPEN_MAPS');
      expect(response.action, isNull);
      expect(response.routing['actionExecuted'], false);
      expect(response.selectedModel, isNull);
      expect(IntentKind.values.map((kind) => kind.wireName).toSet().length, 24);
    },
  );
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('voice_assistant/methods/v1');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test(
    'versioned request round trip preserves unsupported and skipped stages',
    () async {
      messenger.setMockMethodCallHandler(channel, (call) async {
        expect(call.method, 'startVoiceInteraction');
        expect(call.arguments, {
          'schemaVersion': 1,
          'requestId': 'req-1-1',
          'input': null,
        });
        return foundationResponse('req-1-1');
      });
      final response = await PlatformAssistantBridge().startVoiceInteraction(
        const VoiceRequest(requestId: 'req-1-1'),
      );
      expect(response.result.status, ActionStatus.unsupported);
      expect(response.stt, isNull);
      expect(response.executionMode, ExecutionMode.notRun);
      expect(response.metrics.first.durationMs, isNull);
    },
  );
  test('rejects incompatible schema and mismatched request IDs', () async {
    for (final response in [
      {...foundationResponse('req-1-1'), 'schemaVersion': 2},
      foundationResponse('req-2-1'),
      {...foundationResponse('req-1-1'), 'routingTier': 'invented'},
    ]) {
      messenger.setMockMethodCallHandler(channel, (_) async => response);
      await expectLater(
        PlatformAssistantBridge().startVoiceInteraction(
          const VoiceRequest(requestId: 'req-1-1'),
        ),
        throwsA(
          isA<BridgeException>().having(
            (e) => e.code,
            'code',
            'INVALID_RESPONSE',
          ),
        ),
      );
    }
  });
  test('native exception details never propagate', () async {
    messenger.setMockMethodCallHandler(
      channel,
      (_) async => throw PlatformException(
        code: 'secret',
        message: 'Bearer private-token',
        details: {'access_token': 'secret'},
      ),
    );
    await expectLater(
      PlatformAssistantBridge().startVoiceInteraction(
        const VoiceRequest(requestId: 'req-1-1'),
      ),
      throwsA(
        isA<BridgeException>().having(
          (e) => e.message,
          'safe message',
          'The Android request could not be completed.',
        ),
      ),
    );
  });
  test('missing native plugin has a stable recoverable error', () async {
    await expectLater(
      PlatformAssistantBridge().startVoiceInteraction(
        const VoiceRequest(requestId: 'req-1-1'),
      ),
      throwsA(
        isA<BridgeException>().having(
          (e) => e.code,
          'code',
          'NATIVE_UNAVAILABLE',
        ),
      ),
    );
  });
  test(
    'parameter types, confidence and performance values reject malformed data',
    () {
      expect(
        () => IntentParameter(
          name: 'destination',
          type: ParameterType.text,
          value: 42,
        ),
        throwsFormatException,
      );
      expect(
        () => AssistantIntent(
          kind: IntentKind.navigate,
          parameters: [],
          confidence: double.nan,
        ),
        throwsFormatException,
      );
      expect(
        () => PerformanceMetric.fromMap({
          'stage': 'stt',
          'durationMs': 0,
          'status': 'not_run',
        }),
        throwsFormatException,
      );
    },
  );
}
