package com.ailm.android.runtime

/**
 * A multi-label WD head can have ZERO safe whole-image tags (human families
 * are deliberately deferred) while one or more real detected person crops
 * already have verified native Fusion attributes.
 *
 * The automation success gate must count those trained, person-scoped scores,
 * otherwise it reports a false visual-classifier failure, skips the Fusion
 * commit entirely and sends every portrait straight to Review after RT.
 *
 * Only actual detector output with explicit native crop evidence qualifies:
 * prompt text, image-wide guesses and unvalidated IDs never count.
 */
internal object NativeSubjectEvidencePolicy {
    private val canonicalId = Regex("^[A-Z]{2}[0-9]{2,}(?:-[0-9]+)*$")

    fun finalCoverage(
        raw: Any?, accepted: Set<String>, withheld: Set<String>, overlap: Boolean,
    ): List<Map<String, Any>> {
        val acceptedCounts = accepted.groupingBy { it.take(2) }.eachCount()
        val withheldCounts = withheld.groupingBy { it.take(2) }.eachCount()
        return (raw as? List<*>).orEmpty().mapNotNull { value ->
            val row = value as? Map<*, *> ?: return@mapNotNull null
            val family = row["family"] as? String ?: return@mapNotNull null
            val count = acceptedCounts[family] ?: 0
            val held = withheldCounts[family] ?: 0
            val original = row["status"] as? String ?: return@mapNotNull null
            val status = when {
                count > 0 -> "accepted"
                held > 0 && overlap -> "subject_overlap_review"
                held > 0 -> "deferred_review"
                original == "accepted" -> "evaluated_no_confident_match"
                else -> original
            }
            row.entries.mapNotNull { (k, v) ->
                if (k is String && v != null) k to v else null
            }.toMap() + mapOf("observation_outcome" to when {
                overlap || held > 0 -> "requires_review"
                count > 0 -> "recognized"
                original == "accepted" -> "visually_indeterminate"
                else -> row["observation_outcome"]?.toString() ?: "visually_indeterminate"
            }, "status" to status, "accepted_count" to count,
                "deferred_count" to (((row["deferred_count"] as? Number)?.toInt() ?: 0) + held))
        }
    }

    fun verifiedCropIds(subjectAnalysis: Map<String, Any>): Set<String> {
        if (subjectAnalysis["subject_detection_status"] != "detected") return emptySet()
        val results = linkedSetOf<String>()
        (subjectAnalysis["subjects"] as? List<*>).orEmpty().forEach { raw ->
            val subject = raw as? Map<*, *> ?: return@forEach
            if (subject["unverified_overlap"] == true) return@forEach
            (subject["attributes"] as? List<*>).orEmpty().forEach { item ->
                val row = item as? Map<*, *> ?: return@forEach
                val id = row["id"]?.toString()?.trim()?.uppercase(java.util.Locale.ROOT)
                    .orEmpty()
                val confidence = (row["confidence"] as? Number)?.toDouble() ?: return@forEach
                if (canonicalId.matches(id) && row["visible"] == true &&
                    row["evidence"] in setOf(
                        "wd_vit_v3_subject_crop_verified",
                        "wd_vit_v3_primary_upper_focus_verified",
                    ) && confidence.isFinite() && confidence in 0.40..1.0) {
                    results += id
                }
            }
        }
        return results
    }
}
