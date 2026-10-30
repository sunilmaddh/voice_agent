package com.example.voice_assistant.document_ai

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.provider.MediaStore
import android.view.WindowManager
import androidx.core.content.FileProvider
import io.flutter.plugin.common.*
import java.io.File
import kotlinx.coroutines.*
import com.example.voice_assistant.ai.*
import com.example.voice_assistant.native_bridge.contracts.*
import com.example.voice_assistant.stt.*
import com.example.voice_assistant.tts.AndroidTextToSpeechEngine
import com.example.voice_assistant.voice.SpeechAudioFocus
import com.example.voice_assistant.voice.wakeword.ReplyMicrophonePause

/** Document-scoped channel. Never passes text or generated answers to an ActionExecutor. */
class DocumentChannels(private val activity: Activity, messenger: BinaryMessenger) {
    companion object { const val CAMERA = 6101; const val MICROPHONE = 6102 }
    private val channel = MethodChannel(messenger, "voice_assistant/documents/v1")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var resumed = false
    private var job: Job? = null
    private var cameraResult: MethodChannel.Result? = null
    private var cameraUri: Uri? = null
    private val file = File(activity.cacheDir, "document_capture/page.jpg")
    private var secured = false
    private var document = ""
    private var answer = ""
    private var metrics: Map<String, Any?> = emptyMap()
    private var benchmark: List<Map<String, Any?>> = emptyList()
    private val model = LocalGeneralAssistantModel(File(activity.filesDir, "document_models"),
        { LiteRtCommandRuntime(activity.cacheDir.path) }, { android.os.Debug.getPss() })
    private val service = LocalDocumentAiService(MlKitOcrEngine(), model)
    init {
        file.parentFile!!.mkdirs(); file.delete() // Only this module's temporary capture.
        channel.setMethodCallHandler { call, result ->
            if (call.method == "cancel") { job?.cancel(); result.success(null); return@setMethodCallHandler }
            if (call.method == "close") {
                job?.cancel(); document = ""; answer = ""; metrics = emptyMap(); benchmark = emptyList(); file.delete(); unsecure()
                scope.launch { model.close() }; result.success(null); return@setMethodCallHandler
            }
            if (!resumed || activity.getSystemService(KeyguardManager::class.java).isDeviceLocked) {
                result.error("DOCUMENT_UNLOCK_REQUIRED", "Open and unlock Aiko to use documents.", null); return@setMethodCallHandler
            }
            if (job?.isActive == true || cameraResult != null) {
                result.error("DOCUMENT_BUSY", "Wait or cancel the document operation.", null); return@setMethodCallHandler
            }
            if (!secured && activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE); secured = true
            }
            if (call.method == "capture") { capture(result); return@setMethodCallHandler }
            run(result) {
                when (call.method) {
                    "status" -> Unit
                    "loadModel" -> {
                        // Heavy models should be evaluated sequentially to conserve device RAM.
                        AssistantRuntime.commandModel(activity).close()
                        model.configure(CommandModelConfig(call.argument<String>("fileName") ?: "",
                            call.argument<String>("modelId") ?: "", call.argument<String>("sha256") ?: "",
                            call.argument<String>("quantization") ?: ""))
                    }
                    "sample" -> { clearAnswer(); loadSample(call.argument<String>("id") ?: "") }
                    "ask", "askVoice" -> {
                        val selected = call.argument<String>("text") ?: document
                        require(selected.isNotBlank() && selected.length <= DocumentAnswerSchema.MAX_TEXT) { "DOCUMENT_SELECT_EXCERPT_MAX_3000" }
                        val edited = selected != document
                        document = selected; answer = ""
                        metrics = metrics.filterKeys { it.startsWith("ocr") || it in setOf("source", "fixture") } +
                            mapOf("userEditedText" to edited, "contextCharacters" to selected.length)
                        val question = if (call.method == "askVoice") recognize() else call.argument<String>("question") ?: ""
                        require(question.isNotBlank() && question.length <= 500) { "DOCUMENT_QUESTION_INVALID" }
                        val start = System.nanoTime()
                        metrics = metrics + mapOf("question" to question, "intent" to "DOCUMENT_QUERY", "routingTier" to "GENERAL_MODEL", "executionMode" to "LOCAL")
                        answer = available(service.answer(document, question))
                        metrics = metrics + mapOf("answerMs" to elapsed(start))
                        speakAnswer()
                        metrics = metrics + mapOf("questionToSpeechMs" to elapsed(start))
                    }
                    "speak" -> { require(answer.isNotBlank()) { "DOCUMENT_NO_ANSWER" }; speakAnswer() }
                    "benchmark" -> runBenchmark()
                    else -> error("DOCUMENT_METHOD_UNSUPPORTED")
                }
                status()
            }
        }
    }
    private fun status() = mapOf("text" to document, "answer" to answer, "diagnostics" to (metrics + model.diagnostics),
        "benchmark" to benchmark, "validation" to if (benchmark.isEmpty()) "PENDING_DEVICE_EXECUTION" else "MANUAL_ANSWER_REVIEW_REQUIRED")
    private fun run(result: MethodChannel.Result, work: suspend () -> Any?) {
        job = scope.launch {
            try { result.success(work()) }
            catch (_: CancellationException) { result.error("DOCUMENT_CANCELLED", "Document operation cancelled.", null) }
            catch (_: LinkageError) { result.error("DOCUMENT_RUNTIME_UNSUPPORTED", "This device cannot load the selected runtime.", null) }
            catch (e: Exception) {
                val code = e.message?.takeIf { Regex("[A-Z][A-Z0-9_]{1,80}").matches(it) } ?: "DOCUMENT_OPERATION_FAILED"
                result.error(code, "Document operation did not complete. Check the document, model and permissions.", null)
            } finally { if (job === currentCoroutineContext()[Job]) job = null }
        }
    }
    private fun capture(result: MethodChannel.Result) {
        document = ""; clearAnswer(); file.delete()
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.documents", file)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri("Document capture", uri)
            cameraUri = uri; cameraResult = result
            activity.startActivityForResult(intent, CAMERA)
        } catch (_: Exception) { cleanupCapture(); cameraResult = null; result.error("DOCUMENT_CAMERA_UNAVAILABLE", "No accessible camera app.", null) }
    }
    fun cameraFinished(resultCode: Int) {
        val result = cameraResult ?: return
        cameraResult = null
        if (resultCode != Activity.RESULT_OK) { cleanupCapture(); result.error("DOCUMENT_CAPTURE_CANCELLED", "Capture cancelled.", null); return }
        run(result) {
            try {
                withTimeout(5000) { while (!resumed) delay(50) }
                check(!activity.getSystemService(KeyguardManager::class.java).isDeviceLocked) { "DOCUMENT_UNLOCK_REQUIRED" }
                val start = System.nanoTime()
                document = available(service.extractText(file.path))
                metrics = mapOf("source" to "CAMERA", "ocrEngine" to "ML_KIT_BUNDLED_LATIN", "ocrMs" to elapsed(start),
                    "ocrCharacters" to document.length, "executionMode" to "LOCAL")
                status()
            } finally { cleanupCapture() }
        }
    }
    private fun cleanupCapture() {
        cameraUri?.let { activity.revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        cameraUri = null; file.delete()
    }
    private suspend fun recognize(): String {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE)
            error("DOCUMENT_MICROPHONE_PERMISSION_REQUIRED_RETRY")
        }
        return ReplyMicrophonePause.use {
            val stt = AndroidSpeechToTextEngine(activity.applicationContext) {}
            try {
                val start = System.nanoTime()
                val value = available(stt.recognize(VoiceRequest("req-${System.currentTimeMillis()}-0", options = SavedSpeechOptions.read(activity))))
                require(value.isFinal) { "DOCUMENT_FINAL_SPEECH_REQUIRED" }
                metrics = metrics + mapOf("sttMs" to elapsed(start), "sttMode" to stt.mode, "sttEngine" to stt.engine)
                value.transcript
            } finally { stt.close() }
        }
    }
    private suspend fun speakAnswer() {
        try {
            ReplyMicrophonePause.use {
                val tts = AndroidTextToSpeechEngine(activity.applicationContext) {}
                val focus = SpeechAudioFocus(activity)
                try {
                    val current = currentCoroutineContext()[Job]
                    focus.acquire { current?.cancel() }
                    tts.options = SavedSpeechOptions.read(activity)
                    val start = System.nanoTime()
                    available(tts.speak("req-${System.currentTimeMillis()}-0", answer.substringBefore("\n\nDocument excerpts:")))
                    metrics = metrics + mapOf("ttsMs" to elapsed(start), "ttsStartupMs" to tts.startupMs,
                        "ttsEngine" to tts.engine, "ttsVoice" to tts.selectedVoice, "ttsMode" to tts.mode, "ttsError" to null)
                } finally { tts.close(); focus.release() }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { metrics = metrics + mapOf("ttsError" to "DOCUMENT_TTS_UNAVAILABLE") }
    }
    private fun clearAnswer() { answer = ""; metrics = emptyMap() }
    private fun samples() = org.json.JSONArray(activity.assets.open("document_validation_v1.json").bufferedReader().use { it.readText() })
    private suspend fun loadSample(id: String) {
        val data = samples()
        val sample = (0 until data.length()).map { data.getJSONObject(it) }.single { it.getString("id") == id }
        // Real OCR over a generated fixture image, never a fabricated camera/OCR result.
        withContext(Dispatchers.IO) {
            val bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 32f }
                sample.getString("text").lines().forEachIndexed { index, line -> canvas.drawText(line, 40f, 70f + index * 65f, paint) }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            } finally { bitmap.recycle() }
        }
        try {
            val start = System.nanoTime()
            document = available(service.extractText(file.path))
            metrics = mapOf("source" to "SYNTHETIC_FIXTURE_NOT_CAMERA", "fixture" to id, "ocrMs" to elapsed(start),
                "ocrEngine" to "ML_KIT_BUNDLED_LATIN", "ocrExactTextMatch" to (document.replace(Regex("\\s+"), " ") == sample.getString("text").replace(Regex("\\s+"), " ")))
        } finally { file.delete() }
    }
    private suspend fun runBenchmark() {
        check(model.diagnostics["modelReady"] == true) { "GENERAL_MODEL_NOT_CONFIGURED" }
        benchmark = emptyList()
        val data = samples()
        for (i in 0 until data.length()) {
            val fixture = data.getJSONObject(i)
            loadSample(fixture.getString("id"))
            val ocr = metrics
            for (question in listOf("Explain this document.", "Summarize this.", "What is the amount?", "What is the due date?", "Who issued this document?", "What are the important points?")) {
                currentCoroutineContext().ensureActive()
                val start = System.nanoTime()
                val result = service.answer(document, question)
                benchmark = benchmark + mapOf("fixture" to fixture.getString("id"), "question" to question,
                    "ocr" to ocr, "latencyMs" to elapsed(start), "model" to model.diagnostics,
                    "answer" to (result as? CapabilityResult.Available)?.value,
                    "error" to (result as? CapabilityResult.Unavailable)?.code,
                    "expectedAmount" to fixture.getString("amount"), "expectedDue" to fixture.optString("due", "NOT_STATED"),
                    "expectedIssuer" to fixture.getString("issuer"), "semanticAccuracy" to "MANUAL_REVIEW_PENDING",
                    "cameraValidation" to "NOT_RUN_SYNTHETIC_IMAGE", "voiceValidation" to "NOT_RUN")
            }
        }
        answer = "" // Benchmarks do not silently speak 30 answers or report inferred accuracy.
    }
    private fun <T> available(value: CapabilityResult<T>): T = when (value) {
        is CapabilityResult.Available -> value.value
        is CapabilityResult.Unavailable -> error(value.code)
    }
    fun resume() { resumed = true }
    fun pause() { resumed = false; job?.cancel() }
    private fun unsecure() { if (secured) { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE); secured = false } }
    fun close() {
        channel.setMethodCallHandler(null); job?.cancel()
        cameraResult?.error("DOCUMENT_INTERRUPTED", "Document screen was destroyed.", null); cameraResult = null
        cleanupCapture(); document = ""; answer = ""; unsecure()
        scope.launch { model.close(); scope.cancel() }
    }
    private fun elapsed(start: Long) = (System.nanoTime() - start) / 1e6
}
