package com.ailm.android.runtime

/**
 * Validates each subject independently before any observation is saved,
 * used for character recognition, or exported to the legacy tag index.
 * An SA species attribute is never inferred from an unrelated subject's SP.
 */
internal object CanonicalObservationPolicy {
    data class Descriptor(
        val id: String,
        val parentId: String = "",
        val name: String = "",
        val traitRequirement: String = "",
    )

    fun sanitize(
        raw: Map<String, Any>,
        resolve: (String) -> Descriptor?,
    ): Map<String, Double> {
        data class Proposed(val id: String, val confidence: Double, val visible: Boolean, val evidence: String)
        val candidates = mutableListOf<Proposed>()
        val attrs = (raw["attributes"] as? List<*>).orEmpty()
        for (item in attrs) {
            val row = item as? Map<*, *> ?: continue
            val rawId = (row["id"] ?: row["value"])?.toString()?.trim().orEmpty()
            val tag = resolve(rawId) ?: continue
            val conf = ((row["confidence"] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 1.0)
            val visible = row["visible"] == true
            val evidence = row["evidence"]?.toString()?.trim().orEmpty()
            candidates += Proposed(tag.id, conf, visible, evidence)
        }
        // Compact attributes without visibility metadata cannot establish a
        // species-specific trait or an occluded hair/eye observation.
        (raw["attribute_ids"] as? Map<*, *>)?.forEach { (id, value) ->
            val tag = resolve(id?.toString().orEmpty()) ?: return@forEach
            val conf = ((value as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 1.0)
            candidates += Proposed(tag.id, conf, false, "")
        }

        val actualSpecies = candidates.asSequence()
            .filter { it.id.startsWith("SP", ignoreCase = true) &&
                it.confidence >= 0.72 && it.visible && it.evidence.isNotBlank() }
            .map { it.id.uppercase() }
            .toSet()

        fun requiredSpecies(id: String): String? {
            var current = resolve(id)?.parentId.orEmpty()
            val visited = mutableSetOf<String>()
            repeat(6) {
                if (current.isBlank() || !visited.add(current.uppercase())) return null
                if (current.startsWith("SP", ignoreCase = true)) return current.uppercase()
                current = resolve(current)?.parentId.orEmpty()
            }
            return null
        }

        return buildMap {
            for (candidate in candidates) {
                val family = candidate.id.takeWhile(Char::isLetter).uppercase()
                if (candidate.confidence < 0.72) continue
                if (family == "SA") {
                    // Every species attribute requires a visible anatomical
                    // observation AND its declared SP parent on this subject.
                    if (!candidate.visible || candidate.evidence.isBlank()) continue
                    val expectedSpecies = requiredSpecies(candidate.id)
                    if (expectedSpecies != null) {
                        if (expectedSpecies !in actualSpecies) continue
                    } else {
                        // Preserve explicit owner-defined Elf/Dragon/Human
                        // species constraints even where incomplete historical
                        // imports have not yet linked SA.parent_tag_id to SP.
                        val trait = resolve(candidate.id) ?: continue
                        val allowedByEnabledSpecies = actualSpecies.any { speciesId ->
                            val species = resolve(speciesId) ?: return@any false
                            val catalog = mapOf(
                                speciesId to FusionTaxonomyEngine.Candidate(
                                    speciesId, species.name, "Species",
                                    parentId = species.parentId,
                                ),
                                candidate.id to FusionTaxonomyEngine.Candidate(
                                    candidate.id, trait.name, "Species Attribute",
                                    parentId = trait.parentId,
                                    traitRequirement = trait.traitRequirement,
                                ),
                            )
                            FusionSpeciesTraitRules.status(
                                catalog.getValue(speciesId),
                                catalog.getValue(candidate.id),
                                catalog,
                            ) in setOf(
                                FusionSpeciesTraitRules.Status.REQUIRED,
                                FusionSpeciesTraitRules.Status.OPTIONAL,
                            )
                        }
                        if (!allowedByEnabledSpecies) continue
                    }
                }
                if (family in setOf("HC", "EC", "HL", "ET") && !candidate.visible) continue
                put(candidate.id, maxOf(get(candidate.id) ?: 0.0, candidate.confidence))
            }
        }
    }
}
