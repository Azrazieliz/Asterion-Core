package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class CanonicalObservationPolicyTest {
    private val catalog = mapOf(
        "SP001" to CanonicalObservationPolicy.Descriptor("SP001"),
        "SP002" to CanonicalObservationPolicy.Descriptor("SP002"),
        "SA001" to CanonicalObservationPolicy.Descriptor("SA001", "SP002"),
        "HC001" to CanonicalObservationPolicy.Descriptor("HC001"),
        "EC001" to CanonicalObservationPolicy.Descriptor("EC001"),
        "EY001" to CanonicalObservationPolicy.Descriptor("EY001"),
    )
    private fun tag(id: String, confidence: Double = 0.95, visible: Boolean = true, evidence: String = "visibly present") =
        mapOf<String, Any>(
            "id" to id, "confidence" to confidence,
            "visible" to visible, "evidence" to evidence,
        )
    private fun sanitize(vararg rows: Map<String, Any>): Map<String, Double> =
        CanonicalObservationPolicy.sanitize(mapOf("attributes" to rows.toList())) { catalog[it] }

    @Test fun humanCannotAcquireElfSpeciesAttribute() {
        assertEquals(setOf("SP001"), sanitize(tag("SP001"), tag("SA001")).keys)
    }

    @Test fun visibleAndMatchingElfSpeciesAttributeIsAccepted() {
        assertEquals(setOf("SP002", "SA001"), sanitize(tag("SP002"), tag("SA001")).keys)
    }

    @Test fun hiddenOrUnsupportedSpeciesAttributeIsRejected() {
        assertEquals(setOf("SP002"), sanitize(tag("SP002"), tag("SA001", visible = false)).keys)
        assertEquals(setOf("SP002"), sanitize(tag("SP002"), tag("SA001", evidence = "")).keys)
        assertTrue(sanitize(tag("SA001")).isEmpty())
    }

    @Test fun elfEarsRemainAvailableWhenElfSpeciesIsExplicitlyObserved() {
        val refs = catalog + mapOf(
            "SP003" to CanonicalObservationPolicy.Descriptor("SP003", name = "Elf"),
            "SA003" to CanonicalObservationPolicy.Descriptor(
                "SA003", "SP003", "Pointed Ears", "required"),
            "SA004" to CanonicalObservationPolicy.Descriptor(
                "SA004", "SP001", "Wings", "optional"),
        )
        val result = CanonicalObservationPolicy.sanitize(mapOf("attributes" to listOf(
            tag("SP003"), tag("SA003"), tag("SA004"),
        ))) { refs[it] }
        assertEquals(setOf("SP003", "SA003"), result.keys)
    }

    @Test fun speciesMustBeVisibleAndSupportedForAnatomicalTagging() {
        val result = sanitize(tag("SP002", visible = false), tag("SA001"))
        assertTrue(result.isEmpty() || result.keys == setOf("SP002"))
        assertTrue(sanitize(tag("SA001")).isEmpty())
    }

    @Test fun ambiguousAndInvisibleColoursCannotBecomeCanonicalFacts() {
        assertEquals(setOf("HC001", "EC001"), sanitize(tag("HC001"), tag("EC001")).keys)
        assertEquals(setOf("EC001"), sanitize(tag("HC001", visible = false), tag("EC001")).keys)
        assertTrue(sanitize(tag("Black")).isEmpty())
    }

    @Test fun tagsWorkBeforeCharacterRosterAndSheetsAreImported() {
        assertEquals(setOf("EY001", "SP001"), sanitize(tag("EY001"), tag("SP001")).keys)
    }
}
