package com.ailm.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class FusionVisualLabelMapperTest {
    @Test fun identicalGarmentNamesOfferScopedRoleChoicesWithoutRecognizingEitherId() {
        val tags = listOf("OF002-5","OF009-2").map {
            FusionTaxonomyEngine.Candidate(it,"School Swimsuit","outfit",characterScope="both")
        }
        val result = FusionVisualLabelMapper.evaluateCandidates(tags,
            listOf(FusionVisualLabelMapper.LabelScore("school_swimsuit",.94)),listOf("school_swimsuit"),"WD")
        assertEquals(emptyList<String>(), result["tags"])
        val group = (result["role_review_choices"] as List<*>).single() as Map<*,*>
        val choices = (group["choices"] as List<*>).filterIsInstance<Map<*,*>>()
        assertEquals(tags.map { it.id }.toSet(),choices.map { it["id"] }.toSet())
    }
    @Test fun independentFacetsCanShareLiteralEvidenceWhileUnobservableFactsRequireConfirmation() {
        val tags = listOf(FusionTaxonomyEngine.Candidate("AC005-3","Holding Hands","AC"),
            FusionTaxonomyEngine.Candidate("GE055","Holding Hands","GE"),
            FusionTaxonomyEngine.Candidate("SX004","Sexless","SX"))
        val result = FusionVisualLabelMapper.evaluateCandidates(tags,
            listOf(FusionVisualLabelMapper.LabelScore("holding_hands",.96),
                FusionVisualLabelMapper.LabelScore("SX004",1.0)),listOf("holding_hands","SX004"),"fixture")
        assertEquals(setOf("AC005-3","GE055"),(result["tags"] as List<*>).toSet())
    }
    @Test fun allInstalledFamiliesReportUntrainedCandidatesAndActualComparisons() {
        val families = "SX HC HL HS EC ET BH SC BT BS BY AG SA SP AC GE PO OF WP FR OR CA EN WE LI RT EY MO EX".split(" ")
        val tags = families.flatMap { family -> listOf("001","002").map { suffix ->
            FusionTaxonomyEngine.Candidate(family + suffix,"fixture " + suffix, family, characterScope = "both")
        } }
        val vocabulary = tags.filter { it.id.endsWith("001") }.map { it.id }
        for (subject in listOf(true,false)) {
            val result = FusionVisualLabelMapper.evaluateCandidates(tags,
                vocabulary.map { FusionVisualLabelMapper.LabelScore(it,.01) },vocabulary,"fixture",
                subjectScoped = subject,restrictToRegion = true)
            val coverage = (result["family_coverage"] as List<*>).filterIsInstance<Map<*,*>>()
            assertEquals(families.toSet(), coverage.map { it["family"] }.toSet())
            coverage.filter { it["family"] != "RT" }.forEach {
                assertEquals(1,it["visual_candidate_comparisons"])
                assertEquals(false,it["comparison_complete"])
                assertEquals("visually_indeterminate",it["observation_outcome"])
            }
        }
    }
    @Test fun supportedEquivalentLabelsUseTheExecutingHeadAndKeepSubjectsIndependent() {
        val catalog = listOf(FusionTaxonomyEngine.Candidate("GE001","Peace Sign","GE",characterScope="both"))
        fun crop(score: Double) = FusionVisualLabelMapper.evaluateCandidates(catalog,
            listOf(FusionVisualLabelMapper.LabelScore("v",score)), listOf("v"), "WD", subjectScoped=true)
        assertEquals(listOf("GE001"),crop(.98)["tags"])
        assertEquals(emptyList<String>(),crop(.01)["tags"])
    }

    @Test fun trainedFusionIdUsesValidatedPerLabelBoundaryWithoutHidingComparison() {
        val catalog = listOf(
            FusionTaxonomyEngine.Candidate("ZZ042", "Original visual feature", "ZZ",
                characterScope = "subject"),
        )
        fun score(value: Double) = FusionVisualLabelMapper.evaluateCandidates(
            catalog, listOf(FusionVisualLabelMapper.LabelScore("ZZ042", value)),
            listOf("ZZ042"), "pinned-feature-model",
            minimumScore = 0.32, minimumScoreById = mapOf("ZZ042" to 0.75),
            subjectScoped = true, restrictToRegion = true,
        )
        val low = score(0.61)
        assertTrue((low["tags"] as List<*>).isEmpty())
        val receipts = (low["family_coverage"] as List<*>).single() as Map<*, *>
        assertEquals(1, receipts["visual_candidate_comparisons"])
        assertEquals(1, receipts["trained_label_count"])
        assertEquals("evaluated_no_confident_match", receipts["status"])
        assertEquals(listOf("ZZ042"), score(0.89)["tags"])
    }

    @Test fun directFusionHeadAndIndividualCandidateScopesTakePrecedence() {
        val catalog = listOf(
            FusionTaxonomyEngine.Candidate("ZZ001", "Spark", "ZZ", characterScope = "subject"),
            FusionTaxonomyEngine.Candidate("ZZ002", "Cloud", "ZZ", characterScope = "image"),
            FusionTaxonomyEngine.Candidate("ZZ003", "Stripe", "ZZ", characterScope = "both"),
        )
        val scores = listOf("ZZ001" to .30, "Spark" to .99, "ZZ002" to .95, "ZZ003" to .96)
        fun result(subject: Boolean) = FusionVisualLabelMapper.evaluateCandidates(
            catalog, scores.map { FusionVisualLabelMapper.LabelScore(it.first, it.second) },
            scores.map { it.first }, "fixture", subjectScoped = subject, restrictToRegion = true,
        )
        assertEquals(listOf("ZZ003"), result(true)["tags"])
        assertEquals(setOf("ZZ002", "ZZ003"), (result(false)["tags"] as List<*>).toSet())
        val row = (result(false)["family_coverage"] as List<*>).single() as Map<*, *>
        assertEquals(2, row["visual_candidate_comparisons"])
        assertEquals(2, row["candidate_count"])
    }

    @Test fun mixedFamilySceneValuesSurviveSceneryPassAndUseTheSameCorrectionScope() {
        val mixed = FusionTaxonomyEngine.Candidate("ZZ001", "Arc", "ZZ", characterScope = "both")
        val local = FusionTaxonomyEngine.Candidate("ZZ002", "Trace", "ZZ", characterScope = "subject")
        val image = FusionTaxonomyEngine.Candidate("OF999", "Display", "OF", characterScope = "image")
        assertFalse(FusionVisualLabelMapper.acceptsRegion(image, true))
        assertTrue(FusionVisualLabelMapper.acceptsRegion(image, false))
        assertTrue(FusionVisualLabelMapper.acceptsRegion(mixed, true))
        assertTrue(FusionVisualLabelMapper.acceptsRegion(mixed, false))
        val labels = listOf("ZZ001", "ZZ002", "OF999")
        val result = FusionVisualLabelMapper.evaluateCandidates(listOf(mixed, local, image),
            labels.map { FusionVisualLabelMapper.LabelScore(it, .99) }, labels, "fixture",
            subjectScoped = false, restrictToRegion = true, sceneryOrder = true)
        assertEquals(setOf("ZZ001", "OF999"), (result["tags"] as List<*>).toSet())
    }

    @Test fun codeOnlyCatalogUsesBodyPartQualifiedEvidence() {
        val catalog = listOf(
            FusionTaxonomyEngine.Candidate("HC091", "Orange", "HC"),
            FusionTaxonomyEngine.Candidate("EC091", "Gray", "EC"),
        )
        val labels = listOf("orange", "orange_hair", "grey_eyes")
        val scores = labels.zip(listOf(.99, .12, .94)).map { (label, score) ->
            FusionVisualLabelMapper.LabelScore(label, score)
        }
        val result = FusionVisualLabelMapper.evaluateCandidates(catalog, scores, labels, "native")
        assertEquals(listOf("EC091"), result["tags"])
        assertEquals(listOf("orange"), result["unmapped_labels"])
    }

    @Test fun scopesCompareOnlyApplicableFamiliesAndSceneryLeadsWithEnvironment() {
        val catalog = listOf(
            FusionTaxonomyEngine.Candidate("HC001", "Blue", "HC"),
            FusionTaxonomyEngine.Candidate("EN001", "Forest", "EN"),
            FusionTaxonomyEngine.Candidate("OF001", "Jacket", "OF"),
            FusionTaxonomyEngine.Candidate("FR001", "Close-up", "FR"),
        )
        val labels = listOf("blue_hair", "forest", "jacket", "close_up")
        val scores = labels.map { FusionVisualLabelMapper.LabelScore(it, .98) }
        fun order(subject: Boolean) = FusionVisualLabelMapper.evaluateCandidates(
            catalog, scores, labels, "native", subjectScoped = subject,
            restrictToRegion = true, sceneryOrder = !subject,
        )["family_resolution_order"]
        assertEquals(listOf("HC", "OF"), order(true))
        assertEquals(listOf("EN", "FR", "OF"), order(false))
    }

    @Test fun sharedCanonicalFacetsAndShortStatesUseOnlyTheCorrectHead() {
        val catalog = listOf(
            FusionTaxonomyEngine.Candidate("ET005", "Spiral Eyes", "ET"),
            FusionTaxonomyEngine.Candidate("EY018", "Spiral Eyes", "EY"),
            FusionTaxonomyEngine.Candidate("MO001", "Closed", "MO"),
            FusionTaxonomyEngine.Candidate("EY003", "Closed", "EY"),
        )
        val labels = listOf("spiral_eyes", "closed", "closed_mouth", "closed_eyes")
        fun result(vararg observed: String) = FusionVisualLabelMapper.evaluateCandidates(
            catalog, observed.map { FusionVisualLabelMapper.LabelScore(it, .95) },
            labels, "native",
        )["tags"] as List<*>
        assertEquals(setOf("ET005", "EY018"), result("spiral_eyes").toSet())
        assertEquals(listOf("MO001"), result("closed", "closed_mouth"))
        assertEquals(listOf("EY003"), result("closed_eyes"))
    }

    @Test fun allSuppliedFamilyNamespacesReceiveRegionAppropriateReceipts() {
        // Representative canonical records from the supplied packs. The test
        // head has three measured channels; the other candidates must abstain.
        val fixture = listOf(
            "AC001 Movement", "RT001 Safe", "AG001 Child", "BH001 Very Short",
            "BT001 Petite", "BS001 Flat", "BY001 Amputee", "SX001 Male",
            "EN001 Bedroom", "WE001 Clear", "EX001 Neutral", "EY001 Open",
            "MO001 Closed", "EC001 Black", "ET001 Heterochromia", "FR001 Extreme Close-Up",
            "OR001 Front View", "CA001 Eye Level", "LI001 Daylight", "GE001 Peace Sign",
            "HC001 Black", "HL001 Bald", "HS001 Straight", "OF001 Casual", "PO001 Standing",
            "SC001 Very Fair", "SP001 Human", "SA001 Pointed Ears", "WP001 Melee",
        ).map {
            val id = it.substringBefore(' ')
            FusionTaxonomyEngine.Candidate(id, it.substringAfter(' '), id.take(2))
        }
        val labels = listOf("black_hair", "straight_hair", "bedroom", "straight")
        fun evaluate(subject: Boolean) = FusionVisualLabelMapper.evaluateCandidates(
            fixture, labels.map { FusionVisualLabelMapper.LabelScore(it, .95) },
            labels, "fixture", subjectScoped = subject, restrictToRegion = true,
            enforceCompleteCatalog = true,
        )
        val subject = evaluate(true)
        val scene = evaluate(false)
        assertEquals(setOf("HC001", "HS001"), (subject["tags"] as List<*>).toSet())
        assertEquals(listOf("EN001"), scene["tags"])
        val receipts = (listOf(subject, scene).flatMap { it["family_coverage"] as List<*> })
            .map { it as Map<*, *> }
        assertEquals(fixture.map { it.family }.toSet(), receipts.map { it["family"] }.toSet())
        assertTrue(receipts.all { it["candidate_count"] == 1 })
        assertEquals(setOf("HC", "HS", "EN"), receipts.filter {
            it["comparison_complete"] == true
        }.map { it["family"] }.toSet())
        assertTrue(receipts.filter { it["family"] == "RT" }.all {
            it["status"] == "independent_rating_stage"
        })
    }

    @Test fun subjectCropCanResolveWorkbookStyleNames() {
        val catalog = listOf(
            FusionTaxonomyEngine.Candidate("HS018", "Bun", "Hair Style"),
            FusionTaxonomyEngine.Candidate("HS028", "Side Locks", "Hair Style"),
        )
        val vocabulary = listOf("hair_bun", "sidelocks")
        val result = FusionVisualLabelMapper.evaluateCandidates(catalog,
            vocabulary.map { FusionVisualLabelMapper.LabelScore(it, .98) }, vocabulary, "fixture")
        assertEquals(setOf("HS018", "HS028"), (result["tags"] as List<*>).toSet())
    }

    @Test fun refreshedEquivalentVocabularyReusesTheCompiledCandidateList() {
        val catalog = listOf(FusionTaxonomyEngine.Candidate("HS018", "Bun", "HS"))
        val first = FusionVisualLabelMapper.candidateHeadLabels(catalog, arrayListOf("hair_bun"))
        val second = FusionVisualLabelMapper.candidateHeadLabels(catalog, arrayListOf("hair_bun"))
        assertTrue(first === second)
        assertTrue(FusionVisualLabelMapper.candidateHeadLabels(catalog, listOf("forest")).isEmpty())
    }

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
        assertTrue(coverage.any { it["family"] == "RT" && it["status"] == "independent_rating_stage" })
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
        assertTrue(coverage.any { it["family"] == "PO" && it["status"] == "missing_scores" })
        assertTrue(coverage.any { it["family"] == "HC" && it["status"] == "accepted" })
    }

    @Test
    fun nearThresholdEvidenceIsSubjectScopedAndNeverImmediatelyAccepted() {
        val catalog = listOf(
            FusionTaxonomyEngine.Candidate("HC002", "Pink", "Hair Color"),
            FusionTaxonomyEngine.Candidate("SX002", "Male", "Sex"),
        )
        val scores = listOf(
            FusionVisualLabelMapper.LabelScore("pink_hair", .36),
            FusionVisualLabelMapper.LabelScore("1boy", .39),
        )
        val scoped = FusionVisualLabelMapper.evaluateCandidates(
            tags = catalog, scores = scores,
            modelVocabulary = listOf("pink_hair", "1boy"),
            modelId = "asterion-wd-vit-tagger-v3",
            minimumScore = .40, subjectScoped = true,
        )
        assertEquals(emptyList<String>(), scoped["tags"])
        @Suppress("UNCHECKED_CAST")
        val near = scoped["near_threshold_scored_tags"] as List<Map<String, Any>>
        assertEquals(listOf("HC002"), near.map { it["tag"] })
        val global = FusionVisualLabelMapper.evaluateCandidates(
            tags = catalog, scores = scores,
            modelVocabulary = listOf("pink_hair", "1boy"),
            modelId = "asterion-wd-vit-tagger-v3",
            minimumScore = .40, subjectScoped = false,
        )
        @Suppress("UNCHECKED_CAST")
        val globalNear = global["near_threshold_scored_tags"] as List<Map<String, Any>>
        assertTrue(globalNear.isEmpty())
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
    fun compatibleHeadIsRecognizedBeforeAnImageHasBeenScored() {
        val catalog = listOf(FusionTaxonomyEngine.Candidate("HC001", "Blue", "Hair Color"))
        assertEquals(listOf("blue_hair"), FusionVisualLabelMapper.candidateHeadLabels(
            catalog, listOf("blue_hair", "chair")))
        assertTrue(FusionVisualLabelMapper.candidateHeadLabels(catalog, listOf("chair")).isEmpty())
    }

    @Test
    fun absentScoresCannotEstablishVisualComparisons() {
        val tags = listOf(FusionTaxonomyEngine.Candidate("HC001", "Blue", "Hair Color"))
        val result = FusionVisualLabelMapper.evaluateCandidates(tags, emptyList(), listOf("blue hair"),
            "fixed-head", minimumScore = 0.0, enforceCompleteCatalog = true)
        val rows = result["family_coverage"] as List<*>
        val coverage = rows.filterIsInstance<Map<*, *>>().associateBy { it["family"] }
        assertEquals(0, coverage["HC"]?.get("visual_candidate_comparisons"))
        assertEquals("missing_scores", coverage["HC"]?.get("status"))
        assertFalse(coverage.containsKey("EA"))
        assertTrue((result["tags"] as List<*>).isEmpty())
    }

    @Test
    fun familyPlanAlwaysAccountsForCurrentAndFutureFusionFamilies() {
        assertEquals(30, FusionTagFamilyOrder.established.size)
        assertEquals(30, FusionTagFamilyOrder.established.distinct().size)
        assertTrue(FusionTagFamilyOrder.established.indexOf("SA") <
            FusionTagFamilyOrder.established.indexOf("SP"))
        assertEquals(listOf("HC", "EC", "SA", "SP", "PO", "WP", "EN", "ZZ"),
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
        assertEquals(listOf("HC", "SP", "XX"), audit.map { it["family"] })
        assertEquals(listOf("accepted", "unsupported_model", "unsupported_model"),
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
        assertEquals("missing_scores", coverage.first { it["family"] == "SP" }["status"])
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
