package com.screentocopy.core.observability

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 🛠️ Pro Debug Overlay
 * 
 * Production build'te tamamen gizlenir.
 * Sıfır allocation prensibiyle çalışır (String.format yerine StringBuilder).
 */
class DebugOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000") // Yarı saydam siyah
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        textSize = 32f
        typeface = android.graphics.Typeface.MONOSPACE
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }

    // stringBuilder removed — onDraw draws each line directly (Fix #8)

    init {
        // Sadece debug modunda görünür yap (StsLogger isDebugBuild flag'ine bağlı)
        visibility = if (StsLogger.isDebugBuild) View.VISIBLE else View.GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!StsLogger.isDebugBuild) return

        val dropRate = MetricsCollector.getDropRatePercentage()
        val ocrTime = MetricsCollector.getAverageOcrTimeMs()
        val mode = AutoRecoveryEngine.currentMode.name

        // Renk Kodlaması: Sağlık durumuna göre text rengi değişir
        textPaint.color = when (AutoRecoveryEngine.currentState) {
            AutoRecoveryEngine.HealthState.HEALTHY -> Color.GREEN
            AutoRecoveryEngine.HealthState.DEGRADED -> Color.YELLOW
            AutoRecoveryEngine.HealthState.CRITICAL -> Color.RED
        }

        // [Fix #8] Draw lines directly — no split() → no List<String> allocation per frame
        canvas.drawRect(10f, 10f, 350f, 150f, bgPaint)
        var yPos = 50f
        canvas.drawText("MODE: $mode", 20f, yPos, textPaint)
        yPos += textPaint.textSize + 8f
        canvas.drawText("OCR:  ${ocrTime}ms", 20f, yPos, textPaint)
        yPos += textPaint.textSize + 8f
        canvas.drawText("DROP: ${(dropRate * 10).toInt() / 10f}%", 20f, yPos, textPaint)
    }

    fun updateMetrics() {
        if (StsLogger.isDebugBuild) {
            invalidate() // Sadece değerler değiştiğinde tetiklenir
        }
    }
}
