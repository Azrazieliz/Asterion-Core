package com.ailm.android.runtime

import android.database.sqlite.SQLiteDatabase

object FusionDatabaseSchema {
    const val FUSION_SCHEMA_VERSION = 2

    const val TABLE_SCHEMA_HISTORY = "fusion_schema_history"
    const val TABLE_INTEGRITY_RUNS = "fusion_integrity_runs"
    const val TABLE_IMPORT_RUNS = "fusion_import_runs"

    const val TABLE_FRANCHISES = "fusion_franchises"
    const val TABLE_SERIES = "fusion_series"
    const val TABLE_SERIES_LINKS = "fusion_series_links"
    const val TABLE_CHARACTERS = "fusion_characters"
    const val TABLE_CHARACTER_SERIES = "fusion_character_series"
    const val TABLE_CHARACTER_SHEETS = "fusion_character_sheets"
    const val TABLE_CHARACTER_FEATURES = "fusion_character_features"
    const val TABLE_TAGS = "fusion_tags"
    const val TABLE_TAG_ALIASES = "fusion_tag_aliases"
    const val TABLE_OUTFITS = "fusion_outfits"
    const val TABLE_WEAPONS = "fusion_weapons"
    const val TABLE_ARTISTS = "fusion_artists"
    const val TABLE_COLLECTIONS = "fusion_collections"
    const val TABLE_COLLECTION_IMAGES = "fusion_collection_images"
    const val TABLE_CHARACTER_OUTFITS = "fusion_character_outfits"
    const val TABLE_CHARACTER_WEAPONS = "fusion_character_weapons"
    const val TABLE_IMAGE_PROFILES = "fusion_image_profiles"
    const val TABLE_IMAGE_SERIES = "fusion_image_series"
    const val TABLE_IMAGE_CHARACTERS = "fusion_image_characters"
    const val TABLE_IMAGE_TAGS = "fusion_image_tags"
    const val TABLE_IMAGE_OUTFITS = "fusion_image_outfits"
    const val TABLE_IMAGE_WEAPONS = "fusion_image_weapons"
    const val TABLE_IMAGE_ARTISTS = "fusion_image_artists"
    const val TABLE_IMAGE_FINGERPRINTS = "fusion_image_fingerprints"

    val DOMAIN_TABLE_ORDER: List<String> = listOf(
        TABLE_FRANCHISES,
        TABLE_SERIES,
        TABLE_SERIES_LINKS,
        TABLE_CHARACTERS,
        TABLE_CHARACTER_SERIES,
        TABLE_TAGS,
        TABLE_TAG_ALIASES,
        TABLE_OUTFITS,
        TABLE_WEAPONS,
        TABLE_ARTISTS,
        TABLE_COLLECTIONS,
        TABLE_CHARACTER_OUTFITS,
        TABLE_CHARACTER_WEAPONS,
        TABLE_IMAGE_PROFILES,
        TABLE_IMAGE_SERIES,
        TABLE_IMAGE_CHARACTERS,
        TABLE_IMAGE_TAGS,
        TABLE_IMAGE_OUTFITS,
        TABLE_IMAGE_WEAPONS,
        TABLE_IMAGE_ARTISTS,
        TABLE_COLLECTION_IMAGES,
    )

    fun createAll(db: SQLiteDatabase, ifNotExists: Boolean) {
        createFusionTables(db, ifNotExists)
        createCharacterSheetTable(db)
        createFusionIndexes(db, ifNotExists)
        createImmutableIdTriggers(db, ifNotExists)
        createLegacyImageSyncTriggers(db, ifNotExists)
    }

    fun ensureArtifacts(db: SQLiteDatabase) {
        createFusionTables(db, ifNotExists = true)
        createCharacterSheetTable(db)
        createFusionIndexes(db, ifNotExists = true)
        createImmutableIdTriggers(db, ifNotExists = true)
        createLegacyImageSyncTriggers(db, ifNotExists = true)
    }

    /** Non-destructive Fusion-owned sheet index, keeping existing sheet assets on disk. */
    private fun createCharacterSheetTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS fusion_character_sheets (
                sheet_asset_id TEXT PRIMARY KEY,
                character_id TEXT NOT NULL,
                asset_path TEXT NOT NULL,
                sha256 TEXT NOT NULL DEFAULT '',
                updated_at_ms INTEGER NOT NULL,
                FOREIGN KEY(character_id) REFERENCES fusion_characters(character_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fusion_sheets_character ON fusion_character_sheets(character_id)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS fusion_character_features (
                character_id TEXT NOT NULL,
                feature_id TEXT NOT NULL,
                canonical_weight REAL NOT NULL DEFAULT 1.0,
                PRIMARY KEY(character_id, feature_id),
                FOREIGN KEY(character_id) REFERENCES fusion_characters(character_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE,
                FOREIGN KEY(feature_id) REFERENCES fusion_tags(tag_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fusion_char_features_by_tag ON fusion_character_features(feature_id)")
        // Indexed aliases preserve ambiguous names across series; a lookup
        // must never scan/decode every character recognition JSON profile.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS fusion_character_aliases (
                character_id TEXT NOT NULL,
                alias_value TEXT NOT NULL COLLATE NOCASE,
                PRIMARY KEY(character_id, alias_value),
                FOREIGN KEY(character_id) REFERENCES fusion_characters(character_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fusion_character_alias_value ON fusion_character_aliases(alias_value COLLATE NOCASE)")
    }

    fun migrateFromLegacy(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()

        db.execSQL(
            """
            INSERT OR IGNORE INTO $TABLE_IMAGE_PROFILES(
                image_id,
                source_uri,
                thumbnail_uri,
                favorite,
                rating,
                tags_text,
                metadata_json,
                updated_at_ms
            )
            SELECT
                image_id,
                uri,
                uri,
                favorite,
                rating,
                tags_text,
                '{}',
                COALESCE(scanned_at_ms, $now)
            FROM images
            """.trimIndent(),
        )

        db.execSQL(
            """
            INSERT OR IGNORE INTO $TABLE_COLLECTIONS(
                collection_id,
                collection_name,
                collection_kind,
                parent_collection_id,
                smart_filter_json,
                metadata_json,
                created_at_ms,
                updated_at_ms
            )
            SELECT
                'folder::' || folder_uri,
                CASE WHEN folder_uri = '' THEN '(root)' ELSE folder_uri END,
                'dynamic',
                NULL,
                '{}',
                '{}',
                added_at_ms,
                COALESCE(last_scan_completed_ms, added_at_ms)
            FROM library_folders
            """.trimIndent(),
        )

        db.execSQL(
            """
            INSERT OR IGNORE INTO $TABLE_COLLECTION_IMAGES(
                collection_id,
                image_id,
                ordinal,
                added_at_ms
            )
            SELECT
                'folder::' || folder_uri,
                image_id,
                imported_order,
                COALESCE(scanned_at_ms, $now)
            FROM images
            WHERE folder_uri IS NOT NULL
            """.trimIndent(),
        )

        recordSchemaHistory(db, schemaVersion = 6, notes = "Fusion schema bootstrap")
    }

    fun recordSchemaHistory(db: SQLiteDatabase, schemaVersion: Int, notes: String) {
        val now = System.currentTimeMillis()
        db.execSQL(
            "INSERT OR REPLACE INTO $TABLE_SCHEMA_HISTORY(version, applied_at_ms, notes) VALUES (?, ?, ?)",
            arrayOf(schemaVersion, now, notes),
        )
    }

    fun recordIntegritySnapshot(db: SQLiteDatabase): Pair<String, Int> {
        val quickCheck = DatabaseHealthChecks.quickCheckResult(db)
        val fkViolations = DatabaseHealthChecks.foreignKeyViolationCount(db)

        val now = System.currentTimeMillis()
        val summaryJson = if (fkViolations == 0) {
            "{}"
        } else {
            "{\"foreign_key_violations\":$fkViolations}"
        }
        db.execSQL(
            """
            INSERT INTO $TABLE_INTEGRITY_RUNS(
                checked_at_ms,
                quick_check_result,
                foreign_key_violations,
                issue_count,
                summary_json
            ) VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(now, quickCheck, fkViolations, fkViolations, summaryJson),
        )

        return quickCheck to fkViolations
    }

    private fun createFusionTables(db: SQLiteDatabase, ifNotExists: Boolean) {
        val ifNotExistsSql = if (ifNotExists) "IF NOT EXISTS " else ""

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_SCHEMA_HISTORY (
                version INTEGER PRIMARY KEY,
                applied_at_ms INTEGER NOT NULL,
                notes TEXT NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_INTEGRITY_RUNS (
                run_id INTEGER PRIMARY KEY AUTOINCREMENT,
                checked_at_ms INTEGER NOT NULL,
                quick_check_result TEXT NOT NULL,
                foreign_key_violations INTEGER NOT NULL DEFAULT 0,
                issue_count INTEGER NOT NULL DEFAULT 0,
                summary_json TEXT NOT NULL DEFAULT '{}'
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_IMPORT_RUNS (
                import_id TEXT PRIMARY KEY,
                started_at_ms INTEGER NOT NULL,
                finished_at_ms INTEGER,
                status TEXT NOT NULL,
                source_type TEXT NOT NULL,
                conflict_strategy TEXT NOT NULL,
                replace_existing INTEGER NOT NULL DEFAULT 0,
                summary_json TEXT NOT NULL DEFAULT '{}',
                before_snapshot_json TEXT,
                after_snapshot_json TEXT,
                error_message TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_FRANCHISES (
                franchise_id TEXT PRIMARY KEY,
                parent_franchise_id TEXT,
                display_name TEXT NOT NULL,
                canonical_slug TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                metadata_json TEXT NOT NULL DEFAULT '{}',
                workbook_row INTEGER,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                FOREIGN KEY(parent_franchise_id) REFERENCES $TABLE_FRANCHISES(franchise_id)
                    ON UPDATE RESTRICT ON DELETE SET NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_SERIES (
                series_code TEXT PRIMARY KEY,
                franchise_id TEXT,
                parent_series_code TEXT,
                canonical_title TEXT NOT NULL,
                localized_title TEXT NOT NULL DEFAULT '',
                aliases_json TEXT NOT NULL DEFAULT '[]',
                metadata_json TEXT NOT NULL DEFAULT '{}',
                workbook_row INTEGER,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                FOREIGN KEY(franchise_id) REFERENCES $TABLE_FRANCHISES(franchise_id)
                    ON UPDATE RESTRICT ON DELETE SET NULL,
                FOREIGN KEY(parent_series_code) REFERENCES $TABLE_SERIES(series_code)
                    ON UPDATE RESTRICT ON DELETE SET NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_SERIES_LINKS (
                series_code TEXT NOT NULL,
                related_series_code TEXT NOT NULL,
                relation_kind TEXT NOT NULL DEFAULT 'related',
                metadata_json TEXT NOT NULL DEFAULT '{}',
                added_at_ms INTEGER NOT NULL,
                PRIMARY KEY(series_code, related_series_code),
                FOREIGN KEY(series_code) REFERENCES $TABLE_SERIES(series_code)
                    ON UPDATE RESTRICT ON DELETE CASCADE,
                FOREIGN KEY(related_series_code) REFERENCES $TABLE_SERIES(series_code)
                    ON UPDATE RESTRICT ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_CHARACTERS (
                character_id TEXT PRIMARY KEY,
                primary_series_code TEXT,
                canonical_name TEXT NOT NULL,
                localized_name TEXT NOT NULL DEFAULT '',
                romaji_name TEXT NOT NULL DEFAULT '',
                gender TEXT NOT NULL DEFAULT '',
                description TEXT NOT NULL DEFAULT '',
                recognition_profile_json TEXT NOT NULL DEFAULT '{}',
                metadata_json TEXT NOT NULL DEFAULT '{}',
                workbook_row INTEGER,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                FOREIGN KEY(primary_series_code) REFERENCES $TABLE_SERIES(series_code)
                    ON UPDATE RESTRICT ON DELETE SET NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_CHARACTER_SERIES (
                character_id TEXT NOT NULL,
                series_code TEXT NOT NULL,
                relation_kind TEXT NOT NULL DEFAULT 'primary',
                added_at_ms INTEGER NOT NULL,
                PRIMARY KEY(character_id, series_code),
                FOREIGN KEY(character_id) REFERENCES $TABLE_CHARACTERS(character_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE,
                FOREIGN KEY(series_code) REFERENCES $TABLE_SERIES(series_code)
                    ON UPDATE RESTRICT ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_TAGS (
                tag_id TEXT PRIMARY KEY,
                canonical_name TEXT NOT NULL,
                category TEXT NOT NULL DEFAULT '',
                parent_tag_id TEXT,
                metadata_json TEXT NOT NULL DEFAULT '{}',
                workbook_row INTEGER,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                FOREIGN KEY(parent_tag_id) REFERENCES $TABLE_TAGS(tag_id)
                    ON UPDATE RESTRICT ON DELETE SET NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_TAG_ALIASES (
                alias_id TEXT PRIMARY KEY,
                tag_id TEXT NOT NULL,
                alias_value TEXT NOT NULL,
                locale TEXT NOT NULL DEFAULT '',
                created_at_ms INTEGER NOT NULL,
                FOREIGN KEY(tag_id) REFERENCES $TABLE_TAGS(tag_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE,
                UNIQUE(tag_id, alias_value)
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_OUTFITS (
                outfit_id TEXT PRIMARY KEY,
                canonical_name TEXT NOT NULL,
                metadata_json TEXT NOT NULL DEFAULT '{}',
                workbook_row INTEGER,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_WEAPONS (
                weapon_id TEXT PRIMARY KEY,
                canonical_name TEXT NOT NULL,
                metadata_json TEXT NOT NULL DEFAULT '{}',
                workbook_row INTEGER,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_ARTISTS (
                artist_id TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                aliases_json TEXT NOT NULL DEFAULT '[]',
                metadata_json TEXT NOT NULL DEFAULT '{}',
                workbook_row INTEGER,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_COLLECTIONS (
                collection_id TEXT PRIMARY KEY,
                collection_name TEXT NOT NULL,
                collection_kind TEXT NOT NULL,
                parent_collection_id TEXT,
                smart_filter_json TEXT NOT NULL DEFAULT '{}',
                metadata_json TEXT NOT NULL DEFAULT '{}',
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                FOREIGN KEY(parent_collection_id) REFERENCES $TABLE_COLLECTIONS(collection_id)
                    ON UPDATE RESTRICT ON DELETE SET NULL,
                CHECK(collection_kind IN ('manual', 'smart', 'dynamic'))
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_COLLECTION_IMAGES (
                collection_id TEXT NOT NULL,
                image_id INTEGER NOT NULL,
                ordinal INTEGER NOT NULL DEFAULT 0,
                added_at_ms INTEGER NOT NULL,
                PRIMARY KEY(collection_id, image_id),
                FOREIGN KEY(collection_id) REFERENCES $TABLE_COLLECTIONS(collection_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE,
                FOREIGN KEY(image_id) REFERENCES images(image_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_CHARACTER_OUTFITS (
                character_id TEXT NOT NULL,
                outfit_id TEXT NOT NULL,
                relation_kind TEXT NOT NULL DEFAULT 'canonical',
                metadata_json TEXT NOT NULL DEFAULT '{}',
                added_at_ms INTEGER NOT NULL,
                PRIMARY KEY(character_id, outfit_id),
                FOREIGN KEY(character_id) REFERENCES $TABLE_CHARACTERS(character_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE,
                FOREIGN KEY(outfit_id) REFERENCES $TABLE_OUTFITS(outfit_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_CHARACTER_WEAPONS (
                character_id TEXT NOT NULL,
                weapon_id TEXT NOT NULL,
                relation_kind TEXT NOT NULL DEFAULT 'canonical',
                metadata_json TEXT NOT NULL DEFAULT '{}',
                added_at_ms INTEGER NOT NULL,
                PRIMARY KEY(character_id, weapon_id),
                FOREIGN KEY(character_id) REFERENCES $TABLE_CHARACTERS(character_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE,
                FOREIGN KEY(weapon_id) REFERENCES $TABLE_WEAPONS(weapon_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_IMAGE_PROFILES (
                image_id INTEGER PRIMARY KEY,
                source_uri TEXT NOT NULL,
                thumbnail_uri TEXT NOT NULL DEFAULT '',
                favorite INTEGER NOT NULL DEFAULT 0,
                rating INTEGER NOT NULL DEFAULT 0,
                tags_text TEXT NOT NULL DEFAULT '',
                metadata_json TEXT NOT NULL DEFAULT '{}',
                updated_at_ms INTEGER NOT NULL,
                FOREIGN KEY(image_id) REFERENCES images(image_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_IMAGE_SERIES (
                image_id INTEGER NOT NULL,
                series_code TEXT NOT NULL,
                confidence REAL NOT NULL DEFAULT 1.0,
                source TEXT NOT NULL DEFAULT 'manual',
                added_at_ms INTEGER NOT NULL,
                PRIMARY KEY(image_id, series_code),
                FOREIGN KEY(image_id) REFERENCES images(image_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE,
                FOREIGN KEY(series_code) REFERENCES $TABLE_SERIES(series_code)
                    ON UPDATE RESTRICT ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_IMAGE_CHARACTERS (
                image_id INTEGER NOT NULL,
                character_id TEXT NOT NULL,
                confidence REAL NOT NULL DEFAULT 1.0,
                source TEXT NOT NULL DEFAULT 'manual',
                added_at_ms INTEGER NOT NULL,
                PRIMARY KEY(image_id, character_id),
                FOREIGN KEY(image_id) REFERENCES images(image_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE,
                FOREIGN KEY(character_id) REFERENCES $TABLE_CHARACTERS(character_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_IMAGE_TAGS (
                image_id INTEGER NOT NULL,
                tag_id TEXT NOT NULL,
                confidence REAL NOT NULL DEFAULT 1.0,
                source TEXT NOT NULL DEFAULT 'manual',
                added_at_ms INTEGER NOT NULL,
                PRIMARY KEY(image_id, tag_id),
                FOREIGN KEY(image_id) REFERENCES images(image_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE,
                FOREIGN KEY(tag_id) REFERENCES $TABLE_TAGS(tag_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_IMAGE_OUTFITS (
                image_id INTEGER NOT NULL,
                outfit_id TEXT NOT NULL,
                confidence REAL NOT NULL DEFAULT 1.0,
                source TEXT NOT NULL DEFAULT 'manual',
                added_at_ms INTEGER NOT NULL,
                PRIMARY KEY(image_id, outfit_id),
                FOREIGN KEY(image_id) REFERENCES images(image_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE,
                FOREIGN KEY(outfit_id) REFERENCES $TABLE_OUTFITS(outfit_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_IMAGE_WEAPONS (
                image_id INTEGER NOT NULL,
                weapon_id TEXT NOT NULL,
                confidence REAL NOT NULL DEFAULT 1.0,
                source TEXT NOT NULL DEFAULT 'manual',
                added_at_ms INTEGER NOT NULL,
                PRIMARY KEY(image_id, weapon_id),
                FOREIGN KEY(image_id) REFERENCES images(image_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE,
                FOREIGN KEY(weapon_id) REFERENCES $TABLE_WEAPONS(weapon_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_IMAGE_ARTISTS (
                image_id INTEGER NOT NULL,
                artist_id TEXT NOT NULL,
                confidence REAL NOT NULL DEFAULT 1.0,
                source TEXT NOT NULL DEFAULT 'manual',
                added_at_ms INTEGER NOT NULL,
                PRIMARY KEY(image_id, artist_id),
                FOREIGN KEY(image_id) REFERENCES images(image_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE,
                FOREIGN KEY(artist_id) REFERENCES $TABLE_ARTISTS(artist_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_IMAGE_FINGERPRINTS (
                image_id INTEGER PRIMARY KEY,
                sha256 TEXT NOT NULL DEFAULT '',
                perceptual_hash TEXT NOT NULL DEFAULT '',
                pixel_width INTEGER NOT NULL DEFAULT 0,
                pixel_height INTEGER NOT NULL DEFAULT 0,
                source_size_bytes INTEGER NOT NULL DEFAULT 0,
                updated_at_ms INTEGER NOT NULL,
                FOREIGN KEY(image_id) REFERENCES images(image_id)
                    ON UPDATE RESTRICT ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }

    private fun createFusionIndexes(db: SQLiteDatabase, ifNotExists: Boolean) {
        val ifNotExistsSql = if (ifNotExists) "IF NOT EXISTS " else ""

        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_integrity_checked ON $TABLE_INTEGRITY_RUNS(checked_at_ms DESC)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_import_runs_started ON $TABLE_IMPORT_RUNS(started_at_ms DESC)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_import_runs_status ON $TABLE_IMPORT_RUNS(status)")

        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_franchises_parent ON $TABLE_FRANCHISES(parent_franchise_id)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_franchises_name ON $TABLE_FRANCHISES(display_name COLLATE NOCASE)")

        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_series_franchise ON $TABLE_SERIES(franchise_id)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_series_parent ON $TABLE_SERIES(parent_series_code)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_series_title ON $TABLE_SERIES(canonical_title COLLATE NOCASE)")

        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_series_links_related ON $TABLE_SERIES_LINKS(related_series_code)")

        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_characters_series ON $TABLE_CHARACTERS(primary_series_code)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_characters_name ON $TABLE_CHARACTERS(canonical_name COLLATE NOCASE)")

        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_character_series_series ON $TABLE_CHARACTER_SERIES(series_code)")

        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_tags_parent ON $TABLE_TAGS(parent_tag_id)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_tags_name ON $TABLE_TAGS(canonical_name COLLATE NOCASE)")

        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_tag_aliases_tag ON $TABLE_TAG_ALIASES(tag_id)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_tag_aliases_value ON $TABLE_TAG_ALIASES(alias_value COLLATE NOCASE)")

        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_outfits_name ON $TABLE_OUTFITS(canonical_name COLLATE NOCASE)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_weapons_name ON $TABLE_WEAPONS(canonical_name COLLATE NOCASE)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_artists_name ON $TABLE_ARTISTS(display_name COLLATE NOCASE)")

        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_collections_kind ON $TABLE_COLLECTIONS(collection_kind)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_collections_parent ON $TABLE_COLLECTIONS(parent_collection_id)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_collections_name ON $TABLE_COLLECTIONS(collection_name COLLATE NOCASE)")

        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_collection_images_image ON $TABLE_COLLECTION_IMAGES(image_id)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_collection_images_ordinal ON $TABLE_COLLECTION_IMAGES(collection_id, ordinal)")

        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_character_outfits_outfit ON $TABLE_CHARACTER_OUTFITS(outfit_id)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_character_weapons_weapon ON $TABLE_CHARACTER_WEAPONS(weapon_id)")

        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_image_profiles_favorite ON $TABLE_IMAGE_PROFILES(favorite)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_image_profiles_rating ON $TABLE_IMAGE_PROFILES(rating)")

        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_image_series_series ON $TABLE_IMAGE_SERIES(series_code)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_image_characters_character ON $TABLE_IMAGE_CHARACTERS(character_id)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_image_tags_tag ON $TABLE_IMAGE_TAGS(tag_id)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_image_outfits_outfit ON $TABLE_IMAGE_OUTFITS(outfit_id)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_image_weapons_weapon ON $TABLE_IMAGE_WEAPONS(weapon_id)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_image_artists_artist ON $TABLE_IMAGE_ARTISTS(artist_id)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_image_fingerprints_sha256 ON $TABLE_IMAGE_FINGERPRINTS(sha256)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_image_fingerprints_phash ON $TABLE_IMAGE_FINGERPRINTS(perceptual_hash)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_fusion_image_fingerprints_dimensions ON $TABLE_IMAGE_FINGERPRINTS(pixel_width, pixel_height)")
    }

    private fun createImmutableIdTriggers(db: SQLiteDatabase, ifNotExists: Boolean) {
        val ifNotExistsSql = if (ifNotExists) "IF NOT EXISTS " else ""

        createImmutableTrigger(db, ifNotExistsSql, TABLE_FRANCHISES, "franchise_id")
        createImmutableTrigger(db, ifNotExistsSql, TABLE_SERIES, "series_code")
        createImmutableTrigger(db, ifNotExistsSql, TABLE_CHARACTERS, "character_id")
        createImmutableTrigger(db, ifNotExistsSql, TABLE_TAGS, "tag_id")
        createImmutableTrigger(db, ifNotExistsSql, TABLE_TAG_ALIASES, "alias_id")
        createImmutableTrigger(db, ifNotExistsSql, TABLE_OUTFITS, "outfit_id")
        createImmutableTrigger(db, ifNotExistsSql, TABLE_WEAPONS, "weapon_id")
        createImmutableTrigger(db, ifNotExistsSql, TABLE_ARTISTS, "artist_id")
        createImmutableTrigger(db, ifNotExistsSql, TABLE_COLLECTIONS, "collection_id")
        createImmutableTrigger(db, ifNotExistsSql, TABLE_IMAGE_PROFILES, "image_id")
    }

    private fun createImmutableTrigger(
        db: SQLiteDatabase,
        ifNotExistsSql: String,
        tableName: String,
        idColumn: String,
    ) {
        db.execSQL(
            """
            CREATE TRIGGER ${ifNotExistsSql}trg_${tableName}_${idColumn}_immutable
            BEFORE UPDATE OF $idColumn ON $tableName
            BEGIN
                SELECT RAISE(ABORT, '$idColumn is immutable');
            END
            """.trimIndent(),
        )
    }

    private fun createLegacyImageSyncTriggers(db: SQLiteDatabase, ifNotExists: Boolean) {
        val ifNotExistsSql = if (ifNotExists) "IF NOT EXISTS " else ""

        db.execSQL(
            """
            CREATE TRIGGER ${ifNotExistsSql}trg_images_ai_fusion_profile
            AFTER INSERT ON images
            BEGIN
                INSERT OR REPLACE INTO $TABLE_IMAGE_PROFILES(
                    image_id,
                    source_uri,
                    thumbnail_uri,
                    favorite,
                    rating,
                    tags_text,
                    metadata_json,
                    updated_at_ms
                ) VALUES (
                    new.image_id,
                    new.uri,
                    new.uri,
                    new.favorite,
                    new.rating,
                    new.tags_text,
                    COALESCE((SELECT metadata_json FROM $TABLE_IMAGE_PROFILES WHERE image_id = new.image_id), '{}'),
                    COALESCE(new.scanned_at_ms, CAST(strftime('%s','now') AS INTEGER) * 1000)
                );
            END
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TRIGGER ${ifNotExistsSql}trg_images_au_fusion_profile
            AFTER UPDATE OF uri, favorite, rating, tags_text, scanned_at_ms ON images
            BEGIN
                INSERT OR REPLACE INTO $TABLE_IMAGE_PROFILES(
                    image_id,
                    source_uri,
                    thumbnail_uri,
                    favorite,
                    rating,
                    tags_text,
                    metadata_json,
                    updated_at_ms
                ) VALUES (
                    new.image_id,
                    new.uri,
                    new.uri,
                    new.favorite,
                    new.rating,
                    new.tags_text,
                    COALESCE((SELECT metadata_json FROM $TABLE_IMAGE_PROFILES WHERE image_id = new.image_id), '{}'),
                    COALESCE(new.scanned_at_ms, CAST(strftime('%s','now') AS INTEGER) * 1000)
                );
            END
            """.trimIndent(),
        )
    }
}
