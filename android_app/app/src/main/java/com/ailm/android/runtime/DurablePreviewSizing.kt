package com.ailm.android.runtime

/**
 * Preserve the source aspect ratio while producing the smallest true HD
 * preview that satisfies the approved 720px minimum short edge. Do not
 * upscale a small provider bitmap or allocate a needlessly large 1280px
 * square for a 720px request.
 */
internal object DurablePreviewSizing {
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
