package com.ailm.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class FusionImageWideEvidencePolicyTest {
    private val catalog = listOf(
        FusionTaxonomyEngine.Candidate("HC014", "Gray", "Hair Color"),
        FusionTaxonomyEngine.Candidate("HC030", "Blue", "Hair Color"),
        FusionTaxonomyEngine.Candidate("HL005", "Long", "Hair Length"),
        FusionTaxonomyEngine.Candidate("HL006", "Very Long", "Hair Length"),
        FusionTaxonomyEngine.Candidate("SA013", "Halo", "Species Attributes"),
        FusionTaxonomyEngine.Candidate("PO001", "Standing", "Pose"),
        FusionTaxonomyEngine.Candidate("PO002", "Sitting", "Pose"),
        FusionTaxonomyEngine.Candidate("MO006", "Open", "Mouth State"),
        FusionTaxonomyEngine.Candidate("MO010", "Tongue Out", "Mouth State"),
    ).associateBy { it.id }

    private fun gate(vararg scores: Pair<String, Double>, modelId: String = "asterion-wd-vit-tagger-v3") =
        FusionImageWideEvidencePolicy.reconcile(mapOf(*scores), catalog, modelId)

    @Test fun aHaloLabelDoesNotEstablishCharacterSpeciesAnatomyWithoutASubject() {
        val result = gate("SA013" to 0.96, "HC014" to 0.81)
        assertEquals(mapOf("HC014" to 0.81), result.accepted)
        assertEquals("species_not_enabled_for_attribute",
            result.deferred.single().reason)
        assertEquals("SA013", result.deferred.single().id)
    }

    @Test fun multipleHairLengthsAreNeverAssertedAsOneSubjectWithoutEvidence() {
        val result = gate("HL005" to 0.84, "HL006" to 0.81)
        assertTrue(result.accepted.isEmpty())
        assertEquals(setOf("HL005", "HL006"), result.deferred.map { it.id }.toSet())
        val separated = gate("HL005" to 0.51, "HL006" to 0.92)
        assertEquals(mapOf("HL006" to 0.92), separated.accepted)
        assertEquals(listOf("HL005"), separated.deferred.map { it.id })
    }

    @Test fun multipleVisibleHairColoursDoNotBecomeSinglePersonIdentity() {
        val result = gate("HC014" to 0.75, "HC030" to 0.77)
        assertEquals(setOf("HC014", "HC030"), result.accepted.keys)
        assertTrue(result.deferred.isEmpty())
    }

    @Test fun lowEvidenceAndContradictoryPoseLabelsGoToReview() {
        val weak = gate("PO001" to 0.41)
        assertTrue(weak.accepted.isEmpty())
        assertEquals("weak_pose_or_framing_evidence", weak.deferred.single().reason)
        val incompatible = gate("PO001" to 0.95, "PO002" to 0.89)
        assertTrue(incompatible.accepted.isEmpty())
        assertEquals(2, incompatible.deferred.size)
        assertTrue(incompatible.deferred.all { it.reason.startsWith("incompatible_image_wide") })
    }

    @Test fun coexistingMouthStatesAreNotArbitrarilySuppressed() {
        val result = gate("MO006" to 0.8, "MO010" to 0.83)
        assertEquals(setOf("MO006", "MO010"), result.accepted.keys)
        assertFalse(result.accepted.isEmpty())
    }

    @Test fun genericClassifierDoesNotInheritWdSpecificThresholdPolicy() {
        val result = gate("PO001" to 0.71, modelId = "other-onnx-tagger")
        assertEquals(mapOf("PO001" to 0.71), result.accepted)
    }
}
