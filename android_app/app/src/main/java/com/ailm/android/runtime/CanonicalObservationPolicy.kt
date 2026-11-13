package com.ailm.android.runtime

import java.util.Locale

/**
 * Validate observations for one individual subject. Image tags are optional:
 * a family without genuine pixel evidence is omitted rather than guessed.
 * Canonical character rules are checked against that subject's enabled SP.
 */
internal object CanonicalObservationPolicy {
    data class Descriptor(
        val id: String,
        val parentId: String = "",
        val name: String = "",
        val traitRequirement: String = "",
        val speciesRules: Map<String, String> = emptyMap(),
    )

    private data class Proposed(
        val id: String, val confidence: Double, val visible: Boolean,
        val evidence: String,
    )

    fun sanitize(
        raw: Map<String, Any>,
        verifiedSpeciesIds: Set<String> = emptySet(),
        resolve: (String) -> Descriptor?,
    ): Map<String, Double> {
        val candidates = mutableListOf<Proposed>()
        (raw["attributes"] as? List<*>)?.forEach { item ->
            val row = item as? Map<*, *> ?: return@forEach
            val id = (row["id"] ?: row["value"])?.toString()?.trim().orEmpty()
            val tag = resolve(id) ?: return@forEach
            val confidence = (row["confidence"] as? Number)?.toDouble() ?: return@forEach
            if (!confidence.isFinite() || confidence !in 0.0..1.0) return@forEach
            candidates += Proposed(
                tag.id, confidence, row["visible"] == true,
                row["evidence"]?.toString()?.trim().orEmpty(),
            )
        }
        // Legacy compact IDs contain no subject-local visibility evidence.
        // They must not become accepted observations for any family.
        (raw["attribute_ids"] as? Map<*, *>)?.forEach { (rawId, value) ->
            val tag = resolve(rawId?.toString().orEmpty()) ?: return@forEach
            val confidence = (value as? Number)?.toDouble() ?: return@forEach
            if (!confidence.isFinite() || confidence !in 0.0..1.0) return@forEach
            candidates += Proposed(tag.id, confidence, false, "")
        }

        // SA is a first-class pixel observation, not a property granted by
        // a guessed species. A visible SA must survive when SP is unresolved.
        // Only manually VERIFIED species may veto a trait with an explicit
        // FORBIDDEN rule. An unverified conflicting SP loses to visible SA.
        val verifiedSpecies = verifiedSpeciesIds.map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.startsWith("SP") && resolve(it)?.id == it }.toSet()
        val inferredSpecies = candidates.asSequence()
            .filter { it.id.startsWith("SP", ignoreCase = true) &&
                it.confidence >= 0.72 && it.visible && it.evidence.isNotBlank() }
            .map { it.id.uppercase(Locale.ROOT) }.toSet()

        val catalog = linkedMapOf<String, FusionTaxonomyEngine.Candidate>()
        fun addPath(rawId: String) {
            val seen = mutableSetOf<String>()
            var id = rawId.trim().uppercase(Locale.ROOT)
            while (id.isNotBlank() && seen.add(id) && seen.size <= 24) {
                val tag = resolve(id) ?: break
                catalog[tag.id] = FusionTaxonomyEngine.Candidate(
                    id = tag.id, name = tag.name, category = tag.id.take(2),
                    parentId = tag.parentId, traitRequirement = tag.traitRequirement,
                    speciesRules = tag.speciesRules,
                )
                id = tag.parentId.trim().uppercase(Locale.ROOT)
            }
        }
        (verifiedSpecies + inferredSpecies).forEach(::addPath)
        candidates.filter { it.id.startsWith("SA", ignoreCase = true) }
            .forEach { addPath(it.id) }
        fun relation(sp: String, sa: String): FusionSpeciesTraitRules.Status {
            val species = catalog[sp] ?: return FusionSpeciesTraitRules.Status.UNLINKED
            val attribute = catalog[sa] ?: return FusionSpeciesTraitRules.Status.UNLINKED
            return FusionSpeciesTraitRules.status(species, attribute, catalog)
        }
        val visibleTraits = candidates.filter { candidate ->
            candidate.id.startsWith("SA", ignoreCase = true) &&
                candidate.confidence >= 0.72 && candidate.visible &&
                candidate.evidence.isNotBlank()
        }.map { it.id.uppercase(Locale.ROOT) }
        val contradictedPredictions = inferredSpecies.filter { sp ->
            sp !in verifiedSpecies && visibleTraits.any { sa ->
                relation(sp, sa) == FusionSpeciesTraitRules.Status.FORBIDDEN
            }
        }.toSet()

        return buildMap {
            for (candidate in candidates) {
                if (candidate.confidence < 0.72 || !candidate.visible ||
                    candidate.evidence.isBlank()) continue
                val id = candidate.id.uppercase(Locale.ROOT)
                val family = id.take(2)
                if (family == "SP" && id in contradictedPredictions) continue
                if (family == "SA" && verifiedSpecies.isNotEmpty() &&
                    verifiedSpecies.all { sp ->
                        relation(sp, id) == FusionSpeciesTraitRules.Status.FORBIDDEN
                    }) continue
                put(id, maxOf(get(id) ?: 0.0, candidate.confidence))
            }
        }

    }
}
