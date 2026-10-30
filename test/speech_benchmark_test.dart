import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:voice_assistant/benchmark/speech_benchmark.dart';
import 'package:voice_assistant/native_bridge/contracts/models.dart';

void main() {
  test('WER handles normalization, substitution, deletion and insertions', () {
    expect(wordErrorRate('Open Maps!', 'open maps'), 0);
    expect(wordErrorRate('open maps', 'open calendar'), .5);
    expect(wordErrorRate('open maps', 'open'), .5);
    expect(wordErrorRate('maps', 'open maps please'), 2);
    expect(wordErrorRate('', 'hello'), isNull);
  });
  test(
    'all eight benchmark conditions are available without fabricating success',
    () {
      final trials = [
        for (final environment in SoundEnvironment.values)
          for (final length in CommandLength.values)
            SpeechTrial(
              environment: environment,
              length: length,
              requestId: 'req-1-1',
              outcome: 'STT_NO_SPEECH',
            ),
      ];
      final data = jsonDecode(exportSpeechTrials(trials)) as Map;
      expect((data['trials'] as List).length, 8);
      for (final trial in data['trials'] as List) {
        expect(trial['wordErrorRate'], isNull);
        expect(trial['metrics'], isNull);
        expect(trial['outcome'], 'STT_NO_SPEECH');
      }
    },
  );
  test(
    'partial transcript is session data, not a final recognition result',
    () {
      final event = DiagnosticEvent.fromMap({
        'schemaVersion': 1,
        'requestId': 'req-1-1',
        'code': 'STT_PARTIAL',
        'level': 'INFO',
        'timestampMs': 1,
        'transcript': 'open',
      });
      expect(event.transcript, 'open');
      expect(event.code, 'STT_PARTIAL');
    },
  );
}
