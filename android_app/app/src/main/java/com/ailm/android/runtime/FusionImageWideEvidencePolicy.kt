package com.ailm.android.runtime

/**
 * A fixed-head artwork tagger sees the whole picture, not a specific person.
 * Preserve that distinction instead of persisting species-specific traits or
 * mutually exclusive attribute values as if their subject were established.
 *
 * All withheld scores remain reviewable; they are not silently declared false.
 * This policy does not alter the canonical taxonomy or manual corrections.
 */
internal object FusionImageWideEvidencePolicy {
    data class Deferred(val id: String, val score: Double, val reason: String)
    data class Decision(val accepted: Map<String, Double>, val deferred: List<Deferred>)

    fun reconcile(
        scored: Map<String, Double>,
        candidates: Map<String, FusionTaxonomyEngine.Candidate>,
        modelId: String,
        verifiedSpeciesIds: Set<String> = emptySet(),
        subjectScoped: Boolean = true,
        trainedHeadScores: Map<String, Double> = emptyMap(),
    ): Decision {
        val accepted = linkedMapOf<String, Double>()
        accepted.putAll(scored)
        val deferred = linkedMapOf<String, Deferred>()
        fun hold(id: String, reason: String) {
            val score = accepted.remove(id) ?: return
            deferred[id] = Deferred(id, score, reason)
        }

        // A whole-image head sees foliage, pets, props and multiple people.
        // Its character-attribute labels are review suggestions ONLY until
        // an actual person/creature region is analyzed. This prevents a
        // decorative plant from ever becoming the foreground person's SP.
        if (!subjectScoped) {
            val characterFamilies = setOf(
                "SP", "SA", "SX", "AG", "SC", "BH", "BT", "BS",
                "BY", "HC", "HL", "HS", "EC", "ET",
            )
            accepted.keys.toList().forEach { id ->
                if (id.take(2) in characterFamilies) {
                    hold(id, "character_attribute_requires_subject_pixels")
                }
            }
        }

        // Evaluate observed SA pixels first. An inferred SP label must not
        // veto a high-confidence visible feature from the same subject crop.
        // Explicitly verified/manual species constraints remain authoritative.
        // A conflicting *inferred* species is withheld for Review rather than
        // suppressing the independent SA observation or inventing a species.
        val inferredSpecies = accepted.keys.filter { id ->
            id.startsWith("SP") &&
                (accepted[id] ?: 0.0) >= 0.70 &&
                candidates[id]?.family == "SP"
        }
        val confirmedSpecies = verifiedSpeciesIds.filter { id ->
            candidates[id]?.family == "SP"
        }.toSet()
        accepted.keys.filter { it.startsWith("SA") }.toList().forEach { id ->
            val attribute = candidates[id]
            if (attribute == null) {
                hold(id, "unknown_species_attribute")
                return@forEach
            }
            if (!subjectScoped || (accepted[id] ?: 0.0) < 0.65) {
                hold(id, if (subjectScoped) "low_confidence_species_attribute"
                    else "character_attribute_requires_subject_pixels")
                return@forEach
            }
            fun relation(speciesId: String): FusionSpeciesTraitRules.Status? =
                candidates[speciesId]?.let { species ->
                    FusionSpeciesTraitRules.status(species, attribute, candidates)
                }
            // A human-reviewed species constraint is not an inferred label.
            if (confirmedSpecies.isNotEmpty() &&
                confirmedSpecies.all { relation(it) == FusionSpeciesTraitRules.Status.FORBIDDEN }) {
                hold(id, "conflicts_with_verified_species")
                return@forEach
            }
            // Only explicit FORBIDDEN rules count as contradictions. UNLINKED
            // means unknown, not evidence that visible SA pixels are false.
            inferredSpecies.forEach { speciesId ->
                if (speciesId !in confirmedSpecies &&
                    relation(speciesId) == FusionSpeciesTraitRules.Status.FORBIDDEN) {
                    hold(speciesId, "predicted_species_conflicts_with_visible_attribute")
                }
            }
        }

        if (modelId == "asterion-wd-vit-tagger-v3") {
            // The WD head is multi-label; a 0.40 score is not a calibrated
            // probability and is particularly unreliable for actions. A
            // generic erotic-looking pose must not become "Masturbating".
            // Preserve uncertain AC guesses as Review, never as auto tags.
            val contactCues = listOf(
                "hand between legs", "hand in panties", "crotch rub",
                "fingering", "fingering through clothes",
                "fingering through panties", "crotch grab",
            )
            val corroboratingContact = contactCues.any {
                (trainedHeadScores[it] ?: 0.0) >= 0.70
            }
            accepted.keys.filter { it.startsWith("AC") }.toList().forEach { id ->
                val confidence = accepted[id] ?: return@forEach
                val name = candidates[id]?.name.orEmpty()
                if ("masturbat" in name.lowercase()) {
                    if (!subjectScoped || confidence < 0.90 || !corroboratingContact) {
                        hold(id, "sexual_action_requires_subject_and_contact_evidence")
                    }
                } else if (!subjectScoped) {
                    // An image-wide action score cannot establish which
                    // subject performed it (or whether the apparent action
                    // is just a prop/background cue). Keep it in Review.
                    hold(id, "action_requires_subject_pixels")
                } else if (confidence < 0.72) {
                    hold(id, "weak_single_head_action_prediction")
                }
            }
            // "Nude" is not reliable whole-image evidence: a tight swimsuit,
            // exposed legs, or a background figure can activate this head.
            // A true nudity tag requires a separately observed person crop and
            // very strong evidence, not just a globally high image score.
            accepted.keys.filter { id ->
                id.startsWith("OF") &&
                    candidates[id]?.name?.trim()?.lowercase() in
                    setOf("nude", "naked", "completely naked", "fully nude")
            }.toList().forEach { id ->
                if (!subjectScoped || (accepted[id] ?: 0.0) < 0.92) {
                    hold(id, "nudity_requires_verified_subject_and_strong_evidence")
                }
            }
            // Thresholds for visually broad context classes are deliberately
            // stronger than the multi-label 0.40 attribute admission floor.
            // These are conservative gates, not claims of calibrated accuracy.
            accepted.keys.filter { id ->
                (id.startsWith("PO") || id.startsWith("FR")) &&
                    (accepted[id] ?: 0.0) < 0.75
            }.toList().forEach { hold(it, "weak_pose_or_framing_evidence") }
        }

        // A single image may contain multiple people, so competing hair
        // lengths are not proof either prediction is wrong. Without bounding
        // boxes there is no safe per-person assignment. Retain a single
        // dominant observation only when the raw scores clearly separate it.
        val lengthIds = accepted.keys.filter { it.startsWith("HL") }
        if (lengthIds.size > 1) {
            val ordered = lengthIds.sortedByDescending { accepted[it] ?: 0.0 }
            val top = accepted[ordered[0]] ?: 0.0
            val runnerUp = accepted[ordered[1]] ?: 0.0
            val unambiguous = top >= 0.78 && (top - runnerUp) >= 0.16
            ordered.forEachIndexed { index, id ->
                if (!unambiguous || index != 0) {
                    hold(id, "multiple_hair_lengths_without_subject_regions")
                }
            }
        }

        for (family in listOf("PO", "FR")) {
            val ids = accepted.keys.filter { it.startsWith(family) }
            val names = ids.mapNotNull { candidates[it]?.name }
            if (ids.size > 1 && names.size == ids.size &&
                !CanonicalImageTagCoherence.internallyConsistent(family, names)) {
                ids.forEach { hold(it, "incompatible_image_wide_" + family + "_predictions") }
            }
        }
        return Decision(accepted.toMap(), deferred.values.toList())
    }
}
