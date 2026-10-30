import 'dart:async';
import 'package:flutter/services.dart';
import 'contracts/models.dart';

abstract interface class AssistantBridge {
  Stream<DiagnosticEvent> get events;
  Future<AssistantResponse> startVoiceInteraction(VoiceRequest request);
}

abstract interface class SpeechBridge {
  Future<Map<String, Object?>> speechCapabilities();
  Future<void> cancel(String requestId);
  Future<AssistantResponse> speak(VoiceRequest request);
}

abstract interface class CommandModelBridge {
  Future<Map<String, Object?>> commandOperation(
    String method, [
    Map<String, Object?> arguments,
  ]);
  Future<void> cancelCommandWork();
}

abstract interface class CalendarBridge {
  Future<Map<String, Object?>> calendarPermissions({bool request = false});
}

abstract interface class GmailBridge {
  Future<Map<String, Object?>> gmailOperation(
    String method, [
    Map<String, Object?> arguments,
  ]);
}

abstract interface class LockScreenBridge {
  Future<Map<String, Object?>> lockScreenOperation(String method);
}

abstract interface class WakeWordBridge {
  Stream<void> get wakeDetections;
  Future<Map<String, Object?>> wakeWordOperation(String method);
}

class BridgeException implements Exception {
  const BridgeException(this.code, this.message);
  final String code;
  final String message;
}

class PlatformAssistantBridge
    implements
        AssistantBridge,
        SpeechBridge,
        CommandModelBridge,
        CalendarBridge,
        GmailBridge,
        LockScreenBridge,
        WakeWordBridge {
  static const _wakeEvents = EventChannel('voice_assistant/wake_events/v1');
  @override
  Stream<void> get wakeDetections => _wakeEvents
      .receiveBroadcastStream()
      .where((event) => event is Map && event['event'] == 'WAKE_DETECTED')
      .map((_) {});
  @override
  Future<Map<String, Object?>> wakeWordOperation(String method) async {
    if (!const {
      'wakeWordStatus',
      'wakeWordStart',
      'wakeWordStop',
      'wakeWordPermissions',
    }.contains(method)) {
      throw const BridgeException(
        'INVALID_WAKE_REQUEST',
        'Unsupported wake-word operation.',
      );
    }
    try {
      final value = await _methods.invokeMethod<Object?>(method);
      return value == null ? const {} : objectMap(value);
    } on PlatformException catch (e) {
      throw BridgeException(
        e.code,
        'Keep Aiko unlocked, finish other operations, and grant microphone/notification permissions.',
      );
    }
  }

  static const _methods = MethodChannel('voice_assistant/methods/v1');
  static const _events = EventChannel('voice_assistant/events/v1');
  @override
  Future<Map<String, Object?>> lockScreenOperation(String method) async {
    if (!const {
      'lockScreenReport',
      'requestAssistantRole',
      'previewLockScreenAssistant',
    }.contains(method)) {
      throw const BridgeException(
        'INVALID_LOCK_SCREEN_REQUEST',
        'Unsupported operation.',
      );
    }
    try {
      return objectMap(await _methods.invokeMethod<Object?>(method));
    } on PlatformException {
      throw const BridgeException(
        'LOCK_SCREEN_UNAVAILABLE',
        'Keep Aiko unlocked and finish the current operation. Assistant settings may be unavailable on this device.',
      );
    }
  }

  @override
  Future<Map<String, Object?>> gmailOperation(
    String method, [
    Map<String, Object?> arguments = const {},
  ]) async {
    const allowed = {
      'gmailStatus',
      'gmailConnect',
      'gmailDisconnect',
      'gmailRead',
      'gmailSelect',
      'gmailConfirmReply',
      'gmailCancelReply',
    };
    if (!allowed.contains(method)) {
      throw const BridgeException(
        'INVALID_GMAIL_REQUEST',
        'Unsupported Gmail operation.',
      );
    }
    try {
      return objectMap(await _methods.invokeMethod<Object?>(method, arguments));
    } on PlatformException catch (e) {
      const messages = {
        'VOICE_BUSY': 'Finish the current voice or native operation first.',
        'GMAIL_LOCKED_OR_BACKGROUND':
            'Keep Aiko open and unlocked to use Gmail.',
        'GMAIL_AUTH_CANCELLED': 'Google authorization was cancelled.',
        'GMAIL_AUTH_INTERRUPTED':
            'The app closed the authorization session. Keep Aiko open and retry.',
        'GMAIL_AUTH_NO_RESULT':
            'Google closed authorization without returning a result. This does not confirm that you cancelled.',
        'GMAIL_AUTH_NOT_COMPLETED':
            'Google returned without completing authorization.',
        'GMAIL_AUTH_FAILED':
            'Google authorization failed. Configure the Android OAuth client and Gmail API, then retry.',
        'GMAIL_SCOPES_REQUIRED':
            'Gmail read and send access must be granted to connect.',
        'GMAIL_PROFILE_FAILED':
            'Could not verify the Gmail account. Check API setup and reconnect.',
      };
      if (RegExp(r'^GMAIL_GOOGLE_STATUS_-?[0-9]{1,10}$').hasMatch(e.code)) {
        throw BridgeException(
          e.code,
          'Google authorization failed with this status. Check the Android OAuth package/signing certificate, consent configuration and test-account access.',
        );
      }
      throw BridgeException(
        messages.containsKey(e.code) ? e.code : 'GMAIL_OPERATION_FAILED',
        messages[e.code] ??
            'Gmail operation failed. Check Gmail Sent before retrying a reply.',
      );
    } catch (_) {
      throw const BridgeException(
        'GMAIL_UNAVAILABLE',
        'Gmail integration requires the Android app and Google Play services.',
      );
    }
  }

  @override
  Future<Map<String, Object?>> calendarPermissions({
    bool request = false,
  }) async {
    try {
      return objectMap(
        await _methods.invokeMethod<Object?>(
          request ? 'requestCalendarPermissions' : 'calendarPermissions',
        ),
      );
    } on PlatformException catch (error) {
      throw BridgeException(
        error.code,
        'Keep Aiko open and finish the current operation, then retry.',
      );
    } catch (_) {
      throw const BridgeException(
        'CALENDAR_UNAVAILABLE',
        'Calendar integration requires the Android app.',
      );
    }
  }

  @override
  Future<void> cancelCommandWork() async {
    await _methods.invokeMethod<void>('cancelCommandWork');
  }

  @override
  Future<Map<String, Object?>> commandOperation(
    String method, [
    Map<String, Object?> arguments = const {},
  ]) async {
    const allowed = {
      'configureCommandModel',
      'unloadCommandModel',
      'commandModelStatus',
      'extractCommand',
      'diagnoseCommandModel',
      'benchmarkCommands',
    };
    if (!allowed.contains(method)) {
      throw const BridgeException(
        'INVALID_MODEL_REQUEST',
        'Unsupported model operation.',
      );
    }
    try {
      return objectMap(
        await _methods
            .invokeMethod<Object?>(method, arguments)
            .timeout(
              Duration(seconds: method == 'benchmarkCommands' ? 1800 : 120),
            ),
      );
    } on TimeoutException {
      try {
        await cancelCommandWork();
      } catch (_) {
        /* Lifecycle also cancels. */
      }
      throw const BridgeException(
        'MODEL_TIMEOUT',
        'Local model operation timed out.',
      );
    } on PlatformException catch (error) {
      const messages = {
        'MODEL_FILE_MISSING':
            'Stage the model file in the app model directory first.',
        'MODEL_CHECKSUM_MISMATCH':
            'The model file does not match the supplied SHA-256.',
        'INVALID_MODEL_REQUEST':
            'Check the filename, model ID, SHA-256 and quantization.',
        'MODEL_RUNTIME_UNSUPPORTED':
            'The runtime is incompatible with this Android device.',
        'VOICE_BUSY': 'Another native operation is running.',
        'APP_NOT_FOREGROUND':
            'Keep the Android app open during this operation.',
        'CANCELLED': 'Local model operation cancelled.',
      };
      throw BridgeException(
        messages.containsKey(error.code)
            ? error.code
            : 'MODEL_OPERATION_FAILED',
        messages[error.code] ??
            'Local model operation failed. Check model/runtime compatibility.',
      );
    } catch (_) {
      throw const BridgeException(
        'MODEL_UNAVAILABLE',
        'Local models require the Android app.',
      );
    }
  }

  @override
  late final Stream<DiagnosticEvent> events = _events
      .receiveBroadcastStream()
      .map((event) => DiagnosticEvent.fromMap(objectMap(event)));
  @override
  Future<AssistantResponse> startVoiceInteraction(VoiceRequest request) =>
      _request('startVoiceInteraction', request);
  @override
  Future<AssistantResponse> speak(VoiceRequest request) =>
      _request('speak', request);
  @override
  Future<void> cancel(String requestId) async {
    try {
      await _methods
          .invokeMethod<void>('cancelVoiceInteraction', {
            'requestId': requestId,
          })
          .timeout(const Duration(seconds: 5));
    } catch (_) {
      /* Native lifecycle and deadline provide independent cleanup. */
    }
  }

  @override
  Future<Map<String, Object?>> speechCapabilities() async {
    try {
      return objectMap(
        await _methods
            .invokeMethod<Object?>('speechCapabilities')
            .timeout(const Duration(seconds: 15)),
      );
    } catch (_) {
      throw const BridgeException(
        'CAPABILITIES_UNAVAILABLE',
        'Speech capabilities unavailable. Try again with the Android app open.',
      );
    }
  }

  Future<AssistantResponse> _request(
    String method,
    VoiceRequest request,
  ) async {
    try {
      final data = await _methods
          .invokeMethod<Object?>(method, request.toMap())
          .timeout(const Duration(minutes: 10));
      final response = AssistantResponse.fromMap(objectMap(data));
      if (response.requestId != request.requestId) {
        throw const FormatException('Request mismatch');
      }
      return response;
    } on MissingPluginException {
      throw const BridgeException(
        'NATIVE_UNAVAILABLE',
        'Native integration is available in the Android app only.',
      );
    } on TimeoutException {
      await cancel(request.requestId);
      throw const BridgeException(
        'NATIVE_TIMEOUT',
        'The native request timed out. No action was confirmed.',
      );
    } on PlatformException catch (error) {
      const safeErrors = {
        'WAKE_LISTENING_ACTIVE':
            'Hey Indo is using the microphone. Stop listening in English speech settings before using tap-to-speak.',
        'MICROPHONE_PERMISSION_DENIED':
            'Microphone permission is required. Enable it in Android app settings and retry.',
        'CANCELLED': 'Voice interaction cancelled.',
        'VOICE_BUSY': 'A voice request or capability check is already running.',
        'APP_NOT_FOREGROUND': 'Open the app to use speech.',
        'INVALID_REQUEST': 'Invalid speech settings or request.',
      };
      if (safeErrors.containsKey(error.code)) {
        throw BridgeException(error.code, safeErrors[error.code]!);
      }
      // Never propagate potentially sensitive exception messages/details.
      throw const BridgeException(
        'NATIVE_ERROR',
        'The Android request could not be completed.',
      );
    } on FormatException {
      throw const BridgeException(
        'INVALID_RESPONSE',
        'Android returned an invalid response.',
      );
    } on TypeError {
      throw const BridgeException(
        'INVALID_RESPONSE',
        'Android returned an invalid response.',
      );
    }
  }
}
