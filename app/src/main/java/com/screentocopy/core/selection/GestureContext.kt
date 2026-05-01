package com.screentocopy.core.selection

import android.graphics.PointF
import android.graphics.RectF

/**
 * 📊 GestureContext — Rich gesture metadata captured at ACTION_UP.
 *
 * Currently unused at runtime; reserved for future ML-based intent prediction
 * and adaptive threshold tuning (false-edit-rate learning).
 */
data class GestureContext(
    val selectionBounds: RectF,
    val fingerPath: List<PointF>,
    val releasePoint: PointF,
    val dwellTimeInCenter: Long,
    val velocityProfile: List<Float>
)
