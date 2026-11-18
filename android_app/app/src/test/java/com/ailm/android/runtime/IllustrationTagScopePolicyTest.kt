package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class IllustrationTagScopePolicyTest {
    private fun tag(id: String, scope: String = "") =
        FusionTaxonomyEngine.Candidate(id = id, name = id, category = id.take(2),
            characterScope = scope)

    @Test fun allSceneFamiliesSurviveWhenCharactersArePresent() {
        for (id in listOf("GE001", "EN001", "FR001", "CA001", "LI001", "OR001", "WE001")) {
            assertTrue(id, IllustrationTagScopePolicy.isImageLevel(tag(id), true))
        }
    }

    @Test fun personAttributesRemainScopedToTheirOwner() {
        for (id in listOf("HC001", "EC001", "ET001", "SP001", "SA001",
            "EY001", "MO001", "EX001", "PO001", "AC001", "OF001", "WP001")) {
            assertFalse(id, IllustrationTagScopePolicy.isImageLevel(tag(id), true))
        }
    }

    @Test fun isolatedObjectsAreNotDroppedWithoutDetectedSubjects() {
        assertTrue(IllustrationTagScopePolicy.isImageLevel(tag("WP001"), false))
        assertTrue(IllustrationTagScopePolicy.isImageLevel(tag("OF001"), false))
        assertFalse(IllustrationTagScopePolicy.isImageLevel(tag("EC001"), false))
    }

    @Test fun explicitCharacterScopeWinsOverFamilyDefaults() {
        assertFalse(IllustrationTagScopePolicy.isImageLevel(tag("GE001", "character"), true))
        assertFalse(IllustrationTagScopePolicy.isImageLevel(tag("GE001", "character"), false))
    }
}
