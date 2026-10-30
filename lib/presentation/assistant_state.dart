import '../native_bridge/contracts/models.dart';
import '../benchmark/speech_benchmark.dart';

/// Immutable presentation snapshot. Nullable fields are absent until actually run.
class AssistantState {
  AssistantState({
    this.busy = false,
    this.debugMode = false,
    this.error,
    this.eventWarning,
    this.response,
    List<DiagnosticEvent> events = const [],
    this.liveTranscript,
    this.phase = 'Ready',
    List<SpeechTrial> trials = const [],
  }) : events = List.unmodifiable(events),
       trials = List.unmodifiable(trials);
  final String? liveTranscript;
  final String phase;
  final List<SpeechTrial> trials;

  final bool busy;
  final bool debugMode;
  final String? error;
  final String? eventWarning;
  final AssistantResponse? response;
  final List<DiagnosticEvent> events;

  AssistantState copyWith({
    bool? busy,
    bool? debugMode,
    String? error,
    String? eventWarning,
    AssistantResponse? response,
    List<DiagnosticEvent>? events,
    String? liveTranscript,
    String? phase,
    List<SpeechTrial>? trials,
  }) => AssistantState(
    busy: busy ?? this.busy,
    debugMode: debugMode ?? this.debugMode,
    error: error ?? this.error,
    eventWarning: eventWarning ?? this.eventWarning,
    response: response ?? this.response,
    events: events ?? this.events,
    liveTranscript: liveTranscript ?? this.liveTranscript,
    phase: phase ?? this.phase,
    trials: trials ?? this.trials,
  );
}
