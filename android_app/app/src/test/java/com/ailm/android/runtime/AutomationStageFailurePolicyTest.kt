package com.ailm.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationStageFailurePolicyTest {
    @Test fun missingOptionalEnrichmentDoesNotBlockVerifiedNativeTagging() {
        val failures = listOf(
            mapOf<String, Any>("stage_type" to "ocr", "status" to "timeout"),
            mapOf<String, Any>("stage_type" to "embedding_generation", "status" to "unavailable"),
            mapOf<String, Any>("stage_type" to "aesthetic_scoring", "status" to "failed"),
            mapOf<String, Any>("stage_type" to "qwen_semantic_bundle", "status" to "invalid_output"),
        )
        assertTrue(AutomationStageFailurePolicy.blocking(failures, true).isEmpty())
        assertTrue(AutomationStageFailurePolicy.isBlocking("qwen_semantic_bundle", false))
    }

    @Test fun safetyAndClassifierErrorsAlwaysBlockFileOrganization() {
        val failures = listOf(
            mapOf<String, Any>("stage_type" to "nsfw_classification"),
            mapOf<String, Any>("stage_type" to "native_visual_tagger"),
            mapOf<String, Any>("stage_type" to "unknown_stage"),
        )
        assertEquals(3, AutomationStageFailurePolicy.blocking(failures, true).size)
        assertFalse(AutomationStageFailurePolicy.isBlocking("ocr", false))
    }
}
