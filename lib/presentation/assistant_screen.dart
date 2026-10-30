import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'assistant_controller.dart';
import 'assistant_state.dart';
import 'assistant_diagnostics_screen.dart';
import 'command_model_screen.dart';
import 'speech_controls.dart';
import 'speech_settings.dart';
import 'calendar_settings.dart';
import 'gmail_screen.dart';
import 'notification_settings.dart';
import 'system_assistant_settings.dart';
import 'driving_mode_settings.dart';
import 'document_ai_screen.dart';
import 'bill_reminders_screen.dart';
import 'caller_announcement_settings.dart';
import 'whatsapp_reply_review.dart';

const _ink = Color(0xFF252D46);
const _blue = Color(0xFF238BE6);

class AssistantScreen extends ConsumerStatefulWidget {
  const AssistantScreen({super.key});
  @override
  ConsumerState<AssistantScreen> createState() => _AssistantScreenState();
}

class _AssistantScreenState extends ConsumerState<AssistantScreen> {
  int tab = 0;
  void open(Widget screen) => Navigator.of(
    context,
  ).push<void>(MaterialPageRoute(builder: (_) => screen));
  void shortcut(String label) {
    if (label == 'Documents') {
      if (ref.read(assistantControllerProvider).busy) return;
      open(const DocumentAiScreen());
      return;
    }
    if (label == 'Email') {
      open(const GmailScreen());
      return;
    }
    showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(label),
        content: Text(
          label == 'Call'
              ? 'Say Call followed by a phone number or exact saved contact name. Contact names require permission and number selection. Verify the number and tap Call in the dialer. Unlocking is required.'
              : label == 'Navigate'
              ? 'Tap the voice button and say “take me to Bangalore Airport” to open Google Maps with your destination.'
              : label == 'Message'
              ? 'Say “Tell Rahul I will call him after the meeting”. Aiko opens an SMS draft; select Rahul, review it, and tap Send in your messaging app.'
              : label == 'Calendar'
              ? 'Enable calendar access in Settings, then say “What meetings do I have tomorrow?” or “Remind me tomorrow at 4 PM to attend my meeting”.'
              : '$label actions are not available in this POC yet. '
                    'You can use the voice button to test speech and command recognition.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Got it'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final session = ref.watch(assistantControllerProvider);
    final controller = ref.read(assistantControllerProvider.notifier);
    return Scaffold(
      backgroundColor: const Color(0xFFF8FAFE),
      body: DecoratedBox(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [
              Color(0xFFE2D9FA),
              Color(0xFFF5EAF7),
              Color(0xFFF1F8FF),
              Color(0xFFFCFDFE),
            ],
            stops: [0, .22, .55, 1],
          ),
        ),
        child: SafeArea(
          bottom: false,
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 520),
              child: switch (tab) {
                0 => _Home(
                  session: session,
                  onSpeak: session.busy ? controller.cancel : controller.start,
                  onShortcut: shortcut,
                ),
                1 => _History(session: session),
                _ => ListView(
                  padding: const EdgeInsets.all(24),
                  children: [
                    const _Heading('Settings', 'Make Aiko work for you'),
                    const SizedBox(height: 28),
                    const CalendarSettings(),
                    const DrivingModeSettings(),
                    const CallerAnnouncementSettings(),
                    ListTile(
                      leading: const Icon(Icons.document_scanner),
                      title: const Text('Document AI'),
                      subtitle: const Text(
                        'Capture, OCR and local document questions',
                      ),
                      onTap: session.busy
                          ? null
                          : () => open(const DocumentAiScreen()),
                    ),
                    const SystemAssistantSettings(),
                    const NotificationSettings(),
                    ListTile(
                      leading: const Icon(Icons.receipt_long),
                      title: const Text('Smart bill reminders'),
                      subtitle: const Text(
                        'Extract notification / SMS due dates and create local reminders',
                      ),
                      onTap: session.busy
                          ? null
                          : () => open(const BillRemindersScreen()),
                    ),
                    ListTile(
                      leading: const Icon(Icons.email_outlined),
                      title: const Text('Gmail'),
                      subtitle: const Text(
                        'Connect, read emails and review replies',
                      ),
                      onTap: () => open(const GmailScreen()),
                    ),
                    const Card(
                      child: Padding(
                        padding: EdgeInsets.all(8),
                        child: SpeechControls(),
                      ),
                    ),
                    Card(
                      child: Column(
                        children: [
                          ListTile(
                            leading: const Icon(
                              Icons.tune_rounded,
                              color: _blue,
                            ),
                            title: const Text('Technical dashboard'),
                            subtitle: const Text(
                              'Transcript, intent and diagnostics',
                            ),
                            trailing: const Icon(Icons.chevron_right),
                            onTap: () =>
                                open(const AssistantDiagnosticsScreen()),
                          ),
                          const Divider(height: 1, indent: 16, endIndent: 16),
                          ListTile(
                            leading: const Icon(
                              Icons.science_outlined,
                              color: _blue,
                            ),
                            title: const Text('Local command model'),
                            subtitle: const Text('Model setup and benchmarks'),
                            trailing: const Icon(Icons.chevron_right),
                            onTap: session.busy
                                ? null
                                : () => open(const CommandModelScreen()),
                          ),
                        ],
                      ),
                    ),
                    const Padding(
                      padding: EdgeInsets.all(16),
                      child: Text(
                        'English voice assistant · Technical POC',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          color: Color(0xFF67738B),
                          fontSize: 12,
                        ),
                      ),
                    ),
                  ],
                ),
              },
            ),
          ),
        ),
      ),
      bottomNavigationBar: DecoratedBox(
        decoration: const BoxDecoration(
          border: Border(top: BorderSide(color: Color(0xFFEAF0F7))),
        ),
        child: BottomNavigationBar(
          currentIndex: tab,
          onTap: (value) => setState(() => tab = value),
          backgroundColor: Colors.white,
          elevation: 0,
          selectedItemColor: _blue,
          unselectedItemColor: const Color(0xFF7F899A),
          selectedFontSize: 12,
          unselectedFontSize: 12,
          type: BottomNavigationBarType.fixed,
          items: const [
            BottomNavigationBarItem(
              icon: Icon(Icons.home_rounded),
              label: 'Home',
            ),
            BottomNavigationBarItem(
              icon: Icon(Icons.history_rounded),
              label: 'History',
            ),
            BottomNavigationBarItem(
              icon: Icon(Icons.settings_rounded),
              label: 'Settings',
            ),
          ],
        ),
      ),
    );
  }
}

class _Heading extends StatelessWidget {
  const _Heading(this.title, this.subtitle);
  final String title, subtitle;
  @override
  Widget build(BuildContext context) => Column(
    children: [
      const SizedBox(height: 28),
      Text(
        title,
        style: const TextStyle(
          fontSize: 34,
          fontWeight: FontWeight.w800,
          color: _ink,
          letterSpacing: -1,
        ),
      ),
      const SizedBox(height: 6),
      Text(
        subtitle,
        textAlign: TextAlign.center,
        style: const TextStyle(fontSize: 14, color: Color(0xFF56637D)),
      ),
    ],
  );
}

class _Home extends StatelessWidget {
  const _Home({
    required this.session,
    required this.onSpeak,
    required this.onShortcut,
  });
  final AssistantState session;
  final VoidCallback onSpeak;
  final ValueChanged<String> onShortcut;
  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final gap = (constraints.maxHeight * .055).clamp(16.0, 42.0);
      final listening =
          session.phase == 'LISTENING' || session.phase == 'STT_PARTIAL';
      return SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 30, vertical: 16),
        child: ConstrainedBox(
          constraints: BoxConstraints(minHeight: constraints.maxHeight - 32),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const _Heading('Aiko', 'Your AI Assistant'),
              SizedBox(height: gap),
              _VoiceButton(busy: session.busy, onPressed: onSpeak),
              const SizedBox(height: 14),
              Text(
                session.busy
                    ? (listening ? 'Listening…' : 'Working on it…')
                    : 'Tap to speak',
                style: const TextStyle(
                  color: _ink,
                  fontSize: 15,
                  fontWeight: FontWeight.w500,
                ),
              ),
              if (session.busy)
                const Padding(
                  padding: EdgeInsets.only(top: 6),
                  child: Text(
                    'Tap again to stop',
                    style: TextStyle(color: Color(0xFF67738B), fontSize: 12),
                  ),
                ),
              SizedBox(height: gap),
              for (final row in const [
                [
                  ('Call', Icons.call_rounded, Color(0xFF23BB8E)),
                  ('Message', Icons.chat_rounded, Color(0xFF20B8A0)),
                  ('Navigate', Icons.directions_rounded, Color(0xFF238BE6)),
                ],
                [
                  ('Email', Icons.mail_rounded, Color(0xFF369AD7)),
                  ('Calendar', Icons.calendar_month_rounded, Color(0xFF318CE5)),
                  ('Documents', Icons.description_rounded, Color(0xFF379DD5)),
                ],
              ])
                Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      for (var i = 0; i < row.length; i++) ...[
                        if (i > 0) const SizedBox(width: 10),
                        Expanded(
                          child: _Shortcut(
                            label: row[i].$1,
                            icon: row[i].$2,
                            color: row[i].$3,
                            onTap: () => onShortcut(row[i].$1),
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
              if (session.error != null ||
                  session.response != null ||
                  session.liveTranscript != null)
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: _SessionResult(session: session),
                ),
              const SizedBox(height: 16),
            ],
          ),
        ),
      );
    },
  );
}

class _VoiceButton extends StatelessWidget {
  const _VoiceButton({required this.busy, required this.onPressed});
  final bool busy;
  final VoidCallback onPressed;
  @override
  Widget build(BuildContext context) => Semantics(
    button: true,
    label: busy ? 'Cancel speech' : 'Start Voice Interaction',
    child: Tooltip(
      message: busy ? 'Cancel speech' : 'Start Voice Interaction',
      child: Container(
        width: 146,
        height: 146,
        padding: const EdgeInsets.all(9),
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          border: Border.all(
            color: Colors.white.withValues(alpha: .85),
            width: 3,
          ),
          color: Colors.white.withValues(alpha: .3),
          boxShadow: [
            BoxShadow(
              color: const Color(0xFF9299F1).withValues(alpha: .3),
              blurRadius: 32,
              spreadRadius: 8,
            ),
          ],
        ),
        child: Material(
          shape: const CircleBorder(),
          clipBehavior: Clip.antiAlias,
          child: Ink(
            decoration: const BoxDecoration(
              shape: BoxShape.circle,
              gradient: LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [
                  Color(0xFFB4E7FF),
                  Color(0xFF43BCF5),
                  Color(0xFF7DA5F1),
                ],
              ),
            ),
            child: InkWell(
              customBorder: const CircleBorder(),
              onTap: onPressed,
              child: Center(
                child: ExcludeSemantics(
                  child: busy
                      ? const Icon(
                          Icons.stop_rounded,
                          color: Colors.white,
                          size: 44,
                        )
                      : Row(
                          mainAxisSize: MainAxisSize.min,
                          crossAxisAlignment: CrossAxisAlignment.center,
                          children: [
                            for (final height in [
                              12.0,
                              25.0,
                              39.0,
                              53.0,
                              32.0,
                              21.0,
                              10.0,
                            ])
                              Container(
                                width: 4,
                                height: height,
                                margin: const EdgeInsets.symmetric(
                                  horizontal: 2.5,
                                ),
                                decoration: BoxDecoration(
                                  color: Colors.white,
                                  borderRadius: BorderRadius.circular(4),
                                ),
                              ),
                          ],
                        ),
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  );
}

class _Shortcut extends StatelessWidget {
  const _Shortcut({
    required this.label,
    required this.icon,
    required this.color,
    required this.onTap,
  });
  final String label;
  final IconData icon;
  final Color color;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => Material(
    color: Colors.white.withValues(alpha: .9),
    borderRadius: BorderRadius.circular(15),
    child: InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(15),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 18, horizontal: 4),
        child: Column(
          children: [
            Icon(icon, color: color, size: 29),
            const SizedBox(height: 9),
            Text(
              label,
              textAlign: TextAlign.center,
              style: const TextStyle(
                fontSize: 12,
                color: _ink,
                fontWeight: FontWeight.w600,
              ),
            ),
          ],
        ),
      ),
    ),
  );
}

class _SessionResult extends ConsumerWidget {
  const _SessionResult({required this.session});
  final AssistantState session;
  @override
  Widget build(BuildContext context, WidgetRef ref) => Card(
    child: Padding(
      padding: const EdgeInsets.all(18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (session.error != null)
            Text(
              session.error!,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          if (session.response?.stt?.transcript ?? session.liveTranscript
              case final String text) ...[
            const Text(
              'You said',
              style: TextStyle(fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 6),
            SelectableText(text),
          ],
          if (session.response case final response?) ...[
            const SizedBox(height: 12),
            if (response.result.code == 'WHATSAPP_REPLY_REVIEW')
              WhatsAppReplyReview(key: ValueKey(response.requestId)),
            SelectableText(
              response.result.code == 'STT_SERVER_DISCONNECTED'
                  ? 'The Android speech service disconnected. Please try speaking again.'
                  : response.result.code == 'OFFLINE_KANNADA_UNAVAILABLE'
                  ? 'Offline Kannada recognition is unavailable. Enable system recognition if you allow audio to be processed online.'
                  : response.result.code == 'TTS_KANNADA_VOICE_UNAVAILABLE'
                  ? 'Install a Kannada voice in Android text-to-speech settings, then reload voices.'
                  : response.result.code == 'OFFLINE_HINDI_UNAVAILABLE'
                  ? 'Offline Hindi recognition is unavailable. Enable system recognition if you allow audio to be processed online.'
                  : response.result.code == 'TTS_HINDI_VOICE_UNAVAILABLE'
                  ? 'Install a Hindi voice in your Android text-to-speech settings, then reload voices.'
                  : response.result.code == 'OFFLINE_ENGLISH_UNAVAILABLE'
                  ? 'Offline English speech recognition is not available from your device’s speech provider.'
                  : response.result.code == 'STT_LANGUAGE_NOT_INSTALLED'
                  ? 'Android reports that the requested English speech language is not installed or currently available.'
                  : response.result.code == 'STT_LANGUAGE_UNSUPPORTED'
                  ? 'The selected Android speech provider does not support the requested English language.'
                  : response.response.isEmpty
                  ? response.result.code
                  : response.response,
            ),
            if (const {
              'OFFLINE_ENGLISH_UNAVAILABLE',
              'OFFLINE_HINDI_UNAVAILABLE',
              'STT_LANGUAGE_NOT_INSTALLED',
              'STT_LANGUAGE_UNSUPPORTED',
              'STT_SERVER_DISCONNECTED',
            }.contains(response.result.code)) ...[
              const SizedBox(height: 8),
              Text('Error: ${response.result.code}'),
              if (response.speech['sttEngine'] case final String engine)
                Text('Speech engine: $engine'),
              Text(
                ref
                        .watch(speechSettingsProvider)
                        .options
                        .preferSystemRecognition
                    ? 'System recognition is already selected. Check the English language support and downloads in your device’s speech provider settings. Switching to system recognition does not guarantee that the provider can recognize English.'
                    : 'You can try Android system recognition, which may send audio online. For offline-only use, install an English speech model through your device’s speech provider, if supported.',
              ),
              if (!ref
                  .watch(speechSettingsProvider)
                  .options
                  .preferSystemRecognition)
                FilledButton(
                  onPressed: session.busy
                      ? null
                      : () {
                          ref
                              .read(speechSettingsProvider.notifier)
                              .allowSystemRecognition();
                          ref
                              .read(assistantControllerProvider.notifier)
                              .start();
                        },
                  child: const Text('Use system recognition and retry'),
                ),
              if (response.result.code == 'STT_SERVER_DISCONNECTED') ...[
                const SizedBox(height: 8),
                const Text(
                  'If it keeps disconnecting, check for updates to your speech provider and restart your phone. The technical dashboard shows which recognition engine failed.',
                ),
                OutlinedButton(
                  onPressed: session.busy
                      ? null
                      : ref.read(assistantControllerProvider.notifier).start,
                  child: const Text('Retry speech'),
                ),
              ],
            ],
          ],
        ],
      ),
    ),
  );
}

class _History extends StatelessWidget {
  const _History({required this.session});
  final AssistantState session;
  @override
  Widget build(BuildContext context) => ListView(
    padding: const EdgeInsets.all(24),
    children: [
      const _Heading('History', 'From this session'),
      const SizedBox(height: 28),
      if (session.response != null || session.error != null) ...[
        const Text(
          'Latest interaction',
          style: TextStyle(fontWeight: FontWeight.w600),
        ),
        const SizedBox(height: 8),
        _SessionResult(session: session),
      ] else
        const Padding(
          padding: EdgeInsets.symmetric(vertical: 48),
          child: Column(
            children: [
              Icon(Icons.history_rounded, size: 48, color: Color(0xFF9AA8BF)),
              SizedBox(height: 16),
              Text(
                'No conversations yet',
                style: TextStyle(color: _ink, fontSize: 18),
              ),
              SizedBox(height: 8),
              Text(
                'Tap the voice button on Home to get started.',
                textAlign: TextAlign.center,
              ),
            ],
          ),
        ),
      if (session.trials.isNotEmpty) ...[
        const SizedBox(height: 20),
        const Text('Recorded speech benchmarks'),
        for (final trial in session.trials.reversed)
          Card(
            child: ListTile(
              leading: const Icon(Icons.mic_none_rounded, color: _blue),
              title: Text(trial.response?.stt?.transcript ?? 'No transcript'),
              subtitle: Text(trial.outcome),
            ),
          ),
      ],
      const SizedBox(height: 24),
      const Text(
        'Only the latest interaction and recorded benchmarks are retained during this session.',
        textAlign: TextAlign.center,
        style: TextStyle(color: Color(0xFF67738B), fontSize: 12),
      ),
    ],
  );
}
