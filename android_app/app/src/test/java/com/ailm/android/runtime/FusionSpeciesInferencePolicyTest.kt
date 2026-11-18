package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class FusionSpeciesInferencePolicyTest {
    private fun link(sp: String, sa: String, n: Int = 1) =
        FusionSpeciesInferencePolicy.Link(sp, sa, n)

    @Test fun pointedEarsAloneMustNotSelectElfOverSuccubus() {
        val result = FusionSpeciesInferencePolicy.decide(setOf("SA001"),
            listOf(link("SP002", "SA001", 7), link("SP013-1", "SA001", 2)))
        assertNull(result.acceptedSpeciesId)
        assertEquals(2, result.candidates.size)
    }

    @Test fun corroboratingCanonicalTraitsCanResolveSpecies() {
        val result = FusionSpeciesInferencePolicy.decide(setOf("SA001","SA002","SA004"),
            listOf(link("SP002","SA001",5),
                link("SP013-1","SA001",3),
                link("SP013-1","SA002",2),
                link("SP013-1","SA004",2),
                link("SP013","SA002",7)))
        assertEquals("SP013-1", result.acceptedSpeciesId)
    }

    @Test fun multipleFullySupportedSpeciesRequireReview() {
        val result = FusionSpeciesInferencePolicy.decide(setOf("SA001","SA002"),
            listOf(link("SP002","SA001",4),link("SP002","SA002",4),
                link("SP013","SA001",3),link("SP013","SA002",3)))
        assertNull(result.acceptedSpeciesId)
    }

    @Test fun invalidIdsNeverBecomeSpeciesCandidates() {
        val result = FusionSpeciesInferencePolicy.decide(setOf("SA001"),
            listOf(link("SPX999","SA001",200),link("SP001","ELF_EARS",100)))
        assertNull(result.acceptedSpeciesId)
        assertTrue(result.candidates.isEmpty())
    }
}
