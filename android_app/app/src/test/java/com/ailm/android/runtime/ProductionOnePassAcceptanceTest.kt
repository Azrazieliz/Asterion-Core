package com.ailm.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 gates for the sole production release: character-before-scene attribution,
 * exhaustive live family statuses, Asterion Art stars, and real trained media cues.
 * No mock logits are permitted to become a fake "on-device acceptance" result.
 */
class ProductionOnePassAcceptanceTest {
    private val world = listOf(
        FusionTaxonomyEngine.Candidate("SP016", "Plant", "Species"),
        FusionTaxonomyEngine.Candidate("HC014", "Gray", "Hair Color"),
        FusionTaxonomyEngine.Candidate("EN001", "Forest", "Environment"),
    )
    private val modelVocabulary = listOf(
        "plant", "plant girl", "gray hair", "forest",
    )

    @Test fun decorativePlantMustNotBecomeHumanSpecies() {
        val observation = FusionVisualLabelMapper.evaluateCandidates(
            tags = world,
            scores = listOf(
                FusionVisualLabelMapper.LabelScore("plant", .99),
                FusionVisualLabelMapper.LabelScore("gray hair", .96),
                FusionVisualLabelMapper.LabelScore("forest", .90),
            ),
            modelVocabulary = modelVocabulary,
            modelId = "asterion-wd-vit-tagger-v3",
            minimumScore = .40,
            subjectScoped = false,
            enforceCompleteCatalog = true,
        )
        @Suppress("UNCHECKED_CAST")
        val assigned = observation["tags"] as List<String>
        assertFalse(assigned.contains("SP016"))
        assertFalse(assigned.contains("HC014"))
        assertTrue(assigned.contains("EN001"))
        @Suppress("UNCHECKED_CAST")
        val audit = observation["family_coverage"] as List<Map<String, Any>>
        assertEquals("missing_catalog", audit.first { it["family"] == "EC" }["status"])
        assertEquals("missing_catalog", audit.first { it["family"] == "ET" }["status"])
        assertEquals(false, observation["family_resolution_audited"])
        val deferred = observation["deferred_scored_tags"] as List<*>
        assertTrue(deferred.isNotEmpty())
    }

    @Test fun personCropDoesNotTurnPottedPlantIntoCharacterSpecies() {
        val seen = FusionVisualLabelMapper.evaluateCandidates(
            tags = world,
            scores = listOf(
                FusionVisualLabelMapper.LabelScore("plant", .99),
                FusionVisualLabelMapper.LabelScore("gray hair", .94),
            ),
            modelVocabulary = modelVocabulary,
            modelId = "asterion-wd-vit-tagger-v3",
            minimumScore = .40,
            subjectScoped = true,
        )
        @Suppress("UNCHECKED_CAST")
        val assigned = seen["tags"] as List<String>
        assertFalse(assigned.contains("SP016"))
        assertTrue(assigned.contains("HC014"))
    }

    @Test fun genuinePlantCharacterCanStillBeRecognizedFromSubjectEvidence() {
        val seen = FusionVisualLabelMapper.evaluateCandidates(
            tags = world,
            scores = listOf(
                FusionVisualLabelMapper.LabelScore("plant girl", .94),
                FusionVisualLabelMapper.LabelScore("plant", .98),
            ),
            modelVocabulary = modelVocabulary,
            modelId = "asterion-wd-vit-tagger-v3",
            minimumScore = .40,
            subjectScoped = true,
        )
        @Suppress("UNCHECKED_CAST")
        val assigned = seen["tags"] as List<String>
        assertTrue(assigned.contains("SP016"))
    }

    @Test fun taxonomyFirstHandlesFutureFamiliesWithoutFakeSuccess() {
        val catalog = world + FusionTaxonomyEngine.Candidate(
            "ZZ001", "New Untrained Tag", "Extension",
        )
        val seen = FusionVisualLabelMapper.evaluateCandidates(
            catalog, scores = listOf(
                FusionVisualLabelMapper.LabelScore("forest", .87),
            ),
            modelVocabulary = modelVocabulary,
            modelId = "asterion-wd-vit-tagger-v3",
            minimumScore = .40, subjectScoped = false, enforceCompleteCatalog = true,
        )
        @Suppress("UNCHECKED_CAST")
        val audit = seen["family_coverage"] as List<Map<String, Any>>
        assertEquals(listOf("SA", "SP", "SX"), audit.take(3).map { it["family"] })
        assertEquals("unsupported_model", audit.last()["status"])
        assertEquals("ZZ", audit.last()["family"])
        assertTrue(audit.all { (it["resolution_step"] as Int) > 0 })
    }

    @Test fun userStarsOverrideAutomaticArtQualityAndFeedbackIsBounded() {
        assertEquals(3, ArtworkRatingPolicy.rawAutomaticStars(6.0))
        assertEquals(5, ArtworkRatingPolicy.rawAutomaticStars(10.0))
        assertNull(ArtworkRatingPolicy.rawAutomaticStars(Double.NaN))
        assertEquals(2, ArtworkRatingPolicy.preferredStars(2, 5))
        assertEquals(4, ArtworkRatingPolicy.preferredStars(0, 4))
        val learned = ArtworkRatingPolicy.revise(
            ArtworkRatingPolicy.Calibration(), automaticStars = 2,
            previousManual = 0, nextManual = 5,
        )
        assertEquals(1, learned.count)
        assertTrue(learned.bias in -1.5..1.5)
        assertEquals(5, ArtworkRatingPolicy.preferredStars(
            5, ArtworkRatingPolicy.calibratedStars(4.0, learned.bias)))
    }

    @Test fun cosplayUsesActualWdVocabularyInsteadOfNonexistentPhotoMedium() {
        val actual = listOf("realistic", "photorealistic", "cosplay", "anime coloring")
        val seen = VisualMediumPolicy.fromNativeScores(
            scores = listOf(
                "realistic" to .85, "cosplay" to .82, "anime coloring" to .02,
            ),
            modelVocabulary = actual,
            modelId = "asterion-wd-vit-tagger-v3",
        )
        assertNotNull(seen)
        assertEquals("cosplay", seen!!.medium)
        assertNotNull(VisualMediumPolicy.accept(
            mapOf("kind" to "cosplay", "confidence" to seen.confidence,
                "evidence" to seen.evidence)))
    }
}
