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

    /**
     * Independent visual-medium decision from the specialist classifier's
     * actual fixed output-head labels. A broad "realistic" style tag cannot
     * prove this was a photograph; cosplay requires BOTH photographic evidence
     * and a positive cosplay label. Untrained labels and mixed-media evidence
     * abstain instead of generating plausible-looking fake classifications.
     *
     * Confidence here is the ONNX head score, NOT a calibrated probability.
     */
    fun fromNativeScores(
        scores: List<Pair<String, Double>>,
        modelVocabulary: List<String>,
        modelId: String,
    ): Classification? {
        if (modelId != "asterion-wd-vit-tagger-v3") return null
        fun key(s: String): String = s.trim().lowercase(java.util.Locale.ROOT)
            .replace('_', ' ').replace(Regex("\\s+"), " ")
        val counts = modelVocabulary.map(::key).groupingBy { it }.eachCount()
        val valid = counts.filterValues { it == 1 }.keys
        val trusted = scores.mapNotNull { (label, value) ->
            val normalized = key(label)
            if (normalized in valid && value.isFinite() && value in 0.0..1.0)
                normalized to value else null
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.maxOrNull() ?: 0.0 }

        val animeLabels = listOf("anime coloring", "anime screenshot", "anime illustration", "anime style")
        // The bundled WD ViT v3 vocabulary has "realistic" and
        // "photorealistic", NOT "photo (medium)". Keep historical trained
        // model variants for compatibility, but use real installed labels.
        val photoLabels = listOf("photo (medium)", "photograph (medium)",
            "photography (medium)", "realistic", "photorealistic", "cellphone photo")
        val anime = animeLabels.maxOfOrNull { trusted[it] ?: 0.0 } ?: 0.0
        val photo = photoLabels.maxOfOrNull { trusted[it] ?: 0.0 } ?: 0.0
        val costume = trusted["cosplay"] ?: 0.0
        return when {
            // Photographic cosplay is a separate visual medium, not merely
            // realistic imagery. Require *both* independent trained cues.
            costume >= 0.62 && photo >= 0.62 && photo - anime >= 0.08 ->
                Classification("cosplay", minOf(photo, costume),
                    "Native photographic cosplay evidence: realistic/photo and cosplay")
            anime >= 0.80 && anime - photo >= 0.16 -> Classification(
                "anime", anime, "Native anime illustration evidence: " +
                    animeLabels.filter { (trusted[it] ?: 0.0) >= 0.80 }.joinToString(),
            )
            photo >= 0.88 && photo - anime >= 0.16 && costume < 0.45 ->
                Classification("realistic", photo, "Native photograph evidence: realistic/photo")
            else -> null
        }
    }

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
                (!any("cosplay", "costume", "wig") ||
                    any("non-costumed", "not wearing a costume", "without costume", "ordinary clothes"))
            else -> false
        }
        return if (support) Classification(medium, confidence, evidence) else null
    }
}
