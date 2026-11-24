package com.ailm.android.runtime

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.ailm.android.runtime.ai.LocalAiJson
import java.security.MessageDigest

/** Short-lived native head outputs used to resume interrupted analysis. Reapply
 * live Fusion rules, owner corrections and identity matching on every read. */
internal class VisualStageCheckpoint private constructor(
    private val db: LocalDatabase, private val imageId: Int, private val revision: String,
) {
    companion object {
        fun open(db: LocalDatabase, imageId: Int, source: String?, modelHash: String,
                 modelId: String, version: String, candidateLabels: List<String>): VisualStageCheckpoint? {
            val sha = Regex("[0-9a-fA-F]{64}")
            if (source == null || !sha.matches(source) || !sha.matches(modelHash)) return null
            val digest = MessageDigest.getInstance("SHA-256")
            for (component in listOf("wd-crops-v1", source, modelHash, modelId, version) + candidateLabels) {
                digest.update(component.toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
            }
            val key = digest.digest().joinToString("") { "%02x".format(it) }
            return VisualStageCheckpoint(db, imageId, key)
        }
    }

    fun read(stage: String): Map<String, Any>? {
        val raw = db.readableDatabase.rawQuery(
            "SELECT scores_json FROM fusion_visual_checkpoints WHERE image_id=? AND stage=? AND revision=?",
            arrayOf(imageId.toString(), stage, revision),
        ).use { if (it.moveToFirst()) it.getString(0) else null } ?: return null
        val result = LocalAiJson.decodeMap(raw)
        if ((result["scored_tags"] as? List<*>).isNullOrEmpty()) return null
        return mapOf("ok" to true, "raw_result" to mapOf("result" to result))
    }

    fun save(stage: String, scores: List<*>, features: List<*>? = null) {
        val payload = org.json.JSONObject().put("scored_tags", org.json.JSONArray(scores))
        val featureVector = FusionVisualLinearMath.readFeature(features)
        if (featureVector != null) {
            // The checkpoint already binds image fingerprint and model digest.
            // Save one small frozen feature vector, never the original pixels.
            payload.put("fusion_feature_vector", org.json.JSONArray(featureVector))
        }
        val json = payload.toString()
        if (json.toByteArray(Charsets.UTF_8).size > 512 * 1024) return
        db.writableDatabase.insertWithOnConflict("fusion_visual_checkpoints", null, ContentValues().apply {
            put("image_id", imageId)
            put("stage", stage)
            put("revision", revision)
            put("scores_json", json)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun finish() {
        db.writableDatabase.delete("fusion_visual_checkpoints", "image_id=? AND revision=?",
            arrayOf(imageId.toString(), revision))
    }
}
