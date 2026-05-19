package com.screentocopy.core.selection

import kotlin.math.hypot

/**
 * ⚡ Motion Smoothing & Latency Compensation (PRO LEVEL)
 * Jitter ve micro-stutter'ları çözer.
 */
class MotionSmoother {
    private var lastX = 0f
    private var lastY = 0f
    private var lastTime = 0L
    var latestVelocity = 0f

    fun reset(x: Float, y: Float, eventTime: Long) {
        lastX = x
        lastY = y
        latestVelocity = 0f
        lastTime = eventTime  // [Fix #5] use kernel-provided eventTime, no syscall
    }

    /**
     * @return Pair(PredictedX, PredictedY)
     */
    fun process(x: Float, y: Float, eventTime: Long): Pair<Float, Float> {
        val currentTime = eventTime   // [Fix #5] free from MotionEvent, no syscall
        val dt = (currentTime - lastTime).coerceAtLeast(1)
        lastTime = currentTime

        val dx = x - lastX
        val dy = y - lastY
        val velocity = hypot(dx, dy) / dt // piksel/milisaniye

        // Velocity-aware smoothing (Exponential Smoothing)
        // Hızlı hareket = daha az smoothing (alpha yüksek) -> Lag hissi olmaz
        // Yavaş hareket = daha fazla smoothing (alpha düşük) -> İnce titreşimler kesilir
        latestVelocity = velocity

        // [BugFix] Alpha values rebalanced after [Fix #5] (eventTime-based dt).
        // Old System.currentTimeMillis() code had dt≈1ms (inflated), which pushed velocity
        // above 2.0 almost always → alpha stayed at 0.4 (light smoothing).
        // Correct kernel dt values yield lower velocity readings → old alpha 0.15 caused
        // extreme coordinate shrinkage. Raised to preserve true selection extents.
        val alpha = when {
            velocity > 2.0f -> 0.85f   // Fast drag: near-raw (was 0.40)
            velocity > 0.5f -> 0.70f   // Medium: light smoothing (was 0.25)
            else            -> 0.55f   // Slow/tap: gentle smoothing (was 0.15 — too aggressive)
        }


        val smoothedX = lastX + alpha * (x - lastX)
        val smoothedY = lastY + alpha * (y - lastY)

        // Latency Compensation: ~16ms (1 VSYNC frame) geleceği tahmin et
        val velocityX = (smoothedX - lastX) / dt
        val velocityY = (smoothedY - lastY) / dt
        
        val predictedX = smoothedX + velocityX * 16f
        val predictedY = smoothedY + velocityY * 16f

        lastX = smoothedX
        lastY = smoothedY

        return Pair(predictedX, predictedY)
    }
}
