package com.ailm.android.runtime

/**
 * The three Asterion Art image media, independent of canonical character
 * identities, series and any absent character sheets.
 *
 * Reject unknown, unsupported and low-confidence LLM responses instead of
 * filing photographs as anime or cosmetics as realistic art.
 */
internal object VisualMediumPolicy {
    val known = setOf("anime", "cosplay", "realistic")
    data class Classification(val medium: String, val confidence: Double, val evidence: String)

    fun accept(raw: Map<*, *>?): Classification? {
        raw ?: return null
        val medium = raw["kind"]?.toString()?.trim()?.lowercase().orEmpty()
        if (medium !in known) return null
        val confidence = (raw["confidence"] as? Number)?.toDouble() ?: return null
        if (!confidence.isFinite() || confidence < 0.80 || confidence > 1.0) return null
        val evidence = raw["evidence"]?.toString()?.trim().orEmpty().take(200)
        if (evidence.length < 12) return null
        val cues = evidence.lowercase()
        fun any(vararg terms: String) = terms.any { it in cues }
        val support = when (medium) {
            "anime" -> any("2d", "anime", "manga", "illustration", "drawing", "line art", "cel-shad", "painted")
            "cosplay" -> any("photo", "photograph", "camera", "real person", "real-life") &&
                any("cosplay", "costume", "wig", "character outfit", "character prop", "costumed")
            "realistic" -> any("photo", "photograph", "realistic", "photorealistic", "natural skin") &&
                !any("cosplay", "costume", "wig")
            else -> false
        }
        return if (support) Classification(medium, confidence, evidence) else null
    }
}
