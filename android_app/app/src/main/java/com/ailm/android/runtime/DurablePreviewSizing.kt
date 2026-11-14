package com.ailm.android.runtime

/**
 * Preserve the source aspect ratio while producing the smallest true HD
 * preview that satisfies the approved 720px minimum short edge. Do not
 * upscale a small provider bitmap or allocate a needlessly large 1280px
 * square for a 720px request.
 */
internal object DurablePreviewSizing {
    /** Largest power-of-two sampled decode that still keeps true 720p pixels. */
    fun decodingSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        minimumShortEdge: Int = 720,
    ): Int? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || minimumShortEdge <= 0)
            return null
        val short = minOf(sourceWidth, sourceHeight)
        if (short < minimumShortEdge) return null
        var sample = 1
        while (short / (sample * 2) >= minimumShortEdge) sample *= 2
        return sample
    }

    fun outputDimensions(
        sourceWidth: Int,
        sourceHeight: Int,
        minimumShortEdge: Int = 720,
        maximumLongEdge: Int = 3840,
    ): Pair<Int, Int>? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || minimumShortEdge <= 0)
            return null
        val short = minOf(sourceWidth, sourceHeight)
        if (short < minimumShortEdge) return null
        val ratio = minimumShortEdge.toDouble() / short.toDouble()
        val width = (sourceWidth * ratio).toInt().coerceAtLeast(1)
        val height = (sourceHeight * ratio).toInt().coerceAtLeast(1)
        if (minOf(width, height) < minimumShortEdge ||
            maxOf(width, height) > maximumLongEdge) return null
        return width to height
    }
}
