package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class CharacterSubjectEvidencePolicyTest {
    @Test fun imageWideWardrobeCannotLeakBetweenTwoSubjects() {
        val wide = mapOf("WP012" to 0.98, "OF002" to 0.91, "HC003" to 0.99)
        assertTrue(CharacterSubjectEvidencePolicy.sharedWardrobeEvidence(2, wide).isEmpty())
        assertEquals(setOf("WP012", "OF002"),
            CharacterSubjectEvidencePolicy.sharedWardrobeEvidence(1, wide).keys)
    }

    @Test fun groupSubjectCannotUseAnUnscopedWholeImageEmbedding() {
        val whole = List(32) { 0.5 }
        assertTrue(CharacterSubjectEvidencePolicy.selectEmbedding(2, null, whole).isEmpty())
        assertEquals(whole, CharacterSubjectEvidencePolicy.selectEmbedding(1, null, whole))
        val own = List(32) { 0.75 }
        assertEquals(own, CharacterSubjectEvidencePolicy.selectEmbedding(2, own, whole))
    }

    @Test fun malformedEmbeddingsAreNeverTreatedAsValidatedEvidence() {
        val whole = List(32) { 0.2 }
        assertTrue(CharacterSubjectEvidencePolicy.selectEmbedding(1,
            listOf(Double.NaN) + List(31) { 0.2 }, emptyList()).isEmpty())
        assertTrue(CharacterSubjectEvidencePolicy.selectEmbedding(1, listOf(0.5), emptyList()).isEmpty())
        assertEquals(whole, CharacterSubjectEvidencePolicy.selectEmbedding(1,
            listOf(Double.NaN) + List(31) { 0.2 }, whole))
    }

    @Test fun manualMultiSubjectCorrectionsNeverContaminateCharacterReferenceLearning() {
        val wholeImage = List(32) { 0.45 }
        val policy = CharacterSubjectEvidencePolicy
        assertTrue(policy.verifiedSingleSubjectReferenceEmbedding(2, 2, wholeImage).isEmpty())
        assertTrue(policy.verifiedSingleSubjectReferenceEmbedding(1, 2, wholeImage).isEmpty())
        assertTrue(policy.verifiedSingleSubjectReferenceEmbedding(1, 0, wholeImage).isEmpty())
        assertTrue(policy.verifiedSingleSubjectReferenceEmbedding(0, 1, wholeImage).isEmpty())
        assertEquals(wholeImage,
            policy.verifiedSingleSubjectReferenceEmbedding(1, 1, wholeImage))
        assertTrue(policy.verifiedSingleSubjectReferenceEmbedding(
            1, 1, listOf(Double.NaN) + List(31) { 0.5 }).isEmpty())
        assertTrue(policy.verifiedSingleSubjectReferenceEmbedding(
            1, 1, listOf(0.5)).isEmpty())
    }

    @Test fun existingRosterWithoutSheetsYieldsSuggestionsNotFalseIdentity() {
        assertFalse(CharacterSubjectEvidencePolicy.canAcceptIdentity(
            0.99, 0.97, 0.2, false, 0.75))
        assertTrue(CharacterSubjectEvidencePolicy.canAcceptIdentity(
            0.92, 0.88, 0.55, true, 0.75))
        assertFalse(CharacterSubjectEvidencePolicy.canAcceptIdentity(
            0.92, null, 0.55, true, 0.75))
        assertFalse(CharacterSubjectEvidencePolicy.canAcceptIdentity(
            0.92, 0.88, 0.89, true, 0.75))
    }
}
