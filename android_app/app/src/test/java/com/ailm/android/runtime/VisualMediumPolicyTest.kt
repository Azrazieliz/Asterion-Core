package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class VisualMediumPolicyTest {
    private fun classify(kind: String, evidence: String, confidence: Double = 0.93) =
        VisualMediumPolicy.accept(mapOf("kind" to kind, "confidence" to confidence, "evidence" to evidence))

    @Test fun animeIllustrationKeepsAnimeMedium() {
        assertEquals("anime", classify("anime", "Clearly illustrated 2D anime cel-shaded art")?.medium)
    }

    @Test fun cosplayPhotoIsNotDowngradedToRealistic() {
        assertEquals("cosplay", classify("cosplay", "Photograph of real person wearing a character costume and wig")?.medium)
        assertNull(classify("realistic", "Photograph of a person wearing a character costume and wig"))
    }

    @Test fun realisticRenderedOrNonCosplayPhoto() {
        assertEquals("realistic", classify("realistic", "Photorealistic rendering with natural skin textures")?.medium)
        assertEquals("realistic", classify("realistic", "Photograph of a non-costumed person")?.medium)
    }

    @Test fun fixedHeadAnimeMediumRequiresActualTrainedVisualLabel() {
        val result = VisualMediumPolicy.fromNativeScores(
            listOf("anime coloring" to 0.94, "photo (medium)" to 0.11),
            listOf("anime coloring", "photo (medium)", "cosplay"),
            "asterion-wd-vit-tagger-v3",
        )
        assertEquals("anime", result?.medium)
        assertEquals(0.94, result!!.confidence, 0.0001)
        assertTrue(result.evidence.contains("anime coloring"))
    }

    @Test fun photographAndCosplayRequireIndependentVisualEvidence() {
        val vocab = listOf("photo (medium)", "cosplay", "anime screenshot")
        val photo = VisualMediumPolicy.fromNativeScores(
            listOf("photo (medium)" to 0.93), vocab, "asterion-wd-vit-tagger-v3")
        assertEquals("realistic", photo?.medium)
        val cosplay = VisualMediumPolicy.fromNativeScores(
            listOf("photo (medium)" to 0.94, "cosplay" to 0.85),
            vocab, "asterion-wd-vit-tagger-v3")
        assertEquals("cosplay", cosplay?.medium)
        assertNull(VisualMediumPolicy.fromNativeScores(
            listOf("cosplay" to 0.99), vocab, "asterion-wd-vit-tagger-v3"))
    }

    @Test fun uncertainMixedOrUntrainedVisualMediumAbstains() {
        val vocab = listOf("anime coloring", "photo (medium)")
        assertNull(VisualMediumPolicy.fromNativeScores(
            listOf("anime coloring" to 0.81, "photo (medium)" to 0.80),
            vocab, "asterion-wd-vit-tagger-v3"))
        assertNull(VisualMediumPolicy.fromNativeScores(
            listOf("anime coloring" to 0.99), listOf("photo (medium)"),
            "asterion-wd-vit-tagger-v3"))
        assertNull(VisualMediumPolicy.fromNativeScores(
            listOf("anime coloring" to 0.99), vocab, "untrusted-text-generator"))
    }

    @Test fun blankOrUncertainNeverOverwritesClassification() {
        assertNull(classify("unknown", "Image might be styled photo"))
        assertNull(classify("cosplay", "Pretty hair and makeup"))
        assertNull(classify("anime", "Cartoon style", confidence = 0.40))
        assertNull(classify("nonsense", "Clearly illustrated 2D artwork"))
        assertNull(VisualMediumPolicy.accept(emptyMap<String, Any>()))
    }
}
