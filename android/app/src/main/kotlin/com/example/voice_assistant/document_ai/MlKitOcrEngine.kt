package com.example.voice_assistant.document_ai

import android.graphics.*
import android.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface OcrEngine { suspend fun extract(file: File): String }

/** Bundled Latin OCR; no image upload. Bounded decode avoids full-resolution bitmap allocation. */
class MlKitOcrEngine : OcrEngine {
    override suspend fun extract(file: File): String = withContext(Dispatchers.IO) {
        require(file.length() in 1..(20L * 1024 * 1024)) { "DOCUMENT_IMAGE_SIZE_INVALID" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "DOCUMENT_IMAGE_INVALID" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("DOCUMENT_IMAGE_INVALID")
        var upright = bitmap
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val orientation = ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
            val transform = Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
                    5 -> { setRotate(90f); postScale(-1f, 1f) }
                    6 -> setRotate(90f); 7 -> { setRotate(270f); postScale(-1f, 1f) }
                    8 -> setRotate(270f)
                }
            }
            if (!transform.isIdentity) upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
            // ML Kit tasks cannot be cancelled. Await completion before recycling/closing resources.
            val text = withContext(NonCancellable) {
                suspendCancellableCoroutine<String> { c ->
                    recognizer.process(InputImage.fromBitmap(upright, 0))
                        .addOnSuccessListener { c.resume(it.text) }
                        .addOnFailureListener { c.resumeWithException(it) }
                }
            }
            currentCoroutineContext().ensureActive()
            require(text.isNotBlank()) { "DOCUMENT_NO_TEXT" }
            require(text.length <= 20000) { "DOCUMENT_TEXT_TOO_LONG" }
            text
        } finally { recognizer.close(); if (upright !== bitmap) upright.recycle(); bitmap.recycle() }
    }
}
