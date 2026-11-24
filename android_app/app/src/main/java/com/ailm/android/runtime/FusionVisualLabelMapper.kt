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
        minimumScoreById: Map<String, Double> = emptyMap(),
        verifiedSpeciesIds: Set<String> = emptySet(),
        subjectScoped: Boolean = false,
        sceneryOrder: Boolean = false,
    ): Map<String, Any> = evaluateCandidates(
        // The catalog cache is invalidated on every native Fusion edit.
        // Never scan ~1,200 SQL rows afresh for each of 300,000 images.
        tags = taxonomy.allTags(), scores = scores,
        modelVocabulary = modelVocabulary, modelId = modelId,
        minimumScore = minimumScore,
        minimumScoreById = minimumScoreById,
        verifiedSpeciesIds = verifiedSpeciesIds,
        subjectScoped = subjectScoped,
        enforceCompleteCatalog = true,
        restrictToRegion = true,
        sceneryOrder = sceneryOrder,
    )

    companion object {
        private val subjectActivities = setOf("AC", "GE", "PO", "OF", "WP", "EY", "MO", "EX")

        fun requiresContextConfirmation(tag: FusionTaxonomyEngine.Candidate): Boolean =
            when (tag.family) {
                "SX" -> normalize(tag.name) == "sexless"
                "EX" -> normalize(tag.name) in setOf("kuudere", "dandere", "deredere")
                else -> false
            }

        fun acceptsRegion(tag: FusionTaxonomyEngine.Candidate, subject: Boolean): Boolean =
            when (tag.characterScope.lowercase(Locale.ROOT)) {
                "both" -> true
                "subject", "character", "corporal" -> subject
                "image", "illustration" -> !subject
                else -> if (subject) CharacterProfileCompleteness.isCharacterAttribute(tag) ||
                    tag.family in subjectActivities else !CharacterProfileCompleteness.isCharacterAttribute(tag)
            }

        private val canonical = Regex("^[A-Z]{2}[0-9]{2,}(?:-[0-9]+)*$")

        // Immutable model-head vocabulary/candidate mapping. Reused for all
        // images until the live Fusion taxonomy or model vocabulary changes.
        // No per-image re-normalization of 10,861 labels and 1,000+ candidates.
        private data class CompiledPlan(
            val tags: List<FusionTaxonomyEngine.Candidate>,
            val vocabulary: List<String>,
            val byId: Map<String, FusionTaxonomyEngine.Candidate>,
            val byFamily: Map<String, List<FusionTaxonomyEngine.Candidate>>,
            val subjectCandidates: Map<String, List<FusionTaxonomyEngine.Candidate>>,
            val imageCandidates: Map<String, List<FusionTaxonomyEngine.Candidate>>,
            val keysById: Map<String, List<String>>,
            val supportedByFamily: Map<String, Set<String>>,
            val trustedLabels: Set<String>,
            val matchedLabels: Set<String>,
            val reviewChoices: Map<String, Set<String>>,
            val candidateHeadLabels: List<String>,
        )
        @Volatile private var cachedPlan: CompiledPlan? = null

        private fun plan(
            tags: List<FusionTaxonomyEngine.Candidate>,
            vocabulary: List<String>,
        ): CompiledPlan {
            cachedPlan?.let { cached ->
                if (cached.tags === tags && cached.vocabulary == vocabulary) {
                    return cached
                }
            }
            synchronized(this) {
                cachedPlan?.let { cached ->
                    if (cached.tags === tags && cached.vocabulary == vocabulary) {
                        return cached
                    }
                }
                val counts = vocabulary.map(::normalize).filter(String::isNotBlank)
                    .groupingBy { it }.eachCount()
                val trusted = counts.filterValues { it == 1 }.keys
                val labelOwners = mutableMapOf<String, MutableSet<String>>()
                val trainedKeys = tags.associate { tag ->
                    tag.id to vocabularyKeys(tag).filter { it in trusted }
                }
                tags.forEach { tag ->
                    trainedKeys.getValue(tag.id).forEach { key ->
                        labelOwners.getOrPut(key) { linkedSetOf() }.add(tag.id)
                    }
                }
                // These source-defined facets have the same literal observation.
                // An alias collision or a duplicate within a family remains ambiguous.
                val byCanonicalId = tags.associateBy { it.id }
                val sharedFacets = mapOf(
                    "stretching" to setOf("AC", "GE"),
                    "holding hands" to setOf("AC", "GE"),
                    "laughing" to setOf("AC", "EX"),
                    "crying" to setOf("AC", "EX"),
                    "sleeping" to setOf("AC", "EX"),
                    "spiral eyes" to setOf("ET", "EY"),
                    "evil grin" to setOf("EX", "MO"),
                    "pouting" to setOf("EX", "MO"),
                )
                val jointLabels = sharedFacets.filter { (label, families) ->
                    val owners = labelOwners[label].orEmpty().mapNotNull(byCanonicalId::get)
                    owners.size == families.size && owners.map { it.family }.toSet() == families &&
                        owners.all { normalize(it.name) == label }
                }.keys
                val keysById = linkedMapOf<String, List<String>>()
                val supported = linkedMapOf<String, MutableSet<String>>()
                tags.forEach { tag ->
                    if (requiresContextConfirmation(tag)) return@forEach
                    val keys = trainedKeys.getValue(tag.id).filter { key ->
                        key in trusted && (labelOwners[key]?.singleOrNull() == tag.id || key in jointLabels)
                    }.toMutableSet()
                    val directId = normalize(tag.id)
                    if (directId in trusted) {
                        keys.clear()
                        keys.add(directId)
                    }
                    if (keys.isNotEmpty()) {
                        keysById[tag.id] = keys.toList()
                        supported.getOrPut(tag.family) { linkedSetOf() }.add(tag.id)
                    }
                }
                val subjectCandidates = tags.filter { acceptsRegion(it, true) }.groupBy { it.family }
                val imageCandidates = tags.filter { acceptsRegion(it, false) }.groupBy { it.family }
                // A head may identify a garment while its two canonical roles
                // require owner context. Carry the channel and both choices to Review.
                val reviewChoices = labelOwners.filter { (key, ids) ->
                    ids.size > 1 && ids.all { normalize(byCanonicalId.getValue(it).name) == key } &&
                        ids.map { byCanonicalId.getValue(it).family }.distinct().size == 1
                }
                val matchedLabels = keysById.values.flatten().toSet() + reviewChoices.keys
                return CompiledPlan(
                    tags, vocabulary, tags.associateBy { it.id },
                    tags.groupBy { it.family }, subjectCandidates, imageCandidates, keysById,
                    supported.mapValues { it.value.toSet() }, trusted,
                    matchedLabels, reviewChoices, vocabulary.filter { normalize(it) in matchedLabels },
                ).also { cachedPlan = it }
            }
        }

    fun candidateHeadLabels(tags: List<FusionTaxonomyEngine.Candidate>, vocabulary: List<String>): List<String> =
        plan(tags, vocabulary).candidateHeadLabels

    /** Pure deterministic implementation, testable without Android SQLite. */
    fun evaluateCandidates(
        tags: List<FusionTaxonomyEngine.Candidate>,
        scores: List<LabelScore>,
        modelVocabulary: List<String>,
        modelId: String,
        minimumScore: Double = 0.82,
        minimumScoreById: Map<String, Double> = emptyMap(),
        verifiedSpeciesIds: Set<String> = emptySet(),
        subjectScoped: Boolean = true,
        enforceCompleteCatalog: Boolean = false,
        restrictToRegion: Boolean = false,
        sceneryOrder: Boolean = false,
    ): Map<String, Any> {
        require(minimumScore in 0.0..1.0)
        require(minimumScoreById.values.all { it.isFinite() && it in 0.0..1.0 })
        val compiled = plan(tags, modelVocabulary)
        val byId = compiled.byId
        val characterFamilies = tags.asSequence()
            .filter(CharacterProfileCompleteness::isCharacterAttribute)
            .map { it.family }.toSet()
        val expectedFamilies = if (enforceCompleteCatalog)
            FusionTagFamilyOrder.originalSe0031Families + compiled.byFamily.keys
        else compiled.byFamily.keys.toList()
        val regionCandidates = when {
            !restrictToRegion -> compiled.byFamily
            subjectScoped -> compiled.subjectCandidates
            else -> compiled.imageCandidates
        }
        val applicable = expectedFamilies.filter { family ->
            family in regionCandidates || (family !in compiled.byFamily &&
                (!restrictToRegion || (family in FusionTagFamilyOrder.character) == subjectScoped))
        }
        val familyOrder = FusionTagFamilyOrder.ordered(
            applicable, characterFamilies = characterFamilies,
            sceneryOnly = sceneryOrder && !subjectScoped,
            explicitImageFamilies = if (restrictToRegion && !subjectScoped) regionCandidates.keys else emptySet(),
        )

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
            "HC", "HL", "HS", "EC", "EA", "ET", "SC", "BH", "BT",
            "BS", "BY", "AG",
        )
        val familyComparisons = linkedMapOf<String, Int>()
        for (family in familyOrder) {
            var examined = 0
            for (candidate in regionCandidates[family].orEmpty()) {
                val trainedKeys = compiled.keysById[candidate.id].orEmpty()
                if (family == "RT") continue
                val confidence = trainedKeys.mapNotNull(imageScores::get).maxOrNull() ?: continue
                examined += 1
                // A newly trained local Fusion head is validated at its own
                // score boundary. It must not inherit the weaker fixed-head
                // WD gate merely because both use one visual candidate list.
                val required = maxOf(minimumScore, minimumScoreById[candidate.id] ?: minimumScore)
                if (subjectScoped && family in calibratedRecoveryFamilies &&
                    confidence < required &&
                    confidence >= (required - 0.12).coerceAtLeast(0.0)) {
                    nearThreshold[candidate.id] = confidence
                }
                if (family != "RT" && confidence >= required) {
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
        val roleChoices = compiled.reviewChoices.mapNotNull { (key, ids) ->
            val score = imageScores[key] ?: return@mapNotNull null
            if (score < minimumScore) return@mapNotNull null
            val choices = ids.mapNotNull(byId::get).filter { !restrictToRegion || acceptsRegion(it, subjectScoped) }
            if (choices.isEmpty() || choices.any { it.id in mapped }) return@mapNotNull null
            mapOf("channel" to key, "score" to score, "family" to choices.first().family,
                "choices" to choices.map { tag -> mapOf("id" to tag.id, "name" to tag.name,
                    "parent" to (byId[tag.parentId]?.name ?: tag.parentId)) })
        }
        val tagsByFamily = regionCandidates
        val coverage = familyOrder.mapIndexed { index, family ->
            val entries = tagsByFamily[family].orEmpty()
            val supported = entries.count { it.id in compiled.keysById }
            val accepted = mapped.keys.count { it.take(2) == family }
            val deferred = imageWide.deferred.count { it.id.take(2) == family }
            mapOf<String, Any>(
                "family" to family,
                "resolution_step" to index + 1,
                "resolution_total" to familyOrder.size,
                "candidate_count" to entries.size,
                // A family is not fully supported merely because one of its
                // hundreds of possible canonical values overlaps the WD head.
                "trained_label_count" to supported,
                "untrained_candidate_count" to (entries.size - supported),
                "visual_candidate_comparisons" to (familyComparisons[family] ?: 0),
                "accepted_count" to accepted,
                "deferred_count" to deferred,
                "comparison_complete" to (entries.isNotEmpty() && familyComparisons[family] == entries.size),
                "observation_outcome" to when {
                    accepted > 0 -> "recognized"
                    deferred > 0 || roleChoices.any { it["family"] == family } || family == "RT" -> "requires_review"
                    supported == 0 -> "unsupported"
                    familyComparisons[family] != entries.size -> "visually_indeterminate"
                    else -> "not_observed_above_threshold"
                },
                "status" to when {
                    entries.isEmpty() -> "missing_catalog"
                    family == "RT" -> "independent_rating_stage"
                    supported == 0 -> "unsupported_model"
                    (familyComparisons[family] ?: 0) == 0 -> "missing_scores"
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
            "role_review_choices" to roleChoices,
            "near_threshold_scored_tags" to nearThreshold.entries
                .sortedByDescending { it.value }.map { (id, score) ->
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
                (it["visual_candidate_comparisons"] as Int) > 0
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
        val anatomicalSuffix = when (tag.family) {
            "HC", "HL" -> "hair"
            "EC" -> "eyes"
            "SC" -> "skin"
            "BS" -> "breasts"
            else -> null
        }
        if (anatomicalSuffix != null) suffixes += anatomicalSuffix
        val base = buildSet {
            (listOf(tag.name) + tag.aliases).forEach { raw ->
                val key = normalize(raw)
                if (key.isBlank()) return@forEach
                // Unqualified colours and sizes also describe objects in a crop.
                val statePart = when (tag.family) {
                    "EY" -> "eyes"
                    "MO" -> "mouth"
                    else -> null
                }
                if (statePart != null && key in setOf("open", "closed", "half lidded",
                        "wide open", "slightly open")) {
                    add("$key $statePart")
                    return@forEach
                }
                if (tag.family == "HS" && key in setOf("straight", "wavy", "curly", "messy")) {
                    add("$key hair")
                    return@forEach
                }
                if (tag.family == "BS" && key == "flat") add("flat chest")
                if (anatomicalSuffix == null || key.split(' ').any {
                        it == anatomicalSuffix || it == anatomicalSuffix.removeSuffix("s")
                    } || key == "bald" && tag.family == "HL") add(key)
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
            // Exact visual terms for the corresponding live family/name. Unknown
            // or renamed records never inherit a head channel by ordinal ID.
            when (tag.family to normalize(tag.name)) {
                "CA" to "high angle" -> add("from above")
                "OR" to "back view" -> add("from behind")
                "ET" to "horizontal slit pupils" -> add("horizontal pupils")
                "ET" to "no visible pupils" -> add("no pupils")
                "GE" to "peace sign" -> add("v")
                "GE" to "double peace sign" -> add("double v")
                "GE" to "hand on hip" -> add("hand on own hip")
                "GE" to "hand on cheek" -> add("hand on own cheek")
                "GE" to "hand on chin" -> add("hand on own chin")
                "GE" to "hand over mouth" -> add("hand over own mouth")
                "GE" to "hands behind back" -> add("arms behind back")
                "GE" to "finger to lips" -> add("shushing")
                "GE" to "hand on chest" -> add("hand on own chest")
                "GE" to "hands on hips" -> add("hands on own hips")
                "GE" to "hands on knees" -> add("hands on own knees")
                "GE" to "hand in hair" -> add("hand in own hair")
                "GE" to "hand on head" -> add("hand on own head")
                "GE" to "hands on thighs" -> add("hands on own thighs")
                "GE" to "hand on stomach" -> add("hand on own stomach")
                "PO" to "w sitting" -> add("wariza")
                "PO" to "cross legged" -> add("indian style")
                "PO" to "legs crossed" -> add("crossed legs")
                "PO" to "forward lean" -> add("leaning forward")
                "PO" to "backward lean" -> add("leaning back")
                "MO" to "fangs visible" -> add("fang")
                "MO" to "biting lip" -> add("biting own lip")
                "EY" to "half lidded" -> add("half closed eyes")
                "EY" to "heart eyes" -> add("heart shaped pupils")
                "EX" to "blushing" -> add("blush")
                "EX" to "blowing a kiss" -> add("blowing kiss")
                "EN" to "café" -> add("cafe")
                "AC" to "hugging" -> add("hug")
                "AC" to "kissing" -> add("kiss")
                "OF" to "body stocking" -> add("bodystocking")
                "OF" to "mini skirt" -> add("miniskirt")
                "OF" to "hair clip" -> add("hairclip")
                "OF" to "tea cup" -> add("teacup")
                "WP" to "gauntlet" -> add("gauntlets")
                "HS" to "bun" -> add("hair bun")
                "HS" to "twin buns" -> add("double bun")
                "HS" to "drill curls" -> add("drill hair")
                "HS" to "side locks" -> add("sidelocks")
                "ET" to "mechanical eyes" -> add("mechanical eye")
            }
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

    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(separators, " ").trim()
    }
}
