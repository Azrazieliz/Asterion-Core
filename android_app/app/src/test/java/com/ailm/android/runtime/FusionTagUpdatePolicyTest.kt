package com.ailm.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionTagUpdatePolicyTest {
    private fun tag(id: String, source: String = "vision_classifier") =
        CanonicalTagObservation(id, id, "illustration", 0.89, source)

    @Test fun unsupportedAndUnevaluatedFamiliesKeepExistingMachineObservations() {
        val coverage = listOf(
            mapOf("family" to "HC", "status" to "accepted"),
            mapOf("family" to "SA", "status" to "deferred_review"),
            mapOf("family" to "HL", "status" to "evaluated_no_confident_match"),
            mapOf("family" to "EC", "status" to "unsupported_model"),
        )
        val result = FusionTagUpdatePolicy.replaceFamilies(
            listOf(tag("HC014"), tag("RT003", "nsfw_classifier")),
            coverage,
        )
        assertEquals(setOf("HC", "RT", "SA"), result)
        assertFalse("HL" in result)
        assertFalse("EC" in result)
    }

    @Test fun noNewEvidenceCannotWipeUnrelatedTaxonomy() {
        assertTrue(FusionTagUpdatePolicy.replaceFamilies(emptyList(), null).isEmpty())
        val manual = FusionTagUpdatePolicy.replaceFamilies(listOf(tag("HS003", "manual")), null)
        assertTrue(manual.isEmpty())
    }

    @Test fun unknownOrMalformedCoverageCannotRequestDeletion() {
        val result = FusionTagUpdatePolicy.replaceFamilies(
            emptyList(),
            listOf(
                mapOf("family" to "DROP TABLE", "status" to "accepted"),
                mapOf("family" to "HC", "status" to "unsupported_model"),
                mapOf("family" to "ET", "status" to "deferred_review"),
                mapOf("family" to "1X", "status" to "accepted"),
            ),
        )
        assertEquals(setOf("ET"), result)
    }
}
