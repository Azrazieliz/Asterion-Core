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

    @Test fun blankOrUncertainNeverOverwritesClassification() {
        assertNull(classify("unknown", "Image might be styled photo"))
        assertNull(classify("cosplay", "Pretty hair and makeup"))
        assertNull(classify("anime", "Cartoon style", confidence = 0.40))
        assertNull(classify("nonsense", "Clearly illustrated 2D artwork"))
        assertNull(VisualMediumPolicy.accept(emptyMap<String, Any>()))
    }
}
