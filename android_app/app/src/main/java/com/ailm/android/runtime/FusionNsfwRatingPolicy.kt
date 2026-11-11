package com.ailm.android.runtime

/**
 * Converts the independent five-class NSFW model output into human-readable
 * SFW / Ecchi / NSFW ratings. "drawings" describes medium, NEVER content.
 * This classifier does not establish ratings for uncertain illustrated works;
 * abstention leaves those images available for Review.
 */
internal object FusionNsfwRatingPolicy {
    data class RatingEvidence(
        val canonicalName: String,
        val confidence: Double,
        val originatingClass: String,
    )

    private const val MIN_CONFIDENCE = 0.92
    private const val MIN_MARGIN = 0.35

    fun evaluate(ranked: List<Pair<String, Double>>): RatingEvidence? {
        val byClass = ranked
            .filter { it.second.isFinite() && it.second in 0.0..1.0 }
            .groupBy { it.first.trim().lowercase() }
            .mapValues { (_, scores) -> scores.maxOf { it.second } }
        val porn = byClass["porn"] ?: 0.0
        val hentai = byClass["hentai"] ?: 0.0
        // Porn and hentai are two model output classes for one user rating.
        // They must be combined before comparing with competing classes.
        val adult = (porn + hentai).coerceAtMost(1.0)
        val suggestive = byClass["sexy"] ?: 0.0
        val neutral = byClass["neutral"] ?: 0.0
        val drawings = byClass["drawings"] ?: 0.0

        return when {
            adult >= MIN_CONFIDENCE &&
                adult - maxOf(suggestive, neutral, drawings) >= MIN_MARGIN ->
                RatingEvidence("NSFW", adult, if (porn >= hentai) "porn" else "hentai")
            suggestive >= MIN_CONFIDENCE &&
                suggestive - maxOf(adult, neutral, drawings) >= MIN_MARGIN ->
                RatingEvidence("Ecchi", suggestive, "sexy")
            neutral >= MIN_CONFIDENCE &&
                neutral - maxOf(adult, suggestive, drawings) >= MIN_MARGIN ->
                RatingEvidence("SFW", neutral, "neutral")
            // "drawings" is not SFW and is not a rating at all.
            else -> null
        }
    }

    /**
     * The WD artwork tagger has four dedicated rating outputs independent of
     * its general tags. On illustrated media, those are more appropriate than
     * treating the generic NSFW classifier's "drawings" class as content.
     * Require a complete known head, a strong top result, and separation.
     */
    fun evaluateArtworkHead(
        scoredLabels: List<Pair<String, Double>>,
        modelVocabulary: List<String>,
    ): RatingEvidence? {
        val categories = setOf("general", "sensitive", "questionable", "explicit")
        val vocabulary = modelVocabulary.map { it.trim().lowercase() }.toSet()
        if (!vocabulary.containsAll(categories)) return null
        val scores = scoredLabels.filter { (name, score) ->
            name.trim().lowercase() in categories && score.isFinite() && score in 0.0..1.0
        }.groupBy { it.first.trim().lowercase() }
            .mapValues { (_, values) -> values.maxOf { it.second } }
        val groups = listOf(
            Triple("SFW", "general", scores["general"] ?: 0.0),
            Triple("Ecchi", "sensitive", scores["sensitive"] ?: 0.0),
            Triple("Ecchi", "questionable", scores["questionable"] ?: 0.0),
            Triple("NSFW", "explicit", scores["explicit"] ?: 0.0),
        ).sortedByDescending { it.third }
        val winner = groups.firstOrNull() ?: return null
        // The artwork classifier outputs independent sigmoid scores, so do
        // not sum suggestive subclasses as if they were softmax probabilities.
        val competing = groups.filter { it.first != winner.first }
            .maxOfOrNull { it.third } ?: 0.0
        if (winner.third < 0.88 || winner.third - competing < 0.25) return null
        return RatingEvidence(winner.first, winner.third, "wd:" + winner.second)
    }

    /** Existing RT IDs remain stable, including pre-relabel Fusion databases. */
    fun existingFusionNames(name: String): List<String> = when (name) {
        "SFW" -> listOf("SFW", "General", "Safe for Work", "Safe")
        "Ecchi" -> listOf("Ecchi", "Suggestive")
        "NSFW" -> listOf("NSFW", "Explicit")
        else -> emptyList()
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
