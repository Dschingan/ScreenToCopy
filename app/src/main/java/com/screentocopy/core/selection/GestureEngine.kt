package com.screentocopy.core.selection

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.hypot

enum class GestureType { CIRCLE, SCRIBBLE, FREEFORM }

/**
 * 🧠 Gesture Engine (Intent Inference)
 * Şekil tanıma değil, kullanıcının neyi seçmek istediğini anlama (intent) motoru.
 * 
 * CPU'yu korumak için sadece ACTION_UP anında çalışır.
 */
object GestureEngine {

    /**
     * Touch path'ini alır, niyet çıkarımı yapar (Classify) ve 
     * en optimize kapsayıcı RectF'i döner (Resolve).
     */
    fun analyzeAndResolve(points: List<PointF>): RectF {
        if (points.size < 3) return fallbackRect(points)

        val type = classify(points)
        return resolveShape(points, type)
    }

    private fun classify(points: List<PointF>): GestureType {
        if (isCircle(points)) return GestureType.CIRCLE
        if (isScribble(points)) return GestureType.SCRIBBLE
        return GestureType.FREEFORM
    }

    // 1. CIRCLE DETECTION (Variance-based Heuristic)
    private fun isCircle(points: List<PointF>): Boolean {
        val center = computeCentroid(points)
        val distances = points.map { hypot(it.x - center.x, it.y - center.y) }
        val mean = distances.average()
        
        // Merkezden uzaklıkların varyansı düşükse, noktalar eşit uzaklıktadır -> Daire
        val variance = distances.map { 
            (it - mean.toFloat()) * (it - mean.toFloat()) 
        }.average()

        return variance < (mean * 0.25) // Çapın %25'i kadar sapma toleransı
    }

    // 2. SCRIBBLE DETECTION (Density & Turns)
    private fun isScribble(points: List<PointF>): Boolean {
        val turns = countDirectionChanges(points)
        val rect = fallbackRect(points)
        val area = rect.width() * rect.height()
        
        if (area < 1f) return false
        
        val density = points.size / area
        // Karalamada çok fazla yön değişimi (turn) ve dar alanda çok nokta (density) olur.
        return turns > 5 && density > 0.005f 
    }

    // Basit bir yön değişimi algoritması (Heuristic)
    private fun countDirectionChanges(points: List<PointF>): Int {
        var turns = 0
        var lastCross = 0f
        for (i in 1 until points.size - 1) {
            val prev = points[i - 1]
            val curr = points[i]
            val next = points[i + 1]
            
            val cross = crossProduct(prev, curr, next)
            // Eğer cross çarpımın işareti değişirse dönüş yönü değişmiştir
            if (cross * lastCross < 0) turns++ 
            if (cross != 0f) lastCross = cross
        }
        return turns
    }

    // 3. SHAPE RESOLVER (Asıl Sihir)
    private fun resolveShape(points: List<PointF>, type: GestureType): RectF {
        return when (type) {
            GestureType.CIRCLE -> {
                // CASE 1: CIRCLE -> Minimum Enclosing Circle
                val center = computeCentroid(points)
                val radius = points.maxOf { hypot(it.x - center.x, it.y - center.y) }
                RectF(center.x - radius, center.y - radius, center.x + radius, center.y + radius)
            }
            GestureType.SCRIBBLE -> {
                // CASE 2: SCRIBBLE -> Graham Scan Convex Hull
                // Gereksiz boşlukları atar, karalanan alanın en dar zarfını çizer.
                val hull = convexHull(points)
                fallbackRect(hull) 
            }
            GestureType.FREEFORM -> {
                // CASE 3: FREEFORM -> Standart Bounding Box
                fallbackRect(points)
            }
        }
    }

    private fun computeCentroid(points: List<PointF>): PointF {
        var sumX = 0f
        var sumY = 0f
        points.forEach { sumX += it.x; sumY += it.y }
        return PointF(sumX / points.size, sumY / points.size)
    }

    private fun fallbackRect(points: List<PointF>): RectF {
        if (points.isEmpty()) return RectF()
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        points.forEach {
            if (it.x < minX) minX = it.x
            if (it.y < minY) minY = it.y
            if (it.x > maxX) maxX = it.x
            if (it.y > maxY) maxY = it.y
        }
        return RectF(minX, minY, maxX, maxY)
    }

    /**
     * 🧠 Convex Hull (Graham Scan - Production Baseline)
     * Karalama (Scribble) veya serbest seçimlerde objeyi sıkıca sarmak için.
     */
    private fun convexHull(points: List<PointF>): List<PointF> {
        if (points.size < 3) return points
        // X'e göre, eşitse Y'ye göre sırala
        val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
        
        val hull = mutableListOf<PointF>()
        // Lower Hull
        for (p in sorted) {
            while (hull.size >= 2 && crossProduct(hull[hull.size - 2], hull.last(), p) <= 0) {
                hull.removeAt(hull.size - 1)
            }
            hull.add(p)
        }
        // Upper Hull
        val upperHull = mutableListOf<PointF>()
        for (p in sorted.reversed()) {
            while (upperHull.size >= 2 && crossProduct(upperHull[upperHull.size - 2], upperHull.last(), p) <= 0) {
                upperHull.removeAt(upperHull.size - 1)
            }
            upperHull.add(p)
        }
        
        // Başlangıç ve bitiş noktaları çift sayılmasın diye son elemanları at ve birleştir
        hull.removeAt(hull.size - 1)
        upperHull.removeAt(upperHull.size - 1)
        hull.addAll(upperHull)
        
        return hull
    }

    // Çapraz Çarpım (Cross Product): Z ekseni yönü için
    private fun crossProduct(o: PointF, a: PointF, b: PointF): Float {
        return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    }
}
