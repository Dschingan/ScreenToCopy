package com.screentocopy.core.observability

/**
 * 🛠️ Self-Healing & Auto-Recovery Engine
 * 
 * Sistemi sürekli izler (Health Analyzer) ve kendi kendini onarır.
 * Kullanıcı ASLA "Bir şey bozuldu" hissi yaşamaz.
 */
object AutoRecoveryEngine {

    enum class HealthState {
        HEALTHY, DEGRADED, CRITICAL
    }

    enum class PerformanceMode {
        PERFORMANCE, // Tam hız, 60fps okuma, Max ML gücü
        BALANCED,    // Orta hız, batarya dostu
        SAFE         // Düşük çözünürlük, frame atlama (Isınma veya Drop anında)
    }

    var currentMode = PerformanceMode.PERFORMANCE
        private set

    var currentState = HealthState.HEALTHY
        private set

    // Kritik Sınırlar
    private const val DROP_RATE_WARNING_THRESHOLD = 5.0f // %5 drop
    private const val DROP_RATE_CRITICAL_THRESHOLD = 15.0f // %15 drop
    private const val OCR_SLOW_THRESHOLD_MS = 250L // 250ms üstü çok yavaş
    private const val MEMORY_CRITICAL_MB = 200L // 200MB Native Heap şişmesi

    fun analyzeAndHeal() {
        val dropRate = MetricsCollector.getDropRatePercentage()
        val avgOcrTime = MetricsCollector.getAverageOcrTimeMs()
        val memoryMB = MetricsCollector.getNativeMemoryUsageMB()

        // 1. Health State Belirleme
        currentState = when {
            dropRate > DROP_RATE_CRITICAL_THRESHOLD || memoryMB > MEMORY_CRITICAL_MB -> HealthState.CRITICAL
            dropRate > DROP_RATE_WARNING_THRESHOLD || avgOcrTime > OCR_SLOW_THRESHOLD_MS -> HealthState.DEGRADED
            else -> HealthState.HEALTHY
        }

        // 2. Auto-Recovery (Silent Healing)
        when (currentState) {
            HealthState.HEALTHY -> {
                if (currentMode != PerformanceMode.PERFORMANCE) {
                    StsLogger.d("System healthy. Upgrading to PERFORMANCE mode.")
                    currentMode = PerformanceMode.PERFORMANCE
                }
            }
            HealthState.DEGRADED -> {
                if (currentMode != PerformanceMode.BALANCED) {
                    StsLogger.d("System degraded (Drop: $dropRate%, OCR: ${avgOcrTime}ms). Switching to BALANCED mode.")
                    currentMode = PerformanceMode.BALANCED
                    // Action: OCR Frequency düşürülür, ufak buffer temizliği yapılır.
                }
            }
            HealthState.CRITICAL -> {
                if (currentMode != PerformanceMode.SAFE) {
                    StsLogger.e("System CRITICAL! (Memory: ${memoryMB}MB, Drop: $dropRate%). Switching to SAFE mode.")
                    currentMode = PerformanceMode.SAFE
                    
                    // Action 1: Tüm cache'leri temizle
                    System.gc()
                    
                    // Action 2: Eğer Memory çok şişmişse ML motoruna flush emri ver
                    // Action 3: MediaProjection session restart'ı tetiklenebilir
                }
            }
        }
    }
}
