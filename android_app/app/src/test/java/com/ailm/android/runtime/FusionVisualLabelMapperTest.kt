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
    fun trainedHairLabelCanResolveToLiveFusionShortCanonicalName() {
        val dynamicTags = listOf(
            FusionTaxonomyEngine.Candidate("HC091", "Blue", "Hair Color"),
            FusionTaxonomyEngine.Candidate("EC091", "Blue", "Eye Color"),
            FusionTaxonomyEngine.Candidate("RT091", "SFW", "Content Rating"),
        )
        val result = FusionVisualLabelMapper.evaluateCandidates(
            dynamicTags,
            scores = listOf(FusionVisualLabelMapper.LabelScore("blue_hair", .92)),
            modelVocabulary = listOf("blue_hair", "blue_eyes"),
            modelId = "fixed-head-tagger",
        )
        @Suppress("UNCHECKED_CAST")
        val accepted = result["tags"] as List<String>
        assertEquals(listOf("HC091"), accepted)
        @Suppress("UNCHECKED_CAST")
        val coverage = result["family_coverage"] as List<Map<String, Any>>
        assertTrue(coverage.any { it["family"] == "RT" && it["status"] == "unsupported_model" })
    }

    @Test
    fun visuallyAmbiguousShortLabelStillAbstains() {
        val result = FusionVisualLabelMapper.evaluateCandidates(
            listOf(
                FusionTaxonomyEngine.Candidate("HC010", "Blue", "Hair Color"),
                FusionTaxonomyEngine.Candidate("EC010", "Blue", "Eye Color"),
            ),
            scores = listOf(FusionVisualLabelMapper.LabelScore("blue", .98)),
            modelVocabulary = listOf("blue", "blue_hair", "blue_eyes"),
            modelId = "fixed-head-tagger",
        )
        @Suppress("UNCHECKED_CAST")
        val accepted = result["tags"] as List<String>
        assertTrue(accepted.isEmpty())
    }

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
    @Test
    fun elfMandatoryEarsAreGraphExpectationNotFabricatedClassifierResult() {
        val catalog = listOf(
            FusionTaxonomyEngine.Candidate("SP002", "Elf", "Species"),
            FusionTaxonomyEngine.Candidate("SA001", "Pointed Ears", "Species Attributes",
                parentId = "SP002", traitRequirement = "required"),
            FusionTaxonomyEngine.Candidate("SP003", "Dragon", "Species"),
            FusionTaxonomyEngine.Candidate("SA002", "Wings", "Species Attributes",
                parentId = "SP003", traitRequirement = "optional"),
        )
        val result = FusionVisualLabelMapper.evaluateCandidates(
            catalog,
            scores = listOf(FusionVisualLabelMapper.LabelScore("elf", 0.92)),
            modelVocabulary = listOf("elf", "pointy ears", "dragon", "wings"),
            modelId = "asterion-wd-vit-tagger-v3",
            minimumScore = 0.40,
        )
        @Suppress("UNCHECKED_CAST")
        val accepted = result["tags"] as List<String>
        assertEquals(listOf("SP002"), accepted)
        @Suppress("UNCHECKED_CAST")
        val expectations = result["species_trait_expectations"] as List<Map<String, Any>>
        assertEquals(listOf("SA001"), expectations.map { it["attribute_id"] })
        assertEquals(false, expectations.single()["observed_in_image"])
    }

    @Test
    fun trainedPointyEarsAndGreyHairMatchExistingFusionWithoutInventingTags() {
        val catalog = listOf(
            FusionTaxonomyEngine.Candidate("SA201", "Pointed Ears", "Species Attributes"),
            FusionTaxonomyEngine.Candidate("HC214", "Gray", "Hair Color"),
            FusionTaxonomyEngine.Candidate("HC215", "Multicoloured", "Hair Color"),
        )
        val result = FusionVisualLabelMapper.evaluateCandidates(
            tags = catalog,
            scores = listOf(
                FusionVisualLabelMapper.LabelScore("pointy_ears", 0.95),
                FusionVisualLabelMapper.LabelScore("grey_hair", 0.89),
                FusionVisualLabelMapper.LabelScore("multicolored_hair", 0.87),
            ),
            modelVocabulary = listOf("pointy_ears", "grey_hair", "multicolored_hair"),
            modelId = "asterion-wd-vit-tagger-v3",
            minimumScore = 0.40,
        )
        @Suppress("UNCHECKED_CAST")
        val accepted = result["tags"] as List<String>
        // SP can remain uncertain. A visible high-confidence SA observation
        // from this subject crop must not be discarded at the family transition.
        assertEquals(setOf("SA201", "HC214", "HC215"), accepted.toSet())
        @Suppress("UNCHECKED_CAST")
        val deferred = result["deferred_scored_tags"] as List<Map<String, Any>>
        assertTrue(deferred.isEmpty())
    }

    @Test
    fun imageWideSpeciesTraitAndHairLengthConflictAreReviewableNotApproved() {
        val catalog = listOf(
            FusionTaxonomyEngine.Candidate("HC014", "Gray", "Hair Color"),
            FusionTaxonomyEngine.Candidate("HL005", "Long", "Hair Length"),
            FusionTaxonomyEngine.Candidate("HL006", "Very Long", "Hair Length"),
            FusionTaxonomyEngine.Candidate("SA013", "Halo", "Species Attributes"),
        )
        val result = FusionVisualLabelMapper.evaluateCandidates(
            tags = catalog,
            scores = listOf(
                FusionVisualLabelMapper.LabelScore("gray hair", 0.85),
                FusionVisualLabelMapper.LabelScore("long hair", 0.84),
                FusionVisualLabelMapper.LabelScore("very long hair", 0.82),
                FusionVisualLabelMapper.LabelScore("halo", 0.93),
            ),
            modelVocabulary = listOf("gray hair", "long hair", "very long hair", "halo"),
            modelId = "asterion-wd-vit-tagger-v3",
            minimumScore = 0.40,
        )
        @Suppress("UNCHECKED_CAST")
        val accepted = result["tags"] as List<String>
        assertEquals(setOf("HC014", "SA013"), accepted.toSet())
        @Suppress("UNCHECKED_CAST")
        val deferred = result["deferred_scored_tags"] as List<Map<String, Any>>
        assertEquals(setOf("HL005", "HL006"), deferred.map { it["tag"] }.toSet())
        assertTrue(deferred.all { (it["score"] as Double) > 0.80 })
        @Suppress("UNCHECKED_CAST")
        val coverage = result["family_coverage"] as List<Map<String, Any>>
        assertEquals("accepted", coverage.first { it["family"] == "SA" }["status"])
        assertEquals(2, coverage.first { it["family"] == "HL" }["deferred_count"])
        assertEquals("accepted", coverage.first { it["family"] == "HC" }["status"])
    }

    @Test
    fun nestedKnowledgeIdsSurviveFixedHeadMapperWithoutInventingNewTags() {
        val candidates = listOf(
            FusionTaxonomyEngine.Candidate("OF016-1-12", "Beret", "Outfit"),
            FusionTaxonomyEngine.Candidate("WP001-3", "Katana", "Weapon"),
        )
        val result = FusionVisualLabelMapper.evaluateCandidates(
            candidates,
            listOf(
                FusionVisualLabelMapper.LabelScore("OF016-1-12", .93),
                FusionVisualLabelMapper.LabelScore("WP001-3", .88),
            ),
            listOf("OF016-1-12", "WP001-3"),
            "asterion-wd-vit-tagger-v3",
        )
        @Suppress("UNCHECKED_CAST")
        val actual = result["tags"] as List<String>
        assertEquals(setOf("OF016-1-12", "WP001-3"), actual.toSet())
    }

    @Test
    fun wdSingleSubjectGirlAndBoyCanMatchExistingSxWithoutInventingSp() {
        val sx = listOf(
            FusionTaxonomyEngine.Candidate("SX001", "Female", "Sex"),
            FusionTaxonomyEngine.Candidate("SX002", "Male", "Sex"),
            FusionTaxonomyEngine.Candidate("SP001", "Human", "Species"),
        )
        val classifier = FusionVisualLabelMapper.evaluateCandidates(
            tags = sx,
            scores = listOf(FusionVisualLabelMapper.LabelScore("1girl", .92)),
            modelVocabulary = listOf("1girl", "1boy"),
            modelId = "asterion-wd-vit-tagger-v3", minimumScore = .40,
            subjectScoped = true,
        )
        assertEquals(listOf("SX001"), classifier["tags"])
        val fullImage = FusionVisualLabelMapper.evaluateCandidates(
            tags = sx,
            scores = listOf(FusionVisualLabelMapper.LabelScore("1boy", .95)),
            modelVocabulary = listOf("1girl", "1boy"),
            modelId = "asterion-wd-vit-tagger-v3", minimumScore = .40,
            subjectScoped = false,
        )
        assertEquals(emptyList<String>(), fullImage["tags"])
        @Suppress("UNCHECKED_CAST")
        val deferred = fullImage["deferred_scored_tags"] as List<Map<String, Any>>
        assertEquals(listOf("SX002"), deferred.map { it["tag"] })
    }

    @Test
    fun familyPlanAlwaysAccountsForCurrentAndFutureFusionFamilies() {
        assertEquals(29, FusionTagFamilyOrder.established.size)
        assertEquals(29, FusionTagFamilyOrder.established.distinct().size)
        assertTrue(FusionTagFamilyOrder.established.indexOf("SA") <
            FusionTagFamilyOrder.established.indexOf("SP"))
        assertEquals(listOf("SA", "SP", "HC", "EC", "PO", "WP", "EN", "ZZ"),
            FusionTagFamilyOrder.ordered(
                listOf("ZZ", "EN", "PO", "HC", "WP", "SA", "SP", "EC", "ZZ")
            ))
    }

    @Test
    fun familyResolutionOrderDoesNotDependOnModelScoreOrder() {
        val candidates = listOf(
            FusionTaxonomyEngine.Candidate("ZZ001", "Future family", "Future"),
            FusionTaxonomyEngine.Candidate("EN001", "Forest", "Environment"),
            FusionTaxonomyEngine.Candidate("HC001", "Blue", "Hair Color"),
            FusionTaxonomyEngine.Candidate("PO001", "Standing", "Pose"),
            FusionTaxonomyEngine.Candidate("EC001", "Green", "Eye Color"),
            FusionTaxonomyEngine.Candidate("WP001", "Sword", "Weapon"),
        )
        val labels = listOf("future family", "forest", "blue hair", "standing", "green eyes", "sword")
        val scrambled = listOf(
            FusionVisualLabelMapper.LabelScore("forest", .97),
            FusionVisualLabelMapper.LabelScore("future family", .96),
            FusionVisualLabelMapper.LabelScore("sword", .95),
            FusionVisualLabelMapper.LabelScore("standing", .94),
            FusionVisualLabelMapper.LabelScore("green eyes", .93),
            FusionVisualLabelMapper.LabelScore("blue hair", .92),
        )
        val result = FusionVisualLabelMapper.evaluateCandidates(
            candidates, scrambled, labels, "fixed-head", minimumScore = .82,
        )
        @Suppress("UNCHECKED_CAST")
        val accepted = result["tags"] as List<String>
        assertEquals(listOf("HC001", "EC001", "PO001", "WP001", "EN001", "ZZ001"), accepted)
        @Suppress("UNCHECKED_CAST")
        val audit = result["family_coverage"] as List<Map<String, Any>>
        assertEquals(listOf("HC", "EC", "PO", "WP", "EN", "ZZ"), audit.map { it["family"] })
        assertEquals((1..6).toList(), audit.map { it["resolution_step"] })
        assertTrue(audit.all { it["status"] == "accepted" })
        assertEquals(true, result["family_resolution_audited"])
    }

    @Test
    fun unsupportedFamiliesAreExplicitAndNeverFilledWithInventedTags() {
        val catalog = listOf(
            FusionTaxonomyEngine.Candidate("SP001", "Elf", "Species"),
            FusionTaxonomyEngine.Candidate("HC001", "Blue", "Hair Color"),
            FusionTaxonomyEngine.Candidate("XX001", "New family", "Extension"),
        )
        val result = FusionVisualLabelMapper.evaluateCandidates(
            catalog, listOf(FusionVisualLabelMapper.LabelScore("blue hair", .98)),
            listOf("blue hair"), "fixed-head", minimumScore = .82,
        )
        @Suppress("UNCHECKED_CAST")
        val audit = result["family_coverage"] as List<Map<String, Any>>
        assertEquals(listOf("SP", "HC", "XX"), audit.map { it["family"] })
        assertEquals(listOf("unsupported_model", "accepted", "unsupported_model"),
            audit.map { it["status"] })
        assertEquals(listOf("HC001"), result["tags"])
    }



    @Test
    fun missingSpeciesDecisionDoesNotVetoLaterVisibleTagFamilies() {
        val catalog = listOf(
            FusionTaxonomyEngine.Candidate("SP002", "Elf", "Species"),
            FusionTaxonomyEngine.Candidate("SA201", "Pointed Ears", "Species Attributes"),
            FusionTaxonomyEngine.Candidate("HC091", "Blue", "Hair Color"),
            FusionTaxonomyEngine.Candidate("EC092", "Green", "Eye Color"),
            FusionTaxonomyEngine.Candidate("ZZ001", "Glowing Tattoo", "Special Features"),
        )
        val evidence = listOf(
            FusionVisualLabelMapper.LabelScore("pointy_ears", 0.94),
            FusionVisualLabelMapper.LabelScore("blue_hair", 0.93),
            FusionVisualLabelMapper.LabelScore("green_eyes", 0.92),
            FusionVisualLabelMapper.LabelScore("glowing_tattoo", 0.88),
        )
        val result = FusionVisualLabelMapper.evaluateCandidates(
            tags = catalog,
            scores = evidence,
            modelVocabulary = listOf("elf", "pointy_ears", "blue_hair", "green_eyes",
                "glowing_tattoo"),
            modelId = "asterion-wd-vit-tagger-v3",
            minimumScore = 0.40,
            subjectScoped = true,
            enforceCompleteCatalog = true,
        )
        @Suppress("UNCHECKED_CAST")
        val selected = result["tags"] as List<String>
        assertEquals(setOf("SA201", "HC091", "EC092", "ZZ001"), selected.toSet())
        assertFalse("SP002" in selected)
        @Suppress("UNCHECKED_CAST")
        val coverage = result["family_coverage"] as List<Map<String, Any>>
        assertEquals("evaluated_no_confident_match", coverage.first { it["family"] == "SP" }["status"])
        assertTrue(coverage.indexOfFirst { it["family"] == "SA" } <
            coverage.indexOfFirst { it["family"] == "SP" })
        for (family in listOf("SA", "HC", "EC", "ZZ")) {
            assertEquals("accepted", coverage.first { it["family"] == family }["status"])
            assertEquals(1, coverage.first { it["family"] == family }["accepted_count"])
        }
        // A missing unrelated family must not abort the rest of the sequence.
        assertEquals("missing_catalog", coverage.first { it["family"] == "AG" }["status"])
        assertEquals(1, coverage.last { it["family"] == "ZZ" }["visual_candidate_comparisons"])
    }


}
