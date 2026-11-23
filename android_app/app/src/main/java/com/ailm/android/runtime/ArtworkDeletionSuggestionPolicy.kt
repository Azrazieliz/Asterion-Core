package com.ailm.android.runtime

/**
 * Conservative eligibility for a deletion *suggestion*, never deletion.
 * Ratings reflect subjective taste, not file corruption or RT classification.
 */
internal object ArtworkDeletionSuggestionPolicy {
    const val DEFAULT_THRESHOLD = 25
    const val MIN_TRAINING_EXAMPLES = 30
    const val MIN_MATCHED_FEATURE_VOTES = 3

    fun eligible(
        predicted: Int,
        threshold: Int,
        trainingExamples: Int,
        establishedTags: Int,
        maxTagVotes: Int,
        manuallyRated: Boolean,
        personalVisualConfidence: Double = 0.0,
    ): Boolean =
        threshold in 1..100 &&
            predicted in 0..100 &&
            predicted < threshold &&
            (manuallyRated || trainingExamples >= MIN_TRAINING_EXAMPLES &&
            ((establishedTags > 0 && maxTagVotes >= MIN_MATCHED_FEATURE_VOTES) ||
                (personalVisualConfidence.isFinite() && personalVisualConfidence >= 0.50)))
}
