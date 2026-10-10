package com.ailm.android.runtime

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

class SafStorageProvider(
    context: Context,
) : StorageProvider {

    companion object {
        private const val TAG = "AilmSafStorageProvider"
        private const val TIMING_TAG = "AilmFileOpTiming"
    }

    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    override fun walkTree(rootUri: String): Sequence<StorageNode> = sequence {
        val root = resolveDocument(rootUri, mutableMapOf()) ?: return@sequence
        val stack = ArrayDeque<Pair<DocumentFile, String?>>()
        stack.add(root to null)

        while (stack.isNotEmpty()) {
            val (node, parentUri) = stack.removeLast()
            val name = node.name ?: node.uri.lastPathSegment ?: "unknown"
            val item = StorageNode(
                uri = node.uri.toString(),
                name = name,
                isDirectory = node.isDirectory,
                sizeBytes = if (node.isDirectory) null else node.length().takeIf { it >= 0L },
                lastModifiedMs = node.lastModified().takeIf { it > 0L },
                parentUri = parentUri,
            )
            yield(item)

            if (node.isDirectory) {
                val children = node.listFiles().toList()
                for (child: DocumentFile in children.asReversed()) {
                    stack.add(child to node.uri.toString())
                }
            }
        }
    }

    override fun openInputStream(uri: String): InputStream? {
        return resolver.openInputStream(Uri.parse(uri))
    }

    override fun listChildren(folderUri: String): List<StorageNode> {
        val cache = mutableMapOf<String, DocumentFile?>()
        val folder = resolveDocument(folderUri, cache) ?: return emptyList()
        if (!folder.isDirectory) {
            return emptyList()
        }
        return folder.listFiles().map { child ->
            StorageNode(
                uri = child.uri.toString(),
                name = child.name ?: child.uri.lastPathSegment ?: "unknown",
                isDirectory = child.isDirectory,
                sizeBytes = if (child.isDirectory) null else child.length().takeIf { it >= 0L },
                lastModifiedMs = child.lastModified().takeIf { it > 0L },
                parentUri = folder.uri.toString(),
            )
        }
    }

    override fun exists(uri: String): Boolean {
        val parsed = Uri.parse(uri)
        if (!hasPersistedTreePermission(parsed)) {
            Log.w(TAG, "Persisted SAF permission missing for $uri")
            return false
        }

        val treeDocument = DocumentFile.fromTreeUri(appContext, parsed)
        if (treeDocument != null) {
            return treeDocument.exists()
        }

        val document = DocumentFile.fromSingleUri(appContext, parsed)
        return document?.exists() == true
    }

    override fun rename(uri: String, newName: String, parentUri: String): StorageWriteResult {
        val totalStart = SystemClock.elapsedRealtime()
        val cleaned = newName.trim()
        if (cleaned.isBlank()) {
            return StorageWriteResult(ok = false, message = "new name is required")
        }
        val cache = mutableMapOf<String, DocumentFile?>()
        val stage1Start = SystemClock.elapsedRealtime()
        val document = resolveDocument(uri, cache) ?: return StorageWriteResult(ok = false, message = "source not found")
        val parent = parentUri.trim().takeIf { it.isNotBlank() }?.let { resolveDocument(it, cache) } ?: document.parentFile
        val stage1Ms = SystemClock.elapsedRealtime() - stage1Start

        val stage4Start = SystemClock.elapsedRealtime()
        val ok = document.renameTo(cleaned)
        val stage4Ms = SystemClock.elapsedRealtime() - stage4Start
        if (ok) {
            val renamedUri = resolveRenamedUri(parent, cleaned)
                ?: return StorageWriteResult(ok = false, message = "renamed document URI could not be resolved")
            val totalMs = SystemClock.elapsedRealtime() - totalStart
            Log.d(TIMING_TAG, "rename stage1_resolve_source_ms=$stage1Ms")
            Log.d(TIMING_TAG, "rename stage2_resolve_destination_ms=0")
            Log.d(TIMING_TAG, "rename stage3_conflict_detection_ms=0")
            Log.d(TIMING_TAG, "rename stage4_saf_call_ms=$stage4Ms")
            Log.d(TIMING_TAG, "rename stage1to4_total_ms=$totalMs")
            return StorageWriteResult(ok = true, uri = renamedUri, changed = true)
        }
        val stage3Start = SystemClock.elapsedRealtime()
        if (parent != null) {
            val sibling = parent.findFile(cleaned)
            val stage3Ms = SystemClock.elapsedRealtime() - stage3Start
            val totalMs = SystemClock.elapsedRealtime() - totalStart
            Log.d(TIMING_TAG, "rename stage1_resolve_source_ms=$stage1Ms")
            Log.d(TIMING_TAG, "rename stage2_resolve_destination_ms=0")
            Log.d(TIMING_TAG, "rename stage3_conflict_detection_ms=$stage3Ms")
            Log.d(TIMING_TAG, "rename stage4_saf_call_ms=$stage4Ms")
            Log.d(TIMING_TAG, "rename stage1to4_total_ms=$totalMs")
            if (sibling != null && sibling.uri != document.uri) {
                return StorageWriteResult(ok = false, message = "name conflict in folder")
            }
        } else {
            val totalMs = SystemClock.elapsedRealtime() - totalStart
            Log.d(TIMING_TAG, "rename stage1_resolve_source_ms=$stage1Ms")
            Log.d(TIMING_TAG, "rename stage2_resolve_destination_ms=0")
            Log.d(TIMING_TAG, "rename stage3_conflict_detection_ms=0")
            Log.d(TIMING_TAG, "rename stage4_saf_call_ms=$stage4Ms")
            Log.d(TIMING_TAG, "rename stage1to4_total_ms=$totalMs")
        }
        return StorageWriteResult(ok = false, message = "rename failed")
    }

    private fun resolveRenamedUri(parent: DocumentFile?, expectedName: String): String? {
        return parent?.findFile(expectedName)?.uri?.toString()
            ?: parent?.listFiles()?.firstOrNull { child ->
                child.name.equals(expectedName, ignoreCase = true)
            }?.uri?.toString()
    }

    override fun createFolder(parentUri: String, folderName: String): StorageWriteResult {
        val cleaned = folderName.trim()
        if (cleaned.isBlank()) {
            return StorageWriteResult(ok = false, message = "folder name is required")
        }
        val cache = mutableMapOf<String, DocumentFile?>()
        val parent = resolveDocument(parentUri, cache) ?: return StorageWriteResult(ok = false, message = "parent not found")
        if (!parent.isDirectory) {
            return StorageWriteResult(ok = false, message = "parent is not a directory")
        }
        val created = parent.createDirectory(cleaned)
        if (created != null) {
            return StorageWriteResult(ok = true, uri = created.uri.toString(), changed = true)
        }
        val existing = parent.findFile(cleaned)
        if (existing != null && existing.isDirectory) {
            return StorageWriteResult(ok = true, uri = existing.uri.toString(), changed = false, message = "already exists")
        }
        if (existing != null) {
            return StorageWriteResult(ok = false, message = "file with same name exists")
        }
        return StorageWriteResult(ok = false, message = "folder create failed")
    }

    override fun delete(uri: String): StorageWriteResult {
        val cache = mutableMapOf<String, DocumentFile?>()
        val document = resolveDocument(uri, cache) ?: return StorageWriteResult(ok = false, message = "target not found")
        val ok = document.delete()
        return if (ok) {
            StorageWriteResult(ok = true, changed = true)
        } else {
            StorageWriteResult(ok = false, message = "delete failed")
        }
    }

    override fun copy(sourceUri: String, targetFolderUri: String, preferredName: String?): StorageWriteResult {
        val cache = mutableMapOf<String, DocumentFile?>()
        val source = resolveDocument(sourceUri, cache) ?: return StorageWriteResult(ok = false, message = "source not found")
        if (source.isDirectory) {
            return StorageWriteResult(ok = false, message = "directory copy is not supported")
        }
        val targetFolder = resolveDocument(targetFolderUri, cache) ?: return StorageWriteResult(ok = false, message = "target folder not found")
        if (!targetFolder.isDirectory) {
            return StorageWriteResult(ok = false, message = "target is not a directory")
        }
        if (isExperimentalRoundSyncProvider(source.uri) || isExperimentalRoundSyncProvider(targetFolder.uri)) {
            // The VCP is a streamed remote. Never fall back to an unverified
            // remote download/upload for a cross-folder copy.
            if (source.uri.authority != targetFolder.uri.authority) {
                return StorageWriteResult(ok = false, message = "Cross-provider cloud copy is not supported")
            }
            val desiredName = preferredName?.trim().orEmpty().ifBlank { source.name.orEmpty() }
            if (desiredName.isBlank() || targetFolder.findFile(desiredName) != null) {
                return StorageWriteResult(ok = false, message = "Cloud copy name is missing or already exists")
            }
            val remoteCopy = try {
                DocumentsContract.copyDocument(resolver, source.uri, targetFolder.uri)
            } catch (error: Exception) {
                Log.w(TAG, "Round Sync provider refused native copy", error)
                null
            } ?: return StorageWriteResult(ok = false, message = "Round Sync does not support safe native copy for this remote")
            if (desiredName != source.name) {
                val renamed = DocumentFile.fromSingleUri(appContext, remoteCopy)?.renameTo(desiredName) == true
                if (!renamed) {
                    return StorageWriteResult(ok = false, uri = remoteCopy.toString(),
                        message = "Copied remotely, but provider refused to name it; check destination")
                }
            }
            val destination = targetFolder.findFile(desiredName)
                ?: return StorageWriteResult(ok = false, uri = remoteCopy.toString(),
                    message = "Remote copy not visible after operation; original retained")
            return StorageWriteResult(ok = true, uri = destination.uri.toString(), changed = true)
        }
        return copyResolved(source, targetFolder, preferredName)
    }

    override fun move(sourceUri: String, targetFolderUri: String, preferredName: String?): StorageWriteResult {
        val totalStart = SystemClock.elapsedRealtime()
        val cache = mutableMapOf<String, DocumentFile?>()
        val stage1Start = SystemClock.elapsedRealtime()
        val source = resolveDocument(sourceUri, cache) ?: return StorageWriteResult(ok = false, message = "source not found")
        val stage1Ms = SystemClock.elapsedRealtime() - stage1Start
        val sourceParent = source.parentFile?.uri?.toString().orEmpty()

        val stage2Start = SystemClock.elapsedRealtime()
        val targetFolder = resolveDocument(targetFolderUri, cache) ?: return StorageWriteResult(ok = false, message = "target folder not found")
        val stage2Ms = SystemClock.elapsedRealtime() - stage2Start
        if (!targetFolder.isDirectory) {
            return StorageWriteResult(ok = false, message = "target is not a directory")
        }

        val requestedName = preferredName?.trim().orEmpty().ifBlank { source.name ?: "moved" }
        if (isExperimentalRoundSyncProvider(source.uri) || isExperimentalRoundSyncProvider(targetFolder.uri)) {
            // Round Sync VCP streams may report success before copy bytes have
            // reached the remote. Never emulate move with copy+delete here.
            if (source.uri.authority != targetFolder.uri.authority) {
                return StorageWriteResult(ok = false, message = "Cross-provider remote moves are disabled")
            }
            val sourceId = try {
                DocumentsContract.getDocumentId(source.uri)
            } catch (_: IllegalArgumentException) {
                ""
            }
            val parentId = sourceId.substringBeforeLast('/', "")
            val targetId = try {
                DocumentsContract.getDocumentId(targetFolder.uri)
            } catch (_: IllegalArgumentException) {
                ""
            }
            if (parentId.isBlank()) {
                return StorageWriteResult(ok = false, message = "Remote source parent is unknown; move cancelled")
            }
            if (parentId == targetId) {
                return if (source.name == requestedName) {
                    StorageWriteResult(ok = true, uri = source.uri.toString(), changed = false)
                } else {
                    rename(sourceUri, requestedName, targetFolderUri)
                }
            }
            val treeUri = resolver.persistedUriPermissions.firstOrNull {
                it.uri.authority == source.uri.authority && it.isReadPermission && it.isWritePermission
            }?.uri ?: return StorageWriteResult(ok = false, message = "No writable Round Sync tree grant")
            val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
            val moved = try {
                DocumentsContract.moveDocument(resolver, source.uri, parentDocumentUri, targetFolder.uri)
            } catch (error: Exception) {
                Log.w(TAG, "Round Sync provider refused native move", error)
                null
            } ?: return StorageWriteResult(
                ok = false,
                message = "Round Sync does not support safe native move. Original retained.",
            )
            if (requestedName != source.name) {
                val renamed = DocumentFile.fromSingleUri(appContext, moved)?.renameTo(requestedName) == true
                if (!renamed) {
                    return StorageWriteResult(ok = false, uri = moved.toString(),
                        message = "Moved, but provider rejected final rename. Check destination.")
                }
            }
            val resolvedUri = targetFolder.findFile(requestedName)?.uri?.toString() ?: moved.toString()
            return StorageWriteResult(ok = true, uri = resolvedUri, changed = true)
        }

        if (sourceParent == targetFolderUri) {
            val stage4Start = SystemClock.elapsedRealtime()
            val renamed = source.renameTo(requestedName)
            val stage4Ms = SystemClock.elapsedRealtime() - stage4Start
            if (renamed) {
                val renamedUri = resolveRenamedUri(targetFolder, requestedName)
                    ?: return StorageWriteResult(ok = false, message = "renamed document URI could not be resolved")
                val totalMs = SystemClock.elapsedRealtime() - totalStart
                Log.d(TIMING_TAG, "move stage1_resolve_source_ms=$stage1Ms")
                Log.d(TIMING_TAG, "move stage2_resolve_destination_ms=$stage2Ms")
                Log.d(TIMING_TAG, "move stage3_conflict_detection_ms=0")
                Log.d(TIMING_TAG, "move stage4_saf_call_ms=$stage4Ms")
                Log.d(TIMING_TAG, "move stage1to4_total_ms=$totalMs")
                return StorageWriteResult(ok = true, uri = renamedUri, changed = true)
            }
            val stage3Start = SystemClock.elapsedRealtime()
            val sibling = source.parentFile?.findFile(requestedName)
            val stage3Ms = SystemClock.elapsedRealtime() - stage3Start
            val totalMs = SystemClock.elapsedRealtime() - totalStart
            Log.d(TIMING_TAG, "move stage1_resolve_source_ms=$stage1Ms")
            Log.d(TIMING_TAG, "move stage2_resolve_destination_ms=$stage2Ms")
            Log.d(TIMING_TAG, "move stage3_conflict_detection_ms=$stage3Ms")
            Log.d(TIMING_TAG, "move stage4_saf_call_ms=$stage4Ms")
            Log.d(TIMING_TAG, "move stage1to4_total_ms=$totalMs")
            if (sibling != null && sibling.uri != source.uri) {
                return StorageWriteResult(ok = false, message = "name conflict in folder")
            }
            return StorageWriteResult(ok = false, message = "rename failed")
        }

        val stage4Start = SystemClock.elapsedRealtime()
        val copied = copyResolved(source, targetFolder, requestedName)
        val stage4Ms = SystemClock.elapsedRealtime() - stage4Start
        if (!copied.ok) {
            val totalMs = SystemClock.elapsedRealtime() - totalStart
            Log.d(TIMING_TAG, "move stage1_resolve_source_ms=$stage1Ms")
            Log.d(TIMING_TAG, "move stage2_resolve_destination_ms=$stage2Ms")
            Log.d(TIMING_TAG, "move stage3_conflict_detection_ms=0")
            Log.d(TIMING_TAG, "move stage4_saf_call_ms=$stage4Ms")
            Log.d(TIMING_TAG, "move stage1to4_total_ms=$totalMs")
            return copied
        }

        val copiedUri = copied.uri
        if (copiedUri.isNullOrBlank() || !verifyCopiedFile(source, copiedUri, cache)) {
            if (!copiedUri.isNullOrBlank()) {
                resolveDocument(copiedUri, cache)?.delete()
            }
            val totalMs = SystemClock.elapsedRealtime() - totalStart
            Log.d(TIMING_TAG, "move stage1_resolve_source_ms=$stage1Ms")
            Log.d(TIMING_TAG, "move stage2_resolve_destination_ms=$stage2Ms")
            Log.d(TIMING_TAG, "move stage3_conflict_detection_ms=0")
            Log.d(TIMING_TAG, "move stage4_saf_call_ms=$stage4Ms")
            Log.d(TIMING_TAG, "move stage1to4_total_ms=$totalMs")
            return StorageWriteResult(ok = false, message = "copied file verification failed")
        }

        val deleted = source.delete()
        val totalMs = SystemClock.elapsedRealtime() - totalStart
        Log.d(TIMING_TAG, "move stage1_resolve_source_ms=$stage1Ms")
        Log.d(TIMING_TAG, "move stage2_resolve_destination_ms=$stage2Ms")
        Log.d(TIMING_TAG, "move stage3_conflict_detection_ms=0")
        Log.d(TIMING_TAG, "move stage4_saf_call_ms=$stage4Ms")
        Log.d(TIMING_TAG, "move stage1to4_total_ms=$totalMs")
        if (!deleted) {
            return StorageWriteResult(ok = false, uri = copied.uri, message = "copied but failed to remove source")
        }
        return StorageWriteResult(ok = true, uri = copied.uri, changed = true)
    }

    private fun copyResolved(source: DocumentFile, targetFolder: DocumentFile, preferredName: String?): StorageWriteResult {
        val sourceName = source.name ?: source.uri.lastPathSegment ?: "copy"
        val requestedName = preferredName?.trim().orEmpty().ifBlank { sourceName }
        if (requestedName.contains('/') || requestedName.contains('\\')) {
            return StorageWriteResult(ok = false, message = "invalid destination filename")
        }
        // Some DocumentsProviders overwrite on createFile instead of refusing
        // an existing name. Reserve an unused name BEFORE creating the target.
        val finalName = resolveUniqueName(targetFolder, requestedName)
        val mime = resolver.getType(source.uri) ?: mimeFromName(finalName)
        val target = targetFolder.createFile(mime, finalName)
            ?: return StorageWriteResult(ok = false, message = "unable to create target file")

        return try {
            val bytesWritten = resolver.openInputStream(source.uri)?.use { input ->
                resolver.openOutputStream(target.uri, "w")?.use { output ->
                    input.copyTo(output).toLong()
                } ?: error("unable to open output stream")
            } ?: error("unable to open input stream")
            val expected = source.length()
            val actual = target.length()
            if (expected > 0 && bytesWritten != expected) {
                error("incomplete copy: $bytesWritten of $expected source bytes")
            }
            if (actual > 0 && actual != bytesWritten) {
                error("destination size mismatch: $actual versus $bytesWritten written bytes")
            }
            StorageWriteResult(ok = true, uri = target.uri.toString(), changed = true)
        } catch (error: Exception) {
            val cleanup = runCatching { target.delete() }.getOrDefault(false)
            StorageWriteResult(
                ok = false,
                uri = if (cleanup) null else target.uri.toString(),
                message = "copy failed: ${error.message ?: error.javaClass.simpleName}" +
                    if (cleanup) "" else "; partial target could not be removed",
            )
        }
    }

    private fun verifyCopiedFile(
        source: DocumentFile,
        copiedUri: String,
        cache: MutableMap<String, DocumentFile?>,
    ): Boolean {
        val copied = resolveDocument(copiedUri, cache) ?: return false
        if (!copied.exists()) {
            return false
        }

        val sourceSize = source.length()
        val copiedSize = copied.length()
        if (sourceSize > 0L && copiedSize > 0L && sourceSize != copiedSize) {
            return false
        }

        // A successful copy plus matching metadata size is not enough to
        // authorize deletion of an original illustration. Stream both
        // documents and compare a strong content digest before deleting.
        return try {
            fun sha256(uri: Uri): ByteArray {
                val digest = MessageDigest.getInstance("SHA-256")
                resolver.openInputStream(uri)?.use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count == -1) break
                        if (count > 0) digest.update(buffer, 0, count)
                    }
                } ?: error("cannot verify source or destination stream")
                return digest.digest()
            }
            sha256(source.uri).contentEquals(sha256(copied.uri))
        } catch (error: Exception) {
            Log.w(TAG, "Copy verification failed; original retained", error)
            false
        }
    }

    private fun isExperimentalRoundSyncProvider(uri: Uri): Boolean =
        uri.scheme == "content" && uri.authority?.lowercase()?.endsWith(".vcp") == true

    private fun resolveDocument(uri: String, cache: MutableMap<String, DocumentFile?>): DocumentFile? {
        cache[uri]?.let { return it }
        val parsed = Uri.parse(uri)
        val resolved = if (DocumentsContract.isTreeUri(parsed)) {
            DocumentFile.fromTreeUri(appContext, parsed)
        } else {
            DocumentFile.fromSingleUri(appContext, parsed) ?: DocumentFile.fromTreeUri(appContext, parsed)
        }
        cache[uri] = resolved
        return resolved
    }

    private fun resolveUniqueName(folder: DocumentFile, desiredName: String): String {
        val existing = folder.listFiles().mapNotNull { it.name?.lowercase() }.toHashSet()
        if (!existing.contains(desiredName.lowercase())) {
            return desiredName
        }
        val ext = File(desiredName).extension
        val base = if (ext.isBlank()) desiredName else desiredName.removeSuffix(".$ext")
        var index = 1
        while (true) {
            val candidate = if (ext.isBlank()) "$base ($index)" else "$base ($index).$ext"
            if (!existing.contains(candidate.lowercase())) {
                return candidate
            }
            index += 1
        }
    }

    private fun mimeFromName(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "tif", "tiff" -> "image/tiff"
            "heif", "heic" -> "image/heic"
            "avif" -> "image/avif"
            else -> "application/octet-stream"
        }
    }

    private fun hasPersistedTreePermission(uri: Uri): Boolean {
        val target = uri.normalizeScheme().toString()
        return resolver.persistedUriPermissions.any { permission ->
            if (!permission.isReadPermission || !permission.isWritePermission) {
                return@any false
            }
            if (permission.uri.normalizeScheme().toString() == target) {
                return@any true
            }
            containsDocument(permission.uri, uri)
        }
    }

    private fun containsDocument(treeUri: Uri, documentUri: Uri): Boolean {
        if (!DocumentsContract.isTreeUri(treeUri)) {
            return false
        }
        return try {
            val treeId = DocumentsContract.getTreeDocumentId(treeUri)
            val documentId = if (DocumentsContract.isTreeUri(documentUri)) {
                DocumentsContract.getTreeDocumentId(documentUri)
            } else {
                DocumentsContract.getDocumentId(documentUri)
            }
            documentId == treeId || documentId.startsWith("$treeId/")
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
