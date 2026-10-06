package com.ailm.android.runtime

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.documentfile.provider.DocumentFile
import com.ailm.android.runtime.ai.AiTaskTypes
import com.ailm.android.runtime.ai.LocalAiManager
import com.ailm.android.workers.CharacterSheetIndexWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

object StandaloneRuntime {
    private const val RUNTIME_TRACE_TAG = "AilmTraceRuntime"
    private const val AUTOMATION_PREFS = "ailm_automation"
    private const val AUTOMATION_STATUS_KEY = "status"
    private const val AUTOMATION_TOTAL_KEY = "total"
    private const val AUTOMATION_PROCESSED_KEY = "processed"
    private const val AUTOMATION_FAILED_KEY = "failed"
    private const val AUTOMATION_REVIEW_KEY = "review"
    private const val AUTOMATION_SKIPPED_KEY = "skipped"
    private const val AUTOMATION_CURRENT_IMAGE_KEY = "current_image_id"
    private const val AUTOMATION_MESSAGE_KEY = "message"
    private const val AUTOMATION_UPDATED_AT_KEY = "updated_at_ms"
    private const val AUTOMATION_PAUSE_REQUESTED_KEY = "pause_requested"
    private const val AUTOMATION_LEGACY_REVIEW_REPAIR_V1_KEY = "legacy_review_repair_v1_done"

    private val imageExtensions = setOf(
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "tif", "tiff", "avif", "heif", "heic",
    )
    // Canonical per-image order. Each stage is completed before the next one is
    // scheduled, and the whole image finishes before automation advances to the
    // next library image. The execution planner selects the installed model for
    // each task (PaddleOCR, NSFW classifier, Qwen-VL, Nomic, etc.) automatically.
    private val autonomousImageStageCandidates = listOf(
        "ocr",
        "nsfw_classification",
        "embedding_generation",
        "aesthetic_scoring",
        // One Qwen-VL inference emits caption + canonical illustration tags +
        // normalized visual context + observable character attributes.
        "captioning",
    )

    private val stateMutex = Mutex()
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var initialized = false
    private lateinit var storageProvider: StorageProvider
    private lateinit var teraBoxStore: TeraBoxSecureStore
    private lateinit var teraBoxClient: TeraBoxClient
    private lateinit var database: LocalDatabase
    private lateinit var repository: LocalRepository
    private lateinit var knowledgeDatabase: KnowledgeDatabase
    private lateinit var resolutionStore: FusionResolutionStore
    private lateinit var localAiManager: LocalAiManager
    private val knowledgeRepository: KnowledgeRepository by lazy { KnowledgeRepository() }
    private var lastKnowledgeRebuildSignature: String? = null
    private var lastKnowledgeRebuildAtMs: Long = 0L
    private lateinit var aiWorkflowCoordinator: AiWorkflowCoordinator
    private lateinit var appContext: Context

    private var scanStatus: String = "idle"
    private var scanRoot: String? = null
    private var scanProgress: Double = 0.0
    private var scanDiscovered: Int = 0
    private var scanCurrentFile: String? = null
    private var scanError: String? = null
    private var scanPaused: Boolean = false
    private var scanCancelled: Boolean = false
    private var scanSkipped: Int = 0
    private var lastUndoOperations: List<FileOperationPlan> = emptyList()

    private data class FileOperationPlan(
        val action: String,
        val imageId: Int? = null,
        val sourceUri: String = "",
        val sourceName: String = "",
        val sourceFolderUri: String = "",
        val targetFolderUri: String = "",
        val targetName: String = "",
        val conflictMode: String = "rename",
        val canUndo: Boolean = true,
    )

    private class OperationConflictResolver(
        private val storageProvider: StorageProvider,
    ) {
        private val namesByFolder = mutableMapOf<String, MutableSet<String>>()

        fun hasConflict(folderUri: String, targetName: String): Boolean {
            val clean = targetName.trim()
            if (clean.isBlank() || folderUri.isBlank()) {
                return false
            }
            return folderNames(folderUri).contains(clean.lowercase())
        }

        fun reserve(folderUri: String, targetName: String) {
            val clean = targetName.trim()
            if (clean.isBlank() || folderUri.isBlank()) {
                return
            }
            folderNames(folderUri) += clean.lowercase()
        }

        private fun folderNames(folderUri: String): MutableSet<String> {
            return namesByFolder.getOrPut(folderUri) {
                storageProvider.listChildren(folderUri).map { it.name.lowercase() }.toMutableSet()
            }
        }
    }

    fun initialize(context: Context) {
        if (initialized) {
            return
        }
        runBlocking {
            stateMutex.withLock {
                if (!initialized) {
                    appContext = context.applicationContext
                    teraBoxStore = TeraBoxSecureStore(context.applicationContext)
                    teraBoxClient = TeraBoxClient(teraBoxStore)
                    storageProvider = RoutingStorageProvider(
                        saf = SafStorageProvider(context.applicationContext),
                        teraBox = TeraBoxStorageProvider(teraBoxClient),
                    )
                    database = LocalDatabase(context.applicationContext)
                    repository = LocalRepository(database)
                    repository.removeLegacyScanDiscoveryReviews()
                    knowledgeDatabase = KnowledgeDatabase(context.applicationContext)
                    resolutionStore = FusionResolutionStore(database)
                    aiWorkflowCoordinator = AiWorkflowCoordinator(
                        database = database,
                        repository = repository,
                        knowledge = knowledgeDatabase,
                        fusion = resolutionStore,
                    )
                    localAiManager = LocalAiManager(
                        context = context.applicationContext,
                        database = database,
                        scope = runtimeScope,
                    )
                    localAiManager.initialize()
                    cleanupLegacySettings()
                    repository.optimizeDatabase()
                    initialized = true
                }
            }
        }
    }

    fun healthStatus(): Map<String, Any> {
        ensureInitialized()
        return mapOf(
            "status" to "ok",
            "mode" to "android-standalone",
        )
    }

    fun libraryStatistics(): Map<String, Any> {
        ensureInitialized()
        val stats = repository.statistics()
        val status = runBlocking {
            stateMutex.withLock { scanStatus }
        }
        val scanRuns = repository.scanStatistics(limit = 20)
        val folders = repository.listFolders(includeDisabled = true)
        return mapOf(
            "total_images" to (stats["total_images"] ?: 0),
            "total_size_bytes" to (stats["total_size_bytes"] ?: 0L),
            "total_folders" to folders.size,
            "scan_status" to status,
            "recent_scans" to scanRuns,
        )
    }

    fun startScan(root: String): Map<String, Any> {
        ensureInitialized()
        val rootUri = root.trim()
        require(rootUri.isNotBlank()) { "scan root is required" }

        val existingStatus = runBlocking {
            stateMutex.withLock {
                if (scanStatus == "running" || scanStatus == "paused") {
                    snapshotStatus()
                } else {
                    scanStatus = "running"
                    scanRoot = rootUri
                    scanProgress = 0.0
                    scanDiscovered = 0
                    scanCurrentFile = null
                    scanError = null
                    scanPaused = false
                    scanCancelled = false
                    scanSkipped = 0
                    null
                }
            }
        }
        if (existingStatus != null) {
            return existingStatus
        }

        runtimeScope.launch {
            val scanStartedAt = System.currentTimeMillis()
            val scanId = repository.beginScanRun(rootUri, scanStartedAt)
            repository.registerFolder(rootUri, enabled = true)
            try {
                if (!storageProvider.exists(rootUri)) {
                    throw IllegalStateException("Selected SAF root is no longer available: $rootUri")
                }
                val scannedAt = scanStartedAt
                var importIndex = 0L
                var discoveredLocal = 0
                var skippedLocal = 0
                for (node in storageProvider.walkTree(rootUri)) {
                    val shouldContinue = awaitRunningState()
                    if (!shouldContinue) {
                        stateMutex.withLock {
                            scanStatus = "cancelled"
                            scanProgress = 0.0
                        }
                        repository.finishScanRun(
                            scanId = scanId,
                            folderUri = rootUri,
                            completedAtMs = System.currentTimeMillis(),
                            status = "cancelled",
                            discoveredCount = discoveredLocal,
                            skippedCount = skippedLocal,
                            errorMessage = "cancelled",
                        )
                        return@launch
                    }
                    if (node.isDirectory || !node.name.isImageName()) {
                        skippedLocal += 1
                        continue
                    }

                    importIndex += 1
                    val metadata = extractMetadata(node)
                    val upsert = repository.upsertImage(
                        node = node,
                        folderUri = rootUri,
                        scannedAtMs = scannedAt,
                        importOrder = scannedAt * 1_000_000L + importIndex,
                        metadata = metadata,
                        metadataText = buildMetadataText(node, metadata),
                    )
                    discoveredLocal += 1

                    stateMutex.withLock {
                        scanDiscovered += 1
                        scanSkipped = skippedLocal
                        scanCurrentFile = node.uri
                        scanProgress = if (scanDiscovered < 1) 0.0 else minOf(99.0, 5.0 + (scanDiscovered * 0.5))
                    }
                }

                repository.markFolderImagesInactiveBefore(rootUri, scannedAt)
                // images_ai/images_au/images_ad keep FTS synchronized incrementally.
                // Rebuilding the entire FTS corpus on every scan does not scale
                // to six-figure libraries.
                repository.optimizeDatabase()

                stateMutex.withLock {
                    scanStatus = "completed"
                    scanProgress = 100.0
                }
                repository.finishScanRun(
                    scanId = scanId,
                    folderUri = rootUri,
                    completedAtMs = System.currentTimeMillis(),
                    status = "completed",
                    discoveredCount = discoveredLocal,
                    skippedCount = skippedLocal,
                    errorMessage = null,
                )
            } catch (t: Throwable) {
                stateMutex.withLock {
                    scanStatus = "failed"
                    scanError = t.message ?: t.javaClass.simpleName
                    scanProgress = 0.0
                }
                repository.finishScanRun(
                    scanId = scanId,
                    folderUri = rootUri,
                    completedAtMs = System.currentTimeMillis(),
                    status = "failed",
                    discoveredCount = scanDiscovered,
                    skippedCount = scanSkipped,
                    errorMessage = t.message ?: t.javaClass.simpleName,
                )
            }
        }

        return runBlocking {
            stateMutex.withLock { snapshotStatus() }
        }
    }

    fun scanStatus(): Map<String, Any> {
        ensureInitialized()
        return runBlocking {
            stateMutex.withLock { snapshotStatus() }
        }
    }

    fun pauseScan(): Boolean {
        ensureInitialized()
        return runBlocking {
            stateMutex.withLock {
                if (scanStatus != "running") {
                    return@withLock false
                }
                scanPaused = true
                scanStatus = "paused"
                true
            }
        }
    }

    fun resumeScan(): Boolean {
        ensureInitialized()
        return runBlocking {
            stateMutex.withLock {
                if (scanStatus != "paused") {
                    return@withLock false
                }
                scanPaused = false
                scanStatus = "running"
                true
            }
        }
    }

    fun cancelScan(): Boolean {
        ensureInitialized()
        return runBlocking {
            stateMutex.withLock {
                if (scanStatus !in setOf("running", "paused")) {
                    return@withLock false
                }
                scanCancelled = true
                scanPaused = false
                scanStatus = "cancelled"
                true
            }
        }
    }

    fun getCollections(query: String? = null, page: Int = 1, pageSize: Int = 50): List<Map<String, Any>> {
        ensureInitialized()
        val items = repository.collections(page = page, pageSize = pageSize)
        if (query.isNullOrBlank()) {
            return items
        }
        val term = query.trim().lowercase()
        return items.filter { (it["name"]?.toString() ?: "").lowercase().contains(term) }
    }

    fun getLibraryImages(query: String? = null, page: Int = 1, pageSize: Int = 200): List<Map<String, Any>> {
        ensureInitialized()
        return repository.searchImages(
            LibraryQueryOptions(
                query = query,
                page = page,
                pageSize = pageSize,
                sortBy = "import_order",
                sortDirection = "desc",
            ),
        )
    }

    fun getTags(): List<String> {
        ensureInitialized()
        return repository.listStoredTags(limit = 5000)
    }

    fun searchByFilename(query: String, page: Int = 1, pageSize: Int = 200): List<Map<String, Any>> {
        ensureInitialized()
        return repository.searchImages(
            LibraryQueryOptions(
                query = query,
                page = page.coerceAtLeast(1),
                pageSize = pageSize.coerceIn(1, 500),
                sortBy = "filename",
                sortDirection = "asc",
            ),
        )
    }

    fun searchByImageId(imageId: Int): Map<String, Any>? {
        ensureInitialized()
        val result = repository.searchByImageId(imageId)
        Log.d(RUNTIME_TRACE_TAG, "Runtime returned object: imageId=$imageId ${imageSummary(result)}")
        return result
    }

    fun semanticSearch(queryVector: List<Float>): List<Map<String, Any>> {
        return semanticSearch(queryVector, emptyMap())
    }

    fun semanticSearch(queryVector: List<Float>, payload: Map<String, Any> = emptyMap()): List<Map<String, Any>> {
        ensureInitialized()
        val candidateLimit = payload["candidate_limit"].toIntOrNullValue()?.coerceIn(1, 2_000) ?: 600
        val baseOptions = payload.toQueryOptions().copy(
            query = null,
            fullText = null,
            sortBy = "import_order",
            sortDirection = "desc",
            page = 1,
            pageSize = candidateLimit,
        )
        val candidateRows = repository.searchImages(baseOptions)
        if (candidateRows.isEmpty()) {
            return emptyList()
        }

        val topK = payload["top_k"].toIntOrNullValue()?.coerceAtLeast(1) ?: minOf(200, candidateRows.size)
        val semanticPayload = linkedMapOf<String, Any>(
            "query" to payload["query"]?.toString()?.trim().orEmpty(),
            "top_k" to topK,
            "candidates" to candidateRows.mapNotNull { row -> row.toSemanticCandidatePayloadOrNull() },
        )
        if (queryVector.isNotEmpty()) {
            semanticPayload["query_embedding"] = queryVector.map { it.toDouble() }
        }

        val response = localAiManager.semanticSearch(semanticPayload)
        val matches = extractSemanticMatches(response)
        if (matches.isEmpty()) {
            return candidateRows.take(topK)
        }

        val rowsById = candidateRows.mapNotNull { row ->
            val imageId = row["image_id"].toIntOrNullValue() ?: return@mapNotNull null
            imageId to row
        }.toMap()

        val ranked = mutableListOf<Map<String, Any>>()
        matches.forEachIndexed { index, match ->
            val imageId = match["image_id"].toIntOrNullValue() ?: return@forEachIndexed
            val source = rowsById[imageId] ?: repository.searchByImageId(imageId) ?: return@forEachIndexed
            val enriched = source.toMutableMap()
            match["score"].toDoubleOrNullValue()?.let { score ->
                enriched["semantic_score"] = score
            }
            enriched["semantic_rank"] = index + 1
            ranked += enriched
        }

        return if (ranked.isEmpty()) candidateRows.take(topK) else ranked
    }

    fun advancedSearch(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val options = payload.toQueryOptions()
        val items = repository.searchImages(options)
        val total = repository.countImages(options)
        return mapOf(
            "ok" to true,
            "items" to items,
            "count" to total,
            "sort_by" to options.sortBy,
            "sort_direction" to options.sortDirection,
        )
    }

    fun getReviewQueue(): List<Map<String, Any>> {
        ensureInitialized()
        return repository.reviewQueue()
    }

    fun updateReview(itemId: String, action: String, payload: Map<String, Any> = emptyMap()): Boolean {
        ensureInitialized()
        val normalizedItem = itemId.trim()
        val normalizedAction = action.trim().lowercase()
        if (normalizedItem.isBlank() || normalizedAction.isBlank()) return false
        if (normalizedAction == "undo") return repository.undoLastReviewUpdate()

        if (normalizedAction in setOf("approve", "correct")) {
            val reviewId = normalizedItem.toLongOrNull() ?: return false
            val review = resolutionStore.getReviewItem(reviewId) ?: return false
            val reviewType = review["review_type"]?.toString().orEmpty()
            if (reviewType == "character_resolution") {
                val rawCorrection = payload["correction"]
                val correction = when (rawCorrection) {
                    is Map<*, *> -> rawCorrection.entries.mapNotNull { (key, value) ->
                        key?.toString()?.let { text -> value?.let { text to it } }
                    }.toMap()
                    else -> payload.filterKeys { it != "reason" }
                }
                if (correction.isEmpty()) return false

                val imageId = (review["image_id"] as? Number)?.toInt() ?: return false
                val workflow = aiWorkflowCoordinator.applyReviewCorrection(imageId, correction)
                if (workflow["accepted"] != true) return false

                val organization = organizeAutonomousImage(imageId, workflow)
                val organized = organization["ok"] == true &&
                    organization["status"]?.toString() !in setOf("file_operation_failed", "database_update_failed", "folder_failed")
                if (!organized) {
                    resolutionStore.queueReview(
                        imageId = imageId,
                        reviewType = "organization_failure",
                        reason = organization["message"]?.toString().orEmpty().ifBlank { "Corrected identity could not be organized." },
                        payload = mapOf("workflow" to workflow, "organization" to organization),
                    )
                    resolutionStore.markAutomationState(
                        imageId = imageId,
                        state = "retry_required",
                        pipelineComplete = true,
                        organizationComplete = false,
                        needsReview = true,
                        lastError = organization["message"]?.toString().orEmpty(),
                    )
                    return false
                }

                resolutionStore.completeReview(reviewId, "approved", correction)
                resolutionStore.markAutomationState(
                    imageId = imageId,
                    state = "complete",
                    pipelineComplete = true,
                    organizationComplete = true,
                    needsReview = false,
                )
                repository.rebuildSearchIndex()
                return true
            }
        }

        val reason = payload["reason"]?.toString()
        return repository.updateReview(normalizedItem, normalizedAction, reason)
    }

    fun knowledgeSummary(): Map<String, Any> {
        ensureInitialized()
        return knowledgeDatabase.summary()
    }

    fun listKnowledgePacks(): List<Map<String, Any>> {
        ensureInitialized()
        val directory = knowledgePackDirectory()
        return directory.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            ?.map(::describeKnowledgePack)
            ?: emptyList()
    }

    fun importKnowledgePackDocuments(uris: List<Uri>): List<Map<String, Any>> {
        ensureInitialized()
        if (uris.isEmpty()) return emptyList()

        val referenceResults = mutableListOf<Map<String, Any>>()
        val rawReferenceDocuments = linkedMapOf<String, String>()
        val ordinaryUris = mutableListOf<Uri>()

        uris.distinct().forEach { uri ->
            val filename = safeKnowledgePackFileName(uri)

            if (isZipKnowledgeDocument(uri, filename)) {
                referenceResults += mapOf(
                    "ok" to false,
                    "message" to "ZIP Knowledge import is disabled. Select the JSON files individually or select several JSON files together.",
                    "filename" to filename,
                )
                return@forEach
            }

            val raw = if (isJsonKnowledgeDocument(uri, filename)) {
                readKnowledgeDocumentText(uri)
            } else {
                null
            }
            if (raw != null && ReferenceKnowledgeParser.looksLikeReferenceDocument(filename, raw)) {
                rawReferenceDocuments[filename] = raw
            } else {
                ordinaryUris += uri
            }
        }

        if (rawReferenceDocuments.isNotEmpty()) {
            val sourceName = if (rawReferenceDocuments.size == 1) {
                rawReferenceDocuments.keys.first()
            } else {
                "selected-reference-jsons"
            }
            val result = ReferenceKnowledgeImporter(knowledgeDatabase)
                .importDocuments(rawReferenceDocuments, sourceName)
            referenceResults += result
            if (result["ok"] == true) {
                rawReferenceDocuments.forEach { (filename, raw) ->
                    persistReferenceKnowledgeArtifact(filename, raw)
                }
                resolutionStore.resetWaitingForKnowledge()
                repository.rebuildSearchIndex()
            }
        }

        if (ordinaryUris.isEmpty()) return referenceResults

        val directory = knowledgePackDirectory()
        val temporaryPacks = ordinaryUris.map { uri ->
            copyKnowledgePackToTemporaryFile(uri, directory)?.let { temporary ->
                uri to temporary
            } ?: return referenceResults + listOf(
                mapOf(
                    "ok" to false,
                    "message" to "Unable to read selected Knowledge Pack.",
                    "filename" to safeKnowledgePackFileName(uri),
                ),
            )
        }

        try {
            val incoming = temporaryPacks.map { (uri, temporary) ->
                validateKnowledgePack(temporary, safeKnowledgePackFileName(uri))
            }
            val installed = directory.listFiles()
                ?.filter { it.isFile && !it.name.startsWith(".") }
                ?.mapNotNull { file -> describeKnowledgePackOrNull(file) }
                ?: emptyList()

            val placed = mutableListOf<KnowledgePackDescriptor>()
            val copied = mutableListOf<File>()
            val results = mutableListOf<Map<String, Any>>()

            incoming.forEachIndexed { index, pack ->
                if (!pack.ok) {
                    results += mapOf(
                        "ok" to false,
                        "message" to (pack.error ?: "Knowledge Pack validation failed."),
                        "filename" to pack.filename,
                    )
                    return@forEachIndexed
                }

                duplicateKnowledgePack(pack, installed + placed)?.let { reason ->
                    results += mapOf(
                        "ok" to false,
                        "message" to reason,
                        "filename" to pack.filename,
                    )
                    return@forEachIndexed
                }

                val destination = availableKnowledgePackFile(directory, pack.filename)
                try {
                    temporaryPacks[index].second.copyTo(destination, overwrite = false)
                } catch (_: Throwable) {
                    results += mapOf(
                        "ok" to false,
                        "message" to "Unable to install Knowledge Pack.",
                        "filename" to pack.filename,
                    )
                    return@forEachIndexed
                }

                val verified = validateKnowledgePack(destination, destination.name)
                if (!verified.ok || verified.contentHash != pack.contentHash) {
                    destination.delete()
                    results += mapOf(
                        "ok" to false,
                        "message" to (verified.error ?: "Unable to verify copied Knowledge Pack."),
                        "filename" to destination.name,
                    )
                    return@forEachIndexed
                }

                copied += destination
                placed += verified
                results += mapOf("ok" to true, "pack" to describeKnowledgePack(destination))
            }

            if (copied.isNotEmpty()) {
                val rebuildResult = runCatching { rebuildFusionFromInstalledKnowledgePacks() }
                if (rebuildResult.isFailure) {
                    val warning = rebuildResult.exceptionOrNull()?.message ?: "Fusion rebuild failed after install."
                    results.replaceAll { result -> result + mapOf("warning" to warning) }
                }
            }

            return referenceResults + results
        } finally {
            temporaryPacks.forEach { (_, temporary) -> temporary.delete() }
        }
    }

    fun previewKnowledgePack(filename: String): Map<String, Any> {
        ensureInitialized()
        val pack = findKnowledgePack(filename) ?: return mapOf(
            "ok" to false,
            "message" to "Knowledge Pack not found.",
        )
        return mapOf("ok" to true, "pack" to describeKnowledgePack(pack))
    }

    fun previewKnowledgePackDocument(raw: String, filename: String): Map<String, Any> {
        ensureInitialized()
        return runCatching {
            val parsed = knowledgeRepository.parseAndValidate(raw)
            mapOf(
                "ok" to true,
                "message" to "Knowledge pack preview succeeded.",
                "filename" to filename,
                "pack_id" to parsed.packId,
                "pack_name" to parsed.packName.ifBlank { parsed.packId },
                "version" to parsed.version,
                "knowledge_type" to parsed.knowledgeType,
                "author" to parsed.author,
                "creation_date" to parsed.creationDate,
                "description" to parsed.description,
                "dependencies" to parsed.dependencies,
                "supported_categories" to parsed.supportedCategories,
                "entries" to parsed.entries.size,
            )
        }.getOrElse { error ->
            mapOf(
                "ok" to false,
                "message" to (error.message ?: "Knowledge Pack validation failed."),
                "filename" to filename,
            )
        }
    }

    fun validateKnowledgePackDocument(raw: String, filename: String): Map<String, Any> {
        ensureInitialized()
        return runCatching {
            val parsed = knowledgeRepository.parseAndValidate(raw)
            mapOf(
                "ok" to true,
                "message" to "Knowledge pack validation succeeded.",
                "filename" to filename,
                "pack_id" to parsed.packId,
                "pack_name" to parsed.packName.ifBlank { parsed.packId },
                "version" to parsed.version,
                "knowledge_type" to parsed.knowledgeType,
                "entries" to parsed.entries.size,
                "supported_categories" to parsed.supportedCategories,
                "dependencies" to parsed.dependencies,
            )
        }.getOrElse { error ->
            mapOf(
                "ok" to false,
                "message" to (error.message ?: "Knowledge Pack validation failed."),
                "filename" to filename,
            )
        }
    }

    fun replaceKnowledgePackDocument(filename: String, uri: Uri): Map<String, Any> {
        ensureInitialized()
        val target = findKnowledgePack(filename) ?: return mapOf(
            "ok" to false,
            "message" to "Knowledge Pack not found.",
        )
        val directory = knowledgePackDirectory()
        val temporary = copyKnowledgePackToTemporaryFile(uri, directory)
            ?: return mapOf("ok" to false, "message" to "Unable to read selected Knowledge Pack.")
        try {
            val incoming = validateKnowledgePack(temporary, safeKnowledgePackFileName(uri))
            if (!incoming.ok) {
                return mapOf("ok" to false, "message" to (incoming.error ?: "Knowledge Pack validation failed."))
            }
            val installed = directory.listFiles()
                ?.filter { it.isFile && it != target && !it.name.startsWith(".") }
                ?.mapNotNull(::describeKnowledgePackOrNull)
                ?: emptyList()
            duplicateKnowledgePack(incoming, installed)?.let { reason ->
                return mapOf("ok" to false, "message" to reason)
            }

            val destination = availableKnowledgePackFile(directory, incoming.filename)
            val copied = runCatching {
                temporary.copyTo(destination, overwrite = false)
            }.isSuccess
            if (!copied) {
                return mapOf("ok" to false, "message" to "Unable to copy replacement Knowledge Pack.")
            }
            val verified = validateKnowledgePack(destination, destination.name)
            if (!verified.ok || verified.contentHash != incoming.contentHash) {
                destination.delete()
                return mapOf("ok" to false, "message" to (verified.error ?: "Unable to verify copied Knowledge Pack."))
            }
            refreshKnowledgePackReferences()
            if (!target.delete()) {
                destination.delete()
                return mapOf("ok" to false, "message" to "Unable to remove replaced Knowledge Pack.")
            }
            return mapOf("ok" to true, "pack" to describeKnowledgePack(destination))
        } finally {
            if (temporary.exists()) {
                temporary.delete()
            }
        }
    }

    fun removeKnowledgePack(filename: String): Boolean {
        ensureInitialized()
        return findKnowledgePack(filename)?.delete() == true
    }

    fun listDownloads(): List<Map<String, Any>> {
        ensureInitialized()
        val dir = File(appContext.filesDir, "downloads")
        return listLocalArtifacts(dir, kind = "download")
    }

    fun listPlugins(): List<Map<String, Any>> {
        ensureInitialized()
        val dir = File(appContext.filesDir, "plugins")
        return listLocalArtifacts(dir, kind = "plugin")
    }

    fun localAiOverview(): Map<String, Any> {
        ensureInitialized()
        return localAiManager.overview()
    }

    fun localAiExecutionChain(): Map<String, Any> {
        ensureInitialized()
        return localAiManager.executionChain()
    }

    fun detectAiHardwareProfile(): Map<String, Any> {
        ensureInitialized()
        return localAiManager.detectHardwareProfile()
    }

    fun latestAiHardwareProfile(): Map<String, Any> {
        ensureInitialized()
        return localAiManager.latestHardwareProfile()
    }

    fun listAiBackends(): List<Map<String, Any>> {
        ensureInitialized()
        return localAiManager.listBackends()
    }

    fun registerAvailableAiModel(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        return localAiManager.registerAvailableModel(payload)
    }

    fun listAvailableAiModels(): List<Map<String, Any>> {
        ensureInitialized()
        return localAiManager.listAvailableModels()
    }

    fun listInstalledAiModels(): List<Map<String, Any>> {
        ensureInitialized()
        return localAiManager.listInstalledModels()
    }

    fun importLocalAiModel(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        return localAiManager.importLocalModel(payload)
    }

    fun registerAiModelDownload(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        return localAiManager.registerModelDownload(payload)
    }

    fun downloadAiModel(installId: String): Map<String, Any> {
        ensureInitialized()
        return localAiManager.downloadRegisteredModel(installId)
    }

    fun verifyInstalledAiModel(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        return localAiManager.verifyInstalledModel(payload)
    }

    fun activateInstalledAiModel(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        return localAiManager.activateInstalledModel(payload)
    }

    fun removeAiModel(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        return localAiManager.removeModel(payload)
    }

    fun detectAiModelUpdates(): List<Map<String, Any>> {
        ensureInitialized()
        return localAiManager.detectModelUpdates()
    }

    fun enqueueAiTask(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        return localAiManager.enqueueTask(payload)
    }

    fun cancelAiTask(taskId: String): Boolean {
        ensureInitialized()
        return localAiManager.cancelTask(taskId.trim())
    }

    fun pauseAiTask(taskId: String): Boolean {
        ensureInitialized()
        return localAiManager.pauseTask(taskId.trim())
    }

    fun resumeAiTask(taskId: String): Boolean {
        ensureInitialized()
        return localAiManager.resumeTask(taskId.trim())
    }

    fun retryAiTask(taskId: String): Boolean {
        ensureInitialized()
        return localAiManager.retryTask(taskId.trim())
    }

    fun listAiTasks(limit: Int = 200): List<Map<String, Any>> {
        ensureInitialized()
        return localAiManager.listTasks(limit)
    }

    fun resumeAiQueue(): Boolean {
        ensureInitialized()
        localAiManager.resumeQueue()
        return true
    }

    fun pauseAiQueue(): Boolean {
        ensureInitialized()
        localAiManager.pauseQueue()
        return true
    }

    fun listAiInstallRuns(limit: Int = 100): List<Map<String, Any>> {
        ensureInitialized()
        return localAiManager.listInstallRuns(limit)
    }

    fun listAiExecutionSessions(limit: Int = 200): List<Map<String, Any>> {
        ensureInitialized()
        return localAiManager.listExecutionSessions(limit)
    }

    fun listAiExecutionEvents(sessionId: String, limit: Int = 500): List<Map<String, Any>> {
        ensureInitialized()
        return localAiManager.listExecutionEvents(sessionId.trim(), limit)
    }

    fun listAiRuntimeHealthSnapshots(limit: Int = 200): List<Map<String, Any>> {
        ensureInitialized()
        return localAiManager.listRuntimeHealthSnapshots(limit)
    }

    fun aiSettings(): Map<String, Any> {
        ensureInitialized()
        return localAiManager.getSettings()
    }

    fun updateAiSettings(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        return localAiManager.updateSettings(payload)
    }

    fun listAiPlugins(enabledOnly: Boolean? = null): List<Map<String, Any>> {
        ensureInitialized()
        return localAiManager.listPlugins(enabledOnly)
    }

    fun registerAiPlugin(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        return localAiManager.registerPlugin(payload)
    }

    fun listAiCapabilities(providerId: String = ""): List<Map<String, Any>> {
        ensureInitialized()
        return localAiManager.listCapabilities(providerId)
    }

    fun registerAiCapability(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        return localAiManager.registerCapability(payload)
    }

    fun listAiCacheEntries(limit: Int = 200): List<Map<String, Any>> {
        ensureInitialized()
        return localAiManager.listCacheEntries(limit)
    }

    fun upsertAiCacheEntry(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        return localAiManager.upsertCacheEntry(payload)
    }

    fun pruneAiCache(): Map<String, Any> {
        ensureInitialized()
        return localAiManager.pruneCache()
    }

    fun validateLocalAiInfrastructure(): Map<String, Any> {
        ensureInitialized()
        return localAiManager.validateInfrastructure()
    }

    fun runAiPipeline(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val prepared = prepareAiPipelinePayload(payload)
        val response = localAiManager.runPipeline(prepared)
        applyPipelineSideEffects(prepared, response)
        return response
    }

    fun runAiBatchPipeline(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val rawItems = (payload["items"] as? List<*>)
            ?.mapNotNull { (it as? Map<*, *>)?.toStringAnyMap() }
            ?: emptyList()
        if (rawItems.isEmpty()) {
            return mapOf("ok" to false, "status" to "invalid", "message" to "items are required")
        }

        val preparedItems = rawItems.map { prepareAiPipelinePayload(it) }
        val batchPayload = linkedMapOf<String, Any>()
        batchPayload.putAll(payload)
        batchPayload["items"] = preparedItems

        val response = localAiManager.runBatchPipeline(batchPayload)
        val responseItems = (response["items"] as? List<*>)
            ?.mapNotNull { (it as? Map<*, *>)?.toStringAnyMap() }
            ?: emptyList()
        preparedItems.forEachIndexed { index, prepared ->
            val item = responseItems.getOrNull(index) ?: return@forEachIndexed
            val itemResult = (item["result"] as? Map<*, *>)?.toStringAnyMap() ?: return@forEachIndexed
            applyPipelineSideEffects(prepared, itemResult)
        }
        return response
    }

    fun runMultiStageAiPipeline(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val prepared = prepareAiPipelinePayload(payload)
        val response = localAiManager.runMultiStagePipeline(prepared)
        applyPipelineSideEffects(prepared, response)
        return response
    }

    fun runAutonomousImageWorkflow(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val imageId = payload["image_id"].toIntOrNullValue()
            ?: return mapOf("ok" to false, "status" to "invalid", "message" to "image_id is required")

        automationImagePreflight(imageId)?.let { terminalResult ->
            return terminalResult
        }

        val readiness = automationReadiness()
        if (readiness["ready"] != true) {
            return mapOf(
                "ok" to false,
                "status" to "incompatible",
                "message" to readiness["message"]?.toString().orEmpty().ifBlank { "Automation is not ready." },
                "missing_stages" to (readiness["missing_stages"] ?: emptyList<String>()),
            )
        }

        val earlyStages = resolvedAutonomousImageStages().filter {
            it in setOf("ocr", "nsfw_classification", "embedding_generation", "aesthetic_scoring")
        }
        val preparedBase = prepareAiPipelinePayload(
            payload + mapOf(
                "task_type" to "autonomous_image_workflow",
                "character_taxonomy_context" to knowledgeDatabase.taxonomyPromptContext(),
                "illustration_taxonomy_context" to knowledgeDatabase.illustrationTaxonomyPromptContext(),
            ),
        )

        val earlyResponse = localAiManager.runMultiStagePipeline(
            preparedBase + mapOf(
                "stages" to earlyStages,
                "continue_on_stage_error" to true,
            ),
        )
        val earlyOutputs = (earlyResponse["stage_outputs"] as? Map<*, *>)
            ?.entries
            ?.mapNotNull { (key, value) ->
                val stage = key?.toString()?.trim().orEmpty()
                val output = (value as? Map<*, *>)?.entries
                    ?.mapNotNull { (nestedKey, nestedValue) ->
                        nestedKey?.toString()?.let { text -> nestedValue?.let { text to it } }
                    }
                    ?.toMap()
                    .orEmpty()
                if (stage.isBlank()) null else stage to output
            }
            ?.toMap()
            .orEmpty()

        val ocrText = earlyOutputs["ocr"].orEmpty().resultMap()["text"]?.toString().orEmpty()
        val nsfwResult = earlyOutputs["nsfw_classification"].orEmpty().resultMap()

        val qwenModel = resolvedQwenSemanticModel()
        if (qwenModel == null) {
            cleanupPreparedRemoteImage(preparedBase)
            return mapOf(
                "ok" to false,
                "status" to "incompatible",
                "message" to "No execution-ready Qwen-VL model is installed for the semantic image pass.",
            )
        }

        val qwenResponse = localAiManager.runPipeline(
            preparedBase + mapOf(
                "task_type" to "captioning",
                "stages" to listOf("captioning"),
                "model_id" to qwenModel.first,
                "version" to qwenModel.second,
                "asterion_semantic_bundle" to true,
                "ocr_text" to ocrText,
                "nsfw_result" to nsfwResult,
                "max_new_tokens" to 1024,
                "continue_on_stage_error" to false,
            ),
        )

        val qwenRaw = (qwenResponse["raw_result"] as? Map<*, *>)
            ?.entries
            ?.mapNotNull { (key, value) -> key?.toString()?.let { text -> value?.let { text to it } } }
            ?.toMap()
            .orEmpty()
        val qwenResult = qwenRaw.resultMap()

        val syntheticOutputs = linkedMapOf<String, Map<String, Any>>()
        syntheticOutputs.putAll(earlyOutputs)
        if (qwenResponse["ok"] == true) {
            val caption = qwenResult["caption"]?.toString().orEmpty()
            val normalized = qwenResult["normalized_context"]?.toString().orEmpty()
            val tags = (qwenResult["tags"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
            val routing = (qwenResult["routing"] as? Map<*, *>)
                ?.entries
                ?.mapNotNull { (key, value) -> key?.toString()?.let { text -> value?.let { text to it } } }
                ?.toMap()
                .orEmpty()
            val subjects = (qwenResult["subjects"] as? List<*>)?.mapNotNull { subject ->
                (subject as? Map<*, *>)?.entries
                    ?.mapNotNull { (key, value) -> key?.toString()?.let { text -> value?.let { text to it } } }
                    ?.toMap()
            }.orEmpty()

            syntheticOutputs["captioning"] = mapOf(
                "result" to mapOf("caption" to caption),
                "message" to "Qwen-VL bundled semantic pass",
            )
            syntheticOutputs["tag_prediction"] = mapOf(
                "result" to mapOf("tags" to tags),
                "message" to "Qwen-VL bundled semantic pass",
            )
            syntheticOutputs["normalization"] = mapOf(
                "result" to mapOf("text" to normalized),
                "message" to "Qwen-VL bundled semantic pass",
            )
            syntheticOutputs["character_recognition"] = mapOf(
                "result" to mapOf(
                    "subjects" to subjects,
                    "routing" to routing,
                    "raw_observation_json" to (qwenResult["raw_semantic_json"]?.toString().orEmpty()),
                ),
                "message" to "Qwen-VL bundled semantic pass",
            )
        }

        val earlyFailed = (earlyResponse["failed_stages"] as? List<*>)
            ?.mapNotNull { (it as? Map<*, *>)?.entries
                ?.mapNotNull { (key, value) -> key?.toString()?.let { text -> value?.let { text to it } } }
                ?.toMap()
            }
            .orEmpty()
        val qwenParseError = qwenResult["parse_error"]?.toString().orEmpty()
        val qwenSucceeded = qwenResponse["ok"] == true && qwenParseError.isBlank()
        val qwenFailed = if (qwenSucceeded) {
            emptyList()
        } else {
            listOf(
                mapOf(
                    "stage_type" to "qwen_semantic_bundle",
                    "status" to if (qwenParseError.isNotBlank()) "invalid_output" else qwenResponse["status"].toString(),
                    "message" to qwenParseError.ifBlank { qwenResponse["message"]?.toString().orEmpty() },
                ),
            )
        }
        val failedStages = earlyFailed + qwenFailed

        val combinedResponse = mapOf(
            "ok" to (failedStages.isEmpty()),
            "status" to if (failedStages.isEmpty()) "succeeded" else "partial",
            "pipeline_type" to "autonomous_image_workflow",
            "stage_outputs" to syntheticOutputs,
            "failed_stages" to failedStages,
            "stages" to (
                ((earlyResponse["stages"] as? List<*>) ?: emptyList<Any>()) +
                    listOf(
                        mapOf(
                            "stage_type" to "qwen_semantic_bundle",
                            "model_id" to qwenModel.first,
                            "model_version" to qwenModel.second,
                            "status" to if (qwenSucceeded) "succeeded" else if (qwenParseError.isNotBlank()) "invalid_output" else qwenResponse["status"].toString(),
                            "message" to qwenResponse["message"]?.toString().orEmpty(),
                        ),
                    )
                ),
            "result" to qwenResult,
            "message" to when {
                failedStages.isEmpty() -> "Autonomous image analysis completed."
                else -> "Autonomous image analysis completed with one or more failed stages."
            },
        )

        val workflow = aiWorkflowCoordinator.applyImageWorkflow(imageId, combinedResponse)

        val hasCharacterKnowledge = knowledgeDatabase.hasCharacters()
        val terminalContentRoute = workflow["content_route_terminal"] == true
        val organization = when {
            terminalContentRoute -> organizeAutonomousImage(imageId, workflow)
            hasCharacterKnowledge -> organizeAutonomousImage(imageId, workflow)
            else -> mapOf(
                "ok" to true,
                "status" to "waiting_for_knowledge",
                "message" to "Analysis completed; character-based rename/move is deferred until Character Knowledge is available.",
            )
        }
        val needsReview = workflow["queued_for_review"] == true
        val organizationStatus = organization["status"]?.toString().orEmpty()
        val organizationFailed = organization["ok"] == false &&
            organizationStatus !in setOf("skipped", "unchanged", "waiting_for_knowledge")
        val completedStages = syntheticOutputs.size
        val stageFailures = failedStages.size
        val pipelineFailed = failedStages.isNotEmpty()

        val state = when {
            pipelineFailed -> "retry_required"
            organizationFailed -> "retry_required"
            terminalContentRoute -> "complete"
            !hasCharacterKnowledge -> "waiting_for_knowledge"
            needsReview -> "review_pending"
            else -> "complete"
        }
        if (organizationFailed) {
            resolutionStore.queueReview(
                imageId = imageId,
                reviewType = "organization_failure",
                reason = organization["message"]?.toString().orEmpty().ifBlank { "File organization failed." },
                payload = mapOf("organization" to organization, "workflow" to workflow),
            )
        }
        resolutionStore.markAutomationState(
            imageId = imageId,
            state = state,
            pipelineComplete = !pipelineFailed,
            organizationComplete = organizationStatus in setOf(
                "organized",
                "already_organized",
                "unchanged",
                "routed_content",
                "already_routed",
            ),
            needsReview = needsReview || organizationFailed || pipelineFailed,
            lastError = when {
                organizationFailed -> organization["message"]?.toString().orEmpty()
                pipelineFailed -> combinedResponse["message"]?.toString().orEmpty()
                else -> ""
            },
        )
        if (state == "complete") {
            resolutionStore.resolveAutomationReviews(imageId)
        }

        val result = combinedResponse + mapOf(
            "automation_stages" to (earlyStages + "qwen_semantic_bundle"),
            "automation_completed_stages" to completedStages,
            "automation_stage_failures" to stageFailures,
            "workflow" to workflow,
            "organization" to organization,
            "automation_state" to state,
        )
        cleanupPreparedRemoteImage(preparedBase)
        return result
    }

    private fun automationImagePreflight(imageId: Int): Map<String, Any>? {
        val record = repository.getImageRecordsByIds(listOf(imageId)).firstOrNull()
            ?: return mapOf(
                "ok" to false,
                "status" to "missing",
                "message" to "Image record not found.",
                "automation_stage_failures" to 0,
            )

        val inspection = ImageFingerprinting.inspect(
            storage = storageProvider,
            uri = record.uri,
            reportedSizeBytes = record.sizeBytes ?: 0L,
        )
        if (inspection.status == "unreadable") {
            val sourceStillExists = runCatching { storageProvider.exists(record.uri) }.getOrDefault(false)
            if (!sourceStillExists) {
                repository.markImageInactive(imageId)
                resolutionStore.markAutomationState(
                    imageId = imageId,
                    state = "complete",
                    pipelineComplete = false,
                    organizationComplete = true,
                    needsReview = false,
                    lastError = "Source document is no longer accessible and was retired from the active library.",
                )
                return mapOf(
                    "ok" to true,
                    "status" to "stale_source_retired",
                    "message" to "Stale inaccessible image record retired from the active library.",
                    "automation_stage_failures" to 0,
                    "automation_state" to "complete",
                    "automation_skipped" to 1,
                )
            }

            resolutionStore.markAutomationState(
                imageId = imageId,
                state = "retry_required",
                pipelineComplete = false,
                organizationComplete = false,
                needsReview = false,
                lastError = "Image source exists but could not be opened for integrity checking.",
            )
            return mapOf(
                "ok" to false,
                "status" to "retry_required",
                "message" to "Image exists but could not be opened; it was deferred instead of counted as an AI failure.",
                "automation_stage_failures" to 0,
                "automation_state" to "retry_required",
                "automation_skipped" to 1,
            )
        }

        val fingerprint = inspection.fingerprint
        if (inspection.status == "invalid_image" || fingerprint == null) {
            val corrupt = ContentTriagePolicy.corruptDecision()
            val workflow = mapOf<String, Any>(
                "accepted" to false,
                "queued_for_review" to false,
                "content_route" to corrupt.route,
                "content_route_reason" to corrupt.reason,
                "content_route_terminal" to true,
                "content_route_folders" to corrupt.folderSegments,
                "content_route_filename_prefix" to corrupt.filenamePrefix,
                "resolved_characters" to emptyList<Map<String, Any>>(),
            )
            val organization = organizeAutonomousImage(imageId, workflow)
            val ok = organization["ok"] == true
            resolutionStore.markAutomationState(
                imageId = imageId,
                state = if (ok) "complete" else "retry_required",
                pipelineComplete = true,
                organizationComplete = ok,
                needsReview = false,
                lastError = if (ok) "" else organization["message"]?.toString().orEmpty(),
            )
            return mapOf(
                "ok" to ok,
                "status" to if (ok) "corrupt_routed" else "corrupt_route_failed",
                "workflow" to workflow,
                "organization" to organization,
                "automation_stage_failures" to 0,
                "automation_state" to if (ok) "complete" else "retry_required",
            )
        }

        val exact = resolutionStore.findExactDuplicate(imageId, fingerprint.sha256)
        val visual = if (exact == null) {
            resolutionStore
                .perceptualDuplicateCandidates(
                    imageId = imageId,
                    width = fingerprint.width,
                    height = fingerprint.height,
                )
                .firstOrNull { candidate ->
                    ImageFingerprinting.isConservativeVisualDuplicate(
                        current = fingerprint,
                        candidate = candidate,
                    )
                }
        } else {
            null
        }
        val duplicate = exact ?: visual
        if (duplicate != null) {
            val operation = executeFileOperations(
                mapOf(
                    "action" to "delete_images",
                    "image_ids" to listOf(imageId),
                ),
            )
            val ok = operation["ok"] == true
            return mapOf(
                "ok" to ok,
                "status" to if (ok) "duplicate_deleted" else "duplicate_delete_failed",
                "message" to if (ok) {
                    "Duplicate image removed before AI processing."
                } else {
                    operation["message"]?.toString().orEmpty().ifBlank { "Duplicate cleanup failed." }
                },
                "duplicate_kind" to if (exact != null) "exact" else "conservative_visual",
                "duplicate_of_image_id" to duplicate.imageId,
                "duplicate_of_uri" to duplicate.uri,
                "workflow" to mapOf(
                    "queued_for_review" to false,
                    "content_route" to "duplicate_deleted",
                ),
                "organization" to mapOf(
                    "ok" to ok,
                    "status" to if (ok) "duplicate_deleted" else "duplicate_delete_failed",
                ),
                "automation_stage_failures" to 0,
                "automation_state" to if (ok) "complete" else "retry_required",
            )
        }

        resolutionStore.upsertImageFingerprint(
            imageId = imageId,
            sha256 = fingerprint.sha256,
            perceptualHash = fingerprint.perceptualHash,
            width = fingerprint.width,
            height = fingerprint.height,
            sizeBytes = fingerprint.sizeBytes,
        )
        return null
    }

    private fun resolvedQwenSemanticModel(): Pair<String, String>? {
        return localAiManager.listInstalledModels()
            .asSequence()
            .filter { model ->
                val metadata = model["metadata"] as? Map<*, *>
                val readiness = metadata?.get("execution_readiness") as? Map<*, *>
                readiness?.get("ready") != false
            }
            .mapNotNull { model ->
                val modelId = model["model_id"]?.toString()?.trim().orEmpty()
                val version = model["version"]?.toString()?.trim().orEmpty()
                val displayName = model["display_name"]?.toString()?.trim().orEmpty()
                val installPath = model["install_path"]?.toString()?.trim().orEmpty()
                val identity = "$modelId $displayName $installPath".lowercase()
                val metadata = model["metadata"] as? Map<*, *>
                val llama = metadata?.get("llama_cpp") as? Map<*, *>
                val supported = (model["supported_tasks"] as? List<*>)
                    ?.mapNotNull { it?.toString()?.let(AiTaskTypes::normalize) }
                    .orEmpty()
                if (
                    modelId.isNotBlank() &&
                    "qwen" in identity &&
                    llama?.get("multimodal") == true &&
                    ("captioning" in supported || "text_generation" in supported) &&
                    localAiManager.modelTaskExecutionReadiness(modelId, version, "captioning")["ready"] == true
                ) {
                    modelId to version
                } else {
                    null
                }
            }
            .firstOrNull()
    }

    private fun resolvedAutonomousImageStages(): List<String> {
        val execution = localAiManager.taskExecutionReadiness(
            autonomousImageStageCandidates,
            imageInputTasks = setOf("embedding_generation"),
        )
        val taskPlans = (execution["tasks"] as? Map<*, *>).orEmpty()
        return autonomousImageStageCandidates.filter { stage ->
            (taskPlans[stage] as? Map<*, *>)?.get("ready") == true
        }
    }

    private fun organizeAutonomousImage(imageId: Int, workflow: Map<String, Any>): Map<String, Any> {
        if (workflow["content_route_terminal"] == true) {
            val folders = (workflow["content_route_folders"] as? List<*>)
                ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
                .orEmpty()
            val prefix = workflow["content_route_filename_prefix"]?.toString()?.trim().orEmpty()
            if (folders.isNotEmpty() && prefix.isNotBlank()) {
                return organizeContentCategory(
                    imageId = imageId,
                    folderSegments = folders,
                    filenamePrefix = prefix,
                    route = workflow["content_route"]?.toString().orEmpty(),
                )
            }
        }

        if (workflow["queued_for_review"] == true) {
            return mapOf(
                "ok" to true,
                "status" to "unchanged",
                "message" to "Identity is below threshold; file remains in place for Review.",
            )
        }

        val originalCharacter = workflow["original_character"] == true
        val resolvedCharacters = (workflow["resolved_characters"] as? List<*>)
            ?.mapNotNull { raw ->
                (raw as? Map<*, *>)?.entries
                    ?.mapNotNull { (key, value) -> key?.toString()?.let { text -> value?.let { text to it } } }
                    ?.toMap()
            }
            .orEmpty()

        if (!originalCharacter && resolvedCharacters.isEmpty()) {
            return mapOf(
                "ok" to true,
                "status" to "unchanged",
                "message" to "No high-confidence Character Knowledge identity; file left in place.",
            )
        }

        val record = repository.getImageRecordsByIds(listOf(imageId)).firstOrNull()
            ?: return mapOf("ok" to false, "status" to "missing", "message" to "Image record not found.")
        val roots = repository.listFolders(includeDisabled = false)
            .mapNotNull { it["folder_uri"]?.toString()?.trim()?.takeIf(String::isNotBlank) }
        val root = roots
            .filter { candidate ->
                record.uri.startsWith(candidate) ||
                    record.folderUri.startsWith(candidate) ||
                    record.parentUri.startsWith(candidate)
            }
            .maxByOrNull(String::length)
            ?: roots.singleOrNull()
            ?: record.folderUri
        if (root.isBlank()) {
            return mapOf("ok" to false, "status" to "no_root", "message" to "No writable library root is available.")
        }

        val extension = record.filename.substringAfterLast('.', "").takeIf(String::isNotBlank).orEmpty()
        val pathInputs = resolvedCharacters.mapNotNull { character ->
            val canonicalName = character["canonical_name"]?.toString()?.trim().orEmpty()
            val seriesCode = character["series_code"]?.toString()?.trim().orEmpty()
            val seriesName = character["series_name"]?.toString()?.trim().orEmpty()
            if (canonicalName.isBlank() || seriesCode.isBlank() || seriesName.isBlank()) {
                null
            } else {
                ResolvedCharacterPathInput(
                    subjectIndex = (character["subject_index"] as? Number)?.toInt() ?: Int.MAX_VALUE,
                    prominence = (character["prominence"] as? Number)?.toDouble()?.coerceIn(0.0, 1.0) ?: 0.0,
                    canonicalName = canonicalName,
                    seriesCode = seriesCode,
                    seriesName = seriesName,
                )
            }
        }
        val pathPlan = AutomationPathPolicy.plan(
            characters = pathInputs,
            originalCharacter = originalCharacter,
            originalCharacterClusterId = workflow["original_character_cluster_id"]?.toString().orEmpty(),
        ) ?: return mapOf(
            "ok" to true,
            "status" to "unchanged",
            "message" to "Resolved character is missing canonical Character Knowledge path data.",
        )

        var targetFolder = root
        val safeFolderSegments = mutableListOf<String>()
        pathPlan.folderSegments.forEach { rawSegment ->
            val safeSegment = safeAutomationPathSegment(rawSegment)
            targetFolder = getOrCreateAutomationFolder(targetFolder, safeSegment)
                ?: return mapOf(
                    "ok" to false,
                    "status" to "folder_failed",
                    "message" to "Unable to create automation folder '" + safeSegment + "'.",
                )
            safeFolderSegments += safeSegment
        }
        val relativeFolder = safeFolderSegments.joinToString("/")
        val prefix = pathPlan.filenameParts
            .map(::safeAutomationPathSegment)
            .joinToString(" - ")
        val primarySeries = pathPlan.primarySeries
        val primaryCharacter = pathPlan.primaryCharacter

        val sequence = nextAutomationSequenceNumber(targetFolder, prefix, extension)
        val stem = prefix + " " + sequence
        val targetName = if (extension.isBlank()) stem else stem + "." + extension

        if (record.folderUri == targetFolder && record.filename == targetName) {
            return mapOf(
                "ok" to true,
                "status" to "already_organized",
                "folder" to relativeFolder,
                "filename" to targetName,
            )
        }

        val changed = if (record.folderUri == targetFolder) {
            storageProvider.rename(record.uri, targetName)
        } else {
            storageProvider.move(record.uri, targetFolder, targetName)
        }
        if (!changed.ok || changed.uri.isNullOrBlank()) {
            return mapOf(
                "ok" to false,
                "status" to "file_operation_failed",
                "message" to changed.message.ifBlank { "Unable to organize image." },
            )
        }

        val updated = repository.updateImagePathAndClearThumbnails(
            imageId = imageId,
            newUri = changed.uri,
            newFilename = targetName,
            newFolderUri = targetFolder,
            newParentUri = targetFolder,
            newFolderName = relativeFolder.substringAfterLast('/'),
            newRelativePath = relativeFolder + "/" + targetName,
            newModifiedAtMs = System.currentTimeMillis(),
            oldUri = record.uri,
        )
        if (!updated) {
            return mapOf(
                "ok" to false,
                "status" to "database_update_failed",
                "message" to "File was organized but the library record could not be updated.",
            )
        }

        return mapOf(
            "ok" to true,
            "status" to "organized",
            "series" to primarySeries,
            "character" to primaryCharacter,
            "characters" to resolvedCharacters,
            "original_character" to originalCharacter,
            "folder" to relativeFolder,
            "filename" to targetName,
            "sequence" to sequence,
            "uri" to changed.uri,
        )
    }

    private fun organizeContentCategory(
        imageId: Int,
        folderSegments: List<String>,
        filenamePrefix: String,
        route: String,
    ): Map<String, Any> {
        val record = repository.getImageRecordsByIds(listOf(imageId)).firstOrNull()
            ?: return mapOf("ok" to false, "status" to "missing", "message" to "Image record not found.")
        val roots = repository.listFolders(includeDisabled = false)
            .mapNotNull { it["folder_uri"]?.toString()?.trim()?.takeIf(String::isNotBlank) }
        val root = roots
            .filter { candidate ->
                record.uri.startsWith(candidate) ||
                    record.folderUri.startsWith(candidate) ||
                    record.parentUri.startsWith(candidate)
            }
            .maxByOrNull(String::length)
            ?: roots.singleOrNull()
            ?: record.folderUri
        if (root.isBlank()) {
            return mapOf("ok" to false, "status" to "no_root", "message" to "No writable library root is available.")
        }

        var targetFolder = root
        val safeSegments = mutableListOf<String>()
        folderSegments.forEach { raw ->
            val safe = safeAutomationPathSegment(raw)
            targetFolder = getOrCreateAutomationFolder(targetFolder, safe)
                ?: return mapOf(
                    "ok" to false,
                    "status" to "folder_failed",
                    "message" to "Unable to create automation folder '" + safe + "'.",
                )
            safeSegments += safe
        }

        val extension = record.filename.substringAfterLast('.', "").takeIf(String::isNotBlank).orEmpty()
        val prefix = safeAutomationPathSegment(filenamePrefix)
        val sequence = nextAutomationSequenceNumber(targetFolder, prefix, extension)
        val stem = prefix + " " + sequence
        val targetName = if (extension.isBlank()) stem else stem + "." + extension

        if (record.folderUri == targetFolder && record.filename == targetName) {
            return mapOf(
                "ok" to true,
                "status" to "already_routed",
                "route" to route,
                "folder" to safeSegments.joinToString("/"),
                "filename" to targetName,
            )
        }

        val changed = if (record.folderUri == targetFolder) {
            storageProvider.rename(record.uri, targetName)
        } else {
            storageProvider.move(record.uri, targetFolder, targetName)
        }
        if (!changed.ok || changed.uri.isNullOrBlank()) {
            return mapOf(
                "ok" to false,
                "status" to "file_operation_failed",
                "message" to changed.message.ifBlank { "Unable to route image." },
            )
        }

        val relativeFolder = safeSegments.joinToString("/")
        val updated = repository.updateImagePathAndClearThumbnails(
            imageId = imageId,
            newUri = changed.uri,
            newFilename = targetName,
            newFolderUri = targetFolder,
            newParentUri = targetFolder,
            newFolderName = safeSegments.lastOrNull().orEmpty(),
            newRelativePath = if (relativeFolder.isBlank()) targetName else relativeFolder + "/" + targetName,
            newModifiedAtMs = System.currentTimeMillis(),
            oldUri = record.uri,
        )
        return if (updated) {
            mapOf(
                "ok" to true,
                "status" to "routed_content",
                "route" to route,
                "folder" to relativeFolder,
                "filename" to targetName,
                "uri" to changed.uri,
            )
        } else {
            mapOf(
                "ok" to false,
                "status" to "database_update_failed",
                "message" to "File was routed but the library record could not be updated.",
            )
        }
    }

    private fun getOrCreateAutomationFolder(parentUri: String, rawName: String): String? {
        val name = safeAutomationPathSegment(rawName)
        return storageProvider.listChildren(parentUri)
            .firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) }
            ?.uri
            ?: storageProvider.createFolder(parentUri, name).takeIf { it.ok }?.uri
    }

    private fun nextAutomationSequenceNumber(targetFolder: String, prefix: String, extension: String): Int {
        val escapedPrefix = Regex.escape(prefix)
        val escapedExtension = extension.takeIf(String::isNotBlank)?.let { "\\.${Regex.escape(it)}" }.orEmpty()
        val pattern = Regex("^$escapedPrefix\\s+(\\d+)$escapedExtension$", RegexOption.IGNORE_CASE)
        return storageProvider.listChildren(targetFolder)
            .asSequence()
            .filter { !it.isDirectory }
            .mapNotNull { node -> pattern.matchEntire(node.name)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            .maxOrNull()
            ?.plus(1)
            ?: 1
    }

    private fun safeAutomationPathSegment(raw: String): String {
        val cleaned = raw
            .replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
        return cleaned.ifBlank { "Unsorted" }.take(96).trim().ifBlank { "Unsorted" }
    }

    private fun scheduleCharacterSheetIndexing() {
        val request = OneTimeWorkRequestBuilder<CharacterSheetIndexWorker>()
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            CharacterSheetIndexWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun indexPendingCharacterSheets(limit: Int = 50): Map<String, Any> {
        ensureInitialized()
        val pending = resolutionStore.pendingCharacterSheets(limit.coerceIn(1, 250))
        if (pending.isEmpty()) {
            return mapOf(
                "ok" to true,
                "indexed" to 0,
                "failed" to 0,
                "remaining" to 0,
                "message" to "Canonical character sheet visual index is current.",
            )
        }

        var indexed = 0
        var failed = 0
        pending.forEach { sheet ->
            val characterId = sheet["character_id"].orEmpty()
            val sourceKey = sheet["source_uri"].orEmpty()
            val assetPath = sourceKey.substringBeforeLast('#', sourceKey)
            if (characterId.isBlank() || assetPath.isBlank()) {
                failed += 1
                if (characterId.isNotBlank()) {
                    resolutionStore.failCharacterSheetIndex(characterId, "Character sheet index entry is missing an asset path.", retryable = false)
                }
                return@forEach
            }

            val response = localAiManager.runPipeline(
                mapOf(
                    "task_type" to "embedding_generation",
                    "stages" to listOf("embedding_generation"),
                    "path" to assetPath,
                    "image_path" to assetPath,
                    "max_retries" to 1,
                ),
            )
            val result = (response["result"] as? Map<*, *>)?.toStringAnyMap().orEmpty()
            val embedding = (result["embedding"] as? List<*>)
                ?.mapNotNull { (it as? Number)?.toDouble() }
                .orEmpty()

            if (response["ok"] == true && embedding.isNotEmpty()) {
                resolutionStore.replaceCanonicalSheetEvidence(
                    characterId = characterId,
                    sourceUri = sourceKey,
                    embedding = embedding,
                )
                resolutionStore.completeCharacterSheetIndex(characterId, sourceKey)
                indexed += 1
            } else {
                val message = response["message"]?.toString().orEmpty()
                    .ifBlank { "Embedding generation returned no character-sheet vector." }
                resolutionStore.failCharacterSheetIndex(characterId, message, retryable = true)
                failed += 1
            }
        }

        val remaining = resolutionStore.characterSheetIndexRemaining()
        return mapOf(
            "ok" to (failed == 0),
            "indexed" to indexed,
            "failed" to failed,
            "remaining" to remaining,
            "message" to if (remaining == 0) {
                "Canonical character sheet visual index is current."
            } else {
                "Character sheet indexing can continue in another batch."
            },
        )
    }

    fun countPendingCharacterSheetIndexes(): Int {
        ensureInitialized()
        return resolutionStore.characterSheetIndexRemaining()
    }

    fun automationImageIds(forceAll: Boolean = false): List<Int> {
        ensureInitialized()
        return resolutionStore.listAutomationImageIds(forceAll)
    }

    fun automationReadiness(): Map<String, Any> {
        ensureInitialized()
        val stages = resolvedAutonomousImageStages()
        val hasCharacterKnowledge = knowledgeDatabase.hasCharacters()
        val requiredStages = autonomousImageStageCandidates
        val missingStages = requiredStages.filterNot(stages::contains).toMutableList()
        if (resolvedQwenSemanticModel() == null && "qwen_semantic_bundle" !in missingStages) {
            missingStages += "qwen_semantic_bundle"
        }

        return when {
            stages.isEmpty() -> mapOf(
                "ready" to false,
                "stages" to emptyList<String>(),
                "missing_stages" to missingStages.ifEmpty { requiredStages },
                "character_knowledge_ready" to hasCharacterKnowledge,
                "message" to "No installed execution-ready model can run the automation pipeline.",
            )
            missingStages.isNotEmpty() -> mapOf(
                "ready" to false,
                "stages" to stages,
                "missing_stages" to missingStages,
                "character_knowledge_ready" to hasCharacterKnowledge,
                "message" to "Automation is missing required model stage(s): " + missingStages.joinToString(", "),
            )
            else -> mapOf(
                "ready" to true,
                "stages" to (stages + "qwen_semantic_bundle"),
                "missing_stages" to emptyList<String>(),
                "character_knowledge_ready" to hasCharacterKnowledge,
                "message" to if (hasCharacterKnowledge) {
                    "Automation is ready, including one-pass Qwen-VL semantics, character resolution, and organization."
                } else {
                    "Analysis automation is ready. Character-based rename/move will wait for Character Knowledge."
                },
            )
        }
    }

    fun probeAutomationExecution(imageIds: List<Int>): Map<String, Any> {
        ensureInitialized()
        if (imageIds.isEmpty()) {
            return mapOf(
                "ready" to true,
                "image_id" to 0,
                "message" to "No images require automation.",
            )
        }

        var probeImageId = 0
        var lastProbeStatus = "no_candidate"
        val probeCandidates = buildList {
            addAll(imageIds.asReversed().take(120))
            addAll(imageIds.take(80))
        }.distinct()
        for (imageId in probeCandidates) {
            val record = repository.getImageRecordsByIds(listOf(imageId)).firstOrNull()
            if (record == null) {
                lastProbeStatus = "missing_record"
                continue
            }
            val inspection = ImageFingerprinting.inspect(
                storage = storageProvider,
                uri = record.uri,
                reportedSizeBytes = record.sizeBytes ?: 0L,
            )
            lastProbeStatus = inspection.status
            if (inspection.status == "ready" && inspection.fingerprint != null) {
                probeImageId = imageId
                break
            }
            if (inspection.status == "unreadable" && !runCatching { storageProvider.exists(record.uri) }.getOrDefault(false)) {
                repository.markImageInactive(imageId)
            }
        }
        if (probeImageId <= 0) {
            return mapOf(
                "ready" to false,
                "image_id" to 0,
                "stage" to "image_input",
                "status" to lastProbeStatus,
                "message" to "Automation cannot read a real library image. Re-select or rescan the library folder before running AI.",
            )
        }

        val earlyStages = resolvedAutonomousImageStages().filter {
            it in setOf("ocr", "nsfw_classification", "embedding_generation", "aesthetic_scoring")
        }
        val preparedBase = prepareAiPipelinePayload(
            mapOf(
                "image_id" to probeImageId,
                "task_type" to "autonomous_image_workflow",
                "character_taxonomy_context" to knowledgeDatabase.taxonomyPromptContext(),
                "illustration_taxonomy_context" to knowledgeDatabase.illustrationTaxonomyPromptContext(),
            ),
        )

        try {
            val earlyResponse = localAiManager.runMultiStagePipeline(
                preparedBase + mapOf(
                    "stages" to earlyStages,
                    "continue_on_stage_error" to false,
                    "timeout_ms" to 120_000L,
                    "max_retries" to 0,
                ),
            )
            if (earlyResponse["ok"] != true) {
                val stageRecord = (earlyResponse["stages"] as? List<*>)
                    ?.mapNotNull { it as? Map<*, *> }
                    ?.lastOrNull()
                val stage = stageRecord?.get("stage_type")?.toString().orEmpty().ifBlank { "early_ai" }
                val status = stageRecord?.get("status")?.toString().orEmpty()
                    .ifBlank { earlyResponse["status"]?.toString().orEmpty() }
                val message = stageRecord?.get("message")?.toString().orEmpty()
                    .ifBlank { earlyResponse["message"]?.toString().orEmpty() }
                    .ifBlank { "AI execution failed during the automation probe." }
                return mapOf(
                    "ready" to false,
                    "image_id" to probeImageId,
                    "stage" to stage,
                    "status" to status,
                    "message" to "$stage failed before full-library automation: $message",
                    "details" to earlyResponse,
                )
            }

            val earlyOutputs = (earlyResponse["stage_outputs"] as? Map<*, *>)
                ?.entries
                ?.mapNotNull { (key, value) ->
                    val stage = key?.toString()?.trim().orEmpty()
                    val output = (value as? Map<*, *>)?.entries
                        ?.mapNotNull { (nestedKey, nestedValue) ->
                            nestedKey?.toString()?.let { text -> nestedValue?.let { text to it } }
                        }
                        ?.toMap()
                        .orEmpty()
                    if (stage.isBlank()) null else stage to output
                }
                ?.toMap()
                .orEmpty()
            val ocrText = earlyOutputs["ocr"].orEmpty().resultMap()["text"]?.toString().orEmpty()
            val nsfwResult = earlyOutputs["nsfw_classification"].orEmpty().resultMap()

            val qwenModel = resolvedQwenSemanticModel()
                ?: return mapOf(
                    "ready" to false,
                    "image_id" to probeImageId,
                    "stage" to "qwen_semantic_bundle",
                    "status" to "incompatible",
                    "message" to "No execution-ready Qwen-VL model can run the semantic image pass.",
                )

            val qwenResponse = localAiManager.runPipeline(
                preparedBase + mapOf(
                    "task_type" to "captioning",
                    "stages" to listOf("captioning"),
                    "model_id" to qwenModel.first,
                    "version" to qwenModel.second,
                    "asterion_semantic_bundle" to true,
                    "ocr_text" to ocrText,
                    "nsfw_result" to nsfwResult,
                    "max_new_tokens" to 256,
                    "timeout_ms" to 180_000L,
                    "max_retries" to 0,
                    "continue_on_stage_error" to false,
                ),
            )
            val qwenRaw = (qwenResponse["raw_result"] as? Map<*, *>)
                ?.entries
                ?.mapNotNull { (key, value) -> key?.toString()?.let { text -> value?.let { text to it } } }
                ?.toMap()
                .orEmpty()
            val qwenResult = qwenRaw.resultMap()
            val parseError = qwenResult["parse_error"]?.toString().orEmpty()
            if (qwenResponse["ok"] != true || parseError.isNotBlank()) {
                val stageRecord = (qwenResponse["stages"] as? List<*>)
                    ?.mapNotNull { it as? Map<*, *> }
                    ?.lastOrNull()
                val status = stageRecord?.get("status")?.toString().orEmpty()
                    .ifBlank { qwenResponse["status"]?.toString().orEmpty() }
                val message = parseError.ifBlank {
                    stageRecord?.get("message")?.toString().orEmpty()
                        .ifBlank { qwenResponse["message"]?.toString().orEmpty() }
                }.ifBlank { "Qwen-VL semantic execution failed." }
                return mapOf(
                    "ready" to false,
                    "image_id" to probeImageId,
                    "stage" to "qwen_semantic_bundle",
                    "status" to status,
                    "message" to "qwen_semantic_bundle failed before full-library automation: $message",
                    "details" to qwenResponse,
                )
            }

            return mapOf(
                "ready" to true,
                "image_id" to probeImageId,
                "stage" to "qwen_semantic_bundle",
                "status" to "succeeded",
                "message" to "Real AI execution probe succeeded on library image #$probeImageId.",
            )
        } finally {
            cleanupPreparedRemoteImage(preparedBase)
        }
    }

    fun requestAutomationPause() {
        ensureInitialized()
        appContext.getSharedPreferences(AUTOMATION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(AUTOMATION_PAUSE_REQUESTED_KEY, true)
            .apply()
    }

    fun clearAutomationPauseRequest() {
        ensureInitialized()
        appContext.getSharedPreferences(AUTOMATION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(AUTOMATION_PAUSE_REQUESTED_KEY, false)
            .apply()
    }

    fun automationPauseRequested(): Boolean {
        ensureInitialized()
        return appContext.getSharedPreferences(AUTOMATION_PREFS, Context.MODE_PRIVATE)
            .getBoolean(AUTOMATION_PAUSE_REQUESTED_KEY, false)
    }

    fun repairLegacyIncompleteAutomationStates(): Int {
        ensureInitialized()
        val prefs = appContext.getSharedPreferences(AUTOMATION_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(AUTOMATION_LEGACY_REVIEW_REPAIR_V1_KEY, false)) {
            return 0
        }
        val repaired = resolutionStore.requeueLegacyReviewPendingWithoutSubjects()
        prefs.edit().putBoolean(AUTOMATION_LEGACY_REVIEW_REPAIR_V1_KEY, true).apply()
        return repaired
    }

    fun automationStatus(): Map<String, Any> {
        ensureInitialized()
        val prefs = appContext.getSharedPreferences(AUTOMATION_PREFS, Context.MODE_PRIVATE)
        return mapOf(
            "automation_status" to prefs.getString(AUTOMATION_STATUS_KEY, "idle").orEmpty(),
            "automation_total" to prefs.getInt(AUTOMATION_TOTAL_KEY, 0),
            "automation_processed" to prefs.getInt(AUTOMATION_PROCESSED_KEY, 0),
            "automation_failed" to prefs.getInt(AUTOMATION_FAILED_KEY, 0),
            "automation_review" to prefs.getInt(AUTOMATION_REVIEW_KEY, 0),
            "automation_skipped" to prefs.getInt(AUTOMATION_SKIPPED_KEY, 0),
            "automation_current_image_id" to prefs.getInt(AUTOMATION_CURRENT_IMAGE_KEY, 0),
            "automation_message" to prefs.getString(AUTOMATION_MESSAGE_KEY, "").orEmpty(),
            "automation_updated_at_ms" to prefs.getLong(AUTOMATION_UPDATED_AT_KEY, 0L),
        )
    }

    fun updateAutomationStatus(
        status: String,
        total: Int,
        processed: Int,
        failed: Int,
        review: Int = 0,
        skipped: Int = 0,
        currentImageId: Int = 0,
        message: String = "",
    ) {
        ensureInitialized()
        appContext.getSharedPreferences(AUTOMATION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(AUTOMATION_STATUS_KEY, status)
            .putInt(AUTOMATION_TOTAL_KEY, total.coerceAtLeast(0))
            .putInt(AUTOMATION_PROCESSED_KEY, processed.coerceAtLeast(0))
            .putInt(AUTOMATION_FAILED_KEY, failed.coerceAtLeast(0))
            .putInt(AUTOMATION_REVIEW_KEY, review.coerceAtLeast(0))
            .putInt(AUTOMATION_SKIPPED_KEY, skipped.coerceAtLeast(0))
            .putInt(AUTOMATION_CURRENT_IMAGE_KEY, currentImageId.coerceAtLeast(0))
            .putString(AUTOMATION_MESSAGE_KEY, message)
            .putLong(AUTOMATION_UPDATED_AT_KEY, System.currentTimeMillis())
            .apply()
    }

    fun runKnowledgePackExecution(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val prepared = prepareAiPipelinePayload(payload + mapOf("task_type" to "knowledge_pack_execution"))
        val response = localAiManager.executeKnowledgePack(prepared)
        applyPipelineSideEffects(prepared, response)
        return response
    }

    fun runOcrPipeline(payload: Map<String, Any>): Map<String, Any> {
        return runAiPipeline(payload + mapOf("task_type" to "ocr"))
    }

    fun runCaptioningPipeline(payload: Map<String, Any>): Map<String, Any> {
        return runAiPipeline(payload + mapOf("task_type" to "captioning"))
    }

    fun runCharacterRecognitionPipeline(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        return runAiPipeline(
            payload + mapOf(
                "task_type" to "character_recognition",
                "character_taxonomy_context" to knowledgeDatabase.taxonomyPromptContext(),
            ),
        )
    }

    fun runSeriesRecognitionPipeline(payload: Map<String, Any>): Map<String, Any> {
        return runAiPipeline(payload + mapOf("task_type" to "series_recognition"))
    }

    fun runArtistRecognitionPipeline(payload: Map<String, Any>): Map<String, Any> {
        return runAiPipeline(payload + mapOf("task_type" to "artist_recognition"))
    }

    fun runTagPredictionPipeline(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        return runAiPipeline(
            payload + mapOf(
                "task_type" to "tag_prediction",
                "illustration_taxonomy_context" to knowledgeDatabase.illustrationTaxonomyPromptContext(),
            ),
        )
    }

    fun runMetadataExtractionPipeline(payload: Map<String, Any>): Map<String, Any> {
        return runAiPipeline(payload + mapOf("task_type" to "metadata_extraction"))
    }

    fun runPromptGenerationPipeline(payload: Map<String, Any>): Map<String, Any> {
        return runAiPipeline(payload + mapOf("task_type" to "prompt_generation"))
    }

    fun runEmbeddingGenerationPipeline(payload: Map<String, Any>): Map<String, Any> {
        return runAiPipeline(payload + mapOf("task_type" to "embedding_generation"))
    }

    fun runDuplicateDetectionPipeline(payload: Map<String, Any>): Map<String, Any> {
        return runAiPipeline(payload + mapOf("task_type" to "duplicate_detection"))
    }

    fun runClassificationPipeline(payload: Map<String, Any>): Map<String, Any> {
        return runAiPipeline(payload + mapOf("task_type" to "classification"))
    }

    fun runDetectionPipeline(payload: Map<String, Any>): Map<String, Any> {
        return runAiPipeline(payload + mapOf("task_type" to "detection"))
    }

    fun runFaceFeatureExtractionPipeline(payload: Map<String, Any>): Map<String, Any> {
        return runAiPipeline(payload + mapOf("task_type" to "face_feature_extraction"))
    }

    fun configureTeraBox(clientId: String, clientSecret: String, privateSecret: String): Map<String, Any> {
        ensureInitialized()
        val config = TeraBoxConfig(
            clientId = clientId.trim(),
            clientSecret = clientSecret.trim(),
            privateSecret = privateSecret.trim(),
        )
        if (config.clientId.isBlank() || config.clientSecret.isBlank() || config.privateSecret.isBlank()) {
            return mapOf("ok" to false, "message" to "client_id, client_secret, and private_secret are required.")
        }
        teraBoxStore.saveConfig(config)
        return mapOf(
            "ok" to true,
            "message" to "TeraBox application credentials saved securely on this device.",
            "status" to teraBoxClient.connectionStatus(),
        )
    }

    fun teraBoxAuthorizationUrl(): String {
        ensureInitialized()
        return teraBoxClient.authorizationUrl()
    }

    fun completeTeraBoxAuthorization(callbackUri: Uri): Map<String, Any> {
        ensureInitialized()
        if (!callbackUri.scheme.equals("asterioncore", ignoreCase = true) ||
            !callbackUri.host.equals("teraboxOauth", ignoreCase = true)
        ) {
            return mapOf("ok" to false, "message" to "Unsupported OAuth callback.")
        }
        val code = callbackUri.getQueryParameter("code").orEmpty()
        return teraBoxClient.exchangeAuthorizationCode(code)
    }

    fun teraBoxStatus(): Map<String, Any> {
        ensureInitialized()
        return teraBoxClient.connectionStatus()
    }

    fun disconnectTeraBox(): Boolean {
        ensureInitialized()
        teraBoxStore.clearSession()
        return true
    }

    fun addTeraBoxLibraryRoot(path: String = "/"): Map<String, Any> {
        ensureInitialized()
        val status = teraBoxClient.connectionStatus()
        if (status["connected"] != true) {
            return mapOf("ok" to false, "message" to "Connect TeraBox first.")
        }
        val normalized = path.trim().ifBlank { "/" }.let { if (it.startsWith('/')) it else "/$it" }
        val uri = TeraBoxUris.fromPath(normalized)
        repository.registerFolder(uri, enabled = true)
        return mapOf(
            "ok" to true,
            "folder_uri" to uri,
            "message" to "TeraBox library root added: $normalized",
        )
    }

    fun listLibraryFolders(includeDisabled: Boolean = true): List<Map<String, Any>> {
        ensureInitialized()
        return repository.listFolders(includeDisabled = includeDisabled)
    }

    fun addLibraryFolder(folderUri: String): Boolean {
        ensureInitialized()
        val uri = folderUri.trim()
        if (uri.isBlank()) {
            return false
        }
        repository.registerFolder(uri, enabled = true)
        return true
    }

    /**
     * Imports images exposed through Android's document picker (including cloud
     * providers such as TeraBox when they register for ACTION_OPEN_DOCUMENT)
     * into a writable SAF library. Cloud document URIs are copied locally first
     * so later rename/move organization is fully under Asterion's control.
     */
    fun importCloudImages(uris: List<Uri>, targetRootUri: String): Map<String, Any> {
        ensureInitialized()
        val root = targetRootUri.trim()
        if (root.isBlank()) {
            return mapOf("ok" to false, "imported" to 0, "failed" to uris.size, "message" to "Choose a writable library folder first.")
        }
        if (!storageProvider.exists(root)) {
            return mapOf("ok" to false, "imported" to 0, "failed" to uris.size, "message" to "Selected library folder is no longer available.")
        }

        val importFolderName = "Cloud Imports"
        val importFolder = storageProvider.listChildren(root)
            .firstOrNull { node -> node.isDirectory && node.name.equals(importFolderName, ignoreCase = true) }
            ?.uri
            ?: storageProvider.createFolder(root, importFolderName).takeIf { it.ok }?.uri
            ?: return mapOf("ok" to false, "imported" to 0, "failed" to uris.size, "message" to "Unable to create Cloud Imports folder.")

        var imported = 0
        var failed = 0
        val failures = mutableListOf<String>()

        uris.distinctBy(Uri::toString).forEach { sourceUri ->
            val source = DocumentFile.fromSingleUri(appContext, sourceUri)
            val sourceName = source?.name
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: sourceUri.lastPathSegment?.substringAfterLast('/')?.trim().orEmpty().ifBlank { "cloud_image" }

            if (!sourceName.isImageName()) {
                failed += 1
                failures += "$sourceName: unsupported image type"
                return@forEach
            }

            val copied = runCatching {
                storageProvider.copy(sourceUri.toString(), importFolder, sourceName)
            }.getOrElse { error ->
                StorageWriteResult(ok = false, message = error.message ?: error.javaClass.simpleName)
            }

            if (copied.ok) {
                imported += 1
            } else {
                failed += 1
                failures += "$sourceName: " + copied.message.ifBlank { "copy failed" }
            }
        }

        val scan = if (imported > 0) synchronizeFolderIncremental(root) else emptyMap()
        return mapOf(
            "ok" to (imported > 0 && failed == 0),
            "imported" to imported,
            "failed" to failed,
            "import_folder_uri" to importFolder,
            "scan" to scan,
            "failures" to failures,
            "message" to when {
                imported == 0 -> "No cloud images were imported."
                failed == 0 -> "Imported $imported cloud image(s)."
                else -> "Imported $imported cloud image(s); $failed failed."
            },
        )
    }

    fun setLibraryFolderEnabled(folderUri: String, enabled: Boolean): Boolean {
        ensureInitialized()
        return repository.setFolderEnabled(folderUri, enabled)
    }

    fun removeLibraryFolder(folderUri: String): Boolean {
        ensureInitialized()
        return repository.removeFolder(folderUri)
    }

    fun rescanFolder(folderUri: String): Map<String, Any> {
        ensureInitialized()
        return synchronizeFolderIncremental(folderUri)
    }

    fun rescanEnabledFolders(): List<Map<String, Any>> {
        ensureInitialized()
        val folders = repository.listFolders(includeDisabled = false)
        val responses = mutableListOf<Map<String, Any>>()
        for (folder in folders) {
            val uri = folder["folder_uri"]?.toString().orEmpty()
            if (uri.isBlank()) {
                continue
            }
            responses += synchronizeFolderIncremental(uri)
        }
        return responses
    }

    private fun synchronizeFolderIncremental(folderUri: String): Map<String, Any> {
        val normalizedUri = folderUri.trim()
        if (normalizedUri.isBlank()) {
            return mapOf("ok" to false, "status" to "invalid", "message" to "folder_uri is required")
        }
        if (!storageProvider.exists(normalizedUri)) {
            return mapOf("ok" to false, "status" to "missing", "message" to "Selected SAF root is no longer available: $normalizedUri")
        }

        val startedAt = System.currentTimeMillis()
        val scanId = repository.beginScanRun(normalizedUri, startedAt)
        var importIndex = 0L
        var synced = 0
        var skipped = 0

        return try {
            for (node in storageProvider.walkTree(normalizedUri)) {
                if (node.isDirectory || !node.name.isImageName()) {
                    skipped += 1
                    continue
                }

                importIndex += 1
                val metadata = extractMetadata(node)
                val upsert = repository.upsertImage(
                    node = node,
                    folderUri = normalizedUri,
                    scannedAtMs = startedAt,
                    importOrder = startedAt * 1_000_000L + importIndex,
                    metadata = metadata,
                    metadataText = buildMetadataText(node, metadata),
                )
                synced += 1
            }

            repository.markFolderImagesInactiveBefore(normalizedUri, startedAt)
            // FTS triggers update incrementally; no O(N) rebuild is needed here.
            repository.optimizeDatabase()
            repository.finishScanRun(
                scanId = scanId,
                folderUri = normalizedUri,
                completedAtMs = System.currentTimeMillis(),
                status = "completed",
                discoveredCount = synced,
                skippedCount = skipped,
                errorMessage = null,
            )
            mapOf(
                "ok" to true,
                "status" to "completed",
                "folder_uri" to normalizedUri,
                "discovered_count" to synced,
                "skipped_count" to skipped,
                "synchronization_mode" to "incremental",
            )
        } catch (t: Throwable) {
            repository.finishScanRun(
                scanId = scanId,
                folderUri = normalizedUri,
                completedAtMs = System.currentTimeMillis(),
                status = "failed",
                discoveredCount = synced,
                skippedCount = skipped,
                errorMessage = t.message ?: t.javaClass.simpleName,
            )
            mapOf(
                "ok" to false,
                "status" to "failed",
                "folder_uri" to normalizedUri,
                "message" to (t.message ?: t.javaClass.simpleName),
                "discovered_count" to synced,
                "skipped_count" to skipped,
                "synchronization_mode" to "incremental",
            )
        }
    }

    fun setImageTags(imageId: Int, tags: List<String>): Boolean {
        ensureInitialized()
        Log.d(RUNTIME_TRACE_TAG, "Runtime receives tags: imageId=$imageId nextTags=${tags.joinToString("|")}")
        val ok = repository.setTags(imageId, tags)
        Log.d(RUNTIME_TRACE_TAG, "Runtime entry after repository call (tags): imageId=$imageId ok=$ok")
        Log.d(RUNTIME_TRACE_TAG, "Runtime tags update result: imageId=$imageId ok=$ok")
        return ok
    }

    fun scanStatistics(limit: Int = 20): List<Map<String, Any>> {
        ensureInitialized()
        return repository.scanStatistics(limit)
    }

    fun rebuildSearchIndex(): Boolean {
        ensureInitialized()
        repository.rebuildSearchIndex()
        return true
    }

    fun optimizeDatabase(): Boolean {
        ensureInitialized()
        repository.optimizeDatabase()
        return true
    }

    fun manageThumbnailCache(maxBytes: Long = 256L * 1024L * 1024L): Map<String, Any> {
        ensureInitialized()
        val cacheDir = File(appContext.cacheDir, "thumbnails")
        cacheDir.mkdirs()
        return repository.pruneThumbnailCache(maxBytes, cacheDir)
    }

    fun updateSetting(key: String, value: String): Boolean {
        ensureInitialized()
        if (key.isBlank()) {
            return false
        }
        repository.setSetting(key.trim(), value)
        return true
    }

    fun getSetting(key: String, defaultValue: String = ""): String {
        ensureInitialized()
        return repository.getSetting(key.trim(), defaultValue)
    }

    fun exportFusionDatabase(format: String = "json", pretty: Boolean = true): String {
        ensureInitialized()
        val parsedFormat = parseFusionImportFormat(format)
        return repository.exportFusionDatabase(parsedFormat, pretty = pretty)
    }

    fun importKnowledgePackIntoFusion(payload: String, replaceExisting: Boolean = false): Map<String, Any> {
        ensureInitialized()
        val model = knowledgeRepository.buildCanonicalModel(payload)
        val canonicalPayload = knowledgeRepository.toJson(model)
        val rows = knowledgeRepository.buildFusionRows(model)
        val persisted = repository.persistFusionRows(rows, replaceExisting)
        val result: MutableMap<String, Any> = linkedMapOf()
        result.putAll(persisted)
        result["canonical_payload"] = canonicalPayload.toString()
        result["canonical_signature"] = model.canonicalSignature
        result["row_count"] = rows.size
        return result.toMap()
    }

    fun rebuildFusionFromInstalledKnowledgePacks(): Map<String, Any> {
        ensureInitialized()
        val directory = knowledgePackDirectory()
        val discovery = knowledgeRepository.discoverInstalledPacks(directory)
        val validArtifacts = discovery.artifacts.filter { it.validation?.ok == true }
        if (validArtifacts.isEmpty()) {
            return mapOf("ok" to false, "message" to "No valid knowledge packs were found for Fusion rebuild.")
        }
        val model = knowledgeRepository.buildCanonicalModelFromArtifacts(validArtifacts)
        val plan = knowledgeRepository.buildRebuildPlan(validArtifacts)
        val persisted = importKnowledgePackIntoFusion(knowledgeRepository.toJson(model).toString(), replaceExisting = true)
        lastKnowledgeRebuildSignature = plan.rebuildSignature
        lastKnowledgeRebuildAtMs = System.currentTimeMillis()
        return mapOf(
            "ok" to true,
            "message" to "Fusion rebuilt from installed knowledge packs.",
            "pack_count" to validArtifacts.size,
            "row_count" to (persisted["row_count"] as? Int ?: 0),
            "rebuild_signature" to (lastKnowledgeRebuildSignature ?: ""),
            "rebuild_plan" to mapOf(
                "artifact_count" to plan.artifactCount,
                "pack_ids" to plan.packIds,
            ),
            "persisted" to persisted,
        )
    }

    fun fusionManagementStatus(): Map<String, Any> {
        ensureInitialized()
        return repository.fusionManagementStatus()
    }

    fun rebuildFusionOptimizations(): Map<String, Any> {
        ensureInitialized()
        return repository.rebuildFusionOptimizations()
    }

    fun exportFusionDatabaseFile(): Map<String, Any> {
        ensureInitialized()
        val now = System.currentTimeMillis()
        val directory = File(appContext.filesDir, "exports/fusion")
        require(directory.exists() || directory.mkdirs()) { "Unable to prepare Fusion export storage." }
        val output = File(directory, "fusion-$now.json")
        output.writeText(repository.exportFusionDatabase(FusionImportFormat.JSON, pretty = true))
        return mapOf(
            "ok" to true,
            "action" to "export_fusion_database",
            "path" to output.absolutePath,
            "size_bytes" to output.length(),
            "exported_at_ms" to now,
        )
    }

    fun importFusionDatabase(
        payload: String,
        format: String = "json",
        replaceExisting: Boolean = false,
    ): Map<String, Any> {
        ensureInitialized()
        val parsedFormat = parseFusionImportFormat(format)
        val result = repository.importFusionDatabase(
            payload = payload,
            format = parsedFormat,
            replaceExisting = replaceExisting,
        )
        return result.toMap()
    }

    fun previewImport(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val request = payload.toExternalImportRequest()
        val preview = repository.previewImport(request)
        return mapOf(
            "ok" to true,
            "preview" to preview.toMap(),
        )
    }

    fun importDatabase(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val request = payload.toExternalImportRequest()
        val result = repository.importDatabase(request)
        return result.toMap()
    }

    fun cancelImport(importId: String): Boolean {
        ensureInitialized()
        return repository.cancelImport(importId.trim())
    }

    fun rollbackImport(importId: String? = null): Map<String, Any> {
        ensureInitialized()
        val result = repository.rollbackImport(importId?.trim()?.ifBlank { null })
        return result.toMap()
    }

    fun validateImport(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val request = payload.toExternalImportRequest()
        val report = repository.validateImport(request)
        return mapOf(
            "ok" to report.valid,
            "import_id" to request.importId,
            "validation" to report.toMap(),
        )
    }

    fun validateFusionDatabase(): Map<String, Any> {
        ensureInitialized()
        return repository.validateFusionDatabase(persistRun = true).toMap()
    }

    fun previewFileOperations(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        return buildFileOperationPreview(payload, includeConflictAnalysis = true)
    }

    fun executeFileOperations(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val planBuild = buildFileOperationPlans(payload)
        if (!planBuild.ok) {
            return mapOf("ok" to false, "message" to planBuild.message)
        }

        val plans = planBuild.plans
        val results = mutableListOf<Map<String, Any>>()
        val undo = mutableListOf<FileOperationPlan>()
        val touchedFolders = mutableSetOf<String>()
        val recordsById = repository.getImageRecordsByIds(
            plans.mapNotNull { it.imageId }.distinct(),
        ).associateBy { it.imageId }
        val total = plans.size.coerceAtLeast(1)
        var executed = 0

        for (plan in plans) {
            val result = executePlan(plan, recordsById)
            executed += 1
            results += result + mapOf("progress" to (executed.toDouble() / total.toDouble()))

            if (result["ok"] == true) {
                touchedFolders += plan.sourceFolderUri
                touchedFolders += plan.targetFolderUri
                inversePlan(plan, result)?.let { undo += it }
            }
        }

        lastUndoOperations = undo

        val failed = results.count { it["ok"] != true }
        val succeeded = results.size - failed
        val overallOk = failed == 0
        val firstError = results.firstOrNull { it["ok"] != true }?.get("message")?.toString().orEmpty()

        return mapOf(
            "ok" to overallOk,
            "message" to if (overallOk) {
                "Executed $succeeded operation(s)."
            } else {
                firstError.ifBlank { "One or more file operations failed." }
            },
            "results" to results,
            "executed" to executed,
            "total" to plans.size,
            "succeeded" to succeeded,
            "failed" to failed,
            "undo_available" to undo.isNotEmpty(),
            "touched_folders" to touchedFolders.filter { it.isNotBlank() },
        )
    }

    fun undoLastFileOperations(): Map<String, Any> {
        ensureInitialized()
        if (lastUndoOperations.isEmpty()) {
            return mapOf("ok" to false, "message" to "No undo operation available.")
        }
        val results = mutableListOf<Map<String, Any>>()
        val plans = lastUndoOperations.asReversed()
        val recordsById = repository.getImageRecordsByIds(
            plans.mapNotNull { it.imageId }.distinct(),
        ).associateBy { it.imageId }
        for (plan in plans) {
            results += executePlan(plan, recordsById)
        }
        lastUndoOperations = emptyList()

        val failed = results.count { it["ok"] != true }
        val succeeded = results.size - failed
        val overallOk = failed == 0
        val firstError = results.firstOrNull { it["ok"] != true }?.get("message")?.toString().orEmpty()

        return mapOf(
            "ok" to overallOk,
            "message" to if (overallOk) {
                "Undo executed ($succeeded operation(s))."
            } else {
                firstError.ifBlank { "Undo failed for one or more operations." }
            },
            "results" to results,
            "executed" to results.size,
            "succeeded" to succeeded,
            "failed" to failed,
        )
    }

    private data class PlanBuildResult(
        val ok: Boolean,
        val message: String = "",
        val plans: List<FileOperationPlan> = emptyList(),
    )

    private fun buildFileOperationPlans(payload: Map<String, Any>): PlanBuildResult {
        val action = payload["action"]?.toString()?.trim()?.lowercase().orEmpty()
        if (action.isBlank()) {
            return PlanBuildResult(ok = false, message = "action is required")
        }

        val imageIds = parseImageIds(payload["image_ids"])
        val records = repository.getImageRecordsByIds(imageIds)
        val recordsById = records.associateBy { it.imageId }
        val missingImageIds = imageIds.filterNot { recordsById.containsKey(it) }
        val targetFolderUri = payload["target_folder_uri"]?.toString()?.trim().orEmpty()
        val sourceFolderUri = payload["source_folder_uri"]?.toString()?.trim().orEmpty()
        val singleName = payload["name"]?.toString()?.trim().orEmpty()
        val pattern = payload["pattern"]?.toString()?.trim().orEmpty()
        val folderName = payload["folder_name"]?.toString()?.trim().orEmpty()
        val conflictMode = payload["conflict_mode"]?.toString()?.trim()?.lowercase().orEmpty().ifBlank { "rename" }

        val plans = mutableListOf<FileOperationPlan>()
        when (action) {
            "rename_image" -> {
                val id = imageIds.firstOrNull() ?: return PlanBuildResult(ok = false, message = "image_id is required")
                val record = recordsById[id] ?: return PlanBuildResult(ok = false, message = "image not found: $id")
                if (singleName.isBlank()) {
                    return PlanBuildResult(ok = false, message = "name is required")
                }
                plans += FileOperationPlan(
                    action = action,
                    imageId = id,
                    sourceUri = record.uri,
                    sourceName = record.filename,
                    sourceFolderUri = record.folderUri,
                    targetFolderUri = record.folderUri,
                    targetName = singleName,
                    conflictMode = conflictMode,
                )
            }
            "batch_rename_images" -> {
                if (imageIds.isEmpty()) {
                    return PlanBuildResult(ok = false, message = "image_ids are required")
                }
                if (pattern.isBlank()) {
                    return PlanBuildResult(ok = false, message = "pattern is required")
                }
                if (missingImageIds.isNotEmpty()) {
                    return PlanBuildResult(ok = false, message = "image not found: ${missingImageIds.joinToString(",")}")
                }
                imageIds.forEachIndexed { index, id ->
                    val record = recordsById[id] ?: return@forEachIndexed
                    plans += FileOperationPlan(
                        action = action,
                        imageId = id,
                        sourceUri = record.uri,
                        sourceName = record.filename,
                        sourceFolderUri = record.folderUri,
                        targetFolderUri = record.folderUri,
                        targetName = pattern.replace("{n}", (index + 1).toString()),
                        conflictMode = conflictMode,
                    )
                }
            }
            "rename_folder" -> {
                if (sourceFolderUri.isBlank() || folderName.isBlank()) {
                    return PlanBuildResult(ok = false, message = "source_folder_uri and folder_name are required")
                }
                plans += FileOperationPlan(
                    action = action,
                    sourceUri = sourceFolderUri,
                    sourceFolderUri = sourceFolderUri,
                    targetName = folderName,
                    canUndo = true,
                )
            }
            "create_folder" -> {
                if (targetFolderUri.isBlank() || folderName.isBlank()) {
                    return PlanBuildResult(ok = false, message = "target_folder_uri and folder_name are required")
                }
                plans += FileOperationPlan(
                    action = action,
                    sourceFolderUri = targetFolderUri,
                    targetFolderUri = targetFolderUri,
                    targetName = folderName,
                    canUndo = true,
                )
            }
            "delete_folder" -> {
                if (sourceFolderUri.isBlank()) {
                    return PlanBuildResult(ok = false, message = "source_folder_uri is required")
                }
                plans += FileOperationPlan(
                    action = action,
                    sourceUri = sourceFolderUri,
                    sourceFolderUri = sourceFolderUri,
                    canUndo = false,
                )
            }
            "delete_images", "batch_delete" -> {
                if (imageIds.isEmpty()) {
                    return PlanBuildResult(ok = false, message = "image_ids are required")
                }
                if (missingImageIds.isNotEmpty()) {
                    return PlanBuildResult(ok = false, message = "image not found: ${missingImageIds.joinToString(",")}")
                }
                imageIds.forEach { id ->
                    val record = recordsById[id] ?: return@forEach
                    plans += FileOperationPlan(
                        action = "delete_images",
                        imageId = id,
                        sourceUri = record.uri,
                        sourceName = record.filename,
                        sourceFolderUri = record.folderUri,
                        canUndo = false,
                    )
                }
            }
            "move_images", "batch_move" -> {
                if (imageIds.isEmpty() || targetFolderUri.isBlank()) {
                    return PlanBuildResult(ok = false, message = "image_ids and target_folder_uri are required")
                }
                if (missingImageIds.isNotEmpty()) {
                    return PlanBuildResult(ok = false, message = "image not found: ${missingImageIds.joinToString(",")}")
                }
                imageIds.forEach { id ->
                    val record = recordsById[id] ?: return@forEach
                    plans += FileOperationPlan(
                        action = "move_images",
                        imageId = id,
                        sourceUri = record.uri,
                        sourceName = record.filename,
                        sourceFolderUri = record.folderUri,
                        targetFolderUri = targetFolderUri,
                        targetName = record.filename,
                        conflictMode = conflictMode,
                    )
                }
            }
            "copy_images", "batch_copy" -> {
                if (imageIds.isEmpty() || targetFolderUri.isBlank()) {
                    return PlanBuildResult(ok = false, message = "image_ids and target_folder_uri are required")
                }
                if (missingImageIds.isNotEmpty()) {
                    return PlanBuildResult(ok = false, message = "image not found: ${missingImageIds.joinToString(",")}")
                }
                imageIds.forEach { id ->
                    val record = recordsById[id] ?: return@forEach
                    plans += FileOperationPlan(
                        action = "copy_images",
                        imageId = id,
                        sourceUri = record.uri,
                        sourceName = record.filename,
                        sourceFolderUri = record.folderUri,
                        targetFolderUri = targetFolderUri,
                        targetName = record.filename,
                        conflictMode = conflictMode,
                    )
                }
            }
            else -> return PlanBuildResult(ok = false, message = "unsupported action: $action")
        }

        if (plans.isEmpty()) {
            return PlanBuildResult(ok = false, message = "No executable file operation plan was generated.")
        }

        return PlanBuildResult(ok = true, plans = plans)
    }

    private fun buildFileOperationPreview(
        payload: Map<String, Any>,
        includeConflictAnalysis: Boolean,
    ): Map<String, Any> {
        val planBuild = buildFileOperationPlans(payload)
        if (!planBuild.ok) {
            return mapOf("ok" to false, "message" to planBuild.message)
        }

        val plans = planBuild.plans
        val previewRows = mutableListOf<Map<String, Any>>()

        val previewConflictResolver = if (includeConflictAnalysis) OperationConflictResolver(storageProvider) else null
        plans.forEachIndexed { index, plan ->
            val targetName = plan.targetName
            val conflict = if (includeConflictAnalysis) {
                val folder = if (plan.targetFolderUri.isBlank()) plan.sourceFolderUri else plan.targetFolderUri
                val resolver = previewConflictResolver ?: return@forEachIndexed
                val hasConflict = resolver.hasConflict(folder, targetName)
                if (!hasConflict && targetName.isNotBlank()) {
                    resolver.reserve(folder, targetName)
                }
                hasConflict
            } else {
                false
            }
            previewRows += mapOf(
                "index" to index,
                "action" to plan.action,
                "image_id" to (plan.imageId ?: 0),
                "source_uri" to plan.sourceUri,
                "target_folder_uri" to plan.targetFolderUri,
                "target_name" to plan.targetName,
                "conflict" to conflict,
                "conflict_mode" to plan.conflictMode,
                "can_undo" to plan.canUndo,
            )
        }

        return mapOf(
            "ok" to true,
            "operations" to previewRows,
            "total" to previewRows.size,
            "_plans" to plans,
        )
    }

    private fun executePlan(
        plan: FileOperationPlan,
        recordsById: Map<Int, LocalRepository.ImageRecord>,
    ): Map<String, Any> {
        return when (plan.action) {
            "rename_image", "batch_rename_images" -> executeRenameImage(plan, recordsById)
            "rename_folder" -> executeRenameFolder(plan)
            "create_folder" -> executeCreateFolder(plan)
            "delete_folder" -> executeDeleteFolder(plan)
            "delete_images" -> executeDeleteImage(plan, recordsById)
            "move_images" -> executeMoveImage(plan, recordsById)
            "copy_images" -> executeCopyImage(plan, recordsById)
            else -> mapOf("ok" to false, "action" to plan.action, "message" to "unsupported action")
        }
    }

    private fun executeRenameImage(plan: FileOperationPlan, recordsById: Map<Int, LocalRepository.ImageRecord>): Map<String, Any> {
        val imageId = plan.imageId ?: return mapOf("ok" to false, "action" to plan.action, "message" to "image id missing")
        val record = recordsById[imageId] ?: repository.getImageRecordsByIds(listOf(imageId)).firstOrNull()
            ?: return mapOf("ok" to false, "action" to plan.action, "message" to "image not found")
        val requestedName = plan.targetName.trim().ifBlank { return mapOf("ok" to false, "action" to plan.action, "image_id" to imageId, "message" to "name is required") }
        val targetName = preserveImageExtension(requestedName, record.filename)
        val renameOutcome = renameImageWithConflictHandling(
            sourceUri = record.uri,
            sourceFolderUri = record.folderUri,
            sourceParentUri = record.parentUri.ifBlank { record.folderUri },
            requestedName = targetName,
            mode = plan.conflictMode,
        )
        val finalName = renameOutcome.finalName
        val renamed = renameOutcome.result
        if (!renamed.ok || renamed.uri.isNullOrBlank()) {
            return mapOf("ok" to false, "action" to plan.action, "image_id" to imageId, "message" to renamed.message)
        }
        val dbOk = repository.updateImagePathAndClearThumbnails(
            imageId = imageId,
            newUri = renamed.uri,
            newFilename = finalName,
            newFolderUri = record.folderUri,
            newParentUri = record.parentUri,
            newFolderName = FolderUriUtils.displayName(record.folderUri),
            newRelativePath = "$record.parentUri/$finalName",
            newModifiedAtMs = System.currentTimeMillis(),
            oldUri = record.uri,
        )
        if (!dbOk) {
            return mapOf("ok" to false, "action" to plan.action, "image_id" to imageId, "message" to "database update failed")
        }
        return mapOf("ok" to true, "action" to plan.action, "image_id" to imageId, "new_uri" to renamed.uri, "new_name" to finalName)
    }

    private data class RenameAttemptOutcome(
        val result: StorageWriteResult,
        val finalName: String,
    )

    private fun renameImageWithConflictHandling(
        sourceUri: String,
        sourceFolderUri: String,
        sourceParentUri: String,
        requestedName: String,
        mode: String,
    ): RenameAttemptOutcome {
        val normalizedMode = mode.trim().lowercase().ifBlank { "rename" }
        val firstAttempt = storageProvider.rename(sourceUri, requestedName, sourceParentUri)
        if (normalizedMode != "rename") {
            return RenameAttemptOutcome(firstAttempt, requestedName)
        }
        if (firstAttempt.ok) {
            return RenameAttemptOutcome(firstAttempt, requestedName)
        }

        val firstMessage = firstAttempt.message.lowercase()
        if (!firstMessage.contains("conflict") || sourceFolderUri.isBlank()) {
            return RenameAttemptOutcome(firstAttempt, requestedName)
        }

        // Build one in-memory name set to avoid repeated expensive SAF rename retries.
        val existingNames = storageProvider.listChildren(sourceFolderUri)
            .map { it.name.lowercase() }
            .toMutableSet()
        var suffix = 1
        var candidate = withNumericSuffix(requestedName, suffix)
        while (existingNames.contains(candidate.lowercase())) {
            suffix += 1
            if (suffix > 1024) {
                return RenameAttemptOutcome(firstAttempt, requestedName)
            }
            candidate = withNumericSuffix(requestedName, suffix)
        }

        val secondAttempt = storageProvider.rename(sourceUri, candidate, sourceParentUri)
        return RenameAttemptOutcome(secondAttempt, candidate)
    }

    private fun withNumericSuffix(name: String, index: Int): String {
        val ext = name.substringAfterLast('.', "")
        val base = if (ext.isBlank()) name else name.removeSuffix(".$ext")
        return if (ext.isBlank()) "$base ($index)" else "$base ($index).$ext"
    }

    private fun preserveImageExtension(requestedName: String, sourceName: String): String {
        val sourceExtension = sourceName.substringAfterLast('.', "").lowercase()
        if (sourceExtension !in imageExtensions) {
            return requestedName
        }
        val requestedExtension = requestedName.substringAfterLast('.', "").lowercase()
        val baseName = if (requestedExtension in imageExtensions) {
            requestedName.dropLast(requestedExtension.length + 1).trimEnd('.')
        } else {
            requestedName.trimEnd('.')
        }
        return "$baseName.$sourceExtension"
    }

    private fun executeRenameFolder(plan: FileOperationPlan): Map<String, Any> {
        val renamed = storageProvider.rename(plan.sourceUri, plan.targetName)
        if (!renamed.ok || renamed.uri.isNullOrBlank()) {
            return mapOf("ok" to false, "action" to plan.action, "message" to renamed.message)
        }
        val dbOk = repository.renameFolderAndLibraryUri(plan.sourceFolderUri, renamed.uri)
        if (!dbOk) {
            return mapOf("ok" to false, "action" to plan.action, "message" to "database update failed")
        }
        return mapOf("ok" to true, "action" to plan.action, "old_uri" to plan.sourceFolderUri, "new_uri" to renamed.uri)
    }

    private fun executeCreateFolder(plan: FileOperationPlan): Map<String, Any> {
        val created = storageProvider.createFolder(plan.targetFolderUri, plan.targetName)
        if (!created.ok || created.uri.isNullOrBlank()) {
            return mapOf("ok" to false, "action" to plan.action, "message" to created.message)
        }
        repository.registerFolder(created.uri, enabled = true)
        return mapOf("ok" to true, "action" to plan.action, "folder_uri" to created.uri, "changed" to created.changed)
    }

    private fun executeDeleteFolder(plan: FileOperationPlan): Map<String, Any> {
        val deleted = storageProvider.delete(plan.sourceUri)
        if (!deleted.ok) {
            return mapOf("ok" to false, "action" to plan.action, "message" to deleted.message)
        }
        repository.removeFolder(plan.sourceFolderUri)
        return mapOf("ok" to true, "action" to plan.action, "folder_uri" to plan.sourceFolderUri)
    }

    private fun executeDeleteImage(plan: FileOperationPlan, recordsById: Map<Int, LocalRepository.ImageRecord>): Map<String, Any> {
        val imageId = plan.imageId ?: return mapOf("ok" to false, "action" to plan.action, "message" to "image id missing")
        val record = recordsById[imageId] ?: repository.getImageRecordsByIds(listOf(imageId)).firstOrNull()
            ?: return mapOf("ok" to false, "action" to plan.action, "message" to "image not found")
        val deleted = storageProvider.delete(record.uri)
        val missingOnDisk = !deleted.ok && deleted.message.contains("not found", ignoreCase = true)
        if (!deleted.ok && !missingOnDisk) {
            return mapOf("ok" to false, "action" to plan.action, "image_id" to imageId, "message" to deleted.message)
        }
        val dbOk = repository.deleteImageAndThumbnail(imageId, record.uri)
        if (!dbOk) {
            return mapOf("ok" to false, "action" to plan.action, "image_id" to imageId, "message" to "database update failed")
        }
        return mapOf(
            "ok" to true,
            "action" to plan.action,
            "image_id" to imageId,
            "storage_changed" to deleted.ok,
            "storage_status" to if (missingOnDisk) "already_missing" else "deleted",
        )
    }

    private fun executeMoveImage(
        plan: FileOperationPlan,
        recordsById: Map<Int, LocalRepository.ImageRecord>,
    ): Map<String, Any> {
        val imageId = plan.imageId ?: return mapOf("ok" to false, "action" to plan.action, "message" to "image id missing")
        val record = recordsById[imageId] ?: repository.getImageRecordsByIds(listOf(imageId)).firstOrNull()
            ?: return mapOf("ok" to false, "action" to plan.action, "message" to "image not found")
        val finalName = plan.targetName
        val moved = storageProvider.move(record.uri, plan.targetFolderUri, finalName)
        if (!moved.ok || moved.uri.isNullOrBlank()) {
            return mapOf("ok" to false, "action" to plan.action, "image_id" to imageId, "message" to moved.message)
        }
        val dbOk = repository.updateImagePathAndClearThumbnails(
            imageId = imageId,
            newUri = moved.uri,
            newFilename = finalName,
            newFolderUri = plan.targetFolderUri,
            newParentUri = plan.targetFolderUri,
            newFolderName = FolderUriUtils.displayName(plan.targetFolderUri),
            newRelativePath = "$plan.targetFolderUri/$finalName",
            newModifiedAtMs = System.currentTimeMillis(),
            oldUri = record.uri,
        )
        if (!dbOk) {
            return mapOf("ok" to false, "action" to plan.action, "image_id" to imageId, "message" to "database update failed")
        }
        return mapOf("ok" to true, "action" to plan.action, "image_id" to imageId, "new_uri" to moved.uri)
    }

    private fun executeCopyImage(
        plan: FileOperationPlan,
        recordsById: Map<Int, LocalRepository.ImageRecord>,
    ): Map<String, Any> {
        val imageId = plan.imageId ?: return mapOf("ok" to false, "action" to plan.action, "message" to "image id missing")
        val record = recordsById[imageId] ?: repository.getImageRecordsByIds(listOf(imageId)).firstOrNull()
            ?: return mapOf("ok" to false, "action" to plan.action, "message" to "image not found")
        val finalName = plan.targetName
        val copied = storageProvider.copy(record.uri, plan.targetFolderUri, finalName)
        if (!copied.ok || copied.uri.isNullOrBlank()) {
            return mapOf("ok" to false, "action" to plan.action, "image_id" to imageId, "message" to copied.message)
        }
        val scannedAt = System.currentTimeMillis()
        val importOrder = scannedAt * 1_000_000L + imageId
        val newId = repository.insertCopiedImageRecord(
            source = record,
            newUri = copied.uri,
            newFilename = finalName,
            newFolderUri = plan.targetFolderUri,
            newParentUri = plan.targetFolderUri,
            newFolderName = FolderUriUtils.displayName(plan.targetFolderUri),
            newRelativePath = "$plan.targetFolderUri/$finalName",
            scannedAtMs = scannedAt,
            importOrder = importOrder,
        )
        return mapOf("ok" to (newId != null), "action" to plan.action, "image_id" to imageId, "copied_image_id" to (newId ?: 0), "new_uri" to copied.uri)
    }

    private fun inversePlan(plan: FileOperationPlan, result: Map<String, Any>): FileOperationPlan? {
        if (!plan.canUndo) {
            return null
        }
        return when (plan.action) {
            "rename_image", "batch_rename_images" -> FileOperationPlan(
                action = "rename_image",
                imageId = plan.imageId,
                sourceUri = result["new_uri"]?.toString().orEmpty(),
                sourceName = plan.targetName,
                sourceFolderUri = plan.sourceFolderUri,
                targetFolderUri = plan.sourceFolderUri,
                targetName = plan.sourceName,
                conflictMode = "rename",
            )
            "move_images" -> FileOperationPlan(
                action = "move_images",
                imageId = plan.imageId,
                sourceUri = result["new_uri"]?.toString().orEmpty(),
                sourceName = plan.sourceName,
                sourceFolderUri = plan.targetFolderUri,
                targetFolderUri = plan.sourceFolderUri,
                targetName = plan.sourceName,
                conflictMode = "rename",
            )
            "copy_images" -> {
                val copiedId = (result["copied_image_id"] as? Number)?.toInt()
                if (copiedId == null || copiedId <= 0) {
                    null
                } else {
                    FileOperationPlan(
                        action = "delete_images",
                        imageId = copiedId,
                        sourceUri = result["new_uri"]?.toString().orEmpty(),
                        sourceFolderUri = plan.targetFolderUri,
                        canUndo = false,
                    )
                }
            }
            "create_folder" -> FileOperationPlan(
                action = "delete_folder",
                sourceUri = result["folder_uri"]?.toString().orEmpty(),
                sourceFolderUri = result["folder_uri"]?.toString().orEmpty(),
                canUndo = false,
            )
            "rename_folder" -> FileOperationPlan(
                action = "rename_folder",
                sourceUri = result["new_uri"]?.toString().orEmpty(),
                sourceFolderUri = result["new_uri"]?.toString().orEmpty(),
                targetName = FolderUriUtils.displayName(plan.sourceFolderUri),
            )
            else -> null
        }
    }

    private fun parseImageIds(raw: Any?): List<Int> {
        return when (raw) {
            is List<*> -> raw.mapNotNull {
                when (it) {
                    is Number -> it.toInt()
                    else -> it?.toString()?.trim()?.toIntOrNull()
                }
            }
            else -> raw?.toString()
                ?.split(',', '|', ' ')
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?: emptyList()
        }.distinct()
    }

    private fun ensureInitialized() {
        check(initialized) { "StandaloneRuntime is not initialized" }
    }

    private suspend fun awaitRunningState(): Boolean {
        while (true) {
            val (paused, cancelled) = stateMutex.withLock {
                scanPaused to scanCancelled
            }
            if (cancelled) {
                return false
            }
            if (!paused) {
                return true
            }
            delay(50)
        }
    }

    private fun snapshotStatus(): Map<String, Any> {
        return mapOf(
            "status" to scanStatus,
            "root" to (scanRoot ?: ""),
            "discovered_images" to scanDiscovered,
            "skipped_entries" to scanSkipped,
            "current_file" to (scanCurrentFile ?: ""),
            "job_id" to "scan-library",
            "error" to (scanError ?: ""),
            "progress" to scanProgress,
        )
    }

    private fun String.isImageName(): Boolean {
        val idx = lastIndexOf('.')
        if (idx < 0 || idx == lastIndex) {
            return false
        }
        val ext = substring(idx + 1).lowercase()
        return ext in imageExtensions
    }

    private fun listLocalArtifacts(directory: File, kind: String): List<Map<String, Any>> {
        if (!directory.exists()) {
            return emptyList()
        }
        val files = directory.listFiles()?.sortedBy { it.name.lowercase() } ?: return emptyList()
        return files.map {
            mapOf(
                "type" to kind,
                "name" to it.name,
                "path" to it.absolutePath,
                "exists" to it.exists(),
                "size_bytes" to it.length(),
                "last_modified_ms" to it.lastModified(),
            )
        }
    }

    private fun importKnowledgePackDocument(uri: Uri): Map<String, Any> {
        val directory = knowledgePackDirectory()
        val filename = safeKnowledgePackFileName(uri)
        val destination = File(directory, filename)
        if (destination.exists()) {
            return mapOf("ok" to false, "message" to "Knowledge Pack already installed.", "filename" to filename)
        }
        val temporary = copyKnowledgePackToTemporaryFile(uri, directory)
            ?: return mapOf("ok" to false, "message" to "Unable to read selected Knowledge Pack.", "filename" to filename)
        try {
            val incomingHash = sha256Hex(temporary)
            val duplicate = directory.listFiles()
                ?.filter { it.isFile && !it.name.startsWith(".") }
                ?.firstOrNull { sha256Hex(it) == incomingHash }
            if (duplicate != null) {
                return mapOf("ok" to false, "message" to "Knowledge Pack already installed.", "filename" to filename)
            }
            if (!temporary.renameTo(destination)) {
                return mapOf("ok" to false, "message" to "Unable to install Knowledge Pack.", "filename" to filename)
            }
            return mapOf("ok" to true, "pack" to describeKnowledgePack(destination))
        } finally {
            if (temporary.exists()) {
                temporary.delete()
            }
        }
    }

    private fun knowledgePackDirectory(): File {
        return File(appContext.filesDir, "knowledge_packs").also { directory ->
            require(directory.exists() || directory.mkdirs()) { "Unable to prepare Knowledge Pack storage." }
        }
    }

    private fun findKnowledgePack(filename: String): File? {
        val cleanName = filename.trim()
        if (cleanName.isBlank() || cleanName != File(cleanName).name) {
            return null
        }
        return File(knowledgePackDirectory(), cleanName).takeIf { it.isFile }
    }

    private fun persistReferenceKnowledgeArtifact(filename: String, raw: String): File {
        val directory = knowledgePackDirectory()
        val safeName = filename
            .trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "reference-knowledge.json" }
            .let { if (it.lowercase().endsWith(".json")) it else "$it.json" }
        val target = File(directory, safeName)
        val temporary = File(directory, ".$safeName.tmp")
        temporary.writeText(raw, Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            temporary.delete()
            error("Unable to replace stored Knowledge reference '$safeName'.")
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            error("Unable to persist Knowledge reference '$safeName'.")
        }
        return target
    }

    private fun copyKnowledgePackToTemporaryFile(uri: Uri, directory: File): File? {
        val temporary = File.createTempFile(".pack-", ".tmp", directory)
        return runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use(input::copyTo)
            } ?: return null
            temporary
        }.getOrElse {
            temporary.delete()
            null
        }
    }

    private fun isJsonKnowledgeDocument(uri: Uri, filename: String): Boolean {
        if (filename.lowercase().endsWith(".json")) return true
        val mime = runCatching {
            appContext.contentResolver.getType(uri)?.trim()?.lowercase().orEmpty()
        }.getOrDefault("")
        return mime.contains("json") || mime.startsWith("text/")
    }

    private fun readKnowledgeDocumentText(uri: Uri): String? {
        return runCatching {
            appContext.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                reader.readText()
            }
        }.getOrNull()
    }

    private fun safeKnowledgePackFileName(uri: Uri): String {
        val resolver = appContext.contentResolver
        val displayName = runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
        }.getOrNull()?.trim().orEmpty()

        val fallback = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        val candidate = displayName.ifBlank { fallback }
        val sanitized = candidate.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return sanitized.ifBlank {
            if (isZipKnowledgeDocument(uri, "")) "knowledge-release.zip" else "knowledge-pack.json"
        }
    }

    private fun isZipKnowledgeDocument(uri: Uri, filename: String): Boolean {
        if (filename.trim().lowercase().endsWith(".zip")) {
            return true
        }

        val mime = runCatching {
            appContext.contentResolver.getType(uri)?.trim()?.lowercase().orEmpty()
        }.getOrDefault("")
        if (mime in setOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/x-zip",
            )
        ) {
            return true
        }

        return runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                val signature = ByteArray(4)
                val count = input.read(signature)
                if (count < 4) {
                    false
                } else {
                    signature[0] == 0x50.toByte() &&
                        signature[1] == 0x4B.toByte() &&
                        (
                            (signature[2] == 0x03.toByte() && signature[3] == 0x04.toByte()) ||
                                (signature[2] == 0x05.toByte() && signature[3] == 0x06.toByte()) ||
                                (signature[2] == 0x07.toByte() && signature[3] == 0x08.toByte())
                            )
                }
            } ?: false
        }.getOrDefault(false)
    }

    private fun describeKnowledgePack(file: File): Map<String, Any> {
        val rawReference = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        if (
            rawReference != null &&
            ReferenceKnowledgeParser.looksLikeReferenceDocument(file.name, rawReference)
        ) {
            val parsed = runCatching {
                ReferenceKnowledgeParser.parseDocuments(mapOf(file.name to rawReference))
            }.getOrNull()
            if (parsed != null) {
                val entryCount = parsed.series.size + parsed.tags.size + parsed.characters.size
                return mapOf(
                    "type" to "knowledge_pack",
                    "name" to file.name,
                    "filename" to file.name,
                    "pack_name" to file.nameWithoutExtension,
                    "pack_id" to "reference:${sha256Hex(file).take(16)}",
                    "version" to "reference-json",
                    "status" to "Installed",
                    "path" to file.absolutePath,
                    "exists" to file.exists(),
                    "size_bytes" to file.length(),
                    "last_modified_ms" to file.lastModified(),
                    "metadata" to mapOf(
                        "knowledge_type" to "reference_json",
                        "entries" to entryCount,
                        "series_entries" to parsed.series.size,
                        "tag_entries" to parsed.tags.size,
                        "character_entries" to parsed.characters.size,
                        "sha256" to sha256Hex(file),
                    ),
                )
            }
        }

        val pack = describeKnowledgePackOrNull(file)
            ?: return mapOf(
                "type" to "knowledge_pack",
                "name" to file.name,
                "filename" to file.name,
                "status" to "Invalid",
                "size_bytes" to file.length(),
                "metadata" to mapOf("sha256" to sha256Hex(file)),
            )
        return mapOf(
            "type" to "knowledge_pack",
            "name" to file.name,
            "filename" to file.name,
            "pack_name" to pack.packName,
            "pack_id" to pack.packId,
            "version" to pack.version,
            "status" to "Installed",
            "path" to file.absolutePath,
            "exists" to file.exists(),
            "size_bytes" to file.length(),
            "last_modified_ms" to file.lastModified(),
            "metadata" to mapOf(
                "author" to pack.author,
                "creation_date" to pack.creationDate,
                "knowledge_type" to pack.knowledgeType,
                "entries" to pack.entries,
                "supported_categories" to pack.supportedCategories,
                "dependencies" to pack.dependencies,
                "description" to pack.description,
                "sha256" to pack.contentHash,
            ),
        )
    }

    private data class KnowledgePackDescriptor(
        val filename: String,
        val packId: String,
        val packName: String,
        val version: String,
        val author: String,
        val creationDate: String,
        val knowledgeType: String,
        val entries: Int,
        val supportedCategories: List<String>,
        val dependencies: List<String>,
        val description: String,
        val contentHash: String,
        val ok: Boolean = true,
        val error: String? = null,
    )

    private fun validateKnowledgePack(file: File, filename: String): KnowledgePackDescriptor {
        return runCatching {
            val parsed = knowledgeRepository.parseAndValidate(file.readText())
            KnowledgePackDescriptor(
                filename = filename,
                packId = parsed.packId,
                packName = parsed.packName.ifBlank { parsed.packId },
                version = parsed.version,
                author = parsed.author.ifBlank { "Unknown" },
                creationDate = parsed.creationDate.ifBlank { "Unknown" },
                knowledgeType = parsed.knowledgeType.ifBlank { "Unknown" },
                entries = parsed.entries.size,
                supportedCategories = parsed.supportedCategories.ifEmpty { parsed.entries.flatMap { it.categories }.distinct().sorted() },
                dependencies = parsed.dependencies,
                description = parsed.description,
                contentHash = sha256Hex(file),
            )
        }.getOrElse { error ->
            KnowledgePackDescriptor(
                filename = filename,
                packId = "",
                packName = "",
                version = "",
                author = "",
                creationDate = "",
                knowledgeType = "",
                entries = 0,
                supportedCategories = emptyList(),
                dependencies = emptyList(),
                description = "",
                contentHash = "",
                ok = false,
                error = error.message ?: "Knowledge Pack validation failed.",
            )
        }
    }

    private fun duplicateKnowledgePack(
        incoming: KnowledgePackDescriptor,
        installed: List<KnowledgePackDescriptor>,
    ): String? {
        installed.firstOrNull { it.ok && it.packId == incoming.packId }?.let { existing ->
            return if (existing.version == incoming.version) {
                "Knowledge Pack ID and version already installed."
            } else {
                "Knowledge Pack ID already installed."
            }
        }
        installed.firstOrNull { it.ok && it.contentHash == incoming.contentHash }?.let {
            return "Knowledge Pack content already installed."
        }
        return null
    }

    private fun availableKnowledgePackFile(directory: File, requestedName: String): File {
        val normalized = requestedName.ifBlank { "knowledge-pack.json" }
        val stem = normalized.substringBeforeLast('.', normalized)
        val extension = normalized.substringAfterLast('.', "json")
        var candidate = File(directory, normalized)
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(directory, "$stem-$suffix.$extension")
            suffix += 1
        }
        return candidate
    }

    private fun refreshKnowledgePackReferences() {
        listKnowledgePacks()
    }

    private fun describeKnowledgePackOrNull(file: File): KnowledgePackDescriptor? {
        return validateKnowledgePack(file, file.name).takeIf { it.ok }
    }

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (index in 0 until length()) {
            optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private fun JSONArray.categories(): List<String> = buildSet {
        for (index in 0 until length()) {
            optJSONObject(index)?.optJSONArray("categories")?.toStringList()?.let(::addAll)
        }
    }.sorted()

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun extractMetadata(node: StorageNode): ScanMetadata {
        val size = node.sizeBytes
        val modified = node.lastModifiedMs
        val created = modified
        val extension = node.name.substringAfterLast('.', "").lowercase()
        val mimeType = mimeTypeFromExtension(extension)
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        storageProvider.openInputStream(node.uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        val width = bounds.outWidth.takeIf { it > 0 }
        val height = bounds.outHeight.takeIf { it > 0 }
        val resolution = if (width != null && height != null) "${width}x${height}" else null
        val aspectRatio = if (width != null && height != null && height != 0) width.toDouble() / height.toDouble() else null
        val orientation = if (width != null && height != null) {
            when {
                width > height -> "landscape"
                width < height -> "portrait"
                else -> "square"
            }
        } else {
            null
        }
        val folderName = FolderUriUtils.displayName(node.parentUri.orEmpty())
        val relativePath = node.parentUri?.let { parent ->
            val parentName = parent.substringAfterLast('/', "")
            if (parentName.isBlank()) {
                node.name
            } else {
                "$parentName/${node.name}"
            }
        } ?: node.name
        return ScanMetadata(
            width = width,
            height = height,
            createdAtMs = created,
            modifiedAtMs = modified,
            sizeBytes = size,
            extension = extension,
            mimeType = mimeType,
            resolution = resolution,
            aspectRatio = aspectRatio,
            orientation = orientation,
            folderName = folderName,
            relativePath = relativePath,
            indexedAtMs = System.currentTimeMillis(),
        )
    }

    private fun buildMetadataText(node: StorageNode, metadata: ScanMetadata): String {
        val segments = mutableListOf<String>()
        segments += node.name
        segments += node.uri
        node.parentUri?.let { segments += it }
        metadata.width?.let { segments += "width:$it" }
        metadata.height?.let { segments += "height:$it" }
        metadata.resolution?.let { segments += "resolution:$it" }
        metadata.orientation?.let { segments += "orientation:$it" }
        if (metadata.extension.isNotBlank()) {
            segments += "extension:${metadata.extension}"
        }
        if (metadata.mimeType.isNotBlank()) {
            segments += "mime:${metadata.mimeType}"
        }
        metadata.sizeBytes?.let { segments += "size:$it" }
        metadata.modifiedAtMs?.let { segments += "modified:$it" }
        if (metadata.folderName.isNotBlank()) {
            segments += "folder:${metadata.folderName}"
        }
        if (metadata.relativePath.isNotBlank()) {
            segments += "relative:${metadata.relativePath}"
        }
        return segments.joinToString(" ")
    }

    private fun Map<String, Any>.toQueryOptions(): LibraryQueryOptions {
        val tags = (this["tags"] as? List<*>)
            ?.mapNotNull { it?.toString() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val taxonomy = (this["taxonomy_filters"] as? Map<*, *>)
            ?.entries
            ?.mapNotNull { (k, v) ->
                val key = k?.toString()?.trim().orEmpty()
                val value = v?.toString()?.trim().orEmpty()
                if (key.isBlank() || value.isBlank()) null else key to value
            }
            ?.toMap()
            ?: emptyMap()

        return LibraryQueryOptions(
            query = this["query"]?.toString(),
            imageId = (this["image_id"] as? Number)?.toInt() ?: this["image_id"]?.toString()?.toIntOrNull(),
            fullText = this["full_text"]?.toString(),
            sortBy = this["sort_by"]?.toString()?.ifBlank { "import_order" } ?: "import_order",
            sortDirection = this["sort_direction"]?.toString()?.ifBlank { "desc" } ?: "desc",
            page = (this["page"] as? Number)?.toInt() ?: this["page"]?.toString()?.toIntOrNull() ?: 1,
            pageSize = (this["page_size"] as? Number)?.toInt() ?: this["page_size"]?.toString()?.toIntOrNull() ?: 0,
            collection = this["collection"]?.toString(),
            tags = tags,
            minWidth = (this["min_width"] as? Number)?.toInt() ?: this["min_width"]?.toString()?.toIntOrNull(),
            minHeight = (this["min_height"] as? Number)?.toInt() ?: this["min_height"]?.toString()?.toIntOrNull(),
            fileFormat = this["file_format"]?.toString(),
            orientation = this["orientation"]?.toString(),
            folderQuery = this["folder_query"]?.toString(),
            includeHidden = when (val raw = this["include_hidden"]) {
                is Boolean -> raw
                is Number -> raw.toInt() != 0
                else -> raw?.toString()?.equals("true", ignoreCase = true) == true
            },
            missingOnly = when (val raw = this["missing_only"]) {
                is Boolean -> raw
                is Number -> raw.toInt() != 0
                else -> raw?.toString()?.equals("true", ignoreCase = true) == true
            },
            taxonomyFilters = taxonomy,
            includeInactive = when (val raw = this["include_inactive"]) {
                is Boolean -> raw
                is Number -> raw.toInt() != 0
                else -> raw?.toString()?.equals("true", ignoreCase = true) == true
            },
        )
    }

    private fun parseFusionImportFormat(raw: String): FusionImportFormat {
        val normalized = raw.trim().lowercase()
        return when (normalized) {
            "json" -> FusionImportFormat.JSON
            "workbook", "workbook_compat", "workbook-compatible", "workbook_compatible" -> FusionImportFormat.WORKBOOK_COMPAT
            else -> FusionImportFormat.JSON
        }
    }

    private fun parseExternalImportSourceType(raw: String): ExternalImportSourceType {
        val normalized = raw.trim().lowercase()
        return when (normalized) {
            "json", "fusion_json" -> ExternalImportSourceType.JSON
            "workbook", "workbook_compat", "workbook-compatible", "workbook_compatible", "workbook_compat_json" -> {
                ExternalImportSourceType.WORKBOOK_COMPAT_JSON
            }
            "csv" -> ExternalImportSourceType.CSV
            "sqlite", "sqlite3", "db" -> ExternalImportSourceType.SQLITE
            else -> ExternalImportSourceType.JSON
        }
    }

    private fun parseExternalConflictStrategy(raw: String): ExternalImportConflictStrategy {
        val normalized = raw.trim().lowercase()
        return when (normalized) {
            "skip" -> ExternalImportConflictStrategy.SKIP
            "overwrite" -> ExternalImportConflictStrategy.OVERWRITE
            "keep_both", "keep-both", "keepboth" -> ExternalImportConflictStrategy.KEEP_BOTH
            "rename_imported", "rename-imported", "renameimported" -> ExternalImportConflictStrategy.RENAME_IMPORTED
            "merge_metadata", "merge-metadata", "mergemetadata" -> ExternalImportConflictStrategy.MERGE_METADATA
            else -> ExternalImportConflictStrategy.MERGE_METADATA
        }
    }

    private fun parseAliasHints(raw: Any?): Map<String, Map<String, String>> {
        val source = raw as? Map<*, *> ?: return emptyMap()
        val result = mutableMapOf<String, Map<String, String>>()
        source.forEach { (categoryRaw, mappingRaw) ->
            val category = categoryRaw?.toString()?.trim().orEmpty()
            if (category.isBlank()) {
                return@forEach
            }
            val mapping = mappingRaw as? Map<*, *> ?: return@forEach
            val normalizedMapping = mutableMapOf<String, String>()
            mapping.forEach { (aliasRaw, idRaw) ->
                val alias = aliasRaw?.toString()?.trim().orEmpty()
                val id = idRaw?.toString()?.trim().orEmpty()
                if (alias.isNotBlank() && id.isNotBlank()) {
                    normalizedMapping[alias] = id
                }
            }
            result[category] = normalizedMapping
        }
        return result
    }

    private fun parseBooleanFlag(raw: Any?, defaultValue: Boolean): Boolean {
        return when (raw) {
            null -> defaultValue
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            else -> raw.toString().trim().equals("true", ignoreCase = true)
        }
    }

    private fun Map<String, Any>.toExternalImportRequest(): ExternalImportRequest {
        val importId = this["import_id"]?.toString()?.trim()?.ifBlank { UUID.randomUUID().toString() }
            ?: UUID.randomUUID().toString()
        val rawSourceType = this["source_type"]?.toString()
            ?: this["sourceType"]?.toString()
            ?: this["format"]?.toString()
            ?: "json"
        val source = this["source"]?.toString()
            ?: this["payload"]?.toString()
            ?: ""
        require(source.isNotBlank()) { "Import source is required." }

        val conflictRaw = this["conflict_strategy"]?.toString()
            ?: this["conflictStrategy"]?.toString()
            ?: "merge_metadata"

        val replaceExisting = parseBooleanFlag(
            this["replace_existing"] ?: this["replaceExisting"],
            defaultValue = false,
        )
        val csvTableName = this["csv_table_name"]?.toString() ?: this["csvTableName"]?.toString()
        val sourceUri = this["source_uri"]?.toString()?.trim().orEmpty()
        val aliasHints = parseAliasHints(this["alias_hints"] ?: this["aliasHints"])

        return ExternalImportRequest(
            importId = importId,
            sourceType = parseExternalImportSourceType(rawSourceType),
            source = source,
            sourceUri = sourceUri,
            conflictStrategy = parseExternalConflictStrategy(conflictRaw),
            replaceExisting = replaceExisting,
            csvTableName = csvTableName,
            aliasHints = aliasHints,
        )
    }

    private fun imageSummary(image: Map<String, Any>?): String {
        if (image == null) {
            return "null"
        }
        val metadata = image["metadata"] as? Map<*, *>
        val tags = when (val nested = metadata?.get("tags")) {
            is List<*> -> nested.mapNotNull { it?.toString() }.joinToString("|")
            is String -> nested
            else -> ""
        }
        return "id=${image["image_id"]} tags=$tags"
    }

    private fun Map<String, Any>.toSemanticCandidatePayloadOrNull(): Map<String, Any>? {
        val imageId = this["image_id"].toIntOrNullValue() ?: return null
        val metadata = this["metadata"] as? Map<*, *>
        val tags = when (val nested = metadata?.get("tags")) {
            is List<*> -> nested.mapNotNull { it?.toString()?.trim() }.filter { it.isNotBlank() }
            is String -> nested.split(',', '|', ';').map { it.trim() }.filter { it.isNotBlank() }
            else -> ""
        }
        val tagsText = when (tags) {
            is List<*> -> tags.joinToString(" ")
            is String -> tags
            else -> ""
        }
        val taxonomyText = metadata?.get("taxonomy_text")?.toString().orEmpty()
        val metadataText = metadata?.get("metadata_text")?.toString().orEmpty()
        val folderName = metadata?.get("folder_name")?.toString().orEmpty()
        val relativePath = metadata?.get("relative_path")?.toString().orEmpty()

        val text = listOf(
            this["filename"]?.toString().orEmpty(),
            this["path"]?.toString().orEmpty(),
            folderName,
            relativePath,
            tagsText,
            taxonomyText,
            metadataText,
        ).filter { it.isNotBlank() }.joinToString(" ")

        return mapOf(
            "image_id" to imageId,
            "text" to text,
            "filename" to this["filename"]?.toString().orEmpty(),
            "path" to this["path"]?.toString().orEmpty(),
            "metadata" to (metadata?.toStringAnyMap() ?: emptyMap<String, Any>()),
            "tags" to (if (tags is List<*>) tags else emptyList<String>()),
        )
    }

    private fun materializeRemoteImageForAi(
        imageId: Int,
        sourceUri: String,
        filename: String,
    ): File? {
        if (!TeraBoxUris.isTeraBox(sourceUri)) return null
        val extension = filename.substringAfterLast('.', "").lowercase().take(10)
        val suffix = extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
        val directory = File(appContext.cacheDir, "remote_ai").apply { mkdirs() }
        val target = File(directory, "image_${imageId}_${System.currentTimeMillis()}$suffix")
        return runCatching {
            storageProvider.openInputStream(sourceUri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (!target.exists() || target.length() <= 0L) {
                target.delete()
                null
            } else {
                target
            }
        }.getOrElse {
            target.delete()
            null
        }
    }

    private fun cleanupPreparedRemoteImage(payload: Map<String, Any>) {
        val staged = payload["_asterion_staged_remote_path"]?.toString()?.trim().orEmpty()
        if (staged.isBlank()) return
        runCatching {
            val file = File(staged)
            if (file.exists() && file.parentFile?.name == "remote_ai") {
                file.delete()
            }
        }
    }

    private fun prepareAiPipelinePayload(payload: Map<String, Any>): Map<String, Any> {
        val prepared = linkedMapOf<String, Any>()
        prepared.putAll(payload)

        val imageId = prepared["image_id"].toIntOrNullValue()
        if (imageId != null) {
            val image = repository.searchByImageId(imageId)
            if (image != null) {
                prepared.putIfAbsent("image_id", imageId)
                prepared.putIfAbsent("filename", image["filename"]?.toString().orEmpty())
                prepared.putIfAbsent("path", image["path"]?.toString().orEmpty())
                prepared.putIfAbsent("uri", image["uri"]?.toString().orEmpty())

                val originalUri = image["uri"]?.toString().orEmpty()
                if (TeraBoxUris.isTeraBox(originalUri)) {
                    val staged = materializeRemoteImageForAi(
                        imageId = imageId,
                        sourceUri = originalUri,
                        filename = image["filename"]?.toString().orEmpty(),
                    )
                    if (staged != null) {
                        prepared["original_uri"] = originalUri
                        prepared["remote_source_uri"] = originalUri
                        prepared["image_uri"] = staged.absolutePath
                        prepared["uri"] = staged.absolutePath
                        prepared["path"] = staged.absolutePath
                        prepared["source_path"] = staged.absolutePath
                        prepared["image_path"] = staged.absolutePath
                        prepared["file_path"] = staged.absolutePath
                        prepared["_asterion_staged_remote_path"] = staged.absolutePath
                    }
                }

                val metadata = (image["metadata"] as? Map<*, *>)?.toStringAnyMap() ?: emptyMap()
                if (metadata.isNotEmpty() && (prepared["metadata"] as? Map<*, *>) == null) {
                    prepared["metadata"] = metadata
                }
                if (prepared["tags"] !is List<*> && metadata["tags"] != null) {
                    prepared["tags"] = metadata["tags"].toStringList()
                }
            }
        }

        val normalizedTaskType = extractPipelineTaskType(prepared)
        if (normalizedTaskType in setOf("similarity_search", "duplicate_detection")) {
            val existingCandidates = (prepared["candidates"] as? List<*>)
                ?.mapNotNull { (it as? Map<*, *>)?.toStringAnyMap() }
                ?: emptyList()
            if (existingCandidates.isEmpty()) {
                val candidateLimit = prepared["candidate_limit"].toIntOrNullValue()?.coerceIn(1, 2_000)
                    ?: if (normalizedTaskType == "duplicate_detection") 800 else 600
                val candidateImageIds = (prepared["image_ids"] as? List<*>)
                    ?.mapNotNull { it.toIntOrNullValue() }
                    ?.distinct()
                    ?: emptyList()

                val candidateRows = if (candidateImageIds.isNotEmpty()) {
                    candidateImageIds.mapNotNull { id -> repository.searchByImageId(id) }
                } else {
                    val options = prepared.toQueryOptions().copy(
                        query = null,
                        fullText = null,
                        sortBy = "import_order",
                        sortDirection = "desc",
                        page = 1,
                        pageSize = 0,
                    )
                    repository.searchImages(options)
                }
                prepared["candidates"] = candidateRows.take(candidateLimit).mapNotNull { row -> row.toSemanticCandidatePayloadOrNull() }
            }
        }

        return prepared
    }

    private fun applyPipelineSideEffects(requestPayload: Map<String, Any>, response: Map<String, Any>) {
        val ok = parseBooleanFlag(response["ok"], defaultValue = false)
        if (!ok) {
            return
        }

        val taskType = extractPipelineTaskType(requestPayload)
        if (taskType != "tag_prediction") {
            return
        }

        val imageId = requestPayload["image_id"].toIntOrNullValue() ?: return
        val rawTags = extractPredictedTags(response)
        if (rawTags.isEmpty()) return

        val canonical = rawTags.mapNotNull { raw ->
            knowledgeDatabase.resolveTag(raw)?.let { tag ->
                CanonicalTagObservation(
                    tagId = tag.id,
                    tagName = tag.name,
                    scope = "illustration",
                    confidence = 0.80,
                    source = "vision",
                )
            }
        }.distinctBy(CanonicalTagObservation::tagId)
        if (canonical.isEmpty()) return

        resolutionStore.replaceCanonicalTags(imageId, canonical)
        repository.setTags(imageId, canonical.map(CanonicalTagObservation::tagName))
    }

    private fun extractPredictedTags(response: Map<String, Any>): List<String> {
        val result = (response["result"] as? Map<*, *>)?.toStringAnyMap() ?: emptyMap()
        val direct = result["tags"].toStringList()
        if (direct.isNotEmpty()) {
            return direct
        }

        val rawResult = (response["raw_result"] as? Map<*, *>)?.toStringAnyMap() ?: emptyMap()
        val nested = (rawResult["result"] as? Map<*, *>)?.toStringAnyMap() ?: emptyMap()
        return nested["tags"].toStringList()
    }

    private fun extractPipelineTaskType(payload: Map<String, Any>): String {
        val raw = payload["task_type"]?.toString()?.trim().orEmpty()
            .ifBlank { payload["pipeline_type"]?.toString()?.trim().orEmpty() }
        return raw
            .lowercase()
            .replace('-', '_')
            .replace(' ', '_')
            .replace(Regex("_+"), "_")
    }

    private fun extractSemanticMatches(result: Map<String, Any>): List<Map<String, Any>> {
        val direct = (result["matches"] as? List<*>)
            ?.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                map.toStringAnyMap()
            }
            ?: emptyList()
        if (direct.isNotEmpty()) {
            return direct
        }

        val nested = (result["result"] as? Map<*, *>)?.toStringAnyMap() ?: return emptyList()
        return (nested["matches"] as? List<*>)
            ?.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                map.toStringAnyMap()
            }
            ?: emptyList()
    }

    private fun Map<String, Any>.resultMap(): Map<String, Any> {
        return (this["result"] as? Map<*, *>)?.toStringAnyMap() ?: this
    }

    private fun Map<*, *>.toStringAnyMap(): Map<String, Any> {
        val result = linkedMapOf<String, Any>()
        this.forEach { (keyRaw, value) ->
            val key = keyRaw?.toString()?.trim().orEmpty()
            if (key.isBlank() || value == null) {
                return@forEach
            }
            result[key] = value
        }
        return result
    }

    private fun Any?.toIntOrNullValue(): Int? {
        return when (this) {
            is Number -> this.toInt()
            else -> this?.toString()?.toIntOrNull()
        }
    }

    private fun Any?.toDoubleOrNullValue(): Double? {
        return when (this) {
            is Number -> this.toDouble()
            else -> this?.toString()?.toDoubleOrNull()
        }
    }

    private fun Any?.toStringList(): List<String> {
        return when (this) {
            is List<*> -> this.mapNotNull { it?.toString()?.trim() }.filter { it.isNotBlank() }
            is String -> this.split(',', '|', ';').map { it.trim() }.filter { it.isNotBlank() }
            else -> emptyList()
        }
    }

    private fun mimeTypeFromExtension(extension: String): String {
        return when (extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "avif" -> "image/avif"
            "heif", "heic" -> "image/heic"
            "tif", "tiff" -> "image/tiff"
            else -> "application/octet-stream"
        }
    }

    private fun cleanupLegacySettings() {
        val prefs = appContext.getSharedPreferences("ailm_android", Context.MODE_PRIVATE)
        if (prefs.contains("backend_url")) {
            prefs.edit().remove("backend_url").apply()
        }
    }
}
