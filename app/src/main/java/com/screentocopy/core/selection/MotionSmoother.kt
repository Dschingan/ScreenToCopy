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

    fun reset(x: Float, y: Float) {
        lastX = x
        lastY = y
        latestVelocity = 0f
        lastTime = System.currentTimeMillis()
    }

    /**
     * @return Pair(PredictedX, PredictedY)
     */
    fun process(x: Float, y: Float): Pair<Float, Float> {
        val currentTime = System.currentTimeMillis()
        val dt = (currentTime - lastTime).coerceAtLeast(1) // 0'a bölme hatasını önle
        lastTime = currentTime

        val dx = x - lastX
        val dy = y - lastY
        val velocity = hypot(dx, dy) / dt // piksel/milisaniye

        // Velocity-aware smoothing (Exponential Smoothing)
        // Hızlı hareket = daha az smoothing (alpha yüksek) -> Lag hissi olmaz
        // Yavaş hareket = daha fazla smoothing (alpha düşük) -> İnce titreşimler kesilir
        latestVelocity = velocity

        val alpha = when {
            velocity > 2.0f -> 0.4f
            velocity > 0.5f -> 0.25f
            else -> 0.15f
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
