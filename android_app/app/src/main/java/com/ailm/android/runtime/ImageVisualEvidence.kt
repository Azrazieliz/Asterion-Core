package com.ailm.android.runtime

import android.graphics.BitmapFactory
import java.io.File

/**
 * Cheap fail-closed check on the already-staged, original image bytes.
 *
 * Only establishes that a *decodable, non-uniform image exists*. It does
 * not attempt to recognize people or decide whether a picture is blurred.
 * We never use it to authorize deletion or to invent semantic tags.
 */
internal object ImageVisualEvidence {
    fun verifiedNonblank(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return false
        var sample = 1
        while (w / sample > 128 || h / sample > 128) sample *= 2
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return false
        try {
            if (bitmap.width < 2 || bitmap.height < 2) return false
            val colors = HashSet<Int>(64)
            var minLum = 255
            var maxLum = 0
            var horizontalTransitions = 0
            val grid = 20
            for (y in 0 until grid) {
                var previousLum = -1
                for (x in 0 until grid) {
                    val rgb = bitmap.getPixel(
                        ((x * (bitmap.width - 1)) / (grid - 1)).coerceAtLeast(0),
                        ((y * (bitmap.height - 1)) / (grid - 1)).coerceAtLeast(0),
                    )
                    val red = (rgb shr 16) and 255
                    val green = (rgb shr 8) and 255
                    val blue = rgb and 255
                    val luminance = (299 * red + 587 * green + 114 * blue) / 1000
                    minLum = minOf(minLum, luminance)
                    maxLum = maxOf(maxLum, luminance)
                    colors.add(((red shr 4) shl 8) or ((green shr 4) shl 4) or (blue shr 4))
                    if (previousLum >= 0 && kotlin.math.abs(luminance - previousLum) > 48) {
                        horizontalTransitions++
                    }
                    previousLum = luminance
                }
            }
            // Conservative protection against false garbage routing.
            // A legitimate textured photograph/cosplay image passes easily.
            return (colors.size >= 16 && maxLum - minLum >= 20) ||
                horizontalTransitions >= 10
        } finally {
            bitmap.recycle()
        }
    }
}
