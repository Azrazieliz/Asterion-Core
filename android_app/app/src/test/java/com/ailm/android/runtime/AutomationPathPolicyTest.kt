package com.ailm.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AutomationPathPolicyTest {
    @Test
    fun `single character lives in its character subfolder`() {
        val plan = AutomationPathPolicy.plan(
            listOf(
                ResolvedCharacterPathInput(
                    subjectIndex = 0,
                    prominence = 1.0,
                    canonicalName = "Rin Tohsaka",
                    seriesCode = "SE0001",
                    seriesName = "Fate",
                ),
            ),
        )

        assertNotNull(plan)
        assertEquals(listOf("Fate", "Rin Tohsaka"), plan!!.folderSegments)
        assertEquals("Rin Tohsaka - Fate", plan.filenamePrefix)
    }

    @Test
    fun `intraseries multi character image stays in series folder`() {
        val plan = AutomationPathPolicy.plan(
            listOf(
                ResolvedCharacterPathInput(0, 0.8, "Artoria", "SE0001", "Fate"),
                ResolvedCharacterPathInput(1, 0.7, "Rin Tohsaka", "SE0001", "Fate"),
            ),
        )

        assertNotNull(plan)
        assertEquals(listOf("Fate"), plan!!.folderSegments)
        assertEquals("Artoria - Rin Tohsaka - Fate", plan.filenamePrefix)
    }

    @Test
    fun `interseries image is owned by most prominent character but keeps all names`() {
        val plan = AutomationPathPolicy.plan(
            listOf(
                ResolvedCharacterPathInput(0, 0.35, "Character A", "SE0001", "Series A"),
                ResolvedCharacterPathInput(1, 0.92, "Character B", "SE0002", "Series B"),
            ),
        )

        assertNotNull(plan)
        assertEquals(listOf("Series B", "Character B"), plan!!.folderSegments)
        assertEquals("Character A - Character B - Series B", plan.filenamePrefix)
        assertEquals("Character B", plan.primaryCharacter)
        assertEquals("Series B", plan.primarySeries)
    }

    @Test
    fun `original character path stays outside canonical character knowledge`() {
        val plan = AutomationPathPolicy.plan(
            characters = emptyList(),
            originalCharacter = true,
            originalCharacterClusterId = "OC000123",
        )

        assertNotNull(plan)
        assertEquals(listOf("Original Characters"), plan!!.folderSegments)
        assertEquals("Original Character", plan.filenamePrefix)
        assertEquals("OC000123", plan.primaryCharacter)
    }

    @Test
    fun `automation triage preserves scenery and separates unknown groups`() {
        val scenery = ContentTriagePolicy.decide(
            observation = ContentRoutingObservation(
                contentClass = "scenery",
                peopleCount = 0,
                identifiableCharacterCount = 0,
                sceneryOrEnvironment = true,
                environmentDominant = true,
            ),
            resolvedCharacterCount = 0,
            characterKnowledgeReady = true,
        )
        assertEquals(ContentTriagePolicy.ROUTE_SCENERY, scenery.route)
        assertEquals(listOf("Scenery"), scenery.folderSegments)

        val group = ContentTriagePolicy.decide(
            observation = ContentRoutingObservation(
                contentClass = "multi_character",
                peopleCount = 3,
                identifiableCharacterCount = 3,
            ),
            resolvedCharacterCount = 0,
            characterKnowledgeReady = true,
        )
        assertEquals(ContentTriagePolicy.ROUTE_UNIDENTIFIED_GROUP, group.route)
        assertEquals(listOf("Unidentified Groups"), group.folderSegments)
    }

    @Test
    fun `automation triage sends promotions and generic no character content to trash`() {
        val promotion = ContentTriagePolicy.decide(
            observation = ContentRoutingObservation(
                peopleCount = 0,
                ocrText = "Unlock the set! 34 NSFW photos! Full uncensored set",
            ),
            resolvedCharacterCount = 0,
            characterKnowledgeReady = true,
        )
        assertEquals(ContentTriagePolicy.ROUTE_PROMOTION_TRASH, promotion.route)
        assertEquals(listOf("Trash", "Promotion"), promotion.folderSegments)

        val generic = ContentTriagePolicy.decide(
            observation = ContentRoutingObservation(
                contentClass = "non_character",
                peopleCount = 0,
                identifiableCharacterCount = 0,
            ),
            resolvedCharacterCount = 0,
            characterKnowledgeReady = true,
        )
        assertEquals(ContentTriagePolicy.ROUTE_NO_CHARACTER_TRASH, generic.route)
        assertEquals(listOf("Trash", "No Character"), generic.folderSegments)
    }


    @Test
    fun `resolved identity overrides a mistaken zero people routing count`() {
        val decision = ContentTriagePolicy.decide(
            observation = ContentRoutingObservation(
                contentClass = "other",
                peopleCount = 0,
            ),
            resolvedCharacterCount = 1,
            characterKnowledgeReady = true,
        )
        assertEquals(ContentTriagePolicy.ROUTE_CHARACTER, decision.route)
    }

}
