package com.example.voice_assistant.document_ai

import com.example.voice_assistant.ai.GeneralAssistantModel
import com.example.voice_assistant.native_bridge.contracts.*
import java.io.File
import kotlinx.coroutines.CancellationException

class LocalDocumentAiService(private val ocr: OcrEngine, private val model: GeneralAssistantModel) : DocumentAiService {
    override suspend fun extractText(documentUri: String): CapabilityResult<String> = try {
        CapabilityResult.Available(ocr.extract(File(documentUri)))
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { CapabilityResult.Unavailable("DOCUMENT_OCR_FAILED") }
    override suspend fun answer(documentText: String, question: String) = model.answer(question, documentText)
}
