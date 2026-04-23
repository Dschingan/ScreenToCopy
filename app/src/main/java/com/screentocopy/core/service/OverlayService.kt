package com.screentocopy.core.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.screentocopy.core.engine.ClipboardEngine
import com.screentocopy.core.selection.SelectionEngineView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 🎨 Overlay Manager Service
 * Ekranın üzerine görünmez çizim katmanını (SelectionEngineView) yerleştirir.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: SelectionEngineView? = null
    private var mediaProjection: MediaProjection? = null
    private var frozenBitmap: Bitmap? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var clipboardEngine: ClipboardEngine

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        clipboardEngine = ClipboardEngine(this)
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "SHOW_OVERLAY") {
            val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            val data = intent.getParcelableExtra<Intent>("data")
            if (resultCode == Activity.RESULT_OK && data != null) {
                takeScreenshotAndShowOverlay(resultCode, data)
            } else {
                showOverlay() // İzin yoksa düz overlay (ama arkaplan donmaz)
            }
        } else if (intent?.action == "HIDE_OVERLAY") {
            hideOverlay()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "sts_overlay_channel",
                "STS Engine Active",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, "sts_overlay_channel")
            .setContentTitle("ScreenToCopy Active")
            .setContentText("Ready to capture screen.")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .build()

        startForeground(1, notification)
    }

    private fun showOverlay() {
        if (overlayView != null) return

        overlayView = SelectionEngineView(this)
        
        // Kullanıcı seçimi tamamladığında çalışacak kod:
        overlayView?.onSelectionComplete = { rect ->
            if (frozenBitmap != null) {
                // Seçilen alanı Bitmap'ten kırp
                try {
                    val safeRect = android.graphics.Rect(
                        rect.left.coerceAtLeast(0),
                        rect.top.coerceAtLeast(0),
                        rect.right.coerceAtMost(frozenBitmap!!.width),
                        rect.bottom.coerceAtMost(frozenBitmap!!.height)
                    )
                    
                    if (safeRect.width() > 0 && safeRect.height() > 0) {
                        val cropped = Bitmap.createBitmap(frozenBitmap!!, safeRect.left, safeRect.top, safeRect.width(), safeRect.height())
                        
                        // Clipboard'a resim olarak at
                        scope.launch {
                            clipboardEngine.dispatchSmart(null, cropped)
                            hideOverlay()
                        }
                    } else { hideOverlay() }
                } catch (e: Exception) {
                    hideOverlay()
                }
            } else {
                hideOverlay()
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        windowManager.addView(overlayView, params)
    }

    private fun takeScreenshotAndShowOverlay(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        
        val metrics = resources.displayMetrics
        val imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 1)
        
        val virtualDisplay = mediaProjection?.createVirtualDisplay("STS_Capture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, null)
            
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * metrics.widthPixels
                
                val bitmapWidth = metrics.widthPixels + rowPadding / pixelStride
                val bitmap = Bitmap.createBitmap(bitmapWidth, metrics.heightPixels, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                
                // Gerçek ekran boyutuna kırp
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, metrics.widthPixels, metrics.heightPixels)
                frozenBitmap = cropped
                
                Handler(Looper.getMainLooper()).post {
                    showOverlay()
                    overlayView?.background = BitmapDrawable(resources, cropped)
                }
                
                image.close()
                virtualDisplay?.release()
                mediaProjection?.stop()
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun hideOverlay() {
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
    }

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
