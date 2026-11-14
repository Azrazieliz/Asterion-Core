package com.ailm.android.runtime

/**
 * Keep actual WD scores, but abstain on competing, weak subject descriptions.
 * No taxonomy change, fabricated labels, or modification of manual tags.
 */
internal object SubjectTagConsistencyPolicy {
    data class Decision(
        val accepted: Map<String, Double>,
        val withheld: Map<String, String>,
    )

    fun reconcile(
        scored: Map<String, Double>,
        names: Map<String, String>,
        nativeSubjectCrop: Boolean,
    ): Decision {
        if (!nativeSubjectCrop) return Decision(scored.toMap(), emptyMap())
        val accepted = scored.toMutableMap()
        val withheld = linkedMapOf<String, String>()
        fun hold(id: String, reason: String) {
            if (accepted.remove(id) != null) withheld[id] = reason
        }
        fun label(id: String): String = names[id].orEmpty()
            .lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ").trim()
        // Whole-image WD clothing heads are highly overlapping. For the
        // primary subject, only retain garments with strong crop-level visual
        // evidence. Low-scored outfit and prop guesses stay out of the
        // permanent canonical tag set; later manual corrections remain valid.
        accepted.keys.filter { id ->
            val score = accepted[id] ?: 0.0
            (id.startsWith("OF") && score < 0.76) ||
                (id.startsWith("WP") && score < 0.74)
        }.toList().forEach { hold(it, "weak_primary_outfit_or_prop_evidence") }
        // Skintight clothing and large skin regions can strongly activate
        // generic 'nude' heads. Never store a low-score crop as fact.
        accepted.keys.filter { it.startsWith("OF") && label(it) in
            setOf("nude", "naked", "completely naked", "fully nude")
        }.toList().forEach { id ->
            val clothed = accepted.keys.any { other ->
                other != id && other.startsWith("OF") &&
                    listOf("dress", "bodysuit", "leotard", "swimsuit",
                        "uniform", "shirt", "jacket", "pants", "leggings",
                        "sweater", "costume", "robe", "bikini", "one piece")
                        .any { garment -> garment in label(other) }
            }
            if ((accepted[id] ?: 0.0) < 0.92 || clothed) {
                hold(id, "weak_or_clothed_nudity_prediction")
            }
        }
        // A single detected character cannot have several mutually
        // exclusive sizes or hair lengths. When the head cannot distinguish
        // them clearly, keep neither automatic claim.
        for (family in listOf("BS", "HL", "SX")) {
            val ids = accepted.keys.filter { it.startsWith(family) }
            if (ids.size < 2) continue
            val ordered = ids.sortedByDescending { accepted[it] ?: 0.0 }
            val high = accepted[ordered[0]] ?: 0.0
            val second = accepted[ordered[1]] ?: 0.0
            val clear = high >= 0.75 && high - second >= 0.17
            ordered.forEachIndexed { index, id ->
                if (!clear || index > 0) hold(id, "conflicting_subject_" + family.lowercase() + "_scores")
            }
        }
        val toned = accepted.keys.filter { it.startsWith("BT") &&
            label(it) in setOf("toned", "athletic") }
        val muscular = accepted.keys.filter { it.startsWith("BT") &&
            label(it) in setOf("muscular", "very muscular") }
        if (toned.isNotEmpty() && muscular.isNotEmpty()) {
            val ids = toned + muscular
            val ranked = ids.sortedByDescending { accepted[it] ?: 0.0 }
            val clear = (accepted[ranked[0]] ?: 0.0) >= 0.75 &&
                (accepted[ranked[0]] ?: 0.0) -
                    (accepted[ranked[1]] ?: 0.0) >= 0.17
            ranked.forEachIndexed { i, id ->
                if (!clear || i > 0) hold(id, "overlapping_subject_body_type_scores")
            }
        }
        return Decision(accepted.toMap(), withheld.toMap())
    }
}
