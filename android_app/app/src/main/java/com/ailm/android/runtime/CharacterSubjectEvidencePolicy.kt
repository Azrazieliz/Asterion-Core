package com.ailm.android.runtime

/**
 * Prevent image-wide evidence from leaking between subjects. A partial roster
 * or no character sheets still permits Review suggestions, never fabricated
 * single-subject or confident identity observations.
 */
internal object CharacterSubjectEvidencePolicy {
    fun sharedWardrobeEvidence(
        subjectCount: Int,
        imageFeatures: Map<String, Double>,
    ): Map<String, Double> {
        if (subjectCount != 1) return emptyMap()
        return imageFeatures.filter { (id, confidence) ->
            (id.startsWith("WP", ignoreCase = true) || id.startsWith("OF", ignoreCase = true)) &&
                confidence.isFinite() && confidence in 0.72..1.0
        }
    }

    fun selectEmbedding(
        subjectCount: Int,
        rawSubjectEmbedding: Any?,
        imageEmbedding: List<Double>,
    ): List<Double> {
        fun verified(raw: Any?): List<Double> {
            val items = raw as? List<*> ?: return emptyList()
            if (items.size !in 16..8192) return emptyList()
            return items.map { item ->
                (item as? Number)?.toDouble()?.takeIf(Double::isFinite)
                    ?: return emptyList()
            }
        }
        val subjectEmbedding = verified(rawSubjectEmbedding)
        if (subjectEmbedding.isNotEmpty()) return subjectEmbedding
        // Whole-image embeddings can describe the *wrong person* in a group.
        return if (subjectCount == 1) verified(imageEmbedding) else emptyList()
    }

    /**
     * A confirmed identity in Review is not automatically a clean visual
     * reference. The image-wide embedding of a group belongs to nobody in
     * particular; attributing it to each member poisons future recognition.
     * Preserve manual identity corrections separately from reference learning.
     */
    fun verifiedSingleSubjectReferenceEmbedding(
        correctedIdentityCount: Int,
        observedSubjectCount: Int,
        wholeImageEmbedding: List<Double>,
    ): List<Double> {
        if (correctedIdentityCount != 1 || observedSubjectCount != 1) return emptyList()
        if (wholeImageEmbedding.size !in 16..8192 ||
            wholeImageEmbedding.any { !it.isFinite() }) return emptyList()
        return wholeImageEmbedding
    }

    fun canAcceptIdentity(
        finalScore: Double,
        visualScore: Double?,
        runnerUp: Double,
        hasValidatedVisualReference: Boolean,
        acceptanceThreshold: Double,
    ): Boolean =
        hasValidatedVisualReference &&
            finalScore.isFinite() && finalScore >= acceptanceThreshold &&
            visualScore != null && visualScore.isFinite() && visualScore >= 0.72 &&
            runnerUp.isFinite() && finalScore - runnerUp >= 0.12
}
