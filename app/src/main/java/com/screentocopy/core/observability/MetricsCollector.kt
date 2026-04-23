package com.screentocopy.core.observability

import android.os.Debug

data class FrameMetrics(
    val captureTimeMs: Long,
    val ocrTimeMs: Long,
    val totalLatencyMs: Long
)

/**
 * 📊 Metrics Collector
 * 
 * Sistemin kalbi. Sürekli veri toplar ama asla UI'yi bloklamaz.
 */
object MetricsCollector {
    
    // Basit Moving Average için son N ölçüm
    private val frameMetricsHistory = mutableListOf<FrameMetrics>()
    private const val MAX_HISTORY = 10
    
    var totalFrames = 0L
        private set
    var droppedFrames = 0L
        private set

    var totalOcrAttempts = 0L
        private set
    var successfulOcrCount = 0L
        private set

    fun recordFrameDrop() {
        totalFrames++
        droppedFrames++
        StsLogger.e("Frame Dropped. Drop rate: ${getDropRatePercentage()}%")
    }

    fun recordFrameMetrics(captureTime: Long, ocrTime: Long, totalTime: Long, isOcrSuccess: Boolean) {
        totalFrames++
        totalOcrAttempts++
        if (isOcrSuccess) successfulOcrCount++

        val metrics = FrameMetrics(captureTime, ocrTime, totalTime)
        
        synchronized(frameMetricsHistory) {
            frameMetricsHistory.add(metrics)
            if (frameMetricsHistory.size > MAX_HISTORY) {
                frameMetricsHistory.removeAt(0)
            }
        }
        
        StsLogger.d("Metrics -> Capture: ${captureTime}ms, OCR: ${ocrTime}ms, Total: ${totalTime}ms")
    }

    fun getDropRatePercentage(): Float {
        if (totalFrames == 0L) return 0f
        return (droppedFrames.toFloat() / totalFrames) * 100f
    }

    fun getAverageOcrTimeMs(): Long {
        synchronized(frameMetricsHistory) {
            if (frameMetricsHistory.isEmpty()) return 0
            return frameMetricsHistory.map { it.ocrTimeMs }.average().toLong()
        }
    }

    fun getNativeMemoryUsageMB(): Long {
        return Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
    }
}
