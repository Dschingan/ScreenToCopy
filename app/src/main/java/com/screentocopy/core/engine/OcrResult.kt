package com.screentocopy.core.engine

import android.graphics.RectF

data class OcrResult(
    val text: String,
    val boundingBoxes: List<RectF>
)
