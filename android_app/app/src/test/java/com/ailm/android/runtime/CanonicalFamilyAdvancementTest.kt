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

    @Test fun reportsSceneAndEverySubjectWhenTheFirstSubjectIsComplete() {
        fun row(family: String, status: String) = mapOf("family" to family, "status" to status)
        fun subject(vararg rows: Map<String, String>) = mapOf("family_coverage" to rows.toList())
        val result = CanonicalFamilyAdvancement.modelCoverage(
            listOf(row("EN", "missing_scores")),
            listOf(subject(row("ET", "accepted"), row("HC", "accepted")),
                subject(row("ET", "subject_overlap_review"), row("HC", "accepted"))),
        )
        assertEquals(mapOf("EN" to "missing_scores", "ET" to "mixed_subject_outcomes",
            "HC" to "accepted"), result)
    }

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
    @Test fun anotherSubjectsSavedTagsCannotAdvanceAMissingFamily() {
        val both = SubjectProfileCompleteness.combine(listOf(
            CharacterProfileCompleteness.audit(listOf("SX001", "SP001", "HC001"), tax),
            CharacterProfileCompleteness.audit(listOf("SX001", "SP001"), tax),
        ))
        val plan = CanonicalFamilyAdvancement.evaluate(tax,
            listOf(saved("SX001"), saved("SP001"), saved("HC001")), both, 1)
        assertFalse(plan.complete)
        assertEquals("HC", plan.firstBlockedFamily)
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
