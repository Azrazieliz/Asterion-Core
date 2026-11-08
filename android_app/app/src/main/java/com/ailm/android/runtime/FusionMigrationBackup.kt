package com.ailm.android.runtime

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Durable, one-time pre-reconciliation backup of the existing user database.
 *
 * A Fusion migration is not allowed to start until a coherent, independently
 * reopened SQLite snapshot exists. Originals and installed Knowledge files
 * are never modified here. All outputs stay in private app storage.
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
            storedHash.matches(Regex("[0-9a-f]{64}"))) {
            return mapOf("ok" to true, "status" to "backup_available",
                "path" to existing.absolutePath, "sha256" to storedHash)
        }

        val directory = File(context.noBackupFilesDir, "fusion-pre-migration")
        require(directory.isDirectory || directory.mkdirs()) { "Cannot prepare private migration backup folder" }
        val backup = File(directory, "fusion-before-20261108-" + System.currentTimeMillis() + ".sqlite")
        require(!backup.exists()) { "Migration snapshot target already exists" }

        try {
            database.writableDatabase.execSQL("VACUUM INTO ?", arrayOf(backup.absolutePath))
            require(backup.isFile && backup.length() > 0L) {
                "Pre-migration snapshot is empty"
            }
            val counts = linkedMapOf<String, Int>()
            SQLiteDatabase.openDatabase(
                backup.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
            ).use { copy ->
                val integrity = copy.rawQuery("PRAGMA integrity_check", emptyArray())
                    .use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }
                require(integrity.equals("ok", ignoreCase = true)) {
                    "Pre-migration SQLite integrity check failed: " + integrity
                }
                val invalidForeignKeys = copy.rawQuery("PRAGMA foreign_key_check", emptyArray())
                    .use { it.moveToFirst() }
                require(!invalidForeignKeys) {
                    "Pre-migration snapshot has invalid foreign-key references"
                }
                for (table in listOf("images", "fusion_tags", "fusion_characters")) {
                    val count = copy.rawQuery(
                        "SELECT COUNT(*) FROM " + table, emptyArray(),
                    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
                    counts[table] = count
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            backup.inputStream().buffered().use { input ->
                val bytes = ByteArray(131072)
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    if (count > 0) digest.update(bytes, 0, count)
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val manifest = JSONObject()
                .put("schema", "asterion-fusion-pre-reconciliation")
                .put("captured_at_ms", System.currentTimeMillis())
                .put("sqlite_filename", backup.name)
                .put("size_bytes", backup.length())
                .put("sha256", sha)
                .put("integrity", "ok")
                .put("table_counts", JSONObject(counts))
            val manifestFile = File(directory, backup.name + ".json")
            val temporary = File(directory, backup.name + ".json.pending")
            temporary.writeText(manifest.toString(2))
            require(temporary.renameTo(manifestFile)) { "Cannot commit migration backup manifest" }
            val saved = prefs.edit()
                .putString("path", backup.absolutePath)
                .putString("sha256", sha)
                .putLong("size_bytes", backup.length())
                .commit()
            require(saved) { "Unable to persist migration backup checkpoint" }
            return mapOf("ok" to true, "status" to "backup_verified",
                "path" to backup.absolutePath, "sha256" to sha, "table_counts" to counts)
        } catch (failure: Exception) {
            // Only our freshly generated incomplete snapshot is expendable.
            // Never remove earlier backups, Knowledge imports, or the live DB.
            backup.delete()
            throw IllegalStateException(
                "Fusion reconciliation was prevented because a verified backup could not be created: " +
                    (failure.message ?: failure.javaClass.simpleName),
                failure,
            )
        }
    }
}
