package com.ailm.android.runtime

import android.content.Context
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.ailm.android.runtime.ai.LocalAiSchema

class LocalDatabase(
    context: Context,
) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("PRAGMA foreign_keys = ON")
    }

    override fun onCreate(db: SQLiteDatabase) {
        createSchema(db)
        FusionDatabaseSchema.createAll(db, ifNotExists = false)
        LocalAiSchema.createAll(db, ifNotExists = false)
        FusionDatabaseSchema.recordSchemaHistory(db, schemaVersion = DB_VERSION, notes = "Initial schema creation")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 4) {
            migrateToV4(db)
        }
        if (oldVersion < 5) {
            migrateToV5(db)
        }
        if (oldVersion < 6) {
            migrateToV6(db)
        }
        if (oldVersion < 7) {
            migrateToV7(db)
        }
        if (oldVersion < 8) {
            migrateToV8(db)
        }
        if (oldVersion < 9) {
            migrateToV9(db)
        }
        if (oldVersion < 10) {
            migrateToV10(db)
        }
        if (oldVersion < 11) {
            migrateToV11(db)
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        FusionDatabaseSchema.ensureArtifacts(db)
        LocalAiSchema.ensureArtifacts(db)
        runCatching { FusionDatabaseSchema.recordIntegritySnapshot(db) }
            .onFailure { error -> Log.w(DB_TAG, "Fusion integrity snapshot failed", error) }
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE library_folders (
                folder_uri TEXT PRIMARY KEY,
                enabled INTEGER NOT NULL DEFAULT 1,
                added_at_ms INTEGER NOT NULL,
                last_scan_started_ms INTEGER,
                last_scan_completed_ms INTEGER,
                last_scan_status TEXT,
                last_scan_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE images (
                image_id INTEGER PRIMARY KEY AUTOINCREMENT,
                uri TEXT NOT NULL UNIQUE,
                filename TEXT NOT NULL,
                folder_uri TEXT NOT NULL,
                parent_uri TEXT,
                size_bytes INTEGER,
                width INTEGER,
                height INTEGER,
                created_at_ms INTEGER,
                modified_at_ms INTEGER,
                extension TEXT NOT NULL DEFAULT '',
                mime_type TEXT NOT NULL DEFAULT '',
                resolution_text TEXT,
                aspect_ratio REAL,
                orientation TEXT,
                folder_name TEXT NOT NULL DEFAULT '',
                relative_path TEXT NOT NULL DEFAULT '',
                imported_order INTEGER NOT NULL,
                favorite INTEGER NOT NULL DEFAULT 0,
                rating INTEGER NOT NULL DEFAULT 0,
                tags_text TEXT NOT NULL DEFAULT '',
                taxonomy_text TEXT NOT NULL DEFAULT '',
                metadata_text TEXT NOT NULL DEFAULT '',
                active INTEGER NOT NULL DEFAULT 1,
                last_modified_ms INTEGER,
                scanned_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_images_folder_uri ON images(folder_uri)")
        db.execSQL("CREATE INDEX idx_images_active ON images(active)")
        db.execSQL("CREATE INDEX idx_images_imported_order ON images(imported_order)")
        db.execSQL("CREATE INDEX idx_images_filename ON images(filename)")
        db.execSQL("CREATE INDEX idx_images_modified_at ON images(modified_at_ms)")
        db.execSQL("CREATE INDEX idx_images_size ON images(size_bytes)")
        db.execSQL("CREATE INDEX idx_images_resolution ON images(width, height)")
        db.execSQL("CREATE INDEX idx_images_parent_uri ON images(parent_uri)")
        createLargeLibraryIndexes(db, ifNotExists = false)

        createImageSearchIndexArtifacts(db, ifNotExists = false, rebuild = false)

        db.execSQL(
            """
            CREATE TABLE scan_runs (
                scan_id INTEGER PRIMARY KEY AUTOINCREMENT,
                folder_uri TEXT NOT NULL,
                started_at_ms INTEGER NOT NULL,
                completed_at_ms INTEGER,
                status TEXT NOT NULL,
                discovered_count INTEGER NOT NULL DEFAULT 0,
                skipped_count INTEGER NOT NULL DEFAULT 0,
                error_message TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_scan_runs_folder ON scan_runs(folder_uri)")
        db.execSQL("CREATE INDEX idx_scan_runs_started ON scan_runs(started_at_ms)")

        db.execSQL(
            """
            CREATE TABLE review_items (
                review_id INTEGER PRIMARY KEY AUTOINCREMENT,
                image_uri TEXT NOT NULL UNIQUE,
                image_id INTEGER,
                status TEXT NOT NULL DEFAULT 'pending',
                review_type TEXT NOT NULL DEFAULT 'generic',
                reason TEXT,
                payload_json TEXT NOT NULL DEFAULT '{}',
                correction_json TEXT NOT NULL DEFAULT '{}',
                last_updated_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_review_status ON review_items(status)")
        createAutomationV10Artifacts(db, ifNotExists = false)

        db.execSQL(
            """
            CREATE TABLE settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE thumbnail_cache (
                image_uri TEXT PRIMARY KEY,
                cache_path TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                last_access_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_thumbnail_access ON thumbnail_cache(last_access_ms)")
    }

    private fun migrateToV4(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS library_folders (
                folder_uri TEXT PRIMARY KEY,
                enabled INTEGER NOT NULL DEFAULT 1,
                added_at_ms INTEGER NOT NULL,
                last_scan_started_ms INTEGER,
                last_scan_completed_ms INTEGER,
                last_scan_status TEXT,
                last_scan_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )

        db.execSQL("ALTER TABLE images ADD COLUMN folder_uri TEXT")
        db.execSQL("UPDATE images SET folder_uri = COALESCE(parent_uri, '') WHERE folder_uri IS NULL")
        db.execSQL("ALTER TABLE images ADD COLUMN width INTEGER")
        db.execSQL("ALTER TABLE images ADD COLUMN height INTEGER")
        db.execSQL("ALTER TABLE images ADD COLUMN created_at_ms INTEGER")
        db.execSQL("ALTER TABLE images ADD COLUMN modified_at_ms INTEGER")
        db.execSQL("UPDATE images SET modified_at_ms = last_modified_ms WHERE modified_at_ms IS NULL")
        db.execSQL("UPDATE images SET created_at_ms = modified_at_ms WHERE created_at_ms IS NULL")
        db.execSQL("ALTER TABLE images ADD COLUMN imported_order INTEGER")
        db.execSQL("UPDATE images SET imported_order = image_id WHERE imported_order IS NULL")
        db.execSQL("ALTER TABLE images ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE images ADD COLUMN rating INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE images ADD COLUMN tags_text TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE images ADD COLUMN taxonomy_text TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE images ADD COLUMN metadata_text TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE images ADD COLUMN active INTEGER NOT NULL DEFAULT 1")

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_images_folder_uri ON images(folder_uri)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_images_active ON images(active)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_images_imported_order ON images(imported_order)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_images_filename ON images(filename)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_images_modified_at ON images(modified_at_ms)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_images_size ON images(size_bytes)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_images_resolution ON images(width, height)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_images_parent_uri ON images(parent_uri)")

        createImageSearchIndexArtifacts(db, ifNotExists = true, rebuild = true)

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS scan_runs (
                scan_id INTEGER PRIMARY KEY AUTOINCREMENT,
                folder_uri TEXT NOT NULL,
                started_at_ms INTEGER NOT NULL,
                completed_at_ms INTEGER,
                status TEXT NOT NULL,
                discovered_count INTEGER NOT NULL DEFAULT 0,
                skipped_count INTEGER NOT NULL DEFAULT 0,
                error_message TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_scan_runs_folder ON scan_runs(folder_uri)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_scan_runs_started ON scan_runs(started_at_ms)")

        if (columnMissing(db, "review_items", "review_id")) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS review_items (
                    review_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    image_uri TEXT NOT NULL UNIQUE,
                    status TEXT NOT NULL DEFAULT 'pending',
                    reason TEXT,
                    last_updated_ms INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_review_status ON review_items(status)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS thumbnail_cache (
                image_uri TEXT PRIMARY KEY,
                cache_path TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                last_access_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_thumbnail_access ON thumbnail_cache(last_access_ms)")
    }

    private fun migrateToV5(db: SQLiteDatabase) {
        if (columnMissing(db, "images", "extension")) {
            db.execSQL("ALTER TABLE images ADD COLUMN extension TEXT NOT NULL DEFAULT ''")
        }
        if (columnMissing(db, "images", "mime_type")) {
            db.execSQL("ALTER TABLE images ADD COLUMN mime_type TEXT NOT NULL DEFAULT ''")
        }
        if (columnMissing(db, "images", "resolution_text")) {
            db.execSQL("ALTER TABLE images ADD COLUMN resolution_text TEXT")
        }
        if (columnMissing(db, "images", "aspect_ratio")) {
            db.execSQL("ALTER TABLE images ADD COLUMN aspect_ratio REAL")
        }
        if (columnMissing(db, "images", "orientation")) {
            db.execSQL("ALTER TABLE images ADD COLUMN orientation TEXT")
        }
        if (columnMissing(db, "images", "folder_name")) {
            db.execSQL("ALTER TABLE images ADD COLUMN folder_name TEXT NOT NULL DEFAULT ''")
        }
        if (columnMissing(db, "images", "relative_path")) {
            db.execSQL("ALTER TABLE images ADD COLUMN relative_path TEXT NOT NULL DEFAULT ''")
        }
    }

    private fun migrateToV6(db: SQLiteDatabase) {
        FusionDatabaseSchema.createAll(db, ifNotExists = true)
        FusionDatabaseSchema.migrateFromLegacy(db)
        FusionDatabaseSchema.recordSchemaHistory(db, schemaVersion = 6, notes = "Migrated to Fusion schema v1")
    }

    private fun migrateToV7(db: SQLiteDatabase) {
        LocalAiSchema.createAll(db, ifNotExists = true)
        FusionDatabaseSchema.recordSchemaHistory(db, schemaVersion = 7, notes = "Added Local AI Manager infrastructure schema")
    }

    private fun migrateToV8(db: SQLiteDatabase) {
        LocalAiSchema.createAll(db, ifNotExists = true)
        LocalAiSchema.ensureArtifacts(db)
        FusionDatabaseSchema.recordSchemaHistory(db, schemaVersion = 8, notes = "Added Local AI execution layer schema")
    }

    private fun migrateToV9(db: SQLiteDatabase) {
        db.execSQL("DROP TRIGGER IF EXISTS images_ai")
        db.execSQL("DROP TRIGGER IF EXISTS images_ad")
        db.execSQL("DROP TRIGGER IF EXISTS images_au")

        val ftsSql = db.rawQuery(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'image_fts'",
            emptyArray(),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" }
        if (ftsSql.contains("fts5", ignoreCase = true)) {
            createFts5ImageTriggers(db, ifNotExists = false)
        } else if (ftsSql.contains("fts4", ignoreCase = true)) {
            createFts4ImageTriggers(db, ifNotExists = false)
        }
        FusionDatabaseSchema.recordSchemaHistory(db, schemaVersion = 9, notes = "Repaired image FTS mutation triggers")
    }

    private fun migrateToV10(db: SQLiteDatabase) {
        if (columnMissing(db, "review_items", "image_id")) {
            db.execSQL("ALTER TABLE review_items ADD COLUMN image_id INTEGER")
        }
        if (columnMissing(db, "review_items", "review_type")) {
            db.execSQL("ALTER TABLE review_items ADD COLUMN review_type TEXT NOT NULL DEFAULT 'generic'")
        }
        if (columnMissing(db, "review_items", "payload_json")) {
            db.execSQL("ALTER TABLE review_items ADD COLUMN payload_json TEXT NOT NULL DEFAULT '{}'")
        }
        if (columnMissing(db, "review_items", "correction_json")) {
            db.execSQL("ALTER TABLE review_items ADD COLUMN correction_json TEXT NOT NULL DEFAULT '{}'")
        }
        createAutomationV10Artifacts(db, ifNotExists = true)
        FusionDatabaseSchema.recordSchemaHistory(
            db,
            schemaVersion = 10,
            notes = "Separated immutable Knowledge from mutable Fusion resolution state",
        )
    }

    private fun migrateToV11(db: SQLiteDatabase) {
        createLargeLibraryIndexes(db, ifNotExists = true)
    }

    private fun createLargeLibraryIndexes(db: SQLiteDatabase, ifNotExists: Boolean) {
        val clause = if (ifNotExists) "IF NOT EXISTS " else ""
        db.execSQL("CREATE INDEX ${clause}idx_images_active_imported ON images(active, imported_order DESC)")
        db.execSQL("CREATE INDEX ${clause}idx_images_active_modified ON images(active, modified_at_ms DESC)")
        db.execSQL("CREATE INDEX ${clause}idx_images_active_size ON images(active, size_bytes DESC)")
        db.execSQL("CREATE INDEX ${clause}idx_images_active_filename ON images(active, filename COLLATE NOCASE)")
        db.execSQL("CREATE INDEX ${clause}idx_images_folder_active_imported ON images(folder_uri, active, imported_order DESC)")
    }

    private fun createAutomationV10Artifacts(db: SQLiteDatabase, ifNotExists: Boolean) {
        val clause = if (ifNotExists) "IF NOT EXISTS " else ""
        db.execSQL(
            """
            CREATE TABLE $clause fusion_image_subjects (
                subject_id INTEGER PRIMARY KEY AUTOINCREMENT,
                image_id INTEGER NOT NULL,
                subject_index INTEGER NOT NULL,
                prominence REAL NOT NULL DEFAULT 1.0,
                observations_json TEXT NOT NULL DEFAULT '{}',
                candidates_json TEXT NOT NULL DEFAULT '[]',
                resolved_character_id TEXT,
                resolution_confidence REAL NOT NULL DEFAULT 0.0,
                status TEXT NOT NULL DEFAULT 'unresolved',
                updated_at_ms INTEGER NOT NULL,
                UNIQUE(image_id, subject_index),
                FOREIGN KEY(image_id) REFERENCES images(image_id)
                    ON UPDATE CASCADE ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX " + clause + "idx_fusion_subjects_image ON fusion_image_subjects(image_id)")
        db.execSQL("CREATE INDEX " + clause + "idx_fusion_subjects_character ON fusion_image_subjects(resolved_character_id)")

        db.execSQL(
            """
            CREATE TABLE $clause fusion_image_canonical_tags (
                image_id INTEGER NOT NULL,
                tag_id TEXT NOT NULL,
                tag_name TEXT NOT NULL DEFAULT '',
                tag_scope TEXT NOT NULL,
                confidence REAL NOT NULL DEFAULT 0.0,
                source TEXT NOT NULL DEFAULT 'ai',
                added_at_ms INTEGER NOT NULL,
                PRIMARY KEY(image_id, tag_id, tag_scope),
                FOREIGN KEY(image_id) REFERENCES images(image_id)
                    ON UPDATE CASCADE ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX " + clause + "idx_fusion_canonical_tags_tag ON fusion_image_canonical_tags(tag_id)")

        db.execSQL(
            """
            CREATE TABLE $clause fusion_character_visual_evidence (
                evidence_id INTEGER PRIMARY KEY AUTOINCREMENT,
                character_id TEXT NOT NULL,
                image_id INTEGER,
                evidence_kind TEXT NOT NULL,
                source_uri TEXT NOT NULL DEFAULT '',
                embedding_json TEXT NOT NULL DEFAULT '[]',
                attributes_json TEXT NOT NULL DEFAULT '{}',
                validated INTEGER NOT NULL DEFAULT 0,
                weight REAL NOT NULL DEFAULT 1.0,
                added_at_ms INTEGER NOT NULL,
                UNIQUE(character_id, image_id, evidence_kind),
                FOREIGN KEY(image_id) REFERENCES images(image_id)
                    ON UPDATE CASCADE ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX " + clause + "idx_fusion_character_evidence_character ON fusion_character_visual_evidence(character_id)")

        db.execSQL(
            """
            CREATE TABLE $clause fusion_automation_state (
                image_id INTEGER PRIMARY KEY,
                state TEXT NOT NULL DEFAULT 'pending',
                source_modified_at_ms INTEGER NOT NULL DEFAULT 0,
                pipeline_complete INTEGER NOT NULL DEFAULT 0,
                organization_complete INTEGER NOT NULL DEFAULT 0,
                needs_review INTEGER NOT NULL DEFAULT 0,
                last_error TEXT NOT NULL DEFAULT '',
                updated_at_ms INTEGER NOT NULL,
                FOREIGN KEY(image_id) REFERENCES images(image_id)
                    ON UPDATE CASCADE ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX " + clause + "idx_fusion_automation_state ON fusion_automation_state(state)")

        db.execSQL(
            """
            CREATE TABLE $clause fusion_character_sheet_index_state (
                character_id TEXT PRIMARY KEY,
                source_uri TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'pending',
                attempts INTEGER NOT NULL DEFAULT 0,
                last_error TEXT NOT NULL DEFAULT '',
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX " + clause + "idx_fusion_sheet_index_status ON fusion_character_sheet_index_state(status)")

        db.execSQL(
            """
            CREATE TABLE $clause fusion_original_character_clusters (
                cluster_id TEXT PRIMARY KEY,
                label TEXT NOT NULL DEFAULT '',
                attributes_json TEXT NOT NULL DEFAULT '{}',
                prototype_embedding_json TEXT NOT NULL DEFAULT '[]',
                image_count INTEGER NOT NULL DEFAULT 0,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE $clause fusion_image_oc_clusters (
                image_id INTEGER NOT NULL,
                subject_index INTEGER NOT NULL,
                cluster_id TEXT NOT NULL,
                confidence REAL NOT NULL DEFAULT 0.0,
                added_at_ms INTEGER NOT NULL,
                PRIMARY KEY(image_id, subject_index),
                FOREIGN KEY(image_id) REFERENCES images(image_id)
                    ON UPDATE CASCADE ON DELETE CASCADE,
                FOREIGN KEY(cluster_id) REFERENCES fusion_original_character_clusters(cluster_id)
                    ON UPDATE CASCADE ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }

    private fun columnMissing(db: SQLiteDatabase, table: String, column: String): Boolean {
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                if (name == column) {
                    return false
                }
            }
        }
        return true
    }

    private fun createImageSearchIndexArtifacts(db: SQLiteDatabase, ifNotExists: Boolean, rebuild: Boolean) {
        val ifNotExistsSql = if (ifNotExists) "IF NOT EXISTS " else ""

        val ftsMode = try {
            db.execSQL(
                """
                CREATE VIRTUAL TABLE ${ifNotExistsSql}image_fts USING fts5(
                    filename,
                    uri,
                    tags_text,
                    metadata_text,
                    content='images',
                    content_rowid='image_id'
                )
                """.trimIndent(),
            )
            "fts5"
        } catch (e: SQLiteException) {
            if (!isUnsupportedFts5(e)) {
                throw e
            }
            db.execSQL(
                """
                CREATE VIRTUAL TABLE ${ifNotExistsSql}image_fts USING fts4(
                    filename,
                    uri,
                    tags_text,
                    metadata_text,
                    content='images'
                )
                """.trimIndent(),
            )
            "fts4"
        }

        if (ftsMode == "fts5") {
            createFts5ImageTriggers(db, ifNotExists)
        } else {
            createFts4ImageTriggers(db, ifNotExists)
        }

        if (rebuild) {
            db.execSQL("INSERT INTO image_fts(image_fts) VALUES ('rebuild')")
        }
    }

    private fun createFts5ImageTriggers(db: SQLiteDatabase, ifNotExists: Boolean) {
        val ifNotExistsSql = if (ifNotExists) "IF NOT EXISTS " else ""
        db.execSQL(
            """
            CREATE TRIGGER ${ifNotExistsSql}images_ai AFTER INSERT ON images BEGIN
                INSERT INTO image_fts(rowid, filename, uri, tags_text, metadata_text)
                VALUES (new.image_id, new.filename, new.uri, new.tags_text, new.metadata_text);
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER ${ifNotExistsSql}images_ad AFTER DELETE ON images BEGIN
                INSERT INTO image_fts(image_fts, rowid, filename, uri, tags_text, metadata_text)
                VALUES ('delete', old.image_id, old.filename, old.uri, old.tags_text, old.metadata_text);
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER ${ifNotExistsSql}images_au AFTER UPDATE ON images BEGIN
                INSERT INTO image_fts(image_fts, rowid, filename, uri, tags_text, metadata_text)
                VALUES ('delete', old.image_id, old.filename, old.uri, old.tags_text, old.metadata_text);
                INSERT INTO image_fts(rowid, filename, uri, tags_text, metadata_text)
                VALUES (new.image_id, new.filename, new.uri, new.tags_text, new.metadata_text);
            END
            """.trimIndent(),
        )
    }

    private fun createFts4ImageTriggers(db: SQLiteDatabase, ifNotExists: Boolean) {
        val ifNotExistsSql = if (ifNotExists) "IF NOT EXISTS " else ""
        db.execSQL(
            """
            CREATE TRIGGER ${ifNotExistsSql}images_ai AFTER INSERT ON images BEGIN
                INSERT INTO image_fts(docid, filename, uri, tags_text, metadata_text)
                VALUES (new.image_id, new.filename, new.uri, new.tags_text, new.metadata_text);
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER ${ifNotExistsSql}images_ad AFTER DELETE ON images BEGIN
                DELETE FROM image_fts WHERE docid = old.image_id;
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER ${ifNotExistsSql}images_au AFTER UPDATE ON images BEGIN
                DELETE FROM image_fts WHERE docid = old.image_id;
                INSERT INTO image_fts(docid, filename, uri, tags_text, metadata_text)
                VALUES (new.image_id, new.filename, new.uri, new.tags_text, new.metadata_text);
            END
            """.trimIndent(),
        )
    }

    private fun isUnsupportedFts5(error: SQLiteException): Boolean {
        val message = error.message?.lowercase().orEmpty()
        return message.contains("no such module: fts5")
    }

    companion object {
        private const val DB_TAG = "AilmLocalDatabase"
        private const val DB_NAME = "ailm_android.sqlite"
        private const val DB_VERSION = 11
    }
}
