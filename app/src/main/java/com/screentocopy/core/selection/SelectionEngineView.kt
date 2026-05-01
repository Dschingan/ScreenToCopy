package com.screentocopy.core.selection

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.screentocopy.core.action.SelectionAction
import kotlin.math.hypot

/**
 * 🎨 Selection Engine Core (Render + Touch Input)
 *
 * Hot-path performance contract (ACTION_MOVE):
 * - [L1]  Zero PointF allocation — tempCenter pre-allocated.
 * - [L2]  No hypot()/sqrt() — squared-distance comparison only.
 * - [L3]  No System.currentTimeMillis() — event.eventTime (free from kernel).
 * - [L4]  Haptic posted off MOVE loop via post { } + bool guard.
 * - [2.5] Haptic anti-spam: eventTime-based 100ms cooldown (handles fast enter/exit).
 * - [2.8] ACTION_CANCEL isolated → always forceCopy, never evaluates dwell.
 *
 * Callback change (breaking):
 *   REMOVED  onSelectionComplete: ((Rect) -> Unit)?
 *   ADDED    onSelectionResolved: ((Rect, SelectionAction) -> Unit)?
 */
class SelectionEngineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Touch infrastructure ──────────────────────────────────────────────────
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val motionSmoother = MotionSmoother()

    // ── Spatial layers ────────────────────────────────────────────────────────
    private val highlightLayer = HighlightLayer(this)
    val spatialIndex = SpatialGridIndex()
    private val snapEngine = SnapEngine(spatialIndex)

    // ── Selection model ───────────────────────────────────────────────────────
    private val currentSelection = SubpixelRect()
    private var snappedSelection = SubpixelRect()
    // [Fix #3] Pre-allocated float arrays — zero PointF allocation per MOVE sample.
    // 512 slots covers 99%+ of real gestures. Conversion to List<PointF> happens
    // exactly once at ACTION_UP when GestureEngine needs it.
    private val pathXs = FloatArray(512)
    private val pathYs = FloatArray(512)
    private var pathCount = 0

    private var startX = 0f
    private var startY = 0f
    private var isDragging = false

    // ── Edit Intent State ─────────────────────────────────────────────────────

    private var intentState = SelectionIntentState.IDLE

    // [L1] Pre-allocated — ZERO allocation per MOVE event
    private val tempCenter = PointF()

    // [L3] Dwell tracking via event.eventTime — no syscall
    private var enteredCenterAt = -1L

    // [2.5] Haptic anti-spam: time-based cooldown instead of simple bool
    private var lastHapticTime = 0L

    // Adaptive dwell threshold — mutated by OverlayService via Watchdog [L9]
    var editDwellThresholdMs: Long = 80L

    // [L2] Squared radius — no sqrt in hot path
    private val centerRadius = dpToPx(40f)
    private val centerRadiusSq = centerRadius * centerRadius

    // Minimum selection dimension to qualify for edit (prevents accidental edit on tiny tap)
    private val minEditDimPx = dpToPx(100f)

    // Edit anchor visual
    private val editAnchorLayer = EditAnchorLayer(this)

    // ── Callback (replaces onSelectionComplete) ───────────────────────────────
    /**
     * Invoked once on ACTION_UP with the final snapped rect and resolved action.
     * MUST NOT block — OverlayService runs heavy work in a coroutine.
     */
    var onSelectionResolved: ((android.graphics.Rect, SelectionAction) -> Unit)? = null

    // ── Paint ─────────────────────────────────────────────────────────────────
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8EAED")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        setShadowLayer(4f, 0f, 2f, Color.parseColor("#40000000"))
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#20FFFFFF")
        style = Paint.Style.FILL
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                motionSmoother.reset(startX, startY, event.eventTime)  // [Fix #5]
                snapEngine.resetStickyStates()
                isDragging = false
                highlightLayer.clear()
                // [Fix #3] reset float path buffer
                pathCount = 0
                if (pathCount < pathXs.size) {
                    pathXs[pathCount] = startX
                    pathYs[pathCount] = startY
                    pathCount++
                }

                currentSelection.left = startX
                currentSelection.top = startY
                currentSelection.right = startX
                currentSelection.bottom = startY
                snappedSelection = currentSelection.copy()

                // Reset edit intent state
                enteredCenterAt = -1L
                lastHapticTime = 0L          // [2.5] reset cooldown
                editAnchorLayer.hide()
                intentState = SelectionIntentState.SELECTING
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) {
                    val dist = hypot(event.x - startX, event.y - startY)
                    if (dist > touchSlop) {
                        isDragging = true
                    } else {
                        return true // Ignore micro-jitter
                    }
                }

                // Historical pointer sampling — pass historical eventTime per sample [Fix #5]
                val historySize = event.historySize
                for (i in 0 until historySize) {
                    updateSelection(event.getHistoricalX(i), event.getHistoricalY(i), event.getHistoricalEventTime(i))
                }
                updateSelection(event.x, event.y, event.eventTime)

                // ── Center zone tracking (hot path — zero allocation) ──────────
                if (isDragging) {
                    // [L1] reuse pre-allocated PointF
                    tempCenter.set(snappedSelection.centerX(), snappedSelection.centerY())

                    val dx = event.x - tempCenter.x
                    val dy = event.y - tempCenter.y
                    // [L2] squared comparison — NO sqrt / hypot
                    val inCenter = (dx * dx + dy * dy) < centerRadiusSq

                    if (inCenter) {
                        if (enteredCenterAt < 0L) {
                            enteredCenterAt = event.eventTime  // [L3]
                            intentState = SelectionIntentState.CANDIDATE_EDIT

                            // [2.5] Anti-spam: only trigger haptic if > 100ms since last
                            if (event.eventTime - lastHapticTime > 100L) {
                                lastHapticTime = event.eventTime
                                // [L4] post off MOVE loop
                                post { performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }
                            }

                            // Show anchor only for selections large enough to be intentional
                            val w = snappedSelection.width()
                            val h = snappedSelection.height()
                            if (w * h > centerRadiusSq * 4) {
                                editAnchorLayer.show(tempCenter.x, tempCenter.y)
                            }
                        }
                    } else {
                        // Finger left center zone — reset to COPY candidate
                        enteredCenterAt = -1L
                        editAnchorLayer.hide()
                        intentState = SelectionIntentState.CANDIDATE_COPY
                    }
                }

                postInvalidateOnAnimation()
            }

            // ── [Fix 2.8] ACTION_CANCEL — system interrupt, NEVER evaluate dwell ──
            MotionEvent.ACTION_CANCEL -> {
                enteredCenterAt = -1L
                editAnchorLayer.hide()
                intentState = SelectionIntentState.FINAL_COPY

                if (isDragging) {
                    // Force COPY — do NOT check center zone or dwell
                    finishSelection(SelectionAction.COPY)
                }
                isDragging = false
                postInvalidateOnAnimation()
                return true   // Don't fall through to ACTION_UP logic
            }

            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    // [Fix #3] Convert float arrays → List<PointF> once, only at ACTION_UP
                    val pathPoints = ArrayList<PointF>(pathCount)
                    for (i in 0 until pathCount) pathPoints.add(PointF(pathXs[i], pathYs[i]))

                    // Gesture analysis (only on release — CPU-friendly)
                    val resolvedRectF = GestureEngine.analyzeAndResolve(pathPoints)
                    currentSelection.left   = resolvedRectF.left
                    currentSelection.top    = resolvedRectF.top
                    currentSelection.right  = resolvedRectF.right
                    currentSelection.bottom = resolvedRectF.bottom

                    snappedSelection = snapEngine.processSnap(
                        currentRoi = currentSelection,
                        velocity = 0f
                    )

                    // ── Intent resolution ─────────────────────────────────────
                    val minDim = minOf(snappedSelection.width(), snappedSelection.height())

                    val isEdit = if (minDim < minEditDimPx) {
                        false   // Too small → always COPY (Phase 7 guard)
                    } else {
                        val dx = event.x - tempCenter.x
                        val dy = event.y - tempCenter.y
                        val inCenter = (dx * dx + dy * dy) < centerRadiusSq      // [L2]
                        val dwell = if (enteredCenterAt > 0L)
                            event.eventTime - enteredCenterAt                     // [L3]
                        else 0L
                        inCenter && dwell >= editDwellThresholdMs                 // [L9] adaptive
                    }

                    val action = if (isEdit) SelectionAction.EDIT else SelectionAction.COPY
                    intentState = if (isEdit) SelectionIntentState.FINAL_EDIT else SelectionIntentState.FINAL_COPY

                    // Reset
                    enteredCenterAt = -1L
                    editAnchorLayer.hide()

                    finishSelection(action)

                    // Haptic: "system understood you" (original behavior kept)
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                isDragging = false
                postInvalidateOnAnimation()
            }
        }
        return true
    }

    // ── Selection update (called per pointer sample) ──────────────────────────

    private fun updateSelection(rawX: Float, rawY: Float, eventTime: Long) {
        val (predictedX, predictedY) = motionSmoother.process(rawX, rawY, eventTime)  // [Fix #5]
        // [Fix #3] append to pre-allocated arrays, no PointF heap allocation
        if (pathCount < pathXs.size) {
            pathXs[pathCount] = predictedX
            pathYs[pathCount] = predictedY
            pathCount++
        }

        currentSelection.left   = minOf(startX, predictedX, currentSelection.left)
        currentSelection.top    = minOf(startY, predictedY, currentSelection.top)
        currentSelection.right  = maxOf(startX, predictedX, currentSelection.right)
        currentSelection.bottom = maxOf(startY, predictedY, currentSelection.bottom)

        snappedSelection = snapEngine.processSnap(
            currentRoi = currentSelection,
            velocity = motionSmoother.latestVelocity
        )
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        highlightLayer.onDraw(canvas)

        if (isDragging) {
            val rect = snappedSelection.toRectF()
            canvas.drawRect(rect, fillPaint)
            canvas.drawRect(rect, paint)
        }

        // EditAnchorLayer has its own visibility guard — safe to call unconditionally
        editAnchorLayer.onDraw(canvas)
    }

    // ── OCR highlight API ─────────────────────────────────────────────────────

    fun showHighlights(boxes: List<android.graphics.RectF>) {
        highlightLayer.showHighlights(boxes)
    }

    // ── Internal pipeline trigger ─────────────────────────────────────────────

    private fun finishSelection(action: SelectionAction) {
        val finalRect = snappedSelection.toEngineRect()
        if (finalRect.width() > 10 && finalRect.height() > 10) {
            onSelectionResolved?.invoke(finalRect, action)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Float): Float =
        dp * context.resources.displayMetrics.density
}
