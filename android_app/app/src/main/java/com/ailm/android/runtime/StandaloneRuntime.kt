package com.ailm.android.runtime

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.os.SystemClock
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
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
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
    private const val DIAGNOSTIC_PREFS = "ailm_diagnostic_display"
    private const val DIAGNOSTIC_CUTOFF_KEY = "hide_completed_before_ms"
    private const val AUTOMATION_STATUS_KEY = "status"
    private const val AUTOMATION_TOTAL_KEY = "total"
    private const val AUTOMATION_PROCESSED_KEY = "processed"
    private const val AUTOMATION_FAILED_KEY = "failed"
    private const val AUTOMATION_REVIEW_KEY = "review"
    private const val AUTOMATION_SKIPPED_KEY = "skipped"
    private const val AUTOMATION_CURRENT_IMAGE_KEY = "current_image_id"
    private const val AUTOMATION_MESSAGE_KEY = "message"
    private const val AUTOMATION_LAST_TOTAL_MS = "last_image_total_ms"
    private const val AUTOMATION_LAST_QWEN_MS = "last_image_qwen_ms"
    private const val AUTOMATION_LAST_TIMED_IMAGE = "last_timed_image_id"
    private const val AUTOMATION_UPDATED_AT_KEY = "updated_at_ms"
    private const val AUTOMATION_PAUSE_REQUESTED_KEY = "pause_requested"
    private const val AUTOMATION_LEGACY_REVIEW_REPAIR_V1_KEY = "legacy_review_repair_v1_done"
    private const val AUTOMATION_CURSOR_ORDER_KEY = "cursor_imported_order"
    private const val AUTOMATION_CURSOR_IMAGE_KEY = "cursor_image_id"
    private const val AUTOMATION_PROBED_KEY = "probe_completed"
    private const val AUTOMATION_FORCE_ALL_KEY = "force_all"

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
    private val thumbnailWritesInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val thumbnailProviderSlots = java.util.concurrent.Semaphore(3, true)

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
    private val automationFolderCache = mutableMapOf<String, String>()
    private val automationRoutingLock = Any()

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
                    // Existing indexed art is warmed on upgrade/launch, not
                    // only after a tile has already been displayed.
                    runtimeScope.launch(Dispatchers.IO) {
                        runCatching { scheduleArtworkPreviewWarmup() }
                            .onFailure { Log.w("AsterionPreviewWarmup", "Cannot schedule preview warming", it) }
                    }
                    runtimeScope.launch(Dispatchers.IO) {
                        runCatching { reconcileInterruptedRelocations() }
                            .onFailure { Log.w("AsterionRelocation", "Recovery review failed", it) }
                    }
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
        val thumbnails = repository.thumbnailStatistics()
        val status = runBlocking {
            stateMutex.withLock { scanStatus }
        }
        val scanRuns = repository.scanStatistics(limit = 20)
        val folders = repository.listFolders(includeDisabled = true)
        return mapOf(
            "total_images" to (stats["total_images"] ?: 0),
            "total_size_bytes" to (stats["total_size_bytes"] ?: 0L),
            "total_folders" to folders.size,
            "thumbnail_committed" to (thumbnails["thumbnail_committed"] ?: 0),
            "thumbnail_pending" to (thumbnails["thumbnail_pending"] ?: 0),
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
                val scanClock = SystemClock.elapsedRealtime()
                var metadataElapsedMs = 0L
                var sqliteElapsedMs = 0L
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
                    val metadataClock = SystemClock.elapsedRealtime()
                    val metadata = extractMetadata(node)
                    metadataElapsedMs += SystemClock.elapsedRealtime() - metadataClock
                    val sqliteClock = SystemClock.elapsedRealtime()
                    val upsert = repository.upsertImage(
                        node = node,
                        folderUri = rootUri,
                        scannedAtMs = scannedAt,
                        importOrder = scannedAt * 1_000_000L + importIndex,
                        metadata = metadata,
                        metadataText = buildMetadataText(node, metadata),
                    )
                    sqliteElapsedMs += SystemClock.elapsedRealtime() - sqliteClock
                    discoveredLocal += 1
                    if (discoveredLocal % 16 == 0) {
                        repository.updateScanProgress(scanId, discoveredLocal, skippedLocal)
                        val elapsed = SystemClock.elapsedRealtime() - scanClock
                        Log.i("AsterionScanTiming", "folder=$rootUri count=$discoveredLocal total_ms=$elapsed metadata_ms=$metadataElapsedMs sqlite_ms=$sqliteElapsedMs provider_enumeration_ms=${(elapsed-metadataElapsedMs-sqliteElapsedMs).coerceAtLeast(0L)}")
                    }

                    stateMutex.withLock {
                        scanDiscovered += 1
                        scanSkipped = skippedLocal
                        scanCurrentFile = node.uri
                        scanProgress = if (scanDiscovered < 1) 0.0 else minOf(99.0, 5.0 + (scanDiscovered * 0.5))
                    }
                }

                // A cloud scan may be incomplete; preserve indexed records and previews.
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
                scheduleArtworkPreviewWarmup()
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
        if (ReferenceKnowledgeParser.looksLikeReferenceDocument(filename, raw)) {
            return runCatching {
                val parsed = ReferenceKnowledgeParser.parseDocuments(mapOf(filename to raw))
                mapOf(
                    "ok" to true,
                    "message" to "Reference Knowledge JSON preview succeeded.",
                    "filename" to filename,
                    "pack_id" to "reference-json",
                    "pack_name" to filename.substringBeforeLast('.', filename),
                    "version" to "reference-json",
                    "knowledge_type" to "reference_json",
                    "entries" to (parsed.series.size + parsed.tags.size + parsed.characters.size),
                    "series_entries" to parsed.series.size,
                    "tag_entries" to parsed.tags.size,
                    "character_entries" to parsed.characters.size,
                )
            }.getOrElse { error ->
                mapOf(
                    "ok" to false,
                    "message" to (error.message ?: "Reference Knowledge JSON preview failed."),
                    "filename" to filename,
                )
            }
        }
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
        if (ReferenceKnowledgeParser.looksLikeReferenceDocument(filename, raw)) {
            return runCatching {
                val parsed = ReferenceKnowledgeParser.parseDocuments(mapOf(filename to raw))
                mapOf(
                    "ok" to true,
                    "message" to "Reference Knowledge JSON validation succeeded.",
                    "filename" to filename,
                    "pack_id" to "reference-json",
                    "pack_name" to filename.substringBeforeLast('.', filename),
                    "version" to "reference-json",
                    "knowledge_type" to "reference_json",
                    "entries" to (parsed.series.size + parsed.tags.size + parsed.characters.size),
                    "series_entries" to parsed.series.size,
                    "tag_entries" to parsed.tags.size,
                    "character_entries" to parsed.characters.size,
                )
            }.getOrElse { error ->
                mapOf(
                    "ok" to false,
                    "message" to (error.message ?: "Reference Knowledge JSON validation failed."),
                    "filename" to filename,
                )
            }
        }
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

    /**
     * Hide completed historical task diagnostics on demand without deleting
     * model tasks, their dependencies, or evidentiary execution history.
     * Running, paused and pending tasks always remain visible.
     */
    fun clearFinishedAiDiagnostics(): Map<String, Any> {
        ensureInitialized()
        val cutoff = System.currentTimeMillis()
        val persisted = appContext.getSharedPreferences(DIAGNOSTIC_PREFS, Context.MODE_PRIVATE)
            .edit().putLong(DIAGNOSTIC_CUTOFF_KEY, cutoff).commit()
        return mapOf("ok" to persisted, "hidden_before_ms" to cutoff,
            "message" to if (persisted) "Completed diagnostics hidden; execution records and automation progress preserved."
            else "Could not save diagnostics visibility.")
    }

    private fun diagnosticCutoff(): Long =
        appContext.getSharedPreferences(DIAGNOSTIC_PREFS, Context.MODE_PRIVATE)
            .getLong(DIAGNOSTIC_CUTOFF_KEY, 0L)

    fun listAiTasks(limit: Int = 200): List<Map<String, Any>> {
        ensureInitialized()
        val cutoff = diagnosticCutoff()
        return localAiManager.listTasks(limit).filter { task ->
            task["status"]?.toString() in setOf("running", "pending", "paused") ||
                ((task["updated_at_ms"] as? Number)?.toLong() ?: 0L) > cutoff
        }
    }

    fun resumeAiQueue(): Boolean {
        ensureInitialized()
        localAiManager.resumeQueue()
        return true
    }

    fun pauseAiQueueForAutomation() {
        ensureInitialized()
        localAiManager.pauseQueueForAutomation()
    }

    fun awaitAutomationAiQueueIdle(timeoutMs: Long): Boolean {
        ensureInitialized()
        return localAiManager.awaitAutomationQueueIdle(timeoutMs)
    }

    fun resumeAiQueueAfterAutomation() {
        ensureInitialized()
        localAiManager.resumeQueueAfterAutomation()
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
        val cutoff = diagnosticCutoff()
        return localAiManager.listExecutionSessions(limit).filter { session ->
            session["status"]?.toString() in setOf("running", "pending", "paused") ||
                ((session["updated_at_ms"] as? Number)?.toLong() ?: 0L) > cutoff
        }
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
        try {
            val response = localAiManager.runPipeline(prepared)
            applyPipelineSideEffects(prepared, response)
            return response
        } finally {
            cleanupPreparedRemoteImage(prepared)
        }
    }

    fun runAiBatchPipeline(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val rawItems = (payload["items"] as? List<*>)
            ?.mapNotNull { (it as? Map<*, *>)?.toStringAnyMap() }
            ?: emptyList()
        if (rawItems.isEmpty()) {
            return mapOf("ok" to false, "status" to "invalid", "message" to "items are required")
        }

        val preparedItems = mutableListOf<Map<String, Any>>()
        try {
            // Clean already staged files even if a later item fails preparation.
            rawItems.forEach { preparedItems += prepareAiPipelinePayload(it) }
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
        } finally {
            preparedItems.forEach(::cleanupPreparedRemoteImage)
        }
    }

    fun runMultiStageAiPipeline(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val prepared = prepareAiPipelinePayload(payload)
        try {
            val response = localAiManager.runMultiStagePipeline(prepared)
            applyPipelineSideEffects(prepared, response)
            return response
        } finally {
            cleanupPreparedRemoteImage(prepared)
        }
    }

    /** Progress text can be updated by real stages without resetting durable counters. */
    fun updateAutomationStage(stage: String) {
        ensureInitialized()
        val prefs = appContext.getSharedPreferences(AUTOMATION_PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(AUTOMATION_STATUS_KEY, "") == "running") {
            prefs.edit().putString(AUTOMATION_MESSAGE_KEY, stage)
                .putLong(AUTOMATION_UPDATED_AT_KEY, System.currentTimeMillis()).apply()
            // The stage names contain only stage labels and internal image IDs.
            // This is observable in release builds through adb logcat.
            Log.i("AsterionAutomation", "stage=" + stage.take(160))
        }
    }

    fun runAutonomousImageWorkflow(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val imageId = payload["image_id"].toIntOrNullValue()
            ?: return mapOf("ok" to false, "status" to "invalid", "message" to "image_id is required")

        // Remote images are materialized once per image, rather than opened
        // separately for whole-file hashing, bitmap decoding, OCR and Qwen.
        val startedAt = SystemClock.elapsedRealtime()
        val source = repository.getImageRecordsByIds(listOf(imageId)).firstOrNull()
        updateAutomationStage("Opening source and validating fingerprint for image #$imageId")
        // Every SAF/content image must be opened once and passed as a verified
        // local file to OCR, ONNX and Qwen. A successful model probe alone did
        // not prove that the catalog's original content:// URI was usable.
        val sourceStage = source?.takeIf { requiresAiStaging(it.uri) }?.let { record ->
            stageAiImageSource(imageId, record.uri, record.filename)
        }
        val stagedRemote = sourceStage?.file
        if (sourceStage != null && stagedRemote == null) {
            val provider = Uri.parse(source?.uri.orEmpty()).authority.orEmpty().ifBlank { "local_file" }
            val errorCode = sourceStage.errorCode.ifBlank { "source_staging_failed" }
            val message = "Image source unavailable (provider=$provider, code=$errorCode). " +
                "Original retained for retry; no inference or organization attempted."
            resolutionStore.markAutomationState(
                imageId = imageId, state = "retry_required",
                pipelineComplete = false, organizationComplete = false,
                needsReview = true, lastError = message,
            )
            return mapOf(
                "ok" to false, "status" to "retry_required", "message" to message,
                "automation_state" to "retry_required", "automation_skipped" to 1,
                "automation_stage_failures" to 0,
                "source_provider" to provider, "source_failure_code" to errorCode,
            )
        }
        val sourceReadyAt = SystemClock.elapsedRealtime()
        val stagedPayload = if (stagedRemote != null) {
            mapOf("_asterion_staged_remote_path" to stagedRemote.absolutePath)
        } else emptyMap()

        try {
        automationImagePreflight(imageId, stagedRemote, sourceStage?.sha256)?.let { terminalResult ->
            cleanupPreparedRemoteImage(stagedPayload)
            return terminalResult
        }

        // Query the bounded local staging copy, not the cloud provider again.
        // A textured, decodable photo must never become "broken", "blank",
        // or "placeholder" just because Qwen emits those words.
        val verifiedNonblankImage = stagedRemote?.let { staged ->
            runCatching { ImageVisualEvidence.verifiedNonblank(staged) }.getOrDefault(false)
        } ?: false

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
            ) + stagedPayload,
        )

        updateAutomationStage("Image #$imageId: OCR, safety classification, embeddings and aesthetic scoring")
        val earlyResponse = localAiManager.runMultiStagePipeline(
            preparedBase + mapOf(
                "stages" to earlyStages,
                "direct_automation_dispatch" to true,
                "priority" to 1000,
                "continue_on_stage_error" to true,
                // Match the device-tested probe window: the old 12-second
                // production default could abort models that passed preflight.
                "timeout_ms" to 120_000L,
                "max_retries" to 0,
            ),
        )
        // Never accept an early pipeline failure merely because its failed_stages
        // array was omitted by a timeout/incompatible/error return.
        val earlyStageRecords = (earlyResponse["stages"] as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }.orEmpty()
        val incompleteEarlyStages = earlyStages.filter { mandatory ->
            earlyStageRecords.none { record ->
                record["stage_type"]?.toString() == mandatory &&
                    record["status"]?.toString() == "succeeded"
            }
        }
        if (earlyResponse["ok"] != true || incompleteEarlyStages.isNotEmpty()) {
            val message = earlyStageRecords.firstOrNull { it["status"] != "succeeded" }
                ?.get("message")?.toString()?.takeIf(String::isNotBlank)
                ?: earlyResponse["message"]?.toString()?.takeIf(String::isNotBlank)
                ?: "Required AI stages did not complete."
            val failures = incompleteEarlyStages.ifEmpty { listOf("early_ai") }.map { stage ->
                mapOf("stage_type" to stage, "status" to "failed", "message" to message)
            }
            resolutionStore.markAutomationState(
                imageId = imageId, state = "retry_required",
                pipelineComplete = false, organizationComplete = false,
                needsReview = true, lastError = message,
            )
            cleanupPreparedRemoteImage(preparedBase)
            return mapOf(
                "ok" to false, "status" to "early_ai_incomplete",
                "message" to message, "failed_stages" to failures,
                "automation_stage_failures" to failures.size,
                "automation_state" to "retry_required",
                "organization" to mapOf(
                    "ok" to true, "status" to "deferred_incomplete_analysis",
                    "message" to "Original image retained; missing mandatory AI results.",
                ),
            )
        }
        val earlyStageReadyAt = SystemClock.elapsedRealtime()
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

        // A small but important share of the gallery consists of paid-preview
        // cards. Two independent access/offer OCR phrases provide sufficient
        // evidence to route those cards without a 100+ second Qwen generation.
        // No subject, species, clothing, setting or character tags are guessed.
        if (ContentTriagePolicy.unequivocalPaidPreviewOcr(ocrText)) {
            updateAutomationStage("Image #$imageId: confirmed paid preview — bypassing Qwen")
            val fastOutputs = earlyOutputs + mapOf(
                "captioning" to mapOf(
                    "result" to mapOf("caption" to "Paid-content promotional preview with explicit unlock offer."),
                ),
                "tag_prediction" to mapOf("result" to mapOf("tags" to emptyList<String>())),
                "character_recognition" to mapOf("result" to mapOf(
                    "subjects" to emptyList<Map<String, Any>>(),
                    "routing" to mapOf(
                        "content_class" to "promotion",
                        "people_count" to 0,
                        "identifiable_character_count" to 0,
                        "promotion_or_preview" to true,
                        "scenery_or_environment" to false,
                        "environment_dominant" to false,
                        "quality_flags" to listOf("paywall", "promotional_overlay"),
                    ),
                )),
            )
            val fastWorkflow = aiWorkflowCoordinator.applyImageWorkflow(
                imageId,
                mapOf(
                    "ok" to true, "stage_outputs" to fastOutputs,
                    "failed_stages" to emptyList<Any>(),
                    "verified_nonblank_image" to verifiedNonblankImage,
                ),
            )
            val fastOrganize = organizeAutonomousImage(imageId, fastWorkflow)
            val moved = fastOrganize["ok"] == true &&
                fastOrganize["status"] in setOf("routed_content", "already_routed") &&
                fastOrganize["source_cleanup_pending"] != true
            if (!moved) {
                resolutionStore.queueReview(
                    imageId = imageId, reviewType = "promotion_move_pending",
                    reason = "Promotional preview confirmed by OCR, but safe Trash/Promotion relocation is unavailable. Original retained.",
                    payload = mapOf("route" to "promotion_trash", "organization" to fastOrganize),
                )
                if (stagedRemote != null) retainStagedReviewPreview(source?.uri.orEmpty(), stagedRemote)
            } else {
                resolutionStore.resolveAutomationReviews(imageId)
            }
            resolutionStore.markAutomationState(
                imageId = imageId,
                state = if (moved) "complete" else "review_pending",
                pipelineComplete = true,
                organizationComplete = moved,
                needsReview = !moved,
                lastError = if (moved) "" else "Trash destination not safely writable.",
            )
            val completedAt = SystemClock.elapsedRealtime()
            val elapsed = completedAt - startedAt
            appContext.getSharedPreferences(AUTOMATION_PREFS, Context.MODE_PRIVATE).edit()
                .putLong(AUTOMATION_LAST_QWEN_MS, 0L)
                .putLong(AUTOMATION_LAST_TOTAL_MS, elapsed)
                .putInt(AUTOMATION_LAST_TIMED_IMAGE, imageId).apply()
            Log.i("AsterionImageTiming",
                "image=$imageId source_ms=${sourceReadyAt-startedAt} early_ai_ms=${earlyStageReadyAt-sourceReadyAt} " +
                    "qwen_ms=0 fusion_and_organization_ms=${completedAt-earlyStageReadyAt} total_ms=$elapsed " +
                    "state=${if (moved) "complete" else "review_pending"} fastpath=paid_preview_ocr")
            cleanupPreparedRemoteImage(preparedBase)
            return mapOf(
                "ok" to true, "status" to "paid_preview_processed",
                "workflow" to (fastWorkflow + ("queued_for_review" to !moved)),
                "organization" to fastOrganize,
                "automation_state" to if (moved) "complete" else "review_pending",
                "automation_completed_stages" to earlyStages.size,
                "automation_stage_failures" to 0,
                "automation_stages" to (earlyStages + "paid_preview_ocr_fastpath"),
            )
        }

        val qwenModel = resolvedQwenSemanticModel()
        if (qwenModel == null) {
            cleanupPreparedRemoteImage(preparedBase)
            return mapOf(
                "ok" to false,
                "status" to "incompatible",
                "message" to "No execution-ready Qwen-VL model is installed for the semantic image pass.",
            )
        }

        updateAutomationStage("Image #$imageId: Qwen-VL caption, taxonomy and character semantics")
        val qwenResponse = localAiManager.runPipeline(
            preparedBase + mapOf(
                "task_type" to "captioning",
                "stages" to listOf("captioning"),
                "model_id" to qwenModel.first,
                "version" to qwenModel.second,
                "asterion_semantic_bundle" to true,
                "direct_automation_dispatch" to true,
                "priority" to 1000,
                "ocr_text" to ocrText,
                "nsfw_result" to nsfwResult,
                "max_new_tokens" to 512,
                "continue_on_stage_error" to false,
                "timeout_ms" to 180_000L,
                "max_retries" to 0,
            ),
        )

        val qwenReadyAt = SystemClock.elapsedRealtime()
        val qwenRaw = (qwenResponse["raw_result"] as? Map<*, *>)
            ?.entries
            ?.mapNotNull { (key, value) -> key?.toString()?.let { text -> value?.let { text to it } } }
            ?.toMap()
            .orEmpty()
        val qwenResult = qwenRaw.resultMap()

        // v2.0.25 semantic inference remains the primary path. If the bundled
        // JSON is truncated or produces no canonical illustration IDs, ask the
        // same vision model for ONLY the visible taxonomy IDs. This is genuine
        // image inference, not fabricated labels or error suppression.
        val bundleTags = (qwenResult["tags"] as? List<*>)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
            .orEmpty()
        val bundledCanonical = bundleTags.mapNotNull { raw ->
            knowledgeDatabase.resolveTag(raw)?.id?.takeIf { it.equals(raw, true) }
        }.distinct()
        val bundleHealthy = qwenResponse["ok"] == true &&
            qwenResult["parse_error"]?.toString().orEmpty().isBlank()
        val recoveredTags = if (bundleHealthy && bundledCanonical.isNotEmpty()) {
            emptyList<String>()
        } else {
            updateAutomationStage("Image #$imageId: recovering visible taxonomy tags with Qwen")
            val tagResponse = localAiManager.runPipeline(
                preparedBase + mapOf(
                    "task_type" to "tag_prediction",
                    "stages" to listOf("tag_prediction"),
                    "model_id" to qwenModel.first,
                    "version" to qwenModel.second,
                    "direct_automation_dispatch" to true,
                    "priority" to 1000,
                    "max_new_tokens" to 128,
                    "continue_on_stage_error" to false,
                    "timeout_ms" to 180_000L,
                    "max_retries" to 0,
                ),
            )
            val tagResult = (tagResponse["raw_result"] as? Map<*, *>)
                ?.entries?.mapNotNull { (key, value) ->
                    key?.toString()?.let { k -> value?.let { v -> k to v } }
                }?.toMap().orEmpty().resultMap()
            val recovered = ((if (tagResponse["ok"] == true) tagResult["tags"] else null) as? List<*>)
                ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
                ?.mapNotNull { raw ->
                    knowledgeDatabase.resolveTag(raw)?.id?.takeIf { it.equals(raw, true) }
                }?.distinct().orEmpty()
            Log.i("AsterionQwenTagRecovery",
                "image=$imageId bundle_ok=$bundleHealthy fallback_ok=" +
                    (tagResponse["ok"] == true) + " canonical_tags=" + recovered.size)
            recovered
        }

        val syntheticOutputs = linkedMapOf<String, Map<String, Any>>()
        syntheticOutputs.putAll(earlyOutputs)
        if (qwenResponse["ok"] == true) {
            val caption = qwenResult["caption"]?.toString().orEmpty()
            val normalized = qwenResult["normalized_context"]?.toString().orEmpty()
            val tags = (qwenResult["tags"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
            val visualMedium = (qwenResult["visual_medium"] as? Map<*, *>)
                ?.entries
                ?.mapNotNull { (key, value) ->
                    key?.toString()?.let { value?.let { data -> it to data } }
                }?.toMap().orEmpty()
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
                    "visual_medium" to visualMedium,
                    "raw_observation_json" to (qwenResult["raw_semantic_json"]?.toString().orEmpty()),
                ),
                "message" to "Qwen-VL bundled semantic pass",
            )
        }

        if (recoveredTags.isNotEmpty()) {
            // Replace just tag prediction, retaining Qwen bundle diagnostics.
            syntheticOutputs["tag_prediction"] = mapOf(
                "result" to mapOf("tags" to recoveredTags),
                "message" to ("Qwen-VL tag recovery: " + recoveredTags.size + " verified IDs"),
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
        val semanticNoTags = qwenSucceeded && bundledCanonical.isEmpty() &&
            recoveredTags.isEmpty()
        val taggingFailed = if (semanticNoTags) listOf(mapOf(
            "stage_type" to "tag_prediction",
            "status" to "no_canonical_tags",
            "message" to "Qwen provided no verified canonical tags; original retained.",
        )) else emptyList()
        val failedStages = earlyFailed + qwenFailed + taggingFailed

        // Persist verified Qwen tags even when semantic JSON failed. The
        // worker still gets a partial/failed state; nothing gets moved.
        val haveRecoverableTags = recoveredTags.isNotEmpty()
        val combinedResponse = mapOf(
            "verified_nonblank_image" to verifiedNonblankImage,
            "ok" to (failedStages.isEmpty() || haveRecoverableTags),
            "partial_tag_recovery" to haveRecoverableTags,
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

        updateAutomationStage("Image #$imageId: validating and committing Fusion observations")
        val workflow = aiWorkflowCoordinator.applyImageWorkflow(imageId, combinedResponse)

        val hasCharacterKnowledge = knowledgeDatabase.hasCharacters()
        val terminalContentRoute = workflow["content_route_terminal"] == true
        // Never move/rename source artwork on a partial AI result. Prior to
        // this guard, a failed OCR or Qwen pass could still reach the
        // organizer even though the image was marked retry_required.
        val organization = when {
            failedStages.isNotEmpty() -> mapOf(
                "ok" to true,
                "status" to "deferred_incomplete_analysis",
                "message" to "Original image retained; organization waits for every required AI stage.",
            )
            terminalContentRoute -> organizeAutonomousImage(imageId, workflow)
            hasCharacterKnowledge -> organizeAutonomousImage(imageId, workflow)
            else -> mapOf(
                "ok" to true,
                "status" to "waiting_for_knowledge",
                "message" to "Analysis completed; character-based rename/move is deferred until Character Knowledge is available.",
            )
        }
        val needsReview = workflow["queued_for_review"] == true
        if (needsReview && stagedRemote != null) {
            retainStagedReviewPreview(source?.uri.orEmpty(), stagedRemote)
        }
        val organizationStatus = organization["status"]?.toString().orEmpty()
        val organizationFailed = organization["ok"] == false &&
            organizationStatus !in setOf("skipped", "unchanged", "waiting_for_knowledge")
        val completedStages = syntheticOutputs.size
        val stageFailures = failedStages.size
        val pipelineFailed = failedStages.isNotEmpty()

        val sourceCleanupPending = organization["source_cleanup_pending"] == true
        val state = when {
            sourceCleanupPending -> "review_pending"
            pipelineFailed -> "retry_required"
            organizationFailed -> "retry_required"
            // Review has precedence over terminal routing: a promotion, junk,
            // no-character or uncertain image must not be marked complete.
            needsReview -> "review_pending"
            terminalContentRoute -> "complete"
            !hasCharacterKnowledge -> "waiting_for_knowledge"
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
            organizationComplete = !sourceCleanupPending && organizationStatus in setOf(
                "organized",
                "already_organized",
                "unchanged",
                "routed_content",
                "already_routed",
            ),
            needsReview = needsReview || organizationFailed || pipelineFailed || sourceCleanupPending,
            lastError = when {
                organizationFailed -> organization["message"]?.toString().orEmpty()
                pipelineFailed -> combinedResponse["message"]?.toString().orEmpty()
                else -> ""
            },
        )
        if (state == "complete") {
            resolutionStore.resolveAutomationReviews(imageId)
        }

        val finishedAt = SystemClock.elapsedRealtime()
        val qwenDurationMs = qwenReadyAt - earlyStageReadyAt
        val totalDurationMs = finishedAt - startedAt
        appContext.getSharedPreferences(AUTOMATION_PREFS, Context.MODE_PRIVATE).edit()
            .putLong(AUTOMATION_LAST_QWEN_MS, qwenDurationMs)
            .putLong(AUTOMATION_LAST_TOTAL_MS, totalDurationMs)
            .putInt(AUTOMATION_LAST_TIMED_IMAGE, imageId)
            .apply()
        Log.i("AsterionImageTiming",
            "image=$imageId source_ms=${sourceReadyAt - startedAt} " +
                "early_ai_ms=${earlyStageReadyAt - sourceReadyAt} " +
                "qwen_ms=$qwenDurationMs " +
                "fusion_and_organization_ms=${finishedAt - qwenReadyAt} " +
                "total_ms=$totalDurationMs state=$state")
        val result = combinedResponse + mapOf(
            "ok" to !pipelineFailed,
            "automation_stages" to (earlyStages + "qwen_semantic_bundle" +
                if (recoveredTags.isNotEmpty()) listOf("qwen_tag_recovery") else emptyList()),
            "automation_completed_stages" to completedStages,
            "automation_stage_failures" to stageFailures,
            "workflow" to workflow,
            "organization" to organization,
            "automation_state" to state,
        )
        cleanupPreparedRemoteImage(preparedBase)
        return result
        } finally {
            // Also executes for cancellation or exceptions during inference.
            cleanupPreparedRemoteImage(stagedPayload)
        }
    }

    private fun automationImagePreflight(
        imageId: Int,
        stagedRemote: File? = null,
        stagedSha256: String? = null,
    ): Map<String, Any>? {
        val record = repository.getImageRecordsByIds(listOf(imageId)).firstOrNull()
            ?: return mapOf(
                "ok" to false,
                "status" to "missing",
                "message" to "Image record not found.",
                "automation_stage_failures" to 0,
            )

        val inspection = if (stagedRemote != null) {
            ImageFingerprinting.inspectLocal(stagedRemote, record.sizeBytes ?: 0L, stagedSha256)
        } else {
            ImageFingerprinting.inspect(
                storage = storageProvider, uri = record.uri,
                reportedSizeBytes = record.sizeBytes ?: 0L,
            )
        }
        if (inspection.status == "unreadable") {
            // Loss of SAF permission and cloud-provider outages can look like
            // missing files. An unreadable source must never be retired.
            val sourceProvider = Uri.parse(record.uri).authority?.take(80).orEmpty().ifBlank {
                if (TeraBoxUris.isTeraBox(record.uri)) "terabox" else "local_file"
            }
            val failureCode = inspection.failureCode.ifBlank { "stream_or_decode_unavailable" }
            val message = "Image source unavailable (provider=$sourceProvider, code=$failureCode); original retained for retry."
            resolutionStore.markAutomationState(
                imageId = imageId, state = "retry_required",
                pipelineComplete = false, organizationComplete = false,
                needsReview = true, lastError = message,
            )
            return mapOf(
                "ok" to false, "status" to "retry_required", "message" to message,
                "automation_stage_failures" to 0,
                "automation_state" to "retry_required", "automation_skipped" to 1,
                "source_provider" to sourceProvider, "source_failure_code" to failureCode,
            )
        }

        val fingerprint = inspection.fingerprint
        if (inspection.status == "invalid_image" || fingerprint == null) {
            resolutionStore.queueReview(
                imageId = imageId, reviewType = "unreadable_image",
                reason = "Image decoding failed; original retained for review.",
                payload = mapOf("inspection_status" to inspection.status),
            )
            resolutionStore.markAutomationState(
                imageId = imageId, state = "review_pending",
                pipelineComplete = false, organizationComplete = false,
                needsReview = true, lastError = "Image decoding failed.",
            )
            return mapOf(
                "ok" to true, "status" to "corrupt_review_pending",
                "message" to "Original image preserved; decoding issue sent to Review.",
                "workflow" to mapOf("queued_for_review" to true),
                "organization" to mapOf("ok" to true, "status" to "unchanged"),
                "automation_stage_failures" to 0,
                "automation_state" to "review_pending",
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
            val kind = if (exact != null) "exact" else "conservative_visual"
            // An exact SHA-256 match is conclusive, but perceptual similarity
            // is not. Exact duplicates may be relocated to a reversible Trash
            // folder only through copy+SHA verification+catalog commit. SAF
            // read-only providers cannot be silently deleted or moved.
            val exactQuarantine = if (exact != null) {
                organizeContentCategory(
                    imageId = imageId,
                    folderSegments = listOf("Trash", "Duplicates"),
                    filenamePrefix = "Exact Duplicate",
                    route = "exact_duplicate",
                )
            } else null
            val moved = exactQuarantine?.get("ok") == true &&
                exactQuarantine["status"] in setOf("routed_content", "already_routed") &&
                exactQuarantine["source_cleanup_pending"] != true
            if (!moved) {
                resolutionStore.queueReview(
                    imageId = imageId, reviewType = "duplicate_candidate",
                    reason = "Possible $kind duplicate of image ${duplicate.imageId}; " +
                        "original preserved. " +
                        (exactQuarantine?.get("message")?.toString().orEmpty()),
                    payload = mapOf(
                        "duplicate_kind" to kind,
                        "duplicate_of_image_id" to duplicate.imageId,
                        "duplicate_of_uri" to duplicate.uri,
                    ),
                )
            }
            resolutionStore.upsertImageFingerprint(
                imageId = imageId, sha256 = fingerprint.sha256,
                perceptualHash = fingerprint.perceptualHash,
                width = fingerprint.width, height = fingerprint.height,
                sizeBytes = fingerprint.sizeBytes,
            )
            resolutionStore.markAutomationState(
                imageId = imageId, state = if (moved) "complete" else "review_pending",
                pipelineComplete = moved, organizationComplete = moved,
                needsReview = !moved,
                lastError = if (moved) "" else "Duplicate retained for review; destination is not safely writable.",
            )
            return mapOf(
                "ok" to true,
                "status" to if (moved) "duplicate_quarantined" else "duplicate_review_pending",
                "message" to if (moved)
                    "Exact duplicate moved to verified Trash/Duplicates destination."
                else "Duplicate retained for review; original preserved.",
                "duplicate_kind" to kind,
                "duplicate_of_image_id" to duplicate.imageId,
                "duplicate_of_uri" to duplicate.uri,
                "workflow" to mapOf("queued_for_review" to !moved),
                "organization" to (exactQuarantine ?: mapOf("ok" to true, "status" to "unchanged")),
                "automation_stage_failures" to 0,
                "automation_state" to if (moved) "complete" else "review_pending",
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

    private fun relocationJournalStage(
        imageId: Int,
        sourceUri: String,
        folderUri: String,
        targetName: String,
        destinationUri: String,
        phase: String,
    ) {
        val values = ContentValues().apply {
            put("image_id", imageId)
            put("source_uri", sourceUri)
            put("target_folder_uri", folderUri)
            put("target_name", targetName)
            put("destination_uri", destinationUri)
            put("phase", phase)
            put("updated_at_ms", System.currentTimeMillis())
        }
        val saved = database.writableDatabase.insertWithOnConflict(
            "automation_relocation_journal", null, values, SQLiteDatabase.CONFLICT_REPLACE,
        )
        check(saved >= 0L) { "Could not journal verified artwork relocation" }
    }

    private fun pendingRelocationStage(imageId: Int): String? =
        database.readableDatabase.rawQuery(
            "SELECT phase FROM automation_relocation_journal WHERE image_id = ? LIMIT 1",
            arrayOf(imageId.toString()),
        ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun clearRelocationJournal(imageId: Int) {
        database.writableDatabase.delete(
            "automation_relocation_journal", "image_id = ?", arrayOf(imageId.toString()),
        )
    }

    /**
     * Recovery never silently destroys either copy. An interrupted move is
     * surfaced in Review and blocked from creating another duplicate.
     * A later explicit recovery may compare both checksums to finish safely.
     */
    private fun reconcileInterruptedRelocations() {
        val pending = mutableListOf<Pair<Int, String>>()
        database.readableDatabase.rawQuery(
            "SELECT image_id, phase FROM automation_relocation_journal ORDER BY updated_at_ms LIMIT 200",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) pending += cursor.getInt(0) to cursor.getString(1)
        }
        pending.forEach { (imageId, phase) ->
            resolutionStore.queueReview(
                imageId = imageId,
                reviewType = "relocation_interrupted",
                reason = "An artwork relocation was interrupted at phase '$phase'. Original and verified copies were not automatically deleted.",
                payload = mapOf("phase" to phase, "requires_reconciliation" to true),
            )
        }
    }

    /**
     * Preserve the original until the new copy is fully verified and the
     * catalog path update has committed. A crash may leave an extra copy,
     * never an image with neither source nor destination.
     */
    private fun commitVerifiedAutomationRelocation(
        imageId: Int,
        record: LocalRepository.ImageRecord,
        targetFolder: String,
        targetName: String,
        relativeFolder: String,
        relativePath: String,
    ): Map<String, Any> {
        val previousPhase = pendingRelocationStage(imageId)
        if (previousPhase != null) {
            resolutionStore.queueReview(
                imageId, "relocation_interrupted",
                "Existing unresolved relocation '$previousPhase' requires reconciliation before retry.",
                mapOf("phase" to previousPhase),
            )
            return mapOf(
                "ok" to false, "status" to "relocation_recovery_required",
                "message" to "Previous move has an unresolved journal. No additional copy created.",
            )
        }
        relocationJournalStage(imageId, record.uri, targetFolder, targetName, "", "prepared")
        val copy = storageProvider.copy(record.uri, targetFolder, targetName)
        val copiedUri = copy.uri?.takeIf(String::isNotBlank)
        if (!copy.ok || copiedUri == null || copiedUri == record.uri) {
            relocationJournalStage(imageId, record.uri, targetFolder, targetName,
                copiedUri.orEmpty(), "copy_failed")
            return mapOf(
                "ok" to false, "status" to "copy_failed",
                "message" to copy.message.ifBlank { "Unable to create safe destination copy." },
            )
        }

        relocationJournalStage(imageId, record.uri, targetFolder, targetName, copiedUri, "copied")
        // Size-only verification is insufficient for cloud-backed SAF copies.
        val sourceSha = ImageFingerprinting.contentSha256(storageProvider, record.uri)
        val copySha = ImageFingerprinting.contentSha256(storageProvider, copiedUri)
        if (sourceSha == null || copySha == null || sourceSha != copySha) {
            val cleanup = storageProvider.delete(copiedUri)
            if (cleanup.ok) clearRelocationJournal(imageId)
            else relocationJournalStage(imageId, record.uri, targetFolder, targetName,
                copiedUri, "integrity_failed_cleanup_pending")
            return mapOf(
                "ok" to false, "status" to "copy_integrity_failed",
                "message" to "Destination integrity verification failed; source retained." +
                    if (cleanup.ok) "" else " A partial destination copy may require cleanup.",
            )
        }

        relocationJournalStage(imageId, record.uri, targetFolder, targetName, copiedUri, "verified")
        val updated = runCatching {
            repository.updateImagePathAndClearThumbnails(
                imageId = imageId,
                newUri = copiedUri,
                newFilename = targetName,
                newFolderUri = targetFolder,
                newParentUri = targetFolder,
                newFolderName = relativeFolder.substringAfterLast('/'),
                newRelativePath = relativePath,
                newModifiedAtMs = System.currentTimeMillis(),
                oldUri = record.uri,
            )
        }.getOrDefault(false)
        if (!updated) {
            // This is a destination copy created during this operation. The
            // original is still the catalog source and must be preserved.
            val cleanup = storageProvider.delete(copiedUri)
            if (cleanup.ok) clearRelocationJournal(imageId)
            else relocationJournalStage(imageId, record.uri, targetFolder, targetName,
                copiedUri, "catalog_failed_cleanup_pending")
            return mapOf(
                "ok" to false, "status" to "database_update_failed",
                "message" to "Catalog update failed; original retained." +
                    if (cleanup.ok) "" else " Untracked copy may remain at $copiedUri",
            )
        }

        relocationJournalStage(imageId, record.uri, targetFolder, targetName, copiedUri, "catalog_committed")
        // The catalog now points at the verified destination. Failure to
        // remove the old original is an explicit review, never silent success.
        val sourceCleanup = storageProvider.delete(record.uri)
        if (!sourceCleanup.ok) {
            relocationJournalStage(imageId, record.uri, targetFolder, targetName,
                copiedUri, "source_cleanup_pending")
            resolutionStore.queueReview(
                imageId = imageId,
                reviewType = "source_cleanup_pending",
                reason = "Destination verified and indexed; original source remains at " + record.uri,
                payload = mapOf("source_uri" to record.uri, "destination_uri" to copiedUri),
            )
        }
        if (sourceCleanup.ok) clearRelocationJournal(imageId)
        return mapOf(
            "ok" to true,
            "status" to "organized",
            "uri" to copiedUri,
            "source_cleanup_pending" to !sourceCleanup.ok,
            "message" to if (sourceCleanup.ok) "Destination verified; original safely relocated."
                else "Destination verified; old source retained and queued for review.",
        )
    }

    private fun organizeAutonomousImage(imageId: Int, workflow: Map<String, Any>): Map<String, Any> {
        if (workflow["queued_for_review"] == true) {
            return mapOf(
                "ok" to true,
                "status" to "unchanged",
                "message" to "Identity is below threshold; file remains in place for Review.",
            )
        }

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

        val committed = commitVerifiedAutomationRelocation(
            imageId = imageId, record = record,
            targetFolder = targetFolder, targetName = targetName,
            relativeFolder = relativeFolder,
            relativePath = relativeFolder + "/" + targetName,
        )
        if (committed["ok"] != true) return committed

        return mapOf(
            "ok" to true,
            "source_cleanup_pending" to (committed["source_cleanup_pending"] == true),
            "status" to "organized",
            "series" to primarySeries,
            "character" to primaryCharacter,
            "characters" to resolvedCharacters,
            "original_character" to originalCharacter,
            "folder" to relativeFolder,
            "filename" to targetName,
            "sequence" to sequence,
            "uri" to committed["uri"]?.toString().orEmpty(),
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

        val relativeFolder = safeSegments.joinToString("/")
        val committed = commitVerifiedAutomationRelocation(
            imageId = imageId, record = record,
            targetFolder = targetFolder, targetName = targetName,
            relativeFolder = relativeFolder,
            relativePath = if (relativeFolder.isBlank()) targetName else relativeFolder + "/" + targetName,
        )
        return if (committed["ok"] == true) {
            mapOf(
                "ok" to true, "status" to "routed_content",
                "route" to route, "folder" to relativeFolder,
                "filename" to targetName, "uri" to committed["uri"]?.toString().orEmpty(),
                "source_cleanup_pending" to (committed["source_cleanup_pending"] == true),
            )
        } else committed

    }

    private fun getOrCreateAutomationFolder(parentUri: String, rawName: String): String? =
        synchronized(automationRoutingLock) {
            val name = safeAutomationPathSegment(rawName)
            val normalizedName = name.lowercase()
            val key = parentUri + "|" + normalizedName
            automationFolderCache[key]?.let { return@synchronized it }
            val db = database.writableDatabase
            val previous = db.rawQuery(
                "SELECT folder_uri FROM automation_folder_lookup WHERE parent_uri = ? AND folder_name = ?",
                arrayOf(parentUri, normalizedName),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            val found = if (previous != null && storageProvider.exists(previous)) previous else {
                storageProvider.findDirectory(parentUri, name)?.uri
                    ?: storageProvider.createFolder(parentUri, name).takeIf { it.ok }?.uri
            }
            if (found != null) {
                db.execSQL(
                    "INSERT OR REPLACE INTO automation_folder_lookup(parent_uri, folder_name, folder_uri) VALUES (?, ?, ?)",
                    arrayOf(parentUri, normalizedName, found),
                )
                automationFolderCache[key] = found
            }
            found
        }

    private fun nextAutomationSequenceNumber(targetFolder: String, prefix: String, extension: String): Int =
        synchronized(automationRoutingLock) {
            val normalizedPrefix = prefix.lowercase()
            val normalizedExtension = extension.lowercase()
            val db = database.writableDatabase
            val recorded = db.rawQuery(
                "SELECT next_sequence FROM automation_filename_counters WHERE folder_uri = ? AND filename_prefix = ? AND extension = ?",
                arrayOf(targetFolder, normalizedPrefix, normalizedExtension),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else null }
            val next = recorded ?: run {
                // Enumerate siblings only once per new prefix instead of once per image.
                val escapedPrefix = Regex.escape(prefix)
                val escapedExtension = extension.takeIf(String::isNotBlank)?.let { "\\." + Regex.escape(it) }.orEmpty()
                val matcher = Regex("^" + escapedPrefix + "\\s+(\\d+)" + escapedExtension + "$", RegexOption.IGNORE_CASE)
                (storageProvider.listChildren(targetFolder).asSequence()
                    .filter { !it.isDirectory }
                    .mapNotNull { matcher.matchEntire(it.name)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                    .maxOrNull() ?: 0) + 1
            }
            db.execSQL(
                "INSERT OR REPLACE INTO automation_filename_counters(folder_uri, filename_prefix, extension, next_sequence) VALUES (?, ?, ?, ?)",
                arrayOf(targetFolder, normalizedPrefix, normalizedExtension, next + 1),
            )
            next
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

    fun countAutomationImages(forceAll: Boolean = false): Int {
        ensureInitialized()
        return resolutionStore.countAutomationImages(forceAll)
    }

    fun automationImageBatch(
        forceAll: Boolean,
        afterImportedOrder: Long,
        afterImageId: Int,
        limit: Int = 16,
    ): List<AutomationImageCandidate> {
        ensureInitialized()
        return resolutionStore.automationImageBatch(forceAll, afterImportedOrder, afterImageId, limit)
    }

    fun resetAutomationCheckpoint(forceAll: Boolean) {
        ensureInitialized()
        val saved = appContext.getSharedPreferences(AUTOMATION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(AUTOMATION_CURSOR_ORDER_KEY, -1L)
            .putInt(AUTOMATION_CURSOR_IMAGE_KEY, 0)
            .putBoolean(AUTOMATION_PROBED_KEY, false)
            .putBoolean(AUTOMATION_FORCE_ALL_KEY, forceAll)
            .commit()
        check(saved) { "Could not save new automation checkpoint." }
    }

    fun markAutomationProbeComplete() {
        ensureInitialized()
        check(appContext.getSharedPreferences(AUTOMATION_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(AUTOMATION_PROBED_KEY, true).commit()) {
            "Could not save automation probe checkpoint."
        }
    }

    fun automationCheckpoint(): Map<String, Any> {
        ensureInitialized()
        val prefs = appContext.getSharedPreferences(AUTOMATION_PREFS, Context.MODE_PRIVATE)
        return mapOf(
            "cursor_order" to prefs.getLong(AUTOMATION_CURSOR_ORDER_KEY, -1L),
            "cursor_id" to prefs.getInt(AUTOMATION_CURSOR_IMAGE_KEY, 0),
            "probed" to prefs.getBoolean(AUTOMATION_PROBED_KEY, false),
            "force_all" to prefs.getBoolean(AUTOMATION_FORCE_ALL_KEY, false),
        )
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
        var probeStagedPath = ""
        var lastProbeStatus = "no_candidate"
        var checkedCandidates = 0
        val probeCandidates = buildList {
            // Prefer the newest scanned rows: old database rows are the most
            // likely to contain stale SAF document URIs after app upgrades.
            addAll(imageIds.asReversed().take(512))
            addAll(imageIds.take(64))
        }.distinct()
        for (imageId in probeCandidates) {
            val record = repository.getImageRecordsByIds(listOf(imageId)).firstOrNull()
            if (record == null) {
                lastProbeStatus = "missing_record"
                continue
            }
            checkedCandidates += 1

            // This is deliberately a lightweight readability probe. The old
            // preflight hashed and decoded every candidate before AI could
            // even start, so stale rows could block an otherwise healthy
            // library. The full integrity fingerprint still runs per image in
            // the real workflow.
            val staging = if (requiresAiStaging(record.uri)) {
                stageAiImageSource(imageId, record.uri, record.filename)
            } else null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val readable = runCatching {
                val stream = staging?.file?.inputStream() ?: if (staging == null) {
                    storageProvider.openInputStream(record.uri)
                } else null
                stream?.use { BitmapFactory.decodeStream(it, null, bounds) }
                bounds.outWidth > 0 && bounds.outHeight > 0
            }.getOrDefault(false)
            if (readable) {
                probeImageId = imageId
                probeStagedPath = staging?.file?.absolutePath.orEmpty()
                lastProbeStatus = "ready"
                break
            }
            staging?.file?.delete()
            lastProbeStatus = staging?.errorCode?.ifBlank { "unreadable" } ?: "unreadable"
        }
        if (probeImageId <= 0) {
            return mapOf(
                "ready" to false,
                "recoverable" to true,
                "image_id" to 0,
                "stage" to "image_input",
                "status" to lastProbeStatus,
                "checked_candidates" to checkedCandidates,
                "message" to "No readable current image URI was found in the automation sample. Asterion will refresh the enabled library folders once and retry automatically.",
            )
        }

        // Full Qwen inference in the preflight took 13+ minutes and was then
        // performed again for the SAME image without advancing the checkpoint.
        // The readiness check above has already checked model availability,
        // and this preflight has verified real source readability. Run the
        // expensive models ONCE in the checkpointed image workflow below.
        if (probeStagedPath.isNotBlank()) {
            runCatching { java.io.File(probeStagedPath).delete() }
        }
        return mapOf(
            "ready" to true,
            "image_id" to probeImageId,
            "stage" to "image_input",
            "status" to "readable",
            "message" to "Image source verified. Running AI on the first queued image.",
        )
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
            "automation_last_image_ms" to prefs.getLong(AUTOMATION_LAST_TOTAL_MS, 0L),
            "automation_last_qwen_ms" to prefs.getLong(AUTOMATION_LAST_QWEN_MS, 0L),
            "automation_last_timed_image_id" to prefs.getInt(AUTOMATION_LAST_TIMED_IMAGE, 0),
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
        cursorOrder: Long? = null,
        cursorImageId: Int? = null,
    ) {
        ensureInitialized()
        val editor = appContext.getSharedPreferences(AUTOMATION_PREFS, Context.MODE_PRIVATE)
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
        if (cursorOrder != null && cursorImageId != null) {
            // Durable per-image cursor prevents losing progress on abrupt process death.
            check(editor.putLong(AUTOMATION_CURSOR_ORDER_KEY, cursorOrder)
                .putInt(AUTOMATION_CURSOR_IMAGE_KEY, cursorImageId).commit()) {
                "Could not save image processing checkpoint."
            }
        } else {
            editor.apply()
        }
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
        val scanClock = SystemClock.elapsedRealtime()
        var metadataElapsedMs = 0L
        var sqliteElapsedMs = 0L

        return try {
            for (node in storageProvider.walkTree(normalizedUri)) {
                if (node.isDirectory || !node.name.isImageName()) {
                    skipped += 1
                    continue
                }

                importIndex += 1
                val metadataClock = SystemClock.elapsedRealtime()
                val metadata = extractMetadata(node)
                metadataElapsedMs += SystemClock.elapsedRealtime() - metadataClock
                val sqliteClock = SystemClock.elapsedRealtime()
                val upsert = repository.upsertImage(
                    node = node,
                    folderUri = normalizedUri,
                    scannedAtMs = startedAt,
                    importOrder = startedAt * 1_000_000L + importIndex,
                    metadata = metadata,
                    metadataText = buildMetadataText(node, metadata),
                )
                sqliteElapsedMs += SystemClock.elapsedRealtime() - sqliteClock
                synced += 1
                if (synced % 16 == 0) {
                    repository.updateScanProgress(scanId, synced, skipped)
                    val elapsed = SystemClock.elapsedRealtime() - scanClock
                    Log.i("AsterionScanTiming", "folder=$normalizedUri count=$synced total_ms=$elapsed metadata_ms=$metadataElapsedMs sqlite_ms=$sqliteElapsedMs provider_enumeration_ms=${(elapsed-metadataElapsedMs-sqliteElapsedMs).coerceAtLeast(0L)}")
                }
            }

            // Rounder/TeraBox listings may be partial; never retire absent cloud records.
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
            scheduleArtworkPreviewWarmup()
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

    /** Bounded permanent gallery preview generation, independent of opening a tile. */
    fun pendingArtworkPreviews(afterOrder: Long, afterImageId: Int, limit: Int = 24):
        List<LocalRepository.PendingArtworkPreview> {
        ensureInitialized()
        return repository.pendingArtworkPreviewBatch(afterOrder, afterImageId, limit)
    }

    fun markArtworkPreviewFailure(uri: String, code: String) {
        ensureInitialized()
        repository.markArtworkPreviewFailure(uri, code)
    }

    fun pendingArtworkPreviewCount(): Int {
        ensureInitialized()
        return repository.outstandingArtworkPreviewCount()
    }

    /**
     * Prefer the provider's small preview. Never fetch an entire cloud
     * original merely to populate an off-screen thumbnail. On local SAF,
     * sample-decode small images when no provider preview is implemented.
     */
    fun generateIndexedArtworkPreview(uri: String, reportedSize: Long?): String {
        ensureInitialized()
        if (uri.isBlank()) return "invalid_uri"
        if (repository.durableThumbnailUri(uri) != null) return "cached"
        val parsed = Uri.parse(uri)
        if (parsed.scheme != "content") return "unsupported_scheme"
        if (!thumbnailProviderSlots.tryAcquire(3L, TimeUnit.SECONDS)) return "provider_busy"
        try {
            if (repository.durableThumbnailUri(uri) != null) return "cached"
            val timeoutSignal = android.os.CancellationSignal()
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            val cancelCall = Runnable { timeoutSignal.cancel() }
            mainHandler.postDelayed(cancelCall, 8_000L)
            val providerBitmap = try {
                appContext.contentResolver.loadThumbnail(
                    parsed, android.util.Size(384, 384), timeoutSignal,
                )
            } catch (_: Exception) {
                null
            } finally {
                mainHandler.removeCallbacks(cancelCall)
                timeoutSignal.cancel()
            }

            // A remote SAF provider can reopen a multi-GB source for every
            // decode request; limit this fallback strictly to local providers
            // and reasonably sized source documents.
            val localAuthorities = setOf(
                "com.android.externalstorage.documents",
                "com.android.providers.downloads.documents",
                "com.android.providers.media.documents",
                "media",
            )
            val isRemote = TeraBoxUris.isTeraBox(uri) ||
                parsed.authority.orEmpty() !in localAuthorities
            val bitmap = providerBitmap ?: if (!isRemote &&
                reportedSize != null && reportedSize in 1L..(32L * 1024L * 1024L)) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                try {
                    storageProvider.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                        val ratio = (maxOf(bounds.outWidth, bounds.outHeight) / 512)
                            .coerceAtLeast(1)
                        val sample = Integer.highestOneBit(ratio).coerceAtLeast(1)
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                        }
                        storageProvider.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(it, null, options)
                        }
                    }
                } catch (_: Exception) {
                    null
                }
            } else null
            if (bitmap == null) return if (isRemote) "remote_provider_thumbnail_unavailable"
                else "source_thumbnail_unavailable"
            try {
                val drawable = android.graphics.drawable.BitmapDrawable(appContext.resources, bitmap)
                return if (PersistentArtworkThumbnails(appContext, repository).store(uri, drawable))
                    "stored" else "write_failed"
            } finally {
                bitmap.recycle()
            }
        } finally {
            thumbnailProviderSlots.release()
        }
    }

    fun scheduleArtworkPreviewWarmup() {
        ensureInitialized()
        if (repository.outstandingArtworkPreviewCount() <= 0) return
        val constraints = androidx.work.Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        val manager = WorkManager.getInstance(appContext)
        manager.enqueueUniquePeriodicWork(
            com.ailm.android.workers.ArtworkPreviewRecoveryWorker.UNIQUE_WORK_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            androidx.work.PeriodicWorkRequestBuilder<
                com.ailm.android.workers.ArtworkPreviewRecoveryWorker
            >(6L, TimeUnit.HOURS).setConstraints(constraints).build(),
        )
        manager.enqueueUniqueWork(
            com.ailm.android.workers.ArtworkThumbnailWarmupWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<com.ailm.android.workers.ArtworkThumbnailWarmupWorker>()
                .setConstraints(constraints)
                .build(),
        )
    }

    fun resolveArtworkThumbnail(uri: String): String {
        ensureInitialized()
        if (uri.isBlank() || uri.startsWith(appContext.filesDir.absolutePath)) return uri
        return repository.durableThumbnailUri(uri) ?: uri
    }

    /**
     * Fast-path for cloud and Android SAF gallery tiles. DocumentsProviders
     * may expose a small thumbnail without transferring the original image.
     * Fall back unchanged if the provider does not implement thumbnails.
     * Three concurrent provider requests cap Binder/network pressure.
     */
    suspend fun resolveOrFetchArtworkThumbnail(uri: String): String {
        ensureInitialized()
        val existing = resolveArtworkThumbnail(uri)
        if (existing != uri || uri.isBlank()) return existing
        val parsed = Uri.parse(uri)
        if (parsed.scheme != "content" || android.os.Build.VERSION.SDK_INT < 29) return uri

        // Own the Binder request outside the tile's lifetime. A remote
        // DocumentsProvider can hang for many seconds; show a normal Coil
        // fallback after 900ms while a successful thumbnail keeps warming
        // the persistent app-owned preview cache in the background.
        val providerThumbnail = runtimeScope.async(Dispatchers.IO) {
            if (!thumbnailProviderSlots.tryAcquire()) return@async uri
            try {
                val secondCheck = resolveArtworkThumbnail(uri)
                if (secondCheck != uri) return@async secondCheck
                val bitmap = runCatching {
                    appContext.contentResolver.loadThumbnail(
                        parsed, android.util.Size(384, 384), null,
                    )
                }.getOrNull() ?: return@async uri
                try {
                    val drawable = android.graphics.drawable.BitmapDrawable(appContext.resources, bitmap)
                    val saved = PersistentArtworkThumbnails(appContext, repository).store(uri, drawable)
                    if (saved) resolveArtworkThumbnail(uri) else uri
                } finally {
                    bitmap.recycle()
                }
            } finally {
                thumbnailProviderSlots.release()
            }
        }
        return withTimeoutOrNull(900L) { providerThumbnail.await() } ?: uri
    }

    fun persistArtworkThumbnail(uri: String, drawable: android.graphics.drawable.Drawable): Boolean {
        ensureInitialized()
        if (uri.isBlank() || uri.startsWith(appContext.filesDir.absolutePath)) return false
        return PersistentArtworkThumbnails(appContext, repository).store(uri, drawable)
    }

    fun enqueueArtworkThumbnail(uri: String, drawable: android.graphics.drawable.Drawable) {
        ensureInitialized()
        if (uri.isBlank() || uri.startsWith(appContext.filesDir.absolutePath)) return
        if (!thumbnailWritesInFlight.add(uri)) return
        // Do not tie a successful preview's durable write to the lifecycle of
        // its LazyGrid tile: scrolling must not cancel thumbnail retention.
        runtimeScope.launch(Dispatchers.IO) {
            try {
                PersistentArtworkThumbnails(appContext, repository).store(uri, drawable)
            } finally {
                thumbnailWritesInFlight.remove(uri)
            }
        }
    }

    fun artworkThumbnailStatistics(): Map<String, Any> {
        ensureInitialized()
        return repository.thumbnailStatistics()
    }

    fun manageThumbnailCache(maxBytes: Long = 256L * 1024L * 1024L): Map<String, Any> {
        ensureInitialized()
        val cacheDir = File(appContext.filesDir, "artwork_thumbnails")
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
        // A repeat scan must not reopen unchanged cloud files just to decode
        // dimensions already durably stored in SQLite.
        repository.previousScanMetadata(node)?.let { return it }
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        // Cataloguing a remote TeraBox library must never open every
        // original image. A 300K-image tree would otherwise cause 300K
        // network byte-range sessions before the catalog is usable.
        // Dimensions are enriched lazily when the user opens/processes a
        // specific image; only metadata from the provider is read here.
        val remoteMetadataOnly = runCatching {
            android.net.Uri.parse(node.uri).authority ==
                "com.openlocal.office.terabox.documents"
        }.getOrDefault(false)
        if (!remoteMetadataOnly &&
            !repository.isUnchangedImage(node.uri,node.sizeBytes,node.lastModifiedMs)) {
            storageProvider.openInputStream(node.uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
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

    /**
     * Rounder exposes remote cloud objects through a SAF DocumentsProvider;
     * its URI authority does NOT contain "roundsync". Route by authority,
     * not package display name, and preserve the user's original URI.
     */
    private fun isRemoteAiSource(uri: String): Boolean {
        if (TeraBoxUris.isTeraBox(uri)) return true
        val parsed = Uri.parse(uri)
        return parsed.scheme == "content" &&
            parsed.authority == "com.openlocal.office.terabox.documents"
    }

    /** The native backends use file paths; original SAF grants are not file paths.
     * Keep only one bounded, verified app-owned copy for the duration of inference.
     * Never edit, delete, or move the source, including on provider failure.
     */
    private fun requiresAiStaging(uri: String): Boolean {
        val parsed = Uri.parse(uri)
        return isRemoteAiSource(uri) ||
            parsed.scheme.equals("content", ignoreCase = true) ||
            parsed.scheme.isNullOrBlank()
    }

    /** Persist a bounded preview from staged cloud bytes without reopening SAF. */
    private fun retainStagedReviewPreview(sourceUri: String, stageFile: File) {
        if (sourceUri.isBlank() || repository.durableThumbnailUri(sourceUri) != null) return
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(stageFile.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
            var sample = 1
            while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
            val preview = BitmapFactory.decodeFile(
                stageFile.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return
            try {
                PersistentArtworkThumbnails(appContext, repository).store(
                    sourceUri, android.graphics.drawable.BitmapDrawable(appContext.resources, preview),
                )
            } finally {
                preview.recycle()
            }
        }.onFailure { error ->
            Log.w("AsterionReviewPreview",
                "local_staged_preview_failed type=${error.javaClass.simpleName}")
        }
    }

    private data class AiSourceStage(
        val file: File? = null,
        val errorCode: String = "",
        val sha256: String = "",
    )

    private fun stageAiImageSource(
        imageId: Int,
        sourceUri: String,
        filename: String,
    ): AiSourceStage {
        val extension = filename.substringAfterLast('.', "").lowercase().take(10)
            .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }.orEmpty()
        val directory = File(appContext.cacheDir, "remote_ai").apply { mkdirs() }
        val target = File(directory, "image_${imageId}_${SystemClock.elapsedRealtimeNanos()}" +
            if (extension.isBlank()) "" else ".$extension")
        val provider = Uri.parse(sourceUri).authority.orEmpty().ifBlank { "local_file" }
        fun failed(code: String, cause: Throwable? = null): AiSourceStage {
            target.delete()
            // Never log source URIs, artwork paths, tokens or decoded content.
            Log.w("AsterionImageSource",
                "source_stage_failed provider=$provider code=$code cause=${cause?.javaClass?.simpleName.orEmpty()}")
            return AiSourceStage(errorCode = code)
        }
        return try {
            val parsed = Uri.parse(sourceUri)
            val digest = MessageDigest.getInstance("SHA-256")
            val sourceStream = if (parsed.scheme.isNullOrBlank()) {
                File(sourceUri).inputStream()
            } else {
                storageProvider.openInputStream(sourceUri)
            } ?: return failed("provider_returned_no_stream")
            sourceStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        bytes += n.toLong()
                        if (bytes > 256L * 1024L * 1024L) {
                            throw java.io.IOException("source_exceeds_safe_stage_limit")
                        }
                        // Hash the same exact bytes already being copied to
                        // the staging file. The old separate SHA re-open
                        // could fail and lose the underlying error class.
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                }
            }
            if (!target.isFile || target.length() == 0L) failed("empty_source_stream")
            else AiSourceStage(
                file = target,
                sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
            )
        } catch (error: SecurityException) {
            failed("permission_denied", error)
        } catch (error: java.io.FileNotFoundException) {
            failed("stale_or_missing_document", error)
        } catch (error: java.io.IOException) {
            failed(if (error.message == "source_exceeds_safe_stage_limit")
                "source_exceeds_256mib_staging_cap" else "provider_io_failure", error)
        } catch (error: Exception) {
            failed("provider_open_or_copy_failure", error)
        }
    }

    private fun materializeRemoteImageForAi(
        imageId: Int,
        sourceUri: String,
        filename: String,
    ): File? = if (requiresAiStaging(sourceUri)) {
        stageAiImageSource(imageId, sourceUri, filename).file
    } else null

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
                if (requiresAiStaging(originalUri)) {
                    val previouslyStaged = prepared["_asterion_staged_remote_path"]
                        ?.toString()?.takeIf(String::isNotBlank)
                        ?.let { path -> File(path).takeIf { it.isFile && it.length() > 0L } }
                    val staged = previouslyStaged ?: materializeRemoteImageForAi(
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
