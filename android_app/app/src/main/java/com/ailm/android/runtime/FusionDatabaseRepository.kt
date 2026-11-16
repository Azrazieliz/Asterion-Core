package com.ailm.android.runtime

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

enum class FusionImportFormat {
    JSON,
    WORKBOOK_COMPAT,
}

data class FusionValidationIssue(
    val code: String,
    val severity: String,
    val count: Int,
    val details: String,
)

data class FusionValidationReport(
    val valid: Boolean,
    val generatedAtMs: Long,
    val quickCheckResult: String,
    val foreignKeyViolationCount: Int,
    val duplicateIdCount: Int,
    val brokenReferenceCount: Int,
    val missingRequiredEntityCount: Int,
    val invalidRelationshipCount: Int,
    val issues: List<FusionValidationIssue>,
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "valid" to valid,
            "generated_at_ms" to generatedAtMs,
            "quick_check_result" to quickCheckResult,
            "foreign_key_violations" to foreignKeyViolationCount,
            "duplicate_id_count" to duplicateIdCount,
            "broken_reference_count" to brokenReferenceCount,
            "missing_required_entity_count" to missingRequiredEntityCount,
            "invalid_relationship_count" to invalidRelationshipCount,
            "issues" to issues.map {
                mapOf(
                    "code" to it.code,
                    "severity" to it.severity,
                    "count" to it.count,
                    "details" to it.details,
                )
            },
        )
    }
}

data class FusionImportResult(
    val ok: Boolean,
    val format: FusionImportFormat,
    val replaceExisting: Boolean,
    val importedRows: Int,
    val touchedTables: Int,
    val duplicatePayloadIds: Int,
    val duplicatePayloadDetails: List<String>,
    val validation: FusionValidationReport,
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "ok" to ok,
            "format" to format.name.lowercase(),
            "replace_existing" to replaceExisting,
            "imported_rows" to importedRows,
            "touched_tables" to touchedTables,
            "duplicate_payload_ids" to duplicatePayloadIds,
            "duplicate_payload_details" to duplicatePayloadDetails,
            "validation" to validation.toMap(),
        )
    }
}

class FusionDatabaseRepository(
    private val database: LocalDatabase,
) {
    private enum class FusionColumnType {
        TEXT,
        INTEGER,
        REAL,
        BOOLEAN,
    }

    private data class FusionColumn(
        val name: String,
        val type: FusionColumnType,
        val defaultValue: Any? = null,
    )

    private data class FusionTableSpec(
        val tableName: String,
        val idColumn: String?,
        val workbookSheetName: String,
        val columns: List<FusionColumn>,
        val clearOnReplace: Boolean = true,
    )

    private val tableSpecs: List<FusionTableSpec> = listOf(
        FusionTableSpec(
            tableName = FusionDatabaseSchema.TABLE_FRANCHISES,
            idColumn = "franchise_id",
            workbookSheetName = "Franchises",
            columns = listOf(
                FusionColumn("franchise_id", FusionColumnType.TEXT),
                FusionColumn("parent_franchise_id", FusionColumnType.TEXT),
                FusionColumn("display_name", FusionColumnType.TEXT, ""),
                FusionColumn("canonical_slug", FusionColumnType.TEXT, ""),
                FusionColumn("description", FusionColumnType.TEXT, ""),
                FusionColumn("metadata_json", FusionColumnType.TEXT, "{}"),
                FusionColumn("workbook_row", FusionColumnType.INTEGER),
                FusionColumn("created_at_ms", FusionColumnType.INTEGER, 0L),
                FusionColumn("updated_at_ms", FusionColumnType.INTEGER, 0L),
            ),
        ),
        FusionTableSpec(
            tableName = FusionDatabaseSchema.TABLE_SERIES,
            idColumn = "series_code",
            workbookSheetName = "Series",
            columns = listOf(
                FusionColumn("series_code", FusionColumnType.TEXT),
                FusionColumn("franchise_id", FusionColumnType.TEXT),
                FusionColumn("parent_series_code", FusionColumnType.TEXT),
                FusionColumn("canonical_title", FusionColumnType.TEXT, ""),
                FusionColumn("localized_title", FusionColumnType.TEXT, ""),
                FusionColumn("aliases_json", FusionColumnType.TEXT, "[]"),
                FusionColumn("metadata_json", FusionColumnType.TEXT, "{}"),
                FusionColumn("workbook_row", FusionColumnType.INTEGER),
                FusionColumn("created_at_ms", FusionColumnType.INTEGER, 0L),
                FusionColumn("updated_at_ms", FusionColumnType.INTEGER, 0L),
            ),
        ),
        FusionTableSpec(
            tableName = FusionDatabaseSchema.TABLE_SERIES_LINKS,
            idColumn = null,
            workbookSheetName = "SeriesLinks",
            columns = listOf(
                FusionColumn("series_code", FusionColumnType.TEXT),
                FusionColumn("related_series_code", FusionColumnType.TEXT),
                FusionColumn("relation_kind", FusionColumnType.TEXT, "related"),
                FusionColumn("metadata_json", FusionColumnType.TEXT, "{}"),
                FusionColumn("added_at_ms", FusionColumnType.INTEGER, 0L),
            ),
        ),
        FusionTableSpec(
            tableName = FusionDatabaseSchema.TABLE_CHARACTERS,
            idColumn = "character_id",
            workbookSheetName = "Characters",
            columns = listOf(
                FusionColumn("character_id", FusionColumnType.TEXT),
                FusionColumn("primary_series_code", FusionColumnType.TEXT),
                FusionColumn("canonical_name", FusionColumnType.TEXT, ""),
                FusionColumn("localized_name", FusionColumnType.TEXT, ""),
                FusionColumn("romaji_name", FusionColumnType.TEXT, ""),
                FusionColumn("gender", FusionColumnType.TEXT, ""),
                FusionColumn("description", FusionColumnType.TEXT, ""),
                FusionColumn("recognition_profile_json", FusionColumnType.TEXT, "{}"),
                FusionColumn("metadata_json", FusionColumnType.TEXT, "{}"),
                FusionColumn("workbook_row", FusionColumnType.INTEGER),
                FusionColumn("created_at_ms", FusionColumnType.INTEGER, 0L),
                FusionColumn("updated_at_ms", FusionColumnType.INTEGER, 0L),
            ),
        ),
        FusionTableSpec(
            tableName = FusionDatabaseSchema.TABLE_CHARACTER_SERIES,
            idColumn = null,
            workbookSheetName = "CharacterSeries",
            columns = listOf(
                FusionColumn("character_id", FusionColumnType.TEXT),
                FusionColumn("series_code", FusionColumnType.TEXT),
                FusionColumn("relation_kind", FusionColumnType.TEXT, "primary"),
                FusionColumn("added_at_ms", FusionColumnType.INTEGER, 0L),
            ),
        ),
        FusionTableSpec(
            tableName = FusionDatabaseSchema.TABLE_TAGS,
            idColumn = "tag_id",
            workbookSheetName = "Tags",
            columns = listOf(
                FusionColumn("tag_id", FusionColumnType.TEXT),
                FusionColumn("canonical_name", FusionColumnType.TEXT, ""),
                FusionColumn("category", FusionColumnType.TEXT, ""),
                FusionColumn("parent_tag_id", FusionColumnType.TEXT),
                FusionColumn("metadata_json", FusionColumnType.TEXT, "{}"),
                FusionColumn("workbook_row", FusionColumnType.INTEGER),
                FusionColumn("created_at_ms", FusionColumnType.INTEGER, 0L),
                FusionColumn("updated_at_ms", FusionColumnType.INTEGER, 0L),
            ),
        ),
        FusionTableSpec(
            tableName = FusionDatabaseSchema.TABLE_TAG_ALIASES,
            idColumn = "alias_id",
            workbookSheetName = "TagAliases",
            columns = listOf(
                FusionColumn("alias_id", FusionColumnType.TEXT),
                FusionColumn("tag_id", FusionColumnType.TEXT),
                FusionColumn("alias_value", FusionColumnType.TEXT, ""),
                FusionColumn("locale", FusionColumnType.TEXT, ""),
                FusionColumn("created_at_ms", FusionColumnType.INTEGER, 0L),
            ),
        ),
        FusionTableSpec(
            tableName = FusionDatabaseSchema.TABLE_OUTFITS,
            idColumn = "outfit_id",
            workbookSheetName = "Outfits",
            columns = listOf(
                FusionColumn("outfit_id", FusionColumnType.TEXT),
                FusionColumn("canonical_name", FusionColumnType.TEXT, ""),
                FusionColumn("metadata_json", FusionColumnType.TEXT, "{}"),
                FusionColumn("workbook_row", FusionColumnType.INTEGER),
                FusionColumn("created_at_ms", FusionColumnType.INTEGER, 0L),
                FusionColumn("updated_at_ms", FusionColumnType.INTEGER, 0L),
            ),
        ),
        FusionTableSpec(
            tableName = FusionDatabaseSchema.TABLE_WEAPONS,
            idColumn = "weapon_id",
            workbookSheetName = "Weapons",
            columns = listOf(
                FusionColumn("weapon_id", FusionColumnType.TEXT),
                FusionColumn("canonical_name", FusionColumnType.TEXT, ""),
                FusionColumn("metadata_json", FusionColumnType.TEXT, "{}"),
                FusionColumn("workbook_row", FusionColumnType.INTEGER),
                FusionColumn("created_at_ms", FusionColumnType.INTEGER, 0L),
                FusionColumn("updated_at_ms", FusionColumnType.INTEGER, 0L),
            ),
        ),
        FusionTableSpec(
            tableName = FusionDatabaseSchema.TABLE_ARTISTS,
            idColumn = "artist_id",
            workbookSheetName = "Artists",
            columns = listOf(
                FusionColumn("artist_id", FusionColumnType.TEXT),
                FusionColumn("display_name", FusionColumnType.TEXT, ""),
                FusionColumn("aliases_json", FusionColumnType.TEXT, "[]"),
                FusionColumn("metadata_json", FusionColumnType.TEXT, "{}"),
                FusionColumn("workbook_row", FusionColumnType.INTEGER),
                FusionColumn("created_at_ms", FusionColumnType.INTEGER, 0L),
                FusionColumn("updated_at_ms", FusionColumnType.INTEGER, 0L),
            ),
        ),
        FusionTableSpec(
            tableName = FusionDatabaseSchema.TABLE_COLLECTIONS,
            idColumn = "collection_id",
            workbookSheetName = "Collections",
            columns = listOf(
                FusionColumn("collection_id", FusionColumnType.TEXT),
                FusionColumn("collection_name", FusionColumnType.TEXT, ""),
                FusionColumn("collection_kind", FusionColumnType.TEXT, "manual"),
                FusionColumn("parent_collection_id", FusionColumnType.TEXT),
                FusionColumn("smart_filter_json", FusionColumnType.TEXT, "{}"),
                FusionColumn("metadata_json", FusionColumnType.TEXT, "{}"),
                FusionColumn("created_at_ms", FusionColumnType.INTEGER, 0L),
                FusionColumn("updated_at_ms", FusionColumnType.INTEGER, 0L),
            ),
        ),
        FusionTableSpec(
            tableName = FusionDatabaseSchema.TABLE_COLLECTION_IMAGES,
            idColumn = null,
            workbookSheetName = "CollectionImages",
            columns = listOf(
                FusionColumn("collection_id", FusionColumnType.TEXT),
                FusionColumn("image_id", FusionColumnType.INTEGER),
                FusionColumn("ordinal", FusionColumnType.INTEGER, 0L),
                FusionColumn("added_at_ms", FusionColumnType.INTEGER, 0L),
            ),
        ),
        FusionTableSpec(
            tableName = FusionDatabaseSchema.TABLE_CHARACTER_OUTFITS,
            idColumn = null,
            workbookSheetName = "CharacterOutfits",
            columns = listOf(
                FusionColumn("character_id", FusionColumnType.TEXT),
                FusionColumn("outfit_id", FusionColumnType.TEXT),
                FusionColumn("relation_kind", FusionColumnType.TEXT, "canonical"),
                FusionColumn("metadata_json", FusionColumnType.TEXT, "{}"),
                FusionColumn("added_at_ms", FusionColumnType.INTEGER, 0L),
            ),
        ),
        FusionTableSpec(
            tableName = FusionDatabaseSchema.TABLE_CHARACTER_WEAPONS,
            idColumn = null,
            workbookSheetName = "CharacterWeapons",
            columns = listOf(
                FusionColumn("character_id", FusionColumnType.TEXT),
                FusionColumn("weapon_id", FusionColumnType.TEXT),
                FusionColumn("relation_kind", FusionColumnType.TEXT, "canonical"),
                FusionColumn("metadata_json", FusionColumnType.TEXT, "{}"),
                FusionColumn("added_at_ms", FusionColumnType.INTEGER, 0L),
            ),
        ),
        FusionTableSpec(
            tableName = FusionDatabaseSchema.TABLE_IMAGE_PROFILES,
            idColumn = "image_id",
            workbookSheetName = "ImageProfiles",
            columns = listOf(
                FusionColumn("image_id", FusionColumnType.INTEGER),
                FusionColumn("source_uri", FusionColumnType.TEXT, ""),
                FusionColumn("thumbnail_uri", FusionColumnType.TEXT, ""),
                FusionColumn("favorite", FusionColumnType.BOOLEAN, false),
                FusionColumn("rating", FusionColumnType.INTEGER, 0L),
                FusionColumn("tags_text", FusionColumnType.TEXT, ""),
                FusionColumn("metadata_json", FusionColumnType.TEXT, "{}"),
                FusionColumn("updated_at_ms", FusionColumnType.INTEGER, 0L),
            ),
        ),
        FusionTableSpec(
            tableName = FusionDatabaseSchema.TABLE_IMAGE_SERIES,
            idColumn = null,
            workbookSheetName = "ImageSeries",
            columns = listOf(
                FusionColumn("image_id", FusionColumnType.INTEGER),
                FusionColumn("series_code", FusionColumnType.TEXT),
                FusionColumn("confidence", FusionColumnType.REAL, 1.0),
                FusionColumn("source", FusionColumnType.TEXT, "manual"),
                FusionColumn("added_at_ms", FusionColumnType.INTEGER, 0L),
            ),
        ),
        FusionTableSpec(
            tableName = FusionDatabaseSchema.TABLE_IMAGE_CHARACTERS,
            idColumn = null,
            workbookSheetName = "ImageCharacters",
            columns = listOf(
                FusionColumn("image_id", FusionColumnType.INTEGER),
                FusionColumn("character_id", FusionColumnType.TEXT),
                FusionColumn("confidence", FusionColumnType.REAL, 1.0),
                FusionColumn("source", FusionColumnType.TEXT, "manual"),
                FusionColumn("added_at_ms", FusionColumnType.INTEGER, 0L),
            ),
        ),
        FusionTableSpec(
            tableName = FusionDatabaseSchema.TABLE_IMAGE_TAGS,
            idColumn = null,
            workbookSheetName = "ImageTags",
            columns = listOf(
                FusionColumn("image_id", FusionColumnType.INTEGER),
                FusionColumn("tag_id", FusionColumnType.TEXT),
                FusionColumn("confidence", FusionColumnType.REAL, 1.0),
                FusionColumn("source", FusionColumnType.TEXT, "manual"),
                FusionColumn("added_at_ms", FusionColumnType.INTEGER, 0L),
            ),
        ),
        FusionTableSpec(
            tableName = FusionDatabaseSchema.TABLE_IMAGE_OUTFITS,
            idColumn = null,
            workbookSheetName = "ImageOutfits",
            columns = listOf(
                FusionColumn("image_id", FusionColumnType.INTEGER),
                FusionColumn("outfit_id", FusionColumnType.TEXT),
                FusionColumn("confidence", FusionColumnType.REAL, 1.0),
                FusionColumn("source", FusionColumnType.TEXT, "manual"),
                FusionColumn("added_at_ms", FusionColumnType.INTEGER, 0L),
            ),
        ),
        FusionTableSpec(
            tableName = FusionDatabaseSchema.TABLE_IMAGE_WEAPONS,
            idColumn = null,
            workbookSheetName = "ImageWeapons",
            columns = listOf(
                FusionColumn("image_id", FusionColumnType.INTEGER),
                FusionColumn("weapon_id", FusionColumnType.TEXT),
                FusionColumn("confidence", FusionColumnType.REAL, 1.0),
                FusionColumn("source", FusionColumnType.TEXT, "manual"),
                FusionColumn("added_at_ms", FusionColumnType.INTEGER, 0L),
            ),
        ),
        FusionTableSpec(
            tableName = FusionDatabaseSchema.TABLE_IMAGE_ARTISTS,
            idColumn = null,
            workbookSheetName = "ImageArtists",
            columns = listOf(
                FusionColumn("image_id", FusionColumnType.INTEGER),
                FusionColumn("artist_id", FusionColumnType.TEXT),
                FusionColumn("confidence", FusionColumnType.REAL, 1.0),
                FusionColumn("source", FusionColumnType.TEXT, "manual"),
                FusionColumn("added_at_ms", FusionColumnType.INTEGER, 0L),
            ),
        ),
    )

    private val workbookSheetToTable: Map<String, String> by lazy {
        tableSpecs.associate { it.workbookSheetName to it.tableName }
    }

    private val importPipeline: ExternalImportPipeline by lazy {
        ExternalImportPipeline(
            database = database,
            workbookSheetToTable = workbookSheetToTable,
        )
    }

    fun exportJson(pretty: Boolean = true): String {
        val payload = JSONObject()
        payload.put("application", "AsterionCore")
        payload.put("format", "fusion_json")
        payload.put("fusion_schema_version", FusionDatabaseSchema.FUSION_SCHEMA_VERSION)
        payload.put("generated_at_ms", System.currentTimeMillis())

        val tables = JSONObject()
        tableSpecs.forEach { spec ->
            tables.put(spec.tableName, readRows(spec))
        }
        tables.put("images_snapshot", readLegacyImageSnapshot())

        payload.put("tables", tables)
        return if (pretty) payload.toString(2) else payload.toString()
    }

    fun exportWorkbookCompatJson(pretty: Boolean = true): String {
        val payload = JSONObject()
        payload.put("application", "AsterionCore")
        payload.put("format", "fusion_workbook_compat")
        payload.put("fusion_schema_version", FusionDatabaseSchema.FUSION_SCHEMA_VERSION)
        payload.put("generated_at_ms", System.currentTimeMillis())

        val sheets = JSONObject()
        tableSpecs.forEach { spec ->
            sheets.put(spec.workbookSheetName, readRows(spec))
        }

        payload.put("sheets", sheets)
        payload.put("images_snapshot", readLegacyImageSnapshot())
        return if (pretty) payload.toString(2) else payload.toString()
    }

    fun importJson(payload: String, replaceExisting: Boolean): FusionImportResult {
        val result = importDatabase(
            request = ExternalImportRequest(
                sourceType = ExternalImportSourceType.JSON,
                source = payload,
                replaceExisting = replaceExisting,
            ),
        )
        return result.toFusionImportResult(FusionImportFormat.JSON)
    }

    fun importWorkbookCompatJson(payload: String, replaceExisting: Boolean): FusionImportResult {
        val result = importDatabase(
            request = ExternalImportRequest(
                sourceType = ExternalImportSourceType.WORKBOOK_COMPAT_JSON,
                source = payload,
                replaceExisting = replaceExisting,
            ),
        )
        return result.toFusionImportResult(FusionImportFormat.WORKBOOK_COMPAT)
    }

    /**
     * Install discovered Knowledge without deleting canonical entities.
     * REPLACE invokes SQLite DELETE + INSERT semantics, which could cascade
     * manual tags, relationships and image evidence whenever a source release
     * changes. Existing Fusion data therefore wins; explicit edits go through
     * audited Fusion mutation paths rather than destructive import.
     */
    fun persistFusionRows(candidates: List<FusionRowCandidate>, replaceExisting: Boolean = false): Map<String, Any> {
        val rowsByTable = candidates.groupBy { it.table }
        var added = 0
        var retained = 0
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            rowsByTable.forEach { (tableName, rowCandidates) ->
                rowCandidates.forEach { candidate ->
                    val values = contentValuesForRow(candidate.row)
                    // CONFLICT_IGNORE preserves user-approved taxonomy records
                    // and all existing foreign-key references. Never delete
                    // canonical IDs to make a rebuild "succeed".
                    if (db.insertWithOnConflict(
                            tableName, null, values, SQLiteDatabase.CONFLICT_IGNORE,
                        ) != -1L) added++ else retained++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return mapOf(
            "ok" to true,
            "rows_imported" to added,
            "rows_preserved" to retained,
            "tables_touched" to rowsByTable.size,
            "destructive_replace_performed" to false,
            "requested_replace_existing" to replaceExisting,
        )
    }

    private fun tableIdColumn(tableName: String): String {
        return when (tableName) {
            FusionDatabaseSchema.TABLE_CHARACTERS -> "character_id"
            FusionDatabaseSchema.TABLE_TAGS -> "tag_id"
            else -> "id"
        }
    }

    private fun contentValuesForRow(row: Map<String, Any?>): ContentValues {
        val values = ContentValues()
        row.forEach { (key, value) ->
            when (value) {
                null -> values.putNull(key)
                is String -> values.put(key, value)
                is Int -> values.put(key, value)
                is Long -> values.put(key, value)
                is Float -> values.put(key, value)
                is Double -> values.put(key, value)
                is Boolean -> values.put(key, value)
                is Short -> values.put(key, value)
                is Byte -> values.put(key, value)
                is ByteArray -> values.put(key, value)
                else -> values.put(key, value.toString())
            }
        }
        return values
    }

    fun previewImport(
        request: ExternalImportRequest,
        callback: ExternalImportProgressCallback? = null,
    ): ExternalImportPreview {
        return importPipeline.previewImport(request, callback)
    }

    fun importDatabase(
        request: ExternalImportRequest,
        callback: ExternalImportProgressCallback? = null,
    ): ExternalImportExecutionResult {
        return importPipeline.importDatabase(request, callback)
    }

    fun cancelImport(importId: String): Boolean {
        return importPipeline.cancelImport(importId)
    }

    fun rollbackImport(
        importId: String? = null,
        callback: ExternalImportProgressCallback? = null,
    ): ExternalImportExecutionResult {
        return importPipeline.rollbackImport(importId, callback)
    }

    fun validateImport(
        request: ExternalImportRequest,
        callback: ExternalImportProgressCallback? = null,
    ): FusionValidationReport {
        return importPipeline.validateImport(request, callback)
    }

    fun validateDatabase(persistRun: Boolean = false): FusionValidationReport {
        val quickCheck = DatabaseHealthChecks.quickCheckResult(database.readableDatabase)
        val issues = mutableListOf<FusionValidationIssue>()

        val foreignKeyViolations = DatabaseHealthChecks.foreignKeyViolationCount(database.readableDatabase)
        if (quickCheck != "ok") {
            issues += FusionValidationIssue(
                code = "sqlite_quick_check",
                severity = "error",
                count = 1,
                details = quickCheck,
            )
        }
        if (foreignKeyViolations > 0) {
            issues += FusionValidationIssue(
                code = "foreign_key_violations",
                severity = "error",
                count = foreignKeyViolations,
                details = "PRAGMA foreign_key_check reported violations.",
            )
        }

        var duplicateIdCount = 0
        tableSpecs.filter { !it.idColumn.isNullOrBlank() }.forEach { spec ->
            val idColumn = spec.idColumn ?: return@forEach
            val duplicates = scalarInt(
                "SELECT COUNT(*) FROM (SELECT $idColumn FROM ${spec.tableName} GROUP BY $idColumn HAVING COUNT(*) > 1)",
            )
            if (duplicates > 0) {
                duplicateIdCount += duplicates
                issues += FusionValidationIssue(
                    code = "duplicate_ids:${spec.tableName}",
                    severity = "error",
                    count = duplicates,
                    details = "Duplicate immutable IDs detected in ${spec.tableName}.",
                )
            }
        }

        var brokenReferenceCount = 0
        val brokenReferenceChecks = listOf(
            "character_primary_series" to """
                SELECT COUNT(*)
                FROM ${FusionDatabaseSchema.TABLE_CHARACTERS} c
                LEFT JOIN ${FusionDatabaseSchema.TABLE_SERIES} s ON s.series_code = c.primary_series_code
                WHERE c.primary_series_code IS NOT NULL AND c.primary_series_code != '' AND s.series_code IS NULL
            """.trimIndent(),
            "series_franchise" to """
                SELECT COUNT(*)
                FROM ${FusionDatabaseSchema.TABLE_SERIES} s
                LEFT JOIN ${FusionDatabaseSchema.TABLE_FRANCHISES} f ON f.franchise_id = s.franchise_id
                WHERE s.franchise_id IS NOT NULL AND s.franchise_id != '' AND f.franchise_id IS NULL
            """.trimIndent(),
            "character_series_links" to """
                SELECT COUNT(*)
                FROM ${FusionDatabaseSchema.TABLE_CHARACTER_SERIES} cs
                LEFT JOIN ${FusionDatabaseSchema.TABLE_CHARACTERS} c ON c.character_id = cs.character_id
                LEFT JOIN ${FusionDatabaseSchema.TABLE_SERIES} s ON s.series_code = cs.series_code
                WHERE c.character_id IS NULL OR s.series_code IS NULL
            """.trimIndent(),
            "image_character_links" to """
                SELECT COUNT(*)
                FROM ${FusionDatabaseSchema.TABLE_IMAGE_CHARACTERS} ic
                LEFT JOIN images i ON i.image_id = ic.image_id
                LEFT JOIN ${FusionDatabaseSchema.TABLE_CHARACTERS} c ON c.character_id = ic.character_id
                WHERE i.image_id IS NULL OR c.character_id IS NULL
            """.trimIndent(),
            "image_series_links" to """
                SELECT COUNT(*)
                FROM ${FusionDatabaseSchema.TABLE_IMAGE_SERIES} isr
                LEFT JOIN images i ON i.image_id = isr.image_id
                LEFT JOIN ${FusionDatabaseSchema.TABLE_SERIES} s ON s.series_code = isr.series_code
                WHERE i.image_id IS NULL OR s.series_code IS NULL
            """.trimIndent(),
            "image_tag_links" to """
                SELECT COUNT(*)
                FROM ${FusionDatabaseSchema.TABLE_IMAGE_TAGS} it
                LEFT JOIN images i ON i.image_id = it.image_id
                LEFT JOIN ${FusionDatabaseSchema.TABLE_TAGS} t ON t.tag_id = it.tag_id
                WHERE i.image_id IS NULL OR t.tag_id IS NULL
            """.trimIndent(),
            "image_outfit_links" to """
                SELECT COUNT(*)
                FROM ${FusionDatabaseSchema.TABLE_IMAGE_OUTFITS} io
                LEFT JOIN images i ON i.image_id = io.image_id
                LEFT JOIN ${FusionDatabaseSchema.TABLE_OUTFITS} o ON o.outfit_id = io.outfit_id
                WHERE i.image_id IS NULL OR o.outfit_id IS NULL
            """.trimIndent(),
            "image_weapon_links" to """
                SELECT COUNT(*)
                FROM ${FusionDatabaseSchema.TABLE_IMAGE_WEAPONS} iw
                LEFT JOIN images i ON i.image_id = iw.image_id
                LEFT JOIN ${FusionDatabaseSchema.TABLE_WEAPONS} w ON w.weapon_id = iw.weapon_id
                WHERE i.image_id IS NULL OR w.weapon_id IS NULL
            """.trimIndent(),
            "image_artist_links" to """
                SELECT COUNT(*)
                FROM ${FusionDatabaseSchema.TABLE_IMAGE_ARTISTS} ia
                LEFT JOIN images i ON i.image_id = ia.image_id
                LEFT JOIN ${FusionDatabaseSchema.TABLE_ARTISTS} a ON a.artist_id = ia.artist_id
                WHERE i.image_id IS NULL OR a.artist_id IS NULL
            """.trimIndent(),
            "collection_image_links" to """
                SELECT COUNT(*)
                FROM ${FusionDatabaseSchema.TABLE_COLLECTION_IMAGES} ci
                LEFT JOIN ${FusionDatabaseSchema.TABLE_COLLECTIONS} c ON c.collection_id = ci.collection_id
                LEFT JOIN images i ON i.image_id = ci.image_id
                WHERE c.collection_id IS NULL OR i.image_id IS NULL
            """.trimIndent(),
            "tag_alias_links" to """
                SELECT COUNT(*)
                FROM ${FusionDatabaseSchema.TABLE_TAG_ALIASES} ta
                LEFT JOIN ${FusionDatabaseSchema.TABLE_TAGS} t ON t.tag_id = ta.tag_id
                WHERE t.tag_id IS NULL
            """.trimIndent(),
        )

        brokenReferenceChecks.forEach { (name, query) ->
            val count = scalarInt(query)
            if (count > 0) {
                brokenReferenceCount += count
                issues += FusionValidationIssue(
                    code = "broken_reference:$name",
                    severity = "error",
                    count = count,
                    details = "Broken references detected in $name.",
                )
            }
        }

        var missingRequiredEntityCount = 0
        val requiredEntityChecks = listOf(
            "franchises_missing" to "SELECT CASE WHEN EXISTS(SELECT 1 FROM ${FusionDatabaseSchema.TABLE_FRANCHISES}) THEN 0 ELSE 1 END",
            "series_missing" to "SELECT CASE WHEN EXISTS(SELECT 1 FROM ${FusionDatabaseSchema.TABLE_SERIES}) THEN 0 ELSE 1 END",
            "characters_missing" to "SELECT CASE WHEN EXISTS(SELECT 1 FROM ${FusionDatabaseSchema.TABLE_CHARACTERS}) THEN 0 ELSE 1 END",
            "tags_missing" to "SELECT CASE WHEN EXISTS(SELECT 1 FROM ${FusionDatabaseSchema.TABLE_TAGS}) THEN 0 ELSE 1 END",
            "character_without_series" to """
                SELECT COUNT(*)
                FROM ${FusionDatabaseSchema.TABLE_CHARACTERS} c
                LEFT JOIN ${FusionDatabaseSchema.TABLE_CHARACTER_SERIES} cs ON cs.character_id = c.character_id
                WHERE (c.primary_series_code IS NULL OR c.primary_series_code = '')
                  AND cs.character_id IS NULL
            """.trimIndent(),
        )
        requiredEntityChecks.forEach { (name, query) ->
            val count = scalarInt(query)
            if (count > 0) {
                missingRequiredEntityCount += count
                issues += FusionValidationIssue(
                    code = "missing_required:$name",
                    severity = "error",
                    count = count,
                    details = "Missing required entity or linkage: $name.",
                )
            }
        }

        var invalidRelationshipCount = 0
        val invalidRelationshipChecks = listOf(
            "self_parent_franchise" to "SELECT COUNT(*) FROM ${FusionDatabaseSchema.TABLE_FRANCHISES} WHERE parent_franchise_id = franchise_id AND parent_franchise_id IS NOT NULL",
            "self_parent_series" to "SELECT COUNT(*) FROM ${FusionDatabaseSchema.TABLE_SERIES} WHERE parent_series_code = series_code AND parent_series_code IS NOT NULL",
            "self_parent_tag" to "SELECT COUNT(*) FROM ${FusionDatabaseSchema.TABLE_TAGS} WHERE parent_tag_id = tag_id AND parent_tag_id IS NOT NULL",
            "self_parent_collection" to "SELECT COUNT(*) FROM ${FusionDatabaseSchema.TABLE_COLLECTIONS} WHERE parent_collection_id = collection_id AND parent_collection_id IS NOT NULL",
            "self_series_link" to "SELECT COUNT(*) FROM ${FusionDatabaseSchema.TABLE_SERIES_LINKS} WHERE series_code = related_series_code",
            "invalid_collection_kind" to "SELECT COUNT(*) FROM ${FusionDatabaseSchema.TABLE_COLLECTIONS} WHERE collection_kind NOT IN ('manual', 'smart', 'dynamic')",
        )
        invalidRelationshipChecks.forEach { (name, query) ->
            val count = scalarInt(query)
            if (count > 0) {
                invalidRelationshipCount += count
                issues += FusionValidationIssue(
                    code = "invalid_relationship:$name",
                    severity = "error",
                    count = count,
                    details = "Invalid relationship data detected: $name.",
                )
            }
        }

        val report = FusionValidationReport(
            valid = issues.isEmpty(),
            generatedAtMs = System.currentTimeMillis(),
            quickCheckResult = quickCheck,
            foreignKeyViolationCount = foreignKeyViolations,
            duplicateIdCount = duplicateIdCount,
            brokenReferenceCount = brokenReferenceCount,
            missingRequiredEntityCount = missingRequiredEntityCount,
            invalidRelationshipCount = invalidRelationshipCount,
            issues = issues,
        )

        if (persistRun) {
            persistValidationReport(report)
        }

        return report
    }

    fun logicalContentCounts(): Map<String, Int> {
        return tableSpecs.associate { spec ->
            spec.tableName to scalarInt("SELECT COUNT(*) FROM ${spec.tableName}")
        }
    }

    fun managementStatus(): Map<String, Any> {
        val report = validateDatabase(persistRun = false)
        val sqliteHealthy = report.quickCheckResult == "ok"
        val fusionIndexCount = scalarInt(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name LIKE 'idx_fusion_%'",
        )
        val searchIndexExists = scalarInt(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'image_fts'",
        ) > 0
        val searchIndexRows = if (searchIndexExists) scalarInt("SELECT COUNT(*) FROM image_fts") else 0
        val imageCount = scalarInt("SELECT COUNT(*) FROM images")
        val ftsHealthy = searchIndexExists && searchIndexRows == imageCount
        val lastIntegrityCheckMs = scalarLong("SELECT COALESCE(MAX(checked_at_ms), 0) FROM ${FusionDatabaseSchema.TABLE_INTEGRITY_RUNS}")
        return mapOf(
            "fusion_version" to FusionDatabaseSchema.FUSION_SCHEMA_VERSION,
            "database_status" to if (report.valid) "Ready" else "Needs attention",
            "database_health" to if (report.valid) "Healthy" else "Issues detected",
            "rebuild_required" to !report.valid,
            "fusion_entries" to logicalContentCounts().values.sum(),
            "logical_table_counts" to logicalContentCounts(),
            "sqlite_health" to if (sqliteHealthy) "Healthy" else "Corrupted",
            "database_integrity" to if (sqliteHealthy) "Healthy" else "Failed: ${report.quickCheckResult}",
            "index_integrity" to if (sqliteHealthy && fusionIndexCount > 0) "Healthy ($fusionIndexCount Fusion indexes)" else "Needs attention",
            "fts_integrity" to when {
                !searchIndexExists -> "Unavailable"
                ftsHealthy -> "Healthy ($searchIndexRows entries)"
                else -> "Needs rebuild ($searchIndexRows of $imageCount entries)"
            },
            "missing_references" to report.brokenReferenceCount,
            "orphan_entries" to report.brokenReferenceCount,
            "corrupted_records" to (report.duplicateIdCount + report.missingRequiredEntityCount + report.invalidRelationshipCount),
            "knowledge_consistency" to if (report.brokenReferenceCount == 0 && report.missingRequiredEntityCount == 0) "Consistent" else "Needs attention",
            "search_index_status" to if (ftsHealthy) "Ready ($searchIndexRows entries)" else "Needs attention",
            "embedding_index_status" to "Not configured",
            "cache_status" to "Managed automatically",
            "last_integrity_check_ms" to lastIntegrityCheckMs,
            "validation" to report.toMap(),
        )
    }

    private fun readRows(spec: FusionTableSpec): JSONArray {
        val rows = JSONArray()
        val sql = "SELECT ${spec.columns.joinToString(",") { it.name }} FROM ${spec.tableName} ORDER BY ROWID ASC"
        database.readableDatabase.rawQuery(sql, emptyArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val row = JSONObject()
                spec.columns.forEachIndexed { index, column ->
                    if (cursor.isNull(index)) {
                        row.put(column.name, JSONObject.NULL)
                    } else {
                        when (column.type) {
                            FusionColumnType.TEXT -> row.put(column.name, cursor.getString(index))
                            FusionColumnType.INTEGER -> row.put(column.name, cursor.getLong(index))
                            FusionColumnType.REAL -> row.put(column.name, cursor.getDouble(index))
                            FusionColumnType.BOOLEAN -> row.put(column.name, cursor.getLong(index) != 0L)
                        }
                    }
                }
                rows.put(row)
            }
        }
        return rows
    }

    private fun readLegacyImageSnapshot(): JSONArray {
        val rows = JSONArray()
        val sql = """
            SELECT image_id, uri, filename, folder_uri, favorite, rating, tags_text, active, scanned_at_ms
            FROM images
            ORDER BY image_id ASC
        """.trimIndent()
        database.readableDatabase.rawQuery(sql, emptyArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val row = JSONObject()
                row.put("image_id", cursor.getInt(0))
                row.put("uri", cursor.getString(1))
                row.put("filename", cursor.getString(2))
                row.put("folder_uri", cursor.getString(3))
                row.put("favorite", cursor.getInt(4) == 1)
                row.put("rating", cursor.getInt(5))
                row.put("tags_text", cursor.getString(6) ?: "")
                row.put("active", cursor.getInt(7) == 1)
                row.put("scanned_at_ms", if (cursor.isNull(8)) JSONObject.NULL else cursor.getLong(8))
                rows.put(row)
            }
        }
        return rows
    }

    private fun scalarInt(sql: String, args: Array<String> = emptyArray()): Int {
        return database.readableDatabase.rawQuery(sql, args).use { cursor ->
            if (!cursor.moveToFirst()) {
                0
            } else {
                cursor.getInt(0)
            }
        }
    }

    private fun scalarLong(sql: String, args: Array<String> = emptyArray()): Long {
        return database.readableDatabase.rawQuery(sql, args).use { cursor ->
            if (!cursor.moveToFirst()) 0L else cursor.getLong(0)
        }
    }

    private fun persistValidationReport(report: FusionValidationReport) {
        val summary = JSONObject(report.toMap()).toString()
        database.writableDatabase.execSQL(
            """
            INSERT INTO ${FusionDatabaseSchema.TABLE_INTEGRITY_RUNS}(
                checked_at_ms,
                quick_check_result,
                foreign_key_violations,
                issue_count,
                summary_json
            ) VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                report.generatedAtMs,
                report.quickCheckResult,
                report.foreignKeyViolationCount,
                report.issues.sumOf { it.count },
                summary,
            ),
        )
    }
}
