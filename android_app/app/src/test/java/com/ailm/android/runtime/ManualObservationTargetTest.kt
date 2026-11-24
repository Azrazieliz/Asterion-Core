package com.ailm.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualObservationTargetTest {
    @Test fun editsAndTrainingUseTheSameResolvedRegion() {
        val hair = FusionTaxonomyEngine.Candidate("HC001", "Black", "HC")
        val both = FusionTaxonomyEngine.Candidate("ZZ001", "Fixture", "ZZ", characterScope = "both")
        val scene = FusionTaxonomyEngine.Candidate("GE101", "Fixture", "GE", characterScope = "image")
        assertEquals(5, ManualObservationTarget.resolve(hair, null, listOf(5))?.subjectIndex)
        assertNull(ManualObservationTarget.resolve(hair, null, listOf(0, 5)))
        assertNull(ManualObservationTarget.resolve(hair, 3, listOf(0, 5)))
        assertEquals("illustration", ManualObservationTarget.resolve(both, null, listOf(5))?.scope)
        assertEquals(5, ManualObservationTarget.resolve(both, 5, listOf(0, 5))?.subjectIndex)
        assertEquals("illustration", ManualObservationTarget.resolve(scene, 5, listOf(5))?.scope)
        assertEquals(ManualObservationTarget("character_observed", null),
            ManualObservationTarget.resolve(hair, null, emptyList()))
    }
}
