package com.screentocopy

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.screentocopy.core.service.PermissionOrchestrator
import com.screentocopy.core.service.STSAccessibilityService

/**
 * 📱 Bootstrapper Activity
 * UI göstermek için değil, sistemi başlatmak için.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Sadece bootstrapper, layout'a ihtiyacımız yok (veya basit bir splash)
        startPermissionFlow()
    }

    private fun startPermissionFlow() {
        val orchestrator = PermissionOrchestrator(this)
        
        when (orchestrator.getNextRequiredStep()) {
            PermissionOrchestrator.Step.ACCESSIBILITY -> {
                orchestrator.requestAccessibility()
                finish()
            }
            PermissionOrchestrator.Step.OVERLAY -> {
                orchestrator.requestOverlay()
                finish()
            }
            PermissionOrchestrator.Step.MEDIA_PROJECTION, 
            PermissionOrchestrator.Step.ALL_GRANTED -> {
                // MediaProjection'a gerek KALMADI.
                // AccessibilityService içindeki yeni Android 11 API'si ile ekranı donduruyoruz!
                val service = STSAccessibilityService.instance
                if (service != null) {
                    service.triggerScreenshot()
                } else {
                    // Servis uyanmamışsa, sistemin uyandırması için basit bir intent atılabilir.
                }
                finish()
            }
        }
    }
}
