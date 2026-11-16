package com.ailm.android.runtime

/**
 * Infer species only from canonical SP/SA associations already stored with
 * character profiles. No string similarity, rewritten IDs or fabricated WD
 * logits. A single shared trait (e.g. pointed ears) remains a Review clue.
 */
internal object FusionSpeciesInferencePolicy {
    data class Link(val speciesId: String, val attributeId: String, val profileCount: Int)
    data class Candidate(
        val speciesId: String, val supportingTraits: Set<String>,
        val supportingProfiles: Int,
    )

    data class Decision(
        val acceptedSpeciesId: String?,
        val candidates: List<Candidate>,
    )

    fun decide(
        visibleSA: Set<String>,
        links: List<Link>,
    ): Decision {
        val traits = visibleSA.filter { it.matches(Regex("SA[0-9]{2,}(?:-[0-9]+)*")) }.toSet()
        if (traits.isEmpty()) return Decision(null, emptyList())
        val grouped = links.filter { it.attributeId in traits &&
            it.speciesId.matches(Regex("SP[0-9]{2,}(?:-[0-9]+)*")) }
            .groupBy { it.speciesId }
        val ranked = grouped.map { (species, associations) ->
            Candidate(species,
                associations.map { it.attributeId }.toSet(),
                associations.sumOf { it.profileCount.coerceAtLeast(0) },
            )
        }.sortedWith(compareByDescending<Candidate> { it.supportingTraits.size }
            .thenByDescending { it.supportingProfiles }.thenBy { it.speciesId })
        val coveringAll = ranked.filter { it.supportingTraits.size == traits.size }
        val lone = coveringAll.singleOrNull()
        // One trait is not conclusive without repeated independent evidence.
        val safe = lone?.takeIf {
            traits.size >= 2 || it.supportingProfiles >= 3
        }?.speciesId
        return Decision(safe, ranked)
    }
}
