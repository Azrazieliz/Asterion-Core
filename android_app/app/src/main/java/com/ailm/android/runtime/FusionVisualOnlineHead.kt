package com.ailm.android.runtime

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Small private, per-canonical-ID Fusion learner over the frozen WD pre-head
 * pixels. Neither a WD text label nor a character profile is a training target.
 * All supervision comes from explicitly edited visible image/subject tags.
 *
 * Cache entries can be evicted; the owner-supervision ledger keeps its own
 * fingerprint-bound vector so reversing a correction retrains from the remaining
 * examples rather than incorrectly subtracting one SGD update.
 */
internal object FusionVisualLinearMath {
    const val FEATURE_WIDTH = 768
    const val MIN_CLASS_EXAMPLES = 12
    private const val READY_PRECISION = 0.90
    private const val READY_RECALL = 0.60
    private const val ADMISSION = 0.75

    data class Example(val source: String, val positive: Boolean, val pixels: FloatArray)
    data class Head(
        val weights: FloatArray,
        val bias: Double,
        val positives: Int,
        val negatives: Int,
        val precision: Double,
        val recall: Double,
        val ready: Boolean,
    )

    fun normalise(raw: FloatArray): FloatArray? {
        if (raw.size != FEATURE_WIDTH || raw.any { !it.isFinite() }) return null
        val squared = raw.sumOf { value -> value.toDouble() * value.toDouble() }
        if (!squared.isFinite() || squared < 1e-12) return null
        val norm = sqrt(squared)
        return FloatArray(raw.size) { i -> (raw[i] / norm).toFloat() }
    }

    fun predict(weights: FloatArray, bias: Double, raw: FloatArray): Double? {
        val input = normalise(raw) ?: return null
        if (weights.size != FEATURE_WIDTH || !bias.isFinite() || weights.any { !it.isFinite() }) return null
        var sum = bias
        for (i in input.indices) sum += input[i].toDouble() * weights[i]
        return if (sum.isFinite()) 1.0 / (1.0 + exp(-sum.coerceIn(-60.0, 60.0))) else null
    }

    /**
     * Deterministic source-separated training and validation. Model promotion
     * is withheld until both classes have enough independent corrections and
     * a disjoint validation slice reaches conservative acceptance.
     */
    fun fit(records: List<Example>): Head? {
        val distinct = records.distinctBy { it.source }
        if (distinct.size != records.size || distinct.any { normalise(it.pixels) == null }) return null
        val positives = distinct.filter { it.positive }.sortedBy { it.source }
        val negatives = distinct.filterNot { it.positive }.sortedBy { it.source }
        if (positives.size < MIN_CLASS_EXAMPLES || negatives.size < MIN_CLASS_EXAMPLES) return null
        val heldOut = positives.filterIndexed { i, _ -> i % 4 == 0 } +
            negatives.filterIndexed { i, _ -> i % 4 == 0 }
        val train = (positives.filterIndexed { i, _ -> i % 4 != 0 } +
            negatives.filterIndexed { i, _ -> i % 4 != 0 }).sortedBy { it.source }
        if (heldOut.count { it.positive } < 3 || heldOut.count { !it.positive } < 3 ||
            train.count { it.positive } < 5 || train.count { !it.positive } < 5) return null
        val data = train.map { (normalise(it.pixels) ?: return null) to it.positive }
        val weights = FloatArray(FEATURE_WIDTH)
        var bias = 0.0
        repeat(48) {
            for ((features, positive) in data) {
                var score = bias
                for (j in weights.indices) score += weights[j] * features[j]
                val probability = 1.0 / (1.0 + exp(-score.coerceIn(-60.0, 60.0)))
                val error = probability - if (positive) 1.0 else 0.0
                for (j in weights.indices) {
                    weights[j] = (weights[j] - 0.10 * (error * features[j] + 0.0001 * weights[j])).toFloat()
                }
                bias -= 0.10 * error
            }
        }
        val results = heldOut.map {
            (predict(weights, bias, it.pixels) ?: return null) >= ADMISSION to it.positive
        }
        val tp = results.count { it.first && it.second }
        val fp = results.count { it.first && !it.second }
        val fn = results.count { !it.first && it.second }
        val precision = tp.toDouble() / (tp + fp).coerceAtLeast(1)
        val recall = tp.toDouble() / (tp + fn).coerceAtLeast(1)
        return Head(weights, bias, positives.size, negatives.size, precision, recall,
            ready = precision >= READY_PRECISION && recall >= READY_RECALL)
    }
}

/**
 * On-device private training ledger: one decision per (image, subject, Fusion ID).
 * The image source and model package fingerprint must match at use time.
 * Never uploads personal images, regions or embeddings to CI/GitHub.
 */
internal class FusionVisualOnlineHead(private val db: SQLiteDatabase) {
    private val canonical = Regex("^[A-Z]{2}[0-9]{2,}(?:-[0-9]+)*$")
    private val hash = Regex("^[a-f0-9]{64}$")
    private var inserts = 0

    private fun sourceHash(imageId: Int): String? = db.rawQuery(
        "SELECT sha256 FROM fusion_image_fingerprints WHERE image_id=? LIMIT 1",
        arrayOf(imageId.toString()),
    ).use { rows -> if (rows.moveToFirst()) rows.getString(0)?.lowercase(Locale.ROOT) else null }
        ?.takeIf(hash::matches)

    private fun encode(features: FloatArray): ByteArray =
        ByteBuffer.allocate(features.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            features.forEach(::putFloat)
        }.array()

    private fun decode(bytes: ByteArray): FloatArray? {
        if (bytes.size != FusionVisualLinearMath.FEATURE_WIDTH * 4) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(FusionVisualLinearMath.FEATURE_WIDTH) { buf.float }
        return floats.takeIf { FusionVisualLinearMath.normalise(it) != null }
    }

    @Synchronized
    fun cache(imageId: Int, subjectIndex: Int?, modelHash: String, features: List<Double>): Boolean {
        if (imageId <= 0 || !hash.matches(modelHash) || features.size != FusionVisualLinearMath.FEATURE_WIDTH ||
            features.any { !it.isFinite() }) return false
        val source = sourceHash(imageId) ?: return false
        val raw = FloatArray(features.size) { features[it].toFloat() }
        if (FusionVisualLinearMath.normalise(raw) == null) return false
        val scope = subjectIndex ?: -1
        if (scope < -1) return false
        db.insertWithOnConflict("fusion_visual_feature_cache", null, ContentValues().apply {
            put("image_id", imageId)
            put("subject_index", scope)
            put("source_hash", source)
            put("encoder_hash", modelHash)
            put("feature", encode(raw))
            put("updated_ms", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (++inserts % 32 == 0) db.execSQL("""
            DELETE FROM fusion_visual_feature_cache WHERE rowid NOT IN (
                SELECT rowid FROM fusion_visual_feature_cache ORDER BY updated_ms DESC, image_id DESC LIMIT 2048)
        """.trimIndent())
        return true
    }

    /**
     * Only an explicit owner decision counts. If the source/model cache was
     * evicted or changed, remove any stale vote but do not invent new pixels.
     * Calling twice with the same decision never adds a second example.
     */
    @Synchronized
    fun correct(imageId: Int, subjectIndex: Int?, tagId: String, include: Boolean): Boolean {
        val id = tagId.trim().uppercase(Locale.ROOT)
        if (imageId <= 0 || subjectIndex != null && subjectIndex < 0 || !canonical.matches(id)) return false
        val scope = subjectIndex ?: -1
        val source = sourceHash(imageId)
        var modelHash: String? = null
        var vector: ByteArray? = null
        db.rawQuery(
            "SELECT source_hash,encoder_hash,feature FROM fusion_visual_feature_cache WHERE image_id=? AND subject_index=?",
            arrayOf(imageId.toString(), scope.toString()),
        ).use { rows ->
            if (rows.moveToFirst() && source != null && rows.getString(0) == source) {
                modelHash = rows.getString(1)
                vector = rows.getBlob(2)?.takeIf { decode(it) != null }
            }
        }
        db.beginTransaction()
        try {
            val knownEncoders = mutableSetOf<String>()
            db.rawQuery(
                "SELECT encoder_hash FROM fusion_visual_feedback WHERE image_id=? AND subject_index=? AND canonical_id=?",
                arrayOf(imageId.toString(), scope.toString(), id),
            ).use { rows -> while (rows.moveToNext()) knownEncoders += rows.getString(0) }
            db.delete("fusion_visual_feedback", "image_id=? AND subject_index=? AND canonical_id=?",
                arrayOf(imageId.toString(), scope.toString(), id))
            val encoder = modelHash
            val pixels = vector
            if (encoder != null && pixels != null && hash.matches(encoder)) {
                db.insertOrThrow("fusion_visual_feedback", null, ContentValues().apply {
                    put("image_id", imageId)
                    put("subject_index", scope)
                    put("canonical_id", id)
                    put("source_hash", source)
                    put("encoder_hash", encoder)
                    put("label", if (include) 1 else 0)
                    put("features", pixels)
                    put("updated_ms", System.currentTimeMillis())
                })
                knownEncoders += encoder
            }
            knownEncoders.forEach { retrain(id, scope, it) }
            db.setTransactionSuccessful()
            return encoder != null && pixels != null
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun forget(imageId: Int, subjectIndex: Int?, tagId: String) {
        val id = tagId.trim().uppercase(Locale.ROOT)
        if (imageId <= 0 || !canonical.matches(id) || subjectIndex != null && subjectIndex < 0) return
        val scope = subjectIndex ?: -1
        db.beginTransaction()
        try {
            val hashes = mutableSetOf<String>()
            db.rawQuery(
                "SELECT encoder_hash FROM fusion_visual_feedback WHERE image_id=? AND subject_index=? AND canonical_id=?",
                arrayOf(imageId.toString(), scope.toString(), id),
            ).use { rows -> while (rows.moveToNext()) hashes += rows.getString(0) }
            db.delete("fusion_visual_feedback", "image_id=? AND subject_index=? AND canonical_id=?",
                arrayOf(imageId.toString(), scope.toString(), id))
            hashes.forEach { retrain(id, scope, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun retrain(id: String, subject: Int, encoder: String) {
        val rows = mutableListOf<FusionVisualLinearMath.Example>()
        db.rawQuery("""
            SELECT f.source_hash,f.label,f.features
            FROM fusion_visual_feedback f
            JOIN fusion_image_fingerprints fp ON fp.image_id=f.image_id AND fp.sha256=f.source_hash
            WHERE f.canonical_id=? AND f.subject_index=? AND f.encoder_hash=?
            ORDER BY f.source_hash, f.image_id LIMIT 256
        """.trimIndent(), arrayOf(id, subject.toString(), encoder)).use { cursor ->
            while (cursor.moveToNext()) {
                val features = decode(cursor.getBlob(2)) ?: continue
                rows += FusionVisualLinearMath.Example(
                    cursor.getString(0), cursor.getInt(1) == 1, features)
            }
        }
        val head = FusionVisualLinearMath.fit(rows)
        db.delete("fusion_visual_online_heads", "canonical_id=? AND subject_index=? AND encoder_hash=?",
            arrayOf(id, subject.toString(), encoder))
        if (head != null) {
            db.insertOrThrow("fusion_visual_online_heads", null, ContentValues().apply {
                put("canonical_id", id)
                put("subject_index", subject)
                put("encoder_hash", encoder)
                put("dimensions", head.weights.size)
                put("weights", encode(head.weights))
                put("bias", head.bias)
                put("positives", head.positives)
                put("negatives", head.negatives)
                put("validation_precision", head.precision)
                put("validation_recall", head.recall)
                put("enabled", if (head.ready) 1 else 0)
                put("updated_ms", System.currentTimeMillis())
            })
        }
    }

    /** Only validated rows for the current frozen encoder and visual scope. */
    @Synchronized
    fun predict(subjectIndex: Int?, modelHash: String, features: List<Double>): Map<String, Double> {
        if (!hash.matches(modelHash) || features.size != FusionVisualLinearMath.FEATURE_WIDTH ||
            features.any { !it.isFinite() }) return emptyMap()
        val raw = FloatArray(features.size) { features[it].toFloat() }
        if (FusionVisualLinearMath.normalise(raw) == null) return emptyMap()
        val scope = subjectIndex ?: -1
        val candidates = linkedMapOf<String, Double>()
        db.rawQuery("""
            SELECT canonical_id,weights,bias FROM fusion_visual_online_heads
            WHERE subject_index=? AND encoder_hash=? AND enabled=1
        """.trimIndent(), arrayOf(scope.toString(), modelHash)).use { rows ->
            while (rows.moveToNext()) {
                val score = decode(rows.getBlob(1))?.let { weights ->
                    FusionVisualLinearMath.predict(weights, rows.getDouble(2), raw)
                }
                if (score != null) candidates[rows.getString(0)] = score
            }
        }
        return candidates
    }
}
