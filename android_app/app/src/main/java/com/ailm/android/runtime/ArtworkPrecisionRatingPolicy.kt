package com.ailm.android.runtime

import kotlin.math.roundToInt

/** Independent 0..100 quality scale; -1 clears user rating, 0 is valid. */
internal object ArtworkPrecisionRatingPolicy {
    val dimensions = linkedMapOf(
        "artstyle" to "Art style",
        "composition" to "Composition",
        "color" to "Color & lighting",
        "detail" to "Detail & technique",
        "impact" to "Visual impact",
    )
    fun automaticScore(source: Double): Int? {
        if (!source.isFinite() || source < 0.0 || source > 100.0) return null
        return (if (source <= 10.0) source * 10.0 else source)
            .roundToInt().coerceIn(0, 100)
    }
    fun displayedScore(manual: Int?, automatic: Int?): Int? =
        manual?.takeIf { it in 0..100 } ?: automatic?.takeIf { it in 0..100 }
    fun validManual(score: Int): Boolean = score in -1..100
    fun validDimension(code: String): Boolean = code in dimensions.keys
}
