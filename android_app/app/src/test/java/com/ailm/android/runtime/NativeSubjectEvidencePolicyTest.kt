package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class NativeSubjectEvidencePolicyTest {
    private fun crop(id: String, confidence: Double = 0.56, visible: Boolean = true,
        evidence: String = "wd_vit_v3_subject_crop_verified"): Map<String, Any> =
        mapOf("id" to id, "confidence" to confidence, "visible" to visible,
            "evidence" to evidence)

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
