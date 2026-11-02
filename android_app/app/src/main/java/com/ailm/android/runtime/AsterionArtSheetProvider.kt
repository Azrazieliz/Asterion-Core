package com.ailm.android.runtime

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only canonical Character Knowledge bridge consumed by Asterion Art's
 * existing CoreSheetBridge. Never shares arbitrary cloud paths or private DB.
 */
class AsterionArtSheetProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.ailm.android.asterionart.sheets"
        private val COLUMNS = arrayOf(
            "sheet_asset_id", "character_id", "character_name",
            "series_code", "sha256", "reference_uri",
        )
    }

    private fun checkArtCaller() {
        val owner = context ?: throw SecurityException("Provider unavailable")
        if (Binder.getCallingUid() == android.os.Process.myUid()) return
        val callers = owner.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if ("com.asterion.art" !in callers) throw SecurityException("Asterion Art only")
    }

    override fun onCreate(): Boolean = context != null

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor {
        checkArtCaller()
        if (uri.pathSegments != listOf("characters")) throw IllegalArgumentException("Unsupported sheet query")
        val owner = requireNotNull(context)
        val search = uri.getQueryParameter("search").orEmpty().trim().take(150)
        val limit = (uri.getQueryParameter("limit")?.toIntOrNull() ?: 48).coerceIn(1, 100)
        val result = MatrixCursor(COLUMNS)
        KnowledgeDatabase(owner).use { knowledge ->
            val args = arrayOf("%" + search + "%", "%" + search + "%", limit.toString())
            knowledge.readableDatabase.rawQuery(
                """
                SELECT s.sheet_asset_id, s.character_id, c.canonical_name,
                       c.primary_series_code, s.sha256
                FROM knowledge_character_sheets s
                JOIN knowledge_characters c ON c.character_id = s.character_id
                WHERE c.canonical_name LIKE ? OR s.character_id LIKE ?
                ORDER BY c.canonical_name COLLATE NOCASE
                LIMIT ?
                """.trimIndent(), args,
            ).use { rows ->
                while (rows.moveToNext()) {
                    val sheetId = rows.getString(0)
                    result.addRow(arrayOf(
                        sheetId, rows.getString(1), rows.getString(2),
                        rows.getString(3), rows.getString(4),
                        Uri.Builder().scheme("content").authority(AUTHORITY)
                            .appendPath("characters").appendPath(sheetId).build().toString(),
                    ))
                }
            }
        }
        return result
    }

    override fun getType(uri: Uri): String = when (uri.pathSegments.size) {
        1 -> "vnd.android.cursor.dir/vnd.asterion.character-sheet"
        2 -> "image/webp"
        else -> throw IllegalArgumentException("Unsupported path")
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        checkArtCaller()
        if (mode != "r" || uri.pathSegments.size != 2 || uri.pathSegments[0] != "characters") {
            throw FileNotFoundException("Read-only character sheet required")
        }
        val id = uri.pathSegments[1]
        if (!id.matches(Regex("CS-CH[0-9]{6}(?:-[0-9]+)?"))) {
            throw FileNotFoundException("Invalid sheet identifier")
        }
        val owner = requireNotNull(context)
        val row = KnowledgeDatabase(owner).use { knowledge ->
            knowledge.listCharacterSheets(5_000).firstOrNull { it["sheet_asset_id"] == id }
        } ?: throw FileNotFoundException("Sheet not imported")
        val safeRoot = File(owner.filesDir, "knowledge/character-sheets").canonicalFile
        val file = File(row["asset_path"].orEmpty()).canonicalFile
        if (file.parentFile != safeRoot || !file.isFile) throw FileNotFoundException("Sheet not available")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Character Knowledge is immutable")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only provider")

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Read-only provider")
}
