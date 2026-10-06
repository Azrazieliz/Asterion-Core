package com.ailm.android.runtime

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import kotlin.random.Random

class LocalRepository(
    private val database: LocalDatabase,
) {
    private companion object {
        private const val REPO_TRACE_TAG = "AilmTraceRepo"
    }

    private val fusionRepository: FusionDatabaseRepository by lazy {
        FusionDatabaseRepository(database)
    }

    data class ImageRecord(
        val imageId: Int,
        val uri: String,
        val filename: String,
        val folderUri: String,
        val parentUri: String,
        val sizeBytes: Long?,
        val width: Int?,
        val height: Int?,
        val createdAtMs: Long?,
        val modifiedAtMs: Long?,
        val extension: String,
        val mimeType: String,
        val resolutionText: String?,
        val aspectRatio: Double?,
        val orientation: String?,
        val folderName: String,
        val relativePath: String,
        val importedOrder: Long,
        val tagsText: String,
        val taxonomyText: String,
        val metadataText: String,
        val active: Int,
        val lastModifiedMs: Long?,
        val scannedAtMs: Long,
    )

    data class ImageUpsertResult(
        val imageId: Int,
        val needsAiProcessing: Boolean,
    )

    fun registerFolder(folderUri: String, enabled: Boolean = true) {
        val values = ContentValues().apply {
            put("folder_uri", folderUri)
            put("enabled", if (enabled) 1 else 0)
            put("added_at_ms", System.currentTimeMillis())
        }
        database.writableDatabase.insertWithOnConflict(
            "library_folders",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun setFolderEnabled(folderUri: String, enabled: Boolean): Boolean {
        val values = ContentValues().apply {
            put("enabled", if (enabled) 1 else 0)
        }
        val count = database.writableDatabase.update(
            "library_folders",
            values,
            "folder_uri = ?",
            arrayOf(folderUri),
        )
        return count > 0
    }

    fun removeFolder(folderUri: String): Boolean {
        database.writableDatabase.beginTransaction()
        try {
            database.writableDatabase.delete("images", "folder_uri = ?", arrayOf(folderUri))
            database.writableDatabase.delete("library_folders", "folder_uri = ?", arrayOf(folderUri))
            database.writableDatabase.setTransactionSuccessful()
            return true
        } finally {
            database.writableDatabase.endTransaction()
        }
    }

    fun listFolders(includeDisabled: Boolean = true): List<Map<String, Any>> {
        val where = if (includeDisabled) "" else "WHERE enabled = 1"
        val sql = """
            SELECT folder_uri, enabled, added_at_ms, last_scan_started_ms, last_scan_completed_ms, last_scan_status, last_scan_count
            FROM library_folders
            $where
            ORDER BY added_at_ms ASC
        """.trimIndent()
        val rows = mutableListOf<Map<String, Any>>()
        database.readableDatabase.rawQuery(sql, emptyArray()).use { cursor ->
            while (cursor.moveToNext()) {
                rows += mapOf(
                    "folder_uri" to cursor.getString(0),
                    "enabled" to (cursor.getInt(1) == 1),
                    "added_at_ms" to cursor.getLong(2),
                    "last_scan_started_ms" to if (cursor.isNull(3)) 0L else cursor.getLong(3),
                    "last_scan_completed_ms" to if (cursor.isNull(4)) 0L else cursor.getLong(4),
                    "last_scan_status" to (if (cursor.isNull(5)) "" else cursor.getString(5)),
                    "last_scan_count" to cursor.getInt(6),
                )
            }
        }
        return rows
    }

    fun beginScanRun(folderUri: String, startedAtMs: Long): Long {
        registerFolder(folderUri, enabled = true)

        val folderValues = ContentValues().apply {
            put("last_scan_started_ms", startedAtMs)
            put("last_scan_status", "running")
        }
        database.writableDatabase.update(
            "library_folders",
            folderValues,
            "folder_uri = ?",
            arrayOf(folderUri),
        )

        val runValues = ContentValues().apply {
            put("folder_uri", folderUri)
            put("started_at_ms", startedAtMs)
            put("status", "running")
            put("discovered_count", 0)
            put("skipped_count", 0)
        }
        return database.writableDatabase.insert("scan_runs", null, runValues)
    }

    fun finishScanRun(
        scanId: Long,
        folderUri: String,
        completedAtMs: Long,
        status: String,
        discoveredCount: Int,
        skippedCount: Int,
        errorMessage: String?,
    ) {
        val runValues = ContentValues().apply {
            put("completed_at_ms", completedAtMs)
            put("status", status)
            put("discovered_count", discoveredCount)
            put("skipped_count", skippedCount)
            put("error_message", errorMessage ?: "")
        }
        database.writableDatabase.update(
            "scan_runs",
            runValues,
            "scan_id = ?",
            arrayOf(scanId.toString()),
        )

        val folderValues = ContentValues().apply {
            put("last_scan_completed_ms", completedAtMs)
            put("last_scan_status", status)
            put("last_scan_count", discoveredCount)
        }
        database.writableDatabase.update(
            "library_folders",
            folderValues,
            "folder_uri = ?",
            arrayOf(folderUri),
        )
    }

    fun upsertImage(
        node: StorageNode,
        folderUri: String,
        scannedAtMs: Long,
        importOrder: Long,
        metadata: ScanMetadata,
        tagsText: String = "",
        taxonomyText: String = "",
        metadataText: String = "",
    ): ImageUpsertResult {
        if (node.isDirectory) {
            return ImageUpsertResult(imageId = 0, needsAiProcessing = false)
        }

        val existing = readImagePreferences(node.uri)
            ?: findUniqueRenameCandidate(
                folderUri = folderUri,
                newUri = node.uri,
                sizeBytes = metadata.sizeBytes ?: node.sizeBytes,
                modifiedAtMs = metadata.modifiedAtMs ?: node.lastModifiedMs,
                currentScanStartedAtMs = scannedAtMs,
            )
        val resolvedFolderName = FolderUriUtils.displayName(folderUri)
        val resolvedRelativePath = if (node.uri.startsWith(folderUri)) {
            node.uri.removePrefix(folderUri).trimStart('/')
        } else {
            metadata.relativePath
        }
        val values = ContentValues().apply {
            put("uri", node.uri)
            put("filename", node.name)
            put("folder_uri", folderUri)
            put("parent_uri", node.parentUri)
            put("size_bytes", metadata.sizeBytes ?: node.sizeBytes)
            put("width", metadata.width)
            put("height", metadata.height)
            put("created_at_ms", metadata.createdAtMs)
            put("modified_at_ms", metadata.modifiedAtMs ?: node.lastModifiedMs)
            put("extension", metadata.extension)
            put("mime_type", metadata.mimeType)
            put("resolution_text", metadata.resolution)
            put("aspect_ratio", metadata.aspectRatio)
            put("orientation", metadata.orientation)
            put("folder_name", if (resolvedFolderName.isBlank()) metadata.folderName else resolvedFolderName)
            put("relative_path", resolvedRelativePath)
            put("imported_order", existing?.importedOrder ?: importOrder)
            put("tags_text", if (tagsText.isBlank()) existing?.tagsText ?: "" else tagsText)
            put("taxonomy_text", if (taxonomyText.isBlank()) existing?.taxonomyText ?: "" else taxonomyText)
            put("metadata_text", if (metadataText.isBlank()) node.name else metadataText)
            put("active", 1)
            put("last_modified_ms", metadata.modifiedAtMs ?: node.lastModifiedMs)
            put("scanned_at_ms", scannedAtMs)
        }
        val imageId = if (existing == null) {
            database.writableDatabase.insert("images", null, values).toInt()
        } else {
            val db = database.writableDatabase
            db.update(
                "images",
                values,
                "image_id = ?",
                arrayOf(existing.imageId.toString()),
            )
            if (existing.uri != node.uri) {
                db.delete("thumbnail_cache", "image_uri = ?", arrayOf(existing.uri))
                db.delete("thumbnail_cache", "image_uri = ?", arrayOf(node.uri))
            }
            existing.imageId
        }
        return ImageUpsertResult(
            imageId = imageId,
            needsAiProcessing = existing == null,
        )
    }

    fun markImageInactive(imageId: Int): Boolean {
        val values = ContentValues().apply { put("active", 0) }
        return database.writableDatabase.update(
            "images",
            values,
            "image_id = ?",
            arrayOf(imageId.toString()),
        ) > 0
    }

    fun markFolderImagesInactiveBefore(folderUri: String, scannedAtMs: Long) {
        val values = ContentValues().apply {
            put("active", 0)
        }
        database.writableDatabase.update(
            "images",
            values,
            "folder_uri = ? AND scanned_at_ms < ?",
            arrayOf(folderUri, scannedAtMs.toString()),
        )
    }

    fun markMissingFolderImagesInactive(folderUri: String, seenUris: Set<String>, scannedAtMs: Long) {
        val values = ContentValues().apply {
            put("active", 0)
            put("scanned_at_ms", scannedAtMs)
        }
        if (seenUris.isEmpty()) {
            database.writableDatabase.update(
                "images",
                values,
                "folder_uri = ?",
                arrayOf(folderUri),
            )
            return
        }

        val placeholders = seenUris.joinToString(",") { "?" }
        val args = mutableListOf<String>()
        args += folderUri
        args += seenUris
        database.writableDatabase.update(
            "images",
            values,
            "folder_uri = ? AND uri NOT IN ($placeholders)",
            args.toTypedArray(),
        )
    }

    fun searchImages(options: LibraryQueryOptions): List<Map<String, Any>> {
        val normalizedPage = if (options.page < 1) 1 else options.page
        val normalizedSize = if (options.pageSize < 0) 0 else options.pageSize
        val offset = (normalizedPage - 1) * normalizedSize

        val whereClauses = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (!options.includeInactive) {
            whereClauses += "i.active = 1"
        }
        options.imageId?.let {
            whereClauses += "i.image_id = ?"
            args += it.toString()
        }
        options.collection?.takeIf { it.isNotBlank() }?.let {
            whereClauses += "i.folder_uri = ?"
            args += it
        }
        options.folderQuery?.takeIf { it.isNotBlank() }?.let {
            whereClauses += "LOWER(i.folder_uri) LIKE ?"
            args += "%${it.trim().lowercase()}%"
        }
        if (!options.query.isNullOrBlank()) {
            whereClauses += "(LOWER(i.filename) LIKE ? OR LOWER(i.uri) LIKE ? OR LOWER(i.metadata_text) LIKE ?)"
            val term = "%${options.query.trim().lowercase()}%"
            args += term
            args += term
            args += term
        }
        for (tag in options.tags.map { it.trim().lowercase() }.filter { it.isNotBlank() }) {
            whereClauses += "LOWER(i.tags_text) LIKE ?"
            args += "%$tag%"
        }
        options.minWidth?.let {
            whereClauses += "COALESCE(i.width, 0) >= ?"
            args += it.toString()
        }
        options.minHeight?.let {
            whereClauses += "COALESCE(i.height, 0) >= ?"
            args += it.toString()
        }
        options.fileFormat?.takeIf { it.isNotBlank() }?.let {
            whereClauses += "LOWER(COALESCE(i.extension, '')) = ?"
            args += it.trim().lowercase()
        }
        options.orientation?.takeIf { it.isNotBlank() && !it.equals("any", ignoreCase = true) }?.let {
            whereClauses += "LOWER(COALESCE(i.orientation, '')) = ?"
            args += it.trim().lowercase()
        }
        if (!options.includeHidden) {
            whereClauses += "i.filename NOT LIKE '.%'"
        }
        if (options.missingOnly) {
            whereClauses += "i.active = 0"
        }
        for ((key, value) in options.taxonomyFilters) {
            if (key.isBlank() || value.isBlank()) {
                continue
            }
            whereClauses += "LOWER(i.taxonomy_text) LIKE ?"
            args += "%${key.lowercase()}=${value.lowercase()}%"
        }

        val joinFts = !options.fullText.isNullOrBlank()
        if (joinFts) {
            whereClauses += "image_fts MATCH ?"
            args += options.fullText!!.trim()
        }

        val where = if (whereClauses.isEmpty()) "" else "WHERE ${whereClauses.joinToString(" AND ")}"
        val orderBy = when (options.sortBy.lowercase()) {
            "filename", "filename_asc", "filename_desc" -> {
                val dir = when (options.sortBy.lowercase()) {
                    "filename_asc" -> "ASC"
                    "filename_desc" -> "DESC"
                    else -> direction(options.sortDirection)
                }
                "i.filename COLLATE NOCASE $dir"
            }
            "date" -> "COALESCE(i.modified_at_ms, i.created_at_ms, i.last_modified_ms, 0) ${direction(options.sortDirection)}"
            "date_added" -> "COALESCE(i.scanned_at_ms, i.imported_order, 0) ${direction(options.sortDirection)}"
            "date_modified" -> "COALESCE(i.modified_at_ms, i.last_modified_ms, i.created_at_ms, 0) ${direction(options.sortDirection)}"
            "size" -> "COALESCE(i.size_bytes, 0) ${direction(options.sortDirection)}"
            "resolution" -> "(COALESCE(i.width, 0) * COALESCE(i.height, 0)) ${direction(options.sortDirection)}"
            "import_order" -> "i.imported_order ${direction(options.sortDirection)}"
            "random" -> "RANDOM()"
            else -> "i.imported_order DESC"
        }

        val from = if (joinFts) {
            "FROM images i JOIN image_fts fts ON fts.rowid = i.image_id"
        } else {
            "FROM images i"
        }

        val sql = """
            SELECT i.image_id, i.uri, i.filename, i.parent_uri, i.folder_uri, i.size_bytes, i.modified_at_ms,
                     i.created_at_ms, i.width, i.height, i.tags_text, i.taxonomy_text,
                     i.metadata_text, i.imported_order, i.active, i.scanned_at_ms, i.last_modified_ms,
                     i.extension, i.mime_type, i.resolution_text, i.aspect_ratio, i.orientation, i.folder_name, i.relative_path
            $from
            $where
            ORDER BY $orderBy
        """.trimIndent()

        val pagedSql = if (normalizedSize > 0) "$sql LIMIT ? OFFSET ?" else sql
        val finalArgs = if (normalizedSize > 0) {
            args + listOf(normalizedSize.toString(), offset.toString())
        } else {
            args
        }
        val rows = mutableListOf<Map<String, Any>>()
        database.readableDatabase.rawQuery(pagedSql, finalArgs.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                rows += cursorToImage(cursor)
            }
        }

        if (options.sortBy.equals("random", ignoreCase = true) && rows.size > 1) {
            rows.shuffle(Random(System.currentTimeMillis()))
        }
        return rows
    }

    fun searchByImageId(imageId: Int): Map<String, Any>? {
        return searchImages(
            LibraryQueryOptions(imageId = imageId, page = 1, pageSize = 1, includeInactive = true),
        ).firstOrNull()
    }

    fun getImageRecordsByIds(imageIds: List<Int>): List<ImageRecord> {
        if (imageIds.isEmpty()) {
            return emptyList()
        }
        val placeholders = imageIds.joinToString(",") { "?" }
        val sql = """
            SELECT image_id, uri, filename, folder_uri, parent_uri, size_bytes, width, height,
                   created_at_ms, modified_at_ms, extension, mime_type, resolution_text,
                   aspect_ratio, orientation, folder_name, relative_path, imported_order,
                   tags_text, taxonomy_text, metadata_text, active,
                   last_modified_ms, scanned_at_ms
            FROM images
            WHERE image_id IN ($placeholders)
        """.trimIndent()
        val rows = mutableListOf<ImageRecord>()
        database.readableDatabase.rawQuery(sql, imageIds.map { it.toString() }.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                rows += ImageRecord(
                    imageId = cursor.getInt(0),
                    uri = cursor.getString(1),
                    filename = cursor.getString(2),
                    folderUri = cursor.getString(3),
                    parentUri = cursor.getString(4) ?: "",
                    sizeBytes = if (cursor.isNull(5)) null else cursor.getLong(5),
                    width = if (cursor.isNull(6)) null else cursor.getInt(6),
                    height = if (cursor.isNull(7)) null else cursor.getInt(7),
                    createdAtMs = if (cursor.isNull(8)) null else cursor.getLong(8),
                    modifiedAtMs = if (cursor.isNull(9)) null else cursor.getLong(9),
                    extension = cursor.getString(10) ?: "",
                    mimeType = cursor.getString(11) ?: "",
                    resolutionText = if (cursor.isNull(12)) null else cursor.getString(12),
                    aspectRatio = if (cursor.isNull(13)) null else cursor.getDouble(13),
                    orientation = if (cursor.isNull(14)) null else cursor.getString(14),
                    folderName = cursor.getString(15) ?: "",
                    relativePath = cursor.getString(16) ?: "",
                    importedOrder = if (cursor.isNull(17)) 0L else cursor.getLong(17),
                    tagsText = cursor.getString(18) ?: "",
                    taxonomyText = cursor.getString(19) ?: "",
                    metadataText = cursor.getString(20) ?: "",
                    active = cursor.getInt(21),
                    lastModifiedMs = if (cursor.isNull(22)) null else cursor.getLong(22),
                    scannedAtMs = if (cursor.isNull(23)) System.currentTimeMillis() else cursor.getLong(23),
                )
            }
        }
        return rows.sortedBy { imageIds.indexOf(it.imageId) }
    }

    fun updateImageRecordPath(
        imageId: Int,
        newUri: String,
        newFilename: String,
        newFolderUri: String,
        newParentUri: String,
        newFolderName: String,
        newRelativePath: String,
        newModifiedAtMs: Long?,
    ): Boolean {
        val values = ContentValues().apply {
            put("uri", newUri)
            put("filename", newFilename)
            put("folder_uri", newFolderUri)
            put("parent_uri", newParentUri)
            put("folder_name", newFolderName)
            put("relative_path", newRelativePath)
            put("modified_at_ms", newModifiedAtMs)
            put("last_modified_ms", newModifiedAtMs)
            put("scanned_at_ms", System.currentTimeMillis())
            put("active", 1)
        }
        val count = database.writableDatabase.update("images", values, "image_id = ?", arrayOf(imageId.toString()))
        return count > 0
    }

    fun updateImagePathAndClearThumbnails(
        imageId: Int,
        newUri: String,
        newFilename: String,
        newFolderUri: String,
        newParentUri: String,
        newFolderName: String,
        newRelativePath: String,
        newModifiedAtMs: Long?,
        oldUri: String,
    ): Boolean {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            logImageRows("before path update", imageId, oldUri, newUri)
            db.delete(
                "images",
                "uri IN (?, ?) AND image_id != ?",
                arrayOf(oldUri, newUri, imageId.toString()),
            )
            val updated = updateImageRecordPath(
                imageId = imageId,
                newUri = newUri,
                newFilename = newFilename,
                newFolderUri = newFolderUri,
                newParentUri = newParentUri,
                newFolderName = newFolderName,
                newRelativePath = newRelativePath,
                newModifiedAtMs = newModifiedAtMs,
            )
            if (!updated) {
                return false
            }
            db.delete("thumbnail_cache", "image_uri = ?", arrayOf(oldUri))
            db.delete("thumbnail_cache", "image_uri = ?", arrayOf(newUri))
            logImageRows("after path update", imageId, oldUri, newUri)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun insertCopiedImageRecord(
        source: ImageRecord,
        newUri: String,
        newFilename: String,
        newFolderUri: String,
        newParentUri: String,
        newFolderName: String,
        newRelativePath: String,
        scannedAtMs: Long,
        importOrder: Long,
    ): Int? {
        val values = ContentValues().apply {
            put("uri", newUri)
            put("filename", newFilename)
            put("folder_uri", newFolderUri)
            put("parent_uri", newParentUri)
            put("size_bytes", source.sizeBytes)
            put("width", source.width)
            put("height", source.height)
            put("created_at_ms", source.createdAtMs)
            put("modified_at_ms", source.modifiedAtMs)
            put("extension", source.extension)
            put("mime_type", source.mimeType)
            put("resolution_text", source.resolutionText)
            put("aspect_ratio", source.aspectRatio)
            put("orientation", source.orientation)
            put("folder_name", newFolderName)
            put("relative_path", newRelativePath)
            put("imported_order", importOrder)
            put("tags_text", source.tagsText)
            put("taxonomy_text", source.taxonomyText)
            put("metadata_text", source.metadataText)
            put("active", 1)
            put("last_modified_ms", source.lastModifiedMs)
            put("scanned_at_ms", scannedAtMs)
        }
        val newId = database.writableDatabase.insert("images", null, values)
        return if (newId < 0) null else newId.toInt()
    }

    fun deleteImageRecordById(imageId: Int): Boolean {
        val count = database.writableDatabase.delete("images", "image_id = ?", arrayOf(imageId.toString()))
        return count > 0
    }

    fun deleteImageAndThumbnail(imageId: Int, imageUri: String): Boolean {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            logImageRows("before delete", imageId, imageUri, imageUri)
            val deleted = db.delete(
                "images",
                "image_id = ? OR uri = ?",
                arrayOf(imageId.toString(), imageUri),
            ) > 0
            if (!deleted) {
                return false
            }
            db.delete("thumbnail_cache", "image_uri = ?", arrayOf(imageUri))
            logImageRows("after delete", imageId, imageUri, imageUri)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun deleteImageRecordByUri(uri: String): Int {
        return database.writableDatabase.delete("images", "uri = ?", arrayOf(uri))
    }

    fun renameFolderUriReferences(oldFolderUri: String, newFolderUri: String): Int {
        val values = ContentValues().apply {
            put("folder_uri", newFolderUri)
            put("folder_name", FolderUriUtils.displayName(newFolderUri))
            put("scanned_at_ms", System.currentTimeMillis())
        }
        return database.writableDatabase.update("images", values, "folder_uri = ?", arrayOf(oldFolderUri))
    }

    fun replaceLibraryFolderUri(oldFolderUri: String, newFolderUri: String): Boolean {
        val enabled = readFolderEnabled(oldFolderUri) ?: true
        database.writableDatabase.beginTransaction()
        try {
            database.writableDatabase.delete("library_folders", "folder_uri = ?", arrayOf(oldFolderUri))
            registerFolder(newFolderUri, enabled)
            database.writableDatabase.setTransactionSuccessful()
            return true
        } finally {
            database.writableDatabase.endTransaction()
        }
    }

    fun renameFolderAndLibraryUri(oldFolderUri: String, newFolderUri: String): Boolean {
        val enabled = readFolderEnabled(oldFolderUri) ?: true
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("folder_uri", newFolderUri)
                put("folder_name", FolderUriUtils.displayName(newFolderUri))
                put("scanned_at_ms", System.currentTimeMillis())
            }
            db.update("images", values, "folder_uri = ?", arrayOf(oldFolderUri))
            db.delete("library_folders", "folder_uri = ?", arrayOf(oldFolderUri))
            val folderValues = ContentValues().apply {
                put("folder_uri", newFolderUri)
                put("enabled", if (enabled) 1 else 0)
                put("added_at_ms", System.currentTimeMillis())
            }
            db.insertWithOnConflict(
                "library_folders",
                null,
                folderValues,
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun clearThumbnailByUri(imageUri: String) {
        database.writableDatabase.delete("thumbnail_cache", "image_uri = ?", arrayOf(imageUri))
    }

    fun listImages(query: String?, page: Int, pageSize: Int): List<Map<String, Any>> {
        return searchImages(
            LibraryQueryOptions(
                query = query,
                page = page,
                pageSize = pageSize,
                sortBy = "import_order",
                sortDirection = "desc",
            ),
        )
    }

    fun statistics(): Map<String, Any> {
        val sql = """
            SELECT
                SUM(CASE WHEN active = 1 THEN 1 ELSE 0 END) AS total_images,
                COALESCE(SUM(CASE WHEN active = 1 THEN size_bytes ELSE 0 END), 0) AS total_size_bytes
            FROM images
        """.trimIndent()
        database.readableDatabase.rawQuery(sql, emptyArray()).use { cursor ->
            if (!cursor.moveToFirst()) {
                return mapOf(
                    "total_images" to 0,
                    "total_size_bytes" to 0L,
                )
            }
            return mapOf(
                "total_images" to cursor.getInt(0),
                "total_size_bytes" to cursor.getLong(1),
            )
        }
    }

    fun collections(page: Int, pageSize: Int): List<Map<String, Any>> {
        val normalizedPage = if (page < 1) 1 else page
        val normalizedSize = if (pageSize < 1) 1 else pageSize
        val offset = (normalizedPage - 1) * normalizedSize
        val rows = mutableListOf<Map<String, Any>>()
        val sql = """
            SELECT f.folder_uri, f.enabled, COUNT(i.image_id) AS image_count
            FROM library_folders f
            LEFT JOIN images i ON i.folder_uri = f.folder_uri AND i.active = 1
            GROUP BY f.folder_uri, f.enabled
            ORDER BY f.added_at_ms ASC
            LIMIT ? OFFSET ?
        """.trimIndent()
        database.readableDatabase.rawQuery(sql, arrayOf(normalizedSize.toString(), offset.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val folderUri = cursor.getString(0)
                rows += mapOf(
                    "collection_id" to folderUri.hashCode(),
                    "name" to if (folderUri.isBlank()) "(root)" else folderUri,
                    "kind" to "dynamic",
                    "image_count" to cursor.getInt(2),
                    "metadata" to mapOf(
                        "folder_uri" to folderUri,
                        "enabled" to (cursor.getInt(1) == 1),
                    ),
                )
            }
        }
        return rows
    }

    /**
     * Removes legacy placeholder review rows that were created merely because an
     * image was discovered by a scan. Real Review entries are created only by
     * recognition/runtime/organization logic when human attention is required.
     */
    fun removeLegacyScanDiscoveryReviews(): Int {
        return database.writableDatabase.delete(
            "review_items",
            "status = ? AND review_type = ? AND reason = ?",
            arrayOf("pending", "generic", "Discovered during scan"),
        )
    }

    fun reviewQueue(limit: Int = 250): List<Map<String, Any>> {
        val rows = mutableListOf<Map<String, Any>>()
        val sql = """
            SELECT review_id, image_uri, COALESCE(image_id, 0), status, review_type,
                   COALESCE(reason, ''), payload_json, correction_json, last_updated_ms
            FROM review_items
            WHERE status = 'pending'
            ORDER BY last_updated_ms DESC, review_id DESC
            LIMIT ?
        """.trimIndent()
        database.readableDatabase.rawQuery(sql, arrayOf(limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                rows += mapOf(
                    "id" to cursor.getLong(0).toString(),
                    "item_id" to cursor.getLong(0).toString(),
                    "path" to cursor.getString(1),
                    "image_id" to cursor.getInt(2),
                    "status" to cursor.getString(3),
                    "review_type" to cursor.getString(4),
                    "reason" to cursor.getString(5),
                    "payload_json" to cursor.getString(6).orEmpty(),
                    "correction_json" to cursor.getString(7).orEmpty(),
                    "updated_at_ms" to cursor.getLong(8),
                )
            }
        }
        return rows
    }

    fun updateReview(itemId: String, action: String, reason: String?): Boolean {
        val id = itemId.toLongOrNull() ?: return false
        val status = when (action.lowercase()) {
            "approve" -> "approved"
            "reject" -> "rejected"
            "skip" -> "skipped"
            else -> return false
        }
        val values = ContentValues().apply {
            put("status", status)
            put("reason", reason ?: "")
            put("last_updated_ms", System.currentTimeMillis())
        }
        val count = database.writableDatabase.update(
            "review_items",
            values,
            "review_id = ?",
            arrayOf(id.toString()),
        )
        return count > 0
    }

    fun undoLastReviewUpdate(): Boolean {
        val sql = """
            SELECT review_id
            FROM review_items
            WHERE status != 'pending'
            ORDER BY last_updated_ms DESC, review_id DESC
            LIMIT 1
        """.trimIndent()
        val id = database.readableDatabase.rawQuery(sql, emptyArray()).use { cursor ->
            if (!cursor.moveToFirst()) {
                return false
            }
            cursor.getLong(0)
        }
        val values = ContentValues().apply {
            put("status", "pending")
            put("reason", "")
            put("last_updated_ms", System.currentTimeMillis())
        }
        val count = database.writableDatabase.update(
            "review_items",
            values,
            "review_id = ?",
            arrayOf(id.toString()),
        )
        return count > 0
    }

    fun listAutomationImageIds(forceAll: Boolean = false): List<Int> {
        val profileTable = FusionDatabaseSchema.TABLE_IMAGE_PROFILES
        val predicate = if (forceAll) {
            "i.active = 1"
        } else {
            """
            i.active = 1 AND (
                p.image_id IS NULL OR
                COALESCE(p.updated_at_ms, 0) < COALESCE(i.scanned_at_ms, 0) OR
                p.metadata_json NOT LIKE '%\"ai_workflow_updated_at_ms\"%'
            )
            """.trimIndent()
        }
        val sql = """
            SELECT i.image_id
            FROM images i
            LEFT JOIN $profileTable p ON p.image_id = i.image_id
            WHERE $predicate
            ORDER BY i.imported_order ASC, i.image_id ASC
        """.trimIndent()
        val ids = mutableListOf<Int>()
        database.readableDatabase.rawQuery(sql, emptyArray()).use { cursor ->
            while (cursor.moveToNext()) {
                ids += cursor.getInt(0)
            }
        }
        return ids
    }

    fun setTags(imageId: Int, tags: List<String>): Boolean {
        val normalized = tags.map { it.trim() }.filter { it.isNotBlank() }.joinToString("|")
        val values = ContentValues().apply { put("tags_text", normalized) }
        val whereClause = "image_id = ?"
        val whereArgs = arrayOf(imageId.toString())
        logImmediatePreferenceSelect(imageId, "before tags update")
        Log.d(REPO_TRACE_TAG, "SQLite UPDATE SQL: UPDATE images SET tags_text = ? WHERE $whereClause args=[$normalized, ${whereArgs.joinToString()}]")
        val count = try {
            database.writableDatabase.update("images", values, whereClause, whereArgs)
        } catch (t: Throwable) {
            Log.e(REPO_TRACE_TAG, "SQLite UPDATE failed (tags): imageId=$imageId requestedTags=$normalized", t)
            throw t
        }
        Log.d(REPO_TRACE_TAG, "AFTER UPDATE reached (tags): imageId=$imageId")
        try {
            Log.d(REPO_TRACE_TAG, "SQLite rows affected (tags): imageId=$imageId rows=$count")
            if (count == 0) {
                Log.d(REPO_TRACE_TAG, "SQLite UPDATE affected 0 rows (tags): where=$whereClause whereArgs=${whereArgs.joinToString()}")
            }
            logSqlChangeCounters("tags", imageId)
            logImmediatePreferenceSelect(imageId, "after tags update")
            logImmediateFtsSelect(imageId)
            Log.d(REPO_TRACE_TAG, "SQLite statement transaction result (tags): imageId=$imageId committed=${count > 0}")
            Log.d(REPO_TRACE_TAG, "Repository returning (tags): imageId=$imageId result=${count > 0}")
            return count > 0
        } catch (t: Throwable) {
            Log.e(REPO_TRACE_TAG, "Post-update logging failed (tags): imageId=$imageId", t)
            throw t
        }
    }

    private fun logImmediatePreferenceSelect(imageId: Int, action: String) {
        val sql = "SELECT image_id, tags_text FROM images WHERE image_id = ?"
        database.readableDatabase.rawQuery(sql, arrayOf(imageId.toString())).use { cursor ->
            if (!cursor.moveToFirst()) {
                Log.d(REPO_TRACE_TAG, "Immediate SELECT ($action): imageId=$imageId row=missing")
                return
            }
            val selectedImageId = cursor.getInt(0)
            val tags = cursor.getString(1) ?: ""
            Log.d(REPO_TRACE_TAG, "Immediate SELECT ($action): image_id=$selectedImageId tags_text=$tags")
        }
    }

    private fun logImmediateFtsSelect(imageId: Int) {
        val sql = "SELECT rowid, tags_text FROM image_fts WHERE rowid = ?"
        database.readableDatabase.rawQuery(sql, arrayOf(imageId.toString())).use { cursor ->
            if (!cursor.moveToFirst()) {
                Log.d(REPO_TRACE_TAG, "Immediate FTS SELECT: imageId=$imageId row=missing")
                return
            }
            Log.d(
                REPO_TRACE_TAG,
                "Immediate FTS SELECT: rowid=${cursor.getLong(0)} tags_text=${cursor.getString(1) ?: ""}",
            )
        }
    }

    private fun logSqlChangeCounters(action: String, imageId: Int) {
        database.readableDatabase.rawQuery("SELECT changes()", emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) {
                Log.d(REPO_TRACE_TAG, "SQLite changes() ($action): imageId=$imageId changes=${cursor.getInt(0)}")
            }
        }
        database.readableDatabase.rawQuery("SELECT total_changes()", emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) {
                Log.d(REPO_TRACE_TAG, "SQLite total_changes() ($action): imageId=$imageId total_changes=${cursor.getLong(0)}")
            }
        }
    }

    fun countImages(options: LibraryQueryOptions): Int {
        val whereClauses = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (!options.includeInactive) {
            whereClauses += "i.active = 1"
        }
        options.imageId?.let {
            whereClauses += "i.image_id = ?"
            args += it.toString()
        }
        options.collection?.takeIf { it.isNotBlank() }?.let {
            whereClauses += "i.folder_uri = ?"
            args += it
        }
        options.folderQuery?.takeIf { it.isNotBlank() }?.let {
            whereClauses += "LOWER(i.folder_uri) LIKE ?"
            args += "%${it.trim().lowercase()}%"
        }
        if (!options.query.isNullOrBlank()) {
            whereClauses += "(LOWER(i.filename) LIKE ? OR LOWER(i.uri) LIKE ? OR LOWER(i.metadata_text) LIKE ?)"
            val term = "%${options.query.trim().lowercase()}%"
            args += term
            args += term
            args += term
        }
        for (tag in options.tags.map { it.trim().lowercase() }.filter { it.isNotBlank() }) {
            whereClauses += "LOWER(i.tags_text) LIKE ?"
            args += "%$tag%"
        }
        options.minWidth?.let {
            whereClauses += "COALESCE(i.width, 0) >= ?"
            args += it.toString()
        }
        options.minHeight?.let {
            whereClauses += "COALESCE(i.height, 0) >= ?"
            args += it.toString()
        }
        options.fileFormat?.takeIf { it.isNotBlank() }?.let {
            whereClauses += "LOWER(COALESCE(i.extension, '')) = ?"
            args += it.trim().lowercase()
        }
        options.orientation?.takeIf { it.isNotBlank() && !it.equals("any", ignoreCase = true) }?.let {
            whereClauses += "LOWER(COALESCE(i.orientation, '')) = ?"
            args += it.trim().lowercase()
        }
        if (!options.includeHidden) {
            whereClauses += "i.filename NOT LIKE '.%'"
        }
        if (options.missingOnly) {
            whereClauses += "i.active = 0"
        }
        val joinFts = !options.fullText.isNullOrBlank()
        if (joinFts) {
            whereClauses += "image_fts MATCH ?"
            args += options.fullText!!.trim()
        }
        val from = if (joinFts) {
            "FROM images i JOIN image_fts fts ON fts.rowid = i.image_id"
        } else {
            "FROM images i"
        }
        val where = if (whereClauses.isEmpty()) "" else "WHERE ${whereClauses.joinToString(" AND ")}" 
        val sql = "SELECT COUNT(*) $from $where"
        database.readableDatabase.rawQuery(sql, args.toTypedArray()).use { cursor ->
            if (!cursor.moveToFirst()) {
                return 0
            }
            return cursor.getInt(0)
        }
    }

    fun listStoredTags(limit: Int = 1000): List<String> {
        val rows = mutableListOf<String>()
        val sql = """
            SELECT tags_text
            FROM images
            WHERE active = 1 AND tags_text != ''
            ORDER BY imported_order DESC
            LIMIT ?
        """.trimIndent()
        database.readableDatabase.rawQuery(sql, arrayOf(limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val tags = cursor.getString(0).orEmpty()
                    .split('|')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                rows += tags
            }
        }
        return rows.distinct()
    }

    fun setSetting(key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        database.writableDatabase.insertWithOnConflict("settings", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getSetting(key: String, defaultValue: String = ""): String {
        val sql = "SELECT value FROM settings WHERE key = ?"
        database.readableDatabase.rawQuery(sql, arrayOf(key)).use { cursor ->
            if (!cursor.moveToFirst()) {
                return defaultValue
            }
            return cursor.getString(0)
        }
    }

    fun rebuildSearchIndex() {
        database.writableDatabase.execSQL("INSERT INTO image_fts(image_fts) VALUES ('rebuild')")
    }

    fun optimizeDatabase() {
        database.writableDatabase.execSQL("PRAGMA optimize")
    }

    fun fusionManagementStatus(): Map<String, Any> {
        return fusionRepository.managementStatus() + mapOf(
            "last_build_ms" to (getSetting("fusion.last_build_ms", "0").toLongOrNull() ?: 0L),
            "last_optimization_ms" to (getSetting("fusion.last_optimization_ms", "0").toLongOrNull() ?: 0L),
            "last_export_ms" to (getSetting("fusion.last_export_ms", "0").toLongOrNull() ?: 0L),
        )
    }

    fun rebuildFusionOptimizations(): Map<String, Any> {
        val before = fusionRepository.logicalContentCounts()
        val db = database.writableDatabase
        var after = before
        var rebuildError: String? = null
        var logicalContentPreserved = false
        db.beginTransaction()
        try {
            FusionDatabaseSchema.ensureArtifacts(db)
            rebuildSearchIndex()
            optimizeDatabase()
            after = fusionRepository.logicalContentCounts()
            logicalContentPreserved = before == after
            if (logicalContentPreserved) {
                db.setTransactionSuccessful()
            } else {
                rebuildError = "Rebuild aborted because logical Fusion row counts changed."
            }
        } catch (error: Throwable) {
            rebuildError = error.message ?: "Fusion rebuild failed."
        } finally {
            db.endTransaction()
        }
        val validation = fusionRepository.validateDatabase(persistRun = logicalContentPreserved)
        if (logicalContentPreserved) {
            val now = System.currentTimeMillis()
            setSetting("fusion.last_build_ms", now.toString())
            setSetting("fusion.last_optimization_ms", now.toString())
        }
        return fusionManagementStatus() + mapOf(
            "ok" to (logicalContentPreserved && validation.valid),
            "action" to "rebuild_fusion_optimizations",
            "logical_content_preserved" to logicalContentPreserved,
            "before_logical_table_counts" to before,
            "after_logical_table_counts" to after,
            "error" to (rebuildError ?: ""),
        )
    }

    fun putThumbnailCache(imageUri: String, cachePath: String, sizeBytes: Long, lastAccessMs: Long) {
        val values = ContentValues().apply {
            put("image_uri", imageUri)
            put("cache_path", cachePath)
            put("size_bytes", sizeBytes)
            put("last_access_ms", lastAccessMs)
        }
        database.writableDatabase.insertWithOnConflict(
            "thumbnail_cache",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun pruneThumbnailCache(maxBytes: Long, cacheDir: File): Map<String, Any> {
        var totalBytes = 0L
        val entries = mutableListOf<Triple<String, String, Long>>()
        database.readableDatabase.rawQuery(
            "SELECT image_uri, cache_path, size_bytes FROM thumbnail_cache ORDER BY last_access_ms DESC",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val uri = cursor.getString(0)
                val path = cursor.getString(1)
                val size = cursor.getLong(2)
                totalBytes += size
                entries += Triple(uri, path, size)
            }
        }

        var removed = 0
        if (totalBytes > maxBytes) {
            for ((uri, path, size) in entries.asReversed()) {
                if (totalBytes <= maxBytes) {
                    break
                }
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
                database.writableDatabase.delete("thumbnail_cache", "image_uri = ?", arrayOf(uri))
                totalBytes -= size
                removed += 1
            }
        }

        // Remove records with missing files.
        database.readableDatabase.rawQuery(
            "SELECT image_uri, cache_path FROM thumbnail_cache",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val uri = cursor.getString(0)
                val path = cursor.getString(1)
                if (!File(path).exists() || !path.startsWith(cacheDir.absolutePath)) {
                    database.writableDatabase.delete("thumbnail_cache", "image_uri = ?", arrayOf(uri))
                }
            }
        }

        return mapOf(
            "max_bytes" to maxBytes,
            "current_bytes" to totalBytes,
            "removed_entries" to removed,
        )
    }

    fun scanStatistics(limit: Int = 20): List<Map<String, Any>> {
        val sql = """
            SELECT scan_id, folder_uri, started_at_ms, completed_at_ms, status, discovered_count, skipped_count, COALESCE(error_message, '')
            FROM scan_runs
            ORDER BY started_at_ms DESC, scan_id DESC
            LIMIT ?
        """.trimIndent()
        val rows = mutableListOf<Map<String, Any>>()
        database.readableDatabase.rawQuery(sql, arrayOf(limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                rows += mapOf(
                    "scan_id" to cursor.getLong(0),
                    "folder_uri" to cursor.getString(1),
                    "started_at_ms" to cursor.getLong(2),
                    "completed_at_ms" to if (cursor.isNull(3)) 0L else cursor.getLong(3),
                    "status" to cursor.getString(4),
                    "discovered_count" to cursor.getInt(5),
                    "skipped_count" to cursor.getInt(6),
                    "error_message" to cursor.getString(7),
                )
            }
        }
        return rows
    }

    fun exportFusionDatabase(format: FusionImportFormat = FusionImportFormat.JSON, pretty: Boolean = true): String {
        return when (format) {
            FusionImportFormat.JSON -> fusionRepository.exportJson(pretty = pretty)
            FusionImportFormat.WORKBOOK_COMPAT -> fusionRepository.exportWorkbookCompatJson(pretty = pretty)
        }
    }

    fun importFusionDatabase(
        payload: String,
        format: FusionImportFormat = FusionImportFormat.JSON,
        replaceExisting: Boolean = false,
    ): FusionImportResult {
        val sourceType = when (format) {
            FusionImportFormat.JSON -> ExternalImportSourceType.JSON
            FusionImportFormat.WORKBOOK_COMPAT -> ExternalImportSourceType.WORKBOOK_COMPAT_JSON
        }
        val result = fusionRepository.importDatabase(
            request = ExternalImportRequest(
                sourceType = sourceType,
                source = payload,
                replaceExisting = replaceExisting,
            ),
        )
        return result.toFusionImportResult(format)
    }

    fun persistFusionRows(candidates: List<FusionRowCandidate>, replaceExisting: Boolean = false): Map<String, Any> {
        return fusionRepository.persistFusionRows(candidates, replaceExisting)
    }

    fun previewImport(
        request: ExternalImportRequest,
        callback: ExternalImportProgressCallback? = null,
    ): ExternalImportPreview {
        return fusionRepository.previewImport(request, callback)
    }

    fun importDatabase(
        request: ExternalImportRequest,
        callback: ExternalImportProgressCallback? = null,
    ): ExternalImportExecutionResult {
        return fusionRepository.importDatabase(request, callback)
    }

    fun cancelImport(importId: String): Boolean {
        return fusionRepository.cancelImport(importId)
    }

    fun rollbackImport(
        importId: String? = null,
        callback: ExternalImportProgressCallback? = null,
    ): ExternalImportExecutionResult {
        return fusionRepository.rollbackImport(importId, callback)
    }

    fun validateImport(
        request: ExternalImportRequest,
        callback: ExternalImportProgressCallback? = null,
    ): FusionValidationReport {
        return fusionRepository.validateImport(request, callback)
    }

    fun validateFusionDatabase(persistRun: Boolean = true): FusionValidationReport {
        return fusionRepository.validateDatabase(persistRun = persistRun)
    }

    private fun direction(sortDirection: String): String {
        return if (sortDirection.equals("asc", ignoreCase = true)) "ASC" else "DESC"
    }

    private fun cursorToImage(cursor: Cursor): Map<String, Any> {
        val imageId = cursor.getLong(cursor.getColumnIndexOrThrow("image_id")).toInt()
        val uri = cursor.getString(cursor.getColumnIndexOrThrow("uri"))
        val filename = cursor.getString(cursor.getColumnIndexOrThrow("filename"))
        val folderUri = cursor.getString(cursor.getColumnIndexOrThrow("folder_uri")) ?: ""
        val extensionCol = cursor.getColumnIndexOrThrow("extension")
        val mimeTypeCol = cursor.getColumnIndexOrThrow("mime_type")
        val resolutionTextCol = cursor.getColumnIndexOrThrow("resolution_text")
        val aspectRatioCol = cursor.getColumnIndexOrThrow("aspect_ratio")
        val orientationCol = cursor.getColumnIndexOrThrow("orientation")
        val folderNameCol = cursor.getColumnIndexOrThrow("folder_name")
        val relativePathCol = cursor.getColumnIndexOrThrow("relative_path")
        val widthCol = cursor.getColumnIndexOrThrow("width")
        val heightCol = cursor.getColumnIndexOrThrow("height")
        val metadata = linkedMapOf<String, Any>()
        metadata["parent_uri"] = cursor.getString(cursor.getColumnIndexOrThrow("parent_uri")) ?: ""
        metadata["folder_uri"] = folderUri
        metadata["imported_order"] = cursor.getLong(cursor.getColumnIndexOrThrow("imported_order"))
        metadata["active"] = cursor.getInt(cursor.getColumnIndexOrThrow("active")) == 1
        val tagsText = cursor.getString(cursor.getColumnIndexOrThrow("tags_text")) ?: ""
        metadata["tags"] = tagsText
            .split('|')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        metadata["user_tags"] = tagsText
        metadata["taxonomy_text"] = cursor.getString(cursor.getColumnIndexOrThrow("taxonomy_text")) ?: ""
        metadata["metadata_text"] = cursor.getString(cursor.getColumnIndexOrThrow("metadata_text")) ?: ""
        metadata["uri"] = uri
        metadata["filename"] = filename

        val extension = if (!cursor.isNull(extensionCol)) cursor.getString(extensionCol) else ""
        val format = if (extension.isBlank()) fileFormatFromName(filename) else extension.lowercase()
        metadata["format"] = format
        val mimeType = if (!cursor.isNull(mimeTypeCol)) cursor.getString(mimeTypeCol) else ""
        metadata["mime_type"] = mimeType
        metadata["scan_source"] = folderUri

        val folderName = if (!cursor.isNull(folderNameCol)) cursor.getString(folderNameCol) else ""
        if (folderName.isNotBlank()) {
            metadata["folder_name"] = folderName
        }

        val relativePath = if (!cursor.isNull(relativePathCol)) cursor.getString(relativePathCol) else ""
        if (relativePath.isNotBlank()) {
            metadata["relative_path"] = relativePath
        }

        val sizeCol = cursor.getColumnIndexOrThrow("size_bytes")
        if (!cursor.isNull(sizeCol)) {
            metadata["size_bytes"] = cursor.getLong(sizeCol)
        }
        val createdCol = cursor.getColumnIndexOrThrow("created_at_ms")
        if (!cursor.isNull(createdCol)) {
            metadata["created_at_ms"] = cursor.getLong(createdCol)
        }
        val modifiedCol = cursor.getColumnIndexOrThrow("modified_at_ms")
        if (!cursor.isNull(modifiedCol)) {
            metadata["modified_at_ms"] = cursor.getLong(modifiedCol)
        }
        val lastModifiedCol = cursor.getColumnIndexOrThrow("last_modified_ms")
        if (!cursor.isNull(lastModifiedCol)) {
            metadata["last_modified_ms"] = cursor.getLong(lastModifiedCol)
        }
        val scannedAtCol = cursor.getColumnIndexOrThrow("scanned_at_ms")
        if (!cursor.isNull(scannedAtCol)) {
            metadata["date_indexed_ms"] = cursor.getLong(scannedAtCol)
        }
        if (!cursor.isNull(resolutionTextCol)) {
            metadata["resolution"] = cursor.getString(resolutionTextCol)
        }
        if (!cursor.isNull(aspectRatioCol)) {
            metadata["aspect_ratio"] = cursor.getDouble(aspectRatioCol)
        }
        if (!cursor.isNull(orientationCol)) {
            metadata["orientation"] = cursor.getString(orientationCol)
        }
        if (!cursor.isNull(widthCol) && !cursor.isNull(heightCol)) {
            val width = cursor.getInt(widthCol)
            val height = cursor.getInt(heightCol)
            metadata["width"] = width
            metadata["height"] = height
        }

        return linkedMapOf(
            "image_id" to imageId,
            "path" to uri,
            "uri" to uri,
            "filename" to filename,
            "folder_uri" to folderUri,
            "format" to format,
            "mime_type" to mimeType,
            "thumbnail_url" to uri,
            "file_url" to uri,
            "date_indexed_ms" to (metadata["date_indexed_ms"] ?: 0L),
            "metadata" to metadata,
        )
    }

    private fun readFolderEnabled(folderUri: String): Boolean? {
        val sql = "SELECT enabled FROM library_folders WHERE folder_uri = ? LIMIT 1"
        database.readableDatabase.rawQuery(sql, arrayOf(folderUri)).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            return cursor.getInt(0) == 1
        }
    }

    private fun fileFormatFromName(name: String): String {
        val idx = name.lastIndexOf('.')
        if (idx < 0 || idx >= name.lastIndex) {
            return "unknown"
        }
        return name.substring(idx + 1).lowercase()
    }

    private data class ImagePreferences(
        val imageId: Int,
        val uri: String,
        val tagsText: String,
        val taxonomyText: String,
        val importedOrder: Long,
    )

    private fun readImagePreferences(uri: String): ImagePreferences? {
        val sql = "SELECT image_id, uri, tags_text, taxonomy_text, imported_order FROM images WHERE uri = ? LIMIT 1"
        database.readableDatabase.rawQuery(sql, arrayOf(uri)).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            return ImagePreferences(
                imageId = cursor.getInt(0),
                uri = cursor.getString(1) ?: uri,
                tagsText = cursor.getString(2) ?: "",
                taxonomyText = cursor.getString(3) ?: "",
                importedOrder = if (cursor.isNull(4)) 0L else cursor.getLong(4),
            )
        }
    }

    private fun findUniqueRenameCandidate(
        folderUri: String,
        newUri: String,
        sizeBytes: Long?,
        modifiedAtMs: Long?,
        currentScanStartedAtMs: Long,
    ): ImagePreferences? {
        if (sizeBytes == null || modifiedAtMs == null || modifiedAtMs <= 0L) {
            return null
        }

        // Rows already seen in this scan have scanned_at_ms >= currentScanStartedAtMs.
        // This is constant-memory and avoids an ever-growing SQL NOT IN list.
        val sql = """
            SELECT image_id, uri, tags_text, taxonomy_text, imported_order
            FROM images
            WHERE folder_uri = ?
              AND active = 1
              AND uri <> ?
              AND COALESCE(size_bytes, -1) = ?
              AND COALESCE(last_modified_ms, modified_at_ms, -1) = ?
              AND scanned_at_ms < ?
            LIMIT 2
        """.trimIndent()
        val args = mutableListOf(
            folderUri,
            newUri,
            sizeBytes.toString(),
            modifiedAtMs.toString(),
            currentScanStartedAtMs.toString(),
        )
        val candidates = mutableListOf<ImagePreferences>()
        database.readableDatabase.rawQuery(sql, args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                candidates += ImagePreferences(
                    imageId = cursor.getInt(0),
                    uri = cursor.getString(1) ?: "",
                    tagsText = cursor.getString(2) ?: "",
                    taxonomyText = cursor.getString(3) ?: "",
                    importedOrder = if (cursor.isNull(4)) 0L else cursor.getLong(4),
                )
            }
        }
        return candidates.singleOrNull()
    }

    private fun logImageRows(action: String, imageId: Int, oldUri: String, newUri: String) {
        val sql = """
            SELECT image_id, uri, relative_path, filename, active
            FROM images
            WHERE image_id = ? OR uri IN (?, ?)
            ORDER BY image_id
        """.trimIndent()
        val rows = mutableListOf<String>()
        database.readableDatabase.rawQuery(sql, arrayOf(imageId.toString(), oldUri, newUri)).use { cursor ->
            while (cursor.moveToNext()) {
                rows += "id=${cursor.getInt(0)} uri=${cursor.getString(1)} relative_path=${cursor.getString(2)} filename=${cursor.getString(3)} active=${cursor.getInt(4)}"
            }
        }
        Log.d(REPO_TRACE_TAG, "Image rows $action: ${rows.ifEmpty { listOf("none") }.joinToString(" | ")}")
    }
}
