package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class CanonicalObservationPolicyTest {
    private val catalog = mapOf(
        "SP001" to CanonicalObservationPolicy.Descriptor("SP001"),
        "SP002" to CanonicalObservationPolicy.Descriptor("SP002"),
        "SA001" to CanonicalObservationPolicy.Descriptor("SA001", "SP002",
            speciesRules = mapOf("SP001" to "forbidden")),
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

    @Test fun visuallyObservedTraitWinsOverConflictingUnverifiedSpecies() {
        assertEquals(setOf("SA001"), sanitize(tag("SP001"), tag("SA001")).keys)
        assertEquals(setOf("SP001"), CanonicalObservationPolicy.sanitize(
            mapOf("attributes" to listOf(tag("SP001"), tag("SA001"))),
            verifiedSpeciesIds = setOf("SP001"),
        ) { catalog[it] }.keys)
    }

    @Test fun visibleAndMatchingElfSpeciesAttributeIsAccepted() {
        assertEquals(setOf("SP002", "SA001"), sanitize(tag("SP002"), tag("SA001")).keys)
    }

    @Test fun hiddenOrUnsupportedSpeciesAttributeIsRejected() {
        assertEquals(setOf("SP002"), sanitize(tag("SP002"), tag("SA001", visible = false)).keys)
        assertEquals(setOf("SP002"), sanitize(tag("SP002"), tag("SA001", evidence = "")).keys)
        assertEquals(setOf("SA001"), sanitize(tag("SA001")).keys)
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
        assertEquals(setOf("SP003", "SA003", "SA004"), result.keys)
    }

    @Test fun speciesMustBeVisibleAndSupportedForAnatomicalTagging() {
        val result = sanitize(tag("SP002", visible = false), tag("SA001"))
        assertEquals(setOf("SA001"), result.keys)
        assertEquals(setOf("SA001"), sanitize(tag("SA001")).keys)
    }

    @Test fun manuallyEnabledSpeciesPermitsVisibleTraitWithoutFakeSpeciesObservation() {
        val raw = mapOf<String, Any>("attributes" to listOf(tag("SA001")))
        val permitted = CanonicalObservationPolicy.sanitize(
            raw, verifiedSpeciesIds = setOf("SP002"),
        ) { catalog[it] }
        assertEquals(setOf("SA001"), permitted.keys)
        assertEquals(0.95, permitted["SA001"] ?: 0.0, 0.00001)
        // The manual SP controls eligibility; it does not appear as an
        // invented classifier observation inside the current image.
        assertFalse("SP002" in permitted)
        assertTrue(CanonicalObservationPolicy.sanitize(
            raw, verifiedSpeciesIds = setOf("SP001"),
        ) { catalog[it] }.isEmpty())
        assertEquals(setOf("SA001"), CanonicalObservationPolicy.sanitize(
            raw, verifiedSpeciesIds = setOf("SP099"),
        ) { catalog[it] }.keys)
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
