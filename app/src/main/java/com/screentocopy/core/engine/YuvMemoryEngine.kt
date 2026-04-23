package com.screentocopy.core.engine

import android.graphics.Rect
import android.media.Image
import java.nio.ByteBuffer

/**
 * 🧠 6. Bitmap Problemine Final Çözüm & ROI (Region of Interest)
 * 
 * YASAK: Bitmap.createBitmap(...)
 * KURAL: Sadece seçilen bölgeyi (ROI) YUV_420_888 seviyesinde kırp,
 * doğrudan ByteBuffer'a aktar ve ML Kit için NV21 olarak paketle.
 */
object YuvMemoryEngine {

    /**
     * YUV_420_888 formatlı Image objesinden, sadece [roi] alanını okur,
     * hizalar ve ML Kit için doğrudan NV21 formatında paketler.
     */
    fun extractRoiToNv21ByteBuffer(image: Image, roi: Rect): ByteBuffer {
        require(image.format == android.graphics.ImageFormat.YUV_420_888) {
            "Only YUV_420_888 is supported for zero-copy pipeline"
        }

        // 1. ROI Alignment (ÇOK KRİTİK: UV 2x2 subsampled olduğu için tek sayılar hizayı bozar)
        val alignedLeft = roi.left and 1.inv()
        val alignedTop = roi.top and 1.inv()
        val alignedWidth = roi.width() and 1.inv()
        val alignedHeight = roi.height() and 1.inv()

        // Güvenlik: Hizalanmış ROI ekran sınırlarını aşmasın
        val safeWidth = minOf(alignedWidth, image.width - alignedLeft)
        val safeHeight = minOf(alignedHeight, image.height - alignedTop)

        // Buffer boyutları: Y (W*H), UV (W*H / 2) -> Toplam: (W*H*3)/2
        val ySize = safeWidth * safeHeight
        val uvSize = ySize / 2
        
        // TODO: GC'yi tamamen sıfırlamak için burada ByteArray Pool kullanılacak
        val nv21ByteArray = ByteArray(ySize + uvSize) 

        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        // 2. Y Plane Extraction (Luminance)
        extractYPlane(
            plane = yPlane,
            roiLeft = alignedLeft,
            roiTop = alignedTop,
            roiWidth = safeWidth,
            roiHeight = safeHeight,
            output = nv21ByteArray,
            outOffset = 0
        )

        // 3. UV Plane Extraction -> NV21 Packing (V, U, V, U...)
        extractUvPlaneToNv21(
            uPlane = uPlane,
            vPlane = vPlane,
            roiLeft = alignedLeft,
            roiTop = alignedTop,
            roiWidth = safeWidth,
            roiHeight = safeHeight,
            output = nv21ByteArray,
            outOffset = ySize
        )

        return ByteBuffer.wrap(nv21ByteArray)
    }

    private fun extractYPlane(
        plane: Image.Plane,
        roiLeft: Int, roiTop: Int, roiWidth: Int, roiHeight: Int,
        output: ByteArray, outOffset: Int
    ) {
        // Ultra Optimize Versiyon (Gerçek Silah): buffer.duplicate() ile race condition koruması
        val buffer = plane.buffer.duplicate() 
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        var outIndex = outOffset

        for (row in 0 until roiHeight) {
            val srcRow = roiTop + row
            val offset = (srcRow * rowStride) + (roiLeft * pixelStride)

            buffer.position(offset)

            if (pixelStride == 1) {
                // Bulk read: CPU ve I/O dostu. Mümkün olduğunda satırı tek seferde çek.
                buffer.get(output, outIndex, roiWidth)
                outIndex += roiWidth
            } else {
                for (col in 0 until roiWidth) {
                    output[outIndex++] = buffer.get(offset + col * pixelStride)
                }
            }
        }
    }

    private fun extractUvPlaneToNv21(
        uPlane: Image.Plane, vPlane: Image.Plane,
        roiLeft: Int, roiTop: Int, roiWidth: Int, roiHeight: Int,
        output: ByteArray, outOffset: Int
    ) {
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()

        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        // UV çözünürlüğü Y'nin yarısı kadardır
        val uvLeft = roiLeft / 2
        val uvTop = roiTop / 2
        val uvWidth = roiWidth / 2
        val uvHeight = roiHeight / 2

        var outIndex = outOffset

        for (row in 0 until uvHeight) {
            val srcRow = uvTop + row
            val uRowStart = srcRow * uRowStride
            val vRowStart = srcRow * vRowStride

            val uColStart = uvLeft * uPixelStride
            val vColStart = uvLeft * vPixelStride

            for (col in 0 until uvWidth) {
                val uIndex = uRowStart + uColStart + col * uPixelStride
                val vIndex = vRowStart + vColStart + col * vPixelStride

                // ML Kit IMAGE_FORMAT_NV21 bekler: V, U, V, U...
                output[outIndex++] = vBuffer.get(vIndex)
                output[outIndex++] = uBuffer.get(uIndex)
            }
        }
    }

    /**
     * SADECE resim paylaşılacağı zaman (Clipboard Image/Hybrid mode) çağrılır.
     * NV21 byte buffer'ı Bitmap'e dönüştürür.
     */
    fun nv21ToBitmap(nv21Buffer: ByteBuffer, width: Int, height: Int): android.graphics.Bitmap {
        val bytes = if (nv21Buffer.hasArray()) {
            nv21Buffer.array()
        } else {
            val arr = ByteArray(nv21Buffer.remaining())
            val originalPos = nv21Buffer.position()
            nv21Buffer.get(arr)
            nv21Buffer.position(originalPos)
            arr
        }

        val yuvImage = android.graphics.YuvImage(
            bytes,
            android.graphics.ImageFormat.NV21,
            width,
            height,
            null
        )
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 100, out)
        val jpegBytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }
}
