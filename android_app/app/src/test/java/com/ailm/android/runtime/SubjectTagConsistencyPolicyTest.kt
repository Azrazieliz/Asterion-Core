package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class SubjectTagConsistencyPolicyTest {
    private fun decision(scores: Map<String, Double>, names: Map<String, String>) =
        SubjectTagConsistencyPolicy.reconcile(scores, names, nativeSubjectCrop = true)

    @Test fun bodyTypesFromOneCropDoNotBothBecomeFacts() {
        val names = mapOf("BT005" to "Toned", "BT009" to "Muscular", "HC012" to "White")
        val ambiguous = decision(
            mapOf("BT005" to .64, "BT009" to .61, "HC012" to .81), names,
        )
        assertFalse("BT005" in ambiguous.accepted)
        assertFalse("BT009" in ambiguous.accepted)
        assertEquals(.81, ambiguous.accepted["HC012"] ?: .0, .0001)
        val clear = decision(mapOf("BT005" to .90, "BT009" to .55), names)
        assertEquals(setOf("BT005"), clear.accepted.keys)
    }

    @Test fun nudeHeadCannotLabelSkintightClothesWithoutStrongEvidence() {
        val names = mapOf("OF015-1" to "Nude", "OF018-5-1" to "Gloves",
            "OF020" to "Bodysuit")
        val weak = decision(mapOf("OF015-1" to .68, "OF018-5-1" to .71), names)
        assertFalse("OF015-1" in weak.accepted)
        assertTrue("OF018-5-1" in weak.accepted)
        val dressed = decision(mapOf("OF015-1" to .97, "OF020" to .75), names)
        assertFalse("OF015-1" in dressed.accepted)
        assertTrue("OF020" in dressed.accepted)
        val confident = decision(mapOf("OF015-1" to .96), names)
        assertEquals(setOf("OF015-1"), confident.accepted.keys)
    }

    @Test fun contradictoryBreastSizesRequireDecisiveSingleCropEvidence() {
        val names = mapOf("BS001" to "Small", "BS004" to "Large")
        val undecided = decision(mapOf("BS001" to .67, "BS004" to .63), names)
        assertTrue(undecided.accepted.isEmpty())
        val decided = decision(mapOf("BS001" to .83, "BS004" to .60), names)
        assertEquals(setOf("BS001"), decided.accepted.keys)
    }

    @Test fun onlyValidatedCropEvidenceMayUseTheseRules() {
        val scores = mapOf("BT005" to .52, "BT009" to .48)
        assertEquals(scores, SubjectTagConsistencyPolicy.reconcile(scores,
            mapOf("BT005" to "Toned", "BT009" to "Muscular"),
            nativeSubjectCrop = false).accepted)
    }
}
