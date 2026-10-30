import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../native_bridge/document_bridge.dart';

class DocumentAiScreen extends StatefulWidget {
  const DocumentAiScreen({super.key});
  @override
  State<DocumentAiScreen> createState() => _DocumentAiScreenState();
}

class _DocumentAiScreenState extends State<DocumentAiScreen> {
  final bridge = const DocumentBridge();
  final text = TextEditingController();
  final question = TextEditingController(text: 'Explain this document.');
  final fileName = TextEditingController(text: 'document-model.litertlm');
  final modelId = TextEditingController();
  final hash = TextEditingController();
  final quantization = TextEditingController();
  Map<String, Object?> report = {};
  bool busy = false;
  String? error;
  String? operation;
  String sample = 'electricity_bill';
  static const questions = [
    'Explain this document.',
    'Summarize this.',
    'What is the amount?',
    'What is the due date?',
    'Who issued this document?',
    'What are the important points?',
  ];
  @override
  void initState() {
    super.initState();
    run('status');
  }

  @override
  void dispose() {
    bridge.run('close').catchError((Object _) => <String, Object?>{});
    for (final c in [text, question, fileName, modelId, hash, quantization]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> run(String method, [Map<String, Object?>? args]) async {
    if (busy) return;
    setState(() {
      busy = true;
      error = null;
      operation = method;
      if (method == 'capture' || method == 'sample') text.clear();
      if (['ask', 'askVoice', 'sample', 'capture'].contains(method)) {
        report = {...report, 'answer': ''};
      }
    });
    try {
      final value = await bridge.run(method, args);
      if (!mounted) return;
      setState(() {
        report = value;
        if (value['text'] is String) text.text = value['text'] as String;
        final diagnostics = value['diagnostics'] as Map?;
        if (diagnostics?['question'] is String) {
          question.text = diagnostics!['question'] as String;
        }
        if (method == 'close') text.clear();
      });
    } on PlatformException catch (e) {
      if (mounted) {
        setState(
          () =>
              error = '${e.code}: ${e.message ?? "Document operation failed"}',
        );
      }
    } catch (_) {
      if (mounted) {
        setState(() => error = 'Document AI requires the Android application.');
      }
    } finally {
      if (mounted) {
        setState(() {
          busy = false;
          operation = null;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Document AI POC')),
    body: ListView(
      padding: const EdgeInsets.all(16),
      children: [
        const Text(
          'Capture → local OCR → review text → local model → spoken answer',
        ),
        const SizedBox(height: 12),
        FilledButton.icon(
          onPressed: busy ? null : () => run('capture'),
          icon: const Icon(Icons.document_scanner),
          label: const Text('Capture document'),
        ),
        const Text(
          'Capture one page using the camera app. The temporary image is deleted after OCR. Review extracted text, especially amounts and dates.',
        ),
        if (busy) ...[
          const LinearProgressIndicator(),
          Text('Working: $operation'),
          TextButton(
            onPressed: operation == 'capture'
                ? null
                : () => bridge.run('cancel'),
            child: const Text('Cancel'),
          ),
        ],
        if (error != null)
          SelectableText(
            error!,
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        TextField(
          controller: text,
          enabled: !busy,
          minLines: 5,
          maxLines: 12,
          decoration: const InputDecoration(
            labelText: 'OCR text / reviewed excerpt',
            helperText:
                'Select at most 3,000 characters for model context. No silent truncation.',
          ),
        ),
        TextField(
          controller: question,
          enabled: !busy,
          maxLength: 500,
          decoration: const InputDecoration(labelText: 'Question'),
        ),
        Wrap(
          spacing: 6,
          children: questions
              .map(
                (q) => ActionChip(
                  label: Text(q),
                  onPressed: busy
                      ? null
                      : () => setState(() => question.text = q),
                ),
              )
              .toList(),
        ),
        Wrap(
          spacing: 12,
          children: [
            FilledButton(
              onPressed: busy
                  ? null
                  : () => run('ask', {
                      'text': text.text,
                      'question': question.text,
                    }),
              child: const Text('Ask local model'),
            ),
            OutlinedButton.icon(
              onPressed: busy
                  ? null
                  : () => run('askVoice', {'text': text.text}),
              icon: const Icon(Icons.mic),
              label: const Text('Speak a question'),
            ),
          ],
        ),
        const Text(
          'Voice questions use your saved English STT settings. System STT may use its provider network if you previously enabled it. Document text is sent only to the local model.',
        ),
        const SizedBox(height: 12),
        const Text(
          'Answer and supporting excerpts',
          style: TextStyle(fontWeight: FontWeight.bold),
        ),
        SelectableText(report['answer'] as String? ?? 'No answer yet.'),
        const Text(
          'Check the source: matching excerpts do not guarantee the model interpreted them correctly.',
        ),
        OutlinedButton(
          onPressed: busy || (report['answer'] as String? ?? '').isEmpty
              ? null
              : () => run('speak'),
          child: const Text('Read answer aloud'),
        ),
        ExpansionTile(
          title: const Text('Local model setup'),
          children: [
            const Text(
              'Stage a compatible text-instruction .litertlm model in app-private files/document_models using Android Studio Device Explorer. Model weights are not bundled. Hardware compatibility must be tested. Loading unloads the command model to conserve RAM.',
            ),
            for (final field in [
              (fileName, 'Filename'),
              (modelId, 'Model ID'),
              (hash, 'Expected SHA-256'),
              (quantization, 'Declared quantization'),
            ])
              TextField(
                controller: field.$1,
                enabled: !busy,
                decoration: InputDecoration(labelText: field.$2),
              ),
            FilledButton(
              onPressed: busy
                  ? null
                  : () => run('loadModel', {
                      'fileName': fileName.text.trim(),
                      'modelId': modelId.text.trim(),
                      'sha256': hash.text.trim(),
                      'quantization': quantization.text.trim(),
                    }),
              child: const Text('Load local document model'),
            ),
          ],
        ),
        ExpansionTile(
          title: const Text('Five-document validation'),
          children: [
            const Text(
              'Synthetic test documents only. Loading a sample runs real OCR on a generated image; it does not test the camera. No measured scores are prefilled.',
            ),
            DropdownButton<String>(
              value: sample,
              isExpanded: true,
              items:
                  [
                        'electricity_bill',
                        'invoice',
                        'bank_statement',
                        'insurance',
                        'agreement',
                      ]
                      .map(
                        (id) => DropdownMenuItem(
                          value: id,
                          child: Text(id.replaceAll('_', ' ')),
                        ),
                      )
                      .toList(),
              onChanged: busy
                  ? null
                  : (value) => setState(() => sample = value!),
            ),
            OutlinedButton(
              onPressed: busy ? null : () => run('sample', {'id': sample}),
              child: const Text('Load sample through OCR'),
            ),
            OutlinedButton(
              onPressed: busy ? null : () => run('benchmark'),
              child: const Text('Run 5 documents × 6 questions'),
            ),
            const Text(
              'Requires a loaded model and may take several minutes. Answers need manual accuracy review. Camera, voice quality and real-document tests remain separate.',
            ),
          ],
        ),
        ExpansionTile(
          title: const Text('Technical diagnostics and benchmark'),
          children: [
            SelectableText(
              const JsonEncoder.withIndent('  ').convert({
                'diagnostics': report['diagnostics'],
                'validation': report['validation'],
                'benchmark': report['benchmark'],
              }),
            ),
            TextButton(
              onPressed: busy ? null : () => run('status'),
              child: const Text('Refresh results'),
            ),
          ],
        ),
        TextButton(
          onPressed: busy ? null : () => run('close'),
          child: const Text('Clear document and unload model'),
        ),
      ],
    ),
  );
}
