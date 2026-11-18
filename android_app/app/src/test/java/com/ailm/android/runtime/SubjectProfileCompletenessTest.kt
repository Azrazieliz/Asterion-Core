package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class SubjectProfileCompletenessTest {
    private fun report(
        filled: List<String>,
        missing: List<String> = emptyList(),
        conflicts: List<String> = emptyList(),
    ) = CharacterProfileCompleteness.Report(
        filledFamilies = filled, missingFamilies = missing,
        conflictingFamilies = conflicts, notApplicableFamilies = emptyList(),
        missingRequiredSpeciesTraits = emptyList(),
        incompatibleSpeciesTraits = emptyList(),
        unlinkedSpeciesTraits = emptyList(), unknownIds = emptyList(),
        profileComplete = missing.isEmpty() && conflicts.isEmpty(),
    )

    @Test fun missingEyeOnSecondCharacterIsNotHiddenByFirstCharactersEyes() {
        val both = SubjectProfileCompleteness.combine(listOf(
            report(listOf("HC", "EC")),
            report(listOf("HC"), missing = listOf("EC")),
        ))
        assertFalse(both.profileComplete)
        assertEquals(listOf("HC"), both.filledFamilies)
        assertEquals(listOf("EC"), both.missingFamilies)
    }

    @Test fun differentCharactersEyeColorsAreNotAConflict() {
        val both = SubjectProfileCompleteness.combine(listOf(
            report(listOf("HC", "EC")),
            report(listOf("HC", "EC")),
        ))
        assertTrue(both.profileComplete)
        assertEquals(listOf("EC", "HC"), both.filledFamilies)
        assertTrue(both.conflictingFamilies.isEmpty())
    }

    @Test fun conflictOnOneSubjectCannotBeSilencedByOther() {
        val both = SubjectProfileCompleteness.combine(listOf(
            report(listOf("HC"), conflicts = listOf("ET")),
            report(listOf("HC", "ET")),
        ))
        assertFalse(both.profileComplete)
        assertEquals(listOf("ET"), both.conflictingFamilies)
    }
}
