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

        val actualSpecies = candidates.asSequence()
            .filter { it.id.startsWith("SP", ignoreCase = true) &&
                it.confidence >= 0.72 && it.visible && it.evidence.isNotBlank() }
            .map { it.id.uppercase(Locale.ROOT) }
            .toSet()

        // Include SP ancestry and nested SA parents from native Fusion rather
        // than hardcoded Elf/Dragon/Human names or fabricated model labels.
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
        actualSpecies.forEach(::addPath)
        candidates.filter { it.id.startsWith("SA", ignoreCase = true) }
            .forEach { addPath(it.id) }

        return buildMap {
            for (candidate in candidates) {
                if (candidate.confidence < 0.72 || !candidate.visible ||
                    candidate.evidence.isBlank()) continue
                val family = candidate.id.takeWhile(Char::isLetter).uppercase(Locale.ROOT)
                if (family == "SA") {
                    val trait = catalog[candidate.id] ?: continue
                    // A rule may be required/optional for some species but
                    // forbidden for others. Accept only a compatible enabled
                    // species of THIS same subject.
                    val permitted = actualSpecies.any { id ->
                        val species = catalog[id] ?: return@any false
                        val status = FusionSpeciesTraitRules.status(species, trait, catalog)
                        status == FusionSpeciesTraitRules.Status.REQUIRED ||
                            status == FusionSpeciesTraitRules.Status.OPTIONAL
                    }
                    if (!permitted) continue
                }
                put(candidate.id, maxOf(get(candidate.id) ?: 0.0, candidate.confidence))
            }
        }
    }
}
