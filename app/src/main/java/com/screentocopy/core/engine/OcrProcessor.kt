package com.screentocopy.core.engine

import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import kotlin.coroutines.resume

/**
 * 👁️ ML Kit OCR Engine (Real Implementation)
 * 
 * Sadece Latin harfleri veya istenen diller.
 * Async coroutine ile tamamen suspendable yapıya sahiptir.
 */
class OcrProcessor {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun process(
        nv21Buffer: ByteBuffer,
        width: Int,
        height: Int
    ): OcrResult = suspendCancellableCoroutine { cont ->

        // ByteBuffer'ı ByteArray'e güvenli şekilde çeviriyoruz
        val nv21Bytes = if (nv21Buffer.hasArray()) {
            nv21Buffer.array()
        } else {
            val arr = ByteArray(nv21Buffer.remaining())
            val originalPos = nv21Buffer.position()
            nv21Buffer.get(arr)
            nv21Buffer.position(originalPos)
            arr
        }

        val image = InputImage.fromByteArray(
            nv21Bytes,
            width,
            height,
            0,
            InputImage.IMAGE_FORMAT_NV21
        )

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val boxes = mutableListOf<RectF>()

                for (block in visionText.textBlocks) {
                    block.boundingBox?.let {
                        boxes.add(RectF(it))
                    }
                }

                // Coroutine resume ile sonucu dön
                cont.resume(
                    OcrResult(
                        text = visionText.text,
                        boundingBoxes = boxes
                    )
                )
            }
            .addOnFailureListener {
                // Hata durumunda boş dön
                cont.resume(OcrResult("", emptyList()))
            }
    }
}
