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
    // Only a real candidate near the knowledge threshold warrants manual
    // identity review. No-match crops remain tagged but await references.
    val identityCandidatesAvailable: Boolean = false,
)

internal data class ContentTriageDecision(
    val route: String,
    val folderSegments: List<String> = emptyList(),
    val filenamePrefix: String = "",
    val terminal: Boolean = false,
    val requiresReview: Boolean = false,
    val reason: String = "",
    val junkKind: String = "",
    val learnedJunkRoute: Boolean = false,
)

internal object ContentTriagePolicy {
    const val ROUTE_CHARACTER = "character"
    const val ROUTE_SCENERY = "scenery"
    const val ROUTE_PROMOTION_TRASH = "promotion_trash"
    const val ROUTE_JUNK_TRASH = "junk_trash"
    const val ROUTE_NO_CHARACTER_TRASH = "no_character_trash"
    const val ROUTE_UNIDENTIFIED_GROUP = "unidentified_group"
    const val ROUTE_SINGLE_UNIDENTIFIED = "single_unidentified"
    const val ROUTE_UNCERTAIN_JUNK = "uncertain_identity_junk"
    const val ROUTE_WAITING_FOR_KNOWLEDGE = "waiting_for_knowledge"
    const val ROUTE_CORRUPT_TRASH = "corrupt_trash"

    private fun candidateJunk(
        kind: String,
        reason: String,
        learnedJunkKinds: Set<String>,
    ): ContentTriageDecision {
        val trained = kind in learnedJunkKinds
        val category = when (kind) {
            "promotion" -> ROUTE_PROMOTION_TRASH
            "junk" -> ROUTE_JUNK_TRASH
            else -> ROUTE_UNCERTAIN_JUNK
        }
        val folder = when (kind) {
            "promotion" -> "Promotional Previews"
            "junk" -> "Suspected Junk"
            else -> "Uncertain Identity"
        }
        return ContentTriageDecision(
            route = category,
            folderSegments = listOf("Junk", folder),
            filenamePrefix = when (kind) {
                "promotion" -> "Promotional Preview"
                "junk" -> "Suspected Junk"
                else -> "Uncertain Identity"
            },
            terminal = trained,
            requiresReview = !trained,
            reason = reason,
            junkKind = kind,
            learnedJunkRoute = trained,
        )
    }

    fun decide(
        observation: ContentRoutingObservation,
        resolvedCharacterCount: Int,
        characterKnowledgeReady: Boolean,
        learnedJunkKinds: Set<String> = emptySet(),
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
            // Paid-preview text is strong triage evidence, not permission for
            // an irreversible filesystem move. Keep the source until review.
            return candidateJunk(
                "promotion",
                if (unequivocalPaidPreviewOcr(observation.ocrText))
                    "Likely paid promotional preview; first cases require your confirmation."
                else "Possible promotional material; confirm before filing into Junk.",
                learnedJunkKinds,
            )
        }

        // Decode+texture observations beat an LLM claim of "broken image".
        // A genuinely broken file is caught earlier by BitmapFactory.
        val modelClaimsJunk = contentClass in JUNK_CLASSES || flags.any { it in JUNK_FLAGS }
        if (observation.verifiedNonblankImage && modelClaimsJunk) {
            return ContentTriageDecision(
                route = ROUTE_WAITING_FOR_KNOWLEDGE,
                reason = "Contradictory model garbage label ignored: decodable textured image verified.",
            )
        }
        val junk = !observation.verifiedNonblankImage && modelClaimsJunk
        if (junk) {
            return candidateJunk(
                "junk",
                "Possible damaged or placeholder image; verify early examples before filing into Junk.",
                learnedJunkKinds,
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

        if (visiblePeople == 0 && identifiableCharacters == 0 && observation.verifiedNonblankImage) {
            return ContentTriageDecision(
                route = ROUTE_WAITING_FOR_KNOWLEDGE,
                requiresReview = true,
                reason = "Model found no identifiable subject in a verified nonblank photo. Manual review instead of Trash.",
            )
        }
        if (visiblePeople == 0 && identifiableCharacters == 0) {
            return ContentTriageDecision(
                route = ROUTE_WAITING_FOR_KNOWLEDGE,
                requiresReview = true,
                reason = "No identifiable character. Review before excluding a valid image.",
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

        return if (characterKnowledgeReady && observation.identityCandidatesAvailable) {
            candidateJunk(
                "uncertain_identity",
                "A plausible canonical identity remains uncertain; verify early cases before Junk filing.",
                learnedJunkKinds,
            )
        } else if (characterKnowledgeReady && visiblePeople == 1) {
            // No plausible roster match: an unknown SINGLE character belongs
            // in Original Characters, never implicitly Junk or Trash.
            ContentTriageDecision(
                route = ROUTE_SINGLE_UNIDENTIFIED,
                folderSegments = listOf("Original Characters"),
                filenamePrefix = "Unknown Character",
                terminal = true,
                reason = "One character is visible, but no established Character Knowledge identity matches.",
            )
        } else {
            ContentTriageDecision(
                route = ROUTE_WAITING_FOR_KNOWLEDGE,
                reason = "Appearance tags retained; wait for reliable character and scenery evidence.",
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
