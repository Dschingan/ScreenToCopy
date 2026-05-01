package com.screentocopy.core.engine

import android.graphics.Rect
import com.screentocopy.core.capture.FrameSniffer
import com.screentocopy.core.observability.AutoRecoveryEngine
import com.screentocopy.core.observability.MetricsCollector
import com.screentocopy.core.observability.StsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TargetMode {
    TEXT_ONLY, IMAGE_ONLY, HYBRID, SHARE
}

/**
 * 🚀 7. En Optimal Pipeline (Gerçek “Zero-Latency”)
 * 
 * Bu sınıf tüm cepheleri birleştirir:
 * PULL-BASED FrameSniffer -> Direct YUV Crop -> OCR Thread Pool -> Clipboard
 */
class ZeroLatencyPipeline(
    private val scope: CoroutineScope,
    private val frameSniffer: FrameSniffer,
    private val clipboardEngine: ClipboardEngine,
    private val onHighlightReady: (OcrResult) -> Unit
) {
    
    private val ocrProcessor = OcrProcessor()

    /**
     * @param selectionRect: Kullanıcının ekranda seçtiği ROI (Region of Interest) koordinatları.
     * @param targetMode: Ne kopyalamak istiyoruz? (TEXT_ONLY ise bitmap dönüşümü yapılıp bellek yorulmaz).
     */
    fun processSelection(selectionRect: Rect, targetMode: TargetMode = TargetMode.HYBRID) {
        
        // 1. Push değil Pull yapıyoruz. OCR motoru hazır olduğunda frame'i çeker.
        // Bu sayede OCR kuyruğu asla şişmez.
        
        scope.launch(IsolatedOcrDispatcher.dispatcher) {
            
            val pipelineStartTime = System.currentTimeMillis()
            
            // 2. Lifecycle Devri (Ownership)
            val frame = frameSniffer.pullLatestFrame()
            
            if (frame == null) {
                // Eğer frame yoksa (ZOMBIE modda veya ekran donmuşsa), 
                // pipeline'ı grace-ful şekilde sonlandır.
                MetricsCollector.recordFrameDrop()
                AutoRecoveryEngine.analyzeAndHeal()
                return@launch
            }

            try {
                // 3. YUV Direct Crop & Bitmap Avoidance (EN BÜYÜK KAZANÇ - %60 + %40)
                val cropStartTime = System.currentTimeMillis()
                val roiByteBuffer = YuvMemoryEngine.extractRoiToNv21ByteBuffer(
                    image = frame,
                    roi = selectionRect
                )
                val captureTime = System.currentTimeMillis() - cropStartTime

                val alignedWidth = selectionRect.width() and 1.inv()
                val alignedHeight = selectionRect.height() and 1.inv()
                
                // 4. ML Kit'e Besleme (Sadece Text istendiğinde veya Text+Image hibrit ise)
                val ocrStartTime = System.currentTimeMillis()
                val ocrResult = if (targetMode != TargetMode.IMAGE_ONLY) {
                    runOcr(roiByteBuffer, alignedWidth, alignedHeight, selectionRect)
                } else {
                    OcrResult("", emptyList())
                }
                val ocrTime = System.currentTimeMillis() - ocrStartTime

                // 5. Bitmap Dönüşümü (Sadece Görsel İstenirse veya Text Yoksa)
                // "Context-aware output": OCR text bulamazsa fallback olarak resmi kopyala
                val shouldExtractBitmap = targetMode == TargetMode.IMAGE_ONLY || 
                                        targetMode == TargetMode.HYBRID || 
                                        targetMode == TargetMode.SHARE ||
                                        ocrResult.text.isBlank()

                val extractedBitmap = if (shouldExtractBitmap) {
                    YuvMemoryEngine.nv21ToBitmap(roiByteBuffer, alignedWidth, alignedHeight)
                } else {
                    null
                }

                val totalTime = System.currentTimeMillis() - pipelineStartTime
                MetricsCollector.recordFrameMetrics(captureTime, ocrTime, totalTime, ocrResult.text.isNotEmpty())
                AutoRecoveryEngine.analyzeAndHeal()

                // 6. Visual Feedback — fire immediately, don't wait for clipboard I/O [Fix #6]
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (ocrResult.boundingBoxes.isNotEmpty()) {
                        onHighlightReady(ocrResult)
                    }
                }

                // Clipboard dispatch runs independently on IO — does not block highlights [Fix #6]
                clipboardEngine.dispatchSmart(ocrResult.text, extractedBitmap)

            } finally {
                // 🔥 4. Lifetime Yönetimi (EN KRİTİK BUG BURADA ÇÖZÜLDÜ)
                // Image close() disiplini: Ne olursa olsun (crash, success, timeout),
                // bu buffer ImageReader kuyruğuna iade edilmeli.
                frame.close()
            }
        }
    }

    private suspend fun runOcr(byteBuffer: java.nio.ByteBuffer, width: Int, height: Int, roi: Rect): OcrResult {
        // Gerçek ML Kit Motoru (OcrProcessor)
        val rawResult = ocrProcessor.process(byteBuffer, width, height)
        
        // ML Kit'ten gelen kutular kırpılmış resme göredir.
        // Onları ekrandaki orijinal ROI'ye (Absolute Coordinates) kaydırmamız (offset) gerekir.
        val adjustedBoxes = rawResult.boundingBoxes.map { box ->
            android.graphics.RectF(
                box.left + roi.left,
                box.top + roi.top,
                box.right + roi.left,
                box.bottom + roi.top
            )
        }
        
        return rawResult.copy(boundingBoxes = adjustedBoxes)
    }
}
