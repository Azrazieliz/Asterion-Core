package com.ailm.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class FusionVisualLabelMapperTest {
    private val tags = listOf(
        FusionTaxonomyEngine.Candidate("HC001", "Black Hair", "Hair Color", listOf("black")),
        FusionTaxonomyEngine.Candidate("EC001", "Black Eyes", "Eye Color", listOf("black")),
        FusionTaxonomyEngine.Candidate("HC002", "Pink Hair", "Hair Color", listOf("pink")),
        FusionTaxonomyEngine.Candidate("HL001", "Long Hair", "Hair Length", listOf("long")),
        FusionTaxonomyEngine.Candidate("PO001", "Standing", "Pose"),
    )

    @Test
    fun ambiguousLabelsCannotBecomeArbitraryCanonicalIds() {
        val result = FusionVisualLabelMapper.evaluateCandidates(
            tags,
            scores = listOf(FusionVisualLabelMapper.LabelScore("black", .99)),
            modelVocabulary = listOf("black"),
            modelId = "test-classifier",
        )
        @Suppress("UNCHECKED_CAST")
        val selected = result["tags"] as List<String>
        assertTrue(selected.isEmpty())
        assertEquals(0, result["evaluated_families"])
        @Suppress("UNCHECKED_CAST")
        val unmapped = result["unmapped_labels"] as List<String>
        assertTrue("black" in unmapped)
    }

    @Test
    fun independentFamiliesGetExplicitCoverageWithoutFakeTags() {
        val result = FusionVisualLabelMapper.evaluateCandidates(
            tags,
            scores = listOf(FusionVisualLabelMapper.LabelScore("Pink Hair", .95)),
            modelVocabulary = listOf("Pink Hair", "Standing"),
            modelId = "test-classifier",
        )
        @Suppress("UNCHECKED_CAST")
        val accepted = result["tags"] as List<String>
        assertEquals(listOf("HC002"), accepted)
        @Suppress("UNCHECKED_CAST")
        val coverage = result["family_coverage"] as List<Map<String, Any>>
        assertTrue(coverage.any { it["family"] == "HL" && it["status"] == "unsupported_model" })
        assertTrue(coverage.any { it["family"] == "PO" && it["status"] == "evaluated_no_confident_match" })
        assertTrue(coverage.any { it["family"] == "HC" && it["status"] == "accepted" })
    }

    @Test
    fun insufficientConfidenceDoesNotCreateCanonicalTag() {
        val result = FusionVisualLabelMapper.evaluateCandidates(
            tags,
            scores = listOf(
                FusionVisualLabelMapper.LabelScore("Standing", .78),
                FusionVisualLabelMapper.LabelScore("Pink Hair", .81),
            ),
            modelVocabulary = listOf("Standing", "Pink Hair"),
            modelId = "test-classifier",
        )
        @Suppress("UNCHECKED_CAST")
        val accepted = result["tags"] as List<String>
        assertTrue(accepted.isEmpty())
    }

    @Test
    fun canonicalIdsAreAcceptedOnlyIfPresentInFusion() {
        val result = FusionVisualLabelMapper.evaluateCandidates(
            tags,
            scores = listOf(
                FusionVisualLabelMapper.LabelScore("HC999", .99),
                FusionVisualLabelMapper.LabelScore("HC001", .91),
            ),
            modelVocabulary = listOf("HC999", "HC001"),
            modelId = "test-classifier",
        )
        @Suppress("UNCHECKED_CAST")
        val accepted = result["tags"] as List<String>
        assertEquals(listOf("HC001"), accepted)
        assertFalse(accepted.contains("HC999"))
    }

    @Test
    fun familySupportsMultipleGenuinelyScoredColors() {
        val result = FusionVisualLabelMapper.evaluateCandidates(
            tags,
            scores = listOf(
                FusionVisualLabelMapper.LabelScore("Black Hair", .93),
                FusionVisualLabelMapper.LabelScore("Pink Hair", .91),
            ),
            modelVocabulary = listOf("Black Hair", "Pink Hair"),
            modelId = "test-classifier",
        )
        @Suppress("UNCHECKED_CAST")
        val accepted = result["tags"] as List<String>
        assertEquals(setOf("HC001", "HC002"), accepted.toSet())
    }

    @Test
    fun outOfVocabularyPredictionCannotClaimRecognizedFusionTag() {
        val result = FusionVisualLabelMapper.evaluateCandidates(
            tags,
            scores = listOf(FusionVisualLabelMapper.LabelScore("HC001", .99)),
            modelVocabulary = listOf("Standing", "Pink Hair"),
            modelId = "fixed-head-model",
        )
        @Suppress("UNCHECKED_CAST")
        val accepted = result["tags"] as List<String>
        assertTrue(accepted.isEmpty())
        @Suppress("UNCHECKED_CAST")
        val unmapped = result["unmapped_labels"] as List<String>
        assertTrue("HC001" in unmapped)
    }

    @Test
    fun repeatedModelVocabularyLabelIsNotUniqueEvidence() {
        val result = FusionVisualLabelMapper.evaluateCandidates(
            tags,
            scores = listOf(FusionVisualLabelMapper.LabelScore("Pink Hair", .99)),
            modelVocabulary = listOf("Pink Hair", "pink-hair", "Standing"),
            modelId = "malformed-head-model",
        )
        @Suppress("UNCHECKED_CAST")
        val accepted = result["tags"] as List<String>
        assertTrue(accepted.isEmpty())
        @Suppress("UNCHECKED_CAST")
        val coverage = result["family_coverage"] as List<Map<String, Any>>
        assertTrue(coverage.any { it["family"] == "HC" && it["status"] == "unsupported_model" })
    }
}
