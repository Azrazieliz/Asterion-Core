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
    @Test fun partiallyTrainedVisualFamilyCannotBeCalledComplete() {
        val audit = CharacterProfileCompleteness.audit(
            listOf("SP001", "SX001", "HC001"), tax)
        val modelEvidence = listOf(saved("SP001"), saved("SX001"),
            CanonicalTagObservation(
                tagId = "HC001", tagName = "HC001", scope = "character_observed",
                confidence = .94, source = "vision"))
        val incomplete = CanonicalFamilyAdvancement.evaluate(
            tax, modelEvidence, audit, 1,
            incompletelyTrainedFamilies = setOf("HC"))
        assertFalse(incomplete.complete)
        assertEquals("HC", incomplete.firstBlockedFamily)
        assertEquals("required_model_candidate_gap",
            incomplete.steps.first { it.family == "HC" }.status)
        // Explicit owner-confirmed Fusion data has precedence over a
        // classifier that cannot distinguish every candidate in the family.
        val corrected = CanonicalFamilyAdvancement.evaluate(
            tax, modelEvidence.dropLast(1) + saved("HC001"), audit, 1,
            incompletelyTrainedFamilies = setOf("HC"))
        assertTrue(corrected.complete)
    }
}
