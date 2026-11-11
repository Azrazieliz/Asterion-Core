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
            listOf("porn" to .75, "hentai" to .05, "drawings" to .20),
        ))
    }
    @Test fun confidentHentaiIsNsfw() {
        val evidence = FusionNsfwRatingPolicy.evaluate(
            listOf("hentai" to .96, "sexy" to .015, "drawings" to .01),
        )
        assertEquals("NSFW", evidence?.canonicalName)
        assertEquals("hentai", evidence?.originatingClass)
    }
    @Test fun pornAndHentaiAreOneNsfwBucket() {
        val evidence = FusionNsfwRatingPolicy.evaluate(
            listOf("hentai" to .47, "porn" to .47, "sexy" to .025, "drawings" to .02),
        )
        assertEquals("NSFW", evidence?.canonicalName)
        assertEquals(.94, evidence!!.confidence, .00001)
    }
    @Test fun confidentSuggestiveIsEcchi() {
        assertEquals("Ecchi", FusionNsfwRatingPolicy.evaluate(
            listOf("sexy" to .97, "drawings" to .01),
        )?.canonicalName)
    }
    @Test fun stronglyNeutralPhotosCanBeSfw() {
        assertEquals("SFW", FusionNsfwRatingPolicy.evaluate(
            listOf("neutral" to .97, "porn" to .01, "drawings" to .015),
        )?.canonicalName)
    }
    @Test fun uncertainNeutralDoesNotMeanSafe() {
        assertNull(FusionNsfwRatingPolicy.evaluate(
            listOf("neutral" to .69, "drawings" to .28),
        ))
    }
    @Test fun legacyFusionLabelsRemainResolvable() {
        assertTrue("Explicit" in FusionNsfwRatingPolicy.existingFusionNames("NSFW"))
        assertTrue("Suggestive" in FusionNsfwRatingPolicy.existingFusionNames("Ecchi"))
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
