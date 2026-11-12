package com.ailm.android.runtime

import java.util.Locale

/**
 * Conserve only finite, scored canonical Fusion IDs from the native
 * classifier's verified mapped output. Never interpret generated names,
 * descriptions or arbitrary prose as image observations.
 */
internal object FusionImageWideTagEvidence {
    private val canonicalId = Regex("^[A-Z]{2}[0-9]{2,}$")

    fun scoredFeatures(raw: Any?): Map<String, Double> {
        val rows = raw as? List<*> ?: return emptyMap()
        val results = linkedMapOf<String, Double>()
        for (row in rows) {
            val value = row as? Map<*, *> ?: continue
            val id = value["tag"]?.toString()?.trim()?.uppercase(Locale.ROOT).orEmpty()
            val score = (value["score"] as? Number)?.toDouble() ?: continue
            if (!canonicalId.matches(id) || !score.isFinite() || score !in 0.0..1.0) continue
            results[id] = maxOf(results[id] ?: 0.0, score)
        }
        return results
    }

    /**
     * Image-wide scores are useful for *review suggestions* against existing
     * Character Knowledge even before a reference sheet exists. They are NOT
     * subject-local observations and can never authorize identity assignment.
     *
     * The subject parser expects visible/region-specific attributes and
     * intentionally drops image-level evidence; do not send these scores to it.
     * Scores remain the model's measured outputs, never fabricated visibility.
     */
    fun reviewCandidateFeatures(
        scored: Map<String, Double>,
        resolve: (String) -> CanonicalObservationPolicy.Descriptor?,
    ): Map<String, Double> {
        val known = linkedMapOf<String, Pair<CanonicalObservationPolicy.Descriptor, Double>>()
        for ((rawId, score) in scored) {
            val id = rawId.trim().uppercase(Locale.ROOT)
            if (!canonicalId.matches(id) || !score.isFinite() || score !in 0.72..1.0) continue
            if (id.startsWith("RT") || id.startsWith("CH")) continue
            val descriptor = resolve(id) ?: continue
            if (!descriptor.id.equals(id, ignoreCase = true)) continue
            known[id] = descriptor to score
        }

        val catalog = linkedMapOf<String, FusionTaxonomyEngine.Candidate>()
        fun includePath(rawId: String) {
            val visited = mutableSetOf<String>()
            var id = rawId.trim().uppercase(Locale.ROOT)
            while (id.isNotBlank() && visited.add(id) && visited.size <= 24) {
                val descriptor = resolve(id) ?: break
                catalog[id] = FusionTaxonomyEngine.Candidate(
                    descriptor.id, descriptor.name, descriptor.id.take(2),
                    parentId = descriptor.parentId,
                    traitRequirement = descriptor.traitRequirement,
                    speciesRules = descriptor.speciesRules,
                )
                id = descriptor.parentId.trim().uppercase(Locale.ROOT)
            }
        }
        known.keys.forEach(::includePath)
        val enabledSpecies = known.keys.filter { it.startsWith("SP") }

        return buildMap {
            for ((id, details) in known) {
                if (id.startsWith("SA")) {
                    val trait = catalog[id] ?: continue
                    val compatible = enabledSpecies.any { speciesId ->
                        val species = catalog[speciesId] ?: return@any false
                        FusionSpeciesTraitRules.status(species, trait, catalog) in setOf(
                            FusionSpeciesTraitRules.Status.REQUIRED,
                            FusionSpeciesTraitRules.Status.OPTIONAL,
                        )
                    }
                    if (!compatible) continue
                }
                put(id, details.second)
            }
        }
    }
}
