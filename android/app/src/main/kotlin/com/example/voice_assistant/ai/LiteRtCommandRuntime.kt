package com.example.voice_assistant.ai

import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** CPU pilot; backend/model selection remains provisional until hardware benchmarks. */
class LiteRtCommandRuntime(private val cachePath: String) : CommandInferenceRuntime {
    override val runtimeId = "LiteRT-LM 0.17.1"
    override val backend = "CPU"
    private var engine: Engine? = null
    override suspend fun load(modelPath: String) = withContext(Dispatchers.IO) {
        check(engine == null)
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        val candidate = Engine(EngineConfig(modelPath = modelPath, backend = Backend.CPU(),
            maxNumTokens = 2048, cacheDir = cachePath))
        try { candidate.initialize(); engine = candidate }
        catch (error: Throwable) { candidate.close(); throw error }
    }
    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val loaded = checkNotNull(engine)
        // No tool providers registered; automatic invocation explicitly disabled.
        loaded.createConversation(ConversationConfig(automaticToolCalling = false,
            maxOutputToken = 384, samplerConfig = SamplerConfig(1, 1.0, 0.0))).use { conversation ->
            val output = StringBuilder()
            try {
                conversation.sendMessageAsync(prompt).collect { message ->
                    require(message.toolCalls.isEmpty()) { "UNEXPECTED_TOOL_OUTPUT" }
                    require(message.contents.contents.all { it is Content.Text }) { "UNEXPECTED_CONTENT" }
                    message.contents.contents.forEach { output.append((it as Content.Text).text) }
                    require(output.length <= 8192) { "OUTPUT_LIMIT" }
                }
            } finally { conversation.cancelProcess() }
            output.toString()
        }
    }
    override suspend fun release() = withContext(Dispatchers.IO) {
        engine?.close(); engine = null
    }
}
