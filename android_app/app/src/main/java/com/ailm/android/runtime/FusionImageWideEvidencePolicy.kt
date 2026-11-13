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

        // Species attributes are legal when their compatible species is
        // explicitly enabled by the actual image classifier. Dragon wings are
        // OPTIONAL; an elf's pointed ears are REQUIRED by its canonical graph;
        // human-only evidence never supports non-human anatomy. Requirements
        // are candidate constraints, not synthetic visible tags.
        //
        // All image-level tags remain unassigned to individuals until subject
        // detection supplies a common subject ID/bounding region.
        val enabledSpecies = (
            accepted.filter { (id, score) ->
                id.startsWith("SP") && score >= 0.70 && candidates[id]?.family == "SP"
            }.keys + verifiedSpeciesIds.filter { id ->
                candidates[id]?.family == "SP"
            }
        ).toSet()
        accepted.keys.filter { it.startsWith("SA") }.toList().forEach { id ->
            val attribute = candidates[id]
            if (attribute == null) {
                hold(id, "unknown_species_attribute")
                return@forEach
            }
            // Each family's visible evidence must stand independently.
            // A missing/uncertain SP decision must NOT erase a genuinely
            // scored SA value from a detected person's own image crop.
            // Never infer the species from the attribute; retain the
            // observation and keep canonical species compatibility separate.
            if (enabledSpecies.isEmpty()) {
                if (!subjectScoped || (accepted[id] ?: 0.0) < 0.65) {
                    hold(id, if (!subjectScoped) "species_not_enabled_for_attribute"
                        else "low_confidence_species_attribute")
                }
                return@forEach
            }
            val compatible = enabledSpecies.mapNotNull { speciesId ->
                candidates[speciesId]?.let { species ->
                    FusionSpeciesTraitRules.status(species, attribute, candidates)
                }
            }.any { status ->
                status == FusionSpeciesTraitRules.Status.REQUIRED ||
                    status == FusionSpeciesTraitRules.Status.OPTIONAL
            }
            when {
                !compatible -> hold(id, "no_enabled_compatible_species")
                (accepted[id] ?: 0.0) < 0.65 ->
                    hold(id, "low_confidence_species_attribute")
            }
        }

        if (modelId == "asterion-wd-vit-tagger-v3") {
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
