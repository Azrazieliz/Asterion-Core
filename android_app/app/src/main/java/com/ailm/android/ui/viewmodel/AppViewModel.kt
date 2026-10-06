package com.ailm.android.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ailm.android.workers.LibraryAutomationWorker
import com.ailm.android.runtime.StandaloneRuntime
import com.ailm.android.runtime.ai.LocalAiJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class AppUiState(
    val loading: Boolean = false,
    val health: Map<String, Any> = emptyMap(),
    val stats: Map<String, Any> = emptyMap(),
    val images: List<Map<String, Any>> = emptyList(),
    val searchResults: List<Map<String, Any>> = emptyList(),
    val totalResults: Int = 0,
    val collections: List<Map<String, Any>> = emptyList(),
    val libraryFolders: List<Map<String, Any>> = emptyList(),
    val scanRuns: List<Map<String, Any>> = emptyList(),
    val downloads: List<Map<String, Any>> = emptyList(),
    val knowledgePacks: List<Map<String, Any>> = emptyList(),
    val knowledgeSummary: Map<String, Any> = emptyMap(),
    val reviewQueue: List<Map<String, Any>> = emptyList(),
    val tags: List<String> = emptyList(),
    val aiOverview: Map<String, Any> = emptyMap(),
    val aiExecutionChain: Map<String, Any> = emptyMap(),
    val aiHardwareProfile: Map<String, Any> = emptyMap(),
    val aiBackends: List<Map<String, Any>> = emptyList(),
    val aiSettings: Map<String, Any> = emptyMap(),
    val aiAvailableModels: List<Map<String, Any>> = emptyList(),
    val aiInstalledModels: List<Map<String, Any>> = emptyList(),
    val aiTasks: List<Map<String, Any>> = emptyList(),
    val aiInstallRuns: List<Map<String, Any>> = emptyList(),
    val aiExecutionSessions: List<Map<String, Any>> = emptyList(),
    val aiRuntimeHealthSnapshots: List<Map<String, Any>> = emptyList(),
    val aiPlugins: List<Map<String, Any>> = emptyList(),
    val aiCapabilities: List<Map<String, Any>> = emptyList(),
    val aiCacheEntries: List<Map<String, Any>> = emptyList(),
    val aiLastPipelineResult: Map<String, Any> = emptyMap(),
    val knowledgeAutomationStatus: String? = null,
    val settingsValues: Map<String, String> = emptyMap(),
    val teraBoxStatus: Map<String, Any> = emptyMap(),
    val lastMaintenanceResult: Map<String, Any> = emptyMap(),
    val lastActionMessage: String? = null,
    val selectedLibraryUri: String = "",
    val scanStatus: String = "idle",
    val scanProgress: Double = 0.0,
    val scanDiscoveredImages: Int = 0,
    val selectedImage: Map<String, Any>? = null,
    val activeViewerContext: List<Map<String, Any>> = emptyList(),
    val fileOperationPreview: List<Map<String, Any>> = emptyList(),
    val fileOperationResults: List<Map<String, Any>> = emptyList(),
    val fileOperationProgress: Double = 0.0,
    val fileOperationRunning: Boolean = false,
    val fileOperationUndoAvailable: Boolean = false,
    val firstLaunchCompleted: Boolean = false,
    val errorMessage: String? = null,
)

private const val VM_TRACE_TAG = "AilmTraceVM"
private const val FILE_OP_TIMING_TAG = "AilmFileOpTiming"

private data class LocalAiSnapshot(
    val overview: Map<String, Any>,
    val executionChain: Map<String, Any>,
    val hardwareProfile: Map<String, Any>,
    val backends: List<Map<String, Any>>,
    val settings: Map<String, Any>,
    val availableModels: List<Map<String, Any>>,
    val installedModels: List<Map<String, Any>>,
    val tasks: List<Map<String, Any>>,
    val installRuns: List<Map<String, Any>>,
    val executionSessions: List<Map<String, Any>>,
    val runtimeHealthSnapshots: List<Map<String, Any>>,
    val plugins: List<Map<String, Any>>,
    val capabilities: List<Map<String, Any>>,
    val cacheEntries: List<Map<String, Any>>,
    val reviewQueue: List<Map<String, Any>>,
)

class AppViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    private var scanPollingJob: Job? = null

    fun initializeConfiguration(libraryUri: String) {
        _uiState.value = _uiState.value.copy(
            selectedLibraryUri = libraryUri,
            firstLaunchCompleted = libraryUri.isNotBlank(),
        )
    }

    fun setLibraryUri(uri: String) {
        val normalized = uri.trim()
        _uiState.value = _uiState.value.copy(
            selectedLibraryUri = normalized,
            firstLaunchCompleted = normalized.isNotBlank(),
        )
    }

    fun refreshDashboard() {
        viewModelScope.launch(Dispatchers.IO) {
            val previous = _uiState.value

            withContext(Dispatchers.Main) {
                _uiState.value = previous.copy(
                    loading = true,
                    errorMessage = null,
                )
            }

            try {
                val health = StandaloneRuntime.healthStatus()
                val stats = StandaloneRuntime.libraryStatistics()
                val images = StandaloneRuntime.getLibraryImages(page = 1, pageSize = 200)
                val folders = StandaloneRuntime.listLibraryFolders(includeDisabled = true)
                val scanRuns = StandaloneRuntime.scanStatistics(limit = 100)
                val collections = StandaloneRuntime.getCollections()
                val downloads = StandaloneRuntime.listDownloads()
                val knowledgePacks = StandaloneRuntime.listKnowledgePacks()
                val knowledgeSummary = StandaloneRuntime.knowledgeSummary()
                val reviewQueue = StandaloneRuntime.getReviewQueue()
                val tags = StandaloneRuntime.getTags()
                val teraBoxStatus = emptyMap<String, Any>()
                val ai = collectLocalAiSnapshot(previous)

                withContext(Dispatchers.Main) {
                    val current = _uiState.value
                    val selectedImageId = current.selectedImage?.resolvedImageIdOrZero() ?: 0
                    val refreshedSearchResults = reconcileSearchResults(
                        currentImages = current.images,
                        currentSearchResults = current.searchResults,
                        refreshedImages = images,
                    )
                    val refreshedSelectedImage = if (selectedImageId > 0) {
                        images.firstOrNull { row -> row.resolvedImageIdOrZero() == selectedImageId } ?: current.selectedImage
                    } else {
                        current.selectedImage
                    }
                    _uiState.value = AppUiState(
                        loading = false,
                        health = health,
                        stats = stats,
                        images = images,
                        searchResults = refreshedSearchResults,
                        totalResults = refreshedSearchResults.size,
                        collections = collections,
                        libraryFolders = folders,
                        scanRuns = scanRuns,
                        downloads = downloads,
                        knowledgePacks = knowledgePacks,
                        knowledgeSummary = knowledgeSummary,
                        reviewQueue = reviewQueue,
                        tags = tags,
                        aiOverview = ai.overview,
                        aiExecutionChain = ai.executionChain,
                        aiHardwareProfile = ai.hardwareProfile,
                        aiBackends = ai.backends,
                        aiSettings = ai.settings,
                        aiAvailableModels = ai.availableModels,
                        aiInstalledModels = ai.installedModels,
                        aiTasks = ai.tasks,
                        aiInstallRuns = ai.installRuns,
                        aiExecutionSessions = ai.executionSessions,
                        aiRuntimeHealthSnapshots = ai.runtimeHealthSnapshots,
                        aiPlugins = ai.plugins,
                        aiCapabilities = ai.capabilities,
                        aiCacheEntries = ai.cacheEntries,
                        aiLastPipelineResult = current.aiLastPipelineResult,
                        knowledgeAutomationStatus = current.knowledgeAutomationStatus,
                        settingsValues = current.settingsValues,
                        teraBoxStatus = teraBoxStatus,
                        lastMaintenanceResult = current.lastMaintenanceResult,
                        lastActionMessage = current.lastActionMessage,
                        selectedLibraryUri = current.selectedLibraryUri,
                        scanStatus = current.scanStatus,
                        scanProgress = current.scanProgress,
                        scanDiscoveredImages = current.scanDiscoveredImages,
                        selectedImage = refreshedSelectedImage,
                        fileOperationPreview = current.fileOperationPreview,
                        fileOperationResults = current.fileOperationResults,
                        fileOperationProgress = current.fileOperationProgress,
                        fileOperationRunning = current.fileOperationRunning,
                        fileOperationUndoAvailable = current.fileOperationUndoAvailable,
                        firstLaunchCompleted = current.firstLaunchCompleted,
                        errorMessage = null,
                    )
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    _uiState.value = previous.copy(
                        loading = false,
                        errorMessage = t.message ?: t.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun startScan() {
        val root = _uiState.value.selectedLibraryUri.trim()
        if (root.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Choose a library folder first.")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                StandaloneRuntime.startScan(root)
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        scanStatus = "running",
                        errorMessage = null,
                        lastActionMessage = "Scan started.",
                    )
                }
                refreshScanStatus()
                ensureScanPolling()
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(errorMessage = t.message ?: t.javaClass.simpleName)
                }
            }
        }
    }

    fun pauseScan() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { StandaloneRuntime.pauseScan() }
            refreshScanStatus()
            ensureScanPolling()
        }
    }

    fun resumeScan() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { StandaloneRuntime.resumeScan() }
            refreshScanStatus()
            ensureScanPolling()
        }
    }

    fun cancelScan() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { StandaloneRuntime.cancelScan() }
            refreshScanStatus()
        }
    }

    fun refreshScanStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            updateScanState()
        }
    }

    fun searchByFilename(query: String) {
        runIoAction {
            val items = StandaloneRuntime.searchByFilename(query, page = 1, pageSize = 200)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    searchResults = items,
                    totalResults = items.size,
                    errorMessage = null,
                    lastActionMessage = "Filename search returned ${items.size} item(s).",
                )
            }
        }
    }

    fun searchByImageId(rawId: String) {
        val normalized = rawId.trim()
        if (normalized.isBlank()) {
            _uiState.value = _uiState.value.copy(
                searchResults = _uiState.value.images,
                totalResults = _uiState.value.images.size,
                errorMessage = null,
            )
            return
        }
        val imageId = normalized.toIntOrNull()
        if (imageId == null) {
            _uiState.value = _uiState.value.copy(
                searchResults = emptyList(),
                totalResults = 0,
                errorMessage = null,
            )
            return
        }
        runIoAction {
            val item = StandaloneRuntime.searchByImageId(imageId)
            val items = if (item == null) emptyList() else listOf(item)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    searchResults = items,
                    totalResults = items.size,
                    errorMessage = null,
                    lastActionMessage = "Image ID search returned ${items.size} item(s).",
                )
            }
        }
    }

    fun runAdvancedSearch(payload: Map<String, Any>) {
        runIoAction {
            val response = StandaloneRuntime.advancedSearch(payload)
            val items = response["items"] as? List<Map<String, Any>> ?: emptyList()
            val count = response["count"].asIntOrZero()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    searchResults = items,
                    totalResults = count,
                    errorMessage = null,
                    lastActionMessage = "Advanced search returned $count item(s).",
                )
            }
        }
    }

    fun runSemanticSearch(query: String) {
        val normalizedQuery = query.trim()
        runIoAction {
            val items = StandaloneRuntime.semanticSearch(
                queryVector = emptyList(),
                payload = mapOf(
                    "query" to normalizedQuery,
                    "top_k" to 200,
                    "candidate_limit" to 600,
                ),
            )
            withContext(Dispatchers.Main) {
                val message = if (normalizedQuery.isBlank()) {
                    "Semantic search returned ${items.size} item(s)."
                } else {
                    "Semantic search for '$normalizedQuery' returned ${items.size} item(s)."
                }
                _uiState.value = _uiState.value.copy(
                    searchResults = items,
                    totalResults = items.size,
                    errorMessage = null,
                    lastActionMessage = message,
                )
            }
        }
    }

    fun clearSearchResults() {
        _uiState.value = _uiState.value.copy(
            searchResults = _uiState.value.images,
            totalResults = _uiState.value.images.size,
        )
    }

    fun configureTeraBox(clientId: String, clientSecret: String, privateSecret: String) {
        runIoAction {
            val result = StandaloneRuntime.configureTeraBox(clientId, clientSecret, privateSecret)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    teraBoxStatus = StandaloneRuntime.teraBoxStatus(),
                    lastActionMessage = result["message"]?.toString().orEmpty(),
                    errorMessage = if (result["ok"] == true) null else result["message"]?.toString(),
                )
            }
        }
    }

    fun teraBoxAuthorizationUrl(): String {
        return runCatching { StandaloneRuntime.teraBoxAuthorizationUrl() }.getOrDefault("")
    }

    fun refreshTeraBoxStatus() {
        runIoAction {
            val status = StandaloneRuntime.teraBoxStatus()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(teraBoxStatus = status)
            }
        }
    }

    fun disconnectTeraBox() {
        runIoAction {
            StandaloneRuntime.disconnectTeraBox()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    teraBoxStatus = StandaloneRuntime.teraBoxStatus(),
                    lastActionMessage = "TeraBox disconnected.",
                    errorMessage = null,
                )
            }
        }
    }

    fun addTeraBoxLibraryRoot(path: String) {
        runIoAction {
            val result = StandaloneRuntime.addTeraBoxLibraryRoot(path)
            val ok = result["ok"] == true
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    lastActionMessage = result["message"]?.toString().orEmpty(),
                    errorMessage = if (ok) null else result["message"]?.toString(),
                )
            }
            if (ok) refreshDashboard()
        }
    }

    fun addLibraryFolder(folderUri: String) {
        val uri = folderUri.trim()
        if (uri.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Folder URI is required.")
            return
        }
        runIoAction {
            val ok = StandaloneRuntime.addLibraryFolder(uri)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    lastActionMessage = if (ok) "Folder added." else "Folder was not added.",
                )
            }
            refreshDashboard()
        }
    }

    fun importCloudImages(uris: List<Uri>) {
        if (uris.isEmpty()) {
            return
        }
        val root = _uiState.value.selectedLibraryUri.trim()
        if (root.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Choose a writable library folder before importing cloud images.")
            return
        }
        runIoAction {
            val result = StandaloneRuntime.importCloudImages(uris, root)
            val failed = (result["failed"] as? Number)?.toInt() ?: 0
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    lastActionMessage = result["message"]?.toString().orEmpty().ifBlank { "Cloud import completed." },
                    errorMessage = if (failed > 0 && result["imported"] == 0) {
                        result["message"]?.toString() ?: "Cloud import failed."
                    } else null,
                )
            }
            refreshDashboard()
        }
    }

    fun setLibraryFolderEnabled(folderUri: String, enabled: Boolean) {
        runIoAction {
            val ok = StandaloneRuntime.setLibraryFolderEnabled(folderUri, enabled)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    lastActionMessage = if (ok) {
                        if (enabled) "Folder enabled." else "Folder disabled."
                    } else {
                        "Folder update failed."
                    },
                )
            }
            refreshDashboard()
        }
    }

    fun removeLibraryFolder(folderUri: String) {
        runIoAction {
            val ok = StandaloneRuntime.removeLibraryFolder(folderUri)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    lastActionMessage = if (ok) "Folder removed." else "Folder remove failed.",
                )
            }
            refreshDashboard()
        }
    }

    fun rescanFolder(folderUri: String) {
        runIoAction {
            val result = StandaloneRuntime.rescanFolder(folderUri)
            val ok = result["ok"].asBooleanOrFalse()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    lastActionMessage = if (ok) {
                        "Folder refresh completed."
                    } else {
                        result["message"]?.toString() ?: "Folder refresh failed."
                    },
                )
            }
            refreshDashboard()
        }
    }

    fun rescanEnabledFolders() {
        runIoAction {
            val runs = StandaloneRuntime.rescanEnabledFolders()
            val succeeded = runs.count { it["ok"].asBooleanOrFalse() }
            val failed = runs.size - succeeded
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    lastActionMessage = if (failed == 0) {
                        "Refreshed $succeeded enabled folder(s)."
                    } else {
                        "Refreshed $succeeded folder(s); $failed failed."
                    },
                )
            }
            refreshDashboard()
        }
    }

    fun setImageTags(imageId: Int, tagsCsv: String) {
        if (imageId <= 0) {
            _uiState.value = _uiState.value.copy(errorMessage = "Invalid image selection for tag update.")
            return
        }
        val tags = tagsCsv
            .split(',', '|')
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }
        Log.d(VM_TRACE_TAG, "ViewModel receives tags: imageId=$imageId input=$tagsCsv parsed=${tags.joinToString("|")}")
        runIoAction {
            val ok = StandaloneRuntime.setImageTags(imageId, tags)
            Log.d(VM_TRACE_TAG, "ViewModel tags runtime result: imageId=$imageId ok=$ok")
            var updatedImage: Map<String, Any>? = null
            withContext(Dispatchers.Main) {
                if (ok) {
                    updatedImage = updateImageState(imageId) { image ->
                        val metadata = (image["metadata"] as? Map<*, *>)
                            ?.mapNotNull { (key, value) -> (key as? String)?.let { it to value } }
                            ?.toMap()
                            ?.toMutableMap()
                            ?: mutableMapOf()
                        metadata["tags"] = tags
                        metadata["user_tags"] = tags.joinToString("|")
                        image.toMutableMap().apply {
                            this["metadata"] = metadata
                        }
                    }
                    Log.d(VM_TRACE_TAG, "ViewModel tags state staged: imageId=$imageId updatedImage=${imageSummary(updatedImage)}")
                } else {
                    val currentSelectedId = _uiState.value.selectedImage?.get("image_id")
                    Log.d(
                        VM_TRACE_TAG,
                        "Tags update failed: requestedImageId=$imageId currentUISelectedImageId=$currentSelectedId selectedImage=${imageSummary(_uiState.value.selectedImage)}",
                    )
                }
                _uiState.value = _uiState.value.copy(lastActionMessage = if (ok) "Tags updated." else "Tag update failed.")
            }
            if (ok) {
                refreshImageInState(imageId, updatedImage)
                refreshOperationSummaries(includeTags = true)
            }
        }
    }

    fun rebuildSearchIndex() {
        runIoAction {
            val ok = StandaloneRuntime.rebuildSearchIndex()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    lastActionMessage = if (ok) "Search index rebuilt." else "Search index rebuild failed.",
                )
            }
            refreshDashboard()
        }
    }

    fun optimizeDatabase() {
        runIoAction {
            val ok = StandaloneRuntime.optimizeDatabase()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    lastActionMessage = if (ok) "Database optimized." else "Database optimization failed.",
                )
            }
            refreshDashboard()
        }
    }

    fun maintainThumbnailCache(maxMb: Int) {
        val safeMb = maxMb.coerceAtLeast(1)
        runIoAction {
            val result = StandaloneRuntime.manageThumbnailCache(safeMb.toLong() * 1024L * 1024L)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    lastMaintenanceResult = result,
                    lastActionMessage = "Thumbnail cache maintenance completed.",
                )
            }
        }
    }

    fun clearThumbnailCache() {
        runIoAction {
            val result = StandaloneRuntime.manageThumbnailCache(0)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    lastMaintenanceResult = result,
                    lastActionMessage = "Thumbnail cache cleared.",
                )
            }
        }
    }

    fun refreshScanStatistics() {
        runIoAction {
            val runs = StandaloneRuntime.scanStatistics(limit = 100)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(scanRuns = runs)
            }
        }
    }

    fun updateLibrarySetting(key: String, value: String) {
        val normalizedKey = key.trim()
        if (normalizedKey.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Setting key is required.")
            return
        }
        runIoAction {
            val ok = StandaloneRuntime.updateSetting(normalizedKey, value)
            withContext(Dispatchers.Main) {
                val currentSettings = _uiState.value.settingsValues.toMutableMap()
                currentSettings[normalizedKey] = value
                _uiState.value = _uiState.value.copy(
                    settingsValues = currentSettings,
                    lastActionMessage = if (ok) "Setting saved." else "Setting save failed.",
                )
            }
        }
    }

    fun loadLibrarySetting(key: String, defaultValue: String = "") {
        val normalizedKey = key.trim()
        if (normalizedKey.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Setting key is required.")
            return
        }
        runIoAction {
            val value = StandaloneRuntime.getSetting(normalizedKey, defaultValue)
            withContext(Dispatchers.Main) {
                val currentSettings = _uiState.value.settingsValues.toMutableMap()
                currentSettings[normalizedKey] = value
                _uiState.value = _uiState.value.copy(
                    settingsValues = currentSettings,
                    lastActionMessage = "Setting loaded.",
                )
            }
        }
    }

    fun refreshLocalAiState() {
        runIoAction {
            refreshLocalAiStateInternal(clearError = true)
        }
    }

    fun detectAiHardwareProfile() {
        runIoAction {
            val profile = StandaloneRuntime.detectAiHardwareProfile()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    aiHardwareProfile = profile,
                    lastActionMessage = "AI hardware profile refreshed.",
                    errorMessage = null,
                )
            }
            refreshLocalAiStateInternal()
        }
    }

    fun validateLocalAiInfrastructure() {
        runIoAction {
            val result = StandaloneRuntime.validateLocalAiInfrastructure()
            val ok = result["ok"].asBooleanOrFalse()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    aiLastPipelineResult = result,
                    lastActionMessage = if (ok) {
                        "Local AI infrastructure is healthy."
                    } else {
                        "Local AI infrastructure validation failed."
                    },
                )
            }
            refreshLocalAiStateInternal()
        }
    }

    fun startLibraryAutomation(context: Context, forceAll: Boolean = false) {
        val appContext = context.applicationContext
        runCatching {
            StandaloneRuntime.initialize(appContext)
            StandaloneRuntime.clearAutomationPauseRequest()
            val readiness = StandaloneRuntime.automationReadiness()
            if (readiness["ready"] != true) {
                val message = readiness["message"]?.toString().orEmpty()
                    .ifBlank { "Automation prerequisites are not execution-ready." }
                StandaloneRuntime.updateAutomationStatus(
                    status = "failed",
                    total = 0,
                    processed = 0,
                    failed = 0,
                    review = 0,
                    message = message,
                )
                _uiState.value = _uiState.value.copy(
                    lastActionMessage = "Automation blocked.",
                    errorMessage = message,
                )
                refreshLocalAiState()
                return@runCatching
            }
            val repairedLegacyResults = StandaloneRuntime.repairLegacyIncompleteAutomationStates()
            val pending = StandaloneRuntime.automationImageIds(forceAll).size
            StandaloneRuntime.updateAutomationStatus(
                status = "queued",
                total = pending,
                processed = 0,
                failed = 0,
                review = 0,
                message = when {
                    pending == 0 -> "Nothing to process."
                    repairedLegacyResults > 0 ->
                        "Automation queued; $repairedLegacyResults incomplete legacy result(s) will be retried."
                    else -> "Automation queued."
                },
            )
            val request = OneTimeWorkRequestBuilder<LibraryAutomationWorker>()
                .setInputData(workDataOf(LibraryAutomationWorker.FORCE_ALL_KEY to forceAll))
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                LibraryAutomationWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
            _uiState.value = _uiState.value.copy(
                lastActionMessage = if (pending == 0) "No unprocessed images found." else "Automation started in the background.",
                errorMessage = null,
            )
            refreshLocalAiState()
        }.onFailure { error ->
            _uiState.value = _uiState.value.copy(errorMessage = error.message ?: error.javaClass.simpleName)
        }
    }

    fun pauseLibraryAutomation(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            StandaloneRuntime.initialize(appContext)
            StandaloneRuntime.requestAutomationPause()
            val current = StandaloneRuntime.automationStatus()
            StandaloneRuntime.updateAutomationStatus(
                status = "pausing",
                total = (current["automation_total"] as? Number)?.toInt() ?: 0,
                processed = (current["automation_processed"] as? Number)?.toInt() ?: 0,
                failed = (current["automation_failed"] as? Number)?.toInt() ?: 0,
                review = (current["automation_review"] as? Number)?.toInt() ?: 0,
                skipped = (current["automation_skipped"] as? Number)?.toInt() ?: 0,
                currentImageId = (current["automation_current_image_id"] as? Number)?.toInt() ?: 0,
                message = "Pause requested. The current image will finish before automation pauses.",
            )
            _uiState.value = _uiState.value.copy(lastActionMessage = "Safe pause requested.")
            refreshLocalAiState()
        }.onFailure { error ->
            _uiState.value = _uiState.value.copy(errorMessage = error.message ?: error.javaClass.simpleName)
        }
    }

    fun resumeLibraryAutomation(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            StandaloneRuntime.initialize(appContext)
            StandaloneRuntime.clearAutomationPauseRequest()
        }
        startLibraryAutomation(context, forceAll = false)
    }

    fun stopLibraryAutomation(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            StandaloneRuntime.initialize(appContext)
            StandaloneRuntime.clearAutomationPauseRequest()
        }
        WorkManager.getInstance(appContext).cancelUniqueWork(LibraryAutomationWorker.UNIQUE_WORK_NAME)
        runCatching {
            StandaloneRuntime.initialize(appContext)
            val current = StandaloneRuntime.automationStatus()
            StandaloneRuntime.updateAutomationStatus(
                status = "stopped",
                total = (current["automation_total"] as? Number)?.toInt() ?: 0,
                processed = (current["automation_processed"] as? Number)?.toInt() ?: 0,
                failed = (current["automation_failed"] as? Number)?.toInt() ?: 0,
                review = (current["automation_review"] as? Number)?.toInt() ?: 0,
                skipped = (current["automation_skipped"] as? Number)?.toInt() ?: 0,
                currentImageId = (current["automation_current_image_id"] as? Number)?.toInt() ?: 0,
                message = "Automation stopped.",
            )
        }
        _uiState.value = _uiState.value.copy(lastActionMessage = "Automation stop requested.")
        refreshLocalAiState()
    }

    fun pauseAiQueue() {
        runIoAction {
            val ok = StandaloneRuntime.pauseAiQueue()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    lastActionMessage = if (ok) "AI queue paused." else "Failed to pause AI queue.",
                )
            }
            refreshLocalAiStateInternal()
        }
    }

    fun resumeAiQueue() {
        runIoAction {
            val ok = StandaloneRuntime.resumeAiQueue()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    lastActionMessage = if (ok) "AI queue resumed." else "Failed to resume AI queue.",
                )
            }
            refreshLocalAiStateInternal()
        }
    }

    fun updateAiSetting(settingKey: String, settingValue: String) {
        val rawKey = settingKey.trim()
        if (rawKey.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "AI setting key is required.")
            return
        }
        val key = normalizeAiSettingKey(rawKey)
        val value = parseAiSettingValue(key, settingValue)

        runIoAction {
            val updated = StandaloneRuntime.updateAiSettings(mapOf(key to value))
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    aiSettings = updated,
                    lastActionMessage = "AI setting updated: $key",
                    errorMessage = null,
                )
            }
            refreshLocalAiStateInternal()
        }
    }

    fun detectAiModelUpdates() {
        runIoAction {
            val updates = StandaloneRuntime.detectAiModelUpdates()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    aiLastPipelineResult = mapOf(
                        "ok" to true,
                        "updates" to updates,
                        "count" to updates.size,
                    ),
                    lastActionMessage = "Detected ${updates.size} AI model update candidate(s).",
                    errorMessage = null,
                )
            }
            refreshLocalAiStateInternal()
        }
    }

    fun pruneAiCache() {
        runIoAction {
            val result = StandaloneRuntime.pruneAiCache()
            val ok = result["ok"].asBooleanOrFalse()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    aiLastPipelineResult = result,
                    lastActionMessage = if (ok) "AI cache pruned." else "AI cache prune returned errors.",
                    errorMessage = null,
                )
            }
            refreshLocalAiStateInternal()
        }
    }

    fun verifyInstalledAiModel(modelId: String, version: String) {
        val normalizedModelId = modelId.trim()
        val normalizedVersion = version.trim()
        if (normalizedModelId.isBlank() || normalizedVersion.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "model_id and version are required.")
            return
        }
        runIoAction {
            val result = StandaloneRuntime.verifyInstalledAiModel(
                mapOf(
                    "model_id" to normalizedModelId,
                    "version" to normalizedVersion,
                ),
            )
            val ok = result["ok"].asBooleanOrFalse()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    aiLastPipelineResult = result,
                    lastActionMessage = if (ok) {
                        "Model verification passed: $normalizedModelId@$normalizedVersion"
                    } else {
                        "Model verification failed: $normalizedModelId@$normalizedVersion"
                    },
                    errorMessage = null,
                )
            }
            refreshLocalAiStateInternal()
        }
    }

    fun removeInstalledAiModel(modelId: String, version: String = "") {
        val normalizedModelId = modelId.trim()
        if (normalizedModelId.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "model_id is required.")
            return
        }
        val normalizedVersion = version.trim()
        runIoAction {
            val payload = mutableMapOf<String, Any>(
                "model_id" to normalizedModelId,
                "delete_file" to false,
            )
            if (normalizedVersion.isNotBlank()) {
                payload["version"] = normalizedVersion
            }
            val result = StandaloneRuntime.removeAiModel(payload)
            val ok = result["ok"].asBooleanOrFalse()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    aiLastPipelineResult = result,
                    lastActionMessage = if (ok) {
                        if (normalizedVersion.isBlank()) {
                            "Removed installed model: $normalizedModelId"
                        } else {
                            "Removed installed model: $normalizedModelId@$normalizedVersion"
                        }
                    } else {
                        "Failed to remove model: $normalizedModelId"
                    },
                    errorMessage = null,
                )
            }
            refreshLocalAiStateInternal()
        }
    }

    fun registerAvailableAiModel(form: Map<String, String>) {
        val normalizedModelId = form["model_id"]?.trim().orEmpty()
        if (normalizedModelId.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "model_id is required for model registration.")
            return
        }

        runIoAction {
            val payload = buildAiModelPayloadFromForm(form)
            val result = StandaloneRuntime.registerAvailableAiModel(payload)
            val ok = result["ok"].asBooleanOrFalse()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    aiLastPipelineResult = result,
                    lastActionMessage = if (ok) {
                        "Registered available model: $normalizedModelId"
                    } else {
                        "Failed to register available model: $normalizedModelId"
                    },
                    errorMessage = null,
                )
            }
            refreshLocalAiStateInternal()
        }
    }

    fun importLocalAiModel(form: Map<String, String>) {
        val normalizedModelId = form["model_id"]?.trim().orEmpty()
        val sourcePath = form["source_path"]?.trim().orEmpty()
        if (normalizedModelId.isBlank() || sourcePath.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "model_id and source_path are required for local model import.")
            return
        }

        runIoAction {
            importLocalAiModelInternal(form, normalizedModelId, sourcePath)
        }
    }

    fun importLocalAiModelDocument(context: Context, uri: Uri, form: Map<String, String>) {
        val normalizedModelId = form["model_id"]?.trim().orEmpty()
        if (normalizedModelId.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "model_id is required for local model import.")
            return
        }

        runIoAction {
            val sourcePath = copyDocumentToAppStorage(context, uri, "models")
            try {
                importLocalAiModelInternal(
                    form = form + mapOf("source_uri" to uri.toString()),
                    modelId = normalizedModelId,
                    sourcePath = sourcePath,
                )
            } finally {
                File(sourcePath).deleteRecursively()
            }
        }
    }

    fun importLocalAiModelPackageTree(context: Context, uri: Uri, form: Map<String, String>) {
        val normalizedModelId = form["model_id"]?.trim().orEmpty()
        if (normalizedModelId.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "model_id is required for local model import.")
            return
        }

        runIoAction {
            val sourcePath = copyDocumentTreeToAppStorage(context, uri, "models")
            try {
                importLocalAiModelInternal(
                    form = form + mapOf("source_uri" to uri.toString()),
                    modelId = normalizedModelId,
                    sourcePath = sourcePath,
                )
            } finally {
                File(sourcePath).deleteRecursively()
            }
        }
    }

    fun registerAiModelDownload(form: Map<String, String>) {
        val normalizedModelId = form["model_id"]?.trim().orEmpty()
        if (normalizedModelId.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "model_id is required for download registration.")
            return
        }

        runIoAction {
            val payload = buildAiModelPayloadFromForm(form).toMutableMap()
            form["priority"]?.trim()?.toIntOrNull()?.let { payload["priority"] = it }
            val result = StandaloneRuntime.registerAiModelDownload(payload)
            val ok = result["ok"].asBooleanOrFalse()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    aiLastPipelineResult = result,
                    lastActionMessage = if (ok) {
                        "Registered model download: $normalizedModelId"
                    } else {
                        "Failed to register model download: $normalizedModelId"
                    },
                    errorMessage = null,
                )
            }
            refreshLocalAiStateInternal()
            refreshResourceArtifacts()
        }
    }

    fun setActiveAiModel(modelId: String, version: String = "", taskType: String = "") {
        val normalizedModelId = modelId.trim()
        val normalizedVersion = version.trim()
        if (normalizedModelId.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "model_id is required to set active model.")
            return
        }
        if (normalizedVersion.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "version is required to activate an installed model.")
            return
        }

        val normalizedTaskType = normalizeTaskType(taskType)
        runIoAction {
            val result = StandaloneRuntime.activateInstalledAiModel(
                buildMap {
                    put("model_id", normalizedModelId)
                    put("version", normalizedVersion)
                    if (normalizedTaskType.isNotBlank()) {
                        put("task_type", normalizedTaskType)
                    }
                },
            )
            val ok = result["ok"].asBooleanOrFalse()
            val message = result["message"]?.toString()?.trim().orEmpty()
            val returnedSettings = (result["settings"] as? Map<*, *>)
                ?.entries
                ?.filter { it.key != null }
                ?.associate { it.key.toString() to (it.value ?: "") }

            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    aiSettings = returnedSettings ?: _uiState.value.aiSettings,
                    aiLastPipelineResult = result,
                    lastActionMessage = if (ok) {
                        if (normalizedTaskType.isBlank()) {
                            "Active model set to $normalizedModelId@$normalizedVersion."
                        } else {
                            "Active model set for $normalizedTaskType: $normalizedModelId@$normalizedVersion."
                        }
                    } else {
                        message.ifBlank { "Model activation was rejected safely." }
                    },
                    errorMessage = if (ok) null else message.ifBlank {
                        "Model activation was rejected safely."
                    },
                )
            }
            // Keep successful activation session-scoped. A full settings refresh would
            // intentionally discard it because persistent active-model state is disabled until
            // runtime initialization is proven crash-safe.
        }
    }

    fun clearActiveAiModel(taskType: String = "") {
        val normalizedTaskType = normalizeTaskType(taskType)
        val suffix = if (normalizedTaskType.isBlank()) "" else ".$normalizedTaskType"
        val keysToClear = setOf(
            "active_model_id$suffix",
            "active_model_version$suffix",
        )
        val updated = _uiState.value.aiSettings.filterKeys { it !in keysToClear }
        _uiState.value = _uiState.value.copy(
            aiSettings = updated,
            lastActionMessage = if (normalizedTaskType.isBlank()) {
                "Cleared session model selection."
            } else {
                "Cleared session model selection for $normalizedTaskType."
            },
            errorMessage = null,
        )
    }

    fun importFusionDatabasePayload(payload: String, format: String = "json", replaceExisting: Boolean = false) {
        val sourcePayload = payload.trim()
        if (sourcePayload.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Fusion import payload is required.")
            return
        }

        runIoAction {
            importFusionDatabaseInternal(sourcePayload, format, replaceExisting)
        }
    }

    fun importFusionDatabaseDocument(context: Context, uri: Uri, format: String = "json", replaceExisting: Boolean = false) {
        runIoAction {
            importFusionDatabaseInternal(readDocumentText(context, uri), format, replaceExisting)
        }
    }

    fun exportFusionDatabaseSnapshot(format: String = "json", pretty: Boolean = true) {
        runIoAction {
            val exportPayload = StandaloneRuntime.exportFusionDatabase(
                format = format.trim().ifBlank { "json" },
                pretty = pretty,
            )
            val result = mapOf(
                "ok" to true,
                "action" to "export_fusion_database",
                "format" to format.trim().ifBlank { "json" },
                "pretty" to pretty,
                "bytes" to exportPayload.toByteArray().size,
                "preview" to exportPayload.take(4000),
            )
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    aiLastPipelineResult = result,
                    lastActionMessage = "Fusion database exported to in-memory payload preview.",
                    errorMessage = null,
                )
            }
        }
    }

    fun refreshFusionManagement() {
        runIoAction {
            val result = StandaloneRuntime.fusionManagementStatus() + mapOf("kind" to "fusion_management")
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    lastMaintenanceResult = result,
                    errorMessage = null,
                )
            }
        }
    }

    fun exportFusionManagement() {
        runIoAction {
            val export = StandaloneRuntime.exportFusionDatabaseFile()
            val status = StandaloneRuntime.fusionManagementStatus()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    lastMaintenanceResult = status + export + mapOf("kind" to "fusion_management"),
                    lastActionMessage = "Fusion database exported.",
                    errorMessage = null,
                )
            }
        }
    }

    fun rebuildFusionManagement() {
        runIoAction {
            val result = StandaloneRuntime.rebuildFusionOptimizations() + mapOf("kind" to "fusion_management")
            val ok = result["ok"].asBooleanOrFalse()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    lastMaintenanceResult = result,
                    lastActionMessage = if (ok) "Fusion optimized structures rebuilt." else "Fusion rebuild requires attention.",
                    errorMessage = null,
                )
            }
        }
    }

    fun validateFusionDatabase() {
        runIoAction {
            val result = StandaloneRuntime.validateFusionDatabase()
            val ok = result["ok"].asBooleanOrFalse() || result["valid"].asBooleanOrFalse()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    aiLastPipelineResult = result,
                    lastActionMessage = if (ok) "Fusion database validation passed." else "Fusion database validation reported issues.",
                    errorMessage = null,
                )
            }
        }
    }

    fun previewExternalImport(form: Map<String, String>) {
        val payload = buildExternalImportPayload(form) ?: return
        runIoAction {
            previewExternalImportInternal(payload)
        }
    }

    fun previewExternalImportDocument(context: Context, uri: Uri, form: Map<String, String>) {
        runIoAction {
            val payload = buildExternalImportPayload(form + mapOf("source" to readExternalImportDocument(context, uri, form)))
                ?: return@runIoAction
            previewExternalImportInternal(payload)
        }
    }

    fun previewKnowledgePackDocument(context: Context, uri: Uri, form: Map<String, String>) {
        runIoAction {
            val raw = readExternalImportDocument(context, uri, form)
            val result = StandaloneRuntime.previewKnowledgePackDocument(raw, uri.lastPathSegment.orEmpty())
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    aiLastPipelineResult = result + mapOf("kind" to "knowledge_pack_preview"),
                    lastActionMessage = result["message"]?.toString() ?: "Knowledge Pack preview ready.",
                    errorMessage = null,
                )
            }
        }
    }

    fun validateExternalImport(form: Map<String, String>) {
        val payload = buildExternalImportPayload(form) ?: return
        runIoAction {
            validateExternalImportInternal(payload)
        }
    }

    fun validateExternalImportDocument(context: Context, uri: Uri, form: Map<String, String>) {
        runIoAction {
            val payload = buildExternalImportPayload(form + mapOf("source" to readExternalImportDocument(context, uri, form)))
                ?: return@runIoAction
            validateExternalImportInternal(payload)
        }
    }

    fun validateKnowledgePackDocument(context: Context, uri: Uri, form: Map<String, String>) {
        runIoAction {
            val raw = readExternalImportDocument(context, uri, form)
            val result = StandaloneRuntime.validateKnowledgePackDocument(raw, uri.lastPathSegment.orEmpty())
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    aiLastPipelineResult = result + mapOf("kind" to "knowledge_pack_validation"),
                    lastActionMessage = result["message"]?.toString() ?: "Knowledge Pack validation completed.",
                    errorMessage = null,
                )
            }
        }
    }

    fun importExternalDatabase(form: Map<String, String>) {
        val payload = buildExternalImportPayload(form) ?: return
        runIoAction {
            importExternalDatabaseInternal(payload)
        }
    }

    fun importExternalDatabaseDocument(context: Context, uri: Uri, form: Map<String, String>) {
        runIoAction {
            val payload = buildExternalImportPayload(form + mapOf("source" to readExternalImportDocument(context, uri, form)))
                ?: return@runIoAction
            importExternalDatabaseInternal(payload)
        }
    }

    fun importKnowledgePackDocuments(uris: List<Uri>) {
        if (uris.isEmpty()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Choose at least one Knowledge file.")
            return
        }
        runIoAction {
            val results = StandaloneRuntime.importKnowledgePackDocuments(uris)
            val successful = results.filter { result -> result["ok"].asBooleanOrFalse() }
            val failures = results.filterNot { result -> result["ok"].asBooleanOrFalse() }
            val reference = successful.firstOrNull { result ->
                result["kind"]?.toString() == "immutable_knowledge_release"
            }
            val installedPacks = successful.count { result ->
                result["kind"]?.toString() != "immutable_knowledge_release"
            }
            val message = when {
                reference != null -> {
                    val series = (reference["series_entries"] as? Number)?.toInt() ?: 0
                    val tags = (reference["tag_entries"] as? Number)?.toInt() ?: 0
                    val characters = (reference["character_entries"] as? Number)?.toInt() ?: 0
                    val ignoredAliases = (reference["series_aliases_ignored"] as? Number)?.toInt() ?: 0
                    "Reference Knowledge imported: $series series, $tags taxonomy/tag entries" +
                        (if (characters > 0) ", $characters characters" else "") +
                        (if (ignoredAliases > 0) ", $ignoredAliases ambiguous series alias(es) ignored" else "") +
                        "."
                }
                installedPacks > 0 -> "Imported $installedPacks Knowledge Pack(s)."
                failures.isNotEmpty() -> "Knowledge import failed."
                else -> "No Knowledge content was imported."
            }
            val failureMessage = failures
                .mapNotNull { it["message"]?.toString()?.trim()?.takeIf(String::isNotBlank) }
                .distinct()
                .joinToString("\n")
                .takeIf(String::isNotBlank)

            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    aiLastPipelineResult = mapOf("kind" to "knowledge_pack_import", "results" to results),
                    lastActionMessage = message,
                    knowledgeAutomationStatus = if (successful.isNotEmpty()) {
                        "Knowledge changed\nPending Fusion rebuild"
                    } else {
                        _uiState.value.knowledgeAutomationStatus
                    },
                    errorMessage = failureMessage,
                )
            }
            refreshResourceArtifacts()
        }
    }

    fun previewKnowledgePack(filename: String) {
        runIoAction {
            val result = StandaloneRuntime.previewKnowledgePack(filename)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    aiLastPipelineResult = result + mapOf("kind" to "knowledge_pack_preview"),
                    lastActionMessage = result["message"]?.toString() ?: "Knowledge Pack preview ready.",
                    errorMessage = null,
                )
            }
        }
    }

    fun replaceKnowledgePackDocument(filename: String, uri: Uri) {
        runIoAction {
            val result = StandaloneRuntime.replaceKnowledgePackDocument(filename, uri)
            val ok = result["ok"].asBooleanOrFalse()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    aiLastPipelineResult = result + mapOf("kind" to "knowledge_pack_replace"),
                    lastActionMessage = result["message"]?.toString() ?: if (ok) "Knowledge Pack replaced." else "Knowledge Pack replacement failed.",
                    knowledgeAutomationStatus = if (ok) "Knowledge changed\nPending Fusion rebuild" else _uiState.value.knowledgeAutomationStatus,
                    errorMessage = null,
                )
            }
            if (ok) refreshResourceArtifacts()
        }
    }

    fun removeKnowledgePack(filename: String) {
        runIoAction {
            val ok = StandaloneRuntime.removeKnowledgePack(filename)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    lastActionMessage = if (ok) "Knowledge Pack removed." else "Knowledge Pack removal failed.",
                    knowledgeAutomationStatus = if (ok) "Knowledge changed\nPending Fusion rebuild" else _uiState.value.knowledgeAutomationStatus,
                    errorMessage = null,
                )
            }
            if (ok) refreshResourceArtifacts()
        }
    }

    fun rollbackExternalImport(importId: String = "") {
        runIoAction {
            val normalized = importId.trim().ifBlank { null }
            val result = StandaloneRuntime.rollbackImport(normalized)
            val ok = result["ok"].asBooleanOrFalse()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    aiLastPipelineResult = result,
                    lastActionMessage = if (ok) {
                        "Rollback import completed."
                    } else {
                        "Rollback import was not applied."
                    },
                    errorMessage = null,
                )
            }
            refreshDashboard()
        }
    }

    fun cancelExternalImport(importId: String) {
        val normalized = importId.trim()
        if (normalized.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "import_id is required to cancel import.")
            return
        }

        runIoAction {
            val ok = StandaloneRuntime.cancelImport(normalized)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    aiLastPipelineResult = mapOf(
                        "ok" to ok,
                        "action" to "cancel_import",
                        "import_id" to normalized,
                    ),
                    lastActionMessage = if (ok) {
                        "Import cancellation requested: $normalized"
                    } else {
                        "Unable to request import cancellation: $normalized"
                    },
                    errorMessage = null,
                )
            }
        }
    }

    fun controlAiTask(taskId: String, action: String) {
        val normalizedTaskId = taskId.trim()
        val normalizedAction = action.trim().lowercase()
        if (normalizedTaskId.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "task_id is required.")
            return
        }
        if (normalizedAction !in setOf("cancel", "pause", "resume", "retry")) {
            _uiState.value = _uiState.value.copy(errorMessage = "Unsupported task action: $normalizedAction")
            return
        }

        runIoAction {
            val ok = when (normalizedAction) {
                "cancel" -> StandaloneRuntime.cancelAiTask(normalizedTaskId)
                "pause" -> StandaloneRuntime.pauseAiTask(normalizedTaskId)
                "resume" -> StandaloneRuntime.resumeAiTask(normalizedTaskId)
                "retry" -> StandaloneRuntime.retryAiTask(normalizedTaskId)
                else -> false
            }
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    lastActionMessage = if (ok) {
                        "Task ${normalizedAction.replaceFirstChar { it.uppercase() }} succeeded: $normalizedTaskId"
                    } else {
                        "Task ${normalizedAction.replaceFirstChar { it.uppercase() }} failed: $normalizedTaskId"
                    },
                    errorMessage = null,
                )
            }
            refreshLocalAiStateInternal()
        }
    }

    fun runSelectedImageAiPipeline(taskType: String, promptHint: String = "") {
        val normalizedTaskType = normalizeTaskType(taskType)
        if (normalizedTaskType.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "task_type is required.")
            return
        }
        val selected = _uiState.value.selectedImage
        val imageId = selected?.get("image_id").asIntOrZero()
        if (imageId <= 0) {
            _uiState.value = _uiState.value.copy(errorMessage = "Select an image before running AI pipelines.")
            return
        }

        runIoAction {
            val payload = linkedMapOf<String, Any>(
                "image_id" to imageId,
            )
            val prompt = promptHint.trim()
            if (prompt.isNotBlank()) {
                payload["prompt"] = prompt
                payload["style_hint"] = prompt
                payload["context"] = prompt
            }
            val payloadWithModelHint = withActiveModelHint(payload, normalizedTaskType)

            val result = when (normalizedTaskType) {
                "ocr" -> StandaloneRuntime.runOcrPipeline(payloadWithModelHint)
                "captioning" -> StandaloneRuntime.runCaptioningPipeline(payloadWithModelHint)
                "character_recognition" -> StandaloneRuntime.runCharacterRecognitionPipeline(payloadWithModelHint)
                "series_recognition" -> StandaloneRuntime.runSeriesRecognitionPipeline(payloadWithModelHint)
                "artist_recognition" -> StandaloneRuntime.runArtistRecognitionPipeline(payloadWithModelHint)
                "tag_prediction" -> StandaloneRuntime.runTagPredictionPipeline(payloadWithModelHint)
                "metadata_extraction" -> StandaloneRuntime.runMetadataExtractionPipeline(payloadWithModelHint)
                "prompt_generation" -> StandaloneRuntime.runPromptGenerationPipeline(payloadWithModelHint)
                "embedding_generation" -> StandaloneRuntime.runEmbeddingGenerationPipeline(payloadWithModelHint)
                "duplicate_detection" -> StandaloneRuntime.runDuplicateDetectionPipeline(payloadWithModelHint)
                "classification" -> StandaloneRuntime.runClassificationPipeline(payloadWithModelHint)
                "detection" -> StandaloneRuntime.runDetectionPipeline(payloadWithModelHint)
                "face_feature_extraction" -> StandaloneRuntime.runFaceFeatureExtractionPipeline(payloadWithModelHint)
                "knowledge_pack_execution" -> StandaloneRuntime.runKnowledgePackExecution(payloadWithModelHint)
                else -> StandaloneRuntime.runAiPipeline(payloadWithModelHint + mapOf("task_type" to normalizedTaskType))
            }

            val ok = result["ok"].asBooleanOrFalse()
            val resultMessage = result["message"]?.toString()?.trim().orEmpty()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    aiLastPipelineResult = result,
                    lastActionMessage = if (ok) {
                        "${humanTaskName(normalizedTaskType)} completed for image #$imageId."
                    } else {
                        buildString {
                            append("${humanTaskName(normalizedTaskType)} failed for image #$imageId")
                            if (resultMessage.isNotBlank()) append(": ").append(resultMessage)
                        }
                    },
                    errorMessage = if (ok) null else resultMessage.takeIf(String::isNotBlank),
                )
            }

            if (ok) {
                refreshImageInState(imageId)
                val latestTags = StandaloneRuntime.getTags()
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(tags = latestTags)
                }
            }
            refreshLocalAiStateInternal()
        }
    }

    fun runSelectedImageMultiStagePipeline(promptHint: String = "") {
        val selected = _uiState.value.selectedImage
        val imageId = selected?.get("image_id").asIntOrZero()
        if (imageId <= 0) {
            _uiState.value = _uiState.value.copy(errorMessage = "Select an image before running multi-stage AI pipelines.")
            return
        }

        val stages = listOf(
            "ocr",
            "captioning",
            "character_recognition",
            "series_recognition",
            "artist_recognition",
            "tag_prediction",
            "metadata_extraction",
            "classification",
            "detection",
            "face_feature_extraction",
            "prompt_generation",
        )

        runIoAction {
            val payload = linkedMapOf<String, Any>(
                "image_id" to imageId,
                "stages" to stages,
            )
            val prompt = promptHint.trim()
            if (prompt.isNotBlank()) {
                payload["prompt"] = prompt
                payload["style_hint"] = prompt
                payload["context"] = prompt
            }
            val payloadWithModelHint = withActiveModelHint(payload, "multi_stage")

            val result = StandaloneRuntime.runMultiStageAiPipeline(payloadWithModelHint)
            val ok = result["ok"].asBooleanOrFalse()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    aiLastPipelineResult = result,
                    lastActionMessage = if (ok) {
                        "Multi-stage AI pipeline completed for image #$imageId."
                    } else {
                        "Multi-stage AI pipeline failed for image #$imageId."
                    },
                    errorMessage = null,
                )
            }

            if (ok) {
                refreshImageInState(imageId)
                val latestTags = StandaloneRuntime.getTags()
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(tags = latestTags)
                }
            }
            refreshLocalAiStateInternal()
        }
    }

    fun runBatchAiPipelineForVisibleImages(taskType: String, maxItems: Int = 24) {
        val normalizedTaskType = normalizeTaskType(taskType)
        if (normalizedTaskType.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "task_type is required for batch pipeline.")
            return
        }

        val current = _uiState.value
        val sourceRows = if (current.searchResults.isNotEmpty()) current.searchResults else current.images
        val limit = maxItems.coerceIn(1, 200)
        val imageIds = sourceRows
            .mapNotNull { row ->
                val id = row["image_id"].asIntOrZero()
                if (id > 0) id else null
            }
            .distinct()
            .take(limit)
        if (imageIds.isEmpty()) {
            _uiState.value = _uiState.value.copy(errorMessage = "No visible images found for batch pipeline execution.")
            return
        }

        runIoAction {
            val items = imageIds.map { imageId ->
                mapOf(
                    "task_type" to normalizedTaskType,
                    "image_id" to imageId,
                )
            }
            val payloadWithModelHint = withActiveModelHint(
                mapOf(
                    "task_type" to normalizedTaskType,
                    "items" to items,
                ),
                normalizedTaskType,
            )
            val result = StandaloneRuntime.runAiBatchPipeline(
                payloadWithModelHint,
            )
            val ok = result["ok"].asBooleanOrFalse()
            val succeeded = result["succeeded"].asIntOrZero()
            val failed = result["failed"].asIntOrZero()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    aiLastPipelineResult = result,
                    lastActionMessage = if (ok) {
                        "Batch ${humanTaskName(normalizedTaskType)} completed for ${imageIds.size} image(s)."
                    } else {
                        "Batch ${humanTaskName(normalizedTaskType)} finished with issues (ok=$succeeded, failed=$failed)."
                    },
                    errorMessage = null,
                )
            }

            if (normalizedTaskType == "tag_prediction") {
                val latestTags = StandaloneRuntime.getTags()
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(tags = latestTags)
                }
            }

            val selectedImageId = _uiState.value.selectedImage?.get("image_id").asIntOrZero()
            if (selectedImageId > 0 && selectedImageId in imageIds) {
                refreshImageInState(selectedImageId)
            }
            refreshLocalAiStateInternal()
        }
    }

    fun enqueueSelectedImageAiTask(taskType: String) {
        val normalizedTaskType = normalizeTaskType(taskType)
        if (normalizedTaskType.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "task_type is required.")
            return
        }
        val selected = _uiState.value.selectedImage
        val imageId = selected?.get("image_id").asIntOrZero()
        if (imageId <= 0) {
            _uiState.value = _uiState.value.copy(errorMessage = "Select an image before queueing AI tasks.")
            return
        }

        runIoAction {
            val payloadWithModelHint = withActiveModelHint(
                mapOf(
                    "task_type" to normalizedTaskType,
                    "image_id" to imageId,
                ),
                normalizedTaskType,
            )
            val result = StandaloneRuntime.enqueueAiTask(payloadWithModelHint)
            val ok = result["ok"].asBooleanOrFalse()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    aiLastPipelineResult = result,
                    lastActionMessage = if (ok) {
                        "Queued ${humanTaskName(normalizedTaskType)} for image #$imageId."
                    } else {
                        "Failed to queue ${humanTaskName(normalizedTaskType)} for image #$imageId."
                    },
                    errorMessage = null,
                )
            }
            refreshLocalAiStateInternal()
        }
    }

    fun selectImage(image: Map<String, Any>) {
        _uiState.value = _uiState.value.copy(selectedImage = normalizeSelectedImage(image))
    }

    fun setActiveViewerContext(rows: List<Map<String, Any>>) {
        _uiState.value = _uiState.value.copy(activeViewerContext = rows)
    }

    fun clearActiveViewerContext() {
        _uiState.value = _uiState.value.copy(activeViewerContext = emptyList())
    }

    fun selectedImageUrl(): String? {
        val image = _uiState.value.selectedImage ?: return null
        return image["file_url"]?.toString()?.takeIf { it.isNotBlank() }
            ?: image["thumbnail_url"]?.toString()?.takeIf { it.isNotBlank() }
            ?: image["path"]?.toString()?.takeIf { it.isNotBlank() }
    }

    fun previewFileOperations(payload: Map<String, Any>) {
        runIoAction {
            val response = StandaloneRuntime.previewFileOperations(payload)
            val ok = response["ok"] as? Boolean ?: false
            val preview = response["operations"] as? List<Map<String, Any>> ?: emptyList()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    fileOperationPreview = preview,
                    fileOperationResults = emptyList(),
                    fileOperationProgress = 0.0,
                    fileOperationRunning = false,
                    lastActionMessage = if (ok) "Prepared ${preview.size} operation(s)." else (response["message"]?.toString() ?: "Preview failed."),
                )
            }
        }
    }

    fun executeFileOperations(payload: Map<String, Any>) {
        runIoAction {
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    fileOperationRunning = true,
                    fileOperationProgress = 0.0,
                    lastActionMessage = "Executing file operations...",
                )
            }
            try {
                val response = StandaloneRuntime.executeFileOperations(payload)
                val results = response["results"] as? List<Map<String, Any>> ?: emptyList()
                val undoAvailable = response["undo_available"] as? Boolean ?: false
                val ok = response["ok"] as? Boolean ?: false
                val hasSuccessfulResults = results.any { it["ok"] == true }
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        fileOperationResults = results,
                        fileOperationProgress = if (results.isEmpty()) 0.0 else 1.0,
                        fileOperationRunning = false,
                        fileOperationUndoAvailable = undoAvailable,
                        lastActionMessage = if (ok) {
                            response["message"]?.toString() ?: "Executed ${results.size} operation(s)."
                        } else {
                            response["message"]?.toString() ?: "Execution failed."
                        },
                        selectedImage = _uiState.value.selectedImage,
                        searchResults = _uiState.value.searchResults,
                        totalResults = _uiState.value.totalResults,
                    )
                }
                if (hasSuccessfulResults) {
                    applyFileOperationResultsToUi(payload, results)
                    refreshOperationSummaries(includeTags = true)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (_uiState.value.fileOperationRunning) {
                        _uiState.value = _uiState.value.copy(fileOperationRunning = false)
                    }
                }
            }
        }
    }

    fun executeFileOperationSequence(payloads: List<Map<String, Any>>) {
        if (payloads.isEmpty()) {
            return
        }
        runIoAction {
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    fileOperationRunning = true,
                    fileOperationProgress = 0.0,
                    lastActionMessage = "Executing file operations...",
                )
            }

            try {
                val mergedResults = mutableListOf<Map<String, Any>>()
                var overallOk = true
                var undoAvailable = false

                for (payload in payloads) {
                    val response = StandaloneRuntime.executeFileOperations(payload)
                    val results = response["results"] as? List<Map<String, Any>> ?: emptyList()
                    val ok = response["ok"] as? Boolean ?: false
                    mergedResults += results
                    overallOk = overallOk && ok
                    if (response["undo_available"] as? Boolean == true) {
                        undoAvailable = true
                    }
                    if (results.any { it["ok"] == true }) {
                        applyFileOperationResultsToUi(payload, results)
                    }
                    if (!ok) {
                        break
                    }
                }

                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        fileOperationResults = mergedResults,
                        fileOperationProgress = if (mergedResults.isEmpty()) 0.0 else 1.0,
                        fileOperationRunning = false,
                        fileOperationUndoAvailable = undoAvailable,
                        lastActionMessage = if (overallOk) {
                            "Executed ${mergedResults.size} operation(s)."
                        } else {
                            "Execution failed."
                        },
                        selectedImage = _uiState.value.selectedImage,
                        searchResults = _uiState.value.searchResults,
                        totalResults = _uiState.value.totalResults,
                    )
                }

                if (mergedResults.any { it["ok"] == true }) {
                    refreshOperationSummaries(includeTags = true)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (_uiState.value.fileOperationRunning) {
                        _uiState.value = _uiState.value.copy(fileOperationRunning = false)
                    }
                }
            }
        }
    }

    fun undoLastFileOperations() {
        runIoAction {
            val response = StandaloneRuntime.undoLastFileOperations()
            val results = response["results"] as? List<Map<String, Any>> ?: emptyList()
            val ok = response["ok"] as? Boolean ?: false
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    fileOperationResults = results,
                    fileOperationUndoAvailable = false,
                    lastActionMessage = if (ok) "Undo executed (${results.size} operation(s))." else (response["message"]?.toString() ?: "Undo failed."),
                    selectedImage = _uiState.value.selectedImage,
                    searchResults = _uiState.value.searchResults,
                    totalResults = _uiState.value.totalResults,
                )
            }
            if (ok) {
                applyGroupedFileOperationResultsToUi(results)
                refreshOperationSummaries(includeTags = true)
            }
        }
    }

    private suspend fun applyGroupedFileOperationResultsToUi(results: List<Map<String, Any>>) {
        val successful = results.filter { it["ok"] == true }
        if (successful.isEmpty()) {
            return
        }
        val grouped = successful.groupBy { it["action"]?.toString()?.trim()?.lowercase().orEmpty() }
        grouped.forEach { (action, rows) ->
            if (action.isBlank()) {
                return@forEach
            }
            applyFileOperationResultsToUi(mapOf("action" to action), rows)
        }
    }

    private suspend fun applyFileOperationResultsToUi(payload: Map<String, Any>, results: List<Map<String, Any>>) {
        val successful = results.filter { it["ok"] == true }
        if (successful.isEmpty()) {
            return
        }

        val action = payload["action"]?.toString()?.trim()?.lowercase().orEmpty()
        when (action) {
            "rename_image", "batch_rename_images", "move_images", "batch_move" -> {
                val totalStart = SystemClock.elapsedRealtime()
                val operationName = when (action) {
                    "rename_image", "batch_rename_images" -> "rename"
                    else -> "move"
                }
                val ids = successful.mapNotNull { (it["image_id"] as? Number)?.toInt() }.distinct()
                if (ids.isNotEmpty()) {
                    val stage9Start = SystemClock.elapsedRealtime()
                    val latestById = ids.mapNotNull { id -> StandaloneRuntime.searchByImageId(id)?.let { id to it } }.toMap()
                    val stage9Ms = SystemClock.elapsedRealtime() - stage9Start
                    withContext(Dispatchers.Main) {
                        val stage8Start = SystemClock.elapsedRealtime()
                        val current = _uiState.value
                        val images = replaceRenamedImageRows(current.images, latestById)
                        val search = replaceRenamedImageRows(current.searchResults, latestById)
                        var selected = current.selectedImage
                        for ((id, latestRow) in latestById) {
                            if (selected?.resolvedImageIdOrZero() == id) {
                                selected = latestRow
                            }
                        }
                        _uiState.value = current.copy(
                            images = images,
                            searchResults = search,
                            selectedImage = selected,
                            totalResults = if (search.isEmpty()) current.totalResults else search.size,
                        )
                        val stage8Ms = SystemClock.elapsedRealtime() - stage8Start
                        val totalMs = SystemClock.elapsedRealtime() - totalStart
                        Log.d(FILE_OP_TIMING_TAG, "$operationName stage8_ui_refresh_ms=$stage8Ms")
                        Log.d(FILE_OP_TIMING_TAG, "$operationName stage9_post_processing_ms=$stage9Ms")
                        Log.d(FILE_OP_TIMING_TAG, "$operationName stage1to9_total_ms=$totalMs")
                    }
                }
            }
            "copy_images", "batch_copy" -> {
                val copiedIds = successful.mapNotNull { (it["copied_image_id"] as? Number)?.toInt() }
                    .filter { it > 0 }
                    .distinct()
                if (copiedIds.isNotEmpty()) {
                    val copiedRows = copiedIds.mapNotNull { StandaloneRuntime.searchByImageId(it) }
                    withContext(Dispatchers.Main) {
                        val current = _uiState.value
                        val existingIds = current.images.map { it["image_id"].asIntOrZero() }.toSet()
                        val toAdd = copiedRows.filter { it["image_id"].asIntOrZero() !in existingIds }
                        _uiState.value = current.copy(
                            images = current.images + toAdd,
                            searchResults = if (current.searchResults.isEmpty()) current.searchResults else current.searchResults + toAdd,
                            totalResults = if (current.searchResults.isEmpty()) current.totalResults else current.searchResults.size + toAdd.size,
                        )
                    }
                }
            }
            "delete_images", "batch_delete" -> {
                val deletedIds = successful.mapNotNull { (it["image_id"] as? Number)?.toInt() }.distinct().toSet()
                if (deletedIds.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        val current = _uiState.value
                        val images = current.images.filter { it["image_id"].asIntOrZero() !in deletedIds }
                        val search = current.searchResults.filter { it["image_id"].asIntOrZero() !in deletedIds }
                        val selected = current.selectedImage?.takeIf { it["image_id"].asIntOrZero() !in deletedIds }
                        _uiState.value = current.copy(
                            images = images,
                            searchResults = search,
                            selectedImage = selected,
                            totalResults = if (search.isEmpty()) current.totalResults else search.size,
                        )
                    }
                }
            }
            "rename_folder", "create_folder", "delete_folder" -> {
                applyFolderOperationResultsToUi(action, successful)
            }
        }
    }

    private fun replaceRenamedImageRows(
        rows: List<Map<String, Any>>,
        latestById: Map<Int, Map<String, Any>>,
    ): List<Map<String, Any>> {
        val replacedIds = mutableSetOf<Int>()
        return rows.mapNotNull { row ->
            val imageId = row.resolvedImageIdOrZero()
            val latest = latestById[imageId] ?: return@mapNotNull row
            if (replacedIds.add(imageId)) latest else null
        }
    }

    private suspend fun applyFolderOperationResultsToUi(action: String, results: List<Map<String, Any>>) {
        withContext(Dispatchers.Main) {
            val current = _uiState.value
            var folders = current.libraryFolders
            when (action) {
                "rename_folder" -> {
                    val replacements = results.mapNotNull { row ->
                        val oldUri = row["old_uri"]?.toString()?.trim().orEmpty()
                        val newUri = row["new_uri"]?.toString()?.trim().orEmpty()
                        if (oldUri.isBlank() || newUri.isBlank()) null else oldUri to newUri
                    }
                    if (replacements.isNotEmpty()) {
                        val replacementMap = replacements.toMap()
                        folders = folders.map { folder ->
                            val oldUri = folder["folder_uri"]?.toString().orEmpty()
                            val newUri = replacementMap[oldUri] ?: return@map folder
                            folder + mapOf("folder_uri" to newUri)
                        }
                    }
                }
                "create_folder" -> {
                    val existingUris = folders.mapNotNull { it["folder_uri"]?.toString() }.toSet()
                    val toAdd = results.mapNotNull { row ->
                        val uri = row["folder_uri"]?.toString()?.trim().orEmpty()
                        if (uri.isBlank() || uri in existingUris) {
                            null
                        } else {
                            mapOf(
                                "folder_uri" to uri,
                                "enabled" to true,
                                "added_at_ms" to System.currentTimeMillis(),
                                "last_scan_started_ms" to 0L,
                                "last_scan_completed_ms" to 0L,
                                "last_scan_status" to "",
                                "last_scan_count" to 0,
                            )
                        }
                    }
                    if (toAdd.isNotEmpty()) {
                        folders = folders + toAdd
                    }
                }
                "delete_folder" -> {
                    val removed = results.mapNotNull { it["folder_uri"]?.toString()?.trim() }
                        .filter { it.isNotBlank() }
                        .toSet()
                    if (removed.isNotEmpty()) {
                        folders = folders.filterNot { (it["folder_uri"]?.toString().orEmpty()) in removed }
                    }
                }
            }

            _uiState.value = current.copy(
                libraryFolders = folders,
                stats = current.stats + mapOf(
                    "total_folders" to folders.size,
                ),
            )
        }
    }

    fun clearFileOperationPreview() {
        _uiState.value = _uiState.value.copy(
            fileOperationPreview = emptyList(),
            fileOperationResults = emptyList(),
            fileOperationProgress = 0.0,
            fileOperationRunning = false,
        )
    }

    fun approveReview(itemId: String) {
        submitReviewAction(itemId, "approve")
    }

    fun correctReview(itemId: String, charactersText: String, originalCharacter: Boolean) {
        val characters = charactersText
            .split('|')
            .map(String::trim)
            .filter(String::isNotBlank)
        val correction = buildMap<String, Any> {
            put("original_character", originalCharacter)
            if (!originalCharacter && characters.isNotEmpty()) put("characters", characters)
        }
        submitReviewAction(
            itemId = itemId,
            action = "correct",
            payload = mapOf("correction" to correction),
        )
    }

    fun rejectReview(itemId: String) {
        submitReviewAction(itemId, "reject")
    }

    fun undoReview(itemId: String) {
        submitReviewAction(itemId, "undo")
    }

    override fun onCleared() {
        scanPollingJob?.cancel()
        super.onCleared()
    }

    private fun ensureScanPolling() {
        if (scanPollingJob?.isActive == true) {
            return
        }
        scanPollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val state = _uiState.value.scanStatus
                if (state !in setOf("running", "paused", "queued", "in_progress")) {
                    break
                }
                updateScanState()
                delay(1200)
            }
        }
    }

    private suspend fun updateScanState() {
        try {
            val status = StandaloneRuntime.scanStatus()
            val state = status["status"]?.toString()?.ifBlank { "idle" } ?: "idle"
            val progress = status["progress"].asDoubleOrZero()
            val discovered = status["discovered_images"].asIntOrZero()

            val shouldRefreshData = state == "completed"
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    scanStatus = state,
                    scanProgress = progress,
                    scanDiscoveredImages = discovered,
                    errorMessage = status["error"]?.toString(),
                )
            }

            if (shouldRefreshData) {
                refreshDashboard()
            }
        } catch (t: Throwable) {
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(errorMessage = t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun Any?.asDoubleOrZero(): Double = when (this) {
        is Number -> this.toDouble()
        is String -> this.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    private fun Any?.asIntOrZero(): Int = when (this) {
        is Number -> this.toInt()
        is String -> this.toIntOrNull()
            ?: this.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 }?.toInt()
            ?: 0
        else -> 0
    }

    private fun Map<String, Any>.resolvedImageIdOrZero(): Int {
        val primary = this["image_id"].asIntOrZero()
        if (primary > 0) {
            return primary
        }
        val fallback = this["id"].asIntOrZero()
        if (fallback > 0) {
            return fallback
        }
        val metadata = this["metadata"] as? Map<*, *> ?: return 0
        val metadataImageId = metadata["image_id"].asIntOrZero()
        if (metadataImageId > 0) {
            return metadataImageId
        }
        return metadata["id"].asIntOrZero()
    }

    private fun reconcileSearchResults(
        currentImages: List<Map<String, Any>>,
        currentSearchResults: List<Map<String, Any>>,
        refreshedImages: List<Map<String, Any>>,
    ): List<Map<String, Any>> {
        val currentImageIds = currentImages.map { it.resolvedImageIdOrZero() }.filter { it > 0 }.toSet()
        val currentSearchIds = currentSearchResults.map { it.resolvedImageIdOrZero() }.filter { it > 0 }.toSet()
        if (currentSearchResults.isEmpty() || currentSearchIds == currentImageIds) {
            return refreshedImages
        }

        val refreshedById = refreshedImages.associateBy { it.resolvedImageIdOrZero() }
        val retainedIds = mutableSetOf<Int>()
        return currentSearchResults.mapNotNull { row ->
            val imageId = row.resolvedImageIdOrZero()
            val refreshed = refreshedById[imageId] ?: return@mapNotNull null
            if (imageId > 0 && !retainedIds.add(imageId)) null else refreshed
        }
    }

    private fun normalizeSelectedImage(image: Map<String, Any>): Map<String, Any> {
        val resolvedId = image.resolvedImageIdOrZero()
        if (resolvedId <= 0 || image["image_id"].asIntOrZero() == resolvedId) {
            return image
        }
        return image + mapOf("image_id" to resolvedId)
    }

    private fun updateImageState(imageId: Int, update: (Map<String, Any>) -> Map<String, Any>): Map<String, Any>? {
        val current = _uiState.value
        var updatedImage: Map<String, Any>? = null
        val updatedImages = current.images.map { image ->
            if (image.resolvedImageIdOrZero() == imageId) {
                val next = update(image)
                if (updatedImage == null) {
                    updatedImage = next
                }
                next
            } else {
                image
            }
        }
        val updatedSearchResults = current.searchResults.map { image ->
            if (image.resolvedImageIdOrZero() == imageId) {
                update(image)
            } else {
                image
            }
        }
        val updatedSelected = current.selectedImage?.let { image ->
            if (image.resolvedImageIdOrZero() == imageId) {
                update(image)
            } else {
                image
            }
        }
        _uiState.value = current.copy(
            images = updatedImages,
            searchResults = updatedSearchResults,
            selectedImage = updatedSelected,
        )
        return updatedImage
    }

    private fun submitReviewAction(
        itemId: String,
        action: String,
        payload: Map<String, Any> = emptyMap(),
    ) {
        runIoAction {
            val updated = StandaloneRuntime.updateReview(itemId = itemId, action = action, payload = payload)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    lastActionMessage = if (updated) "Review updated." else "Review change was not applied.",
                )
            }
            refreshDashboard()
        }
    }

    private suspend fun refreshOperationSummaries(includeTags: Boolean) {
        val refreshedStats = runCatching { StandaloneRuntime.libraryStatistics() }.getOrNull()
        val refreshedCollections = runCatching { StandaloneRuntime.getCollections() }.getOrNull()
        val refreshedFolders = runCatching { StandaloneRuntime.listLibraryFolders(includeDisabled = true) }.getOrNull()
        val refreshedTags = if (includeTags) runCatching { StandaloneRuntime.getTags() }.getOrNull() else null

        withContext(Dispatchers.Main) {
            val current = _uiState.value
            _uiState.value = current.copy(
                stats = refreshedStats ?: current.stats,
                collections = refreshedCollections ?: current.collections,
                libraryFolders = refreshedFolders ?: current.libraryFolders,
                tags = refreshedTags ?: current.tags,
            )
        }
    }

    private suspend fun refreshResourceArtifacts() {
        val refreshedDownloads = runCatching { StandaloneRuntime.listDownloads() }.getOrNull()
        val refreshedKnowledgePacks = runCatching { StandaloneRuntime.listKnowledgePacks() }.getOrNull()
        val refreshedKnowledgeSummary = runCatching { StandaloneRuntime.knowledgeSummary() }.getOrNull()

        withContext(Dispatchers.Main) {
            val current = _uiState.value
            _uiState.value = current.copy(
                downloads = refreshedDownloads ?: current.downloads,
                knowledgePacks = refreshedKnowledgePacks ?: current.knowledgePacks,
                knowledgeSummary = refreshedKnowledgeSummary ?: current.knowledgeSummary,
            )
        }
    }

    private suspend fun refreshLocalAiStateInternal(clearError: Boolean = false) {
        val current = _uiState.value
        val snapshot = collectLocalAiSnapshot(current)
        withContext(Dispatchers.Main) {
            _uiState.value = applyLocalAiSnapshot(_uiState.value, snapshot).copy(
                errorMessage = if (clearError) null else _uiState.value.errorMessage,
            )
        }
    }

    private fun collectLocalAiSnapshot(fallback: AppUiState): LocalAiSnapshot {
        return LocalAiSnapshot(
            overview = runCatching {
                StandaloneRuntime.localAiOverview() +
                    StandaloneRuntime.automationStatus() +
                    StandaloneRuntime.automationReadiness()
                        .mapKeys { (key, _) -> "automation_readiness_$key" }
            }.getOrElse { fallback.aiOverview },
            executionChain = runCatching { StandaloneRuntime.localAiExecutionChain() }.getOrElse { fallback.aiExecutionChain },
            hardwareProfile = runCatching { StandaloneRuntime.latestAiHardwareProfile() }.getOrElse { fallback.aiHardwareProfile },
            backends = runCatching { StandaloneRuntime.listAiBackends() }.getOrElse { fallback.aiBackends },
            settings = runCatching {
                val persisted = StandaloneRuntime.aiSettings()
                    .filterKeys { key ->
                        key != "active_model_id" &&
                            key != "active_model_version" &&
                            !key.startsWith("active_model_id.") &&
                            !key.startsWith("active_model_version.")
                    }
                val sessionActive = fallback.aiSettings.filterKeys { key ->
                    key == "active_model_id" ||
                        key == "active_model_version" ||
                        key.startsWith("active_model_id.") ||
                        key.startsWith("active_model_version.")
                }
                persisted + sessionActive
            }.getOrElse { fallback.aiSettings },
            availableModels = runCatching { StandaloneRuntime.listAvailableAiModels() }.getOrElse { fallback.aiAvailableModels },
            installedModels = runCatching { StandaloneRuntime.listInstalledAiModels() }.getOrElse { fallback.aiInstalledModels },
            tasks = runCatching { StandaloneRuntime.listAiTasks(limit = 250) }.getOrElse { fallback.aiTasks },
            installRuns = runCatching { StandaloneRuntime.listAiInstallRuns(limit = 120) }.getOrElse { fallback.aiInstallRuns },
            executionSessions = runCatching { StandaloneRuntime.listAiExecutionSessions(limit = 200) }.getOrElse { fallback.aiExecutionSessions },
            runtimeHealthSnapshots = runCatching { StandaloneRuntime.listAiRuntimeHealthSnapshots(limit = 200) }.getOrElse { fallback.aiRuntimeHealthSnapshots },
            plugins = runCatching { StandaloneRuntime.listAiPlugins() }.getOrElse { fallback.aiPlugins },
            capabilities = runCatching { StandaloneRuntime.listAiCapabilities() }.getOrElse { fallback.aiCapabilities },
            cacheEntries = runCatching { StandaloneRuntime.listAiCacheEntries(limit = 200) }.getOrElse { fallback.aiCacheEntries },
            reviewQueue = runCatching { StandaloneRuntime.getReviewQueue() }.getOrElse { fallback.reviewQueue },
        )
    }

    private fun applyLocalAiSnapshot(state: AppUiState, snapshot: LocalAiSnapshot): AppUiState {
        return state.copy(
            aiOverview = snapshot.overview,
            aiExecutionChain = snapshot.executionChain,
            aiHardwareProfile = snapshot.hardwareProfile,
            aiBackends = snapshot.backends,
            aiSettings = snapshot.settings,
            aiAvailableModels = snapshot.availableModels,
            aiInstalledModels = snapshot.installedModels,
            aiTasks = snapshot.tasks,
            aiInstallRuns = snapshot.installRuns,
            aiExecutionSessions = snapshot.executionSessions,
            aiRuntimeHealthSnapshots = snapshot.runtimeHealthSnapshots,
            aiPlugins = snapshot.plugins,
            aiCapabilities = snapshot.capabilities,
            aiCacheEntries = snapshot.cacheEntries,
            reviewQueue = snapshot.reviewQueue,
        )
    }

    private fun normalizeAiSettingKey(input: String): String {
        val key = input.trim()
        if (key.startsWith("ai.")) {
            return key.removePrefix("ai.")
        }
        return key
    }

    private fun parseAiSettingValue(key: String, input: String): Any {
        val value = input.trim()
        if (key == "preferred_runtime_order") {
            return value
                .split(',', '|')
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }
        if (value.equals("true", ignoreCase = true)) {
            return true
        }
        if (value.equals("false", ignoreCase = true)) {
            return false
        }
        value.toIntOrNull()?.let { return it }
        value.toLongOrNull()?.let { return it }
        value.toDoubleOrNull()?.let { return it }
        return value
    }

    private fun parseCsvValues(raw: String): List<String> {
        return raw
            .split(',', '|', ';', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun parseBooleanInput(raw: String, defaultValue: Boolean = false): Boolean {
        val value = raw.trim().lowercase()
        if (value.isBlank()) {
            return defaultValue
        }
        return value in setOf("true", "1", "yes", "on")
    }

    private fun buildAiModelPayloadFromForm(form: Map<String, String>): Map<String, Any> {
        val payload = linkedMapOf<String, Any>()
        val modelId = form["model_id"]?.trim().orEmpty()
        if (modelId.isNotBlank()) {
            payload["model_id"] = modelId
        }
        val version = form["version"]?.trim().orEmpty()
        if (version.isNotBlank()) {
            payload["version"] = version
        }
        val displayName = form["display_name"]?.trim().orEmpty()
        if (displayName.isNotBlank()) {
            payload["display_name"] = displayName
        }
        val requiredRuntime = form["required_runtime"]?.trim().orEmpty()
        if (requiredRuntime.isNotBlank()) {
            payload["required_runtime"] = requiredRuntime
        }

        val supportedTasks = parseCsvValues(form["supported_tasks"].orEmpty())
        if (supportedTasks.isNotEmpty()) {
            payload["supported_tasks"] = supportedTasks
        }
        val supportedRuntimes = parseCsvValues(form["supported_runtimes"].orEmpty())
        if (supportedRuntimes.isNotEmpty()) {
            payload["supported_runtimes"] = supportedRuntimes
        }
        val dependencies = parseCsvValues(form["dependencies"].orEmpty())
        if (dependencies.isNotEmpty()) {
            payload["dependencies"] = dependencies
        }

        val source = form["source"]?.trim().orEmpty()
        if (source.isNotBlank()) {
            payload["source"] = source
        }
        val sourceUri = form["source_uri"]?.trim().orEmpty()
        if (sourceUri.isNotBlank()) {
            payload["source_uri"] = sourceUri
        }
        val hash = form["hash_sha256"]?.trim().orEmpty()
        if (hash.isNotBlank()) {
            payload["hash_sha256"] = hash
        }
        form["size_bytes"]?.trim()?.toLongOrNull()?.takeIf { it >= 0L }?.let {
            payload["size_bytes"] = it
        }

        val metadata = form["metadata_json"]?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(::parseModelMetadata)
            ?.toMutableMap()
            ?: linkedMapOf()
        form["inference_contracts_json"]?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(::parseModelMetadata)
            ?.let { metadata["inference_contracts"] = it }
        if (metadata.isNotEmpty()) {
            payload["metadata"] = metadata
        }

        return payload
    }

    private fun parseModelMetadata(raw: String): Map<String, Any> {
        val parsed = LocalAiJson.fromJsonValue(JSONObject(raw)) as? Map<*, *>
            ?: throw IllegalArgumentException("Model metadata must be a JSON object.")
        return parsed.entries.associate { (key, value) ->
            key.toString() to (value ?: "")
        }
    }

    private fun buildExternalImportPayload(form: Map<String, String>): Map<String, Any>? {
        val source = form["source"]?.trim().orEmpty()
        if (source.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Import source is required.")
            return null
        }

        val payload = linkedMapOf<String, Any>(
            "source" to source,
            "replace_existing" to parseBooleanInput(form["replace_existing"].orEmpty(), defaultValue = false),
        )
        val importId = form["import_id"]?.trim().orEmpty()
        if (importId.isNotBlank()) {
            payload["import_id"] = importId
        }
        val sourceType = form["source_type"]?.trim().orEmpty()
        if (sourceType.isNotBlank()) {
            payload["source_type"] = sourceType
        }
        val conflict = form["conflict_strategy"]?.trim().orEmpty()
        if (conflict.isNotBlank()) {
            payload["conflict_strategy"] = conflict
        }
        val csvTable = form["csv_table_name"]?.trim().orEmpty()
        if (csvTable.isNotBlank()) {
            payload["csv_table_name"] = csvTable
        }
        return payload
    }

    private suspend fun importLocalAiModelInternal(
        form: Map<String, String>,
        modelId: String,
        sourcePath: String,
    ) {
        val payload = buildAiModelPayloadFromForm(form).toMutableMap()
        payload["source_path"] = sourcePath

        // Generate an explicit install_id so we can observe progress while the runtime imports.
        val installId = java.util.UUID.randomUUID().toString()
        payload["install_id"] = installId

        // Start a lightweight poller to refresh the install runs so the UI can show live status.
        val pollJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val runs = runCatching { StandaloneRuntime.listAiInstallRuns(limit = 120) }.getOrElse { emptyList() }
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(aiInstallRuns = runs)
                    }
                    val myRun = runs.firstOrNull { run -> run["install_id"]?.toString() == installId }
                    val status = myRun?.get("status")?.toString()?.lowercase().orEmpty()
                    if (status.isNotBlank() && status in setOf("succeeded", "failed", "cancelled", "removed")) {
                        break
                    }
                    kotlinx.coroutines.delay(400)
                }
            } catch (_: Throwable) {
            }
        }

        val result = runCatching { StandaloneRuntime.importLocalAiModel(payload) }.getOrElse { error ->
            mapOf("ok" to false, "message" to (error.message ?: error.javaClass.simpleName))
        }

        // Ensure poller finishes and we've got a final snapshot
        pollJob.cancel()
        pollJob.join()

        val ok = result["ok"].asBooleanOrFalse()
        val failureReason = sequenceOf(result["error"], result["message"])
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull()
            .orEmpty()
        withContext(Dispatchers.Main) {
            _uiState.value = _uiState.value.copy(
                aiLastPipelineResult = result,
                lastActionMessage = if (ok) {
                    "Imported local model: $modelId"
                } else if (failureReason.isNotBlank()) {
                    "Failed to import local model: $modelId — $failureReason"
                } else {
                    "Failed to import local model: $modelId"
                },
                errorMessage = if (ok) null else failureReason.takeIf(String::isNotBlank),
            )
        }

        // Final refresh to pick up repository state and artifacts
        refreshLocalAiStateInternal()
        refreshResourceArtifacts()
    }

    private suspend fun importFusionDatabaseInternal(payload: String, format: String, replaceExisting: Boolean) {
        val result = StandaloneRuntime.importFusionDatabase(
            payload = payload.trim(),
            format = format.trim().ifBlank { "json" },
            replaceExisting = replaceExisting,
        )
        val ok = result["ok"].asBooleanOrFalse()
        withContext(Dispatchers.Main) {
            _uiState.value = _uiState.value.copy(
                aiLastPipelineResult = result,
                lastActionMessage = if (ok) "Fusion database import completed." else "Fusion database import failed.",
                errorMessage = null,
            )
        }
        refreshResourceArtifacts()
        refreshDashboard()
    }

    private suspend fun previewExternalImportInternal(payload: Map<String, Any>) {
        val result = StandaloneRuntime.previewImport(payload)
        val ok = result["ok"].asBooleanOrFalse()
        withContext(Dispatchers.Main) {
            _uiState.value = _uiState.value.copy(
                aiLastPipelineResult = result,
                lastActionMessage = if (ok) "Import preview generated." else "Import preview failed.",
                errorMessage = null,
            )
        }
    }

    private suspend fun validateExternalImportInternal(payload: Map<String, Any>) {
        val result = StandaloneRuntime.validateImport(payload)
        val ok = result["ok"].asBooleanOrFalse()
        withContext(Dispatchers.Main) {
            _uiState.value = _uiState.value.copy(
                aiLastPipelineResult = result,
                lastActionMessage = if (ok) "Import validation passed." else "Import validation failed.",
                errorMessage = null,
            )
        }
    }

    private suspend fun importExternalDatabaseInternal(payload: Map<String, Any>) {
        val result = StandaloneRuntime.importDatabase(payload)
        val ok = result["ok"].asBooleanOrFalse()
        withContext(Dispatchers.Main) {
            _uiState.value = _uiState.value.copy(
                aiLastPipelineResult = result,
                lastActionMessage = if (ok) "External database import completed." else "External database import failed.",
                errorMessage = null,
            )
        }
        refreshResourceArtifacts()
        refreshDashboard()
    }

    private fun readExternalImportDocument(context: Context, uri: Uri, form: Map<String, String>): String {
        return if (form["source_type"]?.trim()?.lowercase() == "sqlite") {
            copyDocumentToAppStorage(context, uri, "knowledge-packs")
        } else {
            readDocumentText(context, uri)
        }
    }

    private fun readDocumentText(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Unable to open selected document.")
        return input.bufferedReader().use { it.readText() }
    }

    private fun copyDocumentToAppStorage(context: Context, uri: Uri, category: String): String {
        val directory = File(context.filesDir, "document-imports/$category")
        require(directory.exists() || directory.mkdirs()) { "Unable to prepare import storage." }
        val destination = File(directory, "${System.currentTimeMillis()}-${safeDocumentFileName(uri)}")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Unable to open selected document.")
        input.use { source ->
            destination.outputStream().use { target -> source.copyTo(target) }
        }
        return destination.absolutePath
    }

    private fun copyDocumentTreeToAppStorage(context: Context, uri: Uri, category: String): String {
        val sourceRoot = DocumentFile.fromTreeUri(context, uri)
            ?: throw IllegalArgumentException("Unable to open selected model package folder.")
        val directory = File(context.filesDir, "document-imports/$category")
        require(directory.exists() || directory.mkdirs()) { "Unable to prepare import storage." }
        val destination = File(directory, "${System.currentTimeMillis()}-${safeDocumentFileName(uri)}")
        require(destination.mkdirs()) { "Unable to prepare package destination." }

        fun copyChildren(source: DocumentFile, target: File) {
            source.listFiles().forEach { child ->
                val name = child.name?.replace(Regex("[^A-Za-z0-9._-]"), "_")?.ifBlank { "package-file" }
                    ?: "package-file"
                val childTarget = File(target, name)
                require(childTarget.canonicalPath.startsWith(destination.canonicalPath + File.separator)) { "Invalid package file path." }
                if (child.isDirectory) {
                    require(childTarget.mkdirs() || childTarget.isDirectory) { "Unable to create package directory '$name'." }
                    copyChildren(child, childTarget)
                } else if (child.isFile) {
                    val input = context.contentResolver.openInputStream(child.uri)
                        ?: throw IllegalArgumentException("Unable to read package file '$name'.")
                    input.use { sourceInput -> childTarget.outputStream().use(sourceInput::copyTo) }
                }
            }
        }

        copyChildren(sourceRoot, destination)
        return destination.absolutePath
    }

    private fun safeDocumentFileName(uri: Uri): String {
        val candidate = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        return candidate.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "import.bin" }
    }

    private fun withActiveModelHint(payload: Map<String, Any>, taskType: String): Map<String, Any> {
        val settings = _uiState.value.aiSettings
        val normalizedTaskType = normalizeTaskType(taskType)
        val scopedSuffix = if (normalizedTaskType.isBlank()) "" else ".${normalizedTaskType}"

        val scopedModelId = if (scopedSuffix.isBlank()) {
            ""
        } else {
            settings["active_model_id$scopedSuffix"]?.toString()?.trim().orEmpty()
        }
        val scopedVersion = if (scopedSuffix.isBlank()) {
            ""
        } else {
            settings["active_model_version$scopedSuffix"]?.toString()?.trim().orEmpty()
        }

        val globalModelId = settings["active_model_id"]?.toString()?.trim().orEmpty()
        val globalVersion = settings["active_model_version"]?.toString()?.trim().orEmpty()

        val modelId = scopedModelId.ifBlank { globalModelId }
        val version = scopedVersion.ifBlank { globalVersion }
        if (modelId.isBlank()) {
            return payload
        }

        val merged = linkedMapOf<String, Any>()
        merged.putAll(payload)
        merged["model_id"] = modelId
        if (version.isNotBlank()) {
            merged["version"] = version
        }
        return merged
    }

    private fun normalizeTaskType(taskType: String): String {
        return taskType
            .trim()
            .lowercase()
            .replace('-', '_')
            .replace(' ', '_')
            .replace(Regex("_+"), "_")
    }

    private fun humanTaskName(taskType: String): String {
        return normalizeTaskType(taskType)
            .split('_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
            .ifBlank { "AI task" }
    }

    private fun Any?.asBooleanOrFalse(): Boolean {
        return when (this) {
            is Boolean -> this
            is Number -> this.toInt() != 0
            is String -> this.equals("true", ignoreCase = true) || this == "1"
            else -> false
        }
    }

    private suspend fun refreshImageInState(imageId: Int, fallback: Map<String, Any>? = null) {
        val latestFromRepo = StandaloneRuntime.searchByImageId(imageId)
        Log.d(
            VM_TRACE_TAG,
            "ViewModel immediate SELECT result: imageId=$imageId repo=${imageSummary(latestFromRepo)} fallback=${imageSummary(fallback)}",
        )
        val latest = latestFromRepo ?: fallback ?: return
        withContext(Dispatchers.Main) {
            val current = _uiState.value
            Log.d(VM_TRACE_TAG, "selectedImage before replacement: imageId=$imageId value=${imageSummary(current.selectedImage)}")
            val updatedImages = current.images.map { image ->
                if (image.resolvedImageIdOrZero() == imageId) latest else image
            }
            val updatedSearchResults = current.searchResults.map { image ->
                if (image.resolvedImageIdOrZero() == imageId) latest else image
            }
            val updatedSelected = current.selectedImage?.let { image ->
                if (image.resolvedImageIdOrZero() == imageId) latest else image
            }
            _uiState.value = current.copy(
                images = updatedImages,
                searchResults = updatedSearchResults,
                selectedImage = updatedSelected,
            )
            Log.d(VM_TRACE_TAG, "selectedImage after replacement: imageId=$imageId value=${imageSummary(updatedSelected)}")
            Log.d(VM_TRACE_TAG, "ViewModel state updated: imageId=$imageId")
        }
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

    private fun runIoAction(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(errorMessage = t.message ?: t.javaClass.simpleName)
                }
            }
        }
    }
}