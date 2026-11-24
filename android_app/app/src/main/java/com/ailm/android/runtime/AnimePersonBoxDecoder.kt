package com.ailm.android.runtime

import kotlin.math.max
import kotlin.math.min

/** YOLOv8 single-class person boxes, NOT face IDs or character identities. */
internal object AnimePersonBoxDecoder {
    data class Region(
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        val score: Double,
    ) {
        val width get() = right - left
        val height get() = bottom - top
        val area get() = width.toLong() * height
        fun bounds(): List<Int> = listOf(left, top, right, bottom)
    }

    /**
     * Supports [1,5,N] and [1,N,5] ONNX tensor layout. Input preprocess
     * uses aspect-preserving centered padding to a 640 px square.
     */
    fun decode(
        scores: List<Float>,
        outputShape: LongArray,
        sourceWidth: Int,
        sourceHeight: Int,
        confidenceFloor: Double = 0.34,
    ): List<Region> {
        require(sourceWidth > 0 && sourceHeight > 0) { "Invalid detector image dimensions" }
        require(outputShape.size == 3 && outputShape[0] == 1L) { "Invalid person detector output rank" }
        val channelsFirst = outputShape[1] == 5L
        require(channelsFirst || outputShape[2] == 5L) { "Person detector must return five channels" }
        val count = if (channelsFirst) outputShape[2] else outputShape[1]
        require(count in 1L..100_000L && scores.size.toLong() == count * 5L) { "Invalid person detector output length" }
        val columns = count.toInt()
        fun channel(index: Int, offset: Int): Float =
            if (channelsFirst) scores[offset * columns + index]
            else scores[index * 5 + offset]

        val square = 640.0
        val scale = square / max(sourceWidth, sourceHeight)
        val offsetX = (square - sourceWidth * scale) / 2
        val offsetY = (square - sourceHeight * scale) / 2
        val candidates = ArrayList<Region>()
        for (i in 0 until columns) {
            val p = channel(i, 4).toDouble()
            if (!p.isFinite() || p < confidenceFloor || p > 1.0) continue
            val x = channel(i, 0).toDouble()
            val y = channel(i, 1).toDouble()
            val w = channel(i, 2).toDouble()
            val h = channel(i, 3).toDouble()
            if (!x.isFinite() || !y.isFinite() || !w.isFinite() || !h.isFinite() ||
                w <= 0.0 || h <= 0.0) continue
            val left = ((x - w / 2 - offsetX) / scale).toInt().coerceIn(0, sourceWidth)
            val top = ((y - h / 2 - offsetY) / scale).toInt().coerceIn(0, sourceHeight)
            val right = ((x + w / 2 - offsetX) / scale).toInt().coerceIn(0, sourceWidth)
            val bottom = ((y + h / 2 - offsetY) / scale).toInt().coerceIn(0, sourceHeight)
            if (right - left < max(8, sourceWidth / 60) ||
                bottom - top < max(8, sourceHeight / 60)) continue
            candidates += Region(left, top, right, bottom, p)
        }
        val selected = mutableListOf<Region>()
        for (region in candidates.sortedByDescending { it.score }) {
            if (selected.any { intersectionOverUnion(region, it) >= 0.50 }) continue
            selected += region
        }
        // Stable screenshot ordering: left-to-right then top-to-bottom.
        return selected.sortedWith(compareBy<Region> { it.left }.thenBy { it.top })
    }

    fun intersectionOverUnion(a: Region, b: Region): Double {
        val intersectionWidth = max(0, min(a.right, b.right) - max(a.left, b.left))
        val intersectionHeight = max(0, min(a.bottom, b.bottom) - max(a.top, b.top))
        val intersection = intersectionWidth.toLong() * intersectionHeight
        val union = a.area + b.area - intersection
        return if (union > 0) intersection.toDouble() / union else 0.0
    }
}
