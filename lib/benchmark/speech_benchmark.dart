import 'dart:convert';
import '../native_bridge/contracts/models.dart';

enum SoundEnvironment { quiet, normalRoom, noisy, vehicle }

enum CommandLength { short, long }

String benchmarkPrompt(CommandLength length) => length == CommandLength.short
    ? 'Open Maps'
    : 'Remind me tomorrow at ten in the morning to call Rahul after the meeting';

/// Levenshtein word error rate; insertions may legitimately make WER exceed 1.
double? wordErrorRate(String reference, String actual) {
  List<String> words(String value) => value
      .toLowerCase()
      .replaceAll(RegExp(r'[^a-z0-9\s]'), '')
      .split(RegExp(r'\s+'))
      .where((word) => word.isNotEmpty)
      .toList();
  final expected = words(reference), recognized = words(actual);
  if (expected.isEmpty) return null;
  var row = List.generate(recognized.length + 1, (index) => index);
  for (var i = 1; i <= expected.length; i++) {
    final next = <int>[i];
    for (var j = 1; j <= recognized.length; j++) {
      final candidates = [
        next[j - 1] + 1,
        row[j] + 1,
        row[j - 1] + (expected[i - 1] == recognized[j - 1] ? 0 : 1),
      ];
      next.add(candidates.reduce((a, b) => a < b ? a : b));
    }
    row = next;
  }
  return row.last / expected.length;
}

class SpeechTrial {
  SpeechTrial({
    required this.environment,
    required this.length,
    required this.requestId,
    required this.outcome,
    this.response,
    this.device = const {},
    this.settings = const {},
  }) : timestamp = DateTime.now().toUtc();
  final SoundEnvironment environment;
  final CommandLength length;
  final String requestId;
  final String outcome;
  final AssistantResponse? response;
  final Map<String, Object?> device;
  final Map<String, Object?> settings;
  final DateTime timestamp;
  Map<String, Object?> toMap() => {
    'requestId': requestId,
    'timestamp': timestamp.toIso8601String(),
    'environment': environment.name,
    'commandLength': length.name,
    'reference': benchmarkPrompt(length),
    'transcript': response?.stt?.transcript,
    'outcome': outcome,
    'wordErrorRate': response?.stt == null
        ? null
        : wordErrorRate(benchmarkPrompt(length), response!.stt!.transcript),
    'speech': response?.speech,
    'device': device,
    'settings': settings,
    'metrics': response?.metrics
        .map(
          (m) => {
            'stage': m.stage,
            'durationMs': m.durationMs,
            'status': m.status,
          },
        )
        .toList(),
  };
}

String exportSpeechTrials(List<SpeechTrial> trials) =>
    const JsonEncoder.withIndent('  ').convert({
      'schemaVersion': 1,
      'containsTranscripts': true,
      'trials': trials.map((trial) => trial.toMap()).toList(),
    });
