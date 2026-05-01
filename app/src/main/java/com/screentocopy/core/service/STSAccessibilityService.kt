package com.screentocopy.core.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.screentocopy.core.engine.ClipboardEngine
import com.screentocopy.core.selection.SelectionEngineView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class STSAccessibilityService : AccessibilityService() {

    companion object {
        var instance: STSAccessibilityService? = null
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main)
    private lateinit var watchdog: ServiceWatchdog
    
    // UI Bileşenleri
    private lateinit var windowManager: WindowManager
    private lateinit var clipboardEngine: ClipboardEngine
    private var overlayView: SelectionEngineView? = null

    // State buffer - Olayları geçici tuttuğumuz yer
    private val eventBuffer = mutableListOf<AccessibilityEvent>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i("STSService", "Accessibility Service Connected")
        
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        clipboardEngine = ClipboardEngine(this)

        watchdog = ServiceWatchdog(
            context = this,
            scope = scope,
            onStateChanged = { state -> handleStateTransition(state) },
            onPermissionLost = { permission -> Log.e("STSService", "Lost permission: $permission") }
        )
        watchdog.startMonitoring()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        // Zombi veya Degraded durumunda bile ping atılır.
        // Eğer servis "uyanırsa" ping sayesinde watchdog durumu HEALTHY'e çeker.
        watchdog.ping()

        val currentState = watchdog.state.value

        if (currentState == ServiceHealth.ZOMBIE) {
            // ZOMBIE modunda event'leri tamamen drop et (Recovery Pressure)
            // Sisteme nefes aldırıyoruz.
            return
        }

        if (currentState == ServiceHealth.DEGRADED) {
            // DEGRADED modunda sadece en kritik eventleri (örneğin window state change) al.
            // Scroll veya hover eventlerini drop et (Throttle down).
            if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                return
            }
        }

        // Normal capture pipeline
        processEvent(event)
    }

    override fun onInterrupt() {
        Log.e("STSService", "Accessibility Service Interrupted!")
        // Interrupt geldiğinde de internal state'i sıfırla
        flushInternalState()
    }

    private fun handleStateTransition(state: ServiceHealth) {
        when (state) {
            ServiceHealth.HEALTHY -> {
                Log.i("STSService", "Recovery Loop Success: Service is back to HEALTHY.")
                // Fallback'i kapat, tam OCR + UI moduna geri dön
                restoreNormalPipeline()
            }

            ServiceHealth.DEGRADED -> {
                Log.w("STSService", "Service Degraded! Triggering soft-recovery.")
                // 1. Gereksiz memory yükünü boşalt
                flushInternalState()
                // 2. OCR thread priority'sini düşür veya pause et
                pauseHeavyTasks()
            }

            ServiceHealth.ZOMBIE -> {
                Log.e("STSService", "Service is ZOMBIE! Triggering hard fallback.")
                // 1. Accessibility tabanlı tüm state'i yok et
                flushInternalState()
                
                // 2. FULL FALLBACK MODE: Accessibility'yi tamamen by-pass et
                triggerMediaProjectionFallback()
                
                // Not: Android'de kendi kendimizi 'disableSelf()' ile kapatabiliriz
                // ama bu sefer geri açılamayız. Watchdog'un ping() bekleyip 
                // "Resurrection" yapabilmesi için servisi ayakta tutuyoruz,
                // sadece iş yükünü "0" a indiriyoruz. (Nefes Alma Mekanizması)
            }
        }
    }

    private fun processEvent(event: AccessibilityEvent) {
        // ... Normal pipeline ...
    }

    private fun flushInternalState() {
        // Recovery Pressure'ın en kritik adımı: 
        // Servisin şişmesine neden olan memory/event buffer'ları temizlemek.
        eventBuffer.clear()
        // Gerekirse Bitmap cache'lerini temizle
    }

    private fun pauseHeavyTasks() {
        // OCR veya ML görevlerini askıya al (veya throttle et)
    }

    private fun restoreNormalPipeline() {
        // Fallback modundan çıkış
    }

    private fun triggerMediaProjectionFallback() {}

    /**
     * 🚀 Gerçek "Circle to Search" deneyimi (Ekran Kaydı izni istemez!)
     * MainActivity'den (veya asistan tetiklendiğinde) çağrılır.
     */
    fun triggerScreenshot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hwBitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    val swBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    screenshot.hardwareBuffer.close()
                    
                    if (swBitmap != null) {
                        showOverlay(swBitmap)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e("STSService", "Screenshot failed: $errorCode")
                }
            })
        }
    }

    private fun showOverlay(frozenBitmap: Bitmap) {
        if (overlayView != null) return

        overlayView = SelectionEngineView(this)
        overlayView?.background = BitmapDrawable(resources, frozenBitmap)
        
        overlayView?.onSelectionResolved = { rect, action ->
            hideOverlay()
            scope.launch {
                try {
                    val safeRect = android.graphics.Rect(
                        rect.left.coerceAtLeast(0),
                        rect.top.coerceAtLeast(0),
                        rect.right.coerceAtMost(frozenBitmap.width),
                        rect.bottom.coerceAtMost(frozenBitmap.height)
                    )
                    if (safeRect.width() > 0 && safeRect.height() > 0) {
                        val cropped = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            Bitmap.createBitmap(frozenBitmap, safeRect.left, safeRect.top, safeRect.width(), safeRect.height())
                        }
                        kotlinx.coroutines.withContext(Dispatchers.IO) {
                            clipboardEngine.dispatchSmart(null, cropped)
                        }
                        if (action == com.screentocopy.core.action.SelectionAction.EDIT) {
                            launch {
                                com.screentocopy.core.action.EditIntentExecutor(this@STSAccessibilityService).execute(cropped) {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("STSService", "Error in selection pipeline", e)
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
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

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        job.cancel()   // [Fix #2] stop watchdog loop + all child coroutines
        return super.onUnbind(intent)
    }
}
