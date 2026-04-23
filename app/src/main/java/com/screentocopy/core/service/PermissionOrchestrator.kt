package com.screentocopy.core.service

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.net.Uri

/**
 * 🛡️ Permission Orchestration Layer
 * 
 * İzinleri kullanıcının yüzüne aynı anda fırlatmaz.
 * Step-by-step ilerleyerek (Accessibility -> Overlay -> MediaProjection)
 * her aşamada bağlam sunar (UX).
 */
class PermissionOrchestrator(private val context: Context) {

    enum class Step {
        ACCESSIBILITY,
        OVERLAY,
        MEDIA_PROJECTION,
        ALL_GRANTED
    }

    fun getNextRequiredStep(): Step {
        if (!isAccessibilityEnabled()) return Step.ACCESSIBILITY
        if (!Settings.canDrawOverlays(context)) return Step.OVERLAY
        // MediaProjection is usually requested per session or requires a service check
        // For simplicity, we assume if the service is running, it's granted, else we handle it in Capture.
        return Step.ALL_GRANTED
    }

    fun requestAccessibility() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        // TODO: Show UX explanation overlay/toast
    }

    fun requestOverlay() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        // TODO: Show UX explanation overlay/toast
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(context.packageName)
    }
}
