package com.ailm.android.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One bounded image decode supplies an objective technical baseline AND the
 * visual characteristics for the owner's learnable preference model.
 * The baseline is not a measurement of subjective beauty.
 */
internal object ArtworkTechnicalScorer {
    data class Analysis(
        val score: Double,
        val visualFeatures: List<Double>,
    )
    fun scoreFile(file: File): Double? = analyzeFile(file)?.score

    fun analyzeFile(file: File): Analysis? {
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth < 16 || bounds.outHeight < 16) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            try { analyzeBitmap(decoded) } finally { decoded.recycle() }
        }.getOrNull()
    }

    private fun analyzeBitmap(bitmap: Bitmap): Analysis {
        val w = min(bitmap.width, 192)
        val h = min(bitmap.height, 192)
        val preview = Bitmap.createScaledBitmap(bitmap, w, h, true)
        try {
            val pixels = IntArray(w * h)
            preview.getPixels(pixels, 0, w, 0, 0, w, h)
            var sum = 0.0
            var sumSq = 0.0
            var redSum = 0.0
            var blueSum = 0.0
            var satSum = 0.0
            var clipped = 0
            var edge = 0.0
            var adjacent = 0
            var centralL = 0.0
            var outerL = 0.0
            var centralEdge = 0.0
            var outerEdge = 0.0
            var centerCount = 0
            var outerCount = 0
            var centerAdj = 0
            var outerAdj = 0
            var leftL = 0.0
            var rightL = 0.0
            var topL = 0.0
            var bottomL = 0.0
            var leftCount = 0
            var rightCount = 0
            var topCount = 0
            var bottomCount = 0
            var redDominant = 0
            var blueDominant = 0
            for (y in 0 until h) for (x in 0 until w) {
                val rgb = pixels[y * w + x]
                val r = (rgb shr 16 and 255)
                val g = (rgb shr 8 and 255)
                val b = (rgb and 255)
                val l = (r * 299 + g * 587 + b * 114) / 1000
                sum += l
                sumSq += l.toDouble() * l
                redSum += r
                blueSum += b
                satSum += (max(r, max(g, b)) - min(r, min(g, b))) / 255.0
                if (l < 4 || l > 251) clipped++
                if (r > g * 1.12 && r > b * 1.12) redDominant++
                if (b > g * 1.12 && b > r * 1.12) blueDominant++
                val center = x >= w / 4 && x < w * 3 / 4 &&
                    y >= h / 4 && y < h * 3 / 4
                if (center) { centralL += l; centerCount++ }
                else { outerL += l; outerCount++ }
                if (x < w / 2) { leftL += l; leftCount++ }
                else { rightL += l; rightCount++ }
                if (y < h / 2) { topL += l; topCount++ }
                else { bottomL += l; bottomCount++ }
                if (x > 0) {
                    val other = pixels[y * w + x - 1]
                    val pr = other shr 16 and 255
                    val pg = other shr 8 and 255
                    val pb = other and 255
                    val prev = (pr * 299 + pg * 587 + pb * 114) / 1000
                    val difference = abs(l - prev).toDouble()
                    edge += difference
                    adjacent++
                    if (center) { centralEdge += difference; centerAdj++ }
                    else { outerEdge += difference; outerAdj++ }
                }
            }
            val n = max(1, w * h)
            val mean = sum / n
            val deviation = sqrt(max(0.0, sumSq / n - mean * mean))
            val contrast = (deviation / 65.0).coerceIn(0.0, 1.0)
            val detail = (edge / max(1, adjacent) / 22.0).coerceIn(0.0, 1.0)
            val clippedRatio = clipped.toDouble() / n
            val technical = (
                (0.55 * contrast + 0.45 * detail - 0.20 * clippedRatio)
                    .coerceIn(0.0, 1.0) * 100.0
                )
            fun bound(v: Double) = v.coerceIn(-1.0, 1.0)
            val features = listOf(
                bound(2.0 * mean / 255.0 - 1.0),
                bound(2.0 * deviation / 65.0 - 1.0),
                bound(2.0 * satSum / n - 1.0),
                bound((redSum - blueSum) / (n * 255.0)),
                bound(2.0 * edge / max(1, adjacent) / 22.0 - 1.0),
                bound(clippedRatio * 4.0 - 1.0),
                bound((centralL / max(1, centerCount) -
                    outerL / max(1, outerCount)) / 128.0),
                bound((leftL / max(1, leftCount) -
                    rightL / max(1, rightCount)) / 128.0),
                bound((topL / max(1, topCount) -
                    bottomL / max(1, bottomCount)) / 128.0),
                bound((centralEdge / max(1, centerAdj) -
                    outerEdge / max(1, outerAdj)) / 32.0),
                bound(redDominant.toDouble() / n * 2.0 - 1.0),
                bound(blueDominant.toDouble() / n * 2.0 - 1.0),
            )
            return Analysis(technical, features)
        } finally {
            if (preview !== bitmap) preview.recycle()
        }
    }
}
