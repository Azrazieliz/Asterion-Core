package com.ailm.android.runtime

import java.io.InputStream

data class StorageNode(
    val uri: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
    val lastModifiedMs: Long?,
    val parentUri: String?,
)

data class StorageWriteResult(
    val ok: Boolean,
    val uri: String? = null,
    val message: String = "",
    val changed: Boolean = false,
)

interface StorageProvider {
    fun walkTree(rootUri: String): Sequence<StorageNode>
    fun listChildren(folderUri: String): List<StorageNode>
    fun findDirectory(folderUri: String, name: String): StorageNode? =
        listChildren(folderUri).firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) }
    fun openInputStream(uri: String): InputStream?
    fun exists(uri: String): Boolean
    fun rename(uri: String, newName: String, parentUri: String = ""): StorageWriteResult
    fun createFolder(parentUri: String, folderName: String): StorageWriteResult
    fun delete(uri: String): StorageWriteResult
    fun copy(sourceUri: String, targetFolderUri: String, preferredName: String? = null): StorageWriteResult
    fun move(sourceUri: String, targetFolderUri: String, preferredName: String? = null): StorageWriteResult
}
