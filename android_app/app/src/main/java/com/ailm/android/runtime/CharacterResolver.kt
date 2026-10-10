package com.ailm.android.runtime

import kotlin.math.max

internal data class ResolvedSubject(
    val subjectIndex: Int,
    val prominence: Double,
    val observations: Map<String, Double>,
    val character: KnowledgeCharacterEntry?,
    val confidence: Double,
    val candidates: List<Map<String, Any>>,
) {
    val resolved: Boolean get() = character != null
}

internal data class RankedCandidate(
    val candidate: KnowledgeCandidate,
    val coherentAttributeScore: Double,
    val visualScore: Double?,
    val finalScore: Double,
)

internal class CharacterResolver(
    private val knowledge: KnowledgeDatabase,
    private val fusion: FusionResolutionStore,
    private val acceptanceThreshold: Double = DEFAULT_ACCEPTANCE_THRESHOLD,
) {
    fun resolve(
        recognitionResult: Map<String, Any>,
        illustrationFeatureIds: Map<String, Double>,
        imageEmbedding: List<Double>,
    ): List<ResolvedSubject> {
        val rawSubjects = recognitionResult["subjects"].asMapList()
        if (rawSubjects.isEmpty()) return emptyList()
        val singleSubjectImage = rawSubjects.size == 1

        return rawSubjects.mapIndexed { fallbackIndex, raw ->
            val subjectIndex = (raw["subject_index"] as? Number)?.toInt() ?: fallbackIndex
            val prominence = (raw["prominence"] as? Number)?.toDouble()?.coerceIn(0.0, 1.0) ?: 1.0
            val observed = parseObservedFeatures(raw)
                .toMutableMap()
                .apply {
                    // Illustration-level outfit/weapon tags cannot safely be
                    // assigned to a particular person in a multi-subject image.
                    if (singleSubjectImage) {
                        illustrationFeatureIds.forEach { (id, confidence) ->
                            val resolved = knowledge.resolveTag(id) ?: return@forEach
                            if (resolved.id.startsWith("WP", ignoreCase = true) ||
                                resolved.id.startsWith("OF", ignoreCase = true)
                            ) {
                                this[resolved.id] = max(this[resolved.id] ?: 0.0, confidence)
                            }
                        }
                    }
                }

            val explicitSubjectEmbedding = (raw["visual_embedding"] as? List<*>)
                ?.mapNotNull { (it as? Number)?.toDouble() }
                .orEmpty()
            val subjectEmbedding = explicitSubjectEmbedding.ifEmpty {
                if (singleSubjectImage) imageEmbedding else emptyList()
            }

            val ranked = knowledge.rankByFeatures(observed, CANDIDATE_POOL)
                .map { candidate ->
                    val coherentAttributeScore = applyContradictionPenalty(
                        candidate = candidate,
                        observed = observed,
                    )
                    val visual = fusion.visualSimilarity(candidate.character.characterId, subjectEmbedding)
                    val finalScore = if (visual == null) {
                        coherentAttributeScore
                    } else {
                        (ATTRIBUTE_WEIGHT * coherentAttributeScore + VISUAL_WEIGHT * visual).coerceIn(0.0, 1.0)
                    }
                    RankedCandidate(candidate, coherentAttributeScore, visual, finalScore)
                }
                .sortedByDescending { it.finalScore }

            val candidateMaps = ranked.take(REVIEW_CANDIDATE_COUNT).map { rankedCandidate ->
                val candidate = rankedCandidate.candidate
                val visual = rankedCandidate.visualScore
                val score = rankedCandidate.finalScore
                val character = candidate.character
                mapOf(
                    "character_id" to character.characterId,
                    "canonical_name" to character.canonicalName,
                    "display_name" to character.displayName,
                    "form_name" to character.formName,
                    "series_code" to character.primarySeriesCode,
                    "entry_type" to character.entryType,
                    "parent_character_id" to character.parentCharacterId,
                    "attribute_score" to rankedCandidate.coherentAttributeScore,
                    "visual_score" to (visual ?: -1.0),
                    "confidence" to score,
                )
            }

            val best = ranked.firstOrNull()
            val acceptedCharacter = best
                ?.takeIf { it.finalScore >= acceptanceThreshold }
                ?.candidate
                ?.character
            ResolvedSubject(
                subjectIndex = subjectIndex,
                prominence = prominence,
                observations = observed,
                character = acceptedCharacter,
                confidence = best?.finalScore ?: 0.0,
                candidates = candidateMaps,
            )
        }
    }

    private fun applyContradictionPenalty(
        candidate: KnowledgeCandidate,
        observed: Map<String, Double>,
    ): Double {
        if (observed.isEmpty()) return candidate.attributeScore
        val candidateFeatures = (
            candidate.character.attributeIds +
                candidate.character.weaponIds +
                candidate.character.outfitIds
            ).toSet()
        val contradictionFraction = CanonicalAttributeSemantics.contradictionFraction(
            observed = observed,
            candidateFeatures = candidateFeatures,
            strictFamilies = STRICT_CONTRADICTION_FAMILIES,
            weight = KnowledgeDatabase::featureWeight,
        )
        val penalty = CONTRADICTION_PENALTY * contradictionFraction
        return (candidate.attributeScore - penalty).coerceIn(0.0, 1.0)
    }

    private fun parseObservedFeatures(raw: Map<String, Any>): Map<String, Double> {
        val out = linkedMapOf<String, Double>()

        raw["attributes"].asMapList().forEach { item ->
            val rawId = item["id"]?.toString()?.trim().orEmpty()
                .ifBlank { item["value"]?.toString()?.trim().orEmpty() }
            val confidence = (item["confidence"] as? Number)?.toDouble()?.coerceIn(0.0, 1.0) ?: DEFAULT_OBSERVATION_CONFIDENCE
            knowledge.resolveTag(rawId)?.let { tag ->
                out[tag.id] = max(out[tag.id] ?: 0.0, confidence)
            }
        }

        // Accept the compact map form as well:
        // "attribute_ids": {"HC001": 0.9, "EC004": 0.7}
        val compact = raw["attribute_ids"] as? Map<*, *>
        compact?.forEach { (key, value) ->
            val rawId = key?.toString()?.trim().orEmpty()
            val confidence = (value as? Number)?.toDouble()?.coerceIn(0.0, 1.0) ?: DEFAULT_OBSERVATION_CONFIDENCE
            knowledge.resolveTag(rawId)?.let { tag ->
                out[tag.id] = max(out[tag.id] ?: 0.0, confidence)
            }
        }

        return out
    }

    private fun Any?.asMapList(): List<Map<String, Any>> = (this as? List<*>)
        ?.mapNotNull { value ->
            (value as? Map<*, *>)?.entries
                ?.mapNotNull { (key, item) -> key?.toString()?.let { text -> item?.let { text to it } } }
                ?.toMap()
        }
        .orEmpty()

    companion object {
        const val DEFAULT_ACCEPTANCE_THRESHOLD = 0.75
        private const val CANDIDATE_POOL = 80
        private const val REVIEW_CANDIDATE_COUNT = 8
        private const val ATTRIBUTE_WEIGHT = 0.62
        private const val VISUAL_WEIGHT = 0.38
        private const val CONTRADICTION_PENALTY = 0.65
        private const val DEFAULT_OBSERVATION_CONFIDENCE = 0.70
        private val STRICT_CONTRADICTION_FAMILIES = setOf(
            "HC", "HL", "EC", "ET", "SC", "BH", "BT", "BS", "BY", "SX", "AG", "SP",
        )
    }
}
