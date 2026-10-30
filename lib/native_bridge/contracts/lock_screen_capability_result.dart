enum LockScreenCapabilityStatus {
  works,
  supportedWithRestrictions,
  notSupported,
}

class LockScreenCapabilityResult {
  const LockScreenCapabilityResult({
    required this.command,
    required this.state,
    required this.status,
    required this.evidence,
    required this.details,
  });
  final String command;
  final String state;
  final LockScreenCapabilityStatus status;
  final String evidence;
  final Map<String, Object?> details;

  factory LockScreenCapabilityResult.fromMap(Map<String, Object?> map) {
    final status = switch (map['status']) {
      'WORKS' => LockScreenCapabilityStatus.works,
      'SUPPORTED_WITH_RESTRICTIONS' =>
        LockScreenCapabilityStatus.supportedWithRestrictions,
      'NOT_SUPPORTED' => LockScreenCapabilityStatus.notSupported,
      _ => throw const FormatException('Invalid lock-screen capability status'),
    };
    return LockScreenCapabilityResult(
      command: map['command'] as String,
      state: map['state'] as String,
      status: status,
      evidence: map['evidence'] as String,
      details: Map.unmodifiable(map),
    );
  }
}
