package com.ailm.android.runtime

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Protect existing user data before reference reconciliation. A corrupt FTS4
 * inverted index is a rebuildable derivative of images, not evidence that the
 * original gallery/character records are damaged. Never discard a readable
 * snapshot in order to attempt index repair.
 */
internal class FusionMigrationBackup(
    private val context: Context,
    private val database: LocalDatabase,
) {
    private val prefs = context.getSharedPreferences("fusion_migration_backup_v1", Context.MODE_PRIVATE)

    fun ensureBeforeReferenceReconcile(): Map<String, Any> {
        val existing = prefs.getString("path", "").orEmpty().takeIf(String::isNotBlank)?.let(::File)
        val storedHash = prefs.getString("sha256", "").orEmpty()
        val storedSize = prefs.getLong("size_bytes", -1L)
        if (existing != null && existing.isFile && existing.length() == storedSize &&
            storedHash.matches(Regex("[0-9a-f]{64}")) && sha256(existing) == storedHash) {
            val requiresRepair = prefs.getBoolean("fts4_repair_required", false)
            if (requiresRepair) {
                rebuildDerivedSearchIndex()
                require(prefs.edit().putBoolean("fts4_repair_required", false).commit()) {
                    "Repaired index, but recovery checkpoint could not be saved."
                }
            }
            return mapOf(
                "ok" to true, "status" to "backup_available",
                "path" to existing.absolutePath, "sha256" to storedHash,
                "fts4_repaired" to requiresRepair,
            )
        }

        val directory = File(context.noBackupFilesDir, "fusion-pre-migration")
        require(directory.isDirectory || directory.mkdirs()) { "Cannot prepare private migration backup folder" }
        val backup = File(directory, "fusion-before-20261108-" + System.currentTimeMillis() + ".sqlite")
        require(!backup.exists()) { "Migration snapshot target already exists" }
        var checkpointCommitted = false

        try {
            // VACUUM INTO captures a coherent WAL-aware database; it does not
            // mutate the installed original or require a full-library rescan.
            database.writableDatabase.execSQL("VACUUM INTO ?", arrayOf(backup.absolutePath))
            require(backup.isFile && backup.length() > 0L) { "Pre-migration snapshot is empty" }

            val counts = linkedMapOf<String, Int>()
            var derivedFtsCorrupt = false
            SQLiteDatabase.openDatabase(
                backup.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
            ).use { copy ->
                val integrityMessages = mutableListOf<String>()
                copy.rawQuery("PRAGMA integrity_check", emptyArray()).use { rows ->
                    while (rows.moveToNext()) {
                        integrityMessages += rows.getString(0).orEmpty()
                    }
                }
                val failures = integrityMessages.filterNot { it.equals("ok", ignoreCase = true) }
                derivedFtsCorrupt = failures.isNotEmpty() && failures.all { error ->
                    error.contains("malformed inverted index for FTS4 table main.image_fts", ignoreCase = true)
                }
                require(failures.isEmpty() || derivedFtsCorrupt) {
                    "Pre-migration SQLite integrity check found non-rebuildable errors: " +
                        failures.take(3).joinToString("; ")
                }
                val invalidForeignKeys = copy.rawQuery("PRAGMA foreign_key_check", emptyArray())
                    .use { it.moveToFirst() }
                require(!invalidForeignKeys) {
                    "Pre-migration snapshot has invalid foreign-key references"
                }
                for (table in listOf("images", "fusion_tags", "fusion_characters")) {
                    counts[table] = copy.rawQuery(
                        "SELECT COUNT(*) FROM " + table, emptyArray(),
                    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
                }
            }
            val sha = sha256(backup)
            val manifest = JSONObject()
                .put("schema", "asterion-fusion-pre-reconciliation")
                .put("captured_at_ms", System.currentTimeMillis())
                .put("sqlite_filename", backup.name)
                .put("size_bytes", backup.length())
                .put("sha256", sha)
                .put("integrity", if (derivedFtsCorrupt) "derived_fts4_repair_required" else "ok")
                .put("table_counts", JSONObject(counts))
            val manifestFile = File(directory, backup.name + ".json")
            val temporary = File(directory, backup.name + ".json.pending")
            temporary.writeText(manifest.toString(2))
            require(temporary.renameTo(manifestFile)) { "Cannot commit migration backup manifest" }
            val saved = prefs.edit()
                .putString("path", backup.absolutePath)
                .putString("sha256", sha)
                .putLong("size_bytes", backup.length())
                .putBoolean("fts4_repair_required", derivedFtsCorrupt)
                .commit()
            require(saved) { "Unable to persist migration backup checkpoint" }
            checkpointCommitted = true

            if (derivedFtsCorrupt) {
                // The snapshot was saved BEFORE repairing a derived virtual
                // index, and can recover original artwork and Fusion records.
                rebuildDerivedSearchIndex()
                require(prefs.edit().putBoolean("fts4_repair_required", false).commit()) {
                    "Repaired search index, but recovery checkpoint could not be saved."
                }
            }
            return mapOf(
                "ok" to true, "status" to "backup_verified",
                "path" to backup.absolutePath, "sha256" to sha,
                "table_counts" to counts, "fts4_repaired" to derivedFtsCorrupt,
            )
        } catch (failure: Exception) {
            // Never delete a successfully checkpointed snapshot when rebuilding
            // a damaged derived index fails. The next attempt can safely retry.
            if (!checkpointCommitted) backup.delete()
            throw IllegalStateException(
                "Fusion reconciliation paused to protect your library: " +
                    (failure.message ?: failure.javaClass.simpleName),
                failure,
            )
        }
    }

    private fun rebuildDerivedSearchIndex() {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val before = db.rawQuery("SELECT COUNT(*) FROM images", emptyArray()).use {
                if (it.moveToFirst()) it.getLong(0) else -1L
            }
            require(before >= 0L) { "Cannot validate the original image catalog." }
            // FTS4 content='images': this reconstructs the inverted index
            // from the unchanged canonical images table.
            db.execSQL("INSERT INTO image_fts(image_fts) VALUES ('rebuild')")
            val after = db.rawQuery("SELECT COUNT(*) FROM images", emptyArray()).use {
                if (it.moveToFirst()) it.getLong(0) else -1L
            }
            require(before == after) { "Image catalog changed during index rebuild." }
            val integrity = db.rawQuery("PRAGMA integrity_check", emptyArray()).use {
                if (it.moveToFirst()) it.getString(0).orEmpty() else ""
            }
            require(integrity.equals("ok", ignoreCase = true)) {
                "Search index rebuild could not restore SQLite integrity: " + integrity
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(131072)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                if (count > 0) digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
