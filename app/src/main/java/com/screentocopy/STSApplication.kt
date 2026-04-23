package com.screentocopy

import android.app.Application
import com.screentocopy.core.observability.MetricsCollector
import com.screentocopy.core.observability.StsLogger

/**
 * 🚀 Global Orchestrator
 */
class STSApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Global Init
        StsLogger.isDebugBuild = BuildConfig.DEBUG
        StsLogger.d("STS Application Booted")
    }
}
