package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class PrimaryFocusRecoveryPolicyTest {
    @Test fun onlyMissingOrWeakFaceFamiliesTriggerWithinBudget() {
        val verified = mapOf("HC001" to 0.91, "HS001" to 0.84,
            "EC001" to 0.87, "ET001" to 0.90)
        assertFalse(PrimaryFocusRecoveryPolicy.shouldAttempt(
            verified, false, 4500, 450, 650))
        assertTrue(PrimaryFocusRecoveryPolicy.shouldAttempt(
            mapOf("HC001" to 0.55), false, 4000, 450, 650))
        assertFalse(PrimaryFocusRecoveryPolicy.shouldAttempt(
            emptyMap(), true, 4000, 450, 650))
        assertFalse(PrimaryFocusRecoveryPolicy.shouldAttempt(
            emptyMap(), false, 2000, 450, 650))
        assertFalse(PrimaryFocusRecoveryPolicy.shouldAttempt(
            emptyMap(), false, 5000, 35, 40))
    }
    @Test fun focusedEvidenceMustBeClearlyStrongerAndFamilyScoped() {
        assertTrue(PrimaryFocusRecoveryPolicy.acceptFocusedEvidence("EC", .44, .90))
        assertFalse(PrimaryFocusRecoveryPolicy.acceptFocusedEvidence("EC", .75, .86))
        assertFalse(PrimaryFocusRecoveryPolicy.acceptFocusedEvidence("BS", .2, .98))
        assertFalse(PrimaryFocusRecoveryPolicy.acceptFocusedEvidence("HC", .3, Double.NaN))
        assertFalse(PrimaryFocusRecoveryPolicy.acceptFocusedEvidence("HC", .4, .77))
    }
}
