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
    private val knowledge: KnowledgeDatabase,
    private val fusion: FusionResolutionStore,
) {
    private val characterResolver = CharacterResolver(knowledge, fusion)

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
        val blockingFailures = if (response.containsKey("blocking_failed_stages")) {
            response["blocking_failed_stages"]
        } else {
            response["failed_stages"]
        }
        blockingFailures.mapList().forEach { failed ->
            val stage = failed.optText("stage_type").ifBlank { "stage" }
            val message = failed.optText("message").ifBlank { failed.optText("status") }
            reviewReasons += "Required automation stage failed: " + stage +
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
            stages["normalization"]?.resultMap()?.optText("text")?.takeIf { it.isNotBlank() }?.let {
                profile.put("normalized_context", it)
            }
            profile.put("ai_workflow_updated_at_ms", System.currentTimeMillis())
            writeProfile(imageId, profile)
        }

        val illustrationTags = resolveIllustrationTags(stages["tag_prediction"])
        val illustrationFeatureIds = illustrationTags.associate { it.tagId to it.confidence }
        val embedding = stages["embedding_generation"]?.get("embedding").doubleList()

        val hasCharacterKnowledge = knowledge.hasCharacters()
        val recognitionResult = stages["character_recognition"]?.resultMap().orEmpty()
        val resolvedSubjects = if (hasCharacterKnowledge && recognitionResult.isNotEmpty()) {
            characterResolver.resolve(
                recognitionResult = recognitionResult,
                illustrationFeatureIds = illustrationFeatureIds,
                imageEmbedding = embedding,
            )
        } else {
            emptyList()
        }

        val subjectRecords = resolvedSubjects.map { subject ->
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
        fusion.replaceSubjects(imageId, subjectRecords)

        if (hasCharacterKnowledge) {
            if (resolvedSubjects.isEmpty()) {
                reviewReasons += "No character subject could be resolved from observable canonical attributes."
            } else {
                resolvedSubjects.filterNot(ResolvedSubject::resolved).forEach { subject ->
                    reviewReasons += "Subject " + (subject.subjectIndex + 1) +
                        " is below the character confidence threshold (" +
                        "%.2f".format(subject.confidence) + ")."
                }
            }
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
                    "display_name" to character.displayName,
                    "form_name" to character.formName,
                    "entry_type" to character.entryType,
                    "parent_character_id" to character.parentCharacterId,
                    "series_code" to series.code,
                    "series_name" to series.name,
                    "confidence" to subject.confidence,
                )
            }

        val canonicalTags = mutableListOf<CanonicalTagObservation>()
        canonicalTags += illustrationTags
        resolvedSubjects.forEach { subject ->
            subject.observations.forEach { (id, confidence) ->
                knowledge.resolveTag(id)?.let { tag ->
                    canonicalTags += CanonicalTagObservation(
                        tagId = tag.id,
                        tagName = tag.name,
                        scope = "character_observed",
                        confidence = confidence,
                        source = "vision",
                    )
                }
            }
            subject.character?.let { character ->
                character.attributeIds.forEach { id ->
                    knowledge.resolveTag(id)?.let { tag ->
                        canonicalTags += CanonicalTagObservation(
                            tagId = tag.id,
                            tagName = tag.name,
                            scope = "character_profile",
                            confidence = subject.confidence,
                            source = "character_knowledge",
                        )
                    }
                }
                (character.weaponIds + character.outfitIds).forEach { id ->
                    knowledge.resolveTag(id)?.let { tag ->
                        canonicalTags += CanonicalTagObservation(
                            tagId = tag.id,
                            tagName = tag.name,
                            scope = "character_reference",
                            confidence = subject.confidence,
                            source = "character_knowledge",
                        )
                    }
                }
            }
        }
        fusion.replaceCanonicalTags(imageId, canonicalTags)
        // Flatten only actual image tags plus stable physical character profile
        // tags into the legacy search tag table. Signature weapons/outfits remain
        // Character Knowledge evidence unless they are visibly detected in this image.
        repository.setTags(
            imageId,
            canonicalTags
                .filter { it.scope != "character_reference" }
                .map(CanonicalTagObservation::tagName)
                .distinct(),
        )

        val imageUri = repository.searchByImageId(imageId)?.get("uri")?.toString().orEmpty()
        // A whole-image embedding is valid character evidence only when the
        // image contains exactly one recognized subject. Reusing the same vector
        // for several subjects pollutes every character prototype with the other
        // people and creates a self-reinforcing false-match loop.
        if (embedding.isNotEmpty() && resolvedSubjects.size == 1 && resolvedSubjects.single().resolved) {
            val subject = resolvedSubjects.single()
            val character = subject.character
            if (character != null) {
                fusion.addCharacterEvidence(
                    characterId = character.characterId,
                    imageId = imageId,
                    evidenceKind = "auto_resolved_image",
                    sourceUri = imageUri,
                    embedding = embedding,
                    attributes = subject.observations,
                    validated = false,
                    weight = 0.45,
                )
            }
        }

        val unresolved = hasCharacterKnowledge &&
            (resolvedSubjects.isEmpty() || resolvedSubjects.any { !it.resolved })
        val queuedForReview = reviewReasons.isNotEmpty() || unresolved
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
                "display_name" to character.displayName,
                "form_name" to character.formName,
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
        val evidenceEmbedding = if (corrected.size == 1) embedding else emptyList()
        corrected.forEachIndexed { index, row ->
            fusion.addCharacterEvidence(
                characterId = row["character_id"].toString(),
                imageId = imageId,
                evidenceKind = "review_correction",
                sourceUri = imageUri,
                embedding = evidenceEmbedding,
                attributes = existingSubjects.getOrNull(index)?.second.orEmpty(),
                validated = true,
                weight = 1.0,
            )
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
        val tags = stage?.resultMap()?.stringList("tags").orEmpty()
        return tags.mapNotNull { raw ->
            val tag = knowledge.resolveTag(raw) ?: return@mapNotNull null
            CanonicalTagObservation(
                tagId = tag.id,
                tagName = tag.name,
                scope = "illustration",
                confidence = 0.80,
                source = "vision",
            )
        }.distinctBy(CanonicalTagObservation::tagId)
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
        val values = ContentValues().apply {
            put("image_id", imageId)
            put("source_uri", repository.searchByImageId(imageId)?.get("uri")?.toString().orEmpty())
            put("metadata_json", metadata.toString())
            put("updated_at_ms", System.currentTimeMillis())
        }
        database.writableDatabase.insertWithOnConflict(
            FusionDatabaseSchema.TABLE_IMAGE_PROFILES,
            null,
            values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
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
