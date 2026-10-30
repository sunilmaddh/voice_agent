import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../diagnostics/structured_logger.dart';
import '../native_bridge/assistant_bridge.dart';
import '../native_bridge/assistant_bridge_provider.dart';
import '../native_bridge/contracts/models.dart';
import 'assistant_state.dart';
import 'speech_settings.dart';
import '../benchmark/speech_benchmark.dart';

final assistantControllerProvider =
    NotifierProvider.autoDispose<AssistantController, AssistantState>(
      AssistantController.new,
    );

class _SessionLifetime {
  bool disposed = false;
  String? activeRequestId;
}

class AssistantController extends Notifier<AssistantState> {
  final _logger = const StructuredLogger();
  int _sequence = 0;
  late AssistantBridge _bridge;
  late _SessionLifetime _lifetime;

  @override
  AssistantState build() {
    _bridge = ref.watch(assistantBridgeProvider);
    final lifetime = _SessionLifetime();
    _lifetime = lifetime;
    final subscription = _bridge.events.listen(
      (event) {
        if (lifetime.disposed || event.requestId != lifetime.activeRequestId) {
          return;
        }
        final events = [...state.events, event];
        state = state.copyWith(
          liveTranscript: event.transcript,
          phase: event.code,
          events: events.length > 50
              ? events.sublist(events.length - 50)
              : events,
        );
      },
      onError: (Object _) {
        if (lifetime.disposed) return;
        state = state.copyWith(
          eventWarning:
              'Live diagnostics unavailable; request results remain available.',
        );
        _logger.record(
          LogCode.eventStreamFailed,
          lifetime.activeRequestId ?? 'redacted',
        );
      },
    );
    final wakeBridge = _bridge;
    final wakeSubscription = wakeBridge is WakeWordBridge
        ? (wakeBridge as WakeWordBridge).wakeDetections.listen(
            (_) {
              if (!lifetime.disposed && !state.busy) unawaited(start());
            },
            onError: (Object _) {
              /* Wake-word status screen exposes native errors. */
            },
          )
        : null;
    ref.onDispose(() {
      lifetime.disposed = true;
      final id = lifetime.activeRequestId;
      final bridge = _bridge;
      if (id != null && bridge is SpeechBridge) {
        unawaited((bridge as SpeechBridge).cancel(id));
      }
      unawaited(subscription.cancel());
      if (wakeSubscription != null) unawaited(wakeSubscription.cancel());
    });
    return AssistantState();
  }

  void setDebugMode(bool value) {
    if (!_lifetime.disposed) state = state.copyWith(debugMode: value);
  }

  Future<void> start() => _run(false);
  void clearResult() {
    if (state.busy) return;
    state = AssistantState(debugMode: state.debugMode, trials: state.trials);
  }

  Future<void> preview() => _run(true);
  void clearTrials() {
    state = state.copyWith(trials: []);
  }

  Future<void> cancel() async {
    final id = _lifetime.activeRequestId;
    final bridge = _bridge;
    if (id != null && bridge is SpeechBridge) {
      await (bridge as SpeechBridge).cancel(id);
    }
  }

  Future<void> _run(bool ttsOnly) async {
    final lifetime = _lifetime;
    if (lifetime.disposed || state.busy) return;
    final id = 'req-${DateTime.now().microsecondsSinceEpoch}-${++_sequence}';
    lifetime.activeRequestId = id;
    final settings = ref.read(speechSettingsProvider);
    AssistantResponse? completed;
    var outcome = 'FAILED';
    // A fresh snapshot clears prior response, error and event history.
    state = AssistantState(
      busy: true,
      debugMode: state.debugMode,
      eventWarning: state.eventWarning,
      trials: state.trials,
      phase: ttsOnly
          ? 'Preparing speech playback'
          : 'Preparing speech recognition',
    );
    _logger.record(LogCode.requestStarted, id);
    try {
      final request = VoiceRequest(
        requestId: id,
        options: settings.options,
        input: ttsOnly
            ? (settings.options.languageTag == 'kn-IN'
                  ? 'ನಮಸ್ಕಾರ, ನಾನು ನಿಮಗೆ ಸಹಾಯ ಮಾಡಲು ಸಿದ್ಧವಾಗಿದ್ದೇನೆ.'
                  : settings.options.languageTag == 'hi-IN'
                  ? 'नमस्ते, मैं आपकी सहायता के लिए तैयार हूँ।'
                  : 'This is an English voice preview for the assistant.')
            : null,
      );
      final bridge = _bridge;
      if (ttsOnly && bridge is! SpeechBridge) {
        throw const BridgeException(
          'UNAVAILABLE',
          'Android speech unavailable.',
        );
      }
      final result = ttsOnly
          ? await (bridge as SpeechBridge).speak(request)
          : await bridge.startVoiceInteraction(request);
      completed = result;
      outcome = result.result.code;
      if (lifetime.disposed) return;
      state = state.copyWith(response: result);
      _logger.record(LogCode.requestCompleted, id);
    } on BridgeException catch (failure) {
      outcome = failure.code;
      if (lifetime.disposed) return;
      state = state.copyWith(error: '${failure.code}: ${failure.message}');
      _logger.record(LogCode.requestFailed, id);
    } catch (_) {
      if (lifetime.disposed) return;
      state = state.copyWith(
        error: 'UNEXPECTED_ERROR: The request could not be completed.',
      );
      _logger.record(LogCode.requestFailed, id);
    } finally {
      if (!lifetime.disposed) {
        var trials = state.trials;
        if (settings.benchmark && !ttsOnly) {
          trials = [
            ...trials,
            SpeechTrial(
              environment: settings.environment,
              length: settings.length,
              requestId: id,
              outcome: outcome,
              response: completed,
              settings: settings.options.toMap(),
              device: {
                'model': settings.capabilities['device'],
                'androidSdk': settings.capabilities['androidSdk'],
              },
            ),
          ];
          if (trials.length > 100) trials = trials.sublist(trials.length - 100);
        }
        lifetime.activeRequestId = null;
        state = state.copyWith(busy: false, trials: trials, phase: outcome);
      }
    }
  }
}
