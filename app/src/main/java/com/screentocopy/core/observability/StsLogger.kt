package com.screentocopy.core.observability

import android.util.Log

/**
 * 📝 Production-Safe Lightweight Logger
 * 
 * Logcat spam'ini önler. Üretim ortamında kapalıdır.
 * Hızlı analiz için son 100 logu hafızada tutar (Ring Buffer).
 */
object StsLogger {
    private const val TAG = "STS"
    private const val MAX_HISTORY = 100
    
    // Basit ve çok hafif bir Ring Buffer
    private val history = arrayOfNulls<String>(MAX_HISTORY)
    private var head = 0

    // TODO: Build konfigürasyonuna göre ayarlanacak
    var isDebugBuild = true 

    fun d(message: String) {
        if (isDebugBuild) {
            Log.d(TAG, message)
            addToHistory("DEBUG: $message")  // [Fix #7] no-op in release
        }
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (isDebugBuild) {
            Log.e(TAG, message, throwable)
            addToHistory("ERROR: $message")  // [Fix #7] no-op in release
        }
    }

    private fun addToHistory(msg: String) {
        val timestamp = System.currentTimeMillis()
        history[head] = "[$timestamp] $msg"
        head = (head + 1) % MAX_HISTORY
    }

    fun dumpHistory(): List<String> {
        val list = mutableListOf<String>()
        var i = head
        do {
            history[i]?.let { list.add(it) }
            i = (i + 1) % MAX_HISTORY
        } while (i != head)
        return list
    }
}
