package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class ArtworkDeletionSuggestionPolicyTest {
    @Test fun requiresPersonalLearningAndRepeatedVisualFeatures() {
        assertFalse(ArtworkDeletionSuggestionPolicy.eligible(12, 25, 29, 1, 3, false))
        assertFalse(ArtworkDeletionSuggestionPolicy.eligible(12, 25, 30, 0, 1, false))
        assertTrue(ArtworkDeletionSuggestionPolicy.eligible(12, 25, 30, 1, 3, false))
    }

    @Test fun personalizedVisualEvidenceCanQualifyWithoutTagVotesOnlyWhenStrong() {
        assertTrue(ArtworkDeletionSuggestionPolicy.eligible(
            12, 25, 60, 0, 0, false, personalVisualConfidence = .72))
        assertFalse(ArtworkDeletionSuggestionPolicy.eligible(
            12, 25, 60, 0, 0, false, personalVisualConfidence = .31))
        assertFalse(ArtworkDeletionSuggestionPolicy.eligible(
            12, 25, 60, 0, 0, true, personalVisualConfidence = .72))
    }
    @Test fun neverSuggestsAlreadyRatedOrNonLowImages() {
        assertFalse(ArtworkDeletionSuggestionPolicy.eligible(10, 25, 60, 2, 5, true))
        assertFalse(ArtworkDeletionSuggestionPolicy.eligible(25, 25, 60, 2, 5, false))
        assertFalse(ArtworkDeletionSuggestionPolicy.eligible(-1, 25, 60, 2, 5, false))
        assertFalse(ArtworkDeletionSuggestionPolicy.eligible(100, 25, 60, 2, 5, false))
    }
}
