package com.ailm.android.runtime.ai

import android.database.sqlite.SQLiteDatabase

object LocalAiSchema {
    const val AI_SCHEMA_VERSION = 2

    const val TABLE_MODELS = "ai_models"
    const val TABLE_INSTALL_RUNS = "ai_install_runs"
    const val TABLE_TASK_QUEUE = "ai_task_queue"
    const val TABLE_CACHE = "ai_model_cache"
    const val TABLE_SETTINGS = "ai_settings"
    const val TABLE_PLUGINS = "ai_plugins"
    const val TABLE_CAPABILITIES = "ai_capabilities"
    const val TABLE_HARDWARE_SNAPSHOTS = "ai_hardware_snapshots"
    const val TABLE_EXECUTION_SESSIONS = "ai_execution_sessions"
    const val TABLE_EXECUTION_EVENTS = "ai_execution_events"
    const val TABLE_RUNTIME_HEALTH = "ai_runtime_health"

    fun createAll(db: SQLiteDatabase, ifNotExists: Boolean) {
        createTables(db, ifNotExists)
        createIndexes(db, ifNotExists)
    }

    fun ensureArtifacts(db: SQLiteDatabase) {
        createAll(db, ifNotExists = true)
        ensureColumns(db)
    }

    private fun createTables(db: SQLiteDatabase, ifNotExists: Boolean) {
        val ifNotExistsSql = if (ifNotExists) "IF NOT EXISTS " else ""

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_MODELS (
                model_id TEXT NOT NULL,
                version TEXT NOT NULL,
                display_name TEXT NOT NULL,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                hash_sha256 TEXT NOT NULL DEFAULT '',
                supported_tasks_json TEXT NOT NULL DEFAULT '[]',
                required_runtime TEXT NOT NULL DEFAULT '',
                supported_runtimes_json TEXT NOT NULL DEFAULT '[]',
                dependencies_json TEXT NOT NULL DEFAULT '[]',
                required_hardware_json TEXT NOT NULL DEFAULT '{}',
                compatibility_json TEXT NOT NULL DEFAULT '{}',
                metadata_json TEXT NOT NULL DEFAULT '{}',
                source TEXT NOT NULL DEFAULT 'manual',
                source_uri TEXT NOT NULL DEFAULT '',
                installed INTEGER NOT NULL DEFAULT 0,
                install_state TEXT NOT NULL DEFAULT 'available',
                install_path TEXT NOT NULL DEFAULT '',
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                PRIMARY KEY(model_id, version)
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_INSTALL_RUNS (
                install_id TEXT PRIMARY KEY,
                model_id TEXT NOT NULL,
                version TEXT NOT NULL,
                action TEXT NOT NULL,
                source_uri TEXT NOT NULL DEFAULT '',
                expected_hash TEXT NOT NULL DEFAULT '',
                actual_hash TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL,
                details_json TEXT NOT NULL DEFAULT '{}',
                retry_count INTEGER NOT NULL DEFAULT 0,
                created_at_ms INTEGER NOT NULL,
                started_at_ms INTEGER NOT NULL DEFAULT 0,
                finished_at_ms INTEGER NOT NULL DEFAULT 0,
                error_message TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_TASK_QUEUE (
                task_id TEXT PRIMARY KEY,
                task_type TEXT NOT NULL,
                model_id TEXT NOT NULL DEFAULT '',
                version TEXT NOT NULL DEFAULT '',
                runtime_hint TEXT NOT NULL DEFAULT '',
                priority INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                progress REAL NOT NULL DEFAULT 0,
                retry_count INTEGER NOT NULL DEFAULT 0,
                max_retries INTEGER NOT NULL DEFAULT 3,
                cancellation_requested INTEGER NOT NULL DEFAULT 0,
                pause_requested INTEGER NOT NULL DEFAULT 0,
                dependency_task_ids_json TEXT NOT NULL DEFAULT '[]',
                next_run_at_ms INTEGER NOT NULL DEFAULT 0,
                timeout_ms INTEGER NOT NULL DEFAULT 0,
                session_id TEXT NOT NULL DEFAULT '',
                payload_json TEXT NOT NULL DEFAULT '{}',
                result_json TEXT NOT NULL DEFAULT '{}',
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                started_at_ms INTEGER NOT NULL DEFAULT 0,
                finished_at_ms INTEGER NOT NULL DEFAULT 0,
                error_message TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_CACHE (
                cache_key TEXT PRIMARY KEY,
                model_id TEXT NOT NULL,
                artifact_path TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                pinned INTEGER NOT NULL DEFAULT 0,
                metadata_json TEXT NOT NULL DEFAULT '{}',
                created_at_ms INTEGER NOT NULL,
                last_access_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_SETTINGS (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_PLUGINS (
                plugin_id TEXT PRIMARY KEY,
                version TEXT NOT NULL,
                display_name TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                capabilities_json TEXT NOT NULL DEFAULT '[]',
                metadata_json TEXT NOT NULL DEFAULT '{}',
                registered_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_CAPABILITIES (
                capability_id TEXT PRIMARY KEY,
                provider_id TEXT NOT NULL,
                capability_type TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'available',
                metadata_json TEXT NOT NULL DEFAULT '{}',
                registered_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_HARDWARE_SNAPSHOTS (
                snapshot_id INTEGER PRIMARY KEY AUTOINCREMENT,
                captured_at_ms INTEGER NOT NULL,
                profile_json TEXT NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_EXECUTION_SESSIONS (
                session_id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL,
                task_type TEXT NOT NULL,
                model_id TEXT NOT NULL DEFAULT '',
                version TEXT NOT NULL DEFAULT '',
                runtime_id TEXT NOT NULL DEFAULT '',
                backend_id TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL,
                progress REAL NOT NULL DEFAULT 0,
                retry_count INTEGER NOT NULL DEFAULT 0,
                reservation_bytes INTEGER NOT NULL DEFAULT 0,
                context_json TEXT NOT NULL DEFAULT '{}',
                result_json TEXT NOT NULL DEFAULT '{}',
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                started_at_ms INTEGER NOT NULL DEFAULT 0,
                finished_at_ms INTEGER NOT NULL DEFAULT 0,
                error_message TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_EXECUTION_EVENTS (
                event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                task_id TEXT NOT NULL,
                event_type TEXT NOT NULL,
                message TEXT NOT NULL DEFAULT '',
                progress REAL NOT NULL DEFAULT 0,
                payload_json TEXT NOT NULL DEFAULT '{}',
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE ${ifNotExistsSql}$TABLE_RUNTIME_HEALTH (
                snapshot_id INTEGER PRIMARY KEY AUTOINCREMENT,
                runtime_id TEXT NOT NULL,
                backend_id TEXT NOT NULL,
                status TEXT NOT NULL,
                healthy INTEGER NOT NULL DEFAULT 0,
                latency_ms INTEGER NOT NULL DEFAULT 0,
                metadata_json TEXT NOT NULL DEFAULT '{}',
                captured_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createIndexes(db: SQLiteDatabase, ifNotExists: Boolean) {
        val ifNotExistsSql = if (ifNotExists) "IF NOT EXISTS " else ""
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_ai_models_installed ON $TABLE_MODELS(installed, model_id)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_ai_models_updated ON $TABLE_MODELS(updated_at_ms DESC)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_ai_models_runtime ON $TABLE_MODELS(required_runtime, installed)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_ai_install_runs_model ON $TABLE_INSTALL_RUNS(model_id, created_at_ms DESC)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_ai_install_runs_status ON $TABLE_INSTALL_RUNS(status, created_at_ms DESC)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_ai_task_queue_status ON $TABLE_TASK_QUEUE(status, priority DESC, next_run_at_ms ASC, created_at_ms ASC)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_ai_task_queue_model ON $TABLE_TASK_QUEUE(model_id, created_at_ms DESC)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_ai_task_queue_session ON $TABLE_TASK_QUEUE(session_id)")
        // Matches LocalAiRepository.listTasks ordering, avoiding a full
        // historical queue scan/sort on every UI refresh for 1M+ AI tasks.
        db.execSQL("""
            CREATE INDEX ${ifNotExistsSql}idx_ai_task_queue_display_order
            ON $TABLE_TASK_QUEUE(
                CASE status
                    WHEN 'running' THEN 0
                    WHEN 'pending' THEN 1
                    WHEN 'paused' THEN 2
                    WHEN 'failed' THEN 3
                    WHEN 'cancelled' THEN 4
                    WHEN 'succeeded' THEN 5
                    ELSE 6
                END,
                priority DESC, next_run_at_ms ASC, created_at_ms ASC
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_ai_cache_model ON $TABLE_CACHE(model_id, last_access_ms DESC)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_ai_cache_access ON $TABLE_CACHE(last_access_ms ASC)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_ai_plugins_enabled ON $TABLE_PLUGINS(enabled, updated_at_ms DESC)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_ai_capabilities_provider ON $TABLE_CAPABILITIES(provider_id, updated_at_ms DESC)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_ai_hardware_snapshots_time ON $TABLE_HARDWARE_SNAPSHOTS(captured_at_ms DESC)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_ai_execution_sessions_task ON $TABLE_EXECUTION_SESSIONS(task_id, created_at_ms DESC)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_ai_execution_sessions_status ON $TABLE_EXECUTION_SESSIONS(status, updated_at_ms DESC)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_ai_execution_sessions_created ON $TABLE_EXECUTION_SESSIONS(created_at_ms DESC)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_ai_execution_events_session ON $TABLE_EXECUTION_EVENTS(session_id, created_at_ms ASC)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_ai_execution_events_task ON $TABLE_EXECUTION_EVENTS(task_id, created_at_ms ASC)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_ai_runtime_health_runtime ON $TABLE_RUNTIME_HEALTH(runtime_id, captured_at_ms DESC)")
        db.execSQL("CREATE INDEX ${ifNotExistsSql}idx_ai_runtime_health_status ON $TABLE_RUNTIME_HEALTH(status, captured_at_ms DESC)")
    }

    private fun ensureColumns(db: SQLiteDatabase) {
        ensureModelColumns(db)
        ensureTaskColumns(db)
    }

    private fun ensureModelColumns(db: SQLiteDatabase) {
        addColumnIfMissing(db, TABLE_MODELS, "supported_runtimes_json", "TEXT NOT NULL DEFAULT '[]'")
        addColumnIfMissing(db, TABLE_MODELS, "dependencies_json", "TEXT NOT NULL DEFAULT '[]'")
        addColumnIfMissing(db, TABLE_MODELS, "compatibility_json", "TEXT NOT NULL DEFAULT '{}'")
    }

    private fun ensureTaskColumns(db: SQLiteDatabase) {
        addColumnIfMissing(db, TABLE_TASK_QUEUE, "pause_requested", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(db, TABLE_TASK_QUEUE, "dependency_task_ids_json", "TEXT NOT NULL DEFAULT '[]'")
        addColumnIfMissing(db, TABLE_TASK_QUEUE, "next_run_at_ms", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(db, TABLE_TASK_QUEUE, "timeout_ms", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(db, TABLE_TASK_QUEUE, "session_id", "TEXT NOT NULL DEFAULT ''")
    }

    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String, definition: String) {
        if (!columnExists(db, table, column)) {
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }

    private fun columnExists(db: SQLiteDatabase, table: String, column: String): Boolean {
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                if (name == column) {
                    return true
                }
            }
        }
        return false
    }
}
