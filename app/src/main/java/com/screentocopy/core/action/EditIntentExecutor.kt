package com.screentocopy.core.action

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.screentocopy.core.observability.StsLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 🚀 EditIntentExecutor — Fires ACTION_EDIT intent for the cropped bitmap.
 *
 * Performance contract:
 * - All file I/O runs on Dispatchers.IO — never blocks Main.
 * - [Fix 2.7] Cache eviction: keeps ≤ 50 files in cacheDir/images/
 *   to prevent unbounded disk growth and I/O latency spikes.
 * - [L8]  bitmap.recycle() is NEVER called here — edit apps lazy-load
 *   the file and an early recycle would crash them.
 * - Fallback: if no app handles ACTION_EDIT, onFallback() is called.
 *   Copy-First model means clipboard is already written at this point.
 */
class EditIntentExecutor(private val context: Context) {

    /**
     * Save bitmap to cache and fire ACTION_EDIT.
     * Must be called from a coroutine (suspend).
     *
     * @param bitmap   The already-cropped selection bitmap.
     * @param onFallback  Called if no edit app is found. Copy is already done.
     */
    suspend fun execute(bitmap: Bitmap, onFallback: suspend () -> Unit) {
        val uri = withContext(Dispatchers.IO) {
            evictCacheIfNeeded()
            save(bitmap)
        }

        withContext(Dispatchers.Main) {
            val intent = Intent(Intent.ACTION_EDIT).apply {
                setDataAndType(uri, "image/*")
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }

            // Bonus Debug
            val activities = context.packageManager.queryIntentActivities(intent, 0)
            StsLogger.d("EditIntentExecutor: found ${activities.size} editors")
            activities.forEach {
                StsLogger.d("Editor: ${it.activityInfo.packageName}")
            }

            // Explicit permission for Google Markup
            try {
                context.grantUriPermission(
                    "com.google.android.markup",
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Optional, ignore if not possible
            }

            try {
                val chooser = Intent.createChooser(intent, "Edit Image").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
                StsLogger.d("EditIntentExecutor: chooser launched")
            } catch (e: Exception) {
                StsLogger.e("EditIntentExecutor: failed → fallback", e)
                onFallback()
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * [Fix 2.7] Keep cache directory lean.
     * If > 50 files exist, delete oldest until ≤ 40 remain.
     * Called on IO thread.
     */
    private fun evictCacheIfNeeded() {
        val dir = cacheDir()
        val files = dir.listFiles() ?: return
        if (files.size <= 50) return

        val toDelete = files
            .sortedBy { it.lastModified() }
            .take(files.size - 40)   // bring down to 40

        toDelete.forEach { it.delete() }
        StsLogger.d("EditIntentExecutor: evicted ${toDelete.size} cached files")
    }

    /**
     * Persist bitmap as PNG and return a FileProvider URI.
     * Called on IO thread.
     */
    private fun save(bitmap: Bitmap): Uri {
        val dir = cacheDir()
        val file = File(dir, "sts_edit_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
    }

    private fun cacheDir(): File =
        File(context.cacheDir, "images").apply { mkdirs() }
}
