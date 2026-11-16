package com.ailm.android.runtime

data class LibraryQueryOptions(
    val query: String? = null,
    val imageId: Int? = null,
    val fullText: String? = null,
    val sortBy: String = "import_order",
    val sortDirection: String = "desc",
    val page: Int = 1,
    val pageSize: Int = 0,
    val collection: String? = null,
    val tags: List<String> = emptyList(),
    val minWidth: Int? = null,
    val minHeight: Int? = null,
    val fileFormat: String? = null,
    val orientation: String? = null,
    val folderQuery: String? = null,
    val includeHidden: Boolean = true,
    val missingOnly: Boolean = false,
    val taxonomyFilters: Map<String, String> = emptyMap(),
    // Durable Fusion automation receipt, not a filename/folder heuristic.
    val organizationStatus: String = "all",
    val includeInactive: Boolean = false,
)

data class ScanMetadata(
    val width: Int?,
    val height: Int?,
    val createdAtMs: Long?,
    val modifiedAtMs: Long?,
    val sizeBytes: Long?,
    val extension: String,
    val mimeType: String,
    val resolution: String?,
    val aspectRatio: Double?,
    val orientation: String?,
    val folderName: String,
    val relativePath: String,
    val indexedAtMs: Long,
)
