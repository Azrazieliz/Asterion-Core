package com.ailm.android.runtime

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

internal data class CanonicalTagObservation(
    val tagId: String,
    val tagName: String,
    val scope: String,
    val confidence: Double,
    val source: String,
)

internal data class SubjectResolutionRecord(
    val subjectIndex: Int,
    val prominence: Double,
    val observations: Map<String, Double>,
    val candidates: List<Map<String, Any>>,
    val characterId: String?,
    val confidence: Double,
    val status: String,
)

internal data class ImageFingerprintMatch(
    val imageId: Int,
    val uri: String,
    val filename: String,
    val perceptualHash: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
)

internal class FusionResolutionStore(
    private val database: LocalDatabase,
) {
    fun listAutomationImageIds(forceAll: Boolean = false): List<Int> {
        val predicate = if (forceAll) {
            "i.active = 1"
        } else {
            """
            i.active = 1 AND (
                fp.image_id IS NULL OR
                s.image_id IS NULL OR
                s.state IN ('pending', 'retry_required', 'failed') OR
                (
                    s.state = 'complete' AND
                    COALESCE(i.modified_at_ms, i.last_modified_ms, 0) > COALESCE(s.source_modified_at_ms, 0)
                )
            )
            """.trimIndent()
        }
        val sql = """
            SELECT i.image_id
            FROM images i
            LEFT JOIN fusion_automation_state s ON s.image_id = i.image_id
            LEFT JOIN ${FusionDatabaseSchema.TABLE_IMAGE_FINGERPRINTS} fp ON fp.image_id = i.image_id
            WHERE $predicate
            ORDER BY i.imported_order ASC, i.image_id ASC
        """.trimIndent()
        return buildList {
            database.readableDatabase.rawQuery(sql, emptyArray()).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getInt(0))
            }
        }
    }

    fun requeueLegacyReviewPendingWithoutSubjects(): Int {
        return database.writableDatabase.update(
            "fusion_automation_state",
            ContentValues().apply {
                put("state", "pending")
                put("pipeline_complete", 0)
                put("organization_complete", 0)
                put("needs_review", 0)
                put("last_error", "Legacy incomplete automation result queued for a semantic retry.")
                put("updated_at_ms", System.currentTimeMillis())
            },
            """
            state = 'review_pending'
            AND NOT EXISTS (
                SELECT 1
                FROM fusion_image_subjects subjects
                WHERE subjects.image_id = fusion_automation_state.image_id
            )
            """.trimIndent(),
            emptyArray(),
        )
    }

    fun resetWaitingForKnowledge() {
        database.writableDatabase.execSQL(
            """
            UPDATE fusion_automation_state
            SET state = 'pending',
                pipeline_complete = 0,
                organization_complete = 0,
                needs_review = 0,
                last_error = '',
                updated_at_ms = ?
            WHERE state = 'waiting_for_knowledge'
            """.trimIndent(),
            arrayOf(System.currentTimeMillis()),
        )
    }

    fun upsertImageFingerprint(
        imageId: Int,
        sha256: String,
        perceptualHash: String,
        width: Int,
        height: Int,
        sizeBytes: Long,
    ) {
        database.writableDatabase.insertWithOnConflict(
            FusionDatabaseSchema.TABLE_IMAGE_FINGERPRINTS,
            null,
            ContentValues().apply {
                put("image_id", imageId)
                put("sha256", sha256.trim().lowercase())
                put("perceptual_hash", perceptualHash.trim().lowercase())
                put("pixel_width", width.coerceAtLeast(0))
                put("pixel_height", height.coerceAtLeast(0))
                put("source_size_bytes", sizeBytes.coerceAtLeast(0L))
                put("updated_at_ms", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun findExactDuplicate(imageId: Int, sha256: String): ImageFingerprintMatch? {
        val clean = sha256.trim().lowercase()
        if (clean.isBlank()) return null
        return database.readableDatabase.rawQuery(
            """
            SELECT f.image_id, i.uri, i.filename, f.perceptual_hash,
                   f.pixel_width, f.pixel_height, f.source_size_bytes
            FROM ${FusionDatabaseSchema.TABLE_IMAGE_FINGERPRINTS} f
            JOIN images i ON i.image_id = f.image_id
            WHERE f.sha256 = ? AND f.image_id < ? AND i.active = 1
            ORDER BY f.image_id ASC
            LIMIT 1
            """.trimIndent(),
            arrayOf(clean, imageId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else ImageFingerprintMatch(
                imageId = cursor.getInt(0),
                uri = cursor.getString(1),
                filename = cursor.getString(2),
                perceptualHash = cursor.getString(3).orEmpty(),
                width = cursor.getInt(4),
                height = cursor.getInt(5),
                sizeBytes = cursor.getLong(6),
            )
        }
    }

    fun perceptualDuplicateCandidates(
        imageId: Int,
        width: Int,
        height: Int,
        limit: Int = 500,
    ): List<ImageFingerprintMatch> {
        if (width <= 0 || height <= 0) return emptyList()
        val bounded = limit.coerceIn(1, 5000)
        return buildList {
            database.readableDatabase.rawQuery(
                """
                SELECT f.image_id, i.uri, i.filename, f.perceptual_hash,
                       f.pixel_width, f.pixel_height, f.source_size_bytes
                FROM ${FusionDatabaseSchema.TABLE_IMAGE_FINGERPRINTS} f
                JOIN images i ON i.image_id = f.image_id
                WHERE f.image_id < ? AND i.active = 1
                  AND f.pixel_width = ? AND f.pixel_height = ?
                  AND f.perceptual_hash != ''
                ORDER BY f.image_id ASC
                LIMIT ?
                """.trimIndent(),
                arrayOf(imageId.toString(), width.toString(), height.toString(), bounded.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    add(
                        ImageFingerprintMatch(
                            imageId = cursor.getInt(0),
                            uri = cursor.getString(1),
                            filename = cursor.getString(2),
                            perceptualHash = cursor.getString(3).orEmpty(),
                            width = cursor.getInt(4),
                            height = cursor.getInt(5),
                            sizeBytes = cursor.getLong(6),
                        ),
                    )
                }
            }
        }
    }

    fun markAutomationState(
        imageId: Int,
        state: String,
        pipelineComplete: Boolean,
        organizationComplete: Boolean,
        needsReview: Boolean,
        lastError: String = "",
    ) {
        val modifiedAt = database.readableDatabase.rawQuery(
            "SELECT COALESCE(modified_at_ms, last_modified_ms, 0) FROM images WHERE image_id = ? LIMIT 1",
            arrayOf(imageId.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
        database.writableDatabase.insertWithOnConflict(
            "fusion_automation_state",
            null,
            ContentValues().apply {
                put("image_id", imageId)
                put("state", state)
                put("source_modified_at_ms", modifiedAt)
                put("pipeline_complete", if (pipelineComplete) 1 else 0)
                put("organization_complete", if (organizationComplete) 1 else 0)
                put("needs_review", if (needsReview) 1 else 0)
                put("last_error", lastError)
                put("updated_at_ms", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun replaceSubjects(imageId: Int, subjects: List<SubjectResolutionRecord>) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.delete("fusion_image_subjects", "image_id = ?", arrayOf(imageId.toString()))
            subjects.forEach { subject ->
                db.insertOrThrow(
                    "fusion_image_subjects",
                    null,
                    ContentValues().apply {
                        put("image_id", imageId)
                        put("subject_index", subject.subjectIndex)
                        put("prominence", subject.prominence)
                        put("observations_json", JSONObject(subject.observations).toString())
                        put("candidates_json", JSONArray(subject.candidates).toString())
                        put("resolved_character_id", subject.characterId)
                        put("resolution_confidence", subject.confidence)
                        put("status", subject.status)
                        put("updated_at_ms", System.currentTimeMillis())
                    },
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun replaceCanonicalTags(imageId: Int, tags: List<CanonicalTagObservation>) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.delete("fusion_image_canonical_tags", "image_id = ?", arrayOf(imageId.toString()))
            tags.distinctBy { Triple(it.tagId, it.scope, it.source) }.forEach { tag ->
                db.insertWithOnConflict(
                    "fusion_image_canonical_tags",
                    null,
                    ContentValues().apply {
                        put("image_id", imageId)
                        put("tag_id", tag.tagId)
                        put("tag_name", tag.tagName)
                        put("tag_scope", tag.scope)
                        put("confidence", tag.confidence.coerceIn(0.0, 1.0))
                        put("source", tag.source)
                        put("added_at_ms", System.currentTimeMillis())
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun markCharacterSheetPending(characterId: String, sourceUri: String) {
        database.writableDatabase.insertWithOnConflict(
            "fusion_character_sheet_index_state",
            null,
            ContentValues().apply {
                put("character_id", characterId)
                put("source_uri", sourceUri)
                put("status", "pending")
                put("attempts", 0)
                put("last_error", "")
                put("updated_at_ms", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun pendingCharacterSheets(limit: Int = 50): List<Map<String, String>> {
        val bounded = limit.coerceIn(1, 250)
        return buildList {
            database.readableDatabase.rawQuery(
                """
                SELECT character_id, source_uri
                FROM fusion_character_sheet_index_state
                WHERE status IN ('pending', 'retry')
                ORDER BY updated_at_ms ASC, character_id ASC
                LIMIT ?
                """.trimIndent(),
                arrayOf(bounded.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    add(
                        mapOf(
                            "character_id" to cursor.getString(0),
                            "source_uri" to cursor.getString(1),
                        ),
                    )
                }
            }
        }
    }

    fun characterSheetIndexRemaining(): Int = database.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM fusion_character_sheet_index_state WHERE status IN ('pending', 'retry')",
        emptyArray(),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    fun completeCharacterSheetIndex(characterId: String, sourceUri: String) {
        database.writableDatabase.update(
            "fusion_character_sheet_index_state",
            ContentValues().apply {
                put("status", "indexed")
                put("source_uri", sourceUri)
                put("last_error", "")
                put("updated_at_ms", System.currentTimeMillis())
            },
            "character_id = ?",
            arrayOf(characterId),
        )
    }

    fun failCharacterSheetIndex(characterId: String, error: String, retryable: Boolean = true) {
        val attempts = database.readableDatabase.rawQuery(
            "SELECT attempts FROM fusion_character_sheet_index_state WHERE character_id = ? LIMIT 1",
            arrayOf(characterId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        database.writableDatabase.update(
            "fusion_character_sheet_index_state",
            ContentValues().apply {
                put("status", if (retryable && attempts < 3) "retry" else "failed")
                put("attempts", attempts + 1)
                put("last_error", error.take(500))
                put("updated_at_ms", System.currentTimeMillis())
            },
            "character_id = ?",
            arrayOf(characterId),
        )
    }

    fun hasCharacterEvidence(
        characterId: String,
        evidenceKind: String,
        sourceUri: String,
    ): Boolean = database.readableDatabase.rawQuery(
        """
        SELECT EXISTS(
            SELECT 1
            FROM fusion_character_visual_evidence
            WHERE character_id = ? AND evidence_kind = ? AND source_uri = ?
            LIMIT 1
        )
        """.trimIndent(),
        arrayOf(characterId, evidenceKind, sourceUri),
    ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 }

    fun addCharacterEvidence(
        characterId: String,
        imageId: Int?,
        evidenceKind: String,
        sourceUri: String,
        embedding: List<Double>,
        attributes: Map<String, Double>,
        validated: Boolean,
        weight: Double,
    ) {
        val db = database.writableDatabase
        if (imageId == null) {
            // SQLite UNIQUE permits multiple NULL image_ids. Canonical sheet
            // evidence is instead idempotent by character + kind + source path.
            db.delete(
                "fusion_character_visual_evidence",
                "character_id = ? AND evidence_kind = ? AND source_uri = ?",
                arrayOf(characterId, evidenceKind, sourceUri),
            )
        }
        db.insertWithOnConflict(
            "fusion_character_visual_evidence",
            null,
            ContentValues().apply {
                put("character_id", characterId)
                if (imageId == null) putNull("image_id") else put("image_id", imageId)
                put("evidence_kind", evidenceKind)
                put("source_uri", sourceUri)
                put("embedding_json", JSONArray(embedding).toString())
                put("attributes_json", JSONObject(attributes).toString())
                put("validated", if (validated) 1 else 0)
                put("weight", weight)
                put("added_at_ms", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun replaceCanonicalSheetEvidence(
        characterId: String,
        sourceUri: String,
        embedding: List<Double>,
    ) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.delete(
                "fusion_character_visual_evidence",
                "character_id = ? AND evidence_kind = 'canonical_sheet'",
                arrayOf(characterId),
            )
            addCharacterEvidence(
                characterId = characterId,
                imageId = null,
                evidenceKind = "canonical_sheet",
                sourceUri = sourceUri,
                embedding = embedding,
                attributes = emptyMap(),
                validated = true,
                weight = 1.35,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun visualSimilarity(characterId: String, queryEmbedding: List<Double>): Double? {
        if (queryEmbedding.isEmpty()) return null
        val scores = mutableListOf<Pair<Double, Double>>()
        database.readableDatabase.rawQuery(
            """
            SELECT embedding_json, validated, weight
            FROM fusion_character_visual_evidence
            WHERE character_id = ? AND embedding_json != '[]'
            """.trimIndent(),
            arrayOf(characterId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val embedding = parseDoubleList(cursor.getString(0))
                if (embedding.size != queryEmbedding.size || embedding.isEmpty()) continue
                val similarity = cosine(queryEmbedding, embedding)
                val validationWeight = if (cursor.getInt(1) == 1) 1.0 else 0.45
                scores += similarity to (cursor.getDouble(2).coerceAtLeast(0.05) * validationWeight)
            }
        }
        if (scores.isEmpty()) return null
        val best = scores.sortedByDescending { it.first * it.second }.take(4)
        val denominator = best.sumOf { it.second }.coerceAtLeast(0.0001)
        return (best.sumOf { it.first.coerceIn(-1.0, 1.0) * it.second } / denominator)
            .coerceIn(0.0, 1.0)
    }

    fun queueReview(
        imageId: Int,
        reviewType: String,
        reason: String,
        payload: Map<String, Any>,
    ) {
        val imageUri = database.readableDatabase.rawQuery(
            "SELECT uri FROM images WHERE image_id = ? LIMIT 1",
            arrayOf(imageId.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" }
        if (imageUri.isBlank()) return

        val values = ContentValues().apply {
            put("image_uri", imageUri)
            put("image_id", imageId)
            put("status", "pending")
            put("review_type", reviewType)
            put("reason", reason)
            put("payload_json", JSONObject(payload).toString())
            put("last_updated_ms", System.currentTimeMillis())
        }
        val changed = database.writableDatabase.update(
            "review_items",
            values,
            "image_uri = ?",
            arrayOf(imageUri),
        )
        if (changed == 0) {
            values.put("correction_json", "{}")
            database.writableDatabase.insertWithOnConflict(
                "review_items",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    fun resolveAutomationReviews(imageId: Int) {
        database.writableDatabase.update(
            "review_items",
            ContentValues().apply {
                put("status", "auto_resolved")
                put(
                    "correction_json",
                    JSONObject(
                        mapOf(
                            "automatic_resolution" to true,
                            "reason" to "A later complete automation run resolved this item.",
                        ),
                    ).toString(),
                )
                put("last_updated_ms", System.currentTimeMillis())
            },
            "image_id = ? AND status = 'pending' AND review_type IN ('runtime_failure', 'character_resolution', 'organization_failure')",
            arrayOf(imageId.toString()),
        )
    }

    fun getReviewItem(reviewId: Long): Map<String, Any>? = database.readableDatabase.rawQuery(
        """
        SELECT review_id, image_uri, COALESCE(image_id, 0), status, review_type,
               COALESCE(reason, ''), payload_json, correction_json, last_updated_ms
        FROM review_items
        WHERE review_id = ?
        LIMIT 1
        """.trimIndent(),
        arrayOf(reviewId.toString()),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        mapOf(
            "id" to cursor.getLong(0).toString(),
            "path" to cursor.getString(1),
            "image_id" to cursor.getInt(2),
            "status" to cursor.getString(3),
            "review_type" to cursor.getString(4),
            "reason" to cursor.getString(5),
            "payload" to parseObject(cursor.getString(6)),
            "correction" to parseObject(cursor.getString(7)),
            "updated_at_ms" to cursor.getLong(8),
        )
    }

    fun completeReview(reviewId: Long, status: String, correction: Map<String, Any>) {
        database.writableDatabase.update(
            "review_items",
            ContentValues().apply {
                put("status", status)
                put("correction_json", JSONObject(correction).toString())
                put("last_updated_ms", System.currentTimeMillis())
            },
            "review_id = ?",
            arrayOf(reviewId.toString()),
        )
    }

    fun createOrAssignOriginalCharacterCluster(
        imageId: Int,
        subjectIndex: Int,
        attributes: Map<String, Double>,
        embedding: List<Double>,
    ): String {
        val existing = bestOriginalCharacterCluster(attributes, embedding)
        val clusterId = existing?.first?.takeIf { existing.second >= OC_CLUSTER_THRESHOLD }
            ?: nextOriginalCharacterClusterId()

        val db = database.writableDatabase
        val previous = readOriginalCluster(clusterId)
        val count = (previous?.get("image_count") as? Number)?.toInt() ?: 0
        val prototype = (previous?.get("embedding") as? List<*>)
            ?.mapNotNull { (it as? Number)?.toDouble() }
            .orEmpty()
        val updatedPrototype = averageEmbedding(prototype, count, embedding)

        db.insertWithOnConflict(
            "fusion_original_character_clusters",
            null,
            ContentValues().apply {
                put("cluster_id", clusterId)
                put("label", "Original Character " + clusterId.removePrefix("OC"))
                put("attributes_json", JSONObject(attributes).toString())
                put("prototype_embedding_json", JSONArray(updatedPrototype).toString())
                put("image_count", count + 1)
                put("updated_at_ms", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        db.insertWithOnConflict(
            "fusion_image_oc_clusters",
            null,
            ContentValues().apply {
                put("image_id", imageId)
                put("subject_index", subjectIndex)
                put("cluster_id", clusterId)
                put("confidence", existing?.second ?: 1.0)
                put("added_at_ms", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return clusterId
    }

    private fun bestOriginalCharacterCluster(
        attributes: Map<String, Double>,
        embedding: List<Double>,
    ): Pair<String, Double>? {
        var best: Pair<String, Double>? = null
        database.readableDatabase.rawQuery(
            "SELECT cluster_id, attributes_json, prototype_embedding_json FROM fusion_original_character_clusters",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val candidateAttributes = parseObject(cursor.getString(1))
                    .mapNotNull { (key, value) -> (value as? Number)?.toDouble()?.let { key to it } }
                    .toMap()
                val attrScore = attributeJaccard(attributes, candidateAttributes)
                val candidateEmbedding = parseDoubleList(cursor.getString(2))
                val visual = if (embedding.isNotEmpty() && embedding.size == candidateEmbedding.size) {
                    cosine(embedding, candidateEmbedding).coerceIn(0.0, 1.0)
                } else 0.0
                val score = if (visual > 0.0) 0.55 * attrScore + 0.45 * visual else attrScore
                if (best == null || score > best!!.second) best = cursor.getString(0) to score
            }
        }
        return best
    }

    private fun nextOriginalCharacterClusterId(): String {
        val max = database.readableDatabase.rawQuery(
            "SELECT cluster_id FROM fusion_original_character_clusters",
            emptyArray(),
        ).use { cursor ->
            var value = 0
            while (cursor.moveToNext()) {
                value = maxOf(value, cursor.getString(0).removePrefix("OC").toIntOrNull() ?: 0)
            }
            value
        }
        return "OC" + (max + 1).toString().padStart(6, '0')
    }

    private fun readOriginalCluster(clusterId: String): Map<String, Any>? = database.readableDatabase.rawQuery(
        "SELECT image_count, prototype_embedding_json FROM fusion_original_character_clusters WHERE cluster_id = ? LIMIT 1",
        arrayOf(clusterId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else mapOf(
            "image_count" to cursor.getInt(0),
            "embedding" to parseDoubleList(cursor.getString(1)),
        )
    }

    private fun parseDoubleList(raw: String): List<Double> = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            (array.opt(index) as? Number)?.toDouble()
        }
    }.getOrDefault(emptyList())

    private fun parseObject(raw: String): Map<String, Any> = runCatching {
        val obj = JSONObject(raw)
        buildMap {
            obj.keys().forEach { key -> obj.opt(key)?.let { put(key, it) } }
        }
    }.getOrDefault(emptyMap())

    private fun cosine(a: List<Double>, b: List<Double>): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (index in a.indices) {
            dot += a[index] * b[index]
            normA += a[index] * a[index]
            normB += b[index] * b[index]
        }
        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator <= 1e-12) 0.0 else dot / denominator
    }

    private fun attributeJaccard(a: Map<String, Double>, b: Map<String, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val union = a.keys + b.keys
        val intersection = a.keys.intersect(b.keys)
        return intersection.sumOf { minOf(a[it] ?: 0.0, b[it] ?: 0.0) } /
            union.sumOf { maxOf(a[it] ?: 0.0, b[it] ?: 0.0) }.coerceAtLeast(0.0001)
    }

    private fun averageEmbedding(existing: List<Double>, count: Int, incoming: List<Double>): List<Double> {
        if (incoming.isEmpty()) return existing
        if (existing.size != incoming.size || count <= 0) return incoming
        return incoming.indices.map { index ->
            (existing[index] * count.toDouble() + incoming[index]) / (count + 1).toDouble()
        }
    }

    companion object {
        private const val OC_CLUSTER_THRESHOLD = 0.78
    }
}
