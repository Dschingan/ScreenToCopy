package com.screentocopy.core.engine

import android.os.Process
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * 🧵 Thread / Core Isolation
 * Dispatchers.Default kullanmak YASAK.
 * UI ile rekabet etmeyen ama starvation da yaşamayan Dedicated Thread Pool.
 */
object IsolatedOcrDispatcher {
    
    private val threadFactory = ThreadFactory { runnable ->
        Thread {
            // Thread başlarken Android Process seviyesinde önceliğini ayarla.
            // UI thread'ini (Main) boğmamak ama I/O threadleri arasında da ezilmemek için:
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            runnable.run()
        }.apply { 
            name = "STS-OCR-Worker" 
            // JVM seviyesinde de priority veriyoruz (Opsiyonel ama garanti)
            priority = Thread.MAX_PRIORITY 
        }
    }

    // Maksimum 2 thread (CPU'yu context-switching ile boğmamak için)
    private val executor = Executors.newFixedThreadPool(2, threadFactory)
    
    // Coroutine'ler için dispatcher olarak dışarı aç
    val dispatcher = executor.asCoroutineDispatcher()
}
