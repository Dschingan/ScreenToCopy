package com.screentocopy.core.selection

import android.graphics.RectF

/**
 * ⚡ Grid-based Spatial Index
 * O(n) tarama problemini yok edip, O(1)'e yakın hizalama performansı sunar.
 */
class SpatialGridIndex(private val cellSize: Int = 100) {

    // [Fix #9] Long key avoids Pair allocation + Integer autoboxing per lookup
    private val grid = HashMap<Long, MutableList<RectF>>()

    private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    fun clear() {
        grid.clear()
    }

    fun insert(rect: RectF) {
        val startX = (rect.left / cellSize).toInt()
        val endX = (rect.right / cellSize).toInt()
        val startY = (rect.top / cellSize).toInt()
        val endY = (rect.bottom / cellSize).toInt()

        for (x in startX..endX) {
            for (y in startY..endY) {
                grid.getOrPut(key(x, y)) { mutableListOf() }.add(rect)
            }
        }
    }

    fun query(area: RectF): List<RectF> {
        val results = mutableSetOf<RectF>()

        val startX = (area.left / cellSize).toInt()
        val endX = (area.right / cellSize).toInt()
        val startY = (area.top / cellSize).toInt()
        val endY = (area.bottom / cellSize).toInt()

        for (x in startX..endX) {
            for (y in startY..endY) {
                grid[key(x, y)]?.let { results.addAll(it) }
            }
        }

        return results.toList()
    }
}
