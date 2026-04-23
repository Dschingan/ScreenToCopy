package com.screentocopy.core.service

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 🎥 MediaProjection Stabilization Layer
 * 
 * MediaProjection çok kırılgandır (drop olabilir, black frame dönebilir).
 * Bu State Machine ile hataları tolere edip gerektiğinde oturumu yeniden başlatıyoruz.
 */
class MediaProjectionStabilizer(
    private val onRestartRequired: () -> Unit
) {
    enum class State {
        IDLE, READY, CAPTURING, FAILED
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state = _state.asStateFlow()

    private var consecutiveFailures = 0
    private val MAX_FAILURES = 3

    fun markReady() {
        _state.value = State.READY
        consecutiveFailures = 0
    }

    fun markCapturing() {
        _state.value = State.CAPTURING
    }

    fun handleFrameCaptureResult(isSuccess: Boolean) {
        if (isSuccess) {
            consecutiveFailures = 0
            _state.value = State.READY
        } else {
            consecutiveFailures++
            Log.e("MPStabilizer", "Dropped frame detected. Failure count: $consecutiveFailures")
            
            if (consecutiveFailures >= MAX_FAILURES) {
                _state.value = State.FAILED
                Log.e("MPStabilizer", "Max failures reached! Triggering Projection Restart.")
                onRestartRequired()
            }
        }
    }

    fun forceReset() {
        consecutiveFailures = 0
        _state.value = State.IDLE
    }
}
