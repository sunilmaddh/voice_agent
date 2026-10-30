import 'speech_options.dart';

/// Version 1 contracts, independent of presentation and Android APIs.
enum ExecutionMode { local, cloud, notRun }

enum RoutingTier { deterministic, commandModel, generalModel, notRun }

enum ActionStatus { succeeded, unsupported, failed, requiresConfirmation }

enum ParameterType { text, number, boolean }

enum IntentKind {
  drivingModeOn,
  drivingModeOff,
  replyToCall,
  readSms,
  replySms,
  endCall,
  callPhone,
  openMaps,
  openCalendar,
  stopNavigation,
  navigate,
  googleSearch,
  createReminder,
  sendMessage,
  queryCalendar,
  createCalendarEvent,
  readNotification,
  readWhatsApp,
  replyWhatsApp,
  readEmail,
  replyEmail,
  documentQuery,
  generalQuery,
  unknown;

  String get wireName => switch (this) {
    drivingModeOn => 'DRIVING_MODE_ON',
    drivingModeOff => 'DRIVING_MODE_OFF',
    replyToCall => 'REPLY_TO_CALL',
    readSms => 'READ_SMS',
    replySms => 'REPLY_SMS',
    endCall => 'END_CALL',
    callPhone => 'CALL_PHONE',
    openMaps => 'OPEN_MAPS',
    openCalendar => 'OPEN_CALENDAR',
    stopNavigation => 'STOP_NAVIGATION',
    navigate => 'NAVIGATE',
    googleSearch => 'GOOGLE_SEARCH',
    createReminder => 'CREATE_REMINDER',
    sendMessage => 'SEND_MESSAGE',
    queryCalendar => 'QUERY_CALENDAR',
    createCalendarEvent => 'CREATE_CALENDAR_EVENT',
    readNotification => 'READ_NOTIFICATION',
    readWhatsApp => 'READ_WHATSAPP',
    replyWhatsApp => 'REPLY_WHATSAPP',
    readEmail => 'READ_EMAIL',
    replyEmail => 'REPLY_EMAIL',
    documentQuery => 'DOCUMENT_QUERY',
    generalQuery => 'GENERAL_QUERY',
    unknown => 'UNKNOWN',
  };
}

Map<String, Object?> objectMap(Object? value) {
  if (value is! Map) throw const FormatException('Expected an object');
  return value.map((key, value) {
    if (key is! String) throw const FormatException('Expected string keys');
    return MapEntry(key, value);
  });
}

T enumValue<T extends Enum>(List<T> values, Object? value) => values.firstWhere(
  (item) => item.name == value,
  orElse: () => throw const FormatException('Unknown enum value'),
);
String stringValue(Object? value) {
  if (value is! String) throw const FormatException('Expected a string');
  return value;
}

class VoiceRequest {
  const VoiceRequest({required this.requestId, this.input, this.options});
  final SpeechOptions? options;
  final String requestId;
  final String? input;
  Map<String, Object?> toMap() => {
    'schemaVersion': 1,
    'requestId': requestId,
    'input': input,
    if (options != null) 'options': options!.toMap(),
  };
}

class SpeechRecognitionResult {
  const SpeechRecognitionResult({
    required this.transcript,
    required this.isFinal,
  });
  final String transcript;
  final bool isFinal;
  factory SpeechRecognitionResult.fromMap(Map<String, Object?> map) {
    if (map['isFinal'] is! bool) {
      throw const FormatException('Invalid STT result');
    }
    return SpeechRecognitionResult(
      transcript: stringValue(map['transcript']),
      isFinal: map['isFinal']! as bool,
    );
  }
}

class IntentParameter {
  IntentParameter({
    required this.name,
    required this.type,
    required this.value,
  }) {
    final valid = switch (type) {
      ParameterType.text => value is String,
      ParameterType.number => value is num && (value as num).isFinite,
      ParameterType.boolean => value is bool,
    };
    if (name.isEmpty || !valid) {
      throw const FormatException('Invalid parameter');
    }
  }
  final String name;
  final ParameterType type;
  final Object value;
  factory IntentParameter.fromMap(Map<String, Object?> map) => IntentParameter(
    name: stringValue(map['name']),
    type: enumValue(ParameterType.values, map['type']),
    value: map['value'] ?? (throw const FormatException('Missing value')),
  );
}

class AssistantIntent {
  AssistantIntent({
    required this.kind,
    required List<IntentParameter> parameters,
    this.confidence,
  }) : parameters = List.unmodifiable(parameters) {
    if (confidence != null &&
        (!confidence!.isFinite || confidence! < 0 || confidence! > 1)) {
      throw const FormatException('Invalid confidence');
    }
    if (parameters.map((p) => p.name).toSet().length != parameters.length) {
      throw const FormatException('Duplicate parameter');
    }
  }
  final IntentKind kind;
  final List<IntentParameter> parameters;
  final double? confidence;
  factory AssistantIntent.fromMap(Map<String, Object?> map) => AssistantIntent(
    kind: IntentKind.values.firstWhere(
      (kind) => kind.wireName == map['kind'] || kind.name == map['kind'],
      orElse: () => throw const FormatException('Unknown intent'),
    ),
    parameters: (map['parameters'] as List)
        .map((p) => IntentParameter.fromMap(objectMap(p)))
        .toList(),
    confidence: (map['confidence'] as num?)?.toDouble(),
  );
}

class AssistantAction {
  const AssistantAction({required this.name, required this.intent});
  final String name;
  final AssistantIntent intent;
  factory AssistantAction.fromMap(Map<String, Object?> map) => AssistantAction(
    name: stringValue(map['name']),
    intent: AssistantIntent.fromMap(objectMap(map['intent'])),
  );
}

class ActionResult {
  const ActionResult({
    required this.status,
    required this.code,
    required this.message,
    this.evidence = const {},
  });
  final ActionStatus status;
  final String code;
  final String message;
  final Map<String, Object?> evidence;
  factory ActionResult.fromMap(Map<String, Object?> map) => ActionResult(
    status: enumValue(ActionStatus.values, map['status']),
    code: stringValue(map['code']),
    message: stringValue(map['message']),
    evidence: Map.unmodifiable(
      map['evidence'] == null
          ? <String, Object?>{}
          : objectMap(map['evidence']),
    ),
  );
}

class PerformanceMetric {
  const PerformanceMetric({
    required this.stage,
    required this.durationMs,
    required this.status,
  });
  final String stage;
  final double? durationMs;
  final String status;
  factory PerformanceMetric.fromMap(Map<String, Object?> map) {
    final status = stringValue(map['status']);
    final duration = (map['durationMs'] as num?)?.toDouble();
    if (!{'measured', 'not_run', 'unavailable'}.contains(status) ||
        (status == 'measured' &&
            (duration == null || !duration.isFinite || duration < 0)) ||
        (status != 'measured' && duration != null)) {
      throw const FormatException('Invalid metric');
    }
    return PerformanceMetric(
      stage: stringValue(map['stage']),
      durationMs: duration,
      status: status,
    );
  }
}

class DiagnosticEvent {
  const DiagnosticEvent({
    required this.requestId,
    required this.code,
    required this.level,
    required this.timestampMs,
    this.transcript,
  });
  final String requestId;
  final String code;
  final String level;
  final int timestampMs;
  final String? transcript;
  factory DiagnosticEvent.fromMap(Map<String, Object?> map) {
    if (map['schemaVersion'] != 1) {
      throw const FormatException('Unsupported schema');
    }
    return DiagnosticEvent(
      requestId: stringValue(map['requestId']),
      code: stringValue(map['code']),
      level: stringValue(map['level']),
      timestampMs: map['timestampMs'] as int,
      transcript: map['transcript'] as String?,
    );
  }
}

class AssistantResponse {
  AssistantResponse({
    required this.requestId,
    required this.input,
    required this.stt,
    required this.intent,
    required this.routingTier,
    required this.selectedModel,
    required this.structuredOutput,
    required this.action,
    required this.result,
    required this.response,
    required this.executionMode,
    required List<PerformanceMetric> metrics,
    this.speech = const {},
    this.routing = const {},
  }) : metrics = List.unmodifiable(metrics);
  final String requestId;
  final String? input;
  final SpeechRecognitionResult? stt;
  final AssistantIntent? intent;
  final RoutingTier routingTier;
  final String? selectedModel;
  final Map<String, Object?>? structuredOutput;
  final AssistantAction? action;
  final ActionResult result;
  final String response;
  final ExecutionMode executionMode;
  final List<PerformanceMetric> metrics;
  final Map<String, Object?> speech;
  final Map<String, Object?> routing;
  factory AssistantResponse.fromMap(Map<String, Object?> map) {
    if (map['schemaVersion'] != 1) {
      throw const FormatException('Unsupported schema');
    }
    return AssistantResponse(
      requestId: stringValue(map['requestId']),
      routing: map['routing'] == null
          ? const {}
          : Map.unmodifiable(objectMap(map['routing'])),
      speech: map['speech'] == null
          ? const {}
          : Map.unmodifiable(objectMap(map['speech'])),
      input: map['input'] as String?,
      stt: map['stt'] == null
          ? null
          : SpeechRecognitionResult.fromMap(objectMap(map['stt'])),
      intent: map['intent'] == null
          ? null
          : AssistantIntent.fromMap(objectMap(map['intent'])),
      routingTier: enumValue(RoutingTier.values, map['routingTier']),
      selectedModel: map['selectedModel'] as String?,
      structuredOutput: map['structuredOutput'] == null
          ? null
          : Map.unmodifiable(objectMap(map['structuredOutput'])),
      action: map['action'] == null
          ? null
          : AssistantAction.fromMap(objectMap(map['action'])),
      result: ActionResult.fromMap(objectMap(map['result'])),
      response: stringValue(map['response']),
      executionMode: enumValue(ExecutionMode.values, map['executionMode']),
      metrics: (map['metrics'] as List)
          .map((m) => PerformanceMetric.fromMap(objectMap(m)))
          .toList(),
    );
  }
}
