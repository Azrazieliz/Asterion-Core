package com.ailm.android.runtime

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Additive restoration of EC / ET rows from the owner's canonical SE0031
 * taxonomy. It never renumbers IDs, overwrites rows, replaces the Fusion
 * database, or changes manually edited metadata. Run only after the normal
 * pre-migration backup, off the Android startup-animation thread.
 */
internal object FusionEyeCatalogRecovery {
    private const val ASSET = "fusion_eye_catalog_se0031.json"
    private const val SOURCE = "ASTERION_SE0031_BLEACH_INTEGRATED_20261023.xlsx / Taxonomy"
    private val canonical = Regex("^(EC|ET)[0-9]{3}$")

    fun reconcile(context: Context, database: LocalDatabase): Map<String, Int> {
        val json = JSONObject(context.assets.open(ASSET).bufferedReader().use { it.readText() })
        check(json.optString("source") == SOURCE) { "Unexpected eye taxonomy source" }
        val array = json.getJSONArray("entries")
        check(array.length() == 40) { "Canonical EC/ET catalog should contain exactly 40 rows" }
        val entries = (0 until array.length()).map { index -> array.getJSONObject(index) }
        val ids = entries.map { it.getString("id") }
        check(ids.toSet().size == 40 && ids.count { it.startsWith("EC") } == 27 &&
            ids.count { it.startsWith("ET") } == 13 && ids.all(canonical::matches)) {
            "Invalid canonical eye taxonomy IDs"
        }

        val db = database.writableDatabase
        val existing = linkedMapOf<String, String>()
        db.rawQuery(
            "SELECT tag_id,canonical_name FROM fusion_tags WHERE substr(tag_id,1,2) IN ('EC','ET')",
            emptyArray(),
        ).use { rows ->
            while (rows.moveToNext()) existing[rows.getString(0)] = rows.getString(1)
        }
        var inserted = 0
        var skippedExisting = 0
        var conflicting = 0
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for (entry in entries) {
                val id = entry.getString("id")
                val name = entry.getString("name").trim()
                val category = entry.getString("category").trim()
                check(name.isNotBlank() && category == if (id.startsWith("EC"))
                    "Eye Color" else "Eye Traits") { "Invalid $id taxonomy category" }
                val priorName = existing[id]
                if (priorName != null) {
                    skippedExisting++
                    if (!name.equals(priorName, ignoreCase = true)) {
                        // A user-authored canonical name wins. Never attach
                        // aliases from a different semantic value to that ID.
                        conflicting++
                        continue
                    }
                } else {
                    val values = ContentValues().apply {
                        put("tag_id", id)
                        put("canonical_name", name)
                        put("category", category)
                        putNull("parent_tag_id")
                        put("metadata_json", JSONObject(mapOf(
                            "source" to "se0031_eye_taxonomy_recovery",
                            "reference_id" to id,
                            "source_workbook" to SOURCE,
                        )).toString())
                        put("created_at_ms", now)
                        put("updated_at_ms", now)
                    }
                    if (db.insertWithOnConflict("fusion_tags", null, values,
                            SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
                        skippedExisting++
                        continue
                    }
                    inserted++
                }
                // Add missing canonical aliases even when the tag itself was
                // already present. Every insert uses CONFLICT_IGNORE so the
                // migration remains additive and idempotent across updates.
                val aliases = entry.optJSONArray("aliases") ?: continue
                for (index in 0 until aliases.length()) {
                    val alias = aliases.optString(index).trim()
                    if (alias.isBlank()) continue
                    val digest = MessageDigest.getInstance("SHA-256")
                        .digest((id + ":" + alias.lowercase()).toByteArray())
                        .take(8).joinToString("") { "%02x".format(it) }
                    db.insertWithOnConflict("fusion_tag_aliases", null, ContentValues().apply {
                        put("alias_id", "KA_" + id + "_" + digest)
                        put("tag_id", id)
                        put("alias_value", alias)
                        put("locale", "")
                        put("created_at_ms", now)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return mapOf(
            "restored" to inserted,
            "already_present" to skippedExisting,
            "existing_value_conflicts_preserved" to conflicting,
            "canonical_reference_count" to 40,
        )
    }
}
