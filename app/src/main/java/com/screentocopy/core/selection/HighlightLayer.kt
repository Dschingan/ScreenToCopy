package com.screentocopy.core.selection

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 🎯 Highlight Layer = "Görsel Doğrulama Motoru"
 * 
 * - UI Thread safe
 * - Object allocation yok (RectF havuzu, Paint reuse)
 * - 16ms altı draw
 */
class HighlightLayer(private val parentView: View) {

    // Paint configs
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val cornerRadius = 14f // 12-16dp arası şık değer
    
    // Animasyon durumları
    private var isAnimating = false
    private var isPulseAnim = false
    private var isFlashAnim = false
    private var currentProgress = 0f
    private var pulseScale = 1f
    private var pulseAlphaOffset = 0
    private var flashAlphaOffset = 0
    
    // Veri
    private val targetBoxes = mutableListOf<RectF>()
    private val startBoxes = mutableListOf<RectF>()
    
    // Allocation-free draw için pool
    private val drawBox = RectF()

    fun showHighlights(rawBoxes: List<RectF>) {
        val mergedBoxes = mergeBoxes(rawBoxes)
        
        targetBoxes.clear()
        startBoxes.clear()
        
        mergedBoxes.forEach { target ->
            targetBoxes.add(RectF(target))
            // Start state: Genişliği %20, boyutu aynı, ortadan başlayarak expand
            val startW = target.width() * 0.2f
            val cx = target.centerX()
            startBoxes.add(RectF(
                cx - startW / 2,
                target.top,
                cx + startW / 2,
                target.bottom
            ))
        }

        isAnimating = true
        isPulseAnim = false
        isFlashAnim = false
        currentProgress = 0f
        pulseScale = 1f
        pulseAlphaOffset = 0
        flashAlphaOffset = 0

        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 120 // 120ms ideal
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { anim ->
            currentProgress = anim.animatedValue as Float
            parentView.invalidate()
        }
        
        // Morph -> Highlight -> Sonra Glow Pulse
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                triggerGlowPulse()
            }
        })
        
        animator.start()
    }

    private fun triggerGlowPulse() {
        isPulseAnim = true
        val pulseAnim = ValueAnimator.ofFloat(0f, 1f)
        pulseAnim.duration = 160
        // Decelerate-Accelerate hissi için custom hesaplama onDraw'da
        pulseAnim.addUpdateListener { anim ->
            val p = anim.animatedValue as Float
            // 0 -> 1 -> 0
            val wave = Math.sin(p * Math.PI).toFloat()
            pulseScale = 1f + (0.03f * wave) // 1.0 -> 1.03 -> 1.0
            pulseAlphaOffset = (30 * wave).toInt() // 120 -> 150 -> 120
            parentView.invalidate()
        }
        pulseAnim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                isPulseAnim = false
            }
        })
        pulseAnim.start()
    }

    /**
     * Copy işlemi tamamlandığında görsel geri bildirim (Finish Layer)
     */
    fun showCopyFlash() {
        isFlashAnim = true
        val flashAnim = ValueAnimator.ofFloat(1f, 0f)
        flashAnim.duration = 200
        flashAnim.addUpdateListener { anim ->
            val p = anim.animatedValue as Float
            flashAlphaOffset = (80 * p).toInt() // Parlak beyaz patlama
            parentView.invalidate()
        }
        flashAnim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                isFlashAnim = false
            }
        })
        flashAnim.start()
    }

    fun onDraw(canvas: Canvas) {
        if (!isAnimating && !isPulseAnim && !isFlashAnim && targetBoxes.isEmpty()) return

        // Base alpha 120, pulse ve flash ile artar
        val baseAlpha = (currentProgress * 120).toInt()
        val finalAlpha = (baseAlpha + pulseAlphaOffset + flashAlphaOffset).coerceIn(0, 255)
        
        highlightPaint.alpha = finalAlpha

        for (i in targetBoxes.indices) {
            val start = startBoxes[i]
            val target = targetBoxes[i]

            // Expand animasyonu: lerpRect(startRect, targetRect, progress)
            lerpRect(start, target, currentProgress, drawBox)

            // Pulse Scale uygulaması
            if (isPulseAnim) {
                val cx = drawBox.centerX()
                val cy = drawBox.centerY()
                val w = drawBox.width() * pulseScale
                val h = drawBox.height() * pulseScale
                drawBox.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            }

            // drawRoundRect ile şık highlight (Google Lens hissi)
            canvas.drawRoundRect(drawBox, cornerRadius, cornerRadius, highlightPaint)
        }
        
        if (currentProgress >= 1f && !isPulseAnim && !isFlashAnim) {
            isAnimating = false
        }
    }

    fun clear() {
        isAnimating = false
        targetBoxes.clear()
        startBoxes.clear()
        parentView.invalidate()
    }

    // Advanced: Merge yakın rect'leri -> satır bazlı highlight
    private fun mergeBoxes(boxes: List<RectF>): List<RectF> {
        if (boxes.isEmpty()) return emptyList()
        
        // Y pozisyonuna göre sırala (satırları bulmak için)
        val sorted = boxes.sortedBy { it.top }
        val merged = mutableListOf<RectF>()
        
        var current = RectF(sorted[0])
        
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            
            // Aynı satırda mı? (Y ekseninde kesişim veya yakınlık var mı)
            val verticalOverlap = current.bottom >= next.top && current.top <= next.bottom
            val horizontalGap = next.left - current.right
            
            if (verticalOverlap && horizontalGap < 50f) { // 50f threshold for space between words
                // Merge
                current.left = minOf(current.left, next.left)
                current.top = minOf(current.top, next.top)
                current.right = maxOf(current.right, next.right)
                current.bottom = maxOf(current.bottom, next.bottom)
            } else {
                merged.add(current)
                current = RectF(next)
            }
        }
        merged.add(current)
        
        return merged
    }

    private fun lerpRect(start: RectF, target: RectF, progress: Float, out: RectF) {
        out.left = start.left + (target.left - start.left) * progress
        out.top = start.top + (target.top - start.top) * progress
        out.right = start.right + (target.right - start.right) * progress
        out.bottom = start.bottom + (target.bottom - start.bottom) * progress
    }
}
