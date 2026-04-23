package com.screentocopy.core.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class STSAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var watchdog: ServiceWatchdog

    // State buffer - Olayları geçici tuttuğumuz yer
    private val eventBuffer = mutableListOf<AccessibilityEvent>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i("STSService", "Accessibility Service Connected")

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

    private fun triggerMediaProjectionFallback() {
        // Zombi modunda asıl kurtarıcı:
        // Kullanıcı "Circle to Search" yapmak istediğinde Accessibility cevap vermezse,
        // direkt MediaProjection activity'sini tetikle.
        // val intent = Intent(this, MediaProjectionActivity::class.java)
        // intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // startActivity(intent)
    }
}
