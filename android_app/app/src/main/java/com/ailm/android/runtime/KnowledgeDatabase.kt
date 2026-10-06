package com.ailm.android.runtime

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Immutable runtime copy of externally authored Asterion Knowledge.
 *
 * Normal automation only reads this database. The only mutation entry points
 * replace whole externally supplied Knowledge releases. Fusion learning and
 * review corrections never write here.
 */
internal class KnowledgeDatabase(
    context: Context,
) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("PRAGMA foreign_keys = ON")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE knowledge_releases (
                release_kind TEXT PRIMARY KEY,
                source_name TEXT NOT NULL,
                imported_at_ms INTEGER NOT NULL,
                entry_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE knowledge_series (
                series_code TEXT PRIMARY KEY,
                canonical_name TEXT NOT NULL,
                franchise TEXT NOT NULL DEFAULT '',
                aliases_json TEXT NOT NULL DEFAULT '[]'
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_knowledge_series_name ON knowledge_series(canonical_name COLLATE NOCASE)")
        db.execSQL(
            """
            CREATE TABLE knowledge_series_aliases (
                alias_key TEXT PRIMARY KEY,
                series_code TEXT NOT NULL,
                FOREIGN KEY(series_code) REFERENCES knowledge_series(series_code)
                    ON UPDATE RESTRICT ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_knowledge_series_aliases_series ON knowledge_series_aliases(series_code)")

        db.execSQL(
            """
            CREATE TABLE knowledge_tags (
                tag_id TEXT PRIMARY KEY,
                canonical_name TEXT NOT NULL,
                category TEXT NOT NULL,
                parent_tag_id TEXT,
                aliases_json TEXT NOT NULL DEFAULT '[]'
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_knowledge_tags_name ON knowledge_tags(canonical_name COLLATE NOCASE)")
        db.execSQL("CREATE INDEX idx_knowledge_tags_category ON knowledge_tags(category)")
        db.execSQL("CREATE INDEX idx_knowledge_tags_parent ON knowledge_tags(parent_tag_id)")
        db.execSQL(
            """
            CREATE TABLE knowledge_tag_aliases (
                alias_key TEXT NOT NULL,
                tag_id TEXT NOT NULL,
                PRIMARY KEY(alias_key, tag_id),
                FOREIGN KEY(tag_id) REFERENCES knowledge_tags(tag_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_knowledge_tag_aliases_key ON knowledge_tag_aliases(alias_key)")
        db.execSQL("CREATE INDEX idx_knowledge_tag_aliases_tag ON knowledge_tag_aliases(tag_id)")

        db.execSQL(
            """
            CREATE TABLE knowledge_characters (
                character_id TEXT PRIMARY KEY,
                parent_character_id TEXT,
                identity_group_id TEXT NOT NULL DEFAULT '',
                entry_type TEXT NOT NULL DEFAULT 'identity',
                canonical_name TEXT NOT NULL,
                primary_series_code TEXT NOT NULL,
                aliases_json TEXT NOT NULL DEFAULT '[]',
                attributes_json TEXT NOT NULL DEFAULT '[]',
                canonical_weapons_json TEXT NOT NULL DEFAULT '[]',
                canonical_outfits_json TEXT NOT NULL DEFAULT '[]',
                sheet_asset_id TEXT NOT NULL DEFAULT '',
                metadata_json TEXT NOT NULL DEFAULT '{}',
                FOREIGN KEY(parent_character_id) REFERENCES knowledge_characters(character_id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(primary_series_code) REFERENCES knowledge_series(series_code)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_knowledge_characters_name ON knowledge_characters(canonical_name COLLATE NOCASE)")
        db.execSQL("CREATE INDEX idx_knowledge_characters_series ON knowledge_characters(primary_series_code)")
        db.execSQL("CREATE INDEX idx_knowledge_characters_parent ON knowledge_characters(parent_character_id)")
        db.execSQL("CREATE INDEX idx_knowledge_characters_group ON knowledge_characters(identity_group_id)")
        db.execSQL(
            """
            CREATE TABLE knowledge_character_aliases (
                alias_key TEXT NOT NULL,
                character_id TEXT NOT NULL,
                PRIMARY KEY(alias_key, character_id),
                FOREIGN KEY(character_id) REFERENCES knowledge_characters(character_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_knowledge_character_aliases_key ON knowledge_character_aliases(alias_key)")
        db.execSQL("CREATE INDEX idx_knowledge_character_aliases_character ON knowledge_character_aliases(character_id)")

        db.execSQL(
            """
            CREATE TABLE knowledge_character_features (
                character_id TEXT NOT NULL,
                feature_id TEXT NOT NULL,
                feature_kind TEXT NOT NULL,
                canonical_weight REAL NOT NULL DEFAULT 1.0,
                PRIMARY KEY(character_id, feature_id, feature_kind),
                FOREIGN KEY(character_id) REFERENCES knowledge_characters(character_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_knowledge_character_features_feature ON knowledge_character_features(feature_id)")
        db.execSQL("CREATE INDEX idx_knowledge_character_features_character ON knowledge_character_features(character_id)")

        db.execSQL(
            """
            CREATE TABLE knowledge_character_sheets (
                sheet_asset_id TEXT PRIMARY KEY,
                character_id TEXT NOT NULL UNIQUE,
                archive_name TEXT NOT NULL DEFAULT '',
                asset_path TEXT NOT NULL,
                sha256 TEXT NOT NULL DEFAULT '',
                metadata_json TEXT NOT NULL DEFAULT '{}',
                FOREIGN KEY(character_id) REFERENCES knowledge_characters(character_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == newVersion) return
        listOf(
            "knowledge_character_sheets",
            "knowledge_character_features",
            "knowledge_character_aliases",
            "knowledge_characters",
            "knowledge_tag_aliases",
            "knowledge_tags",
            "knowledge_series_aliases",
            "knowledge_series",
            "knowledge_releases",
        ).forEach { table -> db.execSQL("DROP TABLE IF EXISTS $table") }
        onCreate(db)
    }

    fun mergeReferenceKnowledge(bundle: ReferenceKnowledgeBundle, sourceName: String): Map<String, Int> {
        if (bundle.series.isEmpty() && bundle.tags.isEmpty()) {
            return mapOf(
                "series_imported" to 0,
                "tags_imported" to 0,
                "series_aliases_ignored" to 0,
            )
        }

        val db = writableDatabase
        val now = System.currentTimeMillis()
        var ignoredAliases = 0
        db.beginTransaction()
        try {
            if (bundle.series.isNotEmpty()) {
                bundle.series.forEach { entry ->
                    val values = ContentValues().apply {
                        put("series_code", entry.code)
                        put("canonical_name", entry.name)
                        put("franchise", entry.franchise)
                        put("aliases_json", JSONArray(entry.aliases).toString())
                    }
                    val updated = db.update(
                        "knowledge_series",
                        values,
                        "series_code = ?",
                        arrayOf(entry.code),
                    )
                    if (updated == 0) {
                        db.insertOrThrow("knowledge_series", null, values)
                    }
                }

                // The selected external series JSON is authoritative for its
                // canonical names. Older Asterion installs could already
                // contain generated/stale codes for the same title. Migrate
                // any dependent Character Knowledge to the imported code and
                // retire only those stale duplicates instead of aborting the
                // entire series import.
                val incomingCodes = bundle.series.map { it.code }.toSet()
                bundle.series.forEach { entry ->
                    val staleCodes = mutableListOf<String>()
                    db.rawQuery(
                        """
                        SELECT series_code
                        FROM knowledge_series
                        WHERE LOWER(TRIM(canonical_name)) = LOWER(TRIM(?))
                          AND series_code != ?
                        """.trimIndent(),
                        arrayOf(entry.name, entry.code),
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            cursor.getString(0)
                                ?.takeIf { it !in incomingCodes }
                                ?.let(staleCodes::add)
                        }
                    }
                    staleCodes.distinct().forEach { staleCode ->
                        db.execSQL(
                            "UPDATE knowledge_characters SET primary_series_code = ? WHERE primary_series_code = ?",
                            arrayOf(entry.code, staleCode),
                        )
                        db.execSQL(
                            "UPDATE knowledge_series SET franchise = ? WHERE franchise = ?",
                            arrayOf(entry.code, staleCode),
                        )
                        db.delete(
                            "knowledge_series",
                            "series_code = ?",
                            arrayOf(staleCode),
                        )
                    }
                }

                val allSeries = buildList {
                    db.rawQuery(
                        "SELECT series_code, canonical_name, franchise, aliases_json FROM knowledge_series ORDER BY series_code",
                        emptyArray(),
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            add(
                                ReferenceSeriesEntry(
                                    code = cursor.getString(0),
                                    name = cursor.getString(1),
                                    franchise = cursor.getString(2).orEmpty(),
                                    aliases = jsonStringList(cursor.getString(3)),
                                ),
                            )
                        }
                    }
                }
                val aliasPlan = SeriesAliasPlanner.plan(allSeries)
                ignoredAliases = aliasPlan.ignoredAliases.size
                db.delete("knowledge_series_aliases", null, null)
                aliasPlan.canonicalEntries.forEach { (seriesCode, value) ->
                    insertAlias(db, "knowledge_series_aliases", "series_code", seriesCode, value)
                }
                aliasPlan.uniqueAliases.forEach { (seriesCode, alias) ->
                    insertAlias(db, "knowledge_series_aliases", "series_code", seriesCode, alias)
                }
            }

            if (bundle.tags.isNotEmpty()) {
                bundle.tags.forEach { entry ->
                    val values = ContentValues().apply {
                        put("tag_id", entry.id)
                        put("canonical_name", entry.name)
                        put("category", entry.category)
                        putNull("parent_tag_id")
                        put("aliases_json", JSONArray(entry.aliases).toString())
                    }
                    val updated = db.update(
                        "knowledge_tags",
                        values,
                        "tag_id = ?",
                        arrayOf(entry.id),
                    )
                    if (updated == 0) {
                        db.insertOrThrow("knowledge_tags", null, values)
                    }
                    db.delete("knowledge_tag_aliases", "tag_id = ?", arrayOf(entry.id))
                    (entry.aliases + entry.name + entry.id).distinct().forEach { alias ->
                        insertTagAlias(db, entry.id, alias)
                    }
                }

                bundle.tags.filter { it.parentId.isNotBlank() }.forEach { entry ->
                    val parentExists = db.rawQuery(
                        "SELECT EXISTS(SELECT 1 FROM knowledge_tags WHERE tag_id = ?)",
                        arrayOf(entry.parentId),
                    ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 }
                    require(parentExists) {
                        "Knowledge tag '" + entry.id + "' references missing parent '" + entry.parentId + "'. Import its parent taxonomy JSON first."
                    }
                    db.execSQL(
                        "UPDATE knowledge_tags SET parent_tag_id = ? WHERE tag_id = ?",
                        arrayOf(entry.parentId, entry.id),
                    )
                }
            }

            upsertRelease(
                db,
                "taxonomy",
                sourceName,
                now,
                bundle.series.size + bundle.tags.size,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return mapOf(
            "series_imported" to bundle.series.size,
            "tags_imported" to bundle.tags.size,
            "series_aliases_ignored" to ignoredAliases,
        )
    }

    fun replaceReferenceKnowledge(bundle: ReferenceKnowledgeBundle, sourceName: String): Int {
        val aliasPlan = SeriesAliasPlanner.plan(bundle.series)
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            // A new external taxonomy release invalidates character knowledge.
            // This prevents an old Character release from silently pointing at
            // changed IDs after the taxonomy is replaced.
            db.delete("knowledge_character_sheets", null, null)
            db.delete("knowledge_character_features", null, null)
            db.delete("knowledge_character_aliases", null, null)
            db.delete("knowledge_characters", null, null)
            db.delete("knowledge_tag_aliases", null, null)
            db.delete("knowledge_tags", null, null)
            db.delete("knowledge_series_aliases", null, null)
            db.delete("knowledge_series", null, null)

            // Store source aliases unchanged, but build the lookup index from
            // a safe derived plan. Canonical code/name always win. Ambiguous
            // aliases are intentionally absent from the lookup index instead
            // of aborting the entire Knowledge release or resolving randomly.
            bundle.series.forEach { entry ->
                db.insertOrThrow(
                    "knowledge_series",
                    null,
                    ContentValues().apply {
                        put("series_code", entry.code)
                        put("canonical_name", entry.name)
                        put("franchise", entry.franchise)
                        put("aliases_json", JSONArray(entry.aliases).toString())
                    },
                )
            }
            aliasPlan.canonicalEntries.forEach { (seriesCode, value) ->
                insertAlias(db, "knowledge_series_aliases", "series_code", seriesCode, value)
            }
            aliasPlan.uniqueAliases.forEach { (seriesCode, alias) ->
                insertAlias(db, "knowledge_series_aliases", "series_code", seriesCode, alias)
            }

            bundle.tags.forEach { entry ->
                // Parent references are linked in a second pass so file/order
                // differences in an external Knowledge ZIP cannot violate FKs.
                db.insertOrThrow(
                    "knowledge_tags",
                    null,
                    ContentValues().apply {
                        put("tag_id", entry.id)
                        put("canonical_name", entry.name)
                        put("category", entry.category)
                        putNull("parent_tag_id")
                        put("aliases_json", JSONArray(entry.aliases).toString())
                    },
                )
                (entry.aliases + entry.name + entry.id).distinct().forEach { alias ->
                    insertTagAlias(db, entry.id, alias)
                }
            }
            bundle.tags.filter { it.parentId.isNotBlank() }.forEach { entry ->
                val parentExists = db.rawQuery(
                    "SELECT EXISTS(SELECT 1 FROM knowledge_tags WHERE tag_id = ?)",
                    arrayOf(entry.parentId),
                ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 }
                require(parentExists) {
                    "Knowledge tag '" + entry.id + "' references missing parent '" + entry.parentId + "'."
                }
                db.execSQL(
                    "UPDATE knowledge_tags SET parent_tag_id = ? WHERE tag_id = ?",
                    arrayOf(entry.parentId, entry.id),
                )
            }
            db.delete("knowledge_releases", "release_kind = ?", arrayOf("characters"))
            upsertRelease(db, "taxonomy", sourceName, now, bundle.series.size + bundle.tags.size)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return aliasPlan.ignoredAliases.size
    }

    fun replaceCharacterKnowledge(entries: List<KnowledgeCharacterEntry>, sourceName: String) {
        val issues = validateCharacterRelease(entries)
        require(issues.isEmpty()) { issues.joinToString("; ") }

        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.delete("knowledge_character_sheets", null, null)
            db.delete("knowledge_character_features", null, null)
            db.delete("knowledge_character_aliases", null, null)
            db.delete("knowledge_characters", null, null)

            val ordered = entries.sortedWith(
                compareBy<KnowledgeCharacterEntry> { it.parentCharacterId.isNotBlank() }
                    .thenBy { it.characterId },
            )
            ordered.forEach { entry ->
                db.insertOrThrow(
                    "knowledge_characters",
                    null,
                    ContentValues().apply {
                        put("character_id", entry.characterId)
                        put("parent_character_id", entry.parentCharacterId.takeIf(String::isNotBlank))
                        put("identity_group_id", entry.identityGroupId)
                        put("entry_type", entry.entryType)
                        put("canonical_name", entry.canonicalName)
                        put("primary_series_code", entry.primarySeriesCode)
                        put("aliases_json", JSONArray(entry.aliases).toString())
                        put("attributes_json", JSONArray(entry.attributeIds).toString())
                        put("canonical_weapons_json", JSONArray(entry.weaponIds).toString())
                        put("canonical_outfits_json", JSONArray(entry.outfitIds).toString())
                        put("sheet_asset_id", entry.sheetAssetId)
                        put("metadata_json", JSONObject(entry.metadata).toString())
                    },
                )
                (entry.aliases + entry.canonicalName + entry.characterId).distinct().forEach { alias ->
                    insertCharacterAlias(db, entry.characterId, alias)
                }
                entry.attributeIds.distinct().forEach { id ->
                    insertCharacterFeature(db, entry.characterId, id, "attribute", featureWeight(id))
                }
                entry.weaponIds.distinct().forEach { id ->
                    insertCharacterFeature(db, entry.characterId, id, "weapon", 1.55)
                }
                entry.outfitIds.distinct().forEach { id ->
                    insertCharacterFeature(db, entry.characterId, id, "outfit", 1.20)
                }
            }
            upsertRelease(db, "characters", sourceName, now, entries.size)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun attachCharacterSheet(
        characterId: String,
        sheetAssetId: String,
        archiveName: String,
        assetPath: String,
        sha256: String,
        metadata: Map<String, Any> = emptyMap(),
    ) {
        require(getCharacter(characterId) != null) { "Unknown character_id '" + characterId + "'." }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                "knowledge_character_sheets",
                null,
                ContentValues().apply {
                    put("sheet_asset_id", sheetAssetId)
                    put("character_id", characterId)
                    put("archive_name", archiveName)
                    put("asset_path", assetPath)
                    put("sha256", sha256)
                    put("metadata_json", JSONObject(metadata).toString())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.execSQL(
                "UPDATE knowledge_characters SET sheet_asset_id = ? WHERE character_id = ?",
                arrayOf(sheetAssetId, characterId),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun listCharacterSheets(limit: Int = 500, offset: Int = 0): List<Map<String, String>> {
        val boundedLimit = limit.coerceIn(1, 5_000)
        val boundedOffset = offset.coerceAtLeast(0)
        return buildList {
            readableDatabase.rawQuery(
                """
                SELECT s.sheet_asset_id, s.character_id, s.asset_path, s.sha256
                FROM knowledge_character_sheets s
                ORDER BY s.character_id
                LIMIT ? OFFSET ?
                """.trimIndent(),
                arrayOf(boundedLimit.toString(), boundedOffset.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    add(
                        mapOf(
                            "sheet_asset_id" to cursor.getString(0),
                            "character_id" to cursor.getString(1),
                            "asset_path" to cursor.getString(2),
                            "sha256" to cursor.getString(3),
                        ),
                    )
                }
            }
        }
    }

    fun characterSheetCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM knowledge_character_sheets",
        emptyArray(),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    fun summary(): Map<String, Any> {
        fun count(table: String): Int = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $table",
            emptyArray(),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

        val releases = buildList {
            readableDatabase.rawQuery(
                "SELECT release_kind, source_name, imported_at_ms, entry_count FROM knowledge_releases ORDER BY imported_at_ms DESC",
                emptyArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    add(
                        mapOf(
                            "kind" to cursor.getString(0),
                            "source" to cursor.getString(1),
                            "imported_at_ms" to cursor.getLong(2),
                            "entry_count" to cursor.getInt(3),
                        ),
                    )
                }
            }
        }

        return mapOf(
            "series" to count("knowledge_series"),
            "tags" to count("knowledge_tags"),
            "characters" to count("knowledge_characters"),
            "character_sheets" to count("knowledge_character_sheets"),
            "releases" to releases,
        )
    }

    fun hasCharacters(): Boolean = readableDatabase.rawQuery(
        "SELECT EXISTS(SELECT 1 FROM knowledge_characters LIMIT 1)",
        emptyArray(),
    ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 }

    fun getCharacter(characterId: String): KnowledgeCharacterEntry? = readableDatabase.rawQuery(
        """
        SELECT character_id, COALESCE(parent_character_id, ''), identity_group_id, entry_type,
               canonical_name, primary_series_code, aliases_json, attributes_json,
               canonical_weapons_json, canonical_outfits_json, sheet_asset_id, metadata_json
        FROM knowledge_characters
        WHERE character_id = ?
        LIMIT 1
        """.trimIndent(),
        arrayOf(characterId),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toKnowledgeCharacter() else null }

    fun resolveCharacter(
        value: String,
        seriesCode: String? = null,
    ): KnowledgeCharacterEntry? {
        val direct = value.trim()
        if (direct.isBlank()) return null
        getCharacter(direct)?.let { return it }

        val matches = resolveCharacters(value)
            .let { entries ->
                val requiredSeries = seriesCode?.trim().orEmpty()
                if (requiredSeries.isBlank()) entries
                else entries.filter { it.primarySeriesCode == requiredSeries }
            }
        return matches.singleOrNull()
    }

    fun resolveCharacters(value: String): List<KnowledgeCharacterEntry> {
        val key = aliasKey(value)
        if (key.isBlank()) return emptyList()
        val ids = buildList {
            readableDatabase.rawQuery(
                "SELECT character_id FROM knowledge_character_aliases WHERE alias_key = ? ORDER BY character_id",
                arrayOf(key),
            ).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        return ids.mapNotNull(::getCharacter)
    }

    fun seriesByCode(seriesCode: String): ReferenceSeriesEntry? = readableDatabase.rawQuery(
        "SELECT series_code, canonical_name, franchise, aliases_json FROM knowledge_series WHERE series_code = ? LIMIT 1",
        arrayOf(seriesCode),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else ReferenceSeriesEntry(
            cursor.getString(0), cursor.getString(1), cursor.getString(2), jsonStringList(cursor.getString(3)),
        )
    }

    fun resolveSeries(value: String): ReferenceSeriesEntry? {
        val key = aliasKey(value)
        if (key.isBlank()) return null
        val seriesCode = readableDatabase.rawQuery(
            "SELECT series_code FROM knowledge_series_aliases WHERE alias_key = ? LIMIT 1",
            arrayOf(key),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        return seriesCode?.let(::seriesByCode)
    }

    fun resolveTag(value: String): ReferenceTagEntry? {
        val direct = value.trim()
        if (direct.isBlank()) return null

        // Tag IDs are the authoritative machine representation. Always prefer
        // an exact ID before considering human-readable names or aliases.
        readableDatabase.rawQuery(
            "SELECT tag_id, canonical_name, category, COALESCE(parent_tag_id, ''), aliases_json " +
                "FROM knowledge_tags WHERE tag_id = ? COLLATE NOCASE LIMIT 1",
            arrayOf(direct),
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.toReferenceTag()
        }

        val key = aliasKey(direct)
        if (key.isBlank()) return null
        val tagIds = buildList {
            readableDatabase.rawQuery(
                "SELECT tag_id FROM knowledge_tag_aliases WHERE alias_key = ? ORDER BY tag_id",
                arrayOf(key),
            ).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }.distinct()

        // A human-readable label such as "Black" can legitimately belong to
        // several dimensions (hair, eyes, skin). Ambiguous labels must not be
        // silently assigned to an arbitrary tag.
        val tagId = tagIds.singleOrNull() ?: return null
        return readableDatabase.rawQuery(
            "SELECT tag_id, canonical_name, category, COALESCE(parent_tag_id, ''), aliases_json FROM knowledge_tags WHERE tag_id = ? LIMIT 1",
            arrayOf(tagId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toReferenceTag() else null }
    }

    fun taxonomyPromptContext(): String {
        val prefixes = listOf("HC", "HL", "HS", "EC", "ET", "SC", "BH", "BT", "BS", "BY", "SX", "AG", "SP", "SA")
        val grouped = linkedMapOf<String, MutableList<String>>()
        readableDatabase.rawQuery(
            "SELECT tag_id, canonical_name, category FROM knowledge_tags ORDER BY category, tag_id",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                if (prefixes.none { id.startsWith(it, ignoreCase = true) }) continue
                val category = cursor.getString(2).ifBlank { id.takeWhile(Char::isLetter) }
                grouped.getOrPut(category) { mutableListOf() } += id + "=" + cursor.getString(1)
            }
        }
        return grouped.entries.joinToString("\n") { (category, entries) ->
            category + ":[" + entries.joinToString("|") + "]"
        }
    }

    fun illustrationTaxonomyPromptContext(): String {
        val prefixes = listOf(
            "EY", "MO", "EX", "GE", "PO", "EN", "WE", "FR", "OR", "CA", "LI",
            "OF", "WP", "AC", "RT",
        )
        val grouped = linkedMapOf<String, MutableList<String>>()
        readableDatabase.rawQuery(
            "SELECT tag_id, canonical_name, category FROM knowledge_tags ORDER BY category, tag_id",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                if (prefixes.none { id.startsWith(it, ignoreCase = true) }) continue
                val category = cursor.getString(2).ifBlank { id.takeWhile(Char::isLetter) }
                grouped.getOrPut(category) { mutableListOf() } += id + "=" + cursor.getString(1)
            }
        }
        return grouped.entries.joinToString("\n") { (category, entries) ->
            category + ":[" + entries.joinToString("|") + "]"
        }
    }

    fun rankByFeatures(observed: Map<String, Double>, limit: Int = 80): List<KnowledgeCandidate> {
        if (observed.isEmpty()) return emptyList()
        val ids = observed.keys.map(String::trim).filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val sql = """
            SELECT f.character_id, SUM(f.canonical_weight) AS matched_weight
            FROM knowledge_character_features f
            WHERE f.feature_id IN ($placeholders)
            GROUP BY f.character_id
            ORDER BY matched_weight DESC
            LIMIT ?
        """.trimIndent()
        val args = (ids + limit.toString()).toTypedArray()
        val raw = mutableListOf<String>()
        readableDatabase.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) raw += cursor.getString(0)
        }
        return raw.mapNotNull { characterId ->
            val profile = getCharacter(characterId) ?: return@mapNotNull null
            val featureIds = (profile.attributeIds + profile.weaponIds + profile.outfitIds).toSet()
            KnowledgeCandidate(
                character = profile,
                attributeScore = CanonicalAttributeSemantics.coherenceScore(
                    observed = observed,
                    candidateFeatures = featureIds,
                    weight = ::featureWeight,
                ),
            )
        }.sortedByDescending(KnowledgeCandidate::attributeScore)
    }

    fun validateCharacterRelease(entries: List<KnowledgeCharacterEntry>): List<String> {
        val issues = mutableListOf<String>()
        val ids = linkedSetOf<String>()
        entries.forEach { entry ->
            if (!entry.characterId.matches(Regex("^CH\\d{6}(?:-\\d+)?$"))) {
                issues += "Invalid character_id '" + entry.characterId + "'. Expected CHxxxxxx or CHxxxxxx-n."
            }
            if (!ids.add(entry.characterId.lowercase(Locale.US))) {
                issues += "Duplicate character_id '" + entry.characterId + "'."
            }
            if (entry.canonicalName.isBlank()) issues += entry.characterId + ": canonical_name is required."
            if (seriesByCode(entry.primarySeriesCode) == null) {
                issues += entry.characterId + ": unknown series '" + entry.primarySeriesCode + "'."
            }
            if (entry.parentCharacterId.isNotBlank() && entry.entryType != "transformation") {
                issues += entry.characterId + ": parent_character_id requires entry_type=transformation."
            }
            (entry.attributeIds + entry.weaponIds + entry.outfitIds).distinct().forEach { featureId ->
                if (resolveTag(featureId) == null) {
                    issues += entry.characterId + ": unknown Knowledge feature '" + featureId + "'."
                }
            }
        }

        val entryIds = entries.map { it.characterId }.toSet()
        entries.filter { it.parentCharacterId.isNotBlank() }.forEach { entry ->
            if (entry.parentCharacterId !in entryIds) {
                issues += entry.characterId + ": missing transformation parent '" + entry.parentCharacterId + "'."
            }
        }

        // Character aliases are intentionally allowed to be ambiguous across
        // identities. They are lookup/index terms, not canonical identities.
        // Automatic alias resolution succeeds only when the alias (optionally
        // constrained by series) identifies exactly one character.
        return issues.distinct()
    }

    private fun insertCharacterAlias(
        db: SQLiteDatabase,
        characterId: String,
        alias: String,
    ) {
        val key = aliasKey(alias)
        if (key.isBlank()) return
        db.insertWithOnConflict(
            "knowledge_character_aliases",
            null,
            ContentValues().apply {
                put("alias_key", key)
                put("character_id", characterId)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    private fun insertTagAlias(
        db: SQLiteDatabase,
        tagId: String,
        alias: String,
    ) {
        val key = aliasKey(alias)
        if (key.isBlank()) return
        db.insertWithOnConflict(
            "knowledge_tag_aliases",
            null,
            ContentValues().apply {
                put("alias_key", key)
                put("tag_id", tagId)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    private fun insertAlias(
        db: SQLiteDatabase,
        table: String,
        idColumn: String,
        idValue: String,
        alias: String,
    ) {
        val key = aliasKey(alias)
        if (key.isBlank()) return
        val existing = db.rawQuery(
            "SELECT " + idColumn + " FROM " + table + " WHERE alias_key = ? LIMIT 1",
            arrayOf(key),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        require(existing == null || existing == idValue) {
            "Alias collision '" + alias + "' between " + existing + " and " + idValue + "."
        }
        db.insertWithOnConflict(
            table,
            null,
            ContentValues().apply {
                put("alias_key", key)
                put(idColumn, idValue)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun aliasKey(value: String): String = value
        .trim()
        .lowercase(Locale.US)
        .replace(Regex("\\s+"), " ")

    private fun insertCharacterFeature(
        db: SQLiteDatabase,
        characterId: String,
        featureId: String,
        kind: String,
        weight: Double,
    ) {
        db.insertOrThrow(
            "knowledge_character_features",
            null,
            ContentValues().apply {
                put("character_id", characterId)
                put("feature_id", featureId)
                put("feature_kind", kind)
                put("canonical_weight", weight)
            },
        )
    }

    private fun upsertRelease(db: SQLiteDatabase, kind: String, sourceName: String, now: Long, count: Int) {
        db.insertWithOnConflict(
            "knowledge_releases",
            null,
            ContentValues().apply {
                put("release_kind", kind)
                put("source_name", sourceName)
                put("imported_at_ms", now)
                put("entry_count", count)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun android.database.Cursor.toKnowledgeCharacter(): KnowledgeCharacterEntry = KnowledgeCharacterEntry(
        characterId = getString(0),
        parentCharacterId = getString(1).orEmpty(),
        identityGroupId = getString(2).orEmpty(),
        entryType = getString(3).orEmpty().ifBlank { "identity" },
        canonicalName = getString(4),
        primarySeriesCode = getString(5),
        aliases = jsonStringList(getString(6)),
        attributeIds = jsonStringList(getString(7)),
        weaponIds = jsonStringList(getString(8)),
        outfitIds = jsonStringList(getString(9)),
        sheetAssetId = getString(10).orEmpty(),
        metadata = jsonObjectMap(getString(11)),
    )

    private fun android.database.Cursor.toReferenceTag(): ReferenceTagEntry = ReferenceTagEntry(
        id = getString(0),
        name = getString(1),
        category = getString(2),
        parentId = getString(3).orEmpty(),
        aliases = jsonStringList(getString(4)),
    )

    private fun jsonStringList(raw: String?): List<String> = runCatching {
        val array = JSONArray(raw.orEmpty())
        (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf(String::isNotBlank) }
    }.getOrDefault(emptyList())

    private fun jsonObjectMap(raw: String?): Map<String, Any> = runCatching {
        val obj = JSONObject(raw.orEmpty())
        buildMap {
            obj.keys().forEach { key -> obj.opt(key)?.let { put(key, it) } }
        }
    }.getOrDefault(emptyMap())

    companion object {
        private const val DB_NAME = "asterion_knowledge.sqlite"
        private const val DB_VERSION = 4

        fun featureWeight(id: String): Double = when {
            id.startsWith("WP", ignoreCase = true) -> 1.55
            id.startsWith("SP", ignoreCase = true) || id.startsWith("SA", ignoreCase = true) -> 1.45
            id.startsWith("HS", ignoreCase = true) -> 1.35
            id.startsWith("HC", ignoreCase = true) -> 1.30
            id.startsWith("EC", ignoreCase = true) -> 1.25
            id.startsWith("HL", ignoreCase = true) -> 1.15
            id.startsWith("OF", ignoreCase = true) -> 1.15
            id.startsWith("SC", ignoreCase = true) -> 0.90
            id.startsWith("BT", ignoreCase = true) || id.startsWith("BY", ignoreCase = true) -> 0.85
            id.startsWith("BS", ignoreCase = true) -> 0.60
            id.startsWith("BH", ignoreCase = true) -> 0.50
            id.startsWith("AG", ignoreCase = true) -> 0.50
            else -> 0.75
        }
    }
}

internal data class KnowledgeCharacterEntry(
    val characterId: String,
    val parentCharacterId: String = "",
    val identityGroupId: String = "",
    val entryType: String = "identity",
    val canonicalName: String,
    val primarySeriesCode: String,
    val aliases: List<String> = emptyList(),
    val attributeIds: List<String> = emptyList(),
    val weaponIds: List<String> = emptyList(),
    val outfitIds: List<String> = emptyList(),
    val sheetAssetId: String = "",
    val metadata: Map<String, Any> = emptyMap(),
)

internal data class KnowledgeCandidate(
    val character: KnowledgeCharacterEntry,
    val attributeScore: Double,
)
