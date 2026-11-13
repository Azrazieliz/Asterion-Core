package com.ailm.android.runtime

import android.content.ContentValues
import org.json.JSONArray
import org.json.JSONObject

/**
 * Applies AI observations to mutable Fusion state.
 *
 * Canonical identity is never accepted from a free-form model name. The vision
 * stage only emits observable taxonomy IDs; CharacterResolver then constrains
 * candidates to immutable Character Knowledge and derives series from the
 * resolved character entry.
 */
internal class AiWorkflowCoordinator(
    private val database: LocalDatabase,
    private val repository: LocalRepository,
    private val knowledge: FusionKnowledgeReader,
    private val fusion: FusionResolutionStore,
) {
    private val characterResolver = CharacterResolver(knowledge, fusion)
    // Image tagging reads native Fusion directly. Empty Character Knowledge
    // and absent visual sheets cannot make valid HC/EC/SP/etc observations
    // disappear before the user starts building their character roster.
    private val liveTaxonomy = FusionTaxonomyEngine(database)
    private fun subjectDescriptor(id: String): CanonicalObservationPolicy.Descriptor? =
        liveTaxonomy.tagById(id)?.let { tag ->
            CanonicalObservationPolicy.Descriptor(
                id = tag.id,
                parentId = tag.parentId,
                name = tag.name,
                traitRequirement = tag.traitRequirement,
                speciesRules = tag.speciesRules,
            )
        }

    fun applyImageWorkflow(imageId: Int, response: Map<String, Any>): Map<String, Any> {
        if (!response["ok"].asBoolean()) {
            val message = response["message"]?.toString().orEmpty().ifBlank { "AI pipeline failed." }
            fusion.queueReview(
                imageId = imageId,
                reviewType = "runtime_failure",
                reason = message,
                payload = mapOf("pipeline" to response),
            )
            return mapOf(
                "accepted" to false,
                "queued_for_review" to true,
                "review_reasons" to listOf(message),
                "resolved_characters" to emptyList<Map<String, Any>>(),
            )
        }

        val stages = collectStages(response)
        val reviewReasons = mutableListOf<String>()
        val verifiedNativeTags = response["verified_native_tags"] == true
        response["failed_stages"].mapList().forEach { failed ->
            val stage = failed.optText("stage_type").ifBlank { "stage" }
            // Absent optional OCR, embeddings or aesthetic scoring must not
            // reject an otherwise verified Fusion visual classification.
            if (!AutomationStageFailurePolicy.isBlocking(stage, verifiedNativeTags)) {
                return@forEach
            }
            val message = failed.optText("message").ifBlank { failed.optText("status") }
            reviewReasons += "Automation stage failed: " + stage +
                if (message.isBlank()) "" else " (" + message + ")"
        }

        val profile = readProfile(imageId)
        if (!profile.optBoolean("manual_override", false) &&
            profile.optJSONObject("normalization")?.optBoolean("manual_override", false) != true
        ) {
            stages["ocr"]?.resultMap()?.optText("text")?.takeIf { it.isNotBlank() }?.let { profile.put("ocr", it) }
            stages["captioning"]?.resultMap()?.optText("caption")?.takeIf { it.isNotBlank() }?.let { profile.put("caption", it) }
            stages["embedding_generation"]?.get("embedding")?.let { value ->
                profile.put("embedding", JSONArray(value as? List<*> ?: emptyList<Any>()))
            }
            stages["nsfw_classification"]?.resultMap()?.let { nsfw -> profile.put("nsfw", JSONObject(nsfw)) }
            stages["tag_prediction"]?.resultMap()?.get("family_coverage")?.let { coverage ->
                if (coverage is List<*>) profile.put("taxonomy_family_coverage", JSONArray(coverage))
            }
            // Withheld scores remain non-authoritative, per-image Review
            // suggestions with explicit reasons. They cannot become legacy
            // search tags, character attributes or an automatic file route.
            stages["tag_prediction"]?.resultMap()?.get("deferred_scored_tags")?.let { held ->
                if (held is List<*>) profile.put("taxonomy_deferred_tags", JSONArray(held))
            }
            stages["tag_prediction"]?.resultMap()?.get("species_trait_expectations")?.let { expected ->
                if (expected is List<*>) {
                    profile.put("nonvisual_species_trait_expectations", JSONArray(expected))
                }
            }
            // Device diagnostics: a whole-image WD prediction does not
            // prove that subject crops were ever analyzed. Record why the
            // character families may have been withheld for review.
            stages["character_recognition"]?.resultMap()?.let { subjects ->
                val status = subjects["subject_detection_status"]?.toString().orEmpty()
                if (status.isNotBlank()) {
                    profile.put("native_subject_detection_status", status.take(80))
                }
                (subjects["subject_crop_inference_count"] as? Number)?.let {
                    profile.put("native_subject_crop_inference_count", it.toInt().coerceAtLeast(0))
                }
            }
            // Keep the trained model/vocabulary diagnostics separate from
            // accepted image tags and manual corrections.
            stages["tag_prediction"]?.resultMap()?.let { tags ->
                profile.put("native_tagger_model_id", tags["model_id"]?.toString().orEmpty())
                profile.put("native_tagger_unmapped_labels",
                    JSONArray((tags["unmapped_labels"] as? List<*>).orEmpty().take(30)))
            }
            // Persist real body boxes independently of identity. A bounding
            // rectangle is review evidence, not proof of the character name.
            val regions = (stages["character_recognition"]?.resultMap()?.get("subjects") as? List<*>)
                .orEmpty().mapNotNull { raw ->
                    val row = raw as? Map<*, *> ?: return@mapNotNull null
                    val bounds = row["bbox"] as? List<*> ?: return@mapNotNull null
                    if (bounds.size != 4) return@mapNotNull null
                    mapOf(
                        "subject_index" to (row["subject_index"] ?: -1),
                        "bbox" to bounds,
                        "detection_confidence" to (row["detection_confidence"] ?: 0.0),
                        "unverified_overlap" to (row["unverified_overlap"] == true),
                    )
                }
            if (regions.isNotEmpty()) {
                profile.put("native_subject_regions", JSONArray(regions))
                profile.put("native_subject_region_source", "verified_person_onnx")
            }
            // Retain independent family receipts for each detected crop.
            // Subject-specific failures must not inherit image-wide success.
            val subjectAudits = (stages["character_recognition"]?.resultMap()
                ?.get("subjects") as? List<*>).orEmpty()
                .mapNotNull { raw ->
                    val subject = raw as? Map<*, *> ?: return@mapNotNull null
                    val audit = subject["family_coverage"] as? List<*> ?: return@mapNotNull null
                    mapOf(
                        "subject_index" to (subject["subject_index"] ?: -1),
                        "family_coverage" to audit,
                    )
                }
            if (subjectAudits.isNotEmpty()) {
                profile.put("native_subject_family_coverage", JSONArray(subjectAudits))
            }
            // Aesthetic inference was previously discarded by the central
            // image profile writer even when the ONNX stage succeeded.
            stages["aesthetic_scoring"]?.resultMap()?.let { aesthetic ->
                val raw = aesthetic["aesthetic_score"] ?: aesthetic["score"]
                val value = (raw as? Number)?.toDouble()
                    ?: raw?.toString()?.toDoubleOrNull()
                if (value != null && value.isFinite()) {
                    profile.put("aesthetic_score", value)
                    val ratingSource = aesthetic["source"]?.toString()
                        ?.takeIf(String::isNotBlank) ?: "on_device_inference"
                    profile.put("aesthetic_model_source", ratingSource)
                    // Asterion Art: 1..5 stars, independent from RT.
                    // An automatic suggestion can NEVER overwrite saved user
                    // rating, even after a full automation restart.
                    ArtworkRatingPolicy.rawAutomaticStars(value)?.let { proposed ->
                        profile.put("automatic_artwork_rating", proposed)
                        profile.put("automatic_rating_origin", ratingSource)
                    }
                }
            }
            stages["normalization"]?.resultMap()?.optText("text")?.takeIf { it.isNotBlank() }?.let {
                profile.put("normalized_context", it)
            }
            val nativeMedium = VisualMediumPolicy.accept(
                stages["tag_prediction"]?.resultMap()?.get("visual_medium") as? Map<*, *>,
            )
            val semanticMedium = VisualMediumPolicy.accept(
                stages["character_recognition"]?.resultMap()?.get("visual_medium") as? Map<*, *>,
            )
            val medium = nativeMedium ?: semanticMedium
            if (medium != null) {
                profile.put("visual_medium", medium.medium)
                profile.put("visual_medium_confidence", medium.confidence)
                profile.put("visual_medium_evidence", medium.evidence)
                profile.put("visual_medium_source",
                    if (nativeMedium != null) "verified_native_fixed_head" else "semantic_secondary_review")
            }
            // No confident medium response must never erase an earlier
            // classification, including a manually curated one.
            profile.put("ai_workflow_updated_at_ms", System.currentTimeMillis())
            writeProfile(imageId, profile)
        }

        val illustrationTags = resolveIllustrationTags(stages["tag_prediction"])
        val illustrationFeatureIds = illustrationTags.associate { it.tagId to it.confidence }
        val embedding = stages["embedding_generation"]?.get("embedding").doubleList()

        val hasCharacterKnowledge = knowledge.hasCharacters()
        val recognitionResult = stages["character_recognition"]?.resultMap().orEmpty()
        val routingMap = (recognitionResult["routing"] as? Map<*, *>)
            ?.entries
            ?.mapNotNull { (key, value) ->
                key?.toString()?.trim()?.takeIf(String::isNotBlank)?.let { text ->
                    value?.let { text to it }
                }
            }
            ?.toMap()
            .orEmpty()
        val rawSubjectCount = (recognitionResult["subjects"] as? List<*>)?.size ?: 0
        // Only a SINGLE visible subject may inherit manual image-wide SP.
        // In a group, global species labels cannot be assigned to everyone.
        val trustedSpecies = if (rawSubjectCount == 1) fusion.currentImageCanonicalTags(imageId)
            .filter { it.source == "manual" && it.tagId.startsWith("SP") }
            .map { it.tagId }.toSet() else emptySet()
        val trustedSpeciesBySubject = if (trustedSpecies.isNotEmpty())
            mapOf(0 to trustedSpecies) else emptyMap()
        val routingObservation = ContentRoutingObservation(
            contentClass = routingMap["content_class"]?.toString().orEmpty().ifBlank { "other" },
            peopleCount = (routingMap["people_count"] as? Number)?.toInt() ?: rawSubjectCount,
            identifiableCharacterCount = (routingMap["identifiable_character_count"] as? Number)?.toInt()
                ?: 0, // Pixel-detected people are NOT identified characters.
            promotionOrPreview = routingMap["promotion_or_preview"].asBoolean(),
            sceneryOrEnvironment = routingMap["scenery_or_environment"].asBoolean() ||
                routingMap["landscape_or_scenery"].asBoolean(),
            environmentDominant = routingMap["environment_dominant"].asBoolean(),
            qualityFlags = (routingMap["quality_flags"] as? List<*>)
                ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
                .orEmpty(),
            ocrText = stages["ocr"]?.resultMap()?.optText("text").orEmpty(),
            verifiedNonblankImage = response["verified_nonblank_image"] == true,
        )
        val resolvedSubjects = if (hasCharacterKnowledge && recognitionResult.isNotEmpty()) {
            characterResolver.resolve(
                recognitionResult = recognitionResult,
                illustrationFeatureIds = illustrationFeatureIds,
                imageEmbedding = embedding,
                verifiedSpeciesBySubject = trustedSpeciesBySubject,
            )
        } else {
            emptyList()
        }

        // Visual observations and taxonomy tagging do not require a complete
        // character roster or character sheets. Keep the subject evidence for
        // later identity matching, even when no character can be resolved.
        val subjectRecords = if (resolvedSubjects.isNotEmpty()) {
            resolvedSubjects.map { subject ->
                SubjectResolutionRecord(
                    subjectIndex = subject.subjectIndex,
                    prominence = subject.prominence,
                    observations = subject.observations,
                    candidates = subject.candidates,
                    characterId = subject.character?.characterId,
                    confidence = subject.confidence,
                    status = if (subject.resolved) "resolved" else "review",
                )
            }
        } else {
            (recognitionResult["subjects"] as? List<*>).orEmpty().mapIndexedNotNull { index, value ->
                val raw = (value as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
                    k?.toString()?.let { key -> v?.let { key to it } }
                }?.toMap() ?: return@mapIndexedNotNull null
                SubjectResolutionRecord(
                    subjectIndex = (raw["subject_index"] as? Number)?.toInt() ?: index,
                    prominence = ((raw["prominence"] as? Number)?.toDouble() ?: 1.0).coerceIn(0.0, 1.0),
                    observations = CanonicalObservationPolicy.sanitize(
                        raw, trustedSpeciesBySubject[index].orEmpty(),
                    ) { id -> subjectDescriptor(id) },
                    candidates = emptyList(),
                    characterId = null,
                    confidence = 0.0,
                    status = "waiting_for_knowledge",
                )
            }
        }
        if (response["partial_tag_recovery"] != true) {
            fusion.replaceSubjects(imageId, subjectRecords)
        }

        val resolvedCharacters = resolvedSubjects
            .filter(ResolvedSubject::resolved)
            .mapNotNull { subject ->
                val character = subject.character ?: return@mapNotNull null
                val series = knowledge.seriesByCode(character.primarySeriesCode)
                    ?: return@mapNotNull null
                mapOf(
                    "subject_index" to subject.subjectIndex,
                    "prominence" to subject.prominence,
                    "character_id" to character.characterId,
                    "canonical_name" to character.canonicalName,
                    "entry_type" to character.entryType,
                    "parent_character_id" to character.parentCharacterId,
                    "series_code" to series.code,
                    "series_name" to series.name,
                    "confidence" to subject.confidence,
                )
            }

        val triage = ContentTriagePolicy.decide(
            observation = routingObservation.copy(
                identityCandidatesAvailable = resolvedSubjects.any { it.candidates.isNotEmpty() },
            ),
            resolvedCharacterCount = resolvedCharacters.size,
            characterKnowledgeReady = hasCharacterKnowledge,
        )

        when {
            triage.requiresReview -> {
                reviewReasons += triage.reason
            }
            triage.route == ContentTriagePolicy.ROUTE_CHARACTER && hasCharacterKnowledge -> {
                resolvedSubjects.filterNot(ResolvedSubject::resolved).forEach { subject ->
                    reviewReasons += "Subject " + (subject.subjectIndex + 1) +
                        " is below the character confidence threshold (" +
                        "%.2f".format(subject.confidence) + ")."
                }
            }
        }

        val canonicalTags = mutableListOf<CanonicalTagObservation>()
        // RT is determined only by an independently scored NSFW classifier,
        // never by a free-form model or generic image tagger.
        canonicalTags += illustrationTags.filterNot { it.tagId.startsWith("RT", ignoreCase = true) }
        val nsfwEvidence = FusionNsfwRatingPolicy.evaluate(
            FusionNsfwRatingPolicy.parseRanked(
                stages["nsfw_classification"]?.resultMap().orEmpty(),
            ),
        )
        val nativeResult = stages["tag_prediction"]?.resultMap().orEmpty()
        val nativeRating = (nativeResult["artwork_rating"] as? Map<*, *>)
            ?.takeIf { nativeResult["model_id"] == "asterion-wd-vit-tagger-v3" }
            ?.let { evidence ->
                val label = evidence["canonical_name"]?.toString().orEmpty()
                val confidence = (evidence["confidence"] as? Number)?.toDouble() ?: 0.0
                val origin = evidence["originating_class"]?.toString().orEmpty()
                if (label !in setOf("SFW", "Ecchi", "NSFW") ||
                    confidence !in 0.88..1.0 || !confidence.isFinite() ||
                    !origin.startsWith("wd:")) null
                else FusionNsfwRatingPolicy.RatingEvidence(label, confidence, origin)
            }
        // A general NSFW model may say "drawings", which is no rating.
        // A verified WD artwork rating fills that gap. Contradictions require
        // review rather than assigning incompatible RT identities.
        val contentRating = when {
            nsfwEvidence == null -> nativeRating
            nativeRating == null -> nsfwEvidence
            nsfwEvidence.canonicalName == nativeRating.canonicalName -> nsfwEvidence
            else -> {
                reviewReasons += "Content rating models disagree; choose SFW, Ecchi or NSFW."
                null
            }
        }
        contentRating?.let { rating ->
            knowledge.resolveContentRating(rating.canonicalName)?.let { tag ->
                canonicalTags += CanonicalTagObservation(
                    tagId = tag.id,
                    tagName = tag.name,
                    scope = "content_rating",
                    confidence = rating.confidence,
                    source = if (rating.originatingClass.startsWith("wd:"))
                        "wd_rating_classifier" else "nsfw_classifier",
                )
            }
        }
        // RT has its own evidence head. Replace the general tagger's
        // provisional coverage status with the actual independent result.
        val familyAudit = profile.optJSONArray("taxonomy_family_coverage")
        if (familyAudit != null) {
            val ratingSaved = canonicalTags.any { it.tagId.startsWith("RT") }
            val ratingWasScored = stages["nsfw_classification"]?.resultMap()
                ?.isNotEmpty() == true || nativeResult["artwork_rating"] != null
            for (index in 0 until familyAudit.length()) {
                val receipt = familyAudit.optJSONObject(index) ?: continue
                if (receipt.optString("family") != "RT") continue
                receipt.put("accepted_count", if (ratingSaved) 1 else 0)
                receipt.put("status", when {
                    ratingSaved -> "accepted"
                    nsfwEvidence != null && nativeRating != null -> "deferred_review"
                    ratingWasScored -> "evaluated_no_confident_match"
                    else -> "unsupported_model"
                })
            }
            profile.put("taxonomy_family_coverage", familyAudit)
            writeProfile(imageId, profile)
        }
        subjectRecords.forEach { subject ->
            subject.observations.forEach { (id, confidence) ->
                liveTaxonomy.tagById(id)?.let { tag ->
                    canonicalTags += CanonicalTagObservation(
                        tagId = tag.id,
                        tagName = tag.name,
                        scope = "character_observed",
                        confidence = confidence,
                        source = "vision",
                    )
                }
            }
        }
        // Character reference profiles are identity evidence, not visual
        // observations. Never copy unseen canonical attributes into image tags.
        val userRejected = rejectedTagIds(profile)
        val approvedAiTags = canonicalTags.filterNot {
            it.source != "manual" && it.tagId.uppercase() in userRejected
        }
        // A successfully completed new analysis supersedes ONLY prior machine
        // observations. Keep manually curated tags, even if the result is empty.
        // Failed or partial analyses never erase previous evidence.
        if (response["partial_tag_recovery"] == true) {
            if (approvedAiTags.isNotEmpty()) fusion.mergeCanonicalTags(imageId, approvedAiTags)
        } else {
            val adjudicatedFamilies = FusionTagUpdatePolicy.replaceFamilies(
                approvedAiTags,
                stages["tag_prediction"]?.resultMap()?.get("family_coverage"),
            )
            fusion.replaceCanonicalTags(
                imageId = imageId,
                tags = approvedAiTags,
                replaceFamilies = adjudicatedFamilies,
            )
        }
        // Rebuild the legacy index from the committed canonical+manual Fusion
        // observations. Earlier rejected vision tags must not survive in text.
        refreshLegacyCanonicalTags(imageId)

        val imageUri = repository.searchByImageId(imageId)?.get("uri")?.toString().orEmpty()
        // The original implementation attached the full artwork vector to
        // EACH character in a group, contaminating future character matches.
        // Only an accepted subject's own vector may become candidate evidence.
        for (subject in resolvedSubjects.filter(ResolvedSubject::resolved)) {
            val character = subject.character ?: continue
            val vector = subject.visualEmbedding
            if (vector.isEmpty()) continue
            fusion.addCharacterEvidence(
                characterId = character.characterId,
                imageId = imageId,
                evidenceKind = "auto_resolved_subject",
                sourceUri = imageUri,
                embedding = vector,
                attributes = subject.observations,
                validated = false,
                weight = 0.45,
            )
        }

        val unresolved = triage.route == ContentTriagePolicy.ROUTE_CHARACTER &&
            hasCharacterKnowledge &&
            resolvedSubjects.any { !it.resolved }
        val queuedForReview = reviewReasons.isNotEmpty() || triage.requiresReview || unresolved
        if (queuedForReview) {
            fusion.queueReview(
                imageId = imageId,
                reviewType = "character_resolution",
                reason = reviewReasons.distinct().joinToString("; "),
                payload = mapOf(
                    "subjects" to subjectRecords.map {
                        mapOf(
                            "subject_index" to it.subjectIndex,
                            "prominence" to it.prominence,
                            "observations" to it.observations,
                            "candidates" to it.candidates,
                            "resolved_character_id" to (it.characterId ?: ""),
                            "confidence" to it.confidence,
                        )
                    },
                    "resolved_characters" to resolvedCharacters,
                    "content_route" to triage.route,
                    "routing_observation" to mapOf(
                        "content_class" to routingObservation.contentClass,
                        "people_count" to routingObservation.peopleCount,
                        "promotion_or_preview" to routingObservation.promotionOrPreview,
                        "identifiable_character_count" to routingObservation.identifiableCharacterCount,
                        "scenery_or_environment" to routingObservation.sceneryOrEnvironment,
                        "environment_dominant" to routingObservation.environmentDominant,
                        "quality_flags" to routingObservation.qualityFlags,
                    ),
                ),
            )
        }

        val primary = resolvedCharacters.maxByOrNull {
            (it["prominence"] as? Number)?.toDouble() ?: 0.0
        }.orEmpty()

        return mapOf(
            "accepted" to resolvedCharacters.isNotEmpty(),
            "character_resolution_available" to hasCharacterKnowledge,
            "resolved_characters" to resolvedCharacters,
            "all_subjects_resolved" to (hasCharacterKnowledge && resolvedSubjects.isNotEmpty() && resolvedSubjects.all(ResolvedSubject::resolved)),
            "accepted_character_id" to primary["character_id"].orEmptyText(),
            "accepted_character_name" to primary["canonical_name"].orEmptyText(),
            "accepted_series_code" to primary["series_code"].orEmptyText(),
            "accepted_series_name" to primary["series_name"].orEmptyText(),
            "original_character" to false,
            "content_route" to triage.route,
            "content_route_reason" to triage.reason,
            "content_route_terminal" to triage.terminal,
            "content_route_folders" to triage.folderSegments,
            "content_route_filename_prefix" to triage.filenamePrefix,
            "routing_observation" to mapOf(
                "content_class" to routingObservation.contentClass,
                "people_count" to routingObservation.peopleCount,
                "promotion_or_preview" to routingObservation.promotionOrPreview,
                "identifiable_character_count" to routingObservation.identifiableCharacterCount,
                        "scenery_or_environment" to routingObservation.sceneryOrEnvironment,
                        "environment_dominant" to routingObservation.environmentDominant,
                "quality_flags" to routingObservation.qualityFlags,
            ),
            "queued_for_review" to queuedForReview,
            "review_reasons" to reviewReasons.distinct(),
            "canonical_tag_ids" to canonicalTags.map(CanonicalTagObservation::tagId).distinct(),
        )
    }

    fun applyReviewCorrection(imageId: Int, payload: Map<String, Any>): Map<String, Any> {
        val originalCharacter = payload["original_character"].asBoolean()
        val profile = readProfile(imageId)
        val embedding = profile.optJSONArray("embedding").doubleList()

        if (originalCharacter) {
            val observations = payload["attribute_ids"].stringDoubleMap().ifEmpty {
                readSubjectObservations(imageId).firstOrNull()?.second.orEmpty()
            }
            val clusterId = fusion.createOrAssignOriginalCharacterCluster(
                imageId = imageId,
                subjectIndex = 0,
                attributes = observations,
                embedding = embedding,
            )
            return mapOf(
                "accepted" to true,
                "manual_correction" to true,
                "original_character" to true,
                "original_character_cluster_id" to clusterId,
                "resolved_characters" to emptyList<Map<String, Any>>(),
                "queued_for_review" to false,
            )
        }

        val requested = when (val raw = payload["characters"]) {
            is List<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
            else -> listOfNotNull(
                payload["character_id"]?.toString()?.trim()?.takeIf(String::isNotBlank)
                    ?: payload["character_name"]?.toString()?.trim()?.takeIf(String::isNotBlank),
            )
        }
        if (requested.isEmpty()) {
            return mapOf(
                "accepted" to false,
                "queued_for_review" to true,
                "review_reasons" to listOf("A corrected character or Original Character selection is required."),
            )
        }

        val characters = requested.map { raw ->
            knowledge.resolveCharacter(raw)
                ?: return mapOf(
                    "accepted" to false,
                    "queued_for_review" to true,
                    "review_reasons" to listOf("Unknown Character Knowledge identity: " + raw),
                )
        }

        val existingSubjects = readSubjectObservations(imageId)
        val corrected = characters.mapIndexed { index, character ->
            val observations = existingSubjects.getOrNull(index)?.second.orEmpty()
            val series = knowledge.seriesByCode(character.primarySeriesCode)
                ?: return mapOf(
                    "accepted" to false,
                    "queued_for_review" to true,
                    "review_reasons" to listOf("Character has no valid canonical series: " + character.characterId),
                )
            mapOf(
                "subject_index" to index,
                "prominence" to (1.0 - index * 0.05).coerceAtLeast(0.5),
                "character_id" to character.characterId,
                "canonical_name" to character.canonicalName,
                "entry_type" to character.entryType,
                "parent_character_id" to character.parentCharacterId,
                "series_code" to series.code,
                "series_name" to series.name,
                "confidence" to 1.0,
            )
        }

        val records = corrected.mapIndexed { index, row ->
            SubjectResolutionRecord(
                subjectIndex = index,
                prominence = (row["prominence"] as Number).toDouble(),
                observations = existingSubjects.getOrNull(index)?.second.orEmpty(),
                candidates = listOf(row),
                characterId = row["character_id"].toString(),
                confidence = 1.0,
                status = "corrected",
            )
        }
        fusion.replaceSubjects(imageId, records)

        val imageUri = repository.searchByImageId(imageId)?.get("uri")?.toString().orEmpty()
        // Review-corrected identity is durable even without a sheet, but a
        // group-wide image embedding is NOT a validated reference for each
        // character. Only a recognized, single-subject image can bootstrap
        // future automatic visual matching.
        val verifiedReference = CharacterSubjectEvidencePolicy.verifiedSingleSubjectReferenceEmbedding(
            correctedIdentityCount = corrected.size,
            observedSubjectCount = existingSubjects.size,
            wholeImageEmbedding = embedding,
        )
        if (verifiedReference.isNotEmpty() && imageUri.isNotBlank()) {
            corrected.forEachIndexed { index, row ->
                fusion.addCharacterEvidence(
                    characterId = row["character_id"].toString(),
                    imageId = imageId,
                    evidenceKind = "review_correction",
                    sourceUri = imageUri,
                    embedding = verifiedReference,
                    attributes = existingSubjects.getOrNull(index)?.second.orEmpty(),
                    validated = true,
                    weight = 1.0,
                )
            }
        }

        return mapOf(
            "accepted" to true,
            "manual_correction" to true,
            "original_character" to false,
            "resolved_characters" to corrected,
            "all_subjects_resolved" to true,
            "accepted_character_id" to corrected.first()["character_id"].toString(),
            "accepted_character_name" to corrected.first()["canonical_name"].toString(),
            "accepted_series_code" to corrected.first()["series_code"].toString(),
            "accepted_series_name" to corrected.first()["series_name"].toString(),
            "queued_for_review" to false,
        )
    }

    private fun resolveIllustrationTags(stage: Map<String, Any>?): List<CanonicalTagObservation> {
        // Only a trained classifier's actual scored labels may become
        // automatic tags; free-form model words or fabricated constants cannot.
        // Match the trusted fixed-head mapper's model-specific acceptance
        // threshold. An unconditional 0.82 here silently discarded most
        // valid WD ViT v3 observations, leaving Fusion and gallery untagged.
        return FusionClassifierTagGate.acceptedScores(stage?.resultMap().orEmpty())
            .mapNotNull { (id, confidence) ->
                val tag = liveTaxonomy.tagById(id) ?: return@mapNotNull null
                CanonicalTagObservation(
                    tagId = tag.id, tagName = tag.name, scope = "illustration",
                    confidence = confidence, source = "vision_classifier",
                )
            }
    }

    /**
     * Correct one visible observation using a canonical two-letter ID.
     * User rejection is persisted in the image profile so a later automated
     * pass cannot silently reintroduce the same hallucinated image tag.
     * The immutable Character Knowledge taxonomy is never edited.
     */
    fun correctImageTag(imageId: Int, requestedId: String, include: Boolean): Boolean {
        val tag = liveTaxonomy.tagById(requestedId) ?: return false
        if (!tag.id.equals(requestedId.trim(), ignoreCase = true)) return false
        val profile = readProfile(imageId)
        val rejectedArray = profile.optJSONArray("user_rejected_tag_ids")
        val rejected = (0 until (rejectedArray?.length() ?: 0))
            .mapNotNull { index -> rejectedArray?.optString(index)?.takeIf(String::isNotBlank) }
            .map(String::uppercase).toMutableSet()
        if (include) rejected.remove(tag.id.uppercase()) else rejected.add(tag.id.uppercase())
        profile.put("user_rejected_tag_ids", JSONArray(rejected.sorted()))
        profile.put("last_manual_tag_edit_ms", System.currentTimeMillis())

        if (include) {
            // Promote one observation to a single user-verified record rather
            // than storing duplicates with both vision and manual provenance.
            fusion.removeImageCanonicalTag(imageId, tag.id)
            val family = tag.id.takeWhile(Char::isLetter).uppercase()
            val characterFamilies = setOf("HC","HL","HS","EC","ET","SC","BH","BT",
                "BS","BY","SX","AG","SP","SA")
            fusion.mergeCanonicalTags(imageId, listOf(CanonicalTagObservation(
                tagId = tag.id,
                tagName = tag.name,
                scope = if (family in characterFamilies) "character_observed" else "illustration",
                confidence = 1.0,
                source = "manual",
            )))
        } else {
            fusion.removeImageCanonicalTag(imageId, tag.id)
        }
        writeProfile(imageId, profile)
        refreshLegacyCanonicalTags(imageId)
        return true
    }

    private fun rejectedTagIds(profile: JSONObject): Set<String> {
        val arr = profile.optJSONArray("user_rejected_tag_ids") ?: return emptySet()
        return (0 until arr.length()).map { arr.optString(it).uppercase() }.toSet()
    }

    private fun refreshLegacyCanonicalTags(imageId: Int) {
        val prior = repository.getImageRecordsByIds(listOf(imageId))
            .firstOrNull()?.tagsText?.split('|').orEmpty()
        val exactCanonical = Regex("^[A-Z]{2}\\d{2,}(?:-\\d+)*\\s*:", RegexOption.IGNORE_CASE)
        // Preserve free-form manually entered search tags; rebuild indexed
        // canonical labels only from committed Fusion observations.
        val freeform = prior.map(String::trim)
            .filter { it.isNotEmpty() && !exactCanonical.containsMatchIn(it) }
        val canonical = fusion.currentImageCanonicalTags(imageId)
            .filter { it.scope != "character_reference" }
            .distinctBy { it.tagId.uppercase() }
            .map { "${it.tagId}: ${it.tagName}" }
        repository.setTags(imageId, (freeform + canonical).distinct())
    }

    private fun readSubjectObservations(imageId: Int): List<Pair<Int, Map<String, Double>>> =
        buildList {
            database.readableDatabase.rawQuery(
                "SELECT subject_index, observations_json FROM fusion_image_subjects WHERE image_id = ? ORDER BY subject_index",
                arrayOf(imageId.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val json = runCatching { JSONObject(cursor.getString(1)) }.getOrElse { JSONObject() }
                    val observations = buildMap {
                        json.keys().forEach { key ->
                            val number = json.opt(key) as? Number
                            if (number != null) put(key, number.toDouble())
                        }
                    }
                    add(cursor.getInt(0) to observations)
                }
            }
        }

    private fun readProfile(imageId: Int): JSONObject = database.readableDatabase.rawQuery(
        "SELECT metadata_json FROM " + FusionDatabaseSchema.TABLE_IMAGE_PROFILES + " WHERE image_id = ? LIMIT 1",
        arrayOf(imageId.toString()),
    ).use { cursor ->
        if (cursor.moveToFirst()) {
            runCatching { JSONObject(cursor.getString(0).orEmpty()) }.getOrElse { JSONObject() }
        } else JSONObject()
    }

    private fun writeProfile(imageId: Int, metadata: JSONObject) {
        val db = database.writableDatabase
        val now = System.currentTimeMillis()
        val sourceUri = repository.searchByImageId(imageId)?.get("uri")?.toString().orEmpty()
        db.beginTransaction()
        try {
            // Reanalysis may start before a user's latest rating edit. Never
            // restore stale manual-rating metadata from its earlier snapshot.
            db.rawQuery(
                "SELECT metadata_json FROM ${FusionDatabaseSchema.TABLE_IMAGE_PROFILES} WHERE image_id=?",
                arrayOf(imageId.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val latest = runCatching { JSONObject(cursor.getString(0).orEmpty()) }
                        .getOrDefault(JSONObject())
                    for (key in listOf("manual_rating", "manual_rating_authoritative",
                        "manual_rating_updated_at_ms")) {
                        if (latest.has(key)) metadata.put(key, latest.get(key))
                        else metadata.remove(key)
                    }
                }
            }
            val values = ContentValues().apply {
                put("source_uri", sourceUri)
                put("metadata_json", metadata.toString())
                put("updated_at_ms", now)
            }
            val changed = db.update(
                FusionDatabaseSchema.TABLE_IMAGE_PROFILES,
                values,
                "image_id=?", arrayOf(imageId.toString()),
            )
            if (changed == 0) {
                // On an older database without a profile, seed from the
                // original image row. A REPLACE would discard the owner's
                // favorite, manual rating, tags and thumbnail metadata.
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO fusion_image_profiles
                        (image_id, source_uri, thumbnail_uri, favorite,
                         rating, tags_text, metadata_json, updated_at_ms)
                    SELECT image_id, uri, uri, favorite, rating, tags_text, ?, ?
                    FROM images WHERE image_id=?
                    """.trimIndent(),
                    arrayOf(metadata.toString(), now, imageId),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun collectStages(response: Map<String, Any>): Map<String, Map<String, Any>> {
        val stages = linkedMapOf<String, Map<String, Any>>()
        val explicitOutputs = response["stage_outputs"] as? Map<*, *>
        explicitOutputs?.forEach { (stageKey, rawOutput) ->
            val stageType = stageKey?.toString()?.trim().orEmpty()
            val output = (rawOutput as? Map<*, *>)?.toStringAnyMap().orEmpty()
            if (stageType.isNotBlank() && output.isNotEmpty()) stages[stageType] = output
        }
        response["stages"].mapList().forEach { stage ->
            val stageType = stage.optText("task_type").ifBlank { stage.optText("stage_type") }
            if (stageType.isNotBlank()) stages.putIfAbsent(stageType, stage)
        }
        return stages
    }

    private fun Map<String, Any>.resultMap(): Map<String, Any> {
        val result = this["result"] as? Map<*, *> ?: return this
        return result.toStringAnyMap()
    }

    private fun Map<*, *>.toStringAnyMap(): Map<String, Any> = entries.mapNotNull { (key, value) ->
        key?.toString()?.let { text -> value?.let { text to it } }
    }.toMap()

    private fun Any?.mapList(): List<Map<String, Any>> = (this as? List<*>)
        ?.mapNotNull { (it as? Map<*, *>)?.toStringAnyMap() }
        .orEmpty()

    private fun Map<String, Any>.optText(key: String): String = this[key]?.toString()?.trim().orEmpty()

    private fun Map<String, Any>.stringList(key: String): List<String> = (this[key] as? List<*>)
        ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
        .orEmpty()

    private fun Any?.doubleList(): List<Double> = (this as? List<*>)
        ?.mapNotNull { (it as? Number)?.toDouble() }
        .orEmpty()

    private fun JSONArray?.doubleList(): List<Double> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index -> (opt(index) as? Number)?.toDouble() }
    }

    private fun Any?.stringDoubleMap(): Map<String, Double> = when (this) {
        is Map<*, *> -> entries.mapNotNull { (key, value) ->
            val id = key?.toString()?.trim().orEmpty()
            val number = (value as? Number)?.toDouble()
            if (id.isBlank() || number == null) null else id to number
        }.toMap()
        is List<*> -> mapNotNull { value ->
            val id = value?.toString()?.trim().orEmpty()
            if (id.isBlank()) null else id to 1.0
        }.toMap()
        else -> emptyMap()
    }

    private fun Any?.asBoolean(): Boolean = when (this) {
        is Boolean -> this
        is Number -> toInt() != 0
        is String -> equals("true", ignoreCase = true) || this == "1"
        else -> false
    }

    private fun Any?.orEmptyText(): String = this?.toString()?.trim().orEmpty()
}
