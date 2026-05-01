package com.screentocopy.core.selection

import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 🧠 Subpixel ROI Model
 * ROI asla Integer tutulmaz, aksi takdirde subpixel kayıpları OCR doğruluğunu düşürür.
 */
data class SubpixelRect(
    var left: Float = 0f,
    var top: Float = 0f,
    var right: Float = 0f,
    var bottom: Float = 0f
) {
    /**
     * Render Layer'a geçerken hassasiyeti koruyarak RectF döndürür.
     */
    fun toRectF() = RectF(left, top, right, bottom)
    
    /**
     * Motor'a (Engine) verirken hiçbir veriyi (pixel padding) kaybetmeden kapsayıcı Rect'e çevirir.
     */
    fun toEngineRect() = Rect(
        floor(left).toInt(),
        floor(top).toInt(),
        ceil(right).toInt(),
        ceil(bottom).toInt()
    )

    fun width(): Float = right - left
    fun height(): Float = bottom - top
    fun centerX(): Float = left + (right - left) / 2f
    fun centerY(): Float = top + (bottom - top) / 2f
}
