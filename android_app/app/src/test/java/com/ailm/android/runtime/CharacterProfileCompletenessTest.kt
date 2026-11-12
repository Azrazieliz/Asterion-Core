package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class CharacterProfileCompletenessTest {
    private fun tag(
        id: String, name: String, parent: String = "",
        scope: String = "", rule: String = "",
    ) = FusionTaxonomyEngine.Candidate(
        id, name, id.take(2), parentId = parent,
        characterScope = scope, traitRequirement = rule,
    )

    private val taxonomy = listOf(
        tag("HC001", "Black", scope = "corporal"),
        tag("HL001", "Short", scope = "character"),
        tag("EC001", "Blue", scope = "both"),
        tag("SP001", "Human", scope = "character"),
        tag("SP002", "Elf", scope = "character"),
        tag("SA001", "Pointed Ears", "SP002", rule = "required"),
        tag("SA002", "Wings", "SP002", rule = "optional"),
        tag("PO001", "Standing", scope = "illustration"),
        tag("ZZ001", "Future custom character family", scope = "character"),
    )

    @Test fun everyApplicableCharacterFamilyIsTrackedNotJustHairEyes() {
        val report = CharacterProfileCompleteness.audit(
            setOf("HC001", "SP002"), taxonomy,
        )
        assertFalse(report.profileComplete)
        assertEquals(setOf("HL", "EC", "ZZ"), report.missingFamilies.toSet())
        assertEquals(listOf("SA001"), report.missingRequiredSpeciesTraits)
        assertFalse("PO" in report.missingFamilies) // Image tags are OPTIONAL.
        assertFalse("SA" in report.missingFamilies) // Conditional by species.
    }

    @Test fun completedCharacterCanHaveOptionalTraitsAbsent() {
        val report = CharacterProfileCompleteness.audit(
            setOf("HC001", "HL001", "EC001", "SP002", "SA001", "ZZ001"),
            taxonomy,
        )
        assertTrue(report.profileComplete)
        assertTrue(report.missingRequiredSpeciesTraits.isEmpty())
        assertFalse("SA002" in report.missingRequiredSpeciesTraits)
    }

    @Test fun explicitNotApplicableIsNotInventedOrAutomaticallyPromoted() {
        val report = CharacterProfileCompleteness.audit(
            setOf("HC001", "EC001", "SP001", "ZZ001"),
            taxonomy,
            explicitNotApplicable = setOf("HL", "PO"),
        )
        assertTrue(report.profileComplete)
        assertEquals(listOf("HL"), report.notApplicableFamilies)
        assertFalse("PO" in report.notApplicableFamilies)
    }

    @Test fun unknownCanonicalIdsAndUnrelatedSpeciesTraitsAreReviewOnly() {
        val result = CharacterProfileCompleteness.audit(
            setOf("HC001", "HL001", "EC001", "SP001", "SA001", "ZZ001", "HC999"),
            taxonomy,
        )
        assertEquals(listOf("HC999"), result.unknownIds)
        assertEquals(listOf("SA001"), result.unlinkedSpeciesTraits)
        assertFalse(result.profileComplete)
    }

    @Test fun missingSpeciesCannotMarkCharacterComplete() {
        val result = CharacterProfileCompleteness.audit(
            setOf("HC001", "HL001", "EC001", "ZZ001"), taxonomy,
        )
        assertEquals(listOf("SP"), result.missingFamilies)
        assertFalse(result.profileComplete)
    }
}
