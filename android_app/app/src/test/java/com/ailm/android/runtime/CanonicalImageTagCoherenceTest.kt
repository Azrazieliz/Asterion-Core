package com.ailm.android.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalImageTagCoherenceTest {
    @Test fun contradictionsAreRejected() {
        assertFalse(CanonicalImageTagCoherence.internallyConsistent(
            "PO", listOf("Standing", "Sitting")))
        assertFalse(CanonicalImageTagCoherence.internallyConsistent(
            "FR", listOf("Extreme Close-Up", "Knee Shot")))
    }

    @Test fun legitimatelyMultipleAttributesRemainAllowed() {
        assertTrue(CanonicalImageTagCoherence.internallyConsistent(
            "HC", listOf("White", "Pink")))
        assertTrue(CanonicalImageTagCoherence.internallyConsistent(
            "OF", listOf("Dress", "Gloves", "Boots")))
        assertTrue(CanonicalImageTagCoherence.internallyConsistent(
            "PO", listOf("Sitting", "Leaning")))
    }
}
