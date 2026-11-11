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

        fun requiredSpecies(id: String): String? {
            var parent = known[id]?.first?.parentId.orEmpty()
            val visited = mutableSetOf<String>()
            repeat(6) {
                if (parent.isBlank() || !visited.add(parent.uppercase(Locale.ROOT))) return null
                if (parent.startsWith("SP", ignoreCase = true)) return parent.uppercase(Locale.ROOT)
                parent = resolve(parent)?.parentId.orEmpty()
            }
            return null
        }

        return buildMap {
            for ((id, details) in known) {
                // A species-specific anatomical trait without its declared
                // species in this same image is too speculative even for review.
                if (id.startsWith("SA") && requiredSpecies(id) !in known.keys) continue
                put(id, details.second)
            }
        }
    }
}
