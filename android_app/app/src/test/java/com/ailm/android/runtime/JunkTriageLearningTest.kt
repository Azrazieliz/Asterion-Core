package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class JunkTriageLearningTest {
    @Test fun calibratedPrecisionNeverClaimsUnreviewedKindsAreLearned() {
        assertFalse(JunkTriageLearning.Evidence(5, 0).learned)
        assertTrue(JunkTriageLearning.Evidence(6, 0).learned)
        assertFalse(JunkTriageLearning.Evidence(6, 1).learned)
        assertFalse(JunkTriageLearning.Evidence(0, 6).learned)
        assertTrue(JunkTriageLearning.Evidence(10, 1).learned)
    }
}
