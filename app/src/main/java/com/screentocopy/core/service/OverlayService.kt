package com.screentocopy.core.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.screentocopy.core.action.EditIntentExecutor
import com.screentocopy.core.action.SelectionAction
import com.screentocopy.core.engine.ClipboardEngine
import com.screentocopy.core.observability.StsLogger
import com.screentocopy.core.selection.SelectionEngineView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 🎨 Overlay Manager Service
 *
 * Orchestrates the full selection → copy/edit pipeline.
 *
 * Execution order (CRITICAL — do NOT reorder):
 *   1. hideOverlay()                     ← always first (instant visual dismiss)
 *   2. scope.launch { ... }              ← all heavy work off main thread
 *   3. withContext(IO) { crop }          ← bitmap crop on IO
 *   4. clipboardEngine.dispatchSmart()   ← Copy-First model [L10]
 *   5. editExecutor.execute()            ← async, only if EDIT intent [L7]
 *
 * Fixes applied:
 * - [L5]  hideOverlay() always before any bitmap work
 * - [L7]  edit is always async after clipboard write
 * - [L8]  bitmap.recycle() NEVER called (edit apps lazy-load)
 * - [L9]  Adaptive dwell threshold via Watchdog health state (Phase 7)
 * - [L10] Copy-First: clipboard written in both COPY and EDIT paths
 * - [2.3] dispatchSmart runs on Dispatchers.IO — not main thread
 * - [2.6] false-edit-rate counter → adaptive threshold bump
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: SelectionEngineView? = null
    private var mediaProjection: MediaProjection? = null
    private var frozenBitmap: Bitmap? = null

    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var clipboardEngine: ClipboardEngine
    private lateinit var editExecutor: EditIntentExecutor
    private lateinit var watchdog: ServiceWatchdog

    // [Fix 2.6] False-edit-rate tracking for adaptive threshold
    private var editAttempts = 0
    private var falseEdits = 0

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        clipboardEngine = ClipboardEngine(this)
        editExecutor = EditIntentExecutor(this)

        watchdog = ServiceWatchdog(
            context = this,
            scope = scope,
            onStateChanged = { health -> applyWatchdogHealth(health) },
            onPermissionLost = { perm ->
                StsLogger.d("OverlayService: permission lost → $perm")
                hideOverlay()
            }
        )
        watchdog.startMonitoring()

        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "SHOW_OVERLAY" -> {
                val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
                val data = intent.getParcelableExtra<Intent>("data")
                if (resultCode == Activity.RESULT_OK && data != null) {
                    takeScreenshotAndShowOverlay(resultCode, data)
                } else {
                    showOverlay()
                }
            }
            "HIDE_OVERLAY" -> hideOverlay()
        }
        return START_NOT_STICKY
    }

    // ── Overlay lifecycle ─────────────────────────────────────────────────────

    private fun showOverlay() {
        if (overlayView != null) return

        overlayView = SelectionEngineView(this)
        setupSelectionCallback()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        windowManager.addView(overlayView, params)
    }

    private fun hideOverlay() {
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
    }

    // ── Selection callback (Phase 6 — core orchestration) ────────────────────

    private fun setupSelectionCallback() {
        overlayView?.onSelectionResolved = { rect, action ->

            // ═══════════════════════════════════════════════════════════════
            // STEP 1 — Overlay GONE, frame 0. Always first.
            // [L5] User sees instant dismiss before any bitmap work.
            // ═══════════════════════════════════════════════════════════════
            hideOverlay()

            scope.launch {
                val bitmap = frozenBitmap ?: return@launch
                val safeRect = clampRect(rect, bitmap) ?: return@launch

                // ═══════════════════════════════════════════════════════════
                // STEP 2 — Crop on IO thread.
                // Bitmap.createBitmap is a memory allocation — keep off Main.
                // ═══════════════════════════════════════════════════════════
                val cropped = withContext(Dispatchers.IO) {
                    Bitmap.createBitmap(
                        bitmap,
                        safeRect.left, safeRect.top,
                        safeRect.width(), safeRect.height()
                    )
                }

                // ═══════════════════════════════════════════════════════════
                // STEP 3 — Copy-First [L10]: clipboard written in ALL paths.
                // [Fix 2.3] dispatchSmart runs off main thread (OEM-safe).
                // ═══════════════════════════════════════════════════════════
                withContext(Dispatchers.IO) {
                    clipboardEngine.dispatchSmart(null, cropped)
                }

                // ═══════════════════════════════════════════════════════════
                // STEP 4 — Edit async [L7]. Only if EDIT intent AND healthy.
                // Clipboard already written → fallback is a no-op for user.
                // ═══════════════════════════════════════════════════════════
                if (action == SelectionAction.EDIT) {
                    editAttempts++
                    watchdog.reportEvent("EDIT_REQUEST")

                    launch {
                        editExecutor.execute(cropped) {
                            // No edit app found — copy already done, nothing to do.
                            watchdog.reportEvent("EDIT_FALLBACK")
                            recordFalseEdit()   // [Fix 2.6] counts as false positive
                        }
                    }
                }
                // [L8] cropped.recycle() intentionally omitted
            }
        }
    }

    // ── [Phase 7] Adaptive dwell threshold via Watchdog health ───────────────

    /**
     * Called whenever ServiceWatchdog detects a health state change.
     * Maps health → dwell threshold on SelectionEngineView [L9].
     *
     * HEALTHY  →  80ms  (normal)
     * DEGRADED → 150ms  (frame drops detected — raise bar for edit intent)
     * ZOMBIE   →  ∞     (edit completely disabled, copy-only mode)
     */
    private fun applyWatchdogHealth(health: ServiceHealth) {
        val thresholdMs = when (health) {
            ServiceHealth.HEALTHY  -> 80L
            ServiceHealth.DEGRADED -> 150L
            ServiceHealth.ZOMBIE   -> Long.MAX_VALUE
        }
        overlayView?.editDwellThresholdMs = thresholdMs
        StsLogger.d("OverlayService: dwell threshold → ${thresholdMs}ms (health=$health)")
    }

    // ── [Fix 2.6] False-edit-rate adaptive learning ───────────────────────────

    /**
     * Tracks how often EDIT is triggered but falls back (no edit app or user
     * immediately returns). If rate > 20%, bump the dwell threshold by 20ms
     * so the user has to hold longer to trigger edit.
     *
     * Future: replace with activity lifecycle observation for true return-rate tracking.
     */
    private fun recordFalseEdit() {
        falseEdits++
        val rate = falseEdits.toFloat() / editAttempts.coerceAtLeast(1)
        if (rate > 0.2f && editAttempts >= 5) {
            val current = overlayView?.editDwellThresholdMs ?: 80L
            val bumped = (current + 20L).coerceAtMost(300L)   // cap at 300ms
            overlayView?.editDwellThresholdMs = bumped
            StsLogger.d("AdaptiveWatchdog: falseEditRate=${"%.2f".format(rate)}, threshold bumped → ${bumped}ms")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Clamp user rect to bitmap bounds. Returns null if result is degenerate.
     */
    private fun clampRect(rect: Rect, bitmap: Bitmap): Rect? {
        val clamped = Rect(
            rect.left.coerceAtLeast(0),
            rect.top.coerceAtLeast(0),
            rect.right.coerceAtMost(bitmap.width),
            rect.bottom.coerceAtMost(bitmap.height)
        )
        return if (clamped.width() > 0 && clamped.height() > 0) clamped else null
    }

    // ── Screenshot + overlay ──────────────────────────────────────────────────

    private fun takeScreenshotAndShowOverlay(resultCode: Int, data: Intent) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        val metrics = resources.displayMetrics
        val imageReader = ImageReader.newInstance(
            metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 1
        )

        val virtualDisplay = mediaProjection?.createVirtualDisplay(
            "STS_Capture",
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        )

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * metrics.widthPixels

                val bitmapWidth = metrics.widthPixels + rowPadding / pixelStride
                val raw = Bitmap.createBitmap(
                    bitmapWidth, metrics.heightPixels, Bitmap.Config.ARGB_8888
                )
                raw.copyPixelsFromBuffer(buffer)

                val cropped = Bitmap.createBitmap(raw, 0, 0, metrics.widthPixels, metrics.heightPixels)
                frozenBitmap = cropped

                Handler(Looper.getMainLooper()).post {
                    showOverlay()
                    overlayView?.background = BitmapDrawable(resources, cropped)
                }

                image.close()
                virtualDisplay?.release()
                mediaProjection?.stop()
            }
        }, Handler(Looper.getMainLooper()))
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "sts_overlay_channel",
                "STS Engine Active",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, "sts_overlay_channel")
            .setContentTitle("ScreenToCopy Active")
            .setContentText("Ready to capture screen.")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .build()

        startForeground(1, notification)
    }

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
