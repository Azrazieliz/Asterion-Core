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

    fun verifiedCropIds(subjectAnalysis: Map<String, Any>): Set<String> {
        if (subjectAnalysis["subject_detection_status"] != "detected") return emptySet()
        val results = linkedSetOf<String>()
        (subjectAnalysis["subjects"] as? List<*>).orEmpty().forEach { raw ->
            val subject = raw as? Map<*, *> ?: return@forEach
            (subject["attributes"] as? List<*>).orEmpty().forEach { item ->
                val row = item as? Map<*, *> ?: return@forEach
                val id = row["id"]?.toString()?.trim()?.uppercase(java.util.Locale.ROOT)
                    .orEmpty()
                val confidence = (row["confidence"] as? Number)?.toDouble() ?: return@forEach
                if (canonicalId.matches(id) && row["visible"] == true &&
                    row["evidence"] in setOf("wd_vit_v3_subject_crop_verified",
                        "wd_vit_v3_primary_upper_focus_verified") &&
                    confidence.isFinite() && confidence in 0.40..1.0) {
                    results += id
                }
            }
        }
        return results
    }
}
