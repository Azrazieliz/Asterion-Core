package com.ailm.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentTriagePolicyTest {
    @Test
    fun `promotion takes priority and routes to trash`() {
        val decision = ContentTriagePolicy.decide(
            observation = ContentRoutingObservation(
                contentClass = "other",
                peopleCount = 0,
                ocrText = "Unlock the set for UNCENSORED photos! Full set available",
            ),
            resolvedCharacterCount = 0,
            characterKnowledgeReady = true,
        )

        assertEquals(ContentTriagePolicy.ROUTE_PROMOTION_TRASH, decision.route)
        assertEquals(listOf("Trash", "Promotion"), decision.folderSegments)
        assertTrue(decision.terminal)
        assertFalse(decision.requiresReview)
    }

    @Test
    fun `scenery without people is preserved in scenery`() {
        val decision = ContentTriagePolicy.decide(
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

        assertEquals(ContentTriagePolicy.ROUTE_SCENERY, decision.route)
        assertEquals(listOf("Scenery"), decision.folderSegments)
        assertTrue(decision.terminal)
    }

    @Test
    fun `environment dominant art with anonymous luminous figure remains scenery`() {
        val decision = ContentTriagePolicy.decide(
            observation = ContentRoutingObservation(
                contentClass = "scenery",
                peopleCount = 1,
                identifiableCharacterCount = 0,
                sceneryOrEnvironment = true,
                environmentDominant = true,
            ),
            resolvedCharacterCount = 0,
            characterKnowledgeReady = true,
        )

        assertEquals(ContentTriagePolicy.ROUTE_SCENERY, decision.route)
        assertEquals(listOf("Scenery"), decision.folderSegments)
        assertFalse(decision.requiresReview)
    }

    @Test
    fun `generic no character content routes to trash`() {
        val decision = ContentTriagePolicy.decide(
            observation = ContentRoutingObservation(
                contentClass = "non_character",
                peopleCount = 0,
                identifiableCharacterCount = 0,
            ),
            resolvedCharacterCount = 0,
            characterKnowledgeReady = true,
        )

        assertEquals(ContentTriagePolicy.ROUTE_NO_CHARACTER_TRASH, decision.route)
        assertEquals(listOf("Trash", "No Character"), decision.folderSegments)
        assertTrue(decision.requiresReview)
    }

    @Test
    fun `unidentified multi character image is separated from review`() {
        val decision = ContentTriagePolicy.decide(
            observation = ContentRoutingObservation(
                contentClass = "multi_character",
                peopleCount = 4,
                identifiableCharacterCount = 4,
            ),
            resolvedCharacterCount = 0,
            characterKnowledgeReady = true,
        )

        assertEquals(ContentTriagePolicy.ROUTE_UNIDENTIFIED_GROUP, decision.route)
        assertEquals(listOf("Unidentified Groups"), decision.folderSegments)
        assertTrue(decision.terminal)
        assertFalse(decision.requiresReview)
    }

    @Test
    fun `single unresolved character remains reviewable when knowledge exists`() {
        val decision = ContentTriagePolicy.decide(
            observation = ContentRoutingObservation(
                contentClass = "character",
                peopleCount = 1,
                identifiableCharacterCount = 1,
            ),
            resolvedCharacterCount = 0,
            characterKnowledgeReady = true,
        )

        assertEquals(ContentTriagePolicy.ROUTE_SINGLE_UNIDENTIFIED, decision.route)
        assertTrue(decision.requiresReview)
        assertFalse(decision.terminal)
    }

    @Test
    fun `single unresolved character waits when knowledge is absent`() {
        val decision = ContentTriagePolicy.decide(
            observation = ContentRoutingObservation(
                contentClass = "character",
                peopleCount = 1,
                identifiableCharacterCount = 1,
            ),
            resolvedCharacterCount = 0,
            characterKnowledgeReady = false,
        )

        assertEquals(ContentTriagePolicy.ROUTE_WAITING_FOR_KNOWLEDGE, decision.route)
        assertFalse(decision.requiresReview)
        assertFalse(decision.terminal)
    }

    @Test
    fun `resolved character stays in canonical character flow`() {
        val decision = ContentTriagePolicy.decide(
            observation = ContentRoutingObservation(
                contentClass = "character",
                peopleCount = 1,
                identifiableCharacterCount = 1,
            ),
            resolvedCharacterCount = 1,
            characterKnowledgeReady = true,
        )

        assertEquals(ContentTriagePolicy.ROUTE_CHARACTER, decision.route)
        assertFalse(decision.terminal)
        assertFalse(decision.requiresReview)
    }
    @Test
    fun `obvious junk routes away from review`() {
        val decision = ContentTriagePolicy.decide(
            observation = ContentRoutingObservation(
                contentClass = "blank",
                peopleCount = 0,
                identifiableCharacterCount = 0,
                qualityFlags = listOf("solid_color_placeholder"),
            ),
            resolvedCharacterCount = 0,
            characterKnowledgeReady = true,
        )

        assertEquals(ContentTriagePolicy.ROUTE_JUNK_TRASH, decision.route)
        assertEquals(listOf("Trash", "Junk"), decision.folderSegments)
        assertTrue(decision.requiresReview)
        assertTrue(decision.terminal)
    }

    @Test
    fun `environment dominant composition stays scenery even if identity metadata resolves`() {
        val decision = ContentTriagePolicy.decide(
            observation = ContentRoutingObservation(
                contentClass = "scenery",
                peopleCount = 1,
                identifiableCharacterCount = 1,
                sceneryOrEnvironment = true,
                environmentDominant = true,
            ),
            resolvedCharacterCount = 1,
            characterKnowledgeReady = true,
        )

        assertEquals(ContentTriagePolicy.ROUTE_SCENERY, decision.route)
        assertEquals(listOf("Scenery"), decision.folderSegments)
        assertTrue(decision.terminal)
    }

    @Test
    fun `watermarked cosplay is not a promotional preview`() {
        val decision = ContentTriagePolicy.decide(
            observation = ContentRoutingObservation(
                contentClass = "promotion",
                peopleCount = 1,
                identifiableCharacterCount = 1,
                promotionOrPreview = true,
                ocrText = "onlyfans.com/cosplayer",
                verifiedNonblankImage = true,
            ),
            resolvedCharacterCount = 0,
            characterKnowledgeReady = false,
        )
        assertEquals(ContentTriagePolicy.ROUTE_WAITING_FOR_KNOWLEDGE, decision.route)
        assertFalse(decision.terminal)
    }

    @Test
    fun `witch cosplay photograph never becomes broken placeholder`() {
        val decision = ContentTriagePolicy.decide(
            observation = ContentRoutingObservation(
                contentClass = "broken_image",
                peopleCount = 0,
                identifiableCharacterCount = 0,
                qualityFlags = listOf("thumbnail_placeholder"),
                verifiedNonblankImage = true,
            ),
            resolvedCharacterCount = 0,
            characterKnowledgeReady = false,
        )
        assertEquals(ContentTriagePolicy.ROUTE_WAITING_FOR_KNOWLEDGE, decision.route)
        assertFalse(decision.terminal)
        assertFalse(decision.requiresReview)
    }

    @Test
    fun `nonblank photograph with hallucinated zero people is not junk`() {
        val decision = ContentTriagePolicy.decide(
            observation = ContentRoutingObservation(
                contentClass = "character",
                peopleCount = 0,
                identifiableCharacterCount = 0,
                verifiedNonblankImage = true,
            ),
            resolvedCharacterCount = 0,
            characterKnowledgeReady = false,
        )
        assertEquals(ContentTriagePolicy.ROUTE_WAITING_FOR_KNOWLEDGE, decision.route)
        assertTrue(decision.requiresReview)
    }

    @Test
    fun `local censor and weak blur are not trash triggers`() {
        val decision = ContentTriagePolicy.decide(
            observation = ContentRoutingObservation(
                contentClass = "character",
                peopleCount = 1,
                identifiableCharacterCount = 1,
                qualityFlags = listOf("localized_censor", "low_quality"),
                verifiedNonblankImage = true,
            ),
            resolvedCharacterCount = 0,
            characterKnowledgeReady = false,
        )
        assertEquals(ContentTriagePolicy.ROUTE_WAITING_FOR_KNOWLEDGE, decision.route)
    }

    @Test
    fun `positive paid preview requires access and offering OCR`() {
        assertTrue(ContentTriagePolicy.unequivocalPaidPreviewOcr("Unlock the set! 34 NSFW photos!"))
        assertFalse(ContentTriagePolicy.unequivocalPaidPreviewOcr("onlyfans.com/creator"))
        assertFalse(ContentTriagePolicy.unequivocalPaidPreviewOcr("preview only"))
    }

}
