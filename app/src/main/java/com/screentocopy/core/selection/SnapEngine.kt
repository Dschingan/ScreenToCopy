package com.screentocopy.core.selection

import kotlin.math.abs

/**
 * 🧲 Snap Engine (Intent-aware selection correction)
 * 
 * ROI sınırlarını metin kenarlarına yapıştırır. Zıplayan UI hissini (Jumping UI)
 * "Sticky Snap" ve "Lerp Easing" ile çözer.
 */
class SnapEngine(private val spatialIndex: SpatialGridIndex) {

    // Jumping UI engelleme: En son snap'lenmiş koordinatlar
    private var lastSnappedLeft: Float? = null
    private var lastSnappedRight: Float? = null
    private var lastSnappedTop: Float? = null
    private var lastSnappedBottom: Float? = null

    // Easing için
    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction
    }

    /**
     * @param currentRoi Kullanıcının çizdiği raw (ama smooth edilmiş) subpixel ROI
     * @param velocity O anki çizim hızı (px/ms)
     * @return Snap edilmiş yeni SubpixelRect
     */
    fun processSnap(
        currentRoi: SubpixelRect,
        velocity: Float
    ): SubpixelRect {
        
        // 🔥 Dinamik Eşik (Dynamic Threshold)
        val threshold = when {
            velocity > 2.0f -> 20f   // Hızlı hareket = Az snap
            velocity > 0.5f -> 35f
            else -> 50f              // Yavaş hareket = Güçlü snap
        }

        // Objeden kurtulmak için uygulanması gereken güç (Histeresis)
        val releaseThreshold = threshold * 1.5f

        val searchArea = currentRoi.toRectF()
        // Arama alanını eşik kadar genişlet
        searchArea.inset(-threshold, -threshold)
        
        val candidates = spatialIndex.query(searchArea)
        
        if (candidates.isEmpty()) {
            resetStickyStates()
            return currentRoi 
        }

        val xEdges = mutableListOf<Float>()
        val yEdges = mutableListOf<Float>()

        for (c in candidates) {
            xEdges.add(c.left)
            xEdges.add(c.right)
            yEdges.add(c.top)
            yEdges.add(c.bottom)
        }

        // X ve Y Axis Separation
        val targetLeft = stickySnapEdge(currentRoi.left, xEdges, threshold, releaseThreshold, lastSnappedLeft)
        val targetRight = stickySnapEdge(currentRoi.right, xEdges, threshold, releaseThreshold, lastSnappedRight)
        val targetTop = stickySnapEdge(currentRoi.top, yEdges, threshold, releaseThreshold, lastSnappedTop)
        val targetBottom = stickySnapEdge(currentRoi.bottom, yEdges, threshold, releaseThreshold, lastSnappedBottom)

        // Durum (Sticky State) Güncellemesi
        lastSnappedLeft = if (targetLeft != currentRoi.left) targetLeft else null
        lastSnappedRight = if (targetRight != currentRoi.right) targetRight else null
        lastSnappedTop = if (targetTop != currentRoi.top) targetTop else null
        lastSnappedBottom = if (targetBottom != currentRoi.bottom) targetBottom else null

        // 🎨 Google Lens Hissi: Snap birden zıplamasın, oraya doğru süzülsün
        return SubpixelRect(
            left = lerp(currentRoi.left, targetLeft, 0.4f),
            top = lerp(currentRoi.top, targetTop, 0.4f),
            right = lerp(currentRoi.right, targetRight, 0.4f),
            bottom = lerp(currentRoi.bottom, targetBottom, 0.4f)
        )
    }

    private fun stickySnapEdge(
        edge: Float,
        candidates: List<Float>,
        threshold: Float,
        releaseThreshold: Float,
        lastSnapped: Float?
    ): Float {
        // Sticky Snap: Eğer bir yere zaten yapışmışsak, 
        // daha yüksek bir release threshold'u aşılana kadar orayı bırakma.
        if (lastSnapped != null) {
            if (abs(edge - lastSnapped) < releaseThreshold) {
                return lastSnapped
            }
        }

        var best = edge
        var minDist = threshold

        for (c in candidates) {
            val dist = abs(edge - c)
            if (dist < minDist) {
                minDist = dist
                best = c
            }
        }

        return best
    }

    fun resetStickyStates() {
        lastSnappedLeft = null
        lastSnappedRight = null
        lastSnappedTop = null
        lastSnappedBottom = null
    }
}
