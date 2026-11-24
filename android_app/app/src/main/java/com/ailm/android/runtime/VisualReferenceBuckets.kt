package com.ailm.android.runtime

/** Eight reproducible 8-bit locality-sensitive hash bands for cosine retrieval.
 * Each bucket probes validated references only. All final similarities use the original vectors.
 */
internal object VisualReferenceBuckets {
    /** Reserve a Review slot for the strongest visual challenger even when a costume
     * change leaves the attribute ranker with little useful evidence. */
    fun <T> reviewCandidates(ranked: List<T>, limit: Int, visual: (T) -> Double?): List<T> {
        require(limit > 0)
        val strongest = ranked.filter { visual(it) != null }.maxByOrNull { visual(it)!! }
        return (ranked.take(limit - 1) + listOfNotNull(strongest) + ranked).distinct().take(limit)
    }

    fun keys(vector: List<Double>): IntArray {
        if (vector.size !in 16..8192 || vector.any { !it.isFinite() } || vector.none { it != 0.0 }) return intArrayOf()
        val projections = DoubleArray(64)
        // Signed feature hashing: each coordinate contributes once per band.
        for ((coordinate, value) in vector.withIndex()) {
            var state = (coordinate + 1) * -1640531527
            for (band in 0 until 8) {
                state = (state xor (state ushr 16)) * -2048144789
                state = (state xor (state ushr 13)) * -1028477387
                val slot = band * 8 + ((state ushr 16) and 7)
                projections[slot] += if (state < 0) -value else value
            }
        }
        return IntArray(8) { band ->
            var bits = 0
            for (bit in 0 until 8) if (projections[band * 8 + bit] > 0) bits = bits or (1 shl bit)
            vector.size * 2048 + band * 256 + bits
        }
    }
}
