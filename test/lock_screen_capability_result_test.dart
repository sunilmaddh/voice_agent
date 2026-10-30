import 'package:flutter_test/flutter_test.dart';
import 'package:voice_assistant/native_bridge/contracts/lock_screen_capability_result.dart';

void main() {
  test('capability assessment preserves pending evidence and restrictions', () {
    final result = LockScreenCapabilityResult.fromMap({
      'command': 'What time is it?',
      'state': 'SCREEN_ON_LOCKED',
      'status': 'SUPPORTED_WITH_RESTRICTIONS',
      'evidence': 'PENDING_DEVICE_TEST',
      'unlockRequired': false,
    });
    expect(result.status, LockScreenCapabilityStatus.supportedWithRestrictions);
    expect(result.evidence, 'PENDING_DEVICE_TEST');
    expect(result.details['unlockRequired'], false);
  });
  test('unknown native classification is rejected', () {
    expect(
      () => LockScreenCapabilityResult.fromMap({'status': 'probably works'}),
      throwsFormatException,
    );
  });
}
