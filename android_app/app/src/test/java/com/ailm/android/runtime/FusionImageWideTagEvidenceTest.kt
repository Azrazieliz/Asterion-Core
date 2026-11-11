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
    fun missingOrMalformedOutputsCannotProduceCharacterEvidence() {
        assertTrue(FusionImageWideTagEvidence.scoredFeatures(null).isEmpty())
        assertTrue(FusionImageWideTagEvidence.scoredFeatures(mapOf("tags" to listOf("HC001"))).isEmpty())
        assertTrue(FusionImageWideTagEvidence.scoredFeatures(listOf("HC001", null, 0.95)).isEmpty())
    }
}
