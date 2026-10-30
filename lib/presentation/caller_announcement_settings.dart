import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class CallerAnnouncementSettings extends StatefulWidget {
  const CallerAnnouncementSettings({super.key});
  @override
  State<CallerAnnouncementSettings> createState() =>
      _CallerAnnouncementSettingsState();
}

class _CallerAnnouncementSettingsState extends State<CallerAnnouncementSettings>
    with WidgetsBindingObserver {
  static const _channel = MethodChannel('voice_assistant/methods/v1');
  Map<String, Object?> _status = {};
  bool _busy = false;
  String? _error;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh([
    String method = 'callerAnnouncementStatus',
    Map<String, Object?>? args,
  ]) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final value = await _channel.invokeMapMethod<String, Object?>(
        method,
        args,
      );
      if (mounted) setState(() => _status = value ?? {});
    } on PlatformException catch (e) {
      if (mounted) setState(() => _error = e.code);
    } catch (_) {
      if (mounted) {
        setState(() => _error = 'Caller announcements require Android.');
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _set({bool? enabled, bool? locked, bool? voiceControl}) =>
      _refresh('callerAnnouncementSet', {
        'enabled': enabled ?? _status['enabled'] == true,
        'allowLocked': locked ?? _status['allowLocked'] == true,
        'voiceControl': voiceControl ?? _status['voiceControl'] == true,
      });
  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Caller announcements',
            style: TextStyle(fontWeight: FontWeight.bold),
          ),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Announce incoming caller'),
            subtitle: const Text(
              'Say “Incoming call from Rahul” once while ringing. Requires Aiko as default Phone and an installed offline English TTS voice.',
            ),
            value: _status['enabled'] == true,
            onChanged: _busy || _status.isEmpty
                ? null
                : (v) => _set(enabled: v),
          ),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Announce while phone is locked'),
            subtitle: const Text(
              'Caller names may be heard nearby. With voice controls on, '
              'spoken accept or cut commands also work while locked. Off by default.',
            ),
            value: _status['allowLocked'] == true,
            onChanged: _busy || _status.isEmpty ? null : (v) => _set(locked: v),
          ),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Ask to accept or cut the call'),
            subtitle: const Text(
              'After the caller name, wait for the listening beep. '
              'Listen for up to 15 seconds, with one retry if the reply is unclear. '
              'Say "accept the call" to answer, or "cut the call" to send your saved SMS '
              'and decline after Android confirms it was sent. Carrier charges may apply. '
              'No clear reply leaves the call ringing. Off by default.',
            ),
            value: _status['voiceControl'] == true,
            onChanged: _busy || _status.isEmpty
                ? null
                : (v) => _set(voiceControl: v),
          ),
          if (_status['voiceControl'] == true) ...[
            Text('Saved SMS: ${_status['replyMessage'] ?? 'Not loaded'}'),
            const Text(
              'Edit this message in Driving Mode settings. Driving Mode can stay off. '
              'Voice control also requires caller announcements to be enabled. '
              'While locked, it uses the lock-screen announcement switch above. '
              'Recognition uses your existing Speech settings and system-recognition consent.',
            ),
            if (_status['microphoneGranted'] != true)
              const Text(
                'Allow microphone access before using voice call controls.',
              ),
            if (_status['smsGranted'] != true ||
                _status['phoneStateGranted'] != true)
              const Text(
                'Cut and text needs SMS and Phone state permissions from Aiko Phone setup. '
                'If SMS is unavailable or fails, Aiko leaves the call unchanged.',
              ),
          ],
          if (_status.isNotEmpty && _status['defaultPhone'] != true)
            const Text(
              'Currently blocked: Aiko is not the default Phone app. '
              'Choose Aiko in Android Settings > Default apps > Phone app. '
              'Selecting the default assistant alone is not enough.',
            ),
          const Text(
            'Unknown or hidden callers receive a generic announcement. In silent/vibrate mode, announcements use media volume; keep media volume above zero. Do Not Disturb, driving mode, silencing the incoming call and calls already in progress suppress announcements. Audio routing and audibility need device testing.',
          ),
          Text(
            'Contacts access: ${_status['contactsGranted'] == true ? 'Granted' : 'Not granted'}',
          ),
          Text('Last saved event: ${_status['lastEvent'] ?? 'Not loaded'}'),
          if (_status['lastEventAtMs'] is num &&
              (_status['lastEventAtMs'] as num) > 0)
            Text(
              'Recorded on this phone: ${DateTime.fromMillisecondsSinceEpoch((_status['lastEventAtMs'] as num).toInt()).toLocal()}',
            ),
          const Text(
            'Saved events can come from an earlier build or call. '
            'A new incoming call is needed to validate the current setup. '
            'The phone foreground service is normally off when no call is active.',
          ),
          Text('Ringtone: ${_status['ringtoneStatus'] ?? 'Not loaded'}'),
          Text(
            'Phone foreground service: ${_status['phoneForeground'] == true}',
          ),
          if ({
            'AUDIO_FOCUS_DENIED',
            'ANNOUNCEMENT_FOCUS_DENIED_BY_ANDROID',
            'ANNOUNCEMENT_FOCUS_UNAVAILABLE_WHILE_RINGING',
          }.contains(_status['lastEvent']))
            const Text(
              'During that saved attempt, Android did not grant audio focus while the call was ringing. '
              'Caller speech was skipped. Check with Aiko’s incoming-call screen visible; '
              'some devices still reserve focus for Telecom. Aiko will not speak later after you answer.',
            ),
          if (_error != null)
            Text(
              _error!,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          Wrap(
            spacing: 8,
            children: [
              TextButton(
                onPressed: _busy
                    ? null
                    : () => _refresh('callerAnnouncementContacts'),
                child: const Text('Allow contact names'),
              ),
              TextButton(
                onPressed: _busy
                    ? null
                    : () => _refresh('callerAnnouncementMicrophone'),
                child: const Text('Allow voice control microphone'),
              ),
              TextButton(
                onPressed: _busy ? null : () => _refresh(),
                child: const Text('Refresh caller status'),
              ),
            ],
          ),
        ],
      ),
    ),
  );
}
