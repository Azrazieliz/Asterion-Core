package com.ailm.android.runtime

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only, signature-permission Core -> Art reference bridge.
 * No public database dump and no filesystem path disclosed to the caller.
 * Both release APKs must use the same signing identity.
 */
class AsterionArtSheetProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.ailm.android.asterionart.sheets"
        private const val LIST = 1
        private const val IMAGE = 2
        private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "characters", LIST)
            addURI(AUTHORITY, "image/*", IMAGE)
        }
        private val columns = arrayOf(
            "_id", "sheet_asset_id", "character_id", "character_name",
            "series_code", "sha256", "reference_uri"
        )
    }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?,
                       selection: String?, selectionArgs: Array<out String>?,
                       sortOrder: String?): Cursor? {
        if (matcher.match(uri) != LIST) throw IllegalArgumentException("Unknown Core query endpoint")
        val context = requireNotNull(context)
        val search = uri.getQueryParameter("search").orEmpty().trim().take(150)
        val limit = uri.getQueryParameter("limit")?.toIntOrNull()?.coerceIn(1, 100) ?: 48
        val result = MatrixCursor(columns)
        KnowledgeDatabase(context).use { db ->
            db.readableDatabase.rawQuery(
                """
                SELECT s.sheet_asset_id, s.character_id, c.canonical_name,
                       c.primary_series_code, s.sha256
                FROM knowledge_character_sheets s
                JOIN knowledge_characters c ON c.character_id = s.character_id
                WHERE (? = '' OR instr(lower(c.canonical_name), lower(?)) > 0
                        OR instr(lower(s.character_id), lower(?)) > 0)
                ORDER BY c.canonical_name COLLATE NOCASE, s.character_id
                LIMIT ?
                """.trimIndent(),
                arrayOf(search, search, search, limit.toString())
            ).use { cursor ->
                var index = 1
                while (cursor.moveToNext()) {
                    val sheetId = cursor.getString(0)
                    val access = Uri.Builder().scheme("content").authority(AUTHORITY)
                        .appendPath("image").appendPath(sheetId).build()
                    result.addRow(arrayOf(
                        index++, sheetId, cursor.getString(1), cursor.getString(2),
                        cursor.getString(3), cursor.getString(4), access.toString()
                    ))
                }
            }
        }
        result.setNotificationUri(context.contentResolver, uri)
        return result
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (matcher.match(uri) != IMAGE || mode != "r")
            throw FileNotFoundException("Read-only sheet access")
        val key = uri.lastPathSegment.orEmpty()
        if (!key.matches(Regex("[A-Za-z0-9_-]{1,110}")))
            throw FileNotFoundException("Invalid sheet identifier")
        val context = requireNotNull(context)
        val root = File(context.filesDir, "knowledge/character-sheets").canonicalFile
        val file = KnowledgeDatabase(context).use { db ->
            db.readableDatabase.rawQuery(
                "SELECT asset_path FROM knowledge_character_sheets WHERE sheet_asset_id = ? LIMIT 1",
                arrayOf(key)
            ).use { cursor ->
                if (cursor.moveToFirst()) File(cursor.getString(0)).canonicalFile else null
            }
        } ?: throw FileNotFoundException("Unknown sheet")
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile)
            throw FileNotFoundException("Sheet path outside managed Core library")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        LIST -> "vnd.android.cursor.dir/vnd.asterion.charactersheet"
        IMAGE -> "image/webp"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Core reference provider is read-only")
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int =
        throw UnsupportedOperationException("Core reference provider is read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        args: Array<out String>?): Int =
        throw UnsupportedOperationException("Core reference provider is read-only")
}
