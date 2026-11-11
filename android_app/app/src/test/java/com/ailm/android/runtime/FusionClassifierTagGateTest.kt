package com.ailm.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionClassifierTagGateTest {
    @Test fun wdV3AcceptedByMapperIsNotDroppedBeforeFusionStorage() {
        val rows = mapOf<String, Any>(
            "source" to "native_classifier",
            "model_id" to "asterion-wd-vit-tagger-v3",
            "scored_tags" to listOf(
                mapOf("tag" to "HC001", "score" to 0.51),
                mapOf("tag" to "HS002", "score" to 0.40),
                mapOf("tag" to "HL003", "score" to 0.39),
            ),
        )
        val ids = FusionClassifierTagGate.acceptedScores(rows)
        assertEquals(setOf("HC001", "HS002"), ids.keys)
        assertEquals(0.51, ids["HC001"]!!, 0.0001)
    }

    @Test fun genericModelsStillNeedHighConfidenceAndValidCanonicalIDs() {
        val rows = mapOf<String, Any>(
            "source" to "native_classifier", "model_id" to "other-onnx-tagger",
            "scored_tags" to listOf(
                mapOf("tag" to "HC001", "score" to 0.75),
                mapOf("tag" to "EC002", "score" to 0.90),
                mapOf("tag" to "unknown hair", "score" to 0.99),
                mapOf("tag" to "PO004", "score" to Double.NaN),
                mapOf("tag" to "BT005", "score" to 3.0),
            ),
        )
        assertEquals(mapOf("EC002" to 0.9), FusionClassifierTagGate.acceptedScores(rows))
    }

    @Test fun promptedOrMalformedEvidenceCannotWriteCanonicalTags() {
        val scores = listOf(mapOf("tag" to "HC001", "score" to 0.99))
        assertTrue(FusionClassifierTagGate.acceptedScores(
            mapOf("source" to "qwen_unverified", "scored_tags" to scores)
        ).isEmpty())
        assertTrue(FusionClassifierTagGate.acceptedScores(
            mapOf("model_id" to "asterion-wd-vit-tagger-v3", "scored_tags" to scores)
        ).isEmpty())
    }
    @Test fun hierarchicalOutfitAndWeaponIdsAreValidFusionEvidence() {
        val rows = mapOf<String, Any>(
            "source" to "native_classifier",
            "model_id" to "asterion-wd-vit-tagger-v3",
            "scored_tags" to listOf(
                mapOf("tag" to "OF016-1-12", "score" to 0.91),
                mapOf("tag" to "WP001-3", "score" to 0.81),
                mapOf("tag" to "OF-unknown", "score" to 1.0),
            ),
        )
        assertEquals(setOf("OF016-1-12", "WP001-3"),
            FusionClassifierTagGate.acceptedScores(rows).keys)
    }


}
