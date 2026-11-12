package com.ailm.android.runtime

import java.util.Locale

/**
 * A canonical CHARACTER profile must account for all applicable corporal
 * taxonomy families. Unlike image-level metadata, omitted character families
 * cannot silently count as complete. Nothing here fabricates a tag, edits a
 * character, or copies profile facts into a photographed image.
 *
 * Dynamic Fusion metadata is authoritative; the frozen corporal family list
 * provides migration compatibility for older packs without explicit scope.
 */
internal object CharacterProfileCompleteness {
    private val legacyCorporalFamilies = setOf(
        "HC", "HL", "HS", "EC", "ET", "SC", "BH", "BT", "BS",
        "BY", "SX", "AG", "SP", "SA",
    )

    data class Report(
        val filledFamilies: List<String>,
        val missingFamilies: List<String>,
        val notApplicableFamilies: List<String>,
        val missingRequiredSpeciesTraits: List<String>,
        val incompatibleSpeciesTraits: List<String>,
        val unlinkedSpeciesTraits: List<String>,
        val unknownIds: List<String>,
        val profileComplete: Boolean,
    ) {
        fun toMap(): Map<String, Any> = mapOf(
            "filled_families" to filledFamilies,
            "missing_families" to missingFamilies,
            "not_applicable_families" to notApplicableFamilies,
            "missing_required_species_traits" to missingRequiredSpeciesTraits,
            "incompatible_species_traits" to incompatibleSpeciesTraits,
            "unlinked_species_traits_review" to unlinkedSpeciesTraits,
            "unknown_canonical_ids" to unknownIds,
            "profile_complete" to profileComplete,
        )
    }

    fun isCharacterAttribute(candidate: FusionTaxonomyEngine.Candidate): Boolean {
        val scope = candidate.characterScope.lowercase(Locale.ROOT)
        return when (scope) {
            "character", "corporal", "both" -> true
            "image", "illustration" -> false
            else -> candidate.family in legacyCorporalFamilies
        }
    }

    fun audit(
        canonicalIds: Collection<String>,
        taxonomy: Collection<FusionTaxonomyEngine.Candidate>,
        explicitNotApplicable: Set<String> = emptySet(),
    ): Report {
        val byId = taxonomy.associateBy { it.id.uppercase(Locale.ROOT) }
        val selected = canonicalIds.map { it.trim().uppercase(Locale.ROOT) }.toSet()
        val unknown = selected.filterNot(byId::containsKey).sorted()
        val assigned = selected.mapNotNull(byId::get)
        val corpus = taxonomy.filter(::isCharacterAttribute)
        val requiredFamilies = corpus.map { it.family }.toSet() - "SA"
        val nA = explicitNotApplicable.map { it.trim().uppercase(Locale.ROOT) }
            .filter { it in requiredFamilies }.toSet()
        val filled = requiredFamilies.filter { family ->
            family !in nA && assigned.any { it.family == family }
        }.sorted()
        val missing = (requiredFamilies - filled.toSet() - nA).sorted()

        val enabledSpecies = assigned.filter { it.family == "SP" }
        val matchedSA = assigned.filter { it.family == "SA" }
        val required = mutableSetOf<String>()
        val invalid = mutableSetOf<String>()
        val unresolved = mutableSetOf<String>()
        val validCatalog = taxonomy.associateBy { it.id }

        for (species in enabledSpecies) {
            for (trait in corpus.filter { it.family == "SA" }) {
                if (FusionSpeciesTraitRules.status(species, trait, validCatalog) ==
                    FusionSpeciesTraitRules.Status.REQUIRED && trait.id !in selected) {
                    required += trait.id
                }
            }
        }
        for (trait in matchedSA) {
            val states = enabledSpecies.map { sp ->
                FusionSpeciesTraitRules.status(sp, trait, validCatalog)
            }
            when {
                states.isEmpty() -> unresolved += trait.id
                states.any { it == FusionSpeciesTraitRules.Status.REQUIRED ||
                    it == FusionSpeciesTraitRules.Status.OPTIONAL } -> Unit
                states.all { it == FusionSpeciesTraitRules.Status.FORBIDDEN } -> invalid += trait.id
                else -> unresolved += trait.id
            }
        }

        return Report(
            filledFamilies = filled,
            missingFamilies = missing,
            notApplicableFamilies = nA.sorted(),
            missingRequiredSpeciesTraits = required.sorted(),
            incompatibleSpeciesTraits = invalid.sorted(),
            unlinkedSpeciesTraits = unresolved.sorted(),
            unknownIds = unknown,
            profileComplete = missing.isEmpty() && required.isEmpty() &&
                invalid.isEmpty() && unresolved.isEmpty() && unknown.isEmpty(),
        )
    }
}
