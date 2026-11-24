package com.ailm.android.runtime

import androidx.core.database.sqlite.transaction
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
    private val junkLearning: JunkTriageLearning,
) {
    private val characterResolver = CharacterResolver(
        knowledge, fusion, observedTagDescriptor = ::subjectDescriptor,
    )
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
        val db = database.writableDatabase
        db.beginTransactionNonExclusive()
        try {
            val result = persistImageWorkflow(imageId, response)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun persistImageWorkflow(imageId: Int, response: Map<String, Any>): Map<String, Any> {
        if (!response["ok"].asBoolean()) {
            val detection = collectStages(response)["character_recognition"]?.resultMap()
            val failedSubjects = detection?.get("failed_subject_audits") as? List<*>
            if (!failedSubjects.isNullOrEmpty()) {
                val prior = readProfile(imageId)
                prior.put("native_failed_subject_family_coverage", JSONArray(failedSubjects))
                (detection?.get("detected_subject_count") as? Number)?.let { prior.put("native_total_subject_count", it.toInt()) }
                writeProfile(imageId, prior)
            }
            val message = response["message"]?.toString().orEmpty().ifBlank { "AI pipeline failed." }
            fusion.queueReview(
                imageId = imageId,
                reviewType = "runtime_failure",
                reason = message,
                payload = mapOf("pipeline_type" to response["pipeline_type"].toString(),
                    "failed_stages" to response["failed_stages"].mapList().map { stage ->
                        mapOf("stage_type" to stage.optText("stage_type"), "status" to stage.optText("status"))
                    }),
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
            stages["tag_prediction"]?.resultMap()?.get("role_review_choices")?.let { roles ->
                if (roles is List<*>) profile.put("image_role_choices", JSONArray(roles))
            }
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
                (subjects["detected_subject_count"] as? Number)?.let {
                    profile.put("native_total_subject_count", it.toInt().coerceAtLeast(0))
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
            fun recordSubjectRows(key: String, rows: List<Map<String, Any>>) {
                if (rows.isEmpty()) return
                val byIndex = linkedMapOf<Int, JSONObject>()
                if (response["partial_tag_recovery"] == true) {
                    val prior = profile.optJSONArray(key)
                    for (index in 0 until (prior?.length() ?: 0)) {
                        val row = prior?.optJSONObject(index) ?: continue
                        byIndex[row.optInt("subject_index", -1)] = row
                    }
                }
                for (row in rows) {
                    val index = (row["subject_index"] as? Number)?.toInt() ?: continue
                    if (index >= 0) byIndex[index] = JSONObject(row)
                }
                profile.put(key, JSONArray(byIndex.toSortedMap().values.toList()))
            }
            if (regions.isNotEmpty()) {
                recordSubjectRows("native_subject_regions", regions)
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
                        "role_review_choices" to (subject["role_review_choices"] ?: emptyList<Any>()),
                        "withheld_attributes" to (subject["withheld_attributes"] ?: emptyList<Any>()),
                    )
                }
            recordSubjectRows("native_subject_family_coverage", subjectAudits)
            stages["character_recognition"]?.resultMap()?.get("failed_subject_audits")?.let { failed ->
                if (failed is List<*>) profile.put("native_failed_subject_family_coverage", JSONArray(failed))
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
                    val rawVisual = (aesthetic["visual_features"] as? List<*>)
                        ?.mapNotNull { (it as? Number)?.toDouble() }.orEmpty()
                    if (ArtworkVisualPreferenceModel.valid(rawVisual)) {
                        profile.put("artwork_visual_features_v1", JSONArray(rawVisual))
                    }
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

        val hasDetectedSubjects = (stages["character_recognition"]?.resultMap()
            ?.get("subjects") as? List<*>)?.isNotEmpty() == true
        val illustrationTags = resolveIllustrationTags(
            stages["tag_prediction"], hasDetectedSubjects,
        )
        val illustrationFeatureIds = illustrationTags.associate { it.tagId to it.confidence }
        val embedding = stages["embedding_generation"]?.get("embedding").doubleList()

        val hasCharacterKnowledge = response["knowledge_reconciliation_deferred"] != true && knowledge.hasCharacters()
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
        val detectedCount = (recognitionResult["detected_subject_count"] as? Number)?.toInt() ?: rawSubjectCount
        val trustedSpecies = if (rawSubjectCount == 1 && detectedCount == 1) fusion.currentImageCanonicalTags(imageId)
            .filter { it.source == "manual" && it.tagId.startsWith("SP") }
            .map { it.tagId }.toSet() else emptySet()
        val onlySubject = (recognitionResult["subjects"] as? List<*>)?.singleOrNull() as? Map<*, *>
        val onlyIndex = (onlySubject?.get("subject_index") as? Number)?.toInt() ?: 0
        val trustedSpeciesBySubject = if (trustedSpecies.isNotEmpty())
            mapOf(onlyIndex to trustedSpecies) else emptyMap()
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
                // The primary crop owns its appearance and identity evidence.
                // Whole-image wardrobe tags/embeddings can belong to a second
                // person and must not contaminate recognition.
                illustrationFeatureIds = emptyMap(),
                imageEmbedding = emptyList(),
                verifiedSpeciesBySubject = trustedSpeciesBySubject,
            )
        } else {
            emptyList()
        }

        // Every detected person retains a separate Fusion subject record and
        // independent character-specific observations. Prominence influences
        // only the primary name/route, never whether someone is tagged.
        val originalDecisions = profile.optJSONObject("manual_original_clusters") ?: JSONObject()
        val originalIndices = originalDecisions.keys().asSequence().mapNotNull(String::toIntOrNull).toSet()
        val scopedResolvedSubjects = resolvedSubjects.sortedBy { it.subjectIndex }.map { subject ->
            if (subject.subjectIndex in originalIndices) subject.copy(character = null, candidates = emptyList(), confidence = 0.0)
            else subject
        }
        val inferredSpeciesBySubject = scopedResolvedSubjects.mapNotNull { subject ->
            subject.inferredSpeciesId?.let { subject.subjectIndex to it }
        }.toMap()
        profile.put("species_candidates_by_subject", JSONArray(
            scopedResolvedSubjects.filter { it.speciesReviewCandidates.isNotEmpty() }.map {
                mapOf("subject_index" to it.subjectIndex,
                    "accepted_inferred_species_id" to it.inferredSpeciesId.orEmpty(),
                    "candidates" to it.speciesReviewCandidates)
            }
        ))

        fun primaryObservations(
            observed: Map<String, Double>, verified: KnowledgeCharacterEntry?,
        ): Map<String, Double> = observed.filterKeys { id ->
            // The owner excludes automatic Male SX labels. Neither a penis,
            // another figure, nor an identity reference authorizes that tag.
            // A human can still explicitly correct the primary subject.
            val sex = liveTaxonomy.tagById(id)?.takeIf { it.family == "SX" }
                ?.name?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()
            val prohibited = sex in setOf("male", "male character", "masculine")
            !prohibited
        }

        // Visual observations and taxonomy tagging do not require a complete
        // character roster or character sheets. Keep the subject evidence for
        // later identity matching, even when no character can be resolved.
        val preliminarySubjectRecords = if (scopedResolvedSubjects.isNotEmpty()) {
            scopedResolvedSubjects.map { subject ->
                SubjectResolutionRecord(
                    subjectIndex = subject.subjectIndex,
                    prominence = subject.prominence,
                    observations = primaryObservations(
                        subject.observations, subject.character,
                    ),
                    candidates = subject.candidates,
                    characterId = subject.character?.characterId,
                    confidence = subject.confidence,
                    status = if (subject.resolved) "resolved" else "review",
                )
            }
        } else {
            (recognitionResult["subjects"] as? List<*>).orEmpty()
                .mapIndexedNotNull { index, value ->
                val raw = (value as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
                    k?.toString()?.let { key -> v?.let { key to it } }
                }?.toMap() ?: return@mapIndexedNotNull null
                SubjectResolutionRecord(
                    subjectIndex = (raw["subject_index"] as? Number)?.toInt() ?: index,
                    prominence = ((raw["prominence"] as? Number)?.toDouble() ?: 1.0).coerceIn(0.0, 1.0),
                    observations = primaryObservations(
                        CanonicalObservationPolicy.sanitize(
                            raw, trustedSpeciesBySubject[
                                (raw["subject_index"] as? Number)?.toInt() ?: index
                            ].orEmpty(),
                        ) { id -> subjectDescriptor(id) }, null,
                    ),
                    candidates = emptyList(),
                    characterId = null,
                    confidence = 0.0,
                    status = "waiting_for_knowledge",
                )
            }
        }
        // Explicit per-character Review corrections override repeat model
        // suggestions without leaking to a different person in the image.
        val manualSubjectDecisions = profile.optJSONObject("manual_subject_tag_decisions")
        val correctedIdentities = fusion.correctedSubjectIdentities(imageId) - originalIndices
        val subjectRecords = preliminarySubjectRecords.map { observation ->
            val subject = if (observation.subjectIndex in originalIndices) observation.copy(
                characterId = null, candidates = emptyList(), status = "original_character", confidence = 1.0,
            ) else correctedIdentities[observation.subjectIndex]?.let { id ->
                observation.copy(characterId = id, status = "corrected", confidence = 1.0)
            } ?: observation
            val decisions = manualSubjectDecisions?.optJSONObject(subject.subjectIndex.toString())
            if (decisions == null) subject else {
                val corrected = subject.observations.toMutableMap()
                decisions.keys().forEach { id ->
                    if (liveTaxonomy.tagById(id) == null) return@forEach
                    if (decisions.optBoolean(id, false)) corrected[id] = 1.0
                    else corrected.remove(id)
                }
                subject.copy(observations = corrected)
            }
        }
        if (response["partial_tag_recovery"] != true) {
            fusion.replaceSubjects(imageId, subjectRecords)
        } else {
            val completed = (recognitionResult["subjects"] as? List<*>).orEmpty()
                .mapNotNull { it as? Map<*, *> }
                .filter { it["recognition_status"] == "observed" && it["unverified_overlap"] != true }
                .mapNotNull { (it["subject_index"] as? Number)?.toInt() }.toSet()
            fusion.replaceSubjects(imageId, subjectRecords.filter { it.subjectIndex in completed },
                preserveOtherSubjects = true)
        }

        val resolvedCharacters = scopedResolvedSubjects
            .filter { it.resolved || it.subjectIndex in correctedIdentities }
            .mapNotNull { subject ->
                val manualId = correctedIdentities[subject.subjectIndex]
                val character = (if (manualId == null) subject.character else knowledge.resolveCharacter(manualId))
                    ?: return@mapNotNull null
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
                    "confidence" to if (manualId != null) 1.0 else subject.confidence,
                )
            }

        val triage = ContentTriagePolicy.decide(
            observation = routingObservation.copy(
                identityCandidatesAvailable = scopedResolvedSubjects.any { it.candidates.isNotEmpty() },
            ),
            resolvedCharacterCount = resolvedCharacters.size,
            characterKnowledgeReady = hasCharacterKnowledge,
            learnedJunkKinds = junkLearning.learnedKinds(),
        )
        val trainedJunkRoute = triage.learnedJunkRoute && triage.junkKind.isNotBlank()

        when {
            triage.requiresReview -> {
                reviewReasons += triage.reason
            }
            triage.route == ContentTriagePolicy.ROUTE_CHARACTER && hasCharacterKnowledge -> {
                scopedResolvedSubjects.filter { !it.resolved && it.subjectIndex !in correctedIdentities && it.subjectIndex !in originalIndices }.forEach { subject ->
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
                        source = if (inferredSpeciesBySubject[subject.subjectIndex] == tag.id)
                            "vision_sa_species_inference" else "vision",
                    )
                }
            }
        }
        // Character reference profiles are identity evidence, not visual
        // observations. Never copy unseen canonical attributes into image tags.
        val userRejected = rejectedTagIds(profile)
        val scopedRejections = profile.optJSONObject("rejected_tags_by_scope")
        val approvedAiTags = canonicalTags.filterNot {
            it.source != "manual" && (it.tagId.uppercase() in userRejected ||
                scopedRejections?.optJSONObject(it.scope)?.optBoolean(it.tagId.uppercase()) == true)
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
            // A family is replaceable only when this analysis actually
            // adjudicated it. An unsupported/unscored family is NOT evidence
            // of absence: never erase prior recognized tags merely because
            // a model omitted that family on this image.
            fusion.replaceCanonicalTags(
                imageId = imageId,
                tags = approvedAiTags,
                replaceFamilies = adjudicatedFamilies,
            )
        }
        // Rebuild the legacy index from the committed canonical+manual Fusion
        // observations. Earlier rejected vision tags must not survive in text.
        refreshLegacyCanonicalTags(imageId)

        // Canonical appearance is an advancement gate, not a tag-count hint.
        // Audit the primary character's actual persisted pixels and verified
        // manual corrections after replacement, never the whole-image head.
        // A missing detector result must never make a character profile
        // automatically complete. Only confidently non-character scenery
        // is exempt from character appearance, not from RT classification.
        val requiresCharacterProfile = !trainedJunkRoute &&
            (subjectRecords.isNotEmpty() ||
                triage.route != ContentTriagePolicy.ROUTE_SCENERY)
        // Completeness must be checked PER PERSON. A blue-haired second
        // character cannot fill a missing HC for a red-haired first character.
        // Read each subject's own persisted observation map, not the union
        // in fusion_image_canonical_tags used by image-wide search.
        val subjectProfiles = auditStoredSubjects(imageId, profile)
        val mandatoryProfile = if (requiresCharacterProfile) {
            if (subjectProfiles.isNotEmpty()) {
                SubjectProfileCompleteness.combine(subjectProfiles.map { it.second })
            } else CharacterProfileCompleteness.audit(
                emptyList(), liveTaxonomy.allTags(),
            )
        } else null
        val saved = fusion.currentImageCanonicalTags(imageId)
        val rtCount = saved.count { tag ->
            tag.tagId.startsWith("RT") && tag.scope != "character_reference"
        }
        val contentRatingComplete = rtCount == 1
        val canonicalProfileComplete = !requiresCharacterProfile ||
            (subjectProfiles.isNotEmpty() && subjectProfiles.all { it.second.profileComplete })
        val modelCoverage = CanonicalFamilyAdvancement.modelCoverage(
            stages["tag_prediction"]?.resultMap()?.get("family_coverage"),
            recognitionResult["subjects"],
        )
        val advancement = CanonicalFamilyAdvancement.evaluate(
            taxonomy = liveTaxonomy.allTags(),
            savedTags = saved, appearanceAudit = mandatoryProfile,
            contentRatingCount = rtCount, modelCoverage = modelCoverage,
        )
        val missingCanonicalFamilies = FusionTagFamilyOrder.ordered(
            (if (subjectProfiles.isEmpty()) mandatoryProfile?.let { listOf(it) }.orEmpty()
                else subjectProfiles.map { it.second }).flatMap { report ->
                report.missingFamilies + report.conflictingFamilies +
                    (if (report.missingRequiredSpeciesTraits.isEmpty() &&
                        report.incompatibleSpeciesTraits.isEmpty() &&
                        report.unlinkedSpeciesTraits.isEmpty()) emptyList() else listOf("SA"))
            } + (if (contentRatingComplete) emptyList() else listOf("RT")),
            characterFamilies = liveTaxonomy.allTags().filter(CharacterProfileCompleteness::isCharacterAttribute).map { it.family },
        )
        // This is the single authoritative advancement decision. A fast model
        // cannot bypass it, and a UI button cannot fabricate missing values.
        val canonicalAdvanceComplete = canonicalProfileComplete &&
            contentRatingComplete && advancement.complete
        profile.put("canonical_family_steps",
            JSONArray(advancement.steps.map { it.toMap() }))
        profile.put("canonical_families_passed", advancement.requiredPassed)
        profile.put("canonical_families_required", advancement.requiredTotal)
        profile.put("canonical_profile_complete", canonicalAdvanceComplete)
        profile.put("canonical_missing_families", JSONArray(missingCanonicalFamilies))
        profile.put("canonical_first_missing_family",
            advancement.firstBlockedFamily.ifBlank {
                missingCanonicalFamilies.firstOrNull().orEmpty()
            })
        profile.put("canonical_advance_blocked", !canonicalAdvanceComplete)
        profile.put("canonical_review_needed",
            if (requiresCharacterProfile) "primary_character" else "content_rating")
        writeProfile(imageId, profile)
        if (!canonicalAdvanceComplete && !trainedJunkRoute) {
            reviewReasons += "Mandatory Fusion validation blocked at " +
                missingCanonicalFamilies.firstOrNull().orEmpty().ifBlank {
                    "contradictory species traits or canonical values"
                } + ": " +
                missingCanonicalFamilies.joinToString(", ").ifBlank {
                    "taxonomy contradiction"
                } + ". Complete the first missing family before approving the artwork."
        }

        val imageUri = repository.searchByImageId(imageId)?.get("uri")?.toString().orEmpty()
        // The original implementation attached the full artwork vector to
        // EACH character in a group, contaminating future character matches.
        // Only an accepted subject's own vector may become candidate evidence.
        for (subject in scopedResolvedSubjects.filter(ResolvedSubject::resolved)) {
            if (subject.subjectIndex in correctedIdentities) continue
            val character = subject.character ?: continue
            val vector = subject.visualEmbedding
            if (vector.isEmpty()) continue
            fusion.addCharacterEvidence(
                characterId = character.characterId,
                imageId = imageId,
                evidenceKind = "auto_resolved_subject",
                sourceUri = imageUri,
                embedding = vector,
                attributes = subjectRecords.firstOrNull {
                    it.subjectIndex == subject.subjectIndex
                }?.observations.orEmpty(),
                validated = false,
                weight = 0.45,
            )
        }

        val singleOriginal = subjectRecords.singleOrNull()?.subjectIndex?.takeIf {
            it in originalIndices && maxOf(rawSubjectCount, routingObservation.peopleCount) == 1
        }
        if (originalIndices.isNotEmpty() && singleOriginal == null) {
            reviewReasons += "Confirm group organization for the manually identified Original Character."
        }
        val unresolved = singleOriginal == null && triage.route == ContentTriagePolicy.ROUTE_CHARACTER &&
            hasCharacterKnowledge &&
            resolvedCharacters.size < scopedResolvedSubjects.size
        val rolePending = refreshRoleReview(imageId, profile)
        val queuedForReview = reviewReasons.isNotEmpty() || triage.requiresReview || unresolved
        if (queuedForReview) {
            fusion.queueReview(
                imageId = imageId,
                reviewType = if (triage.junkKind.isNotBlank()) "junk_candidate"
                    else "character_resolution",
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
                    "species_candidates_by_subject" to scopedResolvedSubjects.map {
                        mapOf("subject_index" to it.subjectIndex,
                            "inferred_species_id" to it.inferredSpeciesId.orEmpty(),
                            "candidates" to it.speciesReviewCandidates)
                    },
                    "content_route" to triage.route,
                    "junk_kind" to triage.junkKind,
                    "junk_route_folders" to triage.folderSegments,
                    "junk_route_filename_prefix" to triage.filenamePrefix,
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
            "all_subjects_resolved" to (hasCharacterKnowledge && scopedResolvedSubjects.isNotEmpty() && resolvedCharacters.size == scopedResolvedSubjects.size),
            "canonical_profile_complete" to canonicalAdvanceComplete,
            "content_rating_complete" to contentRatingComplete,
            "canonical_next_required_family" to missingCanonicalFamilies.firstOrNull().orEmpty(),
            "canonical_missing_families" to missingCanonicalFamilies,
            "accepted_character_id" to primary["character_id"].orEmptyText(),
            "accepted_character_name" to primary["canonical_name"].orEmptyText(),
            "accepted_series_code" to primary["series_code"].orEmptyText(),
            "accepted_series_name" to primary["series_name"].orEmptyText(),
            "original_character" to (singleOriginal != null),
            "original_character_cluster_id" to singleOriginal?.let { originalDecisions.optString(it.toString()) }.orEmpty(),
            "content_route" to triage.route,
            "junk_kind" to triage.junkKind,
            "junk_routing_calibrated" to trainedJunkRoute,
            "content_route_reason" to triage.reason,
            "content_route_terminal" to (triage.terminal && singleOriginal == null),
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
            "queued_for_review" to (queuedForReview || rolePending),
            "review_reasons" to reviewReasons.distinct(),
            "canonical_tag_ids" to canonicalTags.map(CanonicalTagObservation::tagId).distinct(),
        )
    }

    fun applyReviewCorrection(imageId: Int, payload: Map<String, Any>): Map<String, Any> =
        database.writableDatabase.transaction { applyIdentityDecision(imageId, payload) }

    private fun applyIdentityDecision(imageId: Int, payload: Map<String, Any>): Map<String, Any> {
        val originalCharacter = payload["original_character"].asBoolean()
        val profile = readProfile(imageId)
        val embedding = profile.optJSONArray("embedding").doubleList()

        if (originalCharacter) {
            val existing = readSubjectObservations(imageId)
            val index = (payload["subject_index"] as? Number)?.toInt()
                ?: existing.singleOrNull()?.first ?: if (existing.isEmpty()) 0 else -1
            if (index < 0 || existing.isNotEmpty() && existing.none { it.first == index }) {
                return mapOf("accepted" to false, "queued_for_review" to true,
                    "review_reasons" to listOf("Select the subject for this Original Character."))
            }
            val observations = payload["attribute_ids"].stringDoubleMap().ifEmpty {
                existing.firstOrNull { it.first == index }?.second.orEmpty()
            }
            fusion.retractReviewedIdentityEvidence(imageId)
            val clusterId = fusion.createOrAssignOriginalCharacterCluster(
                imageId = imageId,
                subjectIndex = index,
                attributes = observations,
                embedding = if (existing.size == 1 && profile.optInt("native_total_subject_count", 1) == 1)
                    embedding else emptyList(),
            )
            fusion.markOriginalSubject(imageId, index)
            val originals = profile.optJSONObject("manual_original_clusters") ?: JSONObject()
            originals.put(index.toString(), clusterId)
            profile.put("manual_original_clusters", originals)
            writeProfile(imageId, profile)
            val group = maxOf(existing.size, profile.optInt("native_total_subject_count", existing.size)) > 1
            return mapOf(
                "accepted" to true,
                "manual_correction" to true,
                "original_character" to !group,
                "original_character_cluster_id" to clusterId,
                "resolved_characters" to emptyList<Map<String, Any>>(),
                "queued_for_review" to group,
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

        // A Review correction can name several different subjects. Never
        // truncate a multi-character correction to its first identity.
        val characters = requested.map { raw ->
            knowledge.resolveCharacter(raw)
                ?: return mapOf(
                    "accepted" to false,
                    "queued_for_review" to true,
                    "review_reasons" to listOf("Unknown Character Knowledge identity: " + raw),
                )
        }

        val existingSubjects = readSubjectObservations(imageId)
        val explicitSubjectIndex = (payload["subject_index"] as? Number)?.toInt()
        val correctionIndices = when {
            characters.size == 1 && (explicitSubjectIndex != null || existingSubjects.size <= 1) ->
                listOf(explicitSubjectIndex ?: existingSubjects.singleOrNull()?.first ?: 0)
            characters.size > 1 && existingSubjects.isNotEmpty() && characters.size == existingSubjects.size ->
                existingSubjects.take(characters.size).map { it.first }
            else -> return mapOf(
                "accepted" to false, "queued_for_review" to true,
                "review_reasons" to listOf("One independently detected subject is required for each corrected identity."),
            )
        }
        if (existingSubjects.isNotEmpty() &&
            correctionIndices.any { index -> existingSubjects.none { it.first == index } }) {
            return mapOf(
                "accepted" to false, "queued_for_review" to true,
                "review_reasons" to listOf("Requested subject index was not found."),
            )
        }
        val corrected = characters.mapIndexed { index, character ->
            val subjectIndex = correctionIndices[index]
            val observations = existingSubjects.firstOrNull { it.first == subjectIndex }?.second.orEmpty()
            val series = knowledge.seriesByCode(character.primarySeriesCode)
                ?: return mapOf(
                    "accepted" to false,
                    "queued_for_review" to true,
                    "review_reasons" to listOf("Character has no valid canonical series: " + character.characterId),
                )
            mapOf(
                "subject_index" to subjectIndex,
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

        val records = corrected.map { row ->
            val index = (row["subject_index"] as Number).toInt()
            SubjectResolutionRecord(
                subjectIndex = index,
                prominence = (row["prominence"] as Number).toDouble(),
                observations = existingSubjects.firstOrNull { it.first == index }?.second.orEmpty(),
                candidates = listOf(row),
                characterId = row["character_id"].toString(),
                confidence = 1.0,
                status = "corrected",
            )
        }
        if (!fusion.correctSubjectIdentities(imageId, records)) {
            return mapOf("accepted" to false, "queued_for_review" to true,
                "review_reasons" to listOf("Subject correction could not be persisted without altering other subjects."))
        }

        profile.optJSONObject("manual_original_clusters")?.let { originals ->
            correctionIndices.forEach { originals.remove(it.toString()) }
            writeProfile(imageId, profile)
        }
        val imageUri = repository.searchByImageId(imageId)?.get("uri")?.toString().orEmpty()
        // Review-corrected identity is durable even without a sheet, but a
        // group-wide image embedding is NOT a validated reference for each
        // character. Only a recognized, single-subject image can bootstrap
        // future automatic visual matching.
        // Only single-subject whole-image corrections generate these references.
        // A replacement identity or newly discovered group retracts the old contribution.
        fusion.retractReviewedIdentityEvidence(imageId)
        val verifiedReference = CharacterSubjectEvidencePolicy.verifiedSingleSubjectReferenceEmbedding(
            correctedIdentityCount = corrected.size,
            observedSubjectCount = maxOf(existingSubjects.size,
                profile.optInt("native_total_subject_count", existingSubjects.size)),
            wholeImageEmbedding = embedding,
        )
        if (verifiedReference.isNotEmpty() && imageUri.isNotBlank()) {
            corrected.forEach { row ->
                val index = (row["subject_index"] as Number).toInt()
                fusion.addCharacterEvidence(
                    characterId = row["character_id"].toString(),
                    imageId = imageId,
                    evidenceKind = "review_correction",
                    sourceUri = imageUri,
                    embedding = verifiedReference,
                    attributes = existingSubjects.firstOrNull { it.first == index }?.second.orEmpty(),
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
            "all_subjects_resolved" to fusion.allSubjectIdentitiesResolved(imageId),
            "accepted_character_id" to corrected.first()["character_id"].toString(),
            "accepted_character_name" to corrected.first()["canonical_name"].toString(),
            "accepted_series_code" to corrected.first()["series_code"].toString(),
            "accepted_series_name" to corrected.first()["series_name"].toString(),
            "queued_for_review" to false,
        )
    }

    private fun resolveIllustrationTags(
        stage: Map<String, Any>?,
        hasDetectedSubjects: Boolean,
    ): List<CanonicalTagObservation> {
        // Only a trained classifier's actual scored labels may become
        // automatic tags; free-form model words or fabricated constants cannot.
        // Match the trusted fixed-head mapper's model-specific acceptance
        // threshold. An unconditional 0.82 here silently discarded most
        // valid WD ViT v3 observations, leaving Fusion and gallery untagged.
        return FusionClassifierTagGate.acceptedScores(stage?.resultMap().orEmpty())
            .mapNotNull { (id, confidence) ->
                val tag = liveTaxonomy.tagById(id) ?: return@mapNotNull null
                // Character anatomy stays in its individual subject record.
                // Illustration-wide families (e.g. GE/EN/FR/CA) must not be
                // suppressed just because people were detected in the scene.
                // With no detected person, verified object/pose tags may remain
                // image-level evidence rather than being discarded.
                if (!IllustrationTagScopePolicy.isImageLevel(tag, hasDetectedSubjects)) {
                    return@mapNotNull null
                }
                CanonicalTagObservation(
                    tagId = tag.id, tagName = tag.name, scope = "illustration",
                    confidence = confidence, source = "vision_classifier",
                )
            }
    }

    /** Reusable strict advancement gate, also enforced during manual review. */
    private fun auditStoredSubjects(imageId: Int, profile: JSONObject): List<Pair<Int, CharacterProfileCompleteness.Report>> {
        val taxonomy = liveTaxonomy.allTags()
        val reports = readSubjectObservations(imageId).map { (index, observations) ->
            index to CharacterProfileCompleteness.audit(observations.keys, taxonomy)
        }
        profile.put("canonical_subject_profiles", JSONArray(reports.map { (index, report) ->
            mapOf("subject_index" to index, "profile" to report.toMap())
        }))
        return reports
    }

    fun canonicalProfile(imageId: Int): CharacterProfileCompleteness.Report {
        val taxonomy = liveTaxonomy.allTags()
        val subjects = readSubjectObservations(imageId)
        if (subjects.isNotEmpty()) {
            return SubjectProfileCompleteness.combine(subjects.map { (_, observations) ->
                CharacterProfileCompleteness.audit(observations.keys, taxonomy)
            })
        }
        // Older images may contain image-level manually curated tags without
        // subject records. Retain that path until subject migration is complete.
        val ids = fusion.currentImageCanonicalTags(imageId)
            .filter { tag ->
                tag.scope == "character_observed" &&
                    liveTaxonomy.tagById(tag.tagId)?.let(
                        CharacterProfileCompleteness::isCharacterAttribute
                    ) == true
            }.map { it.tagId }
        return CharacterProfileCompleteness.audit(ids, taxonomy)
    }

    /**
     * Correct one visible observation using a canonical two-letter ID.
     * User rejection is persisted in the image profile so a later automated
     * pass cannot silently reintroduce the same hallucinated image tag.
     * The immutable Character Knowledge taxonomy is never edited.
     */
    private fun refreshRoleReview(imageId: Int, profile: JSONObject): Boolean {
        if (!profile.has("image_role_choices") && !profile.has("native_subject_family_coverage")) return false
        val manualImage by lazy { fusion.currentImageCanonicalTags(imageId).filter {
            it.scope == "illustration" && it.source == "manual"
        }.mapTo(hashSetOf()) { it.tagId } }
        val rows = ArrayList<Map<String,Any>>()
        val reasons = mutableListOf<String>()
        fun append(groups: JSONArray?, subject: Int?) {
            val decisions = if (subject == null) profile.optJSONObject("rejected_tags_by_scope")?.optJSONObject("illustration")
                else profile.optJSONObject("manual_subject_tag_decisions")?.optJSONObject(subject.toString())
            for (index in 0 until (groups?.length() ?: 0)) {
                val group = groups?.optJSONObject(index) ?: continue
                val raw = group.optJSONArray("choices") ?: continue
                val choices = (0 until raw.length()).mapNotNull { raw.optJSONObject(it) }
                if (choices.any { val id = it.optString("id")
                    if (subject == null) id in manualImage else decisions?.optBoolean(id, false) == true }) continue
                val open = choices.filterNot { decisions?.has(it.optString("id")) == true }.map {
                    mapOf("id" to it.optString("id"), "name" to it.optString("name"), "parent" to it.optString("parent"))
                }
                if (open.isNotEmpty()) {
                    rows += mapOf("subject_index" to (subject ?: -1), "channel" to group.optString("channel"), "choices" to open)
                    val scope = subject?.let { "Character " + (it + 1) } ?: "Image"
                    reasons += scope + " · " + group.optString("channel") + " (" + open.joinToString(" / ") { it.getValue("id") } + ")"
                }
            }
        }
        if (profile.optInt("native_total_subject_count", 0) == 0) append(profile.optJSONArray("image_role_choices"), null)
        val audits = profile.optJSONArray("native_subject_family_coverage")
        for (index in 0 until (audits?.length() ?: 0)) {
            val row = audits?.optJSONObject(index) ?: continue
            val subject = row.optInt("subject_index",-1)
            if (subject >= 0) append(row.optJSONArray("role_review_choices"), subject)
        }
        if (rows.isEmpty()) {
            database.writableDatabase.execSQL("UPDATE review_items SET status='auto_resolved',last_updated_ms=? WHERE image_id=? AND review_type='visual_tag_role' AND status='pending'",
                arrayOf(System.currentTimeMillis(),imageId))
            return false
        } else {
            val cause = reasons.sorted().joinToString("; ")
            fusion.queueReview(imageId, "visual_tag_role", "Confirm the canonical role: " + cause,
                mapOf("role_choices" to rows))
            return database.readableDatabase.rawQuery("SELECT 1 FROM review_items WHERE image_id=? AND review_type=\'visual_tag_role\' AND status=\'pending\' LIMIT 1", arrayOf(imageId.toString())).use { it.moveToFirst() }
        }
    }

    fun overlappingSubject(imageId: Int, subjectIndex: Int): Boolean {
        val regions = readProfile(imageId).optJSONArray("native_subject_regions") ?: return false
        return (0 until regions.length()).any { i ->
            val row = regions.optJSONObject(i)
            row?.optInt("subject_index", -1) == subjectIndex && row.optBoolean("unverified_overlap")
        }
    }

    fun correctImageTag(
        imageId: Int,
        requestedId: String,
        include: Boolean,
        subjectIndex: Int? = null,
    ): ManualObservationTarget? {
        val db = database.writableDatabase
        db.beginTransactionNonExclusive()
        try {
            val result = applyManualTag(imageId, requestedId, include, subjectIndex) ?: return null
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun applyManualTag(imageId: Int, requestedId: String, include: Boolean,
                               subjectIndex: Int?): ManualObservationTarget? {
        val tag = liveTaxonomy.tagById(requestedId) ?: return null
        if (!tag.id.equals(requestedId.trim(), ignoreCase = true)) return null
        val profile = readProfile(imageId)
        val rejectedArray = profile.optJSONArray("user_rejected_tag_ids")
        val rejected = (0 until (rejectedArray?.length() ?: 0))
            .mapNotNull { index -> rejectedArray?.optString(index)?.takeIf(String::isNotBlank) }
            .map(String::uppercase).toMutableSet()
        val subjects = readSubjectObservations(imageId)
        val destination = ManualObservationTarget.resolve(tag, subjectIndex, subjects.map { it.first }) ?: return null
        val target = destination.subjectIndex
        if (target != null) {
            val removed = fusion.editSubjectTag(
                imageId, target, tag.id, include,
                exclusiveFamily = tag.family in CharacterProfileCompleteness.exclusiveFamilies,
            ) ?: return null

            // Corrections are scoped to a particular person's tags and kept
            // for explicit reanalysis, rather than globally rejecting tags
            // that may be correct on another person in the same picture.
            val perSubject = profile.optJSONObject("manual_subject_tag_decisions") ?: JSONObject()
            val decisions = perSubject.optJSONObject(target.toString()) ?: JSONObject()
            decisions.put(tag.id, include)
            removed.filterNot { it == tag.id && include }.forEach {
                decisions.put(it, false)
            }
            perSubject.put(target.toString(), decisions)
            profile.put("manual_subject_tag_decisions", perSubject)

            // The image-wide search index is the union of individual subject
            // records. Never delete a tag still observed on another character.
            val otherTags = readSubjectObservations(imageId)
                .flatMap { it.second.keys }.toSet()
            (removed + if (include) emptySet() else setOf(tag.id)).forEach { oldId ->
                if (oldId !in otherTags) fusion.removeScopedCanonicalTag(imageId, oldId, destination.scope)
            }
            if (include) {
                fusion.mergeCanonicalTags(imageId, listOf(CanonicalTagObservation(
                    tagId = tag.id, tagName = tag.name,
                    scope = "character_observed", confidence = 1.0,
                    source = "manual",
                )))
            }
        } else {
            // Original image-wide/legacy correction path; no detected person.
            val byScope = profile.optJSONObject("rejected_tags_by_scope") ?: JSONObject()
            val scopeRejected = byScope.optJSONObject(destination.scope) ?: JSONObject()
            if (include) {
                rejected.remove(tag.id.uppercase())
                scopeRejected.remove(tag.id.uppercase())
            } else scopeRejected.put(tag.id.uppercase(), true)
            byScope.put(destination.scope, scopeRejected)
            profile.put("rejected_tags_by_scope", byScope)
            if (include) {
                if (tag.family in CharacterProfileCompleteness.exclusiveFamilies ||
                    tag.family == "RT") {
                    fusion.currentImageCanonicalTags(imageId)
                        .filter { (it.scope == destination.scope || (tag.family == "RT" && it.scope == "illustration")) &&
                            it.tagId.take(2) == tag.family && (it.tagId != tag.id || it.scope != destination.scope) }
                        .forEach { old ->
                            fusion.removeScopedCanonicalTag(imageId, old.tagId, old.scope)
                            if (old.tagId != tag.id) scopeRejected.put(old.tagId.uppercase(), true)
                        }
                }
                fusion.removeScopedCanonicalTag(imageId, tag.id, destination.scope)
                fusion.mergeCanonicalTags(imageId, listOf(CanonicalTagObservation(
                    tagId = tag.id, tagName = tag.name,
                    scope = destination.scope,
                    confidence = 1.0, source = "manual",
                )))
            } else {
                fusion.removeScopedCanonicalTag(imageId, tag.id, destination.scope)
                if (tag.family == "RT") fusion.removeScopedCanonicalTag(imageId, tag.id, "illustration")
            }
        }

        profile.put("user_rejected_tag_ids", JSONArray(rejected.sorted()))
        profile.put("last_manual_tag_edit_ms", System.currentTimeMillis())
        refreshLegacyCanonicalTags(imageId)
        val reports = auditStoredSubjects(imageId, profile)
        val audit = when {
            reports.isNotEmpty() -> SubjectProfileCompleteness.combine(reports.map { it.second })
            profile.optString("canonical_review_needed") == "content_rating" -> null
            else -> canonicalProfile(imageId)
        }
        val currentTags = fusion.currentImageCanonicalTags(imageId)
        val rtCount = currentTags.count {
            it.tagId.startsWith("RT") && it.scope != "character_reference"
        }
        val rated = rtCount == 1
        val advancement = CanonicalFamilyAdvancement.evaluate(
            taxonomy = liveTaxonomy.allTags(),
            savedTags = currentTags, appearanceAudit = audit,
            contentRatingCount = rtCount,
            modelCoverage = buildMap {
                val steps = profile.optJSONArray("canonical_family_steps")
                for (index in 0 until (steps?.length() ?: 0)) {
                    val step = steps?.optJSONObject(index) ?: continue
                    put(step.optString("family"), step.optString("diagnostic"))
                }
            },
        )
        val pending = FusionTagFamilyOrder.ordered(
            audit?.missingFamilies.orEmpty() + audit?.conflictingFamilies.orEmpty() +
                (if (audit == null || audit.missingRequiredSpeciesTraits.isEmpty() &&
                    audit.incompatibleSpeciesTraits.isEmpty() &&
                    audit.unlinkedSpeciesTraits.isEmpty()) emptyList() else listOf("SA")) +
                (if (rated) emptyList() else listOf("RT")),
            characterFamilies = liveTaxonomy.allTags().filter(CharacterProfileCompleteness::isCharacterAttribute).map { it.family },
        )
        profile.put("canonical_profile_complete",
            audit?.profileComplete != false && rated && advancement.complete)
        profile.put("canonical_family_steps",
            JSONArray(advancement.steps.map { it.toMap() }))
        profile.put("canonical_families_passed", advancement.requiredPassed)
        profile.put("canonical_families_required", advancement.requiredTotal)
        profile.put("canonical_missing_families", JSONArray(pending))
        profile.put("canonical_first_missing_family",
            advancement.firstBlockedFamily.ifBlank { pending.firstOrNull().orEmpty() })
        profile.put("canonical_advance_blocked",
            audit?.profileComplete == false || !rated || !advancement.complete)
        refreshRoleReview(imageId, profile)
        writeProfile(imageId, profile)
        return destination
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
                    // The newest explicit user scores are authoritative.
                    // A long-running background classifier must not erase
                    // precise overall/facet edits performed during analysis.
                    for (key in listOf("manual_rating", "manual_rating_authoritative",
                        "manual_rating_updated_at_ms", "precision_overall_set",
                        "precision_overall_score", "precision_overall_updated_at_ms",
                        "rating_dimensions_v2", "rating_dimensions_updated_at_ms")) {
                        if (latest.has(key)) metadata.put(key, latest.get(key))
                        else metadata.remove(key)
                    }
                    // Cached HD evidence can be graded before AI starts.
                    // Keep that real score unless this run produced a newer
                    // valid aesthetic observation of its own.
                    for (key in listOf("aesthetic_score", "aesthetic_model_source",
                        "automatic_artwork_rating", "artwork_visual_features_v1")) {
                        if (!metadata.has(key) && latest.has(key)) {
                            metadata.put(key, latest.get(key))
                        }
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
