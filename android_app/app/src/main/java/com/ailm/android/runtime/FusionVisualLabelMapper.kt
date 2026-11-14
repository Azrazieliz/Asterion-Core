package com.ailm.android.runtime

import java.util.Locale

/**
 * Maps a specialist image classifier's fixed output labels onto the native
 * Fusion taxonomy. This mapping is constructed from Fusion entries, never from
 * free-form VLM text and never from a taxonomy prompt sent to a language model.
 *
 * Ambiguous labels are excluded. Unsupported families are recorded explicitly.
 * Scores come from the executing classifier; no fabricated confidence is used.
 */
internal class FusionVisualLabelMapper(private val taxonomy: FusionTaxonomyEngine) {
    data class LabelScore(val label: String, val confidence: Double)

    fun evaluate(
        scores: List<LabelScore>,
        modelVocabulary: List<String>,
        modelId: String,
        minimumScore: Double = 0.82,
        verifiedSpeciesIds: Set<String> = emptySet(),
        subjectScoped: Boolean = false,
    ): Map<String, Any> = evaluateCandidates(
        // The catalog cache is invalidated on every native Fusion edit.
        // Never scan ~1,200 SQL rows afresh for each of 300,000 images.
        tags = taxonomy.allTags(), scores = scores,
        modelVocabulary = modelVocabulary, modelId = modelId,
        minimumScore = minimumScore,
        verifiedSpeciesIds = verifiedSpeciesIds,
        subjectScoped = subjectScoped,
        enforceCompleteCatalog = true,
    )

    companion object {
        private val canonical = Regex("^[A-Z]{2}[0-9]{2,}(?:-[0-9]+)*$")

        // Immutable model-head vocabulary/candidate mapping. Reused for all
        // images until the live Fusion taxonomy or model vocabulary changes.
        // No per-image re-normalization of 10,861 labels and 1,000+ candidates.
        private data class CompiledPlan(
            val tags: List<FusionTaxonomyEngine.Candidate>,
            val vocabulary: List<String>,
            val byId: Map<String, FusionTaxonomyEngine.Candidate>,
            val byFamily: Map<String, List<FusionTaxonomyEngine.Candidate>>,
            val keysById: Map<String, List<String>>,
            val supportedByFamily: Map<String, Set<String>>,
            val trustedLabels: Set<String>,
            val matchedLabels: Set<String>,
        )
        @Volatile private var cachedPlan: CompiledPlan? = null

        private fun plan(
            tags: List<FusionTaxonomyEngine.Candidate>,
            vocabulary: List<String>,
        ): CompiledPlan {
            cachedPlan?.let { cached ->
                if (cached.tags === tags && cached.vocabulary === vocabulary) {
                    return cached
                }
            }
            synchronized(this) {
                cachedPlan?.let { cached ->
                    if (cached.tags === tags && cached.vocabulary === vocabulary) {
                        return cached
                    }
                }
                val counts = vocabulary.map(::normalize).filter(String::isNotBlank)
                    .groupingBy { it }.eachCount()
                val trusted = counts.filterValues { it == 1 }.keys
                val labelOwners = mutableMapOf<String, MutableSet<String>>()
                tags.forEach { tag ->
                    vocabularyKeys(tag).forEach { key ->
                        labelOwners.getOrPut(key) { linkedSetOf() }.add(tag.id)
                    }
                }
                val keysById = linkedMapOf<String, List<String>>()
                val supported = linkedMapOf<String, MutableSet<String>>()
                tags.forEach { tag ->
                    val keys = vocabularyKeys(tag).filter { key ->
                        key in trusted && labelOwners[key]?.singleOrNull() == tag.id
                    }.toMutableSet()
                    val directId = normalize(tag.id)
                    if (directId in trusted) keys.add(directId)
                    if (keys.isNotEmpty()) {
                        keysById[tag.id] = keys.toList()
                        supported.getOrPut(tag.family) { linkedSetOf() }.add(tag.id)
                    }
                }
                return CompiledPlan(
                    tags, vocabulary, tags.associateBy { it.id },
                    tags.groupBy { it.family }, keysById,
                    supported.mapValues { it.value.toSet() }, trusted,
                    keysById.values.flatten().toSet(),
                ).also { cachedPlan = it }
            }
        }

    /** Pure deterministic implementation, testable without Android SQLite. */
    fun evaluateCandidates(
        tags: List<FusionTaxonomyEngine.Candidate>,
        scores: List<LabelScore>,
        modelVocabulary: List<String>,
        modelId: String,
        minimumScore: Double = 0.82,
        verifiedSpeciesIds: Set<String> = emptySet(),
        subjectScoped: Boolean = true,
        enforceCompleteCatalog: Boolean = false,
    ): Map<String, Any> {
        require(minimumScore in 0.0..1.0)
        val compiled = plan(tags, modelVocabulary)
        val byId = compiled.byId
        val familyOrder = if (enforceCompleteCatalog) {
            FusionTagFamilyOrder.established +
                (compiled.byFamily.keys - FusionTagFamilyOrder.established.toSet()).sorted()
        } else FusionTagFamilyOrder.ordered(compiled.byFamily.keys)

        // Precomputed ONNX image features have already been evaluated once.
        // Each FAMILY selects its candidate set FIRST, then compares each
        // trained candidate's own image-head score. No global top-K admission.
        val imageScores = linkedMapOf<String, Double>()
        val rejected = mutableListOf<String>()
        for ((label, confidence) in scores) {
            val key = normalize(label)
            if (key !in compiled.trustedLabels || key !in compiled.matchedLabels) {
                rejected += label.take(70)
                continue
            }
            if (!confidence.isFinite() || confidence !in 0.0..1.0) continue
            imageScores[key] = maxOf(imageScores[key] ?: 0.0, confidence)
        }
        val mapped = linkedMapOf<String, Double>()
        // Withhold weak but genuinely scored head candidates. Corrections
        // may later calibrate these when the SAME primary character pixels
        // produce supporting evidence; generic whole-image cues cannot.
        val nearThreshold = linkedMapOf<String, Double>()
        val calibratedRecoveryFamilies = setOf(
            "HC", "HL", "HS", "EC", "ET", "SC", "BH", "BT",
            "BS", "BY", "AG",
        )
        val familyComparisons = linkedMapOf<String, Int>()
        for (family in familyOrder) {
            var examined = 0
            for (candidate in compiled.byFamily[family].orEmpty()) {
                val trainedKeys = compiled.keysById[candidate.id].orEmpty()
                if (trainedKeys.isEmpty()) continue
                examined += 1
                val confidence = trainedKeys.maxOfOrNull { imageScores[it] ?: 0.0 } ?: 0.0
                if (subjectScoped && family in calibratedRecoveryFamilies &&
                    confidence < minimumScore &&
                    confidence >= (minimumScore - 0.12).coerceAtLeast(0.0)) {
                    nearThreshold[candidate.id] = confidence
                }
                if (family != "RT" && confidence >= minimumScore) {
                    // A literal plant object in the background cannot be a
                    // person's species; only an explicit plant-person label
                    // is eligible when a real subject crop was detected.
                    val ambiguousPlant = candidate.family == "SP" &&
                        candidate.name.equals("Plant", ignoreCase = true) &&
                        trainedKeys.none { it in setOf("plant girl", "plant humanoid") &&
                            (imageScores[it] ?: 0.0) >= 0.72 }
                    if (!ambiguousPlant) mapped[candidate.id] = confidence
                }
            }
            familyComparisons[family] = examined
        }
        // Fixed-head scores are image-wide. Preserve uncertain species traits,
        // contradictory length/pose observations and their scores for Review
        // instead of silently accepting them as per-character facts.
        val imageWide = FusionImageWideEvidencePolicy.reconcile(
            mapped, byId, modelId, verifiedSpeciesIds, subjectScoped,
            trainedHeadScores = imageScores,
        )
        mapped.clear()
        mapped.putAll(imageWide.accepted)
        val tagsByFamily = compiled.byFamily
        val coverage = familyOrder.mapIndexed { index, family ->
            val entries = tagsByFamily[family].orEmpty()
            val supported = compiled.supportedByFamily[family].orEmpty()
            val accepted = mapped.keys.count { it.take(2) == family }
            val deferred = imageWide.deferred.count { it.id.take(2) == family }
            mapOf<String, Any>(
                "family" to family,
                "resolution_step" to index + 1,
                "resolution_total" to familyOrder.size,
                "candidate_count" to entries.size,
                // A family is not fully supported merely because one of its
                // hundreds of possible canonical values overlaps the WD head.
                "trained_label_count" to supported.size,
                "untrained_candidate_count" to (entries.size - supported.size),
                "visual_candidate_comparisons" to (familyComparisons[family] ?: 0),
                "accepted_count" to accepted,
                "deferred_count" to deferred,
                "status" to when {
                    entries.isEmpty() -> "missing_catalog"
                    family == "RT" && supported.isNotEmpty() -> "independent_rating_stage"
                    supported.isEmpty() -> "unsupported_model"
                    accepted > 0 -> "accepted"
                    deferred > 0 -> "deferred_review"
                    else -> "evaluated_no_confident_match"
                },
            )
        }
        val scored = mapped.map { (id, score) ->
            mapOf<String, Any>("tag" to id, "score" to score)
        }
        // Logical species requirements belong to the reference graph, not
        // the observed tag set: an Elf can have pointed ears hidden by hair.
        // Do not fabricate pixels, scores or new Fusion IDs.
        val confidentSpecies = (
            mapped.filter { (id, score) -> id.startsWith("SP") && score >= 0.70 }.keys +
            verifiedSpeciesIds.filter { byId[it]?.family == "SP" }
        ).toSet()
        val speciesRequirements = FusionSpeciesTraitRules.requiredExpectations(
            confidentSpecies, byId,
        ).map { expectation ->
            mapOf<String, Any>(
                "species_id" to expectation.speciesId,
                "attribute_id" to expectation.traitId,
                "requirement" to "required",
                "observed_in_image" to (expectation.traitId in mapped),
            )
        }
        return mapOf(
            "model_id" to modelId,
            "tags" to mapped.keys.toList(),
            "scored_tags" to scored,
            "subject_scoped" to subjectScoped,
            "near_threshold_scored_tags" to nearThreshold.entries
                .sortedByDescending { it.value }.take(40).map { (id, score) ->
                    mapOf("tag" to id, "score" to score)
                },
            "deferred_scored_tags" to imageWide.deferred.map { item ->
                mapOf<String, Any>(
                    "tag" to item.id,
                    "name" to byId[item.id]?.name.orEmpty(),
                    "score" to item.score,
                    "reason" to item.reason,
                )
            },
            "family_coverage" to coverage,
            "family_resolution_order" to familyOrder,
            "family_resolution_audited" to coverage.all { it["status"] != "missing_catalog" },
            "missing_catalog_families" to coverage.filter { it["status"] == "missing_catalog" }
                .map { it["family"] },
            "unmapped_labels" to rejected.distinct().take(40),
            "accepted_count" to scored.size,
            "deferred_count" to imageWide.deferred.size,
            "species_trait_expectations" to speciesRequirements,
            "evaluated_families" to coverage.count {
                it["status"] !in setOf("unsupported_model", "missing_catalog", "independent_rating_stage")
            },
            "unsupported_families" to coverage.count { it["status"] == "unsupported_model" },
            "minimum_score" to minimumScore,
            "source" to "native_classifier",
        )
    }

    /**
     * Use the live Fusion category and its aliases to align trained labels such
     * as "blue_hair" with a canonical "Blue" in the Hair Color family. This
     * does not manufacture missing visual evidence or restrict future families.
     * Ambiguous reconstructed labels still resolve to no canonical ID.
     */
    private fun vocabularyKeys(tag: FusionTaxonomyEngine.Candidate): Set<String> {
        val categories = normalize(tag.category)
        val genericQualifiers = setOf(
            "color", "colour", "length", "style", "type", "size",
            "traits", "trait", "state", "features", "feature",
        )
        val stem = categories.split(' ').filter {
            it.isNotBlank() && it !in genericQualifiers
        }.joinToString(" ")
        val suffixes = linkedSetOf<String>()
        if (categories.isNotBlank() && categories != tag.family.lowercase(Locale.ROOT)) {
            suffixes += categories
            if (stem.isNotBlank()) {
                suffixes += stem
                if (stem.endsWith("eye")) suffixes += stem + "s"
                if (stem.endsWith("breast")) suffixes += stem + "s"
            }
        }
        val base = buildSet {
            (listOf(tag.name) + tag.aliases).forEach { raw ->
                val key = normalize(raw)
                if (key.isBlank()) return@forEach
                add(key)
                for (suffix in suffixes) {
                    // Avoid "pink hair hair" and "long hair hair length".
                    if (key != suffix && !key.endsWith(" " + suffix)) {
                        add(key + " " + suffix)
                    }
                }
            }
        }
        // Trained subject-specific plant evidence is distinct from the
        // generic "plant" tag frequently triggered by foliage decorations.
        val subjectSpeciesKeys = if (tag.family == "SP" &&
            tag.name.equals("Plant", ignoreCase = true)) {
            setOf("plant girl", "plant humanoid")
        } else emptySet()
        // These are exact orthographic equivalents, not fuzzy matching.
        // WD labels use both pointy_ears and grey_hair spellings while the
        // historical Fusion corpus sometimes uses Pointed Ears / Gray.
        // WD ViT's fixed Danbooru head calls a single visually female-
        // presented subject "1girl" and a male-presented one "1boy".
        // It has no generic "female"/"male" output. These exact visual
        // semantics may resolve the corresponding live SX entries on a
        // verified subject crop (whole-image SX remains deferred).
        // Do not use them to infer a human SP, biological sex, or identity.
        val observedPresentationKeys = if (tag.family == "SX") {
            when (normalize(tag.name)) {
                "female" -> setOf("1girl")
                "male" -> setOf("1boy")
                else -> emptySet()
            }
        } else emptySet()
        return buildSet {
            addAll(subjectSpeciesKeys)
            addAll(observedPresentationKeys)
            for (key in base) {
                add(key)
                val words = key.split(' ')
                if ("gray" in words) add(words.joinToString(" ") { if (it == "gray") "grey" else it })
                if ("grey" in words) add(words.joinToString(" ") { if (it == "grey") "gray" else it })
                if ("multicolored" in words) add(words.joinToString(" ") {
                    if (it == "multicolored") "multicoloured" else it
                })
                if ("multicoloured" in words) add(words.joinToString(" ") {
                    if (it == "multicoloured") "multicolored" else it
                })
                if ("pointed ears" in key) add(key.replace("pointed ears", "pointy ears"))
                if ("pointy ears" in key) add(key.replace("pointy ears", "pointed ears"))
            }
        }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}
