package com.ailm.android.runtime

internal data class ContentRoutingObservation(
    val contentClass: String = "other",
    val peopleCount: Int = 0,
    val identifiableCharacterCount: Int = -1,
    val promotionOrPreview: Boolean = false,
    val sceneryOrEnvironment: Boolean = false,
    val environmentDominant: Boolean = false,
    val qualityFlags: List<String> = emptyList(),
    val ocrText: String = "",
    // Device evidence from the decoded original, not a model prediction.
    val verifiedNonblankImage: Boolean = false,
)

internal data class ContentTriageDecision(
    val route: String,
    val folderSegments: List<String> = emptyList(),
    val filenamePrefix: String = "",
    val terminal: Boolean = false,
    val requiresReview: Boolean = false,
    val reason: String = "",
)

internal object ContentTriagePolicy {
    const val ROUTE_CHARACTER = "character"
    const val ROUTE_SCENERY = "scenery"
    const val ROUTE_PROMOTION_TRASH = "promotion_trash"
    const val ROUTE_JUNK_TRASH = "junk_trash"
    const val ROUTE_NO_CHARACTER_TRASH = "no_character_trash"
    const val ROUTE_UNIDENTIFIED_GROUP = "unidentified_group"
    const val ROUTE_SINGLE_UNIDENTIFIED = "single_unidentified"
    const val ROUTE_WAITING_FOR_KNOWLEDGE = "waiting_for_knowledge"
    const val ROUTE_CORRUPT_TRASH = "corrupt_trash"

    fun decide(
        observation: ContentRoutingObservation,
        resolvedCharacterCount: Int,
        characterKnowledgeReady: Boolean,
    ): ContentTriageDecision {
        val contentClass = observation.contentClass.trim().lowercase()
        val flags = observation.qualityFlags.map { it.trim().lowercase() }.toSet()
        val visiblePeople = observation.peopleCount.coerceAtLeast(0)
        val identifiableCharacters = observation.identifiableCharacterCount
            .takeIf { it >= 0 }
            ?.coerceAtLeast(0)
            ?: visiblePeople

        // Model-only marketing labels are frequently hallucinated from a
        // watermark such as "onlyfans" on an otherwise ordinary cosplay photo.
        // Require explicit promotional *content* or offer language, never a
        // creator domain, watermark or standalone word "preview".
        val promotion = unequivocalPaidPreviewOcr(observation.ocrText) ||
            strongPromotionText(observation.ocrText)
        if (promotion) {
            // A visually classified promotion plus unmistakable paywall OCR
            // provides two independent signals. Only that strict intersection
            // can be quarantined without manual review. A generic watermark
            // or a model guess alone cannot move somebody's artwork.
            val verifiedPromotion = unequivocalPaidPreviewOcr(observation.ocrText)
            return ContentTriageDecision(
                route = ROUTE_PROMOTION_TRASH,
                folderSegments = listOf("Trash", "Promotion"),
                filenamePrefix = "Promotion",
                terminal = true,
                requiresReview = !verifiedPromotion,
                reason = if (verifiedPromotion)
                    "Promotional preview confirmed by image routing and paywall text; reversible Trash/Promotion relocation."
                else "Possible promotional/preview content; retain source until review.",
            )
        }

        // Decode+texture observations beat an LLM claim of "broken image".
        // A genuinely broken file is caught earlier by BitmapFactory.
        val junk = !observation.verifiedNonblankImage &&
            (contentClass in JUNK_CLASSES || flags.any { it in JUNK_FLAGS })
        if (junk) {
            return ContentTriageDecision(
                route = ROUTE_JUNK_TRASH,
                folderSegments = listOf("Trash", "Junk"),
                filenamePrefix = "Junk",
                terminal = true,
                requiresReview = true,
                reason = "Blank, broken, placeholder, or otherwise non-library image content.",
            )
        }

        val scenery = observation.sceneryOrEnvironment ||
            observation.environmentDominant ||
            contentClass in SCENERY_CLASSES

        // Environment dominance is an organization decision, not an identity
        // decision. Asterion may still resolve/store a known character, but if
        // the artwork itself is primarily scenery (for example a tiny figure,
        // silhouette or luminous focal form inside a vast environment), the
        // file belongs in Scenery rather than a character folder.
        if (observation.environmentDominant) {
            return ContentTriageDecision(
                route = ROUTE_SCENERY,
                folderSegments = listOf("Scenery"),
                filenamePrefix = "Scenery",
                terminal = true,
                reason = "The environment is the primary subject of the artwork.",
            )
        }

        // Outside a genuinely environment-dominant composition, canonical
        // Character Knowledge remains authoritative for character organization.
        if (resolvedCharacterCount > 0) {
            return ContentTriageDecision(
                route = ROUTE_CHARACTER,
                reason = "At least one canonical Character Knowledge identity resolved.",
            )
        }

        // Scenery is defined by composition, not by a literal zero-person count.
        // Tiny silhouettes, decorative figures, statues, anonymous crowd shapes,
        // etc. may be visible without being identifiable character subjects.
        if (scenery && identifiableCharacters == 0) {
            return ContentTriageDecision(
                route = ROUTE_SCENERY,
                folderSegments = listOf("Scenery"),
                filenamePrefix = "Scenery",
                terminal = true,
                reason = "Scenery/environment is primary and no identifiable character subject needs organization.",
            )
        }

        if (visiblePeople == 0 && identifiableCharacters == 0) {
            return ContentTriageDecision(
                route = ROUTE_NO_CHARACTER_TRASH,
                folderSegments = listOf("Trash", "No Character"),
                filenamePrefix = "No Character",
                terminal = true,
                requiresReview = true,
                reason = "No visible character/person and not classified as scenery.",
            )
        }

        // A partially imported roster is not authorization to file unknown
        // characters as a final group. Wait for canonical identities instead.
        if (!characterKnowledgeReady && identifiableCharacters > 0) {
            return ContentTriageDecision(
                route = ROUTE_WAITING_FOR_KNOWLEDGE,
                reason = "Character identity deferred until Character Knowledge is available; visual tags were still saved.",
            )
        }

        if (
            identifiableCharacters >= 2 ||
            (identifiableCharacters < 0 && visiblePeople >= 2) ||
            contentClass in MULTI_CHARACTER_CLASSES
        ) {
            return ContentTriageDecision(
                route = ROUTE_UNIDENTIFIED_GROUP,
                folderSegments = listOf("Unidentified Groups"),
                filenamePrefix = "Unidentified Group",
                terminal = true,
                reason = "Multiple identifiable character subjects are visible but none resolved to Character Knowledge.",
            )
        }

        return if (characterKnowledgeReady) {
            ContentTriageDecision(
                route = ROUTE_SINGLE_UNIDENTIFIED,
                requiresReview = true,
                reason = "One identifiable character subject could not be resolved confidently.",
            )
        } else {
            ContentTriageDecision(
                route = ROUTE_WAITING_FOR_KNOWLEDGE,
                reason = "One identifiable character subject is waiting for Character Knowledge.",
            )
        }
    }

    fun corruptDecision(): ContentTriageDecision = ContentTriageDecision(
        route = ROUTE_CORRUPT_TRASH,
        folderSegments = listOf("Trash", "Corrupt"),
        filenamePrefix = "Corrupt",
        terminal = true,
        reason = "Image could not be decoded safely.",
    )

    /**
     * Cheap, high-specificity OCR gate for paid-preview collages.
     * Do not treat creator domains, ordinary watermarks, censored anatomy,
     * or a single ambiguous "preview" word as sufficient evidence.
     */
    fun unequivocalPaidPreviewOcr(raw: String): Boolean {
        val text = raw.lowercase().replace(Regex("\\s+"), " ")
        val access = listOf("unlock the set", "unlock full set", "subscribe to view",
            "pay to unlock", "full uncensored set").any { it in text }
        val offering = listOf("nsfw photos", "uncensored photos", "uncensored set",
            "exclusive photos", "full set", "photos!").any { it in text }
        return access && offering
    }

    private fun strongPromotionText(raw: String): Boolean {
        val text = raw.lowercase()
        if (text.isBlank()) return false
        return PROMOTION_TEXT_MARKERS.any { marker -> marker in text }
    }

    private val PROMOTION_CLASSES = setOf(
        "promotion",
        "promotional",
        "advertisement",
        "preview",
        "paywall",
        "placeholder",
        "contact_sheet",
    )

    private val SCENERY_CLASSES = setOf(
        "scenery",
        "environment",
        "background",
        "cityscape",
        "nature",
        "landscape",
        "architecture",
        "interior",
        "space",
        "abstract_environment",
    )

    private val MULTI_CHARACTER_CLASSES = setOf(
        "multi_character",
        "group",
        "crowd",
    )

    private val JUNK_CLASSES = setOf(
        "blank",
        "broken_image",
        "error_screen",
        "loading_screen",
        "thumbnail_placeholder",
    )

    private val PROMOTION_FLAGS = setOf(
        "promotional_overlay",
        "paywall",
        "preview",
        "placeholder",
        "contact_sheet",
        "blurred_preview",
        "sample_grid",
    )

    private val JUNK_FLAGS = setOf(
        "blank",
        "broken_image",
        "decode_artifact",
        "loading_screen",
        "thumbnail_placeholder",
        "solid_color_placeholder",
        "fully_blurred",
        "full_frame_blur",
    )

    private val PROMOTION_TEXT_MARKERS = setOf(
        "unlock the set",
        "unlock full set",
        "subscribe to view",
        "pay to unlock",
        "full uncensored set",
        "full image on",
        "full set on",
    )
}
