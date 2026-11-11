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
        private val canonical = Regex("^[A-Z]{2}[0-9]{2,}(?:-[0-9]+)*$")
    /** Pure deterministic implementation, testable without Android SQLite. */
    fun evaluateCandidates(
        tags: List<FusionTaxonomyEngine.Candidate>,
        scores: List<LabelScore>,
        modelVocabulary: List<String>,
        modelId: String,
        minimumScore: Double = 0.82,
    ): Map<String, Any> {
        require(minimumScore in 0.0..1.0)
        // Fixed-head predictions are authoritative only when they match an
        // actual, unambiguous slot in the installed model's label vocabulary.
        // Reject out-of-vocabulary outputs rather than treating generated or
        // stale labels as evidence. Duplicate normalized labels are invalid:
        // they cannot be associated with one unique classifier output slot.
        val vocabularyCounts = modelVocabulary.map(::normalize)
            .filter(String::isNotBlank).groupingBy { it }.eachCount()
        val trustedVocabulary = vocabularyCounts.filterValues { it == 1 }.keys
        val byId = tags.associateBy { it.id }
        val byLabel = mutableMapOf<String, MutableSet<String>>()
        tags.forEach { entry ->
            vocabularyKeys(entry).forEach { key ->
                byLabel.getOrPut(key) { linkedSetOf() }.add(entry.id)
            }
        }

        fun unique(label: String): String? {
            val direct = label.trim().uppercase(Locale.ROOT)
            if (canonical.matches(direct) && direct in byId) return direct
            return byLabel[normalize(label)]?.singleOrNull()
        }

        val supportedByFamily = mutableMapOf<String, MutableSet<String>>()
        modelVocabulary.forEach { label ->
            if (normalize(label) !in trustedVocabulary) return@forEach
            unique(label)?.let { id ->
                supportedByFamily.getOrPut(id.take(2)) { linkedSetOf() }.add(id)
            }
        }
        val mapped = linkedMapOf<String, Double>()
        val rejected = mutableListOf<String>()
        for ((label, confidence) in scores) {
            if (normalize(label) !in trustedVocabulary) {
                rejected += label.take(70)
                continue
            }
            if (!confidence.isFinite() || confidence !in 0.0..1.0) continue
            val id = unique(label)
            when {
                id == null -> rejected += label.take(70)
                confidence >= minimumScore -> mapped[id] = maxOf(mapped[id] ?: 0.0, confidence)
                else -> Unit
            }
        }
        // Fixed-head scores are image-wide. Preserve uncertain species traits,
        // contradictory length/pose observations and their scores for Review
        // instead of silently accepting them as per-character facts.
        val imageWide = FusionImageWideEvidencePolicy.reconcile(mapped, byId, modelId)
        mapped.clear()
        mapped.putAll(imageWide.accepted)
        val coverage = tags.groupBy { it.family }.map { (family, entries) ->
            val supported = supportedByFamily[family].orEmpty()
            val accepted = mapped.keys.count { it.take(2) == family }
            val deferred = imageWide.deferred.count { it.id.take(2) == family }
            mapOf<String, Any>(
                "family" to family,
                "candidate_count" to entries.size,
                "trained_label_count" to supported.size,
                "accepted_count" to accepted,
                "deferred_count" to deferred,
                "status" to when {
                    supported.isEmpty() -> "unsupported_model"
                    accepted > 0 -> "accepted"
                    deferred > 0 -> "deferred_review"
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
            "deferred_scored_tags" to imageWide.deferred.map { item ->
                mapOf<String, Any>(
                    "tag" to item.id,
                    "name" to byId[item.id]?.name.orEmpty(),
                    "score" to item.score,
                    "reason" to item.reason,
                )
            },
            "family_coverage" to coverage,
            "unmapped_labels" to rejected.distinct().take(40),
            "accepted_count" to scored.size,
            "deferred_count" to imageWide.deferred.size,
            "evaluated_families" to coverage.count { it["status"] != "unsupported_model" },
            "unsupported_families" to coverage.count { it["status"] == "unsupported_model" },
            "minimum_score" to minimumScore,
            "source" to "native_classifier",
        )
    }

    /**
     * Use the live Fusion category and its aliases to align trained labels such
     * as "blue_hair" with a canonical "Blue" in the Hair Color family. This
     * does not manufacture missing visual evidence or restrict future families.
     * Ambiguous reconstructed labels still resolve to no canonical ID.
     */
    private fun vocabularyKeys(tag: FusionTaxonomyEngine.Candidate): Set<String> {
        val categories = normalize(tag.category)
        val genericQualifiers = setOf(
            "color", "colour", "length", "style", "type", "size",
            "traits", "trait", "state", "features", "feature",
        )
        val stem = categories.split(' ').filter {
            it.isNotBlank() && it !in genericQualifiers
        }.joinToString(" ")
        val suffixes = linkedSetOf<String>()
        if (categories.isNotBlank() && categories != tag.family.lowercase(Locale.ROOT)) {
            suffixes += categories
            if (stem.isNotBlank()) {
                suffixes += stem
                if (stem.endsWith("eye")) suffixes += stem + "s"
                if (stem.endsWith("breast")) suffixes += stem + "s"
            }
        }
        return buildSet {
            (listOf(tag.name) + tag.aliases).forEach { raw ->
                val key = normalize(raw)
                if (key.isBlank()) return@forEach
                add(key)
                for (suffix in suffixes) {
                    // Avoid "pink hair hair" and "long hair hair length".
                    if (key != suffix && !key.endsWith(" " + suffix)) {
                        add(key + " " + suffix)
                    }
                }
            }
        }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}
