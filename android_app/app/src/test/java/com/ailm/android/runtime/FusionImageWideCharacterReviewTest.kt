package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class FusionImageWideCharacterReviewTest {
    private val catalog = mapOf(
        "HC001" to CanonicalObservationPolicy.Descriptor("HC001"),
        "EC001" to CanonicalObservationPolicy.Descriptor("EC001"),
        "RT001" to CanonicalObservationPolicy.Descriptor("RT001"),
        "SP002" to CanonicalObservationPolicy.Descriptor("SP002"),
        "SA001" to CanonicalObservationPolicy.Descriptor("SA001", "SP002"),
    )
    @Test fun existingTaxonomyCanProduceSheetlessCharacterReviewFeatures() {
        val classifierScores = FusionImageWideTagEvidence.scoredFeatures(listOf(
            mapOf("tag" to "HC001", "score" to .96),
            mapOf("tag" to "EC001", "score" to .91),
            mapOf("tag" to "RT001", "score" to .99),
            mapOf("tag" to "ZZ999", "score" to .99),
        ))
        val review = FusionImageWideTagEvidence.reviewCandidateFeatures(classifierScores) {
            catalog[it]
        }
        assertEquals(setOf("HC001", "EC001"), review.keys)
        assertEquals(.96, review.getValue("HC001"), .0001)
    }

    @Test fun weakAndSpeciesIncompatibleImageWideEvidenceAbstains() {
        val review = FusionImageWideTagEvidence.reviewCandidateFeatures(mapOf(
            "HC001" to .41, "SA001" to .99, "RT001" to .97,
        )) { catalog[it] }
        assertTrue(review.isEmpty())
        val compatible = FusionImageWideTagEvidence.reviewCandidateFeatures(mapOf(
            "SP002" to .86, "SA001" to .92,
        )) { catalog[it] }
        assertEquals(setOf("SP002", "SA001"), compatible.keys)
    }

    @Test fun noScoreCannotBecomeCanonicalObservation() {
        assertTrue(FusionImageWideTagEvidence.reviewCandidateFeatures(emptyMap()) {
            catalog[it]
        }.isEmpty())
        assertTrue(FusionImageWideTagEvidence.reviewCandidateFeatures(
            mapOf("HC001" to Double.NaN, "EC001" to 1.4)
        ) { catalog[it] }.isEmpty())
    }
}
