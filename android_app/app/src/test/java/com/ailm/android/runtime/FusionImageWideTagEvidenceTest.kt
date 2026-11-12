package com.ailm.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionImageWideTagEvidenceTest {
    @Test
    fun acceptsOnlyScoredCanonicalIdsAndDeduplicatesByMaximumScore() {
        val scores = FusionImageWideTagEvidence.scoredFeatures(listOf(
            mapOf("tag" to "hc001", "score" to 0.81),
            mapOf("tag" to "HC001", "score" to 0.93),
            mapOf("tag" to "EC001", "score" to 0.87),
            mapOf("tag" to "pink hair", "score" to 0.99),
            mapOf("tag" to "CH123", "score" to Double.NaN),
            mapOf("tag" to "HS002", "score" to 2.0),
            mapOf("tag" to "HC999", "score" to "0.99"),
            mapOf("tag" to "PO004"),
        ))
        assertEquals(setOf("HC001", "EC001"), scores.keys)
        assertEquals(0.93, scores["HC001"]!!, 0.0001)
    }

    @Test
    fun speciesDependentReviewFeaturesUseCanonicalParentsAndSubtypes() {
        val refs = mapOf(
            "SP005" to CanonicalObservationPolicy.Descriptor("SP005", name = "Rare species",
                parentId = "SP002"),
            "SP002" to CanonicalObservationPolicy.Descriptor("SP002", name = "Parent species"),
            "SA013" to CanonicalObservationPolicy.Descriptor(
                "SA013", parentId = "SP002", name = "Special appendage",
                traitRequirement = "optional"),
            "SA014" to CanonicalObservationPolicy.Descriptor(
                "SA014", parentId = "SP002", name = "Excluded appendage",
                speciesRules = mapOf("SP005" to "forbidden")),
        )
        val yes = FusionImageWideTagEvidence.reviewCandidateFeatures(
            mapOf("SP005" to 0.92, "SA013" to 0.88, "SA014" to 0.91),
        ) { refs[it] }
        assertEquals(setOf("SP005", "SA013"), yes.keys)
        val no = FusionImageWideTagEvidence.reviewCandidateFeatures(
            mapOf("SA013" to 0.88),
        ) { refs[it] }
        assertTrue(no.isEmpty())
    }

    @Test
    fun retainsHierarchicalTaxonomyIdsForVisualReviewEvidence() {
        val observed = FusionImageWideTagEvidence.scoredFeatures(listOf(
            mapOf("tag" to "OF016-1-12", "score" to 0.87),
            mapOf("tag" to "of016-1-12", "score" to 0.92),
            mapOf("tag" to "WP001-3", "score" to 0.83),
            mapOf("tag" to "OF016-BOGUS", "score" to 0.98),
        ))
        assertEquals(setOf("OF016-1-12", "WP001-3"), observed.keys)
        assertEquals(0.92, observed["OF016-1-12"]!!, 0.0001)
        val descriptor = CanonicalObservationPolicy.Descriptor(
            "OF016-1-12", name = "Nested outfit",
        )
        val review = FusionImageWideTagEvidence.reviewCandidateFeatures(
            observed,
        ) { if (it == "OF016-1-12") descriptor else null }
        assertEquals(mapOf("OF016-1-12" to 0.92), review)
    }

    @Test
    fun missingOrMalformedOutputsCannotProduceCharacterEvidence() {
        assertTrue(FusionImageWideTagEvidence.scoredFeatures(null).isEmpty())
        assertTrue(FusionImageWideTagEvidence.scoredFeatures(mapOf("tags" to listOf("HC001"))).isEmpty())
        assertTrue(FusionImageWideTagEvidence.scoredFeatures(listOf("HC001", null, 0.95)).isEmpty())
    }
}
