package com.ailm.android.runtime.ai

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.ailm.android.runtime.LocalDatabase

class LocalAiRepository(
    private val database: LocalDatabase,
) {

    fun upsertModel(model: AiModelDescriptor) {
        val values = ContentValues().apply {
            put("model_id", model.modelId)
            put("version", model.version)
            put("display_name", model.displayName)
            put("size_bytes", model.sizeBytes)
            put("hash_sha256", model.hashSha256)
            put("supported_tasks_json", LocalAiJson.encodeList(model.supportedTasks))
            put("required_runtime", model.requiredRuntime)
            put("supported_runtimes_json", LocalAiJson.encodeList(model.supportedRuntimes))
            put("dependencies_json", LocalAiJson.encodeList(model.dependencies))
            put("required_hardware_json", LocalAiJson.encodeMap(model.requiredHardware))
            put("compatibility_json", LocalAiJson.encodeMap(model.compatibility))
            put("metadata_json", LocalAiJson.encodeMap(model.metadata))
            put("source", model.source)
            put("source_uri", model.sourceUri)
            put("installed", if (model.installed) 1 else 0)
            put("install_state", model.installState)
            put("install_path", model.installPath)
            put("created_at_ms", model.createdAtMs)
            put("updated_at_ms", model.updatedAtMs)
        }
        database.writableDatabase.insertWithOnConflict(
            LocalAiSchema.TABLE_MODELS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun getModel(modelId: String, version: String = ""): AiModelDescriptor? {
        val sql = if (version.isBlank()) {
            """
            SELECT *
            FROM ${LocalAiSchema.TABLE_MODELS}
            WHERE model_id = ?
            ORDER BY installed DESC, updated_at_ms DESC
            LIMIT 1
            """.trimIndent()
        } else {
            "SELECT * FROM ${LocalAiSchema.TABLE_MODELS} WHERE model_id = ? AND version = ? LIMIT 1"
        }
        val args = if (version.isBlank()) arrayOf(modelId) else arrayOf(modelId, version)
        database.readableDatabase.rawQuery(sql, args).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            return cursorToModel(cursor)
        }
    }

    fun listModels(installedOnly: Boolean? = null): List<AiModelDescriptor> {
        val where = when (installedOnly) {
            true -> "WHERE installed = 1"
            false -> "WHERE installed = 0"
            null -> ""
        }
        val sql = """
            SELECT *
            FROM ${LocalAiSchema.TABLE_MODELS}
            $where
            ORDER BY model_id ASC, updated_at_ms DESC
        """.trimIndent()
        val rows = mutableListOf<AiModelDescriptor>()
        database.readableDatabase.rawQuery(sql, emptyArray()).use { cursor ->
            while (cursor.moveToNext()) {
                rows += cursorToModel(cursor)
            }
        }
        return rows
    }

    fun setModelInstallState(
        modelId: String,
        version: String,
        installed: Boolean,
        installState: String,
        installPath: String,
    ): Boolean {
        val values = ContentValues().apply {
            put("installed", if (installed) 1 else 0)
            put("install_state", installState)
            put("install_path", installPath)
            put("updated_at_ms", System.currentTimeMillis())
        }
        val count = database.writableDatabase.update(
            LocalAiSchema.TABLE_MODELS,
            values,
            "model_id = ? AND version = ?",
            arrayOf(modelId, version),
        )
        return count > 0
    }

    fun removeModelVersion(modelId: String, version: String): Boolean {
        val count = database.writableDatabase.delete(
            LocalAiSchema.TABLE_MODELS,
            "model_id = ? AND version = ?",
            arrayOf(modelId, version),
        )
        return count > 0
    }

    fun upsertInstallRun(run: AiInstallRunRecord) {
        val values = ContentValues().apply {
            put("install_id", run.installId)
            put("model_id", run.modelId)
            put("version", run.version)
            put("action", run.action)
            put("source_uri", run.sourceUri)
            put("expected_hash", run.expectedHash)
            put("actual_hash", run.actualHash)
            put("status", run.status)
            put("details_json", LocalAiJson.encodeMap(run.details))
            put("retry_count", run.retryCount)
            put("created_at_ms", run.createdAtMs)
            put("started_at_ms", run.startedAtMs)
            put("finished_at_ms", run.finishedAtMs)
            put("error_message", run.errorMessage)
        }
        database.writableDatabase.insertWithOnConflict(
            LocalAiSchema.TABLE_INSTALL_RUNS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun updateInstallRun(
        installId: String,
        status: String,
        actualHash: String,
        details: Map<String, Any>,
        errorMessage: String,
        retryCount: Int,
        startedAtMs: Long,
        finishedAtMs: Long,
    ): Boolean {
        val values = ContentValues().apply {
            put("status", status)
            put("actual_hash", actualHash)
            put("details_json", LocalAiJson.encodeMap(details))
            put("error_message", errorMessage)
            put("retry_count", retryCount)
            put("started_at_ms", startedAtMs)
            put("finished_at_ms", finishedAtMs)
        }
        val count = database.writableDatabase.update(
            LocalAiSchema.TABLE_INSTALL_RUNS,
            values,
            "install_id = ?",
            arrayOf(installId),
        )
        return count > 0
    }

    fun getInstallRun(installId: String): AiInstallRunRecord? {
        val sql = "SELECT * FROM ${LocalAiSchema.TABLE_INSTALL_RUNS} WHERE install_id = ? LIMIT 1"
        database.readableDatabase.rawQuery(sql, arrayOf(installId)).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            return cursorToInstallRun(cursor)
        }
    }

    fun listInstallRuns(limit: Int = 100): List<AiInstallRunRecord> {
        val rows = mutableListOf<AiInstallRunRecord>()
        val sql = """
            SELECT *
            FROM ${LocalAiSchema.TABLE_INSTALL_RUNS}
            ORDER BY created_at_ms DESC
            LIMIT ?
        """.trimIndent()
        database.readableDatabase.rawQuery(sql, arrayOf(limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                rows += cursorToInstallRun(cursor)
            }
        }
        return rows
    }

    fun upsertTask(task: AiTaskRecord) {
        val values = ContentValues().apply {
            put("task_id", task.taskId)
            put("task_type", task.taskType)
            put("model_id", task.modelId)
            put("version", task.version)
            put("runtime_hint", task.runtimeHint)
            put("priority", task.priority)
            put("status", task.status)
            put("progress", task.progress)
            put("retry_count", task.retryCount)
            put("max_retries", task.maxRetries)
            put("cancellation_requested", if (task.cancellationRequested) 1 else 0)
            put("pause_requested", if (task.pauseRequested) 1 else 0)
            put("dependency_task_ids_json", LocalAiJson.encodeList(task.dependencyTaskIds))
            put("next_run_at_ms", task.nextRunAtMs)
            put("timeout_ms", task.timeoutMs)
            put("session_id", task.sessionId)
            put("payload_json", LocalAiJson.encodeMap(task.payload))
            put("result_json", LocalAiJson.encodeMap(task.result))
            put("created_at_ms", task.createdAtMs)
            put("updated_at_ms", task.updatedAtMs)
            put("started_at_ms", task.startedAtMs)
            put("finished_at_ms", task.finishedAtMs)
            put("error_message", task.errorMessage)
        }
        database.writableDatabase.insertWithOnConflict(
            LocalAiSchema.TABLE_TASK_QUEUE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /**
     * Avoid sorting/de-serializing historical tasks on every model-selection
     * pass. The status index makes this count O(log N) for large queues.
     */
    fun countRunningTasks(): Int = database.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM ${LocalAiSchema.TABLE_TASK_QUEUE} WHERE status = 'running'",
        emptyArray(),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    fun hasActiveTasks(): Boolean = database.readableDatabase.rawQuery(
        "SELECT 1 FROM ${LocalAiSchema.TABLE_TASK_QUEUE} WHERE status IN ('pending', 'running') LIMIT 1",
        emptyArray(),
    ).use { cursor -> cursor.moveToFirst() }

    fun listTasks(limit: Int = 200): List<AiTaskRecord> {
        val rows = mutableListOf<AiTaskRecord>()
        val sql = """
            SELECT *
            FROM ${LocalAiSchema.TABLE_TASK_QUEUE}
            ORDER BY
                CASE status
                    WHEN 'running' THEN 0
                    WHEN 'pending' THEN 1
                    WHEN 'paused' THEN 2
                    WHEN 'failed' THEN 3
                    WHEN 'cancelled' THEN 4
                    WHEN 'succeeded' THEN 5
                    ELSE 6
                END,
                priority DESC,
                next_run_at_ms ASC,
                created_at_ms ASC
            LIMIT ?
        """.trimIndent()
        database.readableDatabase.rawQuery(sql, arrayOf(limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                rows += cursorToTask(cursor)
            }
        }
        return rows
    }

    fun listRunnablePendingTasks(nowMs: Long, limit: Int = 200): List<AiTaskRecord> {
        val rows = mutableListOf<AiTaskRecord>()
        val sql = """
            SELECT *
            FROM ${LocalAiSchema.TABLE_TASK_QUEUE}
            WHERE status = 'pending' AND next_run_at_ms <= ?
            ORDER BY priority DESC, next_run_at_ms ASC, created_at_ms ASC
            LIMIT ?
        """.trimIndent()
        database.readableDatabase.rawQuery(sql, arrayOf(nowMs.toString(), limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                rows += cursorToTask(cursor)
            }
        }
        return rows
    }

    fun getTask(taskId: String): AiTaskRecord? {
        val sql = "SELECT * FROM ${LocalAiSchema.TABLE_TASK_QUEUE} WHERE task_id = ? LIMIT 1"
        database.readableDatabase.rawQuery(sql, arrayOf(taskId)).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            return cursorToTask(cursor)
        }
    }

    fun getNextPendingTask(nowMs: Long = System.currentTimeMillis()): AiTaskRecord? {
        return listRunnablePendingTasks(nowMs, limit = 1).firstOrNull()
    }

    fun listTaskStatuses(taskIds: List<String>): Map<String, String> {
        if (taskIds.isEmpty()) {
            return emptyMap()
        }
        val placeholders = taskIds.joinToString(",") { "?" }
        val sql = "SELECT task_id, status FROM ${LocalAiSchema.TABLE_TASK_QUEUE} WHERE task_id IN ($placeholders)"
        val rows = linkedMapOf<String, String>()
        database.readableDatabase.rawQuery(sql, taskIds.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                rows[cursor.getString(0)] = cursor.getString(1)
            }
        }
        return rows
    }

    fun recoverInterruptedTasks(): Int {
        val now = System.currentTimeMillis()
        var updated = 0

        val runningValues = ContentValues().apply {
            put("status", "pending")
            put("next_run_at_ms", now)
            put("updated_at_ms", now)
            put("error_message", "Recovered after previous runtime shutdown")
            put("session_id", "")
        }
        updated += database.writableDatabase.update(
            LocalAiSchema.TABLE_TASK_QUEUE,
            runningValues,
            "status = 'running'",
            emptyArray(),
        )

        val pausingValues = ContentValues().apply {
            put("status", "paused")
            put("updated_at_ms", now)
        }
        updated += database.writableDatabase.update(
            LocalAiSchema.TABLE_TASK_QUEUE,
            pausingValues,
            "status = 'pending' AND pause_requested = 1",
            emptyArray(),
        )

        return updated
    }

    fun setTaskSession(taskId: String, sessionId: String): Boolean {
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("updated_at_ms", System.currentTimeMillis())
        }
        val count = database.writableDatabase.update(
            LocalAiSchema.TABLE_TASK_QUEUE,
            values,
            "task_id = ?",
            arrayOf(taskId),
        )
        return count > 0
    }

    fun markTaskRunning(taskId: String): Boolean {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("status", "running")
            put("started_at_ms", now)
            put("updated_at_ms", now)
            put("error_message", "")
            put("pause_requested", 0)
        }
        val count = database.writableDatabase.update(
            LocalAiSchema.TABLE_TASK_QUEUE,
            values,
            "task_id = ? AND status = 'pending'",
            arrayOf(taskId),
        )
        return count > 0
    }

    fun updateTaskProgress(taskId: String, progress: Double, message: String): Boolean {
        val existing = getTask(taskId) ?: return false
        val mergedResult = existing.result.toMutableMap().apply {
            put("message", message)
        }
        val values = ContentValues().apply {
            put("progress", progress.coerceIn(0.0, 1.0))
            put("result_json", LocalAiJson.encodeMap(mergedResult))
            put("updated_at_ms", System.currentTimeMillis())
        }
        val count = database.writableDatabase.update(
            LocalAiSchema.TABLE_TASK_QUEUE,
            values,
            "task_id = ?",
            arrayOf(taskId),
        )
        return count > 0
    }

    fun markTaskPaused(taskId: String, message: String): Boolean {
        val values = ContentValues().apply {
            put("status", "paused")
            put("pause_requested", 0)
            put("updated_at_ms", System.currentTimeMillis())
            put("error_message", message)
        }
        val count = database.writableDatabase.update(
            LocalAiSchema.TABLE_TASK_QUEUE,
            values,
            "task_id = ?",
            arrayOf(taskId),
        )
        return count > 0
    }

    fun markTaskCompleted(
        taskId: String,
        status: String,
        result: Map<String, Any>,
        errorMessage: String = "",
    ): Boolean {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("status", status)
            put("progress", if (status == "succeeded") 1.0 else 0.0)
            put("result_json", LocalAiJson.encodeMap(result))
            put("updated_at_ms", now)
            put("finished_at_ms", now)
            put("error_message", errorMessage)
            put("pause_requested", 0)
            put("cancellation_requested", if (status == "cancelled") 1 else 0)
            put("session_id", "")
        }
        val count = database.writableDatabase.update(
            LocalAiSchema.TABLE_TASK_QUEUE,
            values,
            "task_id = ?",
            arrayOf(taskId),
        )
        return count > 0
    }

    fun requestTaskCancellation(taskId: String): Boolean {
        val values = ContentValues().apply {
            put("cancellation_requested", 1)
            put("updated_at_ms", System.currentTimeMillis())
        }
        val count = database.writableDatabase.update(
            LocalAiSchema.TABLE_TASK_QUEUE,
            values,
            "task_id = ?",
            arrayOf(taskId),
        )
        return count > 0
    }

    fun requestTaskPause(taskId: String): Boolean {
        val values = ContentValues().apply {
            put("pause_requested", 1)
            put("updated_at_ms", System.currentTimeMillis())
        }
        val count = database.writableDatabase.update(
            LocalAiSchema.TABLE_TASK_QUEUE,
            values,
            "task_id = ?",
            arrayOf(taskId),
        )
        return count > 0
    }

    fun cancelPendingTask(taskId: String): Boolean {
        val values = ContentValues().apply {
            put("status", "cancelled")
            put("cancellation_requested", 1)
            put("updated_at_ms", System.currentTimeMillis())
            put("finished_at_ms", System.currentTimeMillis())
            put("error_message", "cancelled")
            put("session_id", "")
        }
        val count = database.writableDatabase.update(
            LocalAiSchema.TABLE_TASK_QUEUE,
            values,
            "task_id = ? AND status = 'pending'",
            arrayOf(taskId),
        )
        return count > 0
    }

    fun pausePendingTask(taskId: String): Boolean {
        val values = ContentValues().apply {
            put("status", "paused")
            put("pause_requested", 1)
            put("updated_at_ms", System.currentTimeMillis())
        }
        val count = database.writableDatabase.update(
            LocalAiSchema.TABLE_TASK_QUEUE,
            values,
            "task_id = ? AND status = 'pending'",
            arrayOf(taskId),
        )
        return count > 0
    }

    fun resumePausedTask(taskId: String): Boolean {
        val values = ContentValues().apply {
            put("status", "pending")
            put("pause_requested", 0)
            put("cancellation_requested", 0)
            put("next_run_at_ms", 0)
            put("updated_at_ms", System.currentTimeMillis())
            put("error_message", "")
        }
        val count = database.writableDatabase.update(
            LocalAiSchema.TABLE_TASK_QUEUE,
            values,
            "task_id = ? AND status = 'paused'",
            arrayOf(taskId),
        )
        return count > 0
    }

    fun retryTask(taskId: String): Boolean {
        return scheduleTaskRetry(taskId, nextRunAtMs = 0L, errorMessage = "manual_retry")
    }

    fun scheduleTaskRetry(taskId: String, nextRunAtMs: Long, errorMessage: String): Boolean {
        val task = getTask(taskId) ?: return false
        if (task.status == "running") {
            return false
        }
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("status", "pending")
            put("progress", 0.0)
            put("retry_count", task.retryCount + 1)
            put("cancellation_requested", 0)
            put("pause_requested", 0)
            put("next_run_at_ms", nextRunAtMs.coerceAtLeast(0L))
            put("updated_at_ms", now)
            put("started_at_ms", 0)
            put("finished_at_ms", 0)
            put("error_message", errorMessage)
            put("session_id", "")
        }
        val count = database.writableDatabase.update(
            LocalAiSchema.TABLE_TASK_QUEUE,
            values,
            "task_id = ?",
            arrayOf(taskId),
        )
        return count > 0
    }

    fun upsertCacheEntry(entry: AiCacheEntry) {
        val values = ContentValues().apply {
            put("cache_key", entry.cacheKey)
            put("model_id", entry.modelId)
            put("artifact_path", entry.artifactPath)
            put("size_bytes", entry.sizeBytes)
            put("pinned", if (entry.pinned) 1 else 0)
            put("metadata_json", LocalAiJson.encodeMap(entry.metadata))
            put("created_at_ms", entry.createdAtMs)
            put("last_access_ms", entry.lastAccessMs)
        }
        database.writableDatabase.insertWithOnConflict(
            LocalAiSchema.TABLE_CACHE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun getCacheEntry(cacheKey: String): AiCacheEntry? {
        val sql = "SELECT * FROM ${LocalAiSchema.TABLE_CACHE} WHERE cache_key = ? LIMIT 1"
        database.readableDatabase.rawQuery(sql, arrayOf(cacheKey)).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            return cursorToCacheEntry(cursor)
        }
    }

    fun listCacheEntries(limit: Int = 200): List<AiCacheEntry> {
        val rows = mutableListOf<AiCacheEntry>()
        val sql = """
            SELECT *
            FROM ${LocalAiSchema.TABLE_CACHE}
            ORDER BY last_access_ms DESC
            LIMIT ?
        """.trimIndent()
        database.readableDatabase.rawQuery(sql, arrayOf(limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                rows += cursorToCacheEntry(cursor)
            }
        }
        return rows
    }

    fun listEvictableCacheEntries(): List<AiCacheEntry> {
        val rows = mutableListOf<AiCacheEntry>()
        val sql = """
            SELECT *
            FROM ${LocalAiSchema.TABLE_CACHE}
            WHERE pinned = 0
            ORDER BY last_access_ms ASC
        """.trimIndent()
        database.readableDatabase.rawQuery(sql, emptyArray()).use { cursor ->
            while (cursor.moveToNext()) {
                rows += cursorToCacheEntry(cursor)
            }
        }
        return rows
    }

    fun totalCacheBytes(): Long {
        val sql = "SELECT COALESCE(SUM(size_bytes), 0) FROM ${LocalAiSchema.TABLE_CACHE}"
        database.readableDatabase.rawQuery(sql, emptyArray()).use { cursor ->
            if (!cursor.moveToFirst()) {
                return 0L
            }
            return cursor.getLong(0)
        }
    }

    fun touchCacheEntry(cacheKey: String): Boolean {
        val values = ContentValues().apply {
            put("last_access_ms", System.currentTimeMillis())
        }
        val count = database.writableDatabase.update(
            LocalAiSchema.TABLE_CACHE,
            values,
            "cache_key = ?",
            arrayOf(cacheKey),
        )
        return count > 0
    }

    fun removeCacheEntry(cacheKey: String): Boolean {
        val count = database.writableDatabase.delete(
            LocalAiSchema.TABLE_CACHE,
            "cache_key = ?",
            arrayOf(cacheKey),
        )
        return count > 0
    }

    fun setSetting(key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
            put("updated_at_ms", System.currentTimeMillis())
        }
        database.writableDatabase.insertWithOnConflict(
            LocalAiSchema.TABLE_SETTINGS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun getSetting(key: String, defaultValue: String = ""): String {
        val sql = "SELECT value FROM ${LocalAiSchema.TABLE_SETTINGS} WHERE key = ? LIMIT 1"
        database.readableDatabase.rawQuery(sql, arrayOf(key)).use { cursor ->
            if (!cursor.moveToFirst()) {
                return defaultValue
            }
            return cursor.getString(0)
        }
    }

    fun listSettings(): Map<String, String> {
        val rows = linkedMapOf<String, String>()
        val sql = "SELECT key, value FROM ${LocalAiSchema.TABLE_SETTINGS} ORDER BY key ASC"
        database.readableDatabase.rawQuery(sql, emptyArray()).use { cursor ->
            while (cursor.moveToNext()) {
                rows[cursor.getString(0)] = cursor.getString(1)
            }
        }
        return rows
    }

    fun upsertPlugin(plugin: AiPluginDescriptor) {
        val values = ContentValues().apply {
            put("plugin_id", plugin.pluginId)
            put("version", plugin.version)
            put("display_name", plugin.displayName)
            put("enabled", if (plugin.enabled) 1 else 0)
            put("capabilities_json", LocalAiJson.encodeList(plugin.capabilities))
            put("metadata_json", LocalAiJson.encodeMap(plugin.metadata))
            put("registered_at_ms", plugin.registeredAtMs)
            put("updated_at_ms", plugin.updatedAtMs)
        }
        database.writableDatabase.insertWithOnConflict(
            LocalAiSchema.TABLE_PLUGINS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun listPlugins(enabledOnly: Boolean? = null): List<AiPluginDescriptor> {
        val where = when (enabledOnly) {
            true -> "WHERE enabled = 1"
            false -> "WHERE enabled = 0"
            null -> ""
        }
        val sql = """
            SELECT *
            FROM ${LocalAiSchema.TABLE_PLUGINS}
            $where
            ORDER BY updated_at_ms DESC
        """.trimIndent()
        val rows = mutableListOf<AiPluginDescriptor>()
        database.readableDatabase.rawQuery(sql, emptyArray()).use { cursor ->
            while (cursor.moveToNext()) {
                rows += cursorToPlugin(cursor)
            }
        }
        return rows
    }

    fun upsertCapability(capability: AiCapabilityDescriptor) {
        val values = ContentValues().apply {
            put("capability_id", capability.capabilityId)
            put("provider_id", capability.providerId)
            put("capability_type", capability.capabilityType)
            put("status", capability.status)
            put("metadata_json", LocalAiJson.encodeMap(capability.metadata))
            put("registered_at_ms", capability.registeredAtMs)
            put("updated_at_ms", capability.updatedAtMs)
        }
        database.writableDatabase.insertWithOnConflict(
            LocalAiSchema.TABLE_CAPABILITIES,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun listCapabilities(providerId: String = ""): List<AiCapabilityDescriptor> {
        val (where, args) = if (providerId.isBlank()) {
            "" to emptyArray()
        } else {
            "WHERE provider_id = ?" to arrayOf(providerId)
        }
        val sql = """
            SELECT *
            FROM ${LocalAiSchema.TABLE_CAPABILITIES}
            $where
            ORDER BY updated_at_ms DESC
        """.trimIndent()
        val rows = mutableListOf<AiCapabilityDescriptor>()
        database.readableDatabase.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                rows += cursorToCapability(cursor)
            }
        }
        return rows
    }

    fun saveHardwareProfile(profile: AiHardwareProfile) {
        val values = ContentValues().apply {
            put("captured_at_ms", profile.capturedAtMs)
            put("profile_json", LocalAiJson.encodeMap(profile.toMap()))
        }
        database.writableDatabase.insert(LocalAiSchema.TABLE_HARDWARE_SNAPSHOTS, null, values)
    }

    fun latestHardwareProfile(): AiHardwareProfile? {
        val sql = """
            SELECT profile_json
            FROM ${LocalAiSchema.TABLE_HARDWARE_SNAPSHOTS}
            ORDER BY captured_at_ms DESC
            LIMIT 1
        """.trimIndent()
        database.readableDatabase.rawQuery(sql, emptyArray()).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            return mapToHardwareProfile(LocalAiJson.decodeMap(cursor.getString(0) ?: "{}"))
        }
    }

    fun upsertExecutionSession(session: AiExecutionSessionRecord) {
        val values = ContentValues().apply {
            put("session_id", session.sessionId)
            put("task_id", session.taskId)
            put("task_type", session.taskType)
            put("model_id", session.modelId)
            put("version", session.version)
            put("runtime_id", session.runtimeId)
            put("backend_id", session.backendId)
            put("status", session.status)
            put("progress", session.progress)
            put("retry_count", session.retryCount)
            put("reservation_bytes", session.reservationBytes)
            put("context_json", LocalAiJson.encodeMap(session.context))
            put("result_json", LocalAiJson.encodeMap(session.result))
            put("created_at_ms", session.createdAtMs)
            put("updated_at_ms", session.updatedAtMs)
            put("started_at_ms", session.startedAtMs)
            put("finished_at_ms", session.finishedAtMs)
            put("error_message", session.errorMessage)
        }
        database.writableDatabase.insertWithOnConflict(
            LocalAiSchema.TABLE_EXECUTION_SESSIONS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun getExecutionSession(sessionId: String): AiExecutionSessionRecord? {
        val sql = "SELECT * FROM ${LocalAiSchema.TABLE_EXECUTION_SESSIONS} WHERE session_id = ? LIMIT 1"
        database.readableDatabase.rawQuery(sql, arrayOf(sessionId)).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            return cursorToExecutionSession(cursor)
        }
    }

    fun listExecutionSessions(limit: Int = 200): List<AiExecutionSessionRecord> {
        val rows = mutableListOf<AiExecutionSessionRecord>()
        val sql = """
            SELECT *
            FROM ${LocalAiSchema.TABLE_EXECUTION_SESSIONS}
            ORDER BY created_at_ms DESC
            LIMIT ?
        """.trimIndent()
        database.readableDatabase.rawQuery(sql, arrayOf(limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                rows += cursorToExecutionSession(cursor)
            }
        }
        return rows
    }

    fun appendExecutionEvent(event: AiExecutionEventRecord): Long {
        val values = ContentValues().apply {
            put("session_id", event.sessionId)
            put("task_id", event.taskId)
            put("event_type", event.eventType)
            put("message", event.message)
            put("progress", event.progress)
            put("payload_json", LocalAiJson.encodeMap(event.payload))
            put("created_at_ms", event.createdAtMs)
        }
        return database.writableDatabase.insert(LocalAiSchema.TABLE_EXECUTION_EVENTS, null, values)
    }

    fun listExecutionEvents(sessionId: String, limit: Int = 500): List<AiExecutionEventRecord> {
        val rows = mutableListOf<AiExecutionEventRecord>()
        val sql = """
            SELECT *
            FROM ${LocalAiSchema.TABLE_EXECUTION_EVENTS}
            WHERE session_id = ?
            ORDER BY created_at_ms ASC
            LIMIT ?
        """.trimIndent()
        database.readableDatabase.rawQuery(sql, arrayOf(sessionId, limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                rows += cursorToExecutionEvent(cursor)
            }
        }
        return rows
    }

    fun saveRuntimeHealthSnapshot(snapshot: AiRuntimeHealthSnapshot) {
        val values = ContentValues().apply {
            put("runtime_id", snapshot.runtimeId)
            put("backend_id", snapshot.backendId)
            put("status", snapshot.status)
            put("healthy", if (snapshot.healthy) 1 else 0)
            put("latency_ms", snapshot.latencyMs)
            put("metadata_json", LocalAiJson.encodeMap(snapshot.metadata))
            put("captured_at_ms", snapshot.capturedAtMs)
        }
        database.writableDatabase.insert(LocalAiSchema.TABLE_RUNTIME_HEALTH, null, values)
    }

    fun listRuntimeHealthSnapshots(limit: Int = 200): List<AiRuntimeHealthSnapshot> {
        val rows = mutableListOf<AiRuntimeHealthSnapshot>()
        val sql = """
            SELECT *
            FROM ${LocalAiSchema.TABLE_RUNTIME_HEALTH}
            ORDER BY captured_at_ms DESC
            LIMIT ?
        """.trimIndent()
        database.readableDatabase.rawQuery(sql, arrayOf(limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                rows += cursorToRuntimeHealth(cursor)
            }
        }
        return rows
    }

    private fun cursorToModel(cursor: Cursor): AiModelDescriptor {
        return AiModelDescriptor(
            modelId = cursor.getString(cursor.getColumnIndexOrThrow("model_id")),
            version = cursor.getString(cursor.getColumnIndexOrThrow("version")),
            displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name")),
            sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow("size_bytes")),
            hashSha256 = cursor.getString(cursor.getColumnIndexOrThrow("hash_sha256")),
            supportedTasks = LocalAiJson.decodeList(cursor.getString(cursor.getColumnIndexOrThrow("supported_tasks_json")))
                .map { it.toString() },
            requiredRuntime = cursor.getString(cursor.getColumnIndexOrThrow("required_runtime")),
            supportedRuntimes = LocalAiJson.decodeList(cursor.getStringOrDefault("supported_runtimes_json", "[]"))
                .map { it.toString() },
            dependencies = LocalAiJson.decodeList(cursor.getStringOrDefault("dependencies_json", "[]"))
                .map { it.toString() },
            requiredHardware = LocalAiJson.decodeMap(cursor.getString(cursor.getColumnIndexOrThrow("required_hardware_json"))),
            compatibility = LocalAiJson.decodeMap(cursor.getStringOrDefault("compatibility_json", "{}")),
            metadata = LocalAiJson.decodeMap(cursor.getString(cursor.getColumnIndexOrThrow("metadata_json"))),
            source = cursor.getString(cursor.getColumnIndexOrThrow("source")),
            sourceUri = cursor.getString(cursor.getColumnIndexOrThrow("source_uri")),
            installed = cursor.getInt(cursor.getColumnIndexOrThrow("installed")) == 1,
            installState = cursor.getString(cursor.getColumnIndexOrThrow("install_state")),
            installPath = cursor.getString(cursor.getColumnIndexOrThrow("install_path")),
            createdAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("created_at_ms")),
            updatedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at_ms")),
        )
    }

    private fun cursorToInstallRun(cursor: Cursor): AiInstallRunRecord {
        return AiInstallRunRecord(
            installId = cursor.getString(cursor.getColumnIndexOrThrow("install_id")),
            modelId = cursor.getString(cursor.getColumnIndexOrThrow("model_id")),
            version = cursor.getString(cursor.getColumnIndexOrThrow("version")),
            action = cursor.getString(cursor.getColumnIndexOrThrow("action")),
            sourceUri = cursor.getString(cursor.getColumnIndexOrThrow("source_uri")),
            expectedHash = cursor.getString(cursor.getColumnIndexOrThrow("expected_hash")),
            actualHash = cursor.getString(cursor.getColumnIndexOrThrow("actual_hash")),
            status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
            details = LocalAiJson.decodeMap(cursor.getString(cursor.getColumnIndexOrThrow("details_json"))),
            retryCount = cursor.getInt(cursor.getColumnIndexOrThrow("retry_count")),
            createdAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("created_at_ms")),
            startedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("started_at_ms")),
            finishedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("finished_at_ms")),
            errorMessage = cursor.getString(cursor.getColumnIndexOrThrow("error_message")),
        )
    }

    private fun cursorToTask(cursor: Cursor): AiTaskRecord {
        return AiTaskRecord(
            taskId = cursor.getString(cursor.getColumnIndexOrThrow("task_id")),
            taskType = cursor.getString(cursor.getColumnIndexOrThrow("task_type")),
            modelId = cursor.getString(cursor.getColumnIndexOrThrow("model_id")),
            version = cursor.getString(cursor.getColumnIndexOrThrow("version")),
            runtimeHint = cursor.getString(cursor.getColumnIndexOrThrow("runtime_hint")),
            priority = cursor.getInt(cursor.getColumnIndexOrThrow("priority")),
            status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
            progress = cursor.getDouble(cursor.getColumnIndexOrThrow("progress")),
            retryCount = cursor.getInt(cursor.getColumnIndexOrThrow("retry_count")),
            maxRetries = cursor.getInt(cursor.getColumnIndexOrThrow("max_retries")),
            cancellationRequested = cursor.getInt(cursor.getColumnIndexOrThrow("cancellation_requested")) == 1,
            pauseRequested = cursor.getIntOrDefault("pause_requested", 0) == 1,
            dependencyTaskIds = LocalAiJson.decodeList(cursor.getStringOrDefault("dependency_task_ids_json", "[]"))
                .map { it.toString() },
            nextRunAtMs = cursor.getLongOrDefault("next_run_at_ms", 0L),
            timeoutMs = cursor.getLongOrDefault("timeout_ms", 0L),
            payload = LocalAiJson.decodeMap(cursor.getString(cursor.getColumnIndexOrThrow("payload_json"))),
            result = LocalAiJson.decodeMap(cursor.getString(cursor.getColumnIndexOrThrow("result_json"))),
            createdAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("created_at_ms")),
            updatedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at_ms")),
            startedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("started_at_ms")),
            finishedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("finished_at_ms")),
            errorMessage = cursor.getString(cursor.getColumnIndexOrThrow("error_message")),
            sessionId = cursor.getStringOrDefault("session_id", ""),
        )
    }

    private fun cursorToCacheEntry(cursor: Cursor): AiCacheEntry {
        return AiCacheEntry(
            cacheKey = cursor.getString(cursor.getColumnIndexOrThrow("cache_key")),
            modelId = cursor.getString(cursor.getColumnIndexOrThrow("model_id")),
            artifactPath = cursor.getString(cursor.getColumnIndexOrThrow("artifact_path")),
            sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow("size_bytes")),
            pinned = cursor.getInt(cursor.getColumnIndexOrThrow("pinned")) == 1,
            metadata = LocalAiJson.decodeMap(cursor.getString(cursor.getColumnIndexOrThrow("metadata_json"))),
            createdAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("created_at_ms")),
            lastAccessMs = cursor.getLong(cursor.getColumnIndexOrThrow("last_access_ms")),
        )
    }

    private fun cursorToPlugin(cursor: Cursor): AiPluginDescriptor {
        return AiPluginDescriptor(
            pluginId = cursor.getString(cursor.getColumnIndexOrThrow("plugin_id")),
            version = cursor.getString(cursor.getColumnIndexOrThrow("version")),
            displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name")),
            enabled = cursor.getInt(cursor.getColumnIndexOrThrow("enabled")) == 1,
            capabilities = LocalAiJson.decodeList(cursor.getString(cursor.getColumnIndexOrThrow("capabilities_json")))
                .map { it.toString() },
            metadata = LocalAiJson.decodeMap(cursor.getString(cursor.getColumnIndexOrThrow("metadata_json"))),
            registeredAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("registered_at_ms")),
            updatedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at_ms")),
        )
    }

    private fun cursorToCapability(cursor: Cursor): AiCapabilityDescriptor {
        return AiCapabilityDescriptor(
            capabilityId = cursor.getString(cursor.getColumnIndexOrThrow("capability_id")),
            providerId = cursor.getString(cursor.getColumnIndexOrThrow("provider_id")),
            capabilityType = cursor.getString(cursor.getColumnIndexOrThrow("capability_type")),
            status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
            metadata = LocalAiJson.decodeMap(cursor.getString(cursor.getColumnIndexOrThrow("metadata_json"))),
            registeredAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("registered_at_ms")),
            updatedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at_ms")),
        )
    }

    private fun cursorToExecutionSession(cursor: Cursor): AiExecutionSessionRecord {
        return AiExecutionSessionRecord(
            sessionId = cursor.getString(cursor.getColumnIndexOrThrow("session_id")),
            taskId = cursor.getString(cursor.getColumnIndexOrThrow("task_id")),
            taskType = cursor.getString(cursor.getColumnIndexOrThrow("task_type")),
            modelId = cursor.getString(cursor.getColumnIndexOrThrow("model_id")),
            version = cursor.getString(cursor.getColumnIndexOrThrow("version")),
            runtimeId = cursor.getString(cursor.getColumnIndexOrThrow("runtime_id")),
            backendId = cursor.getString(cursor.getColumnIndexOrThrow("backend_id")),
            status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
            progress = cursor.getDouble(cursor.getColumnIndexOrThrow("progress")),
            retryCount = cursor.getInt(cursor.getColumnIndexOrThrow("retry_count")),
            reservationBytes = cursor.getLong(cursor.getColumnIndexOrThrow("reservation_bytes")),
            context = LocalAiJson.decodeMap(cursor.getString(cursor.getColumnIndexOrThrow("context_json"))),
            result = LocalAiJson.decodeMap(cursor.getString(cursor.getColumnIndexOrThrow("result_json"))),
            createdAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("created_at_ms")),
            updatedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at_ms")),
            startedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("started_at_ms")),
            finishedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("finished_at_ms")),
            errorMessage = cursor.getString(cursor.getColumnIndexOrThrow("error_message")),
        )
    }

    private fun cursorToExecutionEvent(cursor: Cursor): AiExecutionEventRecord {
        return AiExecutionEventRecord(
            eventId = cursor.getLong(cursor.getColumnIndexOrThrow("event_id")),
            sessionId = cursor.getString(cursor.getColumnIndexOrThrow("session_id")),
            taskId = cursor.getString(cursor.getColumnIndexOrThrow("task_id")),
            eventType = cursor.getString(cursor.getColumnIndexOrThrow("event_type")),
            message = cursor.getString(cursor.getColumnIndexOrThrow("message")),
            progress = cursor.getDouble(cursor.getColumnIndexOrThrow("progress")),
            payload = LocalAiJson.decodeMap(cursor.getString(cursor.getColumnIndexOrThrow("payload_json"))),
            createdAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("created_at_ms")),
        )
    }

    private fun cursorToRuntimeHealth(cursor: Cursor): AiRuntimeHealthSnapshot {
        return AiRuntimeHealthSnapshot(
            snapshotId = cursor.getLong(cursor.getColumnIndexOrThrow("snapshot_id")),
            runtimeId = cursor.getString(cursor.getColumnIndexOrThrow("runtime_id")),
            backendId = cursor.getString(cursor.getColumnIndexOrThrow("backend_id")),
            status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
            healthy = cursor.getInt(cursor.getColumnIndexOrThrow("healthy")) == 1,
            latencyMs = cursor.getLong(cursor.getColumnIndexOrThrow("latency_ms")),
            metadata = LocalAiJson.decodeMap(cursor.getString(cursor.getColumnIndexOrThrow("metadata_json"))),
            capturedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")),
        )
    }

    private fun mapToHardwareProfile(map: Map<String, Any>): AiHardwareProfile {
        return AiHardwareProfile(
            cpuCores = map["cpu_cores"].toIntValue(defaultValue = 1),
            gpuAvailable = map["gpu_available"].toBooleanValue(defaultValue = false),
            npuAvailable = map["npu_available"].toBooleanValue(defaultValue = false),
            totalRamBytes = map["total_ram_bytes"].toLongValue(defaultValue = 0L),
            availableRamBytes = map["available_ram_bytes"].toLongValue(defaultValue = 0L),
            totalStorageBytes = map["total_storage_bytes"].toLongValue(defaultValue = 0L),
            availableStorageBytes = map["available_storage_bytes"].toLongValue(defaultValue = 0L),
            threadCount = map["thread_count"].toIntValue(defaultValue = 1),
            simdFeatures = (map["simd_features"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
            abiList = (map["abi_list"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
            batterySaverEnabled = map["battery_saver_enabled"].toBooleanValue(defaultValue = false),
            charging = map["charging"].toBooleanValue(defaultValue = false),
            thermalStatus = map["thermal_status"].toIntValue(defaultValue = 0),
            deviceManufacturer = map["device_manufacturer"]?.toString().orEmpty(),
            deviceModel = map["device_model"]?.toString().orEmpty(),
            capturedAtMs = map["captured_at_ms"].toLongValue(defaultValue = System.currentTimeMillis()),
        )
    }

    private fun Cursor.getStringOrDefault(column: String, defaultValue: String): String {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) {
            return defaultValue
        }
        return getString(index)
    }

    private fun Cursor.getLongOrDefault(column: String, defaultValue: Long): Long {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) {
            return defaultValue
        }
        return getLong(index)
    }

    private fun Cursor.getIntOrDefault(column: String, defaultValue: Int): Int {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) {
            return defaultValue
        }
        return getInt(index)
    }

    private fun Any?.toLongValue(defaultValue: Long): Long {
        return when (this) {
            is Number -> this.toLong()
            else -> this?.toString()?.toLongOrNull() ?: defaultValue
        }
    }

    private fun Any?.toIntValue(defaultValue: Int): Int {
        return when (this) {
            is Number -> this.toInt()
            else -> this?.toString()?.toIntOrNull() ?: defaultValue
        }
    }

    private fun Any?.toBooleanValue(defaultValue: Boolean): Boolean {
        return when (this) {
            is Boolean -> this
            is Number -> this.toInt() != 0
            else -> this?.toString()?.equals("true", ignoreCase = true) ?: defaultValue
        }
    }
}