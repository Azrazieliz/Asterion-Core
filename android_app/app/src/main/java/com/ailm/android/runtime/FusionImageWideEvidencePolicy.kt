package com.ailm.android.runtime

/**
 * A fixed-head artwork tagger sees the whole picture, not a specific person.
 * Preserve that distinction instead of persisting species-specific traits or
 * mutually exclusive attribute values as if their subject were established.
 *
 * All withheld scores remain reviewable; they are not silently declared false.
 * This policy does not alter the canonical taxonomy or manual corrections.
 */
internal object FusionImageWideEvidencePolicy {
    data class Deferred(val id: String, val score: Double, val reason: String)
    data class Decision(val accepted: Map<String, Double>, val deferred: List<Deferred>)

    fun reconcile(
        scored: Map<String, Double>,
        candidates: Map<String, FusionTaxonomyEngine.Candidate>,
        modelId: String,
    ): Decision {
        val accepted = linkedMapOf<String, Double>()
        accepted.putAll(scored)
        val deferred = linkedMapOf<String, Deferred>()
        fun hold(id: String, reason: String) {
            val score = accepted.remove(id) ?: return
            deferred[id] = Deferred(id, score, reason)
        }

        // SA describes anatomy linked to a species/subject. The image-wide
        // classifier has neither an observed subject region nor a subject-local
        // SP parent. 'halo' alone can be an accessory, background or another
        // person's detail, so never promote it to verified SA automatically.
        accepted.keys.filter { it.startsWith("SA") }.toList().forEach {
            hold(it, "subject_and_species_evidence_required")
        }

        if (modelId == "asterion-wd-vit-tagger-v3") {
            // Thresholds for visually broad context classes are deliberately
            // stronger than the multi-label 0.40 attribute admission floor.
            // These are conservative gates, not claims of calibrated accuracy.
            accepted.keys.filter { id ->
                (id.startsWith("PO") || id.startsWith("FR")) &&
                    (accepted[id] ?: 0.0) < 0.75
            }.toList().forEach { hold(it, "weak_pose_or_framing_evidence") }
        }

        // A single image may contain multiple people, so competing hair
        // lengths are not proof either prediction is wrong. Without bounding
        // boxes there is no safe per-person assignment. Retain a single
        // dominant observation only when the raw scores clearly separate it.
        val lengthIds = accepted.keys.filter { it.startsWith("HL") }
        if (lengthIds.size > 1) {
            val ordered = lengthIds.sortedByDescending { accepted[it] ?: 0.0 }
            val top = accepted[ordered[0]] ?: 0.0
            val runnerUp = accepted[ordered[1]] ?: 0.0
            val unambiguous = top >= 0.78 && (top - runnerUp) >= 0.16
            ordered.forEachIndexed { index, id ->
                if (!unambiguous || index != 0) {
                    hold(id, "multiple_hair_lengths_without_subject_regions")
                }
            }
        }

        for (family in listOf("PO", "FR")) {
            val ids = accepted.keys.filter { it.startsWith(family) }
            val names = ids.mapNotNull { candidates[it]?.name }
            if (ids.size > 1 && names.size == ids.size &&
                !CanonicalImageTagCoherence.internallyConsistent(family, names)) {
                ids.forEach { hold(it, "incompatible_image_wide_$family_predictions") }
            }
        }
        return Decision(accepted.toMap(), deferred.values.toList())
    }
}
