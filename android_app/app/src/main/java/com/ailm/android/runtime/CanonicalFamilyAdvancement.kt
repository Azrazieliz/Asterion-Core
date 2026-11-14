package com.ailm.android.runtime

/** Ordered, source-grounded admission ledger for every native Fusion family.
 * One model feature pass is shared to meet the mobile time budget, but each
 * mandatory family is checked and committed as a separate advancement step.
 * Unresolved fields never count as completed. Optional families may be empty. */
internal object CanonicalFamilyAdvancement {
    data class Step(
        val family: String,
        val required: Boolean,
        val saved: Int,
        val status: String,
        val diagnostic: String,
    ) {
        fun toMap(): Map<String, Any> = mapOf(
            "family" to family,
            "required" to required,
            "accepted_count" to saved,
            "status" to status,
            "diagnostic" to diagnostic,
        )
    }
    data class Plan(
        val steps: List<Step>,
        val firstBlockedFamily: String,
        val requiredPassed: Int,
        val requiredTotal: Int,
    ) {
        val complete: Boolean get() = firstBlockedFamily.isBlank() &&
            requiredPassed == requiredTotal
        fun toMap(): Map<String, Any> = mapOf(
            "first_blocked_family" to firstBlockedFamily,
            "required_passed" to requiredPassed,
            "required_total" to requiredTotal,
            "complete" to complete,
            "steps" to steps.map(Step::toMap),
        )
    }

    fun evaluate(
        taxonomy: Collection<FusionTaxonomyEngine.Candidate>,
        savedTags: Collection<CanonicalTagObservation>,
        appearanceAudit: CharacterProfileCompleteness.Report?,
        contentRatingCount: Int,
        modelCoverage: Map<String, String> = emptyMap(),
        incompletelyTrainedFamilies: Set<String> = emptySet(),
    ): Plan {
        val required = if (appearanceAudit == null) emptySet() else
            (appearanceAudit.filledFamilies + appearanceAudit.missingFamilies +
                appearanceAudit.conflictingFamilies +
                if (appearanceAudit.missingRequiredSpeciesTraits.isNotEmpty() ||
                    appearanceAudit.incompatibleSpeciesTraits.isNotEmpty() ||
                    appearanceAudit.unlinkedSpeciesTraits.isNotEmpty()) listOf("SA")
                else emptyList()).toSet()
        val allFamilies = taxonomy.map { it.family }.toSet() + required + "RT"
        val order = FusionTagFamilyOrder.ordered(allFamilies)
        val savedByFamily = savedTags.groupBy { it.tagId.take(2) }
        var blocked = ""
        var passed = 0
        val steps = order.map { family ->
            val mandatory = family == "RT" || family in required
            val saved = if (family == "RT") contentRatingCount
                else savedByFamily[family]?.count { tag ->
                    !mandatory || tag.scope == "character_observed"
                } ?: 0
            val conflict = appearanceAudit?.conflictingFamilies?.contains(family) == true
            // A partial fixed-label vocabulary cannot attest that the chosen
            // value was the best match among ALL Fusion candidates. A verified
            // manual family decision is authoritative; a model-only tag is not.
            val manuallyConfirmed = savedTags.any { tag ->
                tag.tagId.take(2) == family && tag.scope == "character_observed" &&
                    tag.source == "manual"
            }
            val speciesProblem = family == "SA" && appearanceAudit != null &&
                (appearanceAudit.missingRequiredSpeciesTraits.isNotEmpty() ||
                    appearanceAudit.incompatibleSpeciesTraits.isNotEmpty() ||
                    appearanceAudit.unlinkedSpeciesTraits.isNotEmpty())
            val status = when {
                !mandatory && saved == 0 -> "optional_no_observation"
                !mandatory -> "optional_saved"
                family == "RT" && saved != 1 -> "required_content_rating_missing_or_conflicting"
                conflict -> "required_conflict"
                speciesProblem -> "required_species_trait_review"
                saved == 0 -> when (modelCoverage[family]) {
                    "unsupported_model", "missing_catalog" -> "required_model_unsupported"
                    else -> "required_missing_evidence"
                }
                family in incompletelyTrainedFamilies && !manuallyConfirmed ->
                    "required_model_candidate_gap"
                else -> "required_accepted"
            }
            if (mandatory) {
                if (status == "required_accepted") passed++
                else if (blocked.isBlank()) blocked = family
            }
            Step(
                family = family, required = mandatory, saved = saved,
                status = status,
                diagnostic = modelCoverage[family].orEmpty(),
            )
        }
        return Plan(steps, blocked, passed, steps.count { it.required })
    }
}
