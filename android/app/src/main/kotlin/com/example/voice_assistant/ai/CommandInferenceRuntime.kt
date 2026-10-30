package com.example.voice_assistant.ai

/** Runtime-neutral boundary. Implementations produce data only, never Android actions. */
interface TextGenerationRuntime {
    val runtimeId: String
    val backend: String
    suspend fun load(modelPath: String)
    suspend fun generate(prompt: String): String
    suspend fun release()
}

interface CommandInferenceRuntime : TextGenerationRuntime

data class CommandContext(val nowEpochMs: Long = System.currentTimeMillis(),
    val timeZoneId: String = java.util.TimeZone.getDefault().id)

data class CommandModelConfig(val fileName: String, val modelId: String,
    val sha256: String, val quantization: String) {
    init {
        require(Regex("[A-Za-z0-9_-]{1,100}\\.litertlm").matches(fileName))
        require(Regex("[A-Za-z0-9._/-]{1,100}").matches(modelId))
        require(Regex("[a-fA-F0-9]{64}").matches(sha256))
        require(Regex("[A-Za-z0-9._-]{1,40}").matches(quantization))
    }
}
