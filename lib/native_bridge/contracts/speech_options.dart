class SpeechOptions {
  const SpeechOptions({
    this.languageTag = 'en-US',
    this.allowSystemRecognition = false,
    this.preferSystemRecognition = false,
    this.voiceName,
    this.rate = 1,
    this.pitch = 1,
    this.offlineTtsOnly = true,
  });
  final bool allowSystemRecognition;
  final String languageTag;
  final bool preferSystemRecognition;
  final String? voiceName;
  final double rate;
  final double pitch;
  final bool offlineTtsOnly;
  Map<String, Object?> toMap() => {
    'languageTag': languageTag,
    'allowSystemRecognition': allowSystemRecognition,
    'preferSystemRecognition': preferSystemRecognition,
    'voiceName': voiceName,
    'rate': rate,
    'pitch': pitch,
    'offlineTtsOnly': offlineTtsOnly,
  };
}
