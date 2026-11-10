package com.ailm.android.runtime

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * Idempotent, additive migration from the old reference Knowledge database.
 *
 * No source file is deleted and no existing Fusion record is replaced.
 * A transaction guarantees that partial records cannot be published. Original
 * CH/HC/... IDs and manual Fusion edits retain precedence.
 */
internal class FusionReferenceMigration(
    private val fusion: LocalDatabase,
    private val knowledge: KnowledgeDatabase,
) {
    private data class Series(val id: String, val name: String, val aliases: String)
    private data class Tag(
        val id: String, val name: String, val category: String,
        val parent: String, val aliases: String,
    )
    private data class Character(
        val id: String, val name: String, val series: String,
        val aliases: String, val attributes: String, val weapons: String,
        val outfits: String, val metadata: String, val sheetId: String,
    )
    private data class Sheet(val id: String, val characterId: String, val path: String, val sha: String)

    fun reconcile(): Map<String, Any> {
        val reference = knowledge.readableDatabase
        val series = mutableListOf<Series>()
        reference.rawQuery("SELECT series_code, canonical_name, aliases_json FROM knowledge_series",
            emptyArray()).use { rows ->
            while (rows.moveToNext()) series += Series(
                rows.getString(0).orEmpty(), rows.getString(1).orEmpty(), rows.getString(2).orEmpty(),
            )
        }
        val tags = mutableListOf<Tag>()
        reference.rawQuery(
            "SELECT tag_id, canonical_name, category, COALESCE(parent_tag_id, ''), aliases_json FROM knowledge_tags",
            emptyArray(),
        ).use { rows ->
            while (rows.moveToNext()) tags += Tag(
                rows.getString(0).orEmpty(), rows.getString(1).orEmpty(),
                rows.getString(2).orEmpty(), rows.getString(3).orEmpty(),
                rows.getString(4).orEmpty(),
            )
        }
        val characters = mutableListOf<Character>()
        reference.rawQuery(
            "SELECT character_id, canonical_name, primary_series_code, aliases_json, " +
                "attributes_json, canonical_weapons_json, canonical_outfits_json, metadata_json, sheet_asset_id " +
                "FROM knowledge_characters",
            emptyArray(),
        ).use { rows ->
            while (rows.moveToNext()) characters += Character(
                (0..8).map { rows.getString(it).orEmpty() }[0],
                rows.getString(1).orEmpty(), rows.getString(2).orEmpty(),
                rows.getString(3).orEmpty(), rows.getString(4).orEmpty(),
                rows.getString(5).orEmpty(), rows.getString(6).orEmpty(),
                rows.getString(7).orEmpty(), rows.getString(8).orEmpty(),
            )
        }
        val features = mutableListOf<Triple<String, String, Double>>()
        reference.rawQuery(
            "SELECT character_id, feature_id, canonical_weight FROM knowledge_character_features",
            emptyArray(),
        ).use { rows ->
            while (rows.moveToNext()) features += Triple(
                rows.getString(0).orEmpty(), rows.getString(1).orEmpty(), rows.getDouble(2),
            )
        }
        val sheets = mutableListOf<Sheet>()
        reference.rawQuery(
            "SELECT sheet_asset_id, character_id, asset_path, sha256 FROM knowledge_character_sheets",
            emptyArray(),
        ).use { rows ->
            while (rows.moveToNext()) sheets += Sheet(
                rows.getString(0).orEmpty(), rows.getString(1).orEmpty(),
                rows.getString(2).orEmpty(), rows.getString(3).orEmpty(),
            )
        }

        val db = fusion.writableDatabase
        var importedSeries = 0
        var importedTags = 0
        var importedChars = 0
        var importedSheets = 0
        val canonical = Regex("^[A-Z]{2}[0-9]{2,}$")
        val characterCode = Regex("^CH[0-9]{6}(?:-[0-9]+)?$")
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for (item in series) {
                if (item.id.isBlank() || item.name.isBlank()) continue
                val values = ContentValues().apply {
                    put("series_code", item.id)
                    put("canonical_title", item.name)
                    put("localized_title", item.name)
                    put("aliases_json", item.aliases.ifBlank { "[]" })
                    put("metadata_json", JSONObject(mapOf("source" to "legacy_reference_migration")).toString())
                    put("created_at_ms", now)
                    put("updated_at_ms", now)
                }
                if (db.insertWithOnConflict("fusion_series", null, values,
                        SQLiteDatabase.CONFLICT_IGNORE) != -1L) importedSeries++
            }

            // Parent IDs can point forward. Insert without parents, then bind
            // valid relationships only after every parent exists in Fusion.
            for (item in tags) {
                if (!canonical.matches(item.id) || item.name.isBlank()) continue
                val values = ContentValues().apply {
                    put("tag_id", item.id)
                    put("canonical_name", item.name)
                    put("category", item.category.ifBlank { item.id.take(2) })
                    putNull("parent_tag_id")
                    put("metadata_json", JSONObject(mapOf(
                        "source" to "legacy_reference_migration",
                        "reference_id" to item.id,
                    )).toString())
                    put("created_at_ms", now)
                    put("updated_at_ms", now)
                }
                if (db.insertWithOnConflict("fusion_tags", null, values,
                        SQLiteDatabase.CONFLICT_IGNORE) != -1L) importedTags++
            }
            for (item in tags) {
                if (!canonical.matches(item.id)) continue
                if (item.parent.isNotBlank() && item.parent != item.id &&
                    exists(db, "fusion_tags", "tag_id", item.id) &&
                    exists(db, "fusion_tags", "tag_id", item.parent)) {
                    // Existing user-authored parents win; only fill null.
                    db.execSQL(
                        "UPDATE fusion_tags SET parent_tag_id=? WHERE tag_id=? AND parent_tag_id IS NULL",
                        arrayOf(item.parent, item.id),
                    )
                }
                for (alias in parseArray(item.aliases)) {
                    val clean = alias.trim()
                    if (clean.isBlank() || !exists(db, "fusion_tags", "tag_id", item.id)) continue
                    val digest = MessageDigest.getInstance("SHA-256")
                        .digest((item.id + ":" + clean.lowercase(Locale.ROOT)).toByteArray())
                        .take(8).joinToString("") { "%02x".format(it) }
                    val values = ContentValues().apply {
                        put("alias_id", "KA_" + item.id + "_" + digest)
                        put("tag_id", item.id)
                        put("alias_value", clean)
                        put("locale", "")
                        put("created_at_ms", now)
                    }
                    db.insertWithOnConflict("fusion_tag_aliases", null, values,
                        SQLiteDatabase.CONFLICT_IGNORE)
                }
            }

            for (item in characters) {
                if (!characterCode.matches(item.id) || item.name.isBlank()) continue
                val profile = JSONObject()
                    .put("aliases", JSONArray(item.aliases.ifBlank { "[]" }))
                    .put("taxonomy_ids", JSONArray(item.attributes.ifBlank { "[]" }))
                    .put("weapons", JSONArray(item.weapons.ifBlank { "[]" }))
                    .put("outfits", JSONArray(item.outfits.ifBlank { "[]" }))
                    .put("sheet_asset_id", item.sheetId)
                val values = ContentValues().apply {
                    put("character_id", item.id)
                    if (item.series.isNotBlank() &&
                        exists(db, "fusion_series", "series_code", item.series)) {
                        put("primary_series_code", item.series)
                    } else putNull("primary_series_code")
                    put("canonical_name", item.name)
                    put("localized_name", item.name)
                    put("recognition_profile_json", profile.toString())
                    put("metadata_json", item.metadata.ifBlank { "{}" })
                    put("created_at_ms", now)
                    put("updated_at_ms", now)
                }
                if (db.insertWithOnConflict("fusion_characters", null, values,
                        SQLiteDatabase.CONFLICT_IGNORE) != -1L) importedChars++
                if (exists(db, "fusion_characters", "character_id", item.id)) {
                    for (alias in parseArray(item.aliases)) {
                        val normalized = alias.trim().replace(Regex("\\s+"), " ")
                        if (normalized.isBlank()) continue
                        val aliasValues = ContentValues().apply {
                            put("character_id", item.id)
                            put("alias_value", normalized)
                        }
                        db.insertWithOnConflict("fusion_character_aliases", null,
                            aliasValues, SQLiteDatabase.CONFLICT_IGNORE)
                    }
                }
            }
            for ((characterId, featureId, weight) in features) {
                if (!exists(db, "fusion_characters", "character_id", characterId) ||
                    !exists(db, "fusion_tags", "tag_id", featureId)) continue
                val values = ContentValues().apply {
                    put("character_id", characterId)
                    put("feature_id", featureId)
                    put("canonical_weight", weight)
                }
                db.insertWithOnConflict("fusion_character_features", null, values,
                    SQLiteDatabase.CONFLICT_IGNORE)
            }
            for (item in sheets) {
                if (item.id.isBlank() || item.path.isBlank() ||
                    !exists(db, "fusion_characters", "character_id", item.characterId)) continue
                val values = ContentValues().apply {
                    put("sheet_asset_id", item.id)
                    put("character_id", item.characterId)
                    put("asset_path", item.path)
                    put("sha256", item.sha)
                    put("updated_at_ms", now)
                }
                if (db.insertWithOnConflict("fusion_character_sheets", null, values,
                        SQLiteDatabase.CONFLICT_IGNORE) != -1L) importedSheets++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return mapOf(
            "ok" to true,
            "reference_series" to series.size, "reference_tags" to tags.size,
            "reference_characters" to characters.size, "reference_sheets" to sheets.size,
            "reference_features" to features.size,
            "added_series" to importedSeries, "added_tags" to importedTags,
            "added_characters" to importedChars, "added_sheets" to importedSheets,
        )
    }

    private fun parseArray(text: String): List<String> = runCatching {
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { n ->
            array.optString(n).trim().takeIf(String::isNotBlank)
        }
    }.getOrDefault(emptyList())

    private fun exists(db: SQLiteDatabase, table: String, key: String, id: String): Boolean =
        db.rawQuery("SELECT 1 FROM " + table + " WHERE " + key + "=? LIMIT 1", arrayOf(id))
            .use { it.moveToFirst() }
}
