package com.ailm.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionNsfwRatingPolicyTest {
    @Test fun drawingsIsNotEvidenceOfSafeRating() {
        assertNull(FusionNsfwRatingPolicy.evaluate(
            listOf("drawings" to .98, "hentai" to .01),
        ))
    }
    @Test fun lowSeparationDoesNotCreateRating() {
        assertNull(FusionNsfwRatingPolicy.evaluate(
            listOf("porn" to .75, "hentai" to .20),
        ))
    }
    @Test fun confidentExplicitDomainMapsToExistingRatingConcept() {
        val evidence = FusionNsfwRatingPolicy.evaluate(
            listOf("hentai" to .96, "sexy" to .015, "drawings" to .01),
        )
        assertEquals("Explicit", evidence?.canonicalName)
        assertEquals("hentai", evidence?.originatingClass)
    }
    @Test fun neutralAbstainsRatherThanDeclaringGeneralRating() {
        assertNull(FusionNsfwRatingPolicy.evaluate(
            listOf("neutral" to .99, "porn" to .005),
        ))
    }
    @Test fun resultDecoderRejectsMissingScores() {
        val scores = FusionNsfwRatingPolicy.parseRanked(mapOf(
            "ranked_labels" to listOf(
                mapOf("label" to "porn", "score" to 0.98),
                mapOf("label" to "sexy", "score" to "not-number"),
            ),
        ))
        assertEquals(1, scores.size)
        assertTrue(scores.single().first == "porn")
    }
}
