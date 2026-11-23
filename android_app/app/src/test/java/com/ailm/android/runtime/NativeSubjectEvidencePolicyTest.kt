package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class NativeSubjectEvidencePolicyTest {
    private fun crop(id: String, confidence: Double = 0.56, visible: Boolean = true,
        evidence: String = "wd_vit_v3_subject_crop_verified"): Map<String, Any> =
        mapOf("id" to id, "confidence" to confidence, "visible" to visible,
            "evidence" to evidence)

    @Test fun overlappingCropsKeepReviewEvidenceWithoutClaimingAttribution() {
        val tags = listOf(crop("HC001", .98))
        assertTrue(NativeSubjectEvidencePolicy.verifiedCropIds(mapOf(
            "subject_detection_status" to "detected",
            "subjects" to listOf(mapOf("unverified_overlap" to true, "attributes" to tags))
        )).isEmpty())
        val rows = listOf(mapOf("family" to "HC", "status" to "accepted",
            "accepted_count" to 1, "visual_candidate_comparisons" to 43))
        val row = NativeSubjectEvidencePolicy.finalCoverage(rows, emptySet(), setOf("HC001"), true).single()
        assertEquals("subject_overlap_review", row["status"])
        assertEquals(0, row["accepted_count"])
        assertEquals(1, row["deferred_count"])
        assertEquals(43, row["visual_candidate_comparisons"])
    }

    @Test fun validCropRetainsTagsWhenImageWideHasNone() {
        val actual = NativeSubjectEvidencePolicy.verifiedCropIds(mapOf(
            "subject_detection_status" to "detected",
            "subjects" to listOf(mapOf(
                "attributes" to listOf(
                    crop("HC001", 0.56), crop("EC001", 0.48),
                    crop("SP001", 0.63),
                ),
            )),
        ))
        assertEquals(setOf("HC001", "EC001", "SP001"), actual)
        assertTrue(actual.isNotEmpty())
    }

    @Test fun acceptedFocusedEyeTagsSatisfyNativeSubjectSuccessGate() {
        val actual = NativeSubjectEvidencePolicy.verifiedCropIds(mapOf(
            "subject_detection_status" to "detected",
            "subjects" to listOf(mapOf("attributes" to listOf(
                crop("EC001", .65, evidence = "wd_vit_v3_primary_upper_focus_verified"),
                crop("HC001", .72, evidence = "wd_vit_v3_primary_upper_focus_verified"),
                crop("EC002", .90, evidence = "unverified_upper_crop"),
            ))),
        ))
        assertEquals(setOf("EC001", "HC001"), actual)
    }

    @Test fun fakeGlobalModelTextAndBadDetectionCannotApproveTags() {
        val raw = listOf(crop("HC001", 0.75))
        assertTrue(NativeSubjectEvidencePolicy.verifiedCropIds(
            mapOf("subject_detection_status" to "no_person_detected",
                "subjects" to listOf(mapOf("attributes" to raw))),
        ).isEmpty())
        val badRows = listOf(
            crop("HC001", evidence = "whole_image"),
            crop("HC002", visible = false),
            crop("EC001", confidence = 0.39),
            crop("RT001", evidence = "text_prediction"),
            crop("random text", 0.95),
        )
        assertTrue(NativeSubjectEvidencePolicy.verifiedCropIds(mapOf(
            "subject_detection_status" to "detected",
            "subjects" to listOf(mapOf("attributes" to badRows)),
        )).isEmpty())
    }
}
