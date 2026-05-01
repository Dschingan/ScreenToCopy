package com.screentocopy.core.selection

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 🎯 EditAnchorLayer — Visual indicator shown when finger enters the center zone.
 *
 * Performance contract:
 * - [Fix 2.1] animator is a class-level field — ZERO allocation per show() call.
 *   Previous anti-pattern: ValueAnimator.ofFloat(...) created inside show() = alloc + jitter.
 * - Paint & RectF pre-allocated at init — no allocation in onDraw (hot path).
 * - show() is idempotent: calling it while already visible is a no-op.
 */
class EditAnchorLayer(private val parentView: View) {

    // ── Render state ──────────────────────────────────────────────────────────
    private var alpha = 0f
    private var scale = 0.8f
    private var cx = 0f
    private var cy = 0f
    private var visible = false

    // ── Pre-allocated draw objects (ZERO allocation in onDraw) ────────────────
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1AFFFFFF")   // subtle white fill
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80E8EAED")   // soft border
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val drawRect = RectF()   // allocation-free — reused every frame

    // ── [Fix 2.1] Reusable animator — allocated ONCE at class init ────────────
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 80
        interpolator = DecelerateInterpolator()
        addUpdateListener { v ->
            val value = v.animatedValue as Float
            alpha = value
            scale = 0.8f + 0.2f * value
            parentView.invalidate()
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Show the anchor at (x, y).
     * Idempotent: if already visible at same position, does nothing.
     */
    fun show(x: Float, y: Float) {
        cx = x
        cy = y
        if (visible) return        // already showing — don't restart animation
        visible = true
        if (!animator.isRunning) animator.start()
    }

    /**
     * Hide the anchor immediately.
     * Cancels in-flight animation to prevent ghost frames.
     */
    fun hide() {
        animator.cancel()
        visible = false
        alpha = 0f
        parentView.invalidate()
    }

    // ── Render ────────────────────────────────────────────────────────────────

    fun onDraw(canvas: Canvas) {
        if (!visible || alpha <= 0f) return

        paint.alpha = (255 * alpha).toInt()
        strokePaint.alpha = (200 * alpha).toInt()

        val r = 28f * scale
        drawRect.set(cx - r, cy - r, cx + r, cy + r)

        canvas.drawOval(drawRect, paint)
        canvas.drawOval(drawRect, strokePaint)
    }
}
