package com.screentocopy.core.selection

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * 🎨 Selection Engine Core (Render Layer & Touch Input Layer)
 * 
 * - Jitter koruması (Touch Slop)
 * - Historical Sampling (Yüksek Sample Rate)
 * - VSYNC Senkronizasyon (postInvalidateOnAnimation)
 */
class SelectionEngineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 1. Touch Slop (İlk dokunuştaki micro-titremeyi yok et)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    
    // 2. Motion Filter Layer
    private val motionSmoother = MotionSmoother()

    // 🚀 Highlight Layer (Instant Vision Feedback)
    private val highlightLayer = HighlightLayer(this)
    
    // 3. Snap Engine (O(1) Spatial Index tabanlı)
    val spatialIndex = SpatialGridIndex()
    private val snapEngine = SnapEngine(spatialIndex)
    
    // 4. Subpixel ROI Model
    private val currentSelection = SubpixelRect()
    private var snappedSelection = SubpixelRect()

    // 5. Callback
    var onSelectionComplete: ((android.graphics.Rect) -> Unit)? = null

    // 🔥 5. Gesture Path Data
    private val pathPoints = mutableListOf<android.graphics.PointF>()

    private var startX = 0f
    private var startY = 0f
    private var isDragging = false

    // Google Lens hissi veren Render Stili
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8EAED") // Hafif parlak beyazımsı kenar
        style = Paint.Style.STROKE
        strokeWidth = 6f
        setShadowLayer(4f, 0f, 2f, Color.parseColor("#40000000")) // Magnetic derinlik
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#20FFFFFF") // Saydam beyaz iç dolgu
        style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                motionSmoother.reset(startX, startY)
                snapEngine.resetStickyStates()
                isDragging = false
                
                // Ekranı temizle
                highlightLayer.clear()
                
                pathPoints.clear()
                pathPoints.add(android.graphics.PointF(startX, startY))

                currentSelection.left = startX
                currentSelection.top = startY
                currentSelection.right = startX
                currentSelection.bottom = startY
                snappedSelection = currentSelection.copy()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) {
                    val dist = hypot(event.x - startX, event.y - startY)
                    if (dist > touchSlop) {
                        isDragging = true // Slop aşıldı, gerçek çizim başladı
                    } else {
                        return true // Ignore micro-jitter
                    }
                }

                // 🔥 Pointer Sampling
                val historySize = event.historySize
                for (i in 0 until historySize) {
                    val hX = event.getHistoricalX(i)
                    val hY = event.getHistoricalY(i)
                    updateSelection(hX, hY)
                }
                
                updateSelection(event.x, event.y)
                postInvalidateOnAnimation()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    // 🧠 1. Gesture Analysis (Sadece parmak kalkınca çalışır - CPU dostu)
                    val resolvedRectF = GestureEngine.analyzeAndResolve(pathPoints)
                    
                    // 🧠 2. Resolved Shape'i SubpixelRect'e koy
                    currentSelection.left = resolvedRectF.left
                    currentSelection.top = resolvedRectF.top
                    currentSelection.right = resolvedRectF.right
                    currentSelection.bottom = resolvedRectF.bottom

                    // 🧲 3. Gesture Sonrası Snap (YES, tekrar!)
                    // Karalama (scribble) yapsa bile metne mükemmel yapışır
                    snappedSelection = snapEngine.processSnap(
                        currentRoi = currentSelection,
                        velocity = 0f // Final snap, hız sıfır kabul edilebilir (güçlü yapışma)
                    )

                    // 🚀 4. Motor'a gönder
                    triggerZeroLatencyPipeline(snappedSelection.toEngineRect())
                    
                    // 💡 Haptic Feedback: "Sistem seni anladı"
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
                isDragging = false
                postInvalidateOnAnimation()
            }
        }
        return true
    }

    private fun updateSelection(rawX: Float, rawY: Float) {
        // 1. Smoothing + Prediction
        val (predictedX, predictedY) = motionSmoother.process(rawX, rawY)
        
        pathPoints.add(android.graphics.PointF(predictedX, predictedY))

        // Lightweight Preview: Move sırasında sadece bounding box çiziyoruz.
        // Full analiz (Circle/Scribble) Action_UP'da yapılacak.
        currentSelection.left = minOf(startX, predictedX, currentSelection.left)
        currentSelection.top = minOf(startY, predictedY, currentSelection.top)
        currentSelection.right = maxOf(startX, predictedX, currentSelection.right)
        currentSelection.bottom = maxOf(startY, predictedY, currentSelection.bottom)

        // 2. Intent-aware Snapping (Magnetic Edges) (Live Preview sırasında da çalışır)
        snappedSelection = snapEngine.processSnap(
            currentRoi = currentSelection,
            velocity = motionSmoother.latestVelocity
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // 1. Highlight Layer her zaman altta (veya üstte, tercihe bağlı)
        highlightLayer.onDraw(canvas)

        if (isDragging) {
            // Çizim raw input'a değil, snaplenmiş ve easing uygulanmış modele göre yapılır
            val rect = snappedSelection.toRectF()
            canvas.drawRect(rect, fillPaint)
            canvas.drawRect(rect, paint)
        }
    }

    /**
     * OCR sonuçları geldiğinde dışarıdan çağrılır
     */
    fun showHighlights(boxes: List<android.graphics.RectF>) {
        highlightLayer.showHighlights(boxes)
    }

    private fun triggerZeroLatencyPipeline(engineRect: android.graphics.Rect) {
        // OCR pipeline veya Selection bitti
        finishSelection()
    }

    private fun finishSelection() {
        val finalRect = snappedSelection.toEngineRect()
        if (finalRect.width() > 10 && finalRect.height() > 10) {
            onSelectionComplete?.invoke(finalRect)
        }
    }
}
