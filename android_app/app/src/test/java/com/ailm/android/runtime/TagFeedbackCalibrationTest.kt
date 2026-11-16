package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class TagFeedbackCalibrationTest {
    @Test fun calibrationLearnsOnlyFromRepeatedCorrections() {
        assertEquals(0.0, TagFeedbackCalibration.delta(2, 0), 0.00001)
        assertTrue(TagFeedbackCalibration.delta(8, 0) > 0)
        assertTrue(TagFeedbackCalibration.delta(0, 8) < 0)
        assertEquals(0.0, TagFeedbackCalibration.delta(9, 9), 0.00001)
    }

    @Test fun correctionsCannotFabricateConfidenceOrOverpowerTheImage() {
        assertTrue(TagFeedbackCalibration.delta(10000, 0) <= 0.12)
        assertTrue(TagFeedbackCalibration.delta(0, 10000) >= -0.12)
        assertTrue(0.35 + TagFeedbackCalibration.delta(8, 0) > 0.40)
        assertTrue(0.2 + TagFeedbackCalibration.delta(10000, 0) < 0.40)
    }
}
