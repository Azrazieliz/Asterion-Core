package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class CanonicalFamilyAdvancementTest {
    private fun tag(id: String) = FusionTaxonomyEngine.Candidate(
        id = id, name = id, category = id.take(2), characterScope = "character")
    private val tax = listOf(tag("SP001"), tag("SX001"), tag("HC001"))
    private fun saved(id: String) = CanonicalTagObservation(
        tagId = id, tagName = id, scope = "character_observed",
        confidence = .99, source = "manual")

    @Test fun reportsFirstMissingMandatoryFamilyAndNeverClaimsComplete() {
        val audit = CharacterProfileCompleteness.audit(
            listOf("SP001","HC001"), tax)
        val result = CanonicalFamilyAdvancement.evaluate(
            tax, listOf(saved("SP001"),saved("HC001")), audit, 1)
        assertEquals("SX", result.firstBlockedFamily)
        assertFalse(result.complete)
        assertEquals("required_missing_evidence",
            result.steps.first { it.family == "SX" }.status)
    }
    @Test fun explicitContentRatingIsIndependentlyMandatory() {
        val audit = CharacterProfileCompleteness.audit(
            listOf("SP001","SX001","HC001"), tax)
        val result = CanonicalFamilyAdvancement.evaluate(
            tax, listOf(saved("SP001"),saved("SX001"),saved("HC001")), audit, 0)
        assertEquals("RT", result.firstBlockedFamily)
        assertFalse(result.complete)
    }
    @Test fun completedProfileCountsAllMandatoryFamilies() {
        val audit = CharacterProfileCompleteness.audit(
            listOf("SP001","SX001","HC001"), tax)
        val result = CanonicalFamilyAdvancement.evaluate(
            tax, listOf(saved("SP001"),saved("SX001"),saved("HC001")), audit, 1)
        assertTrue(result.complete)
        assertEquals(result.requiredTotal, result.requiredPassed)
    }
}
