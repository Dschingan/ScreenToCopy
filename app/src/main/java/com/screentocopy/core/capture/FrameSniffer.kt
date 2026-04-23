package com.screentocopy.core.capture

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.atomic.AtomicReference

/**
 * ⚙️ Frame Sniffer (Screenshot app değil, Ekranı Dinleyen Sistem)
 * 
 * ImageReader pipeline'ı burada kurulur.
 * Kural 1: ZERO-COPY (YUV_420_888)
 * Kural 2: PULL-BASED OCR (Push yok)
 * Kural 3: LIFETIME MANAGEMENT (Image close() disiplini)
 */
class FrameSniffer(width: Int, height: Int) {

    // Pull-based mimarinin kalbi: Thread-safe frame referansı
    private val latestImageRef = AtomicReference<Image?>(null)

    // ImageReader callback'leri UI'ı boğmasın diye ayrı bir thread'de dinliyoruz.
    private val backgroundThread = HandlerThread("STS-ImageReader-Worker").apply { start() }
    private val backgroundHandler = Handler(backgroundThread.looper)

    // ⚠️ maxImages = 2 NEDEN?
    // 1 -> frame drop riski
    // 2 -> IDEAL (biri okunurken diğeri dolabilir)
    // 3+ -> latency artar (buffer queue şişer)
    val imageReader: ImageReader = ImageReader.newInstance(
        width, 
        height, 
        ImageFormat.YUV_420_888, 
        2 
    )

    init {
        // 🔥 Listener (KRİTİK)
        imageReader.setOnImageAvailableListener({ reader ->
            // acquireNextImage() YASAK -> kuyruk birikmesine (queue build-up) yol açar.
            // acquireLatestImage() DOĞRU -> Aradaki frame'leri atla, en tazesini al.
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

            // Yeni frame geldiğinde, eski frame henüz OCR tarafından "pull" edilmemişse 
            // onu kapatmamız lazım. Yoksa maxImages limiti dolar ve crash yeriz.
            val oldImage = latestImageRef.getAndSet(image)
            oldImage?.close()

        }, backgroundHandler)
    }

    /**
     * OCR Dispatcher tarafından çağrılır. (Push değil, Pull)
     * "OCR hazır -> latest frame'i al"
     */
    fun pullLatestFrame(): Image? {
        // Frame OCR pipeline'ına geçerken referansı null'larız.
        // Artık bu 'Image' nesnesinin lifetime ownership'i (ve close sorumluluğu) çağıran taraftadır.
        return latestImageRef.getAndSet(null)
    }

    /**
     * Servis kapandığında veya ZOMBIE modda tam temizlik.
     */
    fun release() {
        val image = latestImageRef.getAndSet(null)
        image?.close()
        imageReader.close()
        backgroundThread.quitSafely()
    }
}
