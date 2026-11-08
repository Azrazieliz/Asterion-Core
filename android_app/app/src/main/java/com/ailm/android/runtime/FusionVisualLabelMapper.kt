package com.ailm.android.runtime

import java.util.Locale

/**
 * Maps a specialist image classifier's fixed output labels onto the native
 * Fusion taxonomy. This mapping is constructed from Fusion entries, never from
 * free-form VLM text and never from a taxonomy prompt sent to a language model.
 *
 * Ambiguous labels are excluded. Unsupported families are recorded explicitly.
 * Scores come from the executing classifier; no fabricated confidence is used.
 */
internal class FusionVisualLabelMapper(private val taxonomy: FusionTaxonomyEngine) {
    data class LabelScore(val label: String, val confidence: Double)

    fun evaluate(
        scores: List<LabelScore>,
        modelVocabulary: List<String>,
        modelId: String,
        minimumScore: Double = 0.82,
    ): Map<String, Any> = evaluateCandidates(
        tags = taxonomy.allTags(), scores = scores,
        modelVocabulary = modelVocabulary, modelId = modelId,
        minimumScore = minimumScore,
    )

    companion object {
        private val canonical = Regex("^[A-Z]{2}[0-9]{2,}$")
    /** Pure deterministic implementation, testable without Android SQLite. */
    fun evaluateCandidates(
        tags: List<FusionTaxonomyEngine.Candidate>,
        scores: List<LabelScore>,
        modelVocabulary: List<String>,
        modelId: String,
        minimumScore: Double = 0.82,
    ): Map<String, Any> {
        require(minimumScore in 0.0..1.0)
        val byId = tags.associateBy { it.id }
        val byLabel = mutableMapOf<String, MutableSet<String>>()
        tags.forEach { entry ->
            (listOf(entry.name) + entry.aliases).forEach { raw ->
                val key = normalize(raw)
                if (key.isNotEmpty()) byLabel.getOrPut(key) { linkedSetOf() }.add(entry.id)
            }
        }

        fun unique(label: String): String? {
            val direct = label.trim().uppercase(Locale.ROOT)
            if (canonical.matches(direct) && direct in byId) return direct
            return byLabel[normalize(label)]?.singleOrNull()
        }

        val supportedByFamily = mutableMapOf<String, MutableSet<String>>()
        modelVocabulary.forEach { label ->
            unique(label)?.let { id ->
                supportedByFamily.getOrPut(id.take(2)) { linkedSetOf() }.add(id)
            }
        }
        val mapped = linkedMapOf<String, Double>()
        val rejected = mutableListOf<String>()
        for ((label, confidence) in scores) {
            if (!confidence.isFinite() || confidence !in 0.0..1.0) continue
            val id = unique(label)
            when {
                id == null -> rejected += label.take(70)
                confidence >= minimumScore -> mapped[id] = maxOf(mapped[id] ?: 0.0, confidence)
                else -> Unit
            }
        }
        // A category can legitimately contain several tags. Remove only
        // contradictions that Fusion can explicitly recognize.
        val byFamily = mapped.keys.groupBy { it.take(2) }
        if (byFamily["PO"].orEmpty().size > 1) {
            val poses = byFamily["PO"].orEmpty().mapNotNull(byId::get)
            if (!CanonicalImageTagCoherence.internallyConsistent("PO", poses.map { it.name })) {
                byFamily["PO"].orEmpty().forEach(mapped::remove)
            }
        }
        val coverage = tags.groupBy { it.family }.map { (family, entries) ->
            val supported = supportedByFamily[family].orEmpty()
            val accepted = mapped.keys.count { it.take(2) == family }
            mapOf<String, Any>(
                "family" to family,
                "candidate_count" to entries.size,
                "trained_label_count" to supported.size,
                "accepted_count" to accepted,
                "status" to when {
                    supported.isEmpty() -> "unsupported_model"
                    accepted > 0 -> "accepted"
                    else -> "evaluated_no_confident_match"
                },
            )
        }
        val scored = mapped.map { (id, score) ->
            mapOf<String, Any>("tag" to id, "score" to score)
        }
        return mapOf(
            "model_id" to modelId,
            "tags" to mapped.keys.toList(),
            "scored_tags" to scored,
            "family_coverage" to coverage,
            "unmapped_labels" to rejected.distinct().take(40),
            "accepted_count" to scored.size,
            "evaluated_families" to coverage.count { it["status"] != "unsupported_model" },
            "unsupported_families" to coverage.count { it["status"] == "unsupported_model" },
            "minimum_score" to minimumScore,
            "source" to "native_classifier",
        )
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}
