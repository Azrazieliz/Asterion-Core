package com.ailm.android.runtime

/**
 * Independent NSFW output interpretation. The five-class classifier is NOT a
 * reliable granular rating engine; only strong, separated, directly supported
 * categories are eligible for canonical Fusion RT admission.
 *
 * Drawings is a visual medium, never proof that an image is safe.
 * Uncertain scores leave RT unresolved for manual review.
 */
internal object FusionNsfwRatingPolicy {
    data class RatingEvidence(
        val canonicalName: String,
        val confidence: Double,
        val originatingClass: String,
    )

    fun evaluate(ranked: List<Pair<String, Double>>): RatingEvidence? {
        val scores = ranked
            .filter { it.second.isFinite() && it.second in 0.0..1.0 }
            .sortedByDescending(Pair<String, Double>::second)
        val top = scores.firstOrNull() ?: return null
        val runner = scores.getOrNull(1)?.second ?: 0.0
        if (top.second < 0.92 || top.second - runner < 0.35) return null
        return when (top.first.trim().lowercase()) {
            "hentai", "porn" -> RatingEvidence("Explicit", top.second, top.first.lowercase())
            "sexy" -> RatingEvidence("Suggestive", top.second, top.first.lowercase())
            // Neutral is only a coarse model prediction; drawings says
            // nothing about explicit content. Abstain instead of misrating.
            else -> null
        }
    }

    fun parseRanked(result: Map<String, Any>): List<Pair<String, Double>> =
        (result["ranked_labels"] as? List<*>).orEmpty()
            .mapNotNull { item ->
                val row = item as? Map<*, *> ?: return@mapNotNull null
                val name = row["label"]?.toString()?.trim().orEmpty()
                val score = (row["score"] as? Number)?.toDouble() ?: return@mapNotNull null
                if (name.isEmpty()) null else name to score
            }
}
