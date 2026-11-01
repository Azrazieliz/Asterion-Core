package com.ailm.android.runtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.util.AtomicFile
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.security.MessageDigest

/**
 * Durable, app-owned previews for SAF / Asterion Rounder / TeraBox artwork.
 *
 * This is NOT the transient Coil cache or Android cacheDir. A thumbnail is
 * referenced from SQLite only after atomic write, fsync, and decode validation.
 * Original cloud documents are never modified by this component.
 */
internal class PersistentArtworkThumbnails(
    context: Context,
    private val repository: LocalRepository,
) {
    private val directory = File(context.filesDir, "artwork_thumbnails")

    fun store(sourceUri: String, drawable: Drawable): Boolean = synchronized(writeLock) {
        if (sourceUri.isBlank()) return@synchronized false
        if (repository.durableThumbnailUri(sourceUri) != null) return@synchronized true
        if (!directory.exists() && !directory.mkdirs()) return@synchronized false

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sourceUri.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        val target = File(directory, digest + ".webp")
        if (target.isFile && verify(target)) {
            repository.putThumbnailCache(sourceUri, target.absolutePath,
                target.length(), System.currentTimeMillis())
            return@synchronized true
        }

        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 512
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 512
        val scale = minOf(1.0, 512.0 / maxOf(width, height))
        val outputWidth = (width * scale).toInt().coerceAtLeast(1)
        val outputHeight = (height * scale).toInt().coerceAtLeast(1)

        try {
            val bitmap = drawable.toBitmap(
                width = outputWidth,
                height = outputHeight,
                config = Bitmap.Config.ARGB_8888,
            )
            val atomic = AtomicFile(target)
            val stream = atomic.startWrite()
            try {
                val format = if (android.os.Build.VERSION.SDK_INT >= 30)
                    Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                check(bitmap.compress(format, 88, stream)) { "thumbnail compression failed" }
                atomic.finishWrite(stream)
            } catch (error: Throwable) {
                atomic.failWrite(stream)
                throw error
            }
            if (!verify(target)) {
                target.delete()
                return@synchronized false
            }
            repository.putThumbnailCache(sourceUri, target.absolutePath,
                target.length(), System.currentTimeMillis())
            true
        } catch (error: Throwable) {
            Log.w("AsterionArtworkCache", "Thumbnail retention failed without touching original", error)
            false
        }
    }

    private fun verify(file: File): Boolean {
        if (!file.isFile || file.length() <= 64L) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return runCatching {
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.outWidth > 0 && options.outHeight > 0
        }.getOrDefault(false)
    }

    companion object {
        private val writeLock = Any()
    }
}
