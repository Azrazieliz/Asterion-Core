package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class SubjectRegionAssociationTest {
    private fun box(left: Int, width: Int) = AnimePersonBoxDecoder.Region(left, 0, left + width, 100, 0.95)
    private val prior = mapOf(0 to box(0, 70), 1 to box(180, 75))

    @Test fun prominenceOrderChangesAndNewPeopleKeepIndependentIds() {
        assertEquals(listOf(1, 2, 0), SubjectRegionAssociation.indices(
            listOf(box(179, 78), box(320, 60), box(1, 72)), prior, prior.keys))
    }
    @Test fun missingAmbiguousOrUnanchoredSubjectsDeferReplacement() {
        assertNull(SubjectRegionAssociation.indices(listOf(box(1, 72)), prior, prior.keys))
        assertNull(SubjectRegionAssociation.indices(listOf(box(1, 72), box(2, 70), box(180, 75)), prior, prior.keys))
        assertNull(SubjectRegionAssociation.indices(emptyList(), emptyMap(), setOf(0)))
    }
}
