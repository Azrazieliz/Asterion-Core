package com.ailm.android.runtime

import java.util.Locale

/**
 * All species rules are resolved from the canonical Fusion graph:
 * SP ancestry, SA parent linkage, and explicit per-species requirements.
 * Species names are never used as magic classification rules.
 *
 * A required canonical trait is a CHARACTER PROFILE constraint, not a claim
 * that the corresponding body part is visible in the current image.
 */
internal object FusionSpeciesTraitRules {
    enum class Status { REQUIRED, OPTIONAL, FORBIDDEN, UNLINKED }

    data class Expectation(
        val speciesId: String,
        val traitId: String,
        val requirement: Status,
    )

    private val speciesCode = Regex("^SP[0-9]{2,}(?:-[0-9]+)*$")
    private fun normalizedId(id: String) = id.trim().uppercase(Locale.ROOT)

    private fun parseStatus(value: String?): Status? = when (value?.trim()?.lowercase(Locale.ROOT)) {
        "required" -> Status.REQUIRED
        "optional" -> Status.OPTIONAL
        "forbidden" -> Status.FORBIDDEN
        else -> null
    }

    /** Specific subtype first, followed by inherited parent SP identifiers. */
    fun speciesLineage(
        id: String,
        catalog: Map<String, FusionTaxonomyEngine.Candidate>,
    ): List<String> {
        val chain = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        var next = normalizedId(id)
        while (speciesCode.matches(next) && visited.add(next) && chain.size < 24) {
            chain += next
            next = normalizedId(catalog[next]?.parentId.orEmpty())
        }
        return chain
    }

    /** A nested SA group may eventually inherit from a canonical SP node. */
    private fun speciesParentOfAttribute(
        attribute: FusionTaxonomyEngine.Candidate,
        catalog: Map<String, FusionTaxonomyEngine.Candidate>,
    ): String? {
        val visited = mutableSetOf<String>()
        var current = normalizedId(attribute.parentId)
        while (current.isNotBlank() && visited.add(current) && visited.size <= 24) {
            if (speciesCode.matches(current)) return current
            current = normalizedId(catalog[current]?.parentId.orEmpty())
        }
        return null
    }

    fun status(
        species: FusionTaxonomyEngine.Candidate,
        attribute: FusionTaxonomyEngine.Candidate,
        catalog: Map<String, FusionTaxonomyEngine.Candidate>,
    ): Status {
        if (species.family != "SP" || attribute.family != "SA") return Status.UNLINKED
        val lineage = speciesLineage(species.id, catalog)
        if (lineage.isEmpty()) return Status.UNLINKED

        // Explicit rules may override an inherited parent rule. The closest
        // subtype wins. A forbidden rule excludes this trait even if its
        // parent species normally requires or permits it.
        for (speciesId in lineage) {
            parseStatus(attribute.speciesRules[speciesId])?.let { return it }
        }

        val parent = speciesParentOfAttribute(attribute, catalog)
        if (parent == null || parent !in lineage) return Status.UNLINKED
        return parseStatus(attribute.traitRequirement) ?: Status.OPTIONAL
    }

    fun requiredExpectations(
        enabledSpeciesIds: Set<String>,
        catalog: Map<String, FusionTaxonomyEngine.Candidate>,
    ): List<Expectation> = buildList {
        val traits = catalog.values.filter { it.family == "SA" }
        for (id in enabledSpeciesIds) {
            val species = catalog[normalizedId(id)] ?: continue
            if (species.family != "SP") continue
            for (trait in traits) {
                if (status(species, trait, catalog) == Status.REQUIRED) {
                    add(Expectation(species.id, trait.id, Status.REQUIRED))
                }
            }
        }
    }.distinct()
}
