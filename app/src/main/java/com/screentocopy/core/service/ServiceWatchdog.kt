package com.screentocopy.core.service

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.util.Log
import android.content.Context
import android.provider.Settings

class ServiceWatchdog(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onStateChanged: (ServiceHealth) -> Unit,
    private val onPermissionLost: (String) -> Unit
) {
    private var lastResponseTime = System.currentTimeMillis()
    private val _state = MutableStateFlow(ServiceHealth.HEALTHY)
    val state = _state.asStateFlow()

    fun ping() {
        lastResponseTime = System.currentTimeMillis()
        
        // Eğer servis Zombie veya Degraded durumundan Healthy'e döndüyse anında bildir.
        // Bu bizim "Resurrection" (Yeniden Diriliş) tespitimiz.
        if (_state.value != ServiceHealth.HEALTHY) {
            Log.i("ServiceWatchdog", "Service stabilized! Health restored.")
            _state.value = ServiceHealth.HEALTHY
            onStateChanged(ServiceHealth.HEALTHY)
        }
    }

    /**
     * [Phase 5] Structured event logging + ping.
     * Called by OverlayService for EDIT_REQUEST / EDIT_FALLBACK events.
     * Every event counts as a liveness ping — keeps the watchdog satisfied.
     */
    fun reportEvent(tag: String) {
        Log.d("ServiceWatchdog", "WatchdogEvent: $tag")
        ping()
    }

    /**
     * [Phase 5] Inline health check — true when service is not HEALTHY.
     * Used by OverlayService to gate edit execution.
     */
    fun isDegraded(): Boolean = _state.value != ServiceHealth.HEALTHY

    fun startMonitoring() {
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                val delta = System.currentTimeMillis() - lastResponseTime

                val newState = when {
                    delta < 1500 -> ServiceHealth.HEALTHY
                    delta < 4000 -> ServiceHealth.DEGRADED
                    else -> ServiceHealth.ZOMBIE
                }

                if (_state.value != newState) {
                    Log.w("ServiceWatchdog", "Service Health Changed: ${newState.name} (Delta: ${delta}ms)")
                    _state.value = newState
                    onStateChanged(newState)
                }

                // 🔥 Gerçek Dünya: İzin Kontrolü (Permission Drift)
                checkPermissions()

                // 1 saniye bekleme: Batarya dostu (Battery-suicide önleyici)
                delay(1000)
            }
        }
    }

    private fun checkPermissions() {
        // 1. Accessibility Monitor
        val accessibilityEnabled = isAccessibilityServiceEnabled(context, "com.screentocopy.core.service.STSAccessibilityService")
        if (!accessibilityEnabled) {
            onPermissionLost("accessibility")
        }

        // 2. Overlay Permission Drift
        if (!Settings.canDrawOverlays(context)) {
            onPermissionLost("overlay")
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, serviceClassName: String): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        
        return enabledServices.contains(serviceClassName)
    }
}
