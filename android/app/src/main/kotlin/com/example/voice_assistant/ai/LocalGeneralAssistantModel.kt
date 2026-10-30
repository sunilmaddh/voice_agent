package com.example.voice_assistant.ai

import com.example.voice_assistant.document_ai.DocumentAnswerSchema
import com.example.voice_assistant.native_bridge.contracts.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Reuses a text-generation runtime boundary, with independent document-model ownership. */
class LocalGeneralAssistantModel(private val directory: File,
    private val factory: () -> TextGenerationRuntime,
    private val pss: () -> Long? = { null }) : GeneralAssistantModel {
    private val mutex = Mutex()
    private var runtime: TextGenerationRuntime? = null
    override var modelId = "not_configured"; private set
    var diagnostics: Map<String, Any?> = mapOf("modelReady" to false); private set
    suspend fun configure(config: CommandModelConfig) = mutex.withLock {
        withContext(Dispatchers.IO) {
            release()
            val file = File(directory, config.fileName).canonicalFile
            require(file.parentFile == directory.canonicalFile && file.isFile && file.length() > 0) { "MODEL_FILE_MISSING" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            require(digest.digest().joinToString("") { "%02x".format(it) }.equals(config.sha256, true)) { "MODEL_CHECKSUM_MISMATCH" }
            val candidate = factory()
            val before = pss()
            val started = System.nanoTime()
            try {
                candidate.load(file.path)
                currentCoroutineContext().ensureActive()
                runtime = candidate; modelId = config.modelId
                diagnostics = mapOf("modelReady" to true, "modelId" to modelId, "runtime" to candidate.runtimeId,
                    "backend" to candidate.backend, "modelLoadMs" to elapsed(started), "modelSizeBytes" to file.length(),
                    "quantizationDeclared" to config.quantization, "sha256" to config.sha256,
                    "processPssBeforeLoadKb" to before, "processPssAfterLoadKb" to pss(), "executionMode" to "LOCAL")
            } catch (e: Throwable) { withContext(NonCancellable) { candidate.release() }; throw e }
        }
    }
    override suspend fun answer(question: String, context: String?): CapabilityResult<String> = mutex.withLock {
        val active = runtime ?: return@withLock CapabilityResult.Unavailable("GENERAL_MODEL_NOT_CONFIGURED")
        val text = context ?: return@withLock CapabilityResult.Unavailable("DOCUMENT_REQUIRED")
        val started = System.nanoTime()
        var success = false
        try {
            val prompt = DocumentAnswerSchema.prompt(question, text)
            val raw = withTimeout(60000) { active.generate(prompt) }
            val answer = DocumentAnswerSchema.parse(raw, text)
            success = true
            CapabilityResult.Available(answer)
        } catch (_: TimeoutCancellationException) { CapabilityResult.Unavailable("DOCUMENT_MODEL_TIMEOUT") }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { CapabilityResult.Unavailable("DOCUMENT_MODEL_OUTPUT_OR_INPUT_INVALID") }
        finally { diagnostics = diagnostics + mapOf("inferenceMs" to elapsed(started), "validatedOutput" to success,
            "processPssAfterInferenceKb" to pss(), "memoryScope" to "WHOLE_PROCESS_NOT_MODEL_ONLY") }
    }
    override suspend fun close() = mutex.withLock { withContext(NonCancellable) { release() } }
    private suspend fun release() {
        try { runtime?.release() } finally { runtime = null; modelId = "not_configured"; diagnostics = mapOf("modelReady" to false) }
    }
    private fun elapsed(started: Long) = (System.nanoTime() - started) / 1e6
}
