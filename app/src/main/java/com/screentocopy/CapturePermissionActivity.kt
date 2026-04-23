package com.screentocopy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 🎥 MediaProjection Request Activity
 */
class CapturePermissionActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Ekran kaydı izni isteğini başlat
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            1001
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == Activity.RESULT_OK && data != null) {
            // İzni aldık, OverlayService'i başlat!
            val serviceIntent = Intent(this, com.screentocopy.core.service.OverlayService::class.java)
            serviceIntent.action = "SHOW_OVERLAY"
            serviceIntent.putExtra("resultCode", resultCode)
            serviceIntent.putExtra("data", data)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
        finish()
    }
}
