package com.ailm.android.runtime

import java.util.Locale

/**
 * Species-conditioned taxonomy rules, deliberately separate from pixel
 * observations and character identity. Requirements are canonical constraints
 * for candidate matching; they are NOT fabricated image-visible detections.
 *
 * Never allocate a new ID or infer a species merely because its trait appears.
 */
internal object FusionSpeciesTraitRules {
    enum class Status { REQUIRED, OPTIONAL, FORBIDDEN, UNLINKED }

    data class Expectation(
        val speciesId: String,
        val traitId: String,
        val requirement: Status,
    )

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Explicitly user-confirmed examples, never a guessed generic rule. */
    private fun namedRule(species: String, trait: String): Status? {
        val sp = normalize(species)
        val sa = normalize(trait)
        val pointedEar = sa.contains("pointed ear") || sa.contains("elf ear")
        val wing = sa == "wings" || sa.contains("dragon wing") || sa.contains("wings ")
        return when {
            sp == "human" -> Status.FORBIDDEN
            sp == "elf" && pointedEar -> Status.REQUIRED
            sp == "elf" && wing -> Status.FORBIDDEN
            sp == "dragon" && wing -> Status.OPTIONAL
            else -> null
        }
    }

    /** Find the established canonical parent, including intermediate SA groups. */
    private fun canonicalSpeciesParent(
        attribute: FusionTaxonomyEngine.Candidate,
        catalog: Map<String, FusionTaxonomyEngine.Candidate>,
    ): String? {
        var parent = attribute.parentId.trim().uppercase(Locale.ROOT)
        val visited = mutableSetOf<String>()
        repeat(12) {
            if (parent.isBlank() || !visited.add(parent)) return null
            if (parent.matches(Regex("^SP[0-9]{2,}$"))) return parent
            parent = catalog[parent]?.parentId?.trim()?.uppercase(Locale.ROOT).orEmpty()
        }
        return null
    }

    fun status(
        species: FusionTaxonomyEngine.Candidate,
        attribute: FusionTaxonomyEngine.Candidate,
        catalog: Map<String, FusionTaxonomyEngine.Candidate>,
    ): Status {
        if (species.family != "SP" || attribute.family != "SA") return Status.UNLINKED

        // A canonical parent to another species is never transferable.
        val parent = canonicalSpeciesParent(attribute, catalog)
        if (parent != null && parent != species.id) return Status.FORBIDDEN

        // Explicit metadata wins when the relationship is tied to this SP.
        val annotated = when (attribute.traitRequirement.lowercase(Locale.ROOT)) {
            "required" -> Status.REQUIRED
            "optional" -> Status.OPTIONAL
            "forbidden" -> Status.FORBIDDEN
            else -> null
        }
        if (parent == species.id && annotated != null) return annotated

        // The owner's frozen examples apply even before the unfinished
        // taxonomy pack has recorded explicit SP->SA relationships.
        namedRule(species.name, attribute.name)?.let { return it }
        return if (parent == species.id) Status.OPTIONAL else Status.UNLINKED
    }

    /**
     * Required constraints are exposed for the character reference graph,
     * never inserted into observed image tags without a matching model score.
     */
    fun requiredExpectations(
        enabledSpeciesIds: Set<String>,
        catalog: Map<String, FusionTaxonomyEngine.Candidate>,
    ): List<Expectation> = buildList {
        for (speciesId in enabledSpeciesIds) {
            val species = catalog[speciesId] ?: continue
            if (species.family != "SP") continue
            for (trait in catalog.values.filter { it.family == "SA" }) {
                if (status(species, trait, catalog) == Status.REQUIRED) {
                    add(Expectation(species.id, trait.id, Status.REQUIRED))
                }
            }
        }
    }.distinct()
}
