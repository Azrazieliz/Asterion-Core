@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.ailm.android.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import com.ailm.android.R
import com.ailm.android.runtime.FolderUriUtils
import com.ailm.android.ui.components.AsterionEmptyState
import com.ailm.android.ui.components.AsterionProgressCard
import com.ailm.android.ui.components.AsterionStatusNotice
import com.ailm.android.ui.components.AsterionSectionHeader
import com.ailm.android.ui.components.AsterionMetric
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.ailm.android.ui.navigation.AppDestination
import com.ailm.android.ui.viewmodel.AppUiState
import com.ailm.android.ui.viewmodel.AppViewModel
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

private const val PREFS_NAME = "ailm_android"
private const val PREF_LIBRARY_TREE_URI = "library_tree_uri"
private const val SAF_PERMISSION_TAG = "AilmSafPermission"
private const val UI_TRACE_TAG = "AilmTraceUI"

@Composable
fun ScreenScaffold(
    destination: AppDestination,
    onNavigate: (AppDestination) -> Unit,
    appViewModel: AppViewModel,
) {
    val state by appViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val prefs = remember(context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val uriText = uri.toString()
            val persisted = persistAndVerifyTreePermission(context, uri)
            if (persisted) {
                prefs.edit().putString(PREF_LIBRARY_TREE_URI, uriText).apply()
                appViewModel.setLibraryUri(uriText)
            } else {
                prefs.edit().remove(PREF_LIBRARY_TREE_URI).apply()
                appViewModel.setLibraryUri("")
            }
            appViewModel.refreshDashboard()
        }
    }

    val folderManagerAddLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val persisted = persistAndVerifyTreePermission(context, uri)
            if (persisted) {
                val uriText = uri.toString()
                appViewModel.setLibraryUri(uriText)
                appViewModel.addLibraryFolder(uriText)
            }
        }
    }

    val cloudImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                val resolver = context.contentResolver
                val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                val persisted = runCatching {
                    resolver.takePersistableUriPermission(uri, readWrite)
                }.isSuccess
                if (!persisted) {
                    runCatching {
                        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
            }
            appViewModel.importCloudImages(uris)
        }
    }

    var selectedModelDocument by remember { mutableStateOf<Uri?>(null) }
    var selectedModelPackageTree by remember { mutableStateOf<Uri?>(null) }
    var selectedFusionDocument by remember { mutableStateOf<Uri?>(null) }
    var selectedKnowledgeDocument by remember { mutableStateOf<Uri?>(null) }
    var selectedKnowledgeDocumentName by remember { mutableStateOf("") }
    var selectedKnowledgeDocumentIsArchive by remember { mutableStateOf(false) }
    var selectedKnowledgePackDocuments by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var replacingKnowledgePackName by remember { mutableStateOf("") }

    val modelDocumentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            selectedModelDocument = uri
            selectedModelPackageTree = null
        }
    }

    val modelPackagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null && persistAndVerifyTreePermission(context, uri)) {
            selectedModelPackageTree = uri
            selectedModelDocument = null
        }
    }

    val fusionDocumentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            selectedFusionDocument = uri
        }
    }

    val knowledgeDocumentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            selectedKnowledgeDocument = uri
            val displayName = runCatching {
                DocumentFile.fromSingleUri(context, uri)?.name
            }.getOrNull().orEmpty().ifBlank {
                uri.lastPathSegment?.substringAfterLast('/').orEmpty()
            }
            val mime = runCatching {
                context.contentResolver.getType(uri)?.lowercase().orEmpty()
            }.getOrDefault("")
            val hasZipSignature = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val signature = ByteArray(4)
                    val count = input.read(signature)
                    count == 4 &&
                        signature[0] == 0x50.toByte() &&
                        signature[1] == 0x4B.toByte() &&
                        (
                            (signature[2] == 0x03.toByte() && signature[3] == 0x04.toByte()) ||
                                (signature[2] == 0x05.toByte() && signature[3] == 0x06.toByte()) ||
                                (signature[2] == 0x07.toByte() && signature[3] == 0x08.toByte())
                            )
                } ?: false
            }.getOrDefault(false)
            selectedKnowledgeDocumentName = displayName.ifBlank { "Selected document" }
            selectedKnowledgeDocumentIsArchive =
                displayName.lowercase().endsWith(".zip") ||
                    mime in setOf(
                        "application/zip",
                        "application/x-zip-compressed",
                        "application/x-zip",
                    ) ||
                    hasZipSignature
        }
    }

    val knowledgePackDocumentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        selectedKnowledgePackDocuments = uris
    }

    val knowledgePackReplacementPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && replacingKnowledgePackName.isNotBlank()) {
            appViewModel.replaceKnowledgePackDocument(replacingKnowledgePackName, uri)
        }
        replacingKnowledgePackName = ""
    }

    LaunchedEffect(Unit) {
        val persistedUri = prefs.getString(PREF_LIBRARY_TREE_URI, null).orEmpty()
        val restoredUri = if (persistedUri.isNotBlank() && hasPersistedTreePermission(context, persistedUri)) {
            persistedUri
        } else {
            if (persistedUri.isNotBlank()) {
                Log.w(SAF_PERMISSION_TAG, "Clearing stale SAF URI with missing persisted permission: $persistedUri")
                prefs.edit().remove(PREF_LIBRARY_TREE_URI).apply()
            }
            ""
        }
        appViewModel.initializeConfiguration(restoredUri)
        appViewModel.refreshDashboard()
        appViewModel.refreshScanStatus()
    }

    val chooseFolder: () -> Unit = { folderPickerLauncher.launch(null) }
    val addFolderToManager: () -> Unit = { folderManagerAddLauncher.launch(null) }
    val importCloudImages: () -> Unit = { cloudImagePickerLauncher.launch(arrayOf("image/*")) }
    val startTeraBoxLogin: () -> Unit = {
        val url = appViewModel.teraBoxAuthorizationUrl()
        if (url.isNotBlank()) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
    }
    val chooseModelDocument: () -> Unit = {
        modelDocumentPickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
    }
    val chooseFusionDocument: () -> Unit = {
        fusionDocumentPickerLauncher.launch(arrayOf("application/json", "text/*"))
    }
    val chooseKnowledgeDocument: () -> Unit = {
        knowledgeDocumentPickerLauncher.launch(
            arrayOf(
                "application/json",
                "application/zip",
                "application/octet-stream",
                "text/*",
            ),
        )
    }
    val chooseKnowledgePacks: () -> Unit = {
        knowledgePackDocumentPickerLauncher.launch(arrayOf("application/json", "application/zip", "application/octet-stream", "text/*"))
    }
    val chooseKnowledgePackReplacement: (String) -> Unit = { filename ->
        replacingKnowledgePackName = filename
        knowledgePackReplacementPickerLauncher.launch(arrayOf("application/json", "application/zip", "application/octet-stream", "text/*"))
    }

    when (destination) {
        AppDestination.Splash -> NativeSplashScreen()

        AppDestination.FirstLaunchWizard -> FirstLaunchScreen(
            state = state,
            onChooseFolder = chooseFolder,
            onStartScan = appViewModel::startScan,
            onPauseScan = appViewModel::pauseScan,
            onResumeScan = appViewModel::resumeScan,
            onCancelScan = appViewModel::cancelScan,
            onRefreshStatus = appViewModel::refreshScanStatus,
            onNavigate = onNavigate,
        )

        AppDestination.Dashboard -> DashboardScreen(
            state = state,
            onRefresh = appViewModel::rescanEnabledFolders,
            onStartScan = appViewModel::startScan,
            onPauseScan = appViewModel::pauseScan,
            onResumeScan = appViewModel::resumeScan,
            onCancelScan = appViewModel::cancelScan,
            onOpenRecentImage = { item ->
                appViewModel.setActiveViewerContext(state.images)
                appViewModel.selectImage(item)
                onNavigate(AppDestination.ImageViewer)
            },
            onNavigate = onNavigate,
        )

        AppDestination.LibraryBrowser -> LibraryBrowserScreen(
            state = state,
            onSearchByFilename = appViewModel::searchByFilename,
            onSearchByImageId = appViewModel::searchByImageId,
            onAdvancedSearch = appViewModel::runAdvancedSearch,
            onClearResults = appViewModel::clearSearchResults,
            onExecuteFileOperations = appViewModel::executeFileOperations,
            onExecuteFileOperationSequence = appViewModel::executeFileOperationSequence,
            onUndoFileOperations = appViewModel::undoLastFileOperations,
            onOpenImage = { item, visibleList ->
                appViewModel.setActiveViewerContext(visibleList)
                appViewModel.selectImage(item)
                onNavigate(AppDestination.ImageViewer)
            },
            onNavigate = onNavigate,
        )

        AppDestination.FolderBrowser -> FolderBrowserScreen(
            state = state,
            onChooseFolder = chooseFolder,
            onAddFolder = addFolderToManager,
            onImportCloudImages = importCloudImages,
            onStartScan = appViewModel::startScan,
            onRescanFolder = appViewModel::rescanFolder,
            onRescanEnabledFolders = appViewModel::rescanEnabledFolders,
            onSetFolderEnabled = appViewModel::setLibraryFolderEnabled,
            onRemoveFolder = appViewModel::removeLibraryFolder,
            onRefreshStatus = appViewModel::refreshScanStatus,
            onNavigate = onNavigate,
        )

        AppDestination.ImageViewer -> ImageViewerScreen(
            state = state,
            imageUrl = appViewModel.selectedImageUrl(),
            onSetTags = appViewModel::setImageTags,
            onSelectImage = appViewModel::selectImage,
            onNavigate = onNavigate,
        )

        AppDestination.RecognitionResults -> RecognitionWorkbenchScreen(
            state = state,
            onRefreshAi = appViewModel::refreshLocalAiState,
            onDetectHardware = appViewModel::detectAiHardwareProfile,
            onValidateInfrastructure = appViewModel::validateLocalAiInfrastructure,
            onRunPipeline = appViewModel::runSelectedImageAiPipeline,
            onRunMultiStagePipeline = appViewModel::runSelectedImageMultiStagePipeline,
            onRunBatchPipeline = { taskType -> appViewModel.runBatchAiPipelineForVisibleImages(taskType) },
            onEnqueueTask = appViewModel::enqueueSelectedImageAiTask,
            onNavigate = onNavigate,
        )

        AppDestination.ReviewQueue -> ReviewQueueScreen(
            items = state.reviewQueue,
            onApprove = appViewModel::approveReview,
            onCorrect = appViewModel::correctReview,
            onReject = appViewModel::rejectReview,
            onUndo = appViewModel::undoReview,
            onNavigate = onNavigate,
        )

        AppDestination.Search -> SearchScreen(
            title = "Search",
            state = state,
            mode = "search",
            onSearchByFilename = appViewModel::searchByFilename,
            onSearchByImageId = appViewModel::searchByImageId,
            onAdvancedSearch = appViewModel::runAdvancedSearch,
            onSemanticSearch = appViewModel::runSemanticSearch,
            onClearResults = appViewModel::clearSearchResults,
            onOpenImage = { item ->
                // For search screens, the visible list is `state.searchResults` already
                appViewModel.setActiveViewerContext(state.searchResults.ifEmpty { state.images })
                appViewModel.selectImage(item)
                onNavigate(AppDestination.ImageViewer)
            },
            onNavigate = onNavigate,
        )

        AppDestination.AdvancedSearch -> SearchScreen(
            title = "Advanced Search",
            state = state,
            mode = "advanced",
            onSearchByFilename = appViewModel::searchByFilename,
            onSearchByImageId = appViewModel::searchByImageId,
            onAdvancedSearch = appViewModel::runAdvancedSearch,
            onSemanticSearch = appViewModel::runSemanticSearch,
            onClearResults = appViewModel::clearSearchResults,
            onOpenImage = { item ->
                appViewModel.setActiveViewerContext(state.searchResults.ifEmpty { state.images })
                appViewModel.selectImage(item)
                onNavigate(AppDestination.ImageViewer)
            },
            onNavigate = onNavigate,
        )

        AppDestination.SemanticSearch -> SearchScreen(
            title = "Semantic Search",
            state = state,
            mode = "semantic",
            onSearchByFilename = appViewModel::searchByFilename,
            onSearchByImageId = appViewModel::searchByImageId,
            onAdvancedSearch = appViewModel::runAdvancedSearch,
            onSemanticSearch = appViewModel::runSemanticSearch,
            onClearResults = appViewModel::clearSearchResults,
            onOpenImage = { item ->
                appViewModel.setActiveViewerContext(state.searchResults.ifEmpty { state.images })
                appViewModel.selectImage(item)
                onNavigate(AppDestination.ImageViewer)
            },
            onNavigate = onNavigate,
        )

        AppDestination.CharacterPage -> AiTaskFilterScreen(
            title = "Character Recognition",
            taskTypeFilter = "character_recognition",
            tasks = state.aiTasks,
            onNavigate = onNavigate,
        )

        AppDestination.SeriesPage -> AiTaskFilterScreen(
            title = "Series Recognition",
            taskTypeFilter = "series_recognition",
            tasks = state.aiTasks,
            onNavigate = onNavigate,
        )

        AppDestination.Collections -> CollectionsScreen(
            collections = state.collections,
            onOpenCollection = { folderUri ->
                if (folderUri.isNotBlank()) {
                    appViewModel.runAdvancedSearch(
                        mapOf(
                            "collection" to folderUri,
                            "sort_by" to "date_added",
                            "sort_direction" to "desc",
                            "page" to 1,
                            "page_size" to 0,
                        ),
                    )
                    onNavigate(AppDestination.Search)
                }
            },
            onNavigate = onNavigate,
        )

        AppDestination.Tags -> TagScreen(
            tags = state.tags,
            onSearchTag = { tag ->
                appViewModel.runAdvancedSearch(
                    mapOf(
                        "tags" to listOf(tag),
                        "sort_by" to "date_added",
                        "sort_direction" to "desc",
                        "page" to 1,
                        "page_size" to 0,
                    ),
                )
                onNavigate(AppDestination.Search)
            },
            onNavigate = onNavigate,
        )

        AppDestination.BulkOperations -> FileManagerScreen(
            state = state,
            onPreview = appViewModel::previewFileOperations,
            onExecute = appViewModel::executeFileOperations,
            onUndo = appViewModel::undoLastFileOperations,
            onClear = appViewModel::clearFileOperationPreview,
            onNavigate = onNavigate,
        )

        AppDestination.KnowledgePacks -> KnowledgePackManagerScreen(
            state = state,
            selectedPackNames = selectedKnowledgePackDocuments.map { uri -> uri.lastPathSegment.orEmpty() },
            onChoosePacks = chooseKnowledgePacks,
            onImportPacks = { appViewModel.importKnowledgePackDocuments(selectedKnowledgePackDocuments) },
            onPreviewPack = appViewModel::previewKnowledgePack,
            onReplacePack = chooseKnowledgePackReplacement,
            onRemovePack = appViewModel::removeKnowledgePack,
            onNavigate = onNavigate,
        )

        AppDestination.FusionDatabase -> FusionDatabaseScreen(
            state = state,
            onRefresh = appViewModel::refreshFusionManagement,
            onExport = appViewModel::exportFusionManagement,
            onRebuild = appViewModel::rebuildFusionManagement,
            onNavigate = onNavigate,
        )

        AppDestination.Downloads -> MapListScreen(
            title = "Downloads",
            items = state.downloads,
            onNavigate = onNavigate,
        )

        AppDestination.Automation -> AiAutomationScreen(
            state = state,
            onRefreshAi = appViewModel::refreshLocalAiState,
            onStartAutomation = { appViewModel.startLibraryAutomation(context, forceAll = false) },
            onReprocessAll = { appViewModel.startLibraryAutomation(context, forceAll = true) },
            onPauseAutomation = { appViewModel.pauseLibraryAutomation(context) },
            onResumeAutomation = { appViewModel.resumeLibraryAutomation(context) },
            onStopAutomation = { appViewModel.stopLibraryAutomation(context) },
            onNavigate = onNavigate,
        )

        AppDestination.PluginManager -> AiModelManagerScreen(
            state = state,
            onRefreshAi = appViewModel::refreshLocalAiState,
            onDetectHardware = appViewModel::detectAiHardwareProfile,
            onValidateInfrastructure = appViewModel::validateLocalAiInfrastructure,
            onDetectModelUpdates = appViewModel::detectAiModelUpdates,
            onPruneCache = appViewModel::pruneAiCache,
            onVerifyModel = appViewModel::verifyInstalledAiModel,
            onRemoveModel = appViewModel::removeInstalledAiModel,
            onUpdateAiSetting = appViewModel::updateAiSetting,
            onRegisterAvailableModel = appViewModel::registerAvailableAiModel,
            selectedModelDocumentName = selectedModelDocument?.lastPathSegment
                ?: selectedModelPackageTree?.lastPathSegment.orEmpty(),
            onChooseModelDocument = chooseModelDocument,
            onChooseModelPackageDirectory = { modelPackagePickerLauncher.launch(null) },
            onImportModelDocument = { form ->
                selectedModelDocument?.let { uri ->
                    appViewModel.importLocalAiModelDocument(context, uri, form)
                } ?: selectedModelPackageTree?.let { uri ->
                    appViewModel.importLocalAiModelPackageTree(context, uri, form)
                }
            },
            onRegisterModelDownload = appViewModel::registerAiModelDownload,
            onSetActiveModel = appViewModel::setActiveAiModel,
            onClearActiveModel = appViewModel::clearActiveAiModel,
            selectedKnowledgeDocumentName = selectedKnowledgeDocumentName,
            selectedKnowledgeDocumentAvailable = selectedKnowledgeDocument != null,
            selectedKnowledgeDocumentIsArchive = selectedKnowledgeDocumentIsArchive,
            onChooseKnowledgeDocument = chooseKnowledgeDocument,
            onPreviewKnowledgeDocument = { form ->
                selectedKnowledgeDocument?.let { uri ->
                    appViewModel.previewKnowledgePackDocument(context, uri, form)
                }
            },
            onValidateKnowledgeDocument = { form ->
                selectedKnowledgeDocument?.let { uri ->
                    appViewModel.validateKnowledgePackDocument(context, uri, form)
                }
            },
            onImportKnowledgeDocument = { form ->
                selectedKnowledgeDocument?.let { uri -> appViewModel.importKnowledgePackDocuments(listOf(uri)) }
            },
            selectedFusionDocumentName = selectedFusionDocument?.lastPathSegment.orEmpty(),
            onChooseFusionDocument = chooseFusionDocument,
            onPreviewFusionDocument = { format, replaceExisting ->
                selectedFusionDocument?.let { uri ->
                    appViewModel.previewExternalImportDocument(
                        context,
                        uri,
                        mapOf(
                            "source_type" to if (format == "workbook_compat") "workbook_compat" else "json",
                            "replace_existing" to replaceExisting.toString(),
                        ),
                    )
                }
            },
            onValidateFusionDocument = { format, replaceExisting ->
                selectedFusionDocument?.let { uri ->
                    appViewModel.validateExternalImportDocument(
                        context,
                        uri,
                        mapOf(
                            "source_type" to if (format == "workbook_compat") "workbook_compat" else "json",
                            "replace_existing" to replaceExisting.toString(),
                        ),
                    )
                }
            },
            onImportFusionDocument = { format, replaceExisting ->
                selectedFusionDocument?.let { uri ->
                    appViewModel.importFusionDatabaseDocument(context, uri, format, replaceExisting)
                }
            },
            onExportFusionSnapshot = appViewModel::exportFusionDatabaseSnapshot,
            onValidateFusionDatabase = appViewModel::validateFusionDatabase,
            onRebuildFusionDatabase = appViewModel::rebuildFusionManagement,
            onRemoveKnowledgePack = appViewModel::removeKnowledgePack,
            onRollbackImport = appViewModel::rollbackExternalImport,
            onCancelImport = appViewModel::cancelExternalImport,
            onNavigate = onNavigate,
        )

        AppDestination.Statistics -> StatisticsScreen(
            state = state,
            onRefresh = {
                appViewModel.refreshDashboard()
                appViewModel.refreshScanStatistics()
            },
            onNavigate = onNavigate,
        )

        AppDestination.Logs -> RuntimeLogsScreen(
            state = state,
            onRefresh = {
                appViewModel.refreshDashboard()
                appViewModel.refreshLocalAiState()
            },
            onNavigate = onNavigate,
        )

        AppDestination.Settings -> SettingsScreen(
            state = state,
            onChooseFolder = chooseFolder,
            onAddFolder = addFolderToManager,
            onConfigureTeraBox = appViewModel::configureTeraBox,
            onStartTeraBoxLogin = startTeraBoxLogin,
            onRefreshTeraBox = appViewModel::refreshTeraBoxStatus,
            onDisconnectTeraBox = appViewModel::disconnectTeraBox,
            onAddTeraBoxLibraryRoot = appViewModel::addTeraBoxLibraryRoot,
            onSetFolderEnabled = appViewModel::setLibraryFolderEnabled,
            onRemoveFolder = appViewModel::removeLibraryFolder,
            onRescanFolder = appViewModel::rescanFolder,
            onRescanEnabledFolders = appViewModel::rescanEnabledFolders,
            onRebuildSearchIndex = appViewModel::rebuildSearchIndex,
            onOptimizeDatabase = appViewModel::optimizeDatabase,
            onMaintainThumbnailCache = appViewModel::maintainThumbnailCache,
            onClearThumbnailCache = appViewModel::clearThumbnailCache,
            onUpdateSetting = appViewModel::updateLibrarySetting,
            onLoadSetting = appViewModel::loadLibrarySetting,
            onRefresh = {
                appViewModel.refreshDashboard()
                appViewModel.refreshScanStatus()
            },
            onNavigate = onNavigate,
        )

        AppDestination.About -> AsterionAboutScreen(onNavigate)
    }
}

private fun persistAndVerifyTreePermission(context: Context, uri: Uri): Boolean {
    val resolver = context.contentResolver
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    val granted = runCatching {
        resolver.takePersistableUriPermission(uri, flags)
        true
    }.getOrElse { error ->
        Log.e(SAF_PERMISSION_TAG, "takePersistableUriPermission failed for $uri", error)
        false
    }
    if (!granted) {
        return false
    }
    return hasPersistedTreePermission(context, uri.toString())
}

private fun hasPersistedTreePermission(context: Context, uriText: String): Boolean {
    val resolver = context.contentResolver
    val target = Uri.parse(uriText).normalizeScheme().toString()
    val match = resolver.persistedUriPermissions.firstOrNull {
        it.uri.normalizeScheme().toString() == target
    }
    if (match == null) {
        Log.w(SAF_PERMISSION_TAG, "No persisted URI permission found for $uriText")
        return false
    }
    val hasRead = match.isReadPermission
    val hasWrite = match.isWritePermission
    if (!hasRead || !hasWrite) {
        Log.w(
            SAF_PERMISSION_TAG,
            "Persisted URI permission incomplete for $uriText (read=$hasRead, write=$hasWrite)",
        )
        return false
    }
    return true
}

@Composable
private fun FirstLaunchScreen(
    state: AppUiState,
    onChooseFolder: () -> Unit,
    onStartScan: () -> Unit,
    onPauseScan: () -> Unit,
    onResumeScan: () -> Unit,
    onCancelScan: () -> Unit,
    onRefreshStatus: () -> Unit,
    onNavigate: (AppDestination) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("First Launch Wizard", style = MaterialTheme.typography.headlineMedium)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onRefreshStatus) {
                Text("Refresh Status")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Current library folder:")
                Text(
                    text = state.selectedLibraryUri.displayFolderLabel("No folder selected yet."),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Button(onClick = onChooseFolder) {
                    Text(if (state.selectedLibraryUri.isBlank()) "Choose Folder (SAF)" else "Change Folder")
                }
            }
        }

        ScanControlsCard(
            state = state,
            onStartScan = onStartScan,
            onPauseScan = onPauseScan,
            onResumeScan = onResumeScan,
            onCancelScan = onCancelScan,
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { onNavigate(AppDestination.Dashboard) }, enabled = state.firstLaunchCompleted) {
                Text("Finish Setup")
            }
            Button(onClick = { onNavigate(AppDestination.FolderBrowser) }) {
                Text("Open Folder Browser")
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    state: AppUiState,
    onRefresh: () -> Unit,
    onStartScan: () -> Unit,
    onPauseScan: () -> Unit,
    onResumeScan: () -> Unit,
    onCancelScan: () -> Unit,
    onOpenRecentImage: (Map<String, Any>) -> Unit,
    onNavigate: (AppDestination) -> Unit,
) {
    val totalImages = state.stats["total_images"] ?: state.images.size
    val totalFolders = state.stats["total_folders"] ?: state.libraryFolders.size
    val healthLabel = state.health["status"] ?: state.health["healthy"] ?: "Unknown"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsterionSectionHeader(
            title = "AsterionCore",
            detail = "Library overview",
        )

        state.errorMessage?.let { AsterionStatusNotice(it, isError = true) }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Library", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    AsterionMetric(totalImages.toString(), "Images", Modifier.weight(1f))
                    AsterionMetric(totalFolders.toString(), "Folders", Modifier.weight(1f))
                    AsterionMetric(state.tags.size.toString(), "Tags", Modifier.weight(1f))
                }
                Text(
                    "Status: $healthLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Open", style = MaterialTheme.typography.titleMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { onNavigate(AppDestination.LibraryBrowser) }) { Text("Library") }
                    Button(onClick = { onNavigate(AppDestination.Search) }) { Text("Search") }
                    Button(onClick = { onNavigate(AppDestination.Automation) }) { Text("Run Automation") }
                    Button(onClick = { onNavigate(AppDestination.ReviewQueue) }) { Text("Review") }
                    AssistChip(onClick = { onNavigate(AppDestination.PluginManager) }, label = { Text("Models") })
                    AssistChip(onClick = { onNavigate(AppDestination.RecognitionResults) }, label = { Text("Manual AI") })
                }
            }
        }

        if (state.images.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Recent", style = MaterialTheme.typography.titleMedium)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(end = 18.dp),
                ) {
                    items(state.images.take(12)) { image ->
                        val title = image["filename"]?.toString().orEmpty().ifBlank { "Untitled" }
                        val preview = image["file_url"]?.toString()?.takeIf { it.isNotBlank() }
                            ?: image["thumbnail_url"]?.toString()?.takeIf { it.isNotBlank() }
                        Card(
                            modifier = Modifier
                                .width(252.dp)
                                .clickable { onOpenRecentImage(image) },
                        ) {
                            Column(
                                modifier = Modifier.padding(7.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                if (!preview.isNullOrBlank()) {
                                    ImageTile(
                                        model = preview,
                                        contentDescription = title,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(340.dp),
                                        contentScale = ContentScale.Fit,
                                    )
                                }
                                Text(
                                    title,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        val scanActive = state.scanStatus.lowercase() in setOf("running", "paused", "queued", "in_progress")
        if (scanActive) {
            ScanControlsCard(
                state = state,
                onStartScan = onStartScan,
                onPauseScan = onPauseScan,
                onResumeScan = onResumeScan,
                onCancelScan = onCancelScan,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (totalFolders.toString() == "0") "No library folder configured" else "Library indexed • $totalFolders folder(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { onNavigate(AppDestination.FolderBrowser) }) {
                    Text("Manage folders")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { onNavigate(AppDestination.FusionDatabase) }) { Text("Knowledge & Fusion") }
            TextButton(onClick = { onNavigate(AppDestination.Settings) }) { Text("Settings") }
        }
    }
}

@Composable
private fun NativeSplashScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(R.drawable.asterioncore_splash),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun FolderBrowserScreen(
    state: AppUiState,
    onChooseFolder: () -> Unit,
    onAddFolder: () -> Unit,
    onImportCloudImages: () -> Unit,
    onStartScan: () -> Unit,
    onRescanFolder: (String) -> Unit,
    onRescanEnabledFolders: () -> Unit,
    onSetFolderEnabled: (String, Boolean) -> Unit,
    onRemoveFolder: (String) -> Unit,
    onRefreshStatus: () -> Unit,
    onNavigate: (AppDestination) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Folder Browser", style = MaterialTheme.typography.headlineMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Selected folder")
                Text(
                    text = state.selectedLibraryUri.displayFolderLabel("No SAF folder selected."),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onChooseFolder) {
                        Text(if (state.selectedLibraryUri.isBlank()) "Choose Folder" else "Change Folder")
                    }
                    Button(onClick = onAddFolder) {
                        Text("Add Folder")
                    }
                    Button(
                        onClick = onImportCloudImages,
                        enabled = state.selectedLibraryUri.isNotBlank(),
                    ) {
                        Text("Import from TeraBox / Cloud")
                    }
                    Button(onClick = onRefreshStatus) {
                        Text("Refresh Status")
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onStartScan, enabled = state.selectedLibraryUri.isNotBlank()) {
                        Text("Scan Selected")
                    }
                    Button(onClick = onRescanEnabledFolders) {
                        Text("Rescan Enabled")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Folder Manager", style = MaterialTheme.typography.titleMedium)
                if (state.libraryFolders.isEmpty()) {
                    Text("No folders registered yet.")
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.libraryFolders) { folder ->
                            val folderUri = folder["folder_uri"]?.toString().orEmpty()
                            val enabled = folder["enabled"] as? Boolean ?: false
                            val lastStatus = folder["last_scan_status"]?.toString().orEmpty()
                            val lastCount = folder["last_scan_count"]?.toString().orEmpty()
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(folderUri.displayFolderLabel("(no folder)"), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("Enabled: $enabled | Last status: ${lastStatus.ifBlank { "n/a" }} | Last count: ${lastCount.ifBlank { "0" }}")
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { onSetFolderEnabled(folderUri, !enabled) }, enabled = folderUri.isNotBlank()) {
                                            Text(if (enabled) "Disable" else "Enable")
                                        }
                                        Button(onClick = { onRescanFolder(folderUri) }, enabled = folderUri.isNotBlank()) {
                                            Text("Rescan")
                                        }
                                        Button(onClick = { onRemoveFolder(folderUri) }, enabled = folderUri.isNotBlank()) {
                                            Text("Remove")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onNavigate(AppDestination.Dashboard) }) {
                Text("Dashboard")
            }
            Button(onClick = { onNavigate(AppDestination.LibraryBrowser) }) {
                Text("Library")
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Suppress("EXPERIMENTAL_API_USAGE")
private fun LibraryBrowserScreen(
    state: AppUiState,
    onSearchByFilename: (String) -> Unit,
    onSearchByImageId: (String) -> Unit,
    onAdvancedSearch: (Map<String, Any>) -> Unit,
    onClearResults: () -> Unit,
    onExecuteFileOperations: (Map<String, Any>) -> Unit,
    onExecuteFileOperationSequence: (List<Map<String, Any>>) -> Unit,
    onUndoFileOperations: () -> Unit,
    onOpenImage: (Map<String, Any>, List<Map<String, Any>>) -> Unit,
    onNavigate: (AppDestination) -> Unit,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var imageIdQuery by rememberSaveable { mutableStateOf("") }
    var fullTextQuery by rememberSaveable { mutableStateOf("") }
    var sortBy by rememberSaveable { mutableStateOf("date_added") }
    var sortDirection by rememberSaveable { mutableStateOf("desc") }
    var showSearch by rememberSaveable { mutableStateOf(true) }
    var showSort by rememberSaveable { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showFileTools by rememberSaveable { mutableStateOf(false) }
    var tagsQuery by rememberSaveable { mutableStateOf("") }
    var minWidthText by rememberSaveable { mutableStateOf("") }
    var minHeightText by rememberSaveable { mutableStateOf("") }
    var formatQuery by rememberSaveable { mutableStateOf("") }
    var orientationQuery by rememberSaveable { mutableStateOf("any") }
    var folderQuery by rememberSaveable { mutableStateOf("") }
    var includeHidden by rememberSaveable { mutableStateOf(false) }
    var missingOnly by rememberSaveable { mutableStateOf(false) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedImageIds by rememberSaveable { mutableStateOf(setOf<Int>()) }
    var selectedFolderUri by rememberSaveable { mutableStateOf("") }
    var selectedTargetFolderUri by rememberSaveable { mutableStateOf("") }
    var conflictMode by rememberSaveable { mutableStateOf("rename") }
    var targetMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var conflictMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var libraryPage by rememberSaveable { mutableIntStateOf(1) }
    val libraryPageSize = 200

    var showCreateFolderDialog by rememberSaveable { mutableStateOf(false) }
    var showRenameImageDialog by rememberSaveable { mutableStateOf(false) }
    var showBatchRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showRenameFolderDialog by rememberSaveable { mutableStateOf(false) }
    var showMoveDialog by rememberSaveable { mutableStateOf(false) }
    var showCopyDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    var renameImageName by rememberSaveable { mutableStateOf("") }
    var renamePattern by rememberSaveable { mutableStateOf("renamed_{n}") }
    var renameFolderName by rememberSaveable { mutableStateOf("") }
    var createFolderName by rememberSaveable { mutableStateOf("") }
    var moveDeleteSourceFolderAfterMove by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(
        searchQuery,
        imageIdQuery,
        fullTextQuery,
        sortBy,
        sortDirection,
        tagsQuery,
        minWidthText,
        minHeightText,
        formatQuery,
        orientationQuery,
        folderQuery,
        includeHidden,
        missingOnly,
    ) {
        libraryPage = 1
    }

    LaunchedEffect(
        searchQuery,
        imageIdQuery,
        fullTextQuery,
        sortBy,
        sortDirection,
        tagsQuery,
        minWidthText,
        minHeightText,
        formatQuery,
        orientationQuery,
        folderQuery,
        includeHidden,
        missingOnly,
        libraryPage,
    ) {
        delay(250)
        val imageId = imageIdQuery.trim()
        if (imageId.isNotBlank()) {
            onSearchByImageId(imageId)
            return@LaunchedEffect
        }

        val payload = mutableMapOf<String, Any>(
            "query" to searchQuery,
            "full_text" to fullTextQuery,
            "sort_by" to sortBy,
            "sort_direction" to sortDirection,
            "include_hidden" to includeHidden,
            "missing_only" to missingOnly,
            "include_inactive" to missingOnly,
            "page" to libraryPage,
            "page_size" to libraryPageSize,
        )
        minWidthText.toIntOrNull()?.let { payload["min_width"] = it }
        minHeightText.toIntOrNull()?.let { payload["min_height"] = it }
        if (formatQuery.isNotBlank()) payload["file_format"] = formatQuery
        if (orientationQuery != "any") payload["orientation"] = orientationQuery
        if (folderQuery.isNotBlank()) payload["folder_query"] = folderQuery
        val tags = tagsQuery.split(',', '|').map { it.trim() }.filter { it.isNotBlank() }
        if (tags.isNotEmpty()) payload["tags"] = tags
        onAdvancedSearch(payload)
    }

    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val adaptiveMinSize = if (screenWidthDp >= 900) 220.dp else 180.dp
    val images = state.searchResults
    val totalCount = state.totalResults
    val totalPages = maxOf(1, (totalCount + libraryPageSize - 1) / libraryPageSize)
    val safeLibraryPage = libraryPage.coerceIn(1, totalPages)
    val allFolders = state.libraryFolders.mapNotNull { it["folder_uri"]?.toString() }.distinct()
    val selectedFolderImages = if (selectedFolderUri.isBlank()) emptyList() else images.filter { it.folderUriValue() == selectedFolderUri }
    val visibleList = if (selectedFolderUri.isBlank()) images else selectedFolderImages
    val selectedFolderImageIds = selectedFolderImages.mapNotNull { it.imageId() }.toSet()
    val visibleImageIds = (if (selectedFolderUri.isBlank()) images else selectedFolderImages).mapNotNull { it.imageId() }.toSet()
    val selectedImageFromViewerId = state.selectedImage?.imageId()
    val selectedImageIdsForDirectOps = if (selectedImageIds.isNotEmpty()) {
        selectedImageIds
    } else {
        selectedImageFromViewerId?.let { setOf(it) } ?: emptySet()
    }
    val selectedIdsForMoveCopy = when {
        selectedImageIds.isNotEmpty() -> selectedImageIds
        selectedFolderUri.isNotBlank() -> selectedFolderImageIds
        else -> selectedImageIdsForDirectOps
    }

    val selectedOperationKind = when {
        selectedImageIdsForDirectOps.isNotEmpty() -> "images"
        selectedFolderUri.isNotBlank() -> "folder"
        else -> "none"
    }

    val canCreateFolder = allFolders.isNotEmpty()
    val canRename = selectedImageIdsForDirectOps.isNotEmpty() || selectedFolderUri.isNotBlank()
    val canMove = selectedIdsForMoveCopy.isNotEmpty()
    val canCopy = selectedIdsForMoveCopy.isNotEmpty()
    val canDelete = selectedImageIdsForDirectOps.isNotEmpty() || selectedFolderUri.isNotBlank()

    val activeFilterChips = buildList {
        if (tagsQuery.isNotBlank()) add("tags")
        if (minWidthText.isNotBlank() || minHeightText.isNotBlank()) add("resolution")
        if (formatQuery.isNotBlank()) add("format")
        if (orientationQuery != "any") add("orientation")
        if (folderQuery.isNotBlank()) add("folder")
        if (includeHidden) add("hidden")
        if (missingOnly) add("missing")
        if (fullTextQuery.isNotBlank()) add("full-text")
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = adaptiveMinSize),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                Text("Library", style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (totalCount == 0) "0 images" else "$totalCount images • page $safeLibraryPage / $totalPages",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            AssistChip(
                onClick = { showFileTools = !showFileTools },
                label = {
                    Text(if (showFileTools) "Hide file operations" else "File operations")
                },
            )
        }

        if (showFileTools) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("File Manager", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Selection: ${if (selectedOperationKind == "images") "${selectedImageIdsForDirectOps.size} image(s)" else if (selectedOperationKind == "folder") "folder selected" else "none"}",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    if (allFolders.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            allFolders.forEach { folderUri ->
                                AssistChip(
                                    onClick = {
                                        selectedFolderUri = if (selectedFolderUri == folderUri) "" else folderUri
                                    },
                                    label = { Text(if (selectedFolderUri == folderUri) "[${folderLabelFromUri(folderUri)}]" else folderLabelFromUri(folderUri)) },
                                )
                            }
                        }
                    }

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showCreateFolderDialog = true }, enabled = canCreateFolder) { Text("Create Folder") }
                        Button(
                            onClick = {
                                when {
                                    selectedImageIdsForDirectOps.size > 1 -> showBatchRenameDialog = true
                                    selectedImageIdsForDirectOps.size == 1 -> {
                                        renameImageName = ""
                                        showRenameImageDialog = true
                                    }
                                    selectedFolderUri.isNotBlank() -> {
                                        renameFolderName = ""
                                        showRenameFolderDialog = true
                                    }
                                }
                            },
                            enabled = canRename,
                        ) { Text("Rename") }
                        Button(onClick = { showMoveDialog = true }, enabled = canMove) { Text("Move") }
                        Button(onClick = { showCopyDialog = true }, enabled = canCopy) { Text("Copy") }
                        Button(onClick = { showDeleteDialog = true }, enabled = canDelete) { Text("Delete") }
                        Button(
                            onClick = {
                                selectionMode = !selectionMode
                                if (!selectionMode) {
                                    selectedImageIds = emptySet()
                                }
                            },
                        ) { Text(if (selectionMode) "Batch Select: ON" else "Batch Select") }
                        AssistChip(
                            onClick = { selectedImageIds = visibleImageIds },
                            label = { Text("Select Visible") },
                        )
                        AssistChip(
                            onClick = { selectedImageIds = emptySet() },
                            label = { Text("Deselect All") },
                        )
                        AssistChip(
                            onClick = { selectedImageIds = visibleImageIds - selectedImageIds },
                            label = { Text("Invert Selection") },
                        )
                        Button(onClick = onUndoFileOperations, enabled = state.fileOperationUndoAvailable && !state.fileOperationRunning) { Text("Undo Last") }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Conflict")
                        Box {
                            Button(onClick = { conflictMenuExpanded = true }) {
                                Text(
                                    when (conflictMode) {
                                        "overwrite" -> "Overwrite"
                                        "skip" -> "Skip"
                                        else -> "Keep both"
                                    },
                                )
                            }
                            DropdownMenu(expanded = conflictMenuExpanded, onDismissRequest = { conflictMenuExpanded = false }) {
                                listOf(
                                    "rename" to "Keep both",
                                    "overwrite" to "Overwrite",
                                    "skip" to "Skip",
                                ).forEach { (mode, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            conflictMode = mode
                                            conflictMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { showSearch = !showSearch }, label = { Text(if (showSearch) "Hide search" else "Search") })
                AssistChip(onClick = { showSort = !showSort }, label = { Text(if (showSort) "Hide sort" else "Sort") })
                AssistChip(onClick = { showFilters = !showFilters }, label = { Text(if (showFilters) "Hide filters" else "Filters") })
                Button(onClick = {
                    searchQuery = ""
                    imageIdQuery = ""
                    fullTextQuery = ""
                    tagsQuery = ""
                    minWidthText = ""
                    minHeightText = ""
                    formatQuery = ""
                    folderQuery = ""
                    includeHidden = false
                    missingOnly = false
                    orientationQuery = "any"
                    sortBy = "date_added"
                    sortDirection = "desc"
                    onClearResults()
                }) { Text("Clear") }
            }
        }

        if (showSearch) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Card(modifier = Modifier.fillMaxWidth().animateContentSize()) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Filename search") })
                        OutlinedTextField(value = imageIdQuery, onValueChange = { imageIdQuery = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Image ID search") })
                        OutlinedTextField(value = fullTextQuery, onValueChange = { fullTextQuery = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Full-text search") })
                    }
                }
            }
        }

        if (showSort) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Card(modifier = Modifier.fillMaxWidth().animateContentSize()) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Sort", style = MaterialTheme.typography.titleSmall)
                        val options = listOf(
                            "filename_asc" to "Filename A→Z",
                            "filename_desc" to "Filename Z→A",
                            "date_added" to "Date Added",
                            "date_modified" to "Date Modified",
                            "resolution" to "Resolution",
                            "size" to "File Size",
                            "random" to "Random",
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            options.forEach { (value, label) ->
                                AssistChip(onClick = {
                                    when (value) {
                                        "filename_asc" -> { sortBy = "filename"; sortDirection = "asc" }
                                        "filename_desc" -> { sortBy = "filename"; sortDirection = "desc" }
                                        else -> { sortBy = value; sortDirection = "desc" }
                                    }
                                }, label = {
                                    val selected =
                                        (value == "filename_asc" && sortBy == "filename" && sortDirection == "asc") ||
                                            (value == "filename_desc" && sortBy == "filename" && sortDirection == "desc") ||
                                            (value !in setOf("filename_asc", "filename_desc") && sortBy == value)
                                    Text(if (selected) "[$label]" else label)
                                })
                            }
                        }
                    }
                }
            }
        }

        if (showFilters) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Card(modifier = Modifier.fillMaxWidth().animateContentSize()) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Filters", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = { includeHidden = !includeHidden }, label = { Text(if (includeHidden) "Hidden:on" else "Hidden") })
                            AssistChip(onClick = { missingOnly = !missingOnly }, label = { Text(if (missingOnly) "Missing:on" else "Missing") })
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = tagsQuery, onValueChange = { tagsQuery = it }, singleLine = true, label = { Text("Tags") })
                            OutlinedTextField(value = minWidthText, onValueChange = { minWidthText = it }, singleLine = true, label = { Text("Min width") })
                            OutlinedTextField(value = minHeightText, onValueChange = { minHeightText = it }, singleLine = true, label = { Text("Min height") })
                            OutlinedTextField(value = formatQuery, onValueChange = { formatQuery = it }, singleLine = true, label = { Text("Format") })
                            OutlinedTextField(value = folderQuery, onValueChange = { folderQuery = it }, singleLine = true, label = { Text("Folder") })
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("any", "portrait", "landscape", "square").forEach { ori ->
                                AssistChip(onClick = { orientationQuery = ori }, label = { Text(if (orientationQuery == ori) "[$ori]" else ori) })
                            }
                        }
                    }
                }
            }
        }

        if (activeFilterChips.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    activeFilterChips.forEach { chip -> AssistChip(onClick = {}, label = { Text(chip) }) }
                }
            }
        }

        if (state.loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator()
                    Text("Loading library...")
                }
            }
        }

        if (images.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                AsterionEmptyState(
                    title = "No matching images",
                    detail = "Adjust the active filters, clear the search, or scan a library folder to add images.",
                )
            }
        } else {
            items(items = visibleList, key = { item -> item.imageId() ?: item["uri"].toString() }) { item ->
                val title = item["filename"]?.toString().orEmpty().ifBlank { "Untitled image" }
                val imageId = item.imageId()
                val isSelected = imageId != null && selectedImageIds.contains(imageId)
                val thumbnailUrl = item["thumbnail_url"]?.toString()?.takeIf { it.isNotBlank() } ?: item["file_url"]?.toString()?.takeIf { it.isNotBlank() }
                val cardHeight = 280.dp
                val thumbHeight = 210.dp

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                                onClick = {
                                if (selectionMode && imageId != null) {
                                    selectedImageIds = if (isSelected) selectedImageIds - imageId else selectedImageIds + imageId
                                } else {
                                    onOpenImage(item, visibleList)
                                }
                            },
                            onLongClick = {
                                if (imageId != null) {
                                    selectionMode = true
                                    selectedImageIds = if (isSelected) selectedImageIds - imageId else selectedImageIds + imageId
                                }
                            },
                        ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(cardHeight)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!thumbnailUrl.isNullOrBlank()) {
                            ImageTile(
                                model = thumbnailUrl,
                                contentDescription = title,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(thumbHeight),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Spacer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(thumbHeight),
                            )
                        }
                        Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            buildString {
                                if (isSelected) {
                                    append("Selected")
                                }
                                if (isEmpty()) append("—")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        if (totalPages > 1) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { libraryPage = (libraryPage - 1).coerceAtLeast(1) },
                        enabled = libraryPage > 1 && !state.loading,
                    ) { Text("Previous") }
                    Text("Page $safeLibraryPage / $totalPages")
                    Button(
                        onClick = { libraryPage = (libraryPage + 1).coerceAtMost(totalPages) },
                        enabled = libraryPage < totalPages && !state.loading,
                    ) { Text("Next") }
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                TextButton(onClick = { onNavigate(AppDestination.Dashboard) }) { Text("Dashboard") }
                Button(onClick = { onNavigate(AppDestination.Search) }) { Text("Search") }
            }
        }
    }

    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Create Folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = createFolderName,
                        onValueChange = { createFolderName = it },
                        singleLine = true,
                        label = { Text("Folder name") },
                    )
                    Box {
                        Button(onClick = { targetMenuExpanded = true }) {
                            Text(if (selectedTargetFolderUri.isBlank()) "Parent folder" else folderLabelFromUri(selectedTargetFolderUri))
                        }
                        DropdownMenu(expanded = targetMenuExpanded, onDismissRequest = { targetMenuExpanded = false }) {
                            allFolders.forEach { folderUri ->
                                DropdownMenuItem(
                                    text = { Text(folderLabelFromUri(folderUri)) },
                                    onClick = {
                                        selectedTargetFolderUri = folderUri
                                        targetMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onExecuteFileOperations(
                            mapOf(
                                "action" to "create_folder",
                                "target_folder_uri" to selectedTargetFolderUri,
                                "folder_name" to createFolderName.trim(),
                                "conflict_mode" to conflictMode,
                            ),
                        )
                        showCreateFolderDialog = false
                    },
                    enabled = createFolderName.isNotBlank() && selectedTargetFolderUri.isNotBlank(),
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateFolderDialog = false }) { Text("Cancel") } },
        )
    }

    if (showRenameImageDialog) {
        val imageId = selectedImageIdsForDirectOps.firstOrNull()
        val sourceFilename = state.images.firstOrNull { it.imageId() == imageId }
            ?.get("filename")
            ?.toString()
            .orEmpty()
        val sourceExtension = sourceFilename.substringAfterLast('.', "")
        AlertDialog(
            onDismissRequest = { showRenameImageDialog = false },
            title = { Text("Rename File") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Image ID: ${imageId ?: 0}")
                    if (sourceExtension.isNotBlank()) {
                        Text("The .$sourceExtension extension is preserved automatically.", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(value = renameImageName, onValueChange = { renameImageName = it }, singleLine = true, label = { Text("New name") })
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onExecuteFileOperations(
                            mapOf(
                                "action" to "rename_image",
                                "image_ids" to (imageId?.toString().orEmpty()),
                                "name" to renameImageName.trim(),
                                "conflict_mode" to conflictMode,
                            ),
                        )
                        showRenameImageDialog = false
                    },
                    enabled = imageId != null && renameImageName.isNotBlank(),
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { showRenameImageDialog = false }) { Text("Cancel") } },
        )
    }

    if (showBatchRenameDialog) {
        AlertDialog(
            onDismissRequest = { showBatchRenameDialog = false },
            title = { Text("Batch Rename") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Selected images: ${selectedImageIdsForDirectOps.size}")
                    OutlinedTextField(value = renamePattern, onValueChange = { renamePattern = it }, singleLine = true, label = { Text("Pattern (use {n})") })
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onExecuteFileOperations(
                            mapOf(
                                "action" to "batch_rename_images",
                                "image_ids" to selectedImageIdsForDirectOps.joinToString(","),
                                "pattern" to renamePattern.trim(),
                                "conflict_mode" to conflictMode,
                            ),
                        )
                        showBatchRenameDialog = false
                    },
                    enabled = selectedImageIdsForDirectOps.isNotEmpty() && renamePattern.isNotBlank(),
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { showBatchRenameDialog = false }) { Text("Cancel") } },
        )
    }

    if (showRenameFolderDialog) {
        AlertDialog(
            onDismissRequest = { showRenameFolderDialog = false },
            title = { Text("Rename Folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(folderLabelFromUri(selectedFolderUri))
                    OutlinedTextField(value = renameFolderName, onValueChange = { renameFolderName = it }, singleLine = true, label = { Text("Folder new name") })
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onExecuteFileOperations(
                            mapOf(
                                "action" to "rename_folder",
                                "source_folder_uri" to selectedFolderUri,
                                "folder_name" to renameFolderName.trim(),
                                "conflict_mode" to conflictMode,
                            ),
                        )
                        showRenameFolderDialog = false
                    },
                    enabled = selectedFolderUri.isNotBlank() && renameFolderName.isNotBlank(),
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { showRenameFolderDialog = false }) { Text("Cancel") } },
        )
    }

    if (showMoveDialog) {
        val selectedIds = selectedIdsForMoveCopy
        AlertDialog(
            onDismissRequest = { showMoveDialog = false },
            title = { Text(if (selectedImageIds.isNotEmpty()) "Move Images" else "Move Folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Items: ${selectedIds.size}")
                    Box {
                        Button(onClick = { targetMenuExpanded = true }) {
                            Text(if (selectedTargetFolderUri.isBlank()) "Target folder" else folderLabelFromUri(selectedTargetFolderUri))
                        }
                        DropdownMenu(expanded = targetMenuExpanded, onDismissRequest = { targetMenuExpanded = false }) {
                            allFolders.forEach { folderUri ->
                                DropdownMenuItem(
                                    text = { Text(folderLabelFromUri(folderUri)) },
                                    onClick = {
                                        selectedTargetFolderUri = folderUri
                                        targetMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    AssistChip(
                        onClick = { moveDeleteSourceFolderAfterMove = !moveDeleteSourceFolderAfterMove },
                        label = {
                            Text(
                                if (moveDeleteSourceFolderAfterMove) {
                                    "Delete source folder after move: on"
                                } else {
                                    "Delete source folder after move: off"
                                },
                            )
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val movePayload = mapOf(
                            "action" to if (selectedIds.size > 1) "batch_move" else "move_images",
                            "image_ids" to selectedIds.joinToString(","),
                            "target_folder_uri" to selectedTargetFolderUri,
                            "conflict_mode" to conflictMode,
                        )
                        if (selectedImageIds.isEmpty() && selectedFolderUri.isNotBlank() && moveDeleteSourceFolderAfterMove) {
                            onExecuteFileOperationSequence(
                                listOf(
                                    movePayload,
                                    mapOf(
                                        "action" to "delete_folder",
                                        "source_folder_uri" to selectedFolderUri,
                                        "conflict_mode" to conflictMode,
                                    ),
                                ),
                            )
                        } else {
                            onExecuteFileOperations(movePayload)
                        }
                        showMoveDialog = false
                    },
                    enabled = selectedIds.isNotEmpty() && selectedTargetFolderUri.isNotBlank(),
                ) { Text("Move") }
            },
            dismissButton = { TextButton(onClick = { showMoveDialog = false }) { Text("Cancel") } },
        )
    }

    if (showCopyDialog) {
        val selectedIds = selectedIdsForMoveCopy
        AlertDialog(
            onDismissRequest = { showCopyDialog = false },
            title = { Text(if (selectedImageIds.isNotEmpty()) "Copy Images" else "Copy Folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Items: ${selectedIds.size}")
                    Box {
                        Button(onClick = { targetMenuExpanded = true }) {
                            Text(if (selectedTargetFolderUri.isBlank()) "Target folder" else folderLabelFromUri(selectedTargetFolderUri))
                        }
                        DropdownMenu(expanded = targetMenuExpanded, onDismissRequest = { targetMenuExpanded = false }) {
                            allFolders.forEach { folderUri ->
                                DropdownMenuItem(
                                    text = { Text(folderLabelFromUri(folderUri)) },
                                    onClick = {
                                        selectedTargetFolderUri = folderUri
                                        targetMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onExecuteFileOperations(
                            mapOf(
                                "action" to if (selectedIds.size > 1) "batch_copy" else "copy_images",
                                "image_ids" to selectedIds.joinToString(","),
                                "target_folder_uri" to selectedTargetFolderUri,
                                "conflict_mode" to conflictMode,
                            ),
                        )
                        showCopyDialog = false
                    },
                    enabled = selectedIds.isNotEmpty() && selectedTargetFolderUri.isNotBlank(),
                ) { Text("Copy") }
            },
            dismissButton = { TextButton(onClick = { showCopyDialog = false }) { Text("Cancel") } },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete") },
            text = {
                Text(
                    if (selectedImageIdsForDirectOps.isNotEmpty()) {
                        "Delete ${selectedImageIdsForDirectOps.size} selected image(s)?"
                    } else {
                        "Delete selected folder?"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (selectedImageIdsForDirectOps.isNotEmpty()) {
                            onExecuteFileOperations(
                                mapOf(
                                    "action" to if (selectedImageIdsForDirectOps.size > 1) "batch_delete" else "delete_images",
                                    "image_ids" to selectedImageIdsForDirectOps.joinToString(","),
                                    "conflict_mode" to conflictMode,
                                ),
                            )
                        } else if (selectedFolderUri.isNotBlank()) {
                            onExecuteFileOperations(
                                mapOf(
                                    "action" to "delete_folder",
                                    "source_folder_uri" to selectedFolderUri,
                                    "conflict_mode" to conflictMode,
                                ),
                            )
                        }
                        showDeleteDialog = false
                    },
                    enabled = selectedImageIdsForDirectOps.isNotEmpty() || selectedFolderUri.isNotBlank(),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } },
        )
    }

    if (state.fileOperationRunning) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Progress") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text("Executing file operation...")
                }
            },
        )
    }
}

@Composable
private fun ImageViewerScreen(
    state: AppUiState,
    imageUrl: String?,
    onSetTags: (Int, String) -> Unit,
    onSelectImage: (Map<String, Any>) -> Unit,
    onNavigate: (AppDestination) -> Unit,
) {
    var tagsText by rememberSaveable { mutableStateOf("") }
    var showMetadata by rememberSaveable { mutableStateOf(false) }
    var imageScale by rememberSaveable { mutableStateOf(1f) }
    var imageOffset by remember { mutableStateOf(Offset.Zero) }
    var imageContentScale by rememberSaveable { mutableStateOf("fit") }
    var swipeAccumLocal by remember { mutableStateOf(0f) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        imageScale = (imageScale * zoomChange).coerceIn(1f, 4f)
        imageOffset = if (imageScale > 1f) imageOffset + panChange else Offset.Zero
    }

    val selected = state.selectedImage
    val selectedId = selected?.imageId()

    LaunchedEffect(selectedId, selected?.get("metadata")) {
        val metadata = selected?.get("metadata") as? Map<*, *>
        val tags = when (val nested = metadata?.get("tags")) {
            is List<*> -> nested.mapNotNull { it?.toString()?.trim() }.filter { it.isNotBlank() }
            is String -> nested.split('|').map { it.trim() }.filter { it.isNotBlank() }
            else -> emptyList()
        }
        tagsText = tags.joinToString(", ")
        imageScale = 1f
        imageOffset = Offset.Zero
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (selected == null) {
            Text("Viewer", style = MaterialTheme.typography.headlineMedium)
            AsterionEmptyState(
                title = "No image selected",
                detail = "Open an image from the library or search results.",
            )
            Button(onClick = { onNavigate(AppDestination.LibraryBrowser) }) { Text("Open Library") }
            return@Column
        }

        val title = selected["filename"]?.toString().orEmpty().ifBlank { "Untitled image" }
        val imageId = selected.imageId()
        val sourceRows = when {
            state.activeViewerContext.isNotEmpty() -> state.activeViewerContext
            state.searchResults.isNotEmpty() -> state.searchResults
            else -> state.images
        }
        val currentIndex = sourceRows.indexOfFirst { it.imageId() == imageId }
        val positionLabel = if (currentIndex >= 0) "${currentIndex + 1} / ${sourceRows.size}" else ""

        fun finishSwipe() {
            val threshold = 44f
            val idx = sourceRows.indexOfFirst { it.imageId() == imageId }
            when {
                swipeAccumLocal > threshold && idx >= 0 && idx < sourceRows.lastIndex -> onSelectImage(sourceRows[idx + 1])
                swipeAccumLocal < -threshold && idx > 0 -> onSelectImage(sourceRows[idx - 1])
            }
            swipeAccumLocal = 0f
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            if (!imageUrl.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(430.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ImageTile(
                        model = imageUrl,
                        contentDescription = title,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = imageScale,
                                scaleY = imageScale,
                                translationX = imageOffset.x,
                                translationY = imageOffset.y,
                            )
                            .pointerInput(selectedId) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        imageScale = if (imageScale > 1f) 1f else 2f
                                        imageOffset = Offset.Zero
                                    },
                                )
                            }
                            .transformable(transformState),
                        contentScale = if (imageContentScale == "fill") ContentScale.Crop else ContentScale.Fit,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(selectedId) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { _, dragAmount ->
                                swipeAccumLocal += dragAmount
                            },
                            onDragEnd = { finishSwipe() },
                            onDragCancel = { swipeAccumLocal = 0f },
                        )
                    }
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (positionLabel.isNotBlank()) {
                        Text(
                            positionLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (sourceRows.size > 1 && currentIndex >= 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { if (currentIndex > 0) onSelectImage(sourceRows[currentIndex - 1]) },
                            enabled = currentIndex > 0,
                        ) {
                            Text("Previous")
                        }
                        Text(
                            "Swipe anywhere below the image",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = { if (currentIndex < sourceRows.lastIndex) onSelectImage(sourceRows[currentIndex + 1]) },
                            enabled = currentIndex < sourceRows.lastIndex,
                        ) {
                            Text("Next")
                        }
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AssistChip(
                        onClick = {
                            imageScale = 1f
                            imageOffset = Offset.Zero
                            imageContentScale = "fit"
                        },
                        label = { Text("Fit") },
                    )
                    AssistChip(
                        onClick = {
                            imageScale = 1f
                            imageOffset = Offset.Zero
                            imageContentScale = "fill"
                        },
                        label = { Text("Fill") },
                    )
                    AssistChip(
                        onClick = { showMetadata = !showMetadata },
                        label = { Text(if (showMetadata) "Hide details" else "Details") },
                    )
                }

                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Tags") },
                )
                Button(
                    onClick = {
                        if (imageId != null) onSetTags(imageId, tagsText)
                    },
                    enabled = imageId != null,
                ) {
                    Text("Save tags")
                }

                state.lastActionMessage?.let { AsterionStatusNotice(it) }
                state.errorMessage?.let { AsterionStatusNotice(it, isError = true) }

                AnimatedVisibility(visible = showMetadata) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        metadataRows(selected).forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { onNavigate(AppDestination.LibraryBrowser) }) { Text("Library") }
            TextButton(onClick = { onNavigate(AppDestination.Dashboard) }) { Text("Dashboard") }
        }
    }
}

@Composable
private fun ReviewQueueScreen(
    items: List<Map<String, Any>>,
    onApprove: (String) -> Unit,
    onCorrect: (String, String, Boolean) -> Unit,
    onReject: (String) -> Unit,
    onUndo: (String) -> Unit,
    onNavigate: (AppDestination) -> Unit,
) {
    var currentIndex by rememberSaveable { mutableStateOf(0) }
    val boundedIndex = currentIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Review Queue", style = MaterialTheme.typography.headlineMedium)
        Text("Resolve items in sequence, or apply an action to the remaining queue.", style = MaterialTheme.typography.bodyMedium)

        if (items.isEmpty()) {
            AsterionEmptyState(
                title = "Review queue is clear",
                detail = "New low-confidence recognition and automation results will appear here for confirmation.",
            )
        } else {
            val item = items[boundedIndex]
            val id = item["id"]?.toString()
                ?: item["item_id"]?.toString()
                ?: item["path"]?.toString()
                ?: item.hashCode().toString()
            val reviewType = item["review_type"]?.toString().orEmpty()
            val isCharacterReview = reviewType == "character_resolution"
            var correctedCharacters by rememberSaveable(id) { mutableStateOf("") }
            var markOriginalCharacter by rememberSaveable(id) { mutableStateOf(false) }

            Text("Item " + (boundedIndex + 1) + " of " + items.size, style = MaterialTheme.typography.labelLarge)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item["path"]?.toString().orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    val reason = item["reason"]?.toString().orEmpty()
                    if (reason.isNotBlank()) {
                        Text(reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                    if (isCharacterReview) {
                        val payloadJson = item["payload_json"]?.toString().orEmpty()
                        val suggestion = remember(payloadJson) { reviewCandidateSummary(payloadJson) }
                        if (suggestion.isNotBlank()) {
                            Text("Candidates: " + suggestion, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "Correct with canonical Character Knowledge names or IDs. For multiple characters, use | in visual order. Series is derived automatically from the corrected identities.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = correctedCharacters,
                            onValueChange = { correctedCharacters = it },
                            label = { Text("Character(s): Artoria | Rin Tohsaka") },
                            enabled = !markOriginalCharacter,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = { markOriginalCharacter = !markOriginalCharacter }) {
                            Text(if (markOriginalCharacter) "Original Character selected" else "Mark as Original Character")
                        }
                        Button(
                            onClick = {
                                onCorrect(id, correctedCharacters, markOriginalCharacter)
                                currentIndex = (boundedIndex + 1).coerceAtMost(items.lastIndex)
                            },
                            enabled = markOriginalCharacter || correctedCharacters.isNotBlank(),
                        ) {
                            Text("Apply correction")
                        }
                    } else {
                        item.entries
                            .filter { it.key !in setOf("payload_json", "correction_json", "path", "reason") }
                            .take(8)
                            .forEach { entry ->
                                Text(entry.key.toString() + ": " + entry.value.toString(), style = MaterialTheme.typography.bodySmall)
                            }
                    }

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isCharacterReview) {
                            Button(onClick = { onApprove(id); currentIndex = (boundedIndex + 1).coerceAtMost(items.lastIndex) }) {
                                Text("Accept")
                            }
                        }
                        Button(onClick = { onReject(id); currentIndex = (boundedIndex + 1).coerceAtMost(items.lastIndex) }) { Text("Reject") }
                        Button(onClick = { onUndo(id) }) { Text("Undo") }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { currentIndex = (boundedIndex - 1).coerceAtLeast(0) }, label = { Text("Previous") })
                AssistChip(onClick = { currentIndex = (boundedIndex + 1).coerceAtMost(items.lastIndex) }, label = { Text("Next") })
                AssistChip(onClick = { currentIndex = (boundedIndex + 1).coerceAtMost(items.lastIndex) }, label = { Text("Next unresolved") })
                if (!isCharacterReview) {
                    Button(onClick = { items.drop(boundedIndex).forEach { row -> onApprove(row.reviewItemId()) } }) { Text("Accept Remaining") }
                }
                Button(onClick = { items.drop(boundedIndex).forEach { row -> onReject(row.reviewItemId()) } }) { Text("Reject Remaining") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onNavigate(AppDestination.Dashboard) }) {
                Text("Dashboard")
            }
            Button(onClick = { onNavigate(AppDestination.LibraryBrowser) }) {
                Text("Library")
            }
        }
    }
}

@Composable
private fun RecognitionWorkbenchScreen(
    state: AppUiState,
    onRefreshAi: () -> Unit,
    onDetectHardware: () -> Unit,
    onValidateInfrastructure: () -> Unit,
    onRunPipeline: (String, String) -> Unit,
    onRunMultiStagePipeline: (String) -> Unit,
    onRunBatchPipeline: (String) -> Unit,
    onEnqueueTask: (String) -> Unit,
    onNavigate: (AppDestination) -> Unit,
) {
    var promptHint by rememberSaveable { mutableStateOf("") }
    var selectedTaskType by rememberSaveable { mutableStateOf("ocr") }
    var showBackendDetails by rememberSaveable { mutableStateOf(false) }
    var showLatestResult by rememberSaveable { mutableStateOf(false) }

    val selected = state.selectedImage
    val selectedId = selected?.imageId()
    val selectedName = selected?.get("filename")?.toString().orEmpty().ifBlank { "No image selected" }
    val selectedThumb = selected?.get("thumbnail_url")?.toString()?.takeIf { it.isNotBlank() }
        ?: selected?.get("file_url")?.toString()?.takeIf { it.isNotBlank() }

    val queuePending = state.aiOverview["queue_pending"]?.toString().orEmpty().ifBlank { "0" }
    val queueRunning = state.aiOverview["queue_running"]?.toString().orEmpty().ifBlank { "0" }
    val queueFailed = state.aiOverview["queue_failed"]?.toString().orEmpty().ifBlank { "0" }
    val activeModelId = state.aiSettings["active_model_id"]?.toString().orEmpty()
    val activeModelVersion = state.aiSettings["active_model_version"]?.toString().orEmpty()
    val activeModel = state.aiInstalledModels.firstOrNull { model ->
        model["model_id"]?.toString() == activeModelId &&
            (activeModelVersion.isBlank() || model["version"]?.toString() == activeModelVersion)
    }

    val pipelineTasks = listOf(
        "ocr" to "OCR",
        "captioning" to "Caption",
        "character_recognition" to "Character",
        "series_recognition" to "Series",
        "artist_recognition" to "Artist",
        "tag_prediction" to "Tag Prediction",
        "metadata_extraction" to "Metadata",
        "prompt_generation" to "Prompt",
        "embedding_generation" to "Embedding",
        "duplicate_detection" to "Duplicates",
        "classification" to "Classification",
        "detection" to "Detection",
        "face_feature_extraction" to "Face Features",
        "knowledge_pack_execution" to "Knowledge Pack",
        "similarity_search" to "Similarity",
    )
    val backendTasks = state.aiBackends
        .flatMap { backend -> (backend["supported_tasks"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList() }
        .map { it.trim().lowercase() }
        .toSet()
    val activeModelTasks = (activeModel?.get("supported_tasks") as? List<*>)
        ?.mapNotNull { it?.toString()?.trim()?.lowercase() }
        ?.toSet()
        ?: emptySet()
    val availableTasks = pipelineTasks.filter { (taskType, _) ->
        (backendTasks.isEmpty() || taskType in backendTasks) &&
            (activeModelTasks.isEmpty() || taskType in activeModelTasks)
    }
    val selectedTaskAvailable = availableTasks.any { it.first == selectedTaskType }
    val activeModelLabel = when {
        activeModel != null -> "${activeModel["display_name"] ?: activeModelId}@${activeModel["version"] ?: activeModelVersion}"
        activeModelId.isNotBlank() -> "$activeModelId${if (activeModelVersion.isBlank()) "" else "@$activeModelVersion"}"
        else -> "No compatible native model selected"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AI Workbench", style = MaterialTheme.typography.headlineMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Runtime", style = MaterialTheme.typography.titleMedium)
                Text("Active model: $activeModelLabel")
                Text("Pending $queuePending   Running $queueRunning   Failed $queueFailed")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRefreshAi) { Text("Refresh") }
                    TextButton(onClick = onDetectHardware) { Text("Hardware") }
                    TextButton(onClick = onValidateInfrastructure) { Text("Validate") }
                    Button(onClick = { onNavigate(AppDestination.PluginManager) }) { Text("Models") }
                }
            }
        }

        AssistChip(
            onClick = { showBackendDetails = !showBackendDetails },
            label = { Text(if (showBackendDetails) "Hide runtime details" else "Runtime details") },
        )
        AnimatedVisibility(visible = showBackendDetails) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Backend Status", style = MaterialTheme.typography.titleMedium)
                if (state.aiBackends.isEmpty()) {
                    Text("No Local AI backend is registered. Install or activate a compatible runtime before running tasks.")
                } else {
                    state.aiBackends.forEach { backend ->
                        val taskList = (backend["supported_tasks"] as? List<*>)?.joinToString() ?: "none"
                        Text("${backend["runtime_id"] ?: "runtime"}: $taskList", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Selected Image", style = MaterialTheme.typography.titleMedium)
                if (selected == null || selectedId == null) {
                    Text("Open an image from Library Browser or Search before running AI pipelines.")
                } else {
                    if (!selectedThumb.isNullOrBlank()) {
                        ImageTile(
                            model = selectedThumb,
                            contentDescription = selectedName,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Text("Image #$selectedId")
                    Text(selectedName)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onNavigate(AppDestination.LibraryBrowser) }) { Text("Open Library") }
                    Button(onClick = { onNavigate(AppDestination.ImageViewer) }, enabled = selectedId != null) { Text("Open Viewer") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Run task", style = MaterialTheme.typography.titleMedium)
                Text("Choose a task compatible with the selected model.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = promptHint,
                    onValueChange = { promptHint = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Prompt/Context hint (optional)") },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    availableTasks.forEach { (taskType, label) ->
                        AssistChip(
                            onClick = { selectedTaskType = taskType },
                            label = { Text(if (selectedTaskType == taskType) "Selected: $label" else label) },
                        )
                    }
                }
                if (availableTasks.isEmpty()) {
                    Text("No task is compatible with the active model and registered backends.", style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = { onRunPipeline(selectedTaskType, promptHint) },
                    enabled = selectedId != null && selectedTaskAvailable,
                ) {
                    Text("Run")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Queue", style = MaterialTheme.typography.titleMedium)
                Text("Queue the selected task for the selected image; progress appears in Automation.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { onEnqueueTask(selectedTaskType) }, enabled = selectedId != null && selectedTaskAvailable) {
                    Text("Queue")
                }
            }
        }

        AssistChip(
            onClick = { showLatestResult = !showLatestResult },
            label = { Text(if (showLatestResult) "Hide latest result" else "Latest result") },
        )
        AnimatedVisibility(visible = showLatestResult) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Latest AI Result", style = MaterialTheme.typography.titleMedium)
                if (state.aiLastPipelineResult.isEmpty()) {
                    Text("No pipeline result yet.")
                } else {
                    state.aiLastPipelineResult.entries.take(20).forEach { (key, value) ->
                        Text("$key: $value", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        }

        if (state.lastActionMessage != null) {
            Text(state.lastActionMessage)
        }
        if (state.errorMessage != null) {
            Text("Error: ${state.errorMessage}")
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onNavigate(AppDestination.Automation) }) { Text("Automation") }
            Button(onClick = { onNavigate(AppDestination.PluginManager) }) { Text("Models") }
            Button(onClick = { onNavigate(AppDestination.Dashboard) }) { Text("Dashboard") }
        }
    }
}

@Composable
private fun AiTaskFilterScreen(
    title: String,
    taskTypeFilter: String,
    tasks: List<Map<String, Any>>,
    onNavigate: (AppDestination) -> Unit,
) {
    val normalized = taskTypeFilter.trim().lowercase()
    val filtered = tasks.filter {
        it["task_type"]?.toString()?.trim()?.lowercase() == normalized
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text("Matching tasks: ${filtered.size}")

        if (filtered.isEmpty()) {
            AsterionEmptyState(
                title = "No matching tasks",
                detail = "Tasks for $taskTypeFilter will appear here when they are available.",
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 4.dp),
            ) {
                items(filtered) { task ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Task: ${task["task_id"] ?: "n/a"}")
                            Text("Status: ${task["status"] ?: "unknown"} | Progress: ${task["progress"] ?: 0.0}")
                            Text("Model: ${task["model_id"] ?: "(auto)"} ${task["version"] ?: ""}")
                            val error = task["error_message"]?.toString().orEmpty()
                            if (error.isNotBlank()) {
                                Text("Error: $error", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onNavigate(AppDestination.RecognitionResults) }) { Text("Recognition") }
            Button(onClick = { onNavigate(AppDestination.Automation) }) { Text("Automation") }
            Button(onClick = { onNavigate(AppDestination.Dashboard) }) { Text("Dashboard") }
        }
    }
}

@Composable
private fun AiAutomationScreen(
    state: AppUiState,
    onRefreshAi: () -> Unit,
    onStartAutomation: () -> Unit,
    onReprocessAll: () -> Unit,
    onPauseAutomation: () -> Unit,
    onResumeAutomation: () -> Unit,
    onStopAutomation: () -> Unit,
    onNavigate: (AppDestination) -> Unit,
) {
    val automationStatus = state.aiOverview["automation_status"]?.toString().orEmpty().ifBlank { "idle" }
    val total = (state.aiOverview["automation_total"] as? Number)?.toInt() ?: 0
    val processed = (state.aiOverview["automation_processed"] as? Number)?.toInt() ?: 0
    val failed = (state.aiOverview["automation_failed"] as? Number)?.toInt() ?: 0
    val review = (state.aiOverview["automation_review"] as? Number)?.toInt() ?: 0
    val currentImageId = (state.aiOverview["automation_current_image_id"] as? Number)?.toInt() ?: 0
    val message = state.aiOverview["automation_message"]?.toString().orEmpty()
    val active = automationStatus in setOf("queued", "running", "pausing", "stopping")
    val paused = automationStatus == "paused"
    val progress = if (total > 0) processed.toFloat() / total.toFloat() else 0f
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            onRefreshAi()
            delay(1500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsterionSectionHeader(
            title = "Automation",
            detail = "One image at a time. Models, routing, deduplication and organization run automatically.",
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            when (automationStatus) {
                                "queued" -> "Queued"
                                "running" -> "Running"
                                "pausing" -> "Pausing safely"
                                "paused" -> "Paused"
                                "stopping" -> "Stopping"
                                "completed" -> "Complete"
                                "failed" -> "Needs attention"
                                "stopped" -> "Stopped"
                                else -> "Ready"
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            when {
                                active && total > 0 -> "$processed / $total images"
                                total > 0 -> "$processed / $total images processed"
                                else -> "Processes new or changed images"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (review > 0) {
                            Text(
                                "$review review${if (review == 1) "" else "s"}",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        if (failed > 0) {
                            Text(
                                "$failed failure${if (failed == 1) "" else "s"}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }

                if (active || total > 0) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (currentImageId > 0 && active) {
                    Text(
                        "Current image #$currentImageId",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (message.isNotBlank()) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = if (paused) onResumeAutomation else onStartAutomation,
                        enabled = !active,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            when {
                                paused -> "Resume"
                                automationStatus == "completed" -> "Run New / Changed"
                                else -> "Run Automation"
                            },
                        )
                    }
                    Button(
                        onClick = onPauseAutomation,
                        enabled = automationStatus == "running",
                    ) {
                        Text("Pause")
                    }
                    Button(
                        onClick = onStopAutomation,
                        enabled = active || paused,
                    ) {
                        Text("Stop")
                    }
                }

                TextButton(
                    onClick = onReprocessAll,
                    enabled = !active,
                ) {
                    Text("Reprocess entire library")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text("What happens automatically", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Asterion checks integrity and duplicates before loading AI. OCR, NSFW, embeddings and scoring run first, followed by one Qwen-VL semantic pass. Character Knowledge remains the identity authority; Scenery, promotional junk, corrupt files and unresolved groups are routed separately instead of flooding Review.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("Review", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.reviewQueue.isEmpty()) "No items need attention." else "${state.reviewQueue.size} item(s) need attention.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onNavigate(AppDestination.ReviewQueue) }) {
                    Text("Open")
                }
            }
        }

        state.errorMessage?.let { AsterionStatusNotice(it, isError = true) }

        TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
            Text(if (showDiagnostics) "Hide diagnostics" else "Diagnostics")
        }
        AnimatedVisibility(visible = showDiagnostics) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val pending = state.aiOverview["queue_pending"]?.toString().orEmpty().ifBlank { "0" }
                val running = state.aiOverview["queue_running"]?.toString().orEmpty().ifBlank { "0" }
                val queueFailed = state.aiOverview["queue_failed"]?.toString().orEmpty().ifBlank { "0" }
                Text(
                    "Internal queue • pending $pending • running $running • failed $queueFailed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.aiTasks.take(5).forEach { task ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(task["task_type"]?.toString().orEmpty().ifBlank { "Task" })
                            Text(
                                "${task["status"] ?: "unknown"} • ${task["model_id"] ?: "automatic model"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            task["error_message"]?.toString()?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                TextButton(onClick = onRefreshAi) { Text("Refresh diagnostics") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onNavigate(AppDestination.Dashboard) }) { Text("Dashboard") }
            TextButton(onClick = { onNavigate(AppDestination.PluginManager) }) { Text("Models") }
        }
    }
}

@Composable
private fun AiModelManagerScreen(
    state: AppUiState,
    onRefreshAi: () -> Unit,
    onDetectHardware: () -> Unit,
    onValidateInfrastructure: () -> Unit,
    onDetectModelUpdates: () -> Unit,
    onPruneCache: () -> Unit,
    onVerifyModel: (String, String) -> Unit,
    onRemoveModel: (String, String) -> Unit,
    onUpdateAiSetting: (String, String) -> Unit,
    onRegisterAvailableModel: (Map<String, String>) -> Unit,
    selectedModelDocumentName: String,
    onChooseModelDocument: () -> Unit,
    onChooseModelPackageDirectory: () -> Unit,
    onImportModelDocument: (Map<String, String>) -> Unit,
    onRegisterModelDownload: (Map<String, String>) -> Unit,
    onSetActiveModel: (String, String, String) -> Unit,
    onClearActiveModel: (String) -> Unit,
    selectedKnowledgeDocumentName: String,
    selectedKnowledgeDocumentAvailable: Boolean,
    selectedKnowledgeDocumentIsArchive: Boolean,
    onChooseKnowledgeDocument: () -> Unit,
    onPreviewKnowledgeDocument: (Map<String, String>) -> Unit,
    onValidateKnowledgeDocument: (Map<String, String>) -> Unit,
    onImportKnowledgeDocument: (Map<String, String>) -> Unit,
    selectedFusionDocumentName: String,
    onChooseFusionDocument: () -> Unit,
    onPreviewFusionDocument: (String, Boolean) -> Unit,
    onValidateFusionDocument: (String, Boolean) -> Unit,
    onImportFusionDocument: (String, Boolean) -> Unit,
    onExportFusionSnapshot: (String, Boolean) -> Unit,
    onValidateFusionDatabase: () -> Unit,
    onRebuildFusionDatabase: () -> Unit,
    onRemoveKnowledgePack: (String) -> Unit,
    onRollbackImport: (String) -> Unit,
    onCancelImport: (String) -> Unit,
    onNavigate: (AppDestination) -> Unit,
) {
    var previewedModelKey by rememberSaveable { mutableStateOf("") }
    var modelImportMessage by rememberSaveable { mutableStateOf("") }
    var taskPickerKey by rememberSaveable { mutableStateOf("") }
    var showImportTools by rememberSaveable { mutableStateOf(false) }
    var showTaskAssignments by rememberSaveable { mutableStateOf(false) }
    var showResourceTools by rememberSaveable { mutableStateOf(false) }
    var showInstallHistory by rememberSaveable { mutableStateOf(false) }

    var fusionFormat by rememberSaveable { mutableStateOf("json") }
    var fusionReplaceExisting by rememberSaveable { mutableStateOf(false) }
    var fusionPrettyExport by rememberSaveable { mutableStateOf(true) }

    val hasSelectedModelDocument = selectedModelDocumentName.isNotBlank()
    val hasSelectedKnowledgeDocument = selectedKnowledgeDocumentAvailable
    val hasSelectedFusionDocument = selectedFusionDocumentName.isNotBlank()
    val latestImportId = state.aiLastPipelineResult["import_id"]?.toString().orEmpty()
    val importControlId = latestImportId
    val importStatus = state.aiLastPipelineResult["status"]?.toString()?.lowercase().orEmpty()
    val canRollbackImport = importControlId.isNotBlank()
    val canCancelImport = importControlId.isNotBlank() && importStatus in setOf("queued", "running", "in_progress")
    val selectedModelId = selectedModelDocumentName
        .substringBeforeLast('.')
        .lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "_")
        .trim('_', '-', '.')
        .ifBlank { "local_model" }
    val selectedModelDisplayName = selectedModelDocumentName.substringBeforeLast('.').ifBlank { selectedModelId }

    val activeGlobalModelId = state.aiSettings["active_model_id"]?.toString().orEmpty()
    val activeGlobalModelVersion = state.aiSettings["active_model_version"]?.toString().orEmpty()
    val executionReadyModels = state.aiInstalledModels.filter { model ->
        val metadata = model["metadata"] as? Map<*, *>
        val readiness = metadata?.get("execution_readiness") as? Map<*, *>
        readiness?.get("ready") != false
    }
    val taskCapabilities = executionReadyModels
        .flatMap { model -> (model["supported_tasks"] as? List<*>)?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) } ?: emptyList() }
        .distinct()
        .sorted()
    val selectedModelAlreadyInstalled = state.aiInstalledModels.any { model ->
        model["model_id"]?.toString() == selectedModelId &&
            model["version"]?.toString() == "1.0.0"
    }
    val modelImportForm = mapOf(
        "model_id" to selectedModelId,
        "version" to "1.0.0",
        "display_name" to selectedModelDisplayName,
        "required_runtime" to "",
        "supported_tasks" to "",
        "supported_runtimes" to "",
        "dependencies" to "",
        "source" to "local_import",
    )

    val importForm = emptyMap<String, String>()
    val fusionStatus = state.lastMaintenanceResult.takeIf { it["kind"]?.toString() == "fusion_management" } ?: emptyMap()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Models", style = MaterialTheme.typography.headlineMedium)
            Text(
                "${state.aiInstalledModels.size} installed · ${executionReadyModels.size} execution-ready",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AssistChip(
            onClick = { showImportTools = !showImportTools },
            label = { Text(if (showImportTools) "Hide import" else "Import model") },
        )
        AnimatedVisibility(visible = showImportTools) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Import model", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (hasSelectedModelDocument) "Selected package: $selectedModelDocumentName" else "Choose a model package, archive, or model file.",
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onChooseModelDocument) {
                        Text("Choose package")
                    }
                    Button(onClick = onChooseModelPackageDirectory) {
                        Text("Choose folder")
                    }
                    Button(
                        onClick = {
                            if (selectedModelAlreadyInstalled) {
                                modelImportMessage = "Model already installed"
                            } else {
                                modelImportMessage = ""
                                onImportModelDocument(modelImportForm)
                            }
                        },
                        enabled = hasSelectedModelDocument,
                    ) {
                        Text("Import")
                    }
                }
                if (modelImportMessage.isNotBlank()) {
                    Text(modelImportMessage, style = MaterialTheme.typography.bodySmall)
                }

                // Surface the most recent import control ID and status next to the import controls
                if (importControlId.isNotBlank()) {
                    when (importStatus) {
                        "queued", "running", "in_progress" -> {
                            AsterionProgressCard(
                                title = "Import in progress",
                                progress = 0.5f,
                                detail = "Import ID: $importControlId",
                            )
                        }
                        "succeeded" -> {
                            AsterionStatusNotice("Import succeeded (id: $importControlId)")
                        }
                        "failed" -> {
                            val importRun = state.aiInstallRuns.firstOrNull { run -> run["install_id"]?.toString() == importControlId }
                            val runError = importRun?.get("error_message")?.toString().orEmpty()
                            if (runError.isNotBlank()) {
                                AsterionStatusNotice("Import failed: $runError", isError = true)
                                val details = importRun?.get("details")
                                if (details != null) {
                                    Text(details.toString(), style = MaterialTheme.typography.bodySmall)
                                }
                            } else {
                                AsterionStatusNotice("Import failed (id: $importControlId)", isError = true)
                            }
                        }
                        else -> {
                            AsterionStatusNotice("Import status: ${if (importStatus.isBlank()) "unknown" else importStatus} (id: $importControlId)")
                        }
                    }
                }
            }
        }
        }

        Text("Installed models", style = MaterialTheme.typography.titleMedium)
        if (state.aiInstalledModels.isEmpty()) {
            Text("Imported models will appear here.")
        } else {
            state.aiInstalledModels.take(40).forEach { model ->
                val listedId = model["model_id"]?.toString().orEmpty()
                val listedVersion = model["version"]?.toString().orEmpty()
                val modelKey = "$listedId@$listedVersion"
                val verificationRun = state.aiInstallRuns
                    .filter { run ->
                        run["action"]?.toString() == "verify" &&
                            run["model_id"]?.toString() == listedId &&
                            run["version"]?.toString() == listedVersion
                    }
                    .maxByOrNull { run -> run["created_at_ms"].asLongNullable() ?: 0L }
                val verificationLabel = when (verificationRun?.get("status")?.toString()?.lowercase()) {
                    "succeeded" -> "Verified"
                    "failed" -> "Verification failed"
                    else -> "Not verified"
                }
                val metadata = model["metadata"] as? Map<*, *>
                val readiness = metadata?.get("execution_readiness") as? Map<*, *>
                val executionReady = readiness?.get("ready") != false
                val isActive = executionReady &&
                    listedId == activeGlobalModelId &&
                    (activeGlobalModelVersion.isBlank() || activeGlobalModelVersion == listedVersion)
                val stateLabel = when {
                    isActive -> "Selected (session)"
                    !executionReady -> "Imported / not execution-ready"
                    else -> "Ready"
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            model["display_name"]?.toString().orEmpty().ifBlank { listedId },
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "$verificationLabel · $stateLabel · ${humanBytes(model["size_bytes"].asLongNullable() ?: 0L)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Button(
                                onClick = { onSetActiveModel(listedId, listedVersion, "") },
                                enabled = listedId.isNotBlank() &&
                                    !isActive &&
                                    executionReady &&
                                    verificationLabel == "Verified",
                            ) {
                                Text(if (isActive) "Selected" else "Activate")
                            }
                            if (verificationLabel != "Verified") {
                                TextButton(
                                    onClick = { onVerifyModel(listedId, listedVersion) },
                                    enabled = listedId.isNotBlank() && listedVersion.isNotBlank(),
                                ) {
                                    Text("Verify")
                                }
                            }
                            TextButton(onClick = { previewedModelKey = if (previewedModelKey == modelKey) "" else modelKey }) {
                                Text(if (previewedModelKey == modelKey) "Less" else "Details")
                            }
                        }
                        AnimatedVisibility(visible = previewedModelKey == modelKey) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    if (executionReady) {
                                        "Execution contract ready. Selection stays session-only until runtime work is requested."
                                    } else {
                                        "Imported successfully, but this package does not yet expose a complete execution contract."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(
                                    onClick = { onRemoveModel(listedId, listedVersion) },
                                    enabled = listedId.isNotBlank(),
                                ) {
                                    Text("Remove model")
                                }
                            }
                        }
                    }
                }
            }
        }

        AssistChip(
            onClick = { showTaskAssignments = !showTaskAssignments },
            label = { Text(if (showTaskAssignments) "Hide task assignments" else "Task assignments") },
        )
        AnimatedVisibility(visible = showTaskAssignments) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AI Tasks", style = MaterialTheme.typography.titleMedium)
        if (taskCapabilities.isEmpty()) {
            Text("Task assignments will appear when installed models report capabilities.")
        } else {
            taskCapabilities.forEach { taskType ->
                val candidates = executionReadyModels.filter { model ->
                    (model["supported_tasks"] as? List<*>)
                        ?.any { it?.toString()?.trim() == taskType }
                        ?: false
                }
                val selectedId = state.aiSettings["active_model_id.$taskType"]?.toString().orEmpty()
                val selectedVersion = state.aiSettings["active_model_version.$taskType"]?.toString().orEmpty()
                val selected = candidates.firstOrNull { model ->
                    model["model_id"]?.toString() == selectedId &&
                        (selectedVersion.isBlank() || model["version"]?.toString() == selectedVersion)
                }
                val assigned = selected ?: candidates.singleOrNull()
                val assignedName = assigned?.get("display_name")?.toString()
                    ?.ifBlank { assigned["model_id"]?.toString().orEmpty() }
                    ?: "No compatible installed model"
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(taskType.replace('_', ' ').replaceFirstChar { it.uppercase() })
                            Text(assignedName, style = MaterialTheme.typography.bodySmall)
                            if (candidates.size == 1) {
                                Text("Automatically assigned", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (candidates.size > 1) {
                            Box {
                                Button(onClick = { taskPickerKey = taskType }) { Text("Change") }
                                DropdownMenu(
                                    expanded = taskPickerKey == taskType,
                                    onDismissRequest = { taskPickerKey = "" },
                                ) {
                                    candidates.forEach { candidate ->
                                        val candidateId = candidate["model_id"]?.toString().orEmpty()
                                        val candidateVersion = candidate["version"]?.toString().orEmpty()
                                        val candidateName = candidate["display_name"]?.toString().orEmpty().ifBlank { candidateId }
                                        DropdownMenuItem(
                                            text = { Text(candidateName) },
                                            onClick = {
                                                taskPickerKey = ""
                                                onSetActiveModel(candidateId, candidateVersion, taskType)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("AI Runtime", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Status: " + if (activeGlobalModelId.isNotBlank()) {
                        "Session model selected; runtime not initialized"
                    } else {
                        "No session model selected"
                    },
                )
                Text("Configuration: Manual selection")
                Text("Installed Models: ${state.aiInstalledModels.size} (${executionReadyModels.size} execution-ready)")
                Text(
                    "Capabilities Available: ${taskCapabilities.joinToString(", ") { it.replace('_', ' ').replaceFirstChar { letter -> letter.uppercase() } }}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

            }
        }

        AssistChip(
            onClick = { showResourceTools = !showResourceTools },
            label = { Text(if (showResourceTools) "Hide resources" else "Knowledge & Fusion") },
        )
        AnimatedVisibility(visible = showResourceTools) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Knowledge Management", style = MaterialTheme.typography.titleMedium)
                Text("Knowledge packs are installed and managed here without affecting Fusion database maintenance.", style = MaterialTheme.typography.bodySmall)
                Text(
                    if (hasSelectedKnowledgeDocument) {
                        "Selected knowledge file: ${selectedKnowledgeDocumentName.ifBlank { "Selected document" }}"
                    } else {
                        "Select a Knowledge JSON or ZIP reference release."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (selectedKnowledgeDocumentIsArchive) {
                    Text(
                        "ZIP reference release detected. It will be unpacked and parsed as a grouped series/taxonomy release; it will not be read as JSON text.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onChooseKnowledgeDocument) { Text("Browse Knowledge File") }
                    Button(
                        onClick = { onPreviewKnowledgeDocument(importForm) },
                        enabled = hasSelectedKnowledgeDocument && !selectedKnowledgeDocumentIsArchive,
                    ) { Text("Preview JSON Pack") }
                    Button(
                        onClick = { onValidateKnowledgeDocument(importForm) },
                        enabled = hasSelectedKnowledgeDocument && !selectedKnowledgeDocumentIsArchive,
                    ) { Text("Validate JSON Pack") }
                    Button(onClick = { onImportKnowledgeDocument(importForm) }, enabled = hasSelectedKnowledgeDocument) {
                        Text(if (selectedKnowledgeDocumentIsArchive) "Import Reference ZIP" else "Install Knowledge Pack")
                    }
                }

                Text("Installed knowledge packs", style = MaterialTheme.typography.titleSmall)
                if (state.knowledgePacks.isEmpty()) {
                    Text("No installed knowledge packs found.", style = MaterialTheme.typography.bodySmall)
                } else {
                    state.knowledgePacks.take(6).forEach { pack ->
                        val packName = pack["name"]?.toString().orEmpty()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(packName.ifBlank { "Unnamed pack"}, style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { onRemoveKnowledgePack(packName) }, enabled = packName.isNotBlank()) {
                                Text("Delete Installed Knowledge Pack")
                            }
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Fusion Database", style = MaterialTheme.typography.titleMedium)
                Text("Fusion rebuild, validation, export, and rollback actions are maintained here.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRebuildFusionDatabase) { Text("Rebuild Fusion Database") }
                    Button(onClick = onValidateFusionDatabase) { Text("Validate Fusion Database") }
                    Button(onClick = { onExportFusionSnapshot(fusionFormat, fusionPrettyExport) }) { Text("Export Fusion Database") }
                    Button(onClick = { onRollbackImport(importControlId) }, enabled = canRollbackImport) {
                        Text("Rollback Fusion Import")
                    }
                }
                Text("Status: ${fusionStatus["database_status"] ?: "Loading"}", style = MaterialTheme.typography.bodySmall)
                Text("Health: ${fusionStatus["database_health"] ?: "Loading"}", style = MaterialTheme.typography.bodySmall)
                Text("Rebuild Required: ${if (fusionStatus["rebuild_required"] == true) "Yes" else "No"}", style = MaterialTheme.typography.bodySmall)
                Text("Validation Status: ${if (fusionStatus["database_health"] == null) "Pending" else "Available"}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Text("Installed Databases and Knowledge Packs", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Knowledge packs: ${state.knowledgePacks.size} | Downloads: ${state.downloads.size}")
                Text(
                    "Fusion database health is available through Validate Fusion DB. Rollback removes imported database content when an import ID is available.",
                    style = MaterialTheme.typography.bodySmall,
                )

                if (state.knowledgePacks.isEmpty()) {
                    Text("No installed knowledge pack artifacts found.")
                } else {
                    state.knowledgePacks.take(10).forEach { item ->
                        Text(
                            "KP ${item["name"] ?: "unknown"} • ${humanBytes(item["size_bytes"].asLongNullable() ?: 0L)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (state.downloads.isEmpty()) {
                    Text("No local download artifacts found.")
                } else {
                    state.downloads.take(10).forEach { item ->
                        Text(
                            "DL ${item["name"] ?: "unknown"} • ${humanBytes(item["size_bytes"].asLongNullable() ?: 0L)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

            }
        }

        AssistChip(
            onClick = { showInstallHistory = !showInstallHistory },
            label = { Text(if (showInstallHistory) "Hide history" else "Import history") },
        )
        AnimatedVisibility(visible = showInstallHistory) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Install and Import Runs", style = MaterialTheme.typography.titleMedium)
        if (state.aiInstallRuns.isEmpty()) {
            Text("No install runs recorded yet.")
        } else {
            state.aiInstallRuns.take(20).forEach { run ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val action = run["action"]?.toString().orEmpty()
                        val modelId = run["model_id"]?.toString().orEmpty()
                        val version = run["version"]?.toString().orEmpty()
                        val status = run["status"]?.toString().orEmpty().replaceFirstChar { it.uppercase() }
                        val retry = run["retry_count"] ?: 0
                        Text(if (modelId.isNotBlank()) "$action $modelId@$version" else action)
                        Text("Status: $status • Retries: $retry")
                        val error = run["error_message"]?.toString().orEmpty()
                        if (error.isNotBlank()) {
                            AsterionStatusNotice("$error", isError = true)
                        }
                    }
                }
            }
        }

            }
        }

        if (state.lastActionMessage != null) {
            Text(state.lastActionMessage)
        }
        if (state.errorMessage != null) {
            Text("Error: ${state.errorMessage}")
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onNavigate(AppDestination.RecognitionResults) }) { Text("Recognition") }
            Button(onClick = { onNavigate(AppDestination.Automation) }) { Text("Automation") }
            Button(onClick = { onNavigate(AppDestination.Dashboard) }) { Text("Dashboard") }
        }
    }
}

@Composable
private fun KnowledgePackManagerScreen(
    state: AppUiState,
    selectedPackNames: List<String>,
    onChoosePacks: () -> Unit,
    onImportPacks: () -> Unit,
    onPreviewPack: (String) -> Unit,
    onReplacePack: (String) -> Unit,
    onRemovePack: (String) -> Unit,
    onNavigate: (AppDestination) -> Unit,
) {
    val preview = state.aiLastPipelineResult.takeIf {
        it["kind"]?.toString() == "knowledge_pack_preview"
    }?.get("pack") as? Map<*, *>
    val previewedPackName = preview?.get("name")?.toString().orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Knowledge Management", style = MaterialTheme.typography.headlineMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Knowledge Pack Actions", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (selectedPackNames.size) {
                        0 -> "Choose one or more pack files to import."
                        1 -> "Selected: ${selectedPackNames.first()}"
                        else -> "Selected: ${selectedPackNames.size} packs"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onChoosePacks) { Text("Browse Packs") }
                    Button(onClick = onImportPacks, enabled = selectedPackNames.isNotEmpty()) { Text("Import Selected") }
                }
                Text(
                    "Jsons.zip can be imported directly. It loads the canonical series and non-character vocabulary used by automation. Character knowledge is intentionally separate for the next pass.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // If a recent knowledge-pack import was performed, surface failures/summaries inline here
                val kpImport = state.aiLastPipelineResult.takeIf { it["kind"]?.toString() == "knowledge_pack_import" }
                kpImport?.let { result ->
                    val results = (result["results"] as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: emptyList()
                    val failed = results.filter { row -> (row["ok"] as? Boolean) != true }
                    val succeeded = results.count { row -> (row["ok"] as? Boolean) == true }
                    if (results.isNotEmpty()) {
                        Text("Import summary: $succeeded succeeded, ${failed.size} failed", style = MaterialTheme.typography.bodySmall)
                        failed.forEach { row ->
                            val name = row["filename"]?.toString() ?: row["name"]?.toString() ?: "(unknown)"
                            val message = row["message"]?.toString().orEmpty()
                            AsterionStatusNotice("$name: ${if (message.isNotBlank()) message else "Import failed."}", isError = true)
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Installed Knowledge Packs", style = MaterialTheme.typography.titleMedium)
                if (state.knowledgePacks.isEmpty()) {
                    Text("Imported Knowledge Packs will appear here.")
                } else {
                    state.knowledgePacks.forEach { pack ->
                        val name = pack["name"]?.toString().orEmpty()
                        val version = pack["version"]?.toString().orEmpty().ifBlank { "Unversioned" }
                        val status = pack["status"]?.toString().orEmpty().ifBlank { "Installed" }
                        val previewVisible = previewedPackName == name
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateContentSize()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(name.ifBlank { "Unnamed Knowledge Pack" })
                                Text("Version: $version | Status: $status")
                                Text("Size: ${humanBytes(pack["size_bytes"].asLongNullable() ?: 0L)}", style = MaterialTheme.typography.bodySmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { onPreviewPack(name) }, enabled = name.isNotBlank()) { Text("Preview") }
                                    Button(onClick = { onReplacePack(name) }, enabled = name.isNotBlank()) { Text("Replace") }
                                    Button(onClick = { onRemovePack(name) }, enabled = name.isNotBlank()) { Text("Remove") }
                                }
                                AnimatedVisibility(visible = previewVisible) {
                                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text("Read-only Summary", style = MaterialTheme.typography.titleSmall)
                                        val metadata = preview?.get("metadata") as? Map<*, *>
                                        Text("Pack Name: ${preview?.get("pack_name") ?: name}", style = MaterialTheme.typography.bodySmall)
                                        Text("Version: ${preview?.get("version") ?: version}", style = MaterialTheme.typography.bodySmall)
                                        Text("Author: ${metadata?.get("author") ?: "Unknown"}", style = MaterialTheme.typography.bodySmall)
                                        Text("Creation Date: ${metadata?.get("creation_date") ?: "Unknown"}", style = MaterialTheme.typography.bodySmall)
                                        Text("Knowledge Type: ${metadata?.get("knowledge_type") ?: "Unknown"}", style = MaterialTheme.typography.bodySmall)
                                        Text("Entries: ${metadata?.get("entries") ?: 0}", style = MaterialTheme.typography.bodySmall)
                                        Text("Supported Categories: ${(metadata?.get("supported_categories") as? List<*>)?.joinToString().orEmpty().ifBlank { "None" }}", style = MaterialTheme.typography.bodySmall)
                                        Text("Dependencies: ${(metadata?.get("dependencies") as? List<*>)?.joinToString().orEmpty().ifBlank { "None" }}", style = MaterialTheme.typography.bodySmall)
                                        Text("Description: ${metadata?.get("description")?.toString().orEmpty().ifBlank { "None" }}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        state.lastActionMessage?.let { message ->
            AsterionStatusNotice(message)
        }
        state.errorMessage?.let { message ->
            AsterionStatusNotice(message, isError = true)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onNavigate(AppDestination.Dashboard) }) { Text("Dashboard") }
        }
    }
}

@Composable
private fun FusionDatabaseScreen(
    state: AppUiState,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onRebuild: () -> Unit,
    onNavigate: (AppDestination) -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }

    val status = state.lastMaintenanceResult.takeIf {
        it["kind"]?.toString() == "fusion_management"
    } ?: emptyMap()
    val automationTasks = state.aiTasks
    val runningTasks = automationTasks.count { it["status"]?.toString() == "running" }
    val pendingTasks = automationTasks.count { it["status"]?.toString() in setOf("pending", "paused") }
    val failedTasks = automationTasks.filter { it["status"]?.toString() == "failed" }
    val knowledgeChanged = state.knowledgeAutomationStatus?.contains("Knowledge changed", ignoreCase = true) == true
    val fusionNeedsRebuild = status["rebuild_required"] == true || knowledgeChanged
    val automationStatus = when {
        runningTasks > 0 -> "Running"
        failedTasks.isNotEmpty() -> "Needs attention"
        pendingTasks > 0 -> "Queued"
        else -> "Idle"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Fusion", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Database and automation health",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Overview", style = MaterialTheme.typography.titleMedium)
                Text("Database · ${status["database_health"] ?: status["database_status"] ?: "Loading"}")
                Text("Automation · $automationStatus")
                Text("Knowledge packs · ${state.knowledgePacks.size}")
                Text("Search index · ${status["search_index_status"] ?: "Loading"}")
                if (fusionNeedsRebuild) {
                    AsterionStatusNotice("Fusion rebuild recommended.")
                }
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onRebuild) { Text("Rebuild") }
            Button(onClick = onRefresh) { Text("Refresh") }
            TextButton(onClick = onExport) { Text("Export") }
        }

        AssistChip(
            onClick = { showDiagnostics = !showDiagnostics },
            label = { Text(if (showDiagnostics) "Hide diagnostics" else "Diagnostics") },
        )

        AnimatedVisibility(visible = showDiagnostics) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Database", style = MaterialTheme.typography.titleSmall)
                        Text("Version: ${status["fusion_version"] ?: "Loading"}")
                        Text("SQLite: ${status["sqlite_health"] ?: "Loading"}")
                        Text("Integrity: ${status["database_integrity"] ?: "Loading"}")
                        Text("Index integrity: ${status["index_integrity"] ?: "Loading"}")
                        Text("FTS integrity: ${status["fts_integrity"] ?: "Loading"}")
                        Text("Missing references: ${status["missing_references"] ?: "Loading"}")
                        Text("Orphans: ${status["orphan_entries"] ?: "Loading"}")
                        Text("Corrupted records: ${status["corrupted_records"] ?: "Loading"}")
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Maintenance", style = MaterialTheme.typography.titleSmall)
                        Text("Last build: ${formatFusionTimestamp(status["last_build_ms"].asLongNullable() ?: 0L)}")
                        Text("Last optimization: ${formatFusionTimestamp(status["last_optimization_ms"].asLongNullable() ?: 0L)}")
                        Text("Last export: ${formatFusionTimestamp(status["exported_at_ms"].asLongNullable() ?: 0L)}")
                        Text("Pending tasks: $pendingTasks")
                        Text("Failed tasks: ${failedTasks.size}")
                        failedTasks.firstOrNull()?.get("error_message")?.toString()?.takeIf { it.isNotBlank() }?.let {
                            AsterionStatusNotice(it, isError = true)
                        }
                    }
                }
            }
        }

        if (status["logical_content_preserved"] == false) {
            AsterionStatusNotice(
                "Rebuild was aborted because logical Fusion row counts changed.",
                isError = true,
            )
        }
        state.lastActionMessage?.let { AsterionStatusNotice(it) }
        state.errorMessage?.let { AsterionStatusNotice(it, isError = true) }

        TextButton(onClick = { onNavigate(AppDestination.Dashboard) }) { Text("Dashboard") }
    }
}

private fun formatFusionTimestamp(value: Long): String {
    return if (value <= 0L) "Never" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(value))
}

@Composable
private fun RuntimeLogsScreen(
    state: AppUiState,
    onRefresh: () -> Unit,
    onNavigate: (AppDestination) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Logs", style = MaterialTheme.typography.headlineMedium)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRefresh) { Text("Refresh") }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.errorMessage != null) {
                    AsterionStatusNotice(state.errorMessage, isError = true)
                } else {
                    Text("Current error: none")
                }
                if (state.lastActionMessage != null) {
                    AsterionStatusNotice(state.lastActionMessage)
                } else {
                    Text("Last action: n/a")
                }
                Text("Scan status: ${state.scanStatus} (${state.scanProgress.toInt()}%)")
            }
        }

        Text("Recent Scan Runs", style = MaterialTheme.typography.titleMedium)
        if (state.scanRuns.isEmpty()) {
            Text("No scan runs recorded.")
        } else {
            state.scanRuns.take(30).forEach { run ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("scan_id=${run["scan_id"]} status=${run["status"]}")
                        Text("folder=${run["folder_uri"]?.toString().orEmpty().displayFolderLabel("(no folder)")}")
                        Text("discovered=${run["discovered_count"]} skipped=${run["skipped_count"]}")
                        val error = run["error_message"]?.toString().orEmpty()
                        if (error.isNotBlank()) {
                            Text("error=$error", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Text("AI Install Runs", style = MaterialTheme.typography.titleMedium)
        if (state.aiInstallRuns.isEmpty()) {
            Text("No AI install runs recorded.")
        } else {
            state.aiInstallRuns.take(30).forEach { row ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val action = row["action"]?.toString().orEmpty()
                        val modelId = row["model_id"]?.toString().orEmpty()
                        val version = row["version"]?.toString().orEmpty()
                        val status = row["status"]?.toString().orEmpty().replaceFirstChar { it.uppercase() }
                        val retry = row["retry_count"] ?: 0
                        Text(if (modelId.isNotBlank()) "$action $modelId@$version" else action)
                        Text("Status: $status • Retries: $retry")
                        val error = row["error_message"]?.toString().orEmpty()
                        if (error.isNotBlank()) {
                            AsterionStatusNotice(error, isError = true)
                        }
                    }
                }
            }
        }

        Text("AI Execution Sessions", style = MaterialTheme.typography.titleMedium)
        if (state.aiExecutionSessions.isEmpty()) {
            Text("No AI sessions recorded.")
        } else {
            state.aiExecutionSessions.take(40).forEach { row ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("session=${row["session_id"]}")
                        Text("task=${row["task_type"]} status=${row["status"]} progress=${row["progress"]}")
                        Text("backend=${row["backend_id"]} runtime=${row["runtime_id"]}")
                        val error = row["error_message"]?.toString().orEmpty()
                        if (error.isNotBlank()) {
                            Text("error=$error", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Text("Runtime Health", style = MaterialTheme.typography.titleMedium)
        if (state.aiRuntimeHealthSnapshots.isEmpty()) {
            Text("No runtime health snapshots recorded.")
        } else {
            state.aiRuntimeHealthSnapshots.take(40).forEach { row ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("runtime=${row["runtime_id"]} backend=${row["backend_id"]}")
                        Text("status=${row["status"]} healthy=${row["healthy"]} latency_ms=${row["latency_ms"]}")
                        Text("captured_at=${row["captured_at_ms"]}")
                    }
                }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onNavigate(AppDestination.Automation) }) { Text("Automation") }
            Button(onClick = { onNavigate(AppDestination.PluginManager) }) { Text("Plugin Manager") }
            Button(onClick = { onNavigate(AppDestination.Dashboard) }) { Text("Dashboard") }
        }
    }
}

@Composable
private fun SearchScreen(
    title: String,
    state: AppUiState,
    mode: String,
    onSearchByFilename: (String) -> Unit,
    onSearchByImageId: (String) -> Unit,
    onAdvancedSearch: (Map<String, Any>) -> Unit,
    onSemanticSearch: (String) -> Unit,
    onClearResults: () -> Unit,
    onOpenImage: (Map<String, Any>) -> Unit,
    onNavigate: (AppDestination) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var imageIdQuery by rememberSaveable { mutableStateOf("") }
    var fullText by rememberSaveable { mutableStateOf("") }
    var collection by rememberSaveable { mutableStateOf("") }
    var tagsCsv by rememberSaveable { mutableStateOf("") }
    var taxonomyKey by rememberSaveable { mutableStateOf("") }
    var taxonomyValue by rememberSaveable { mutableStateOf("") }
    var includeInactive by rememberSaveable { mutableStateOf(false) }
    var sortBy by rememberSaveable { mutableStateOf("import_order") }
    var sortDirection by rememberSaveable { mutableStateOf("desc") }
    var sortMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(mode == "advanced") }
    var recentQueries by rememberSaveable { mutableStateOf(emptyList<String>()) }

    val results = state.searchResults.ifEmpty { state.images }

    fun runPrimarySearch() {
        val normalized = query.trim()
        if (normalized.isBlank()) return
        recentQueries = (listOf(normalized) + recentQueries.filterNot { it.equals(normalized, ignoreCase = true) }).take(6)
        if (mode == "semantic") onSemanticSearch(normalized) else onSearchByFilename(normalized)
    }

    fun runAdvanced() {
        val payload = mutableMapOf<String, Any>(
            "query" to query,
            "sort_by" to sortBy,
            "sort_direction" to sortDirection,
            "include_inactive" to includeInactive,
            "page" to 1,
            "page_size" to 0,
        )
        if (fullText.isNotBlank()) payload["full_text"] = fullText
        if (collection.isNotBlank()) payload["collection"] = collection
        val tags = tagsCsv.split(',', '|').map { it.trim() }.filter { it.isNotBlank() }
        if (tags.isNotEmpty()) payload["tags"] = tags
        if (taxonomyKey.isNotBlank() && taxonomyValue.isNotBlank()) {
            payload["taxonomy_filters"] = mapOf(taxonomyKey.trim() to taxonomyValue.trim())
        }
        imageIdQuery.trim().toIntOrNull()?.let { payload["image_id"] = it }
        onAdvancedSearch(payload)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (mode == "semantic") "Semantic search" else "Search", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(if (mode == "semantic") "Describe what you want to find" else "Filename or keyword") },
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { runPrimarySearch() }, enabled = query.isNotBlank()) { Text("Search") }
            TextButton(onClick = {
                query = ""
                imageIdQuery = ""
                fullText = ""
                collection = ""
                tagsCsv = ""
                taxonomyKey = ""
                taxonomyValue = ""
                includeInactive = false
                onClearResults()
            }) { Text("Clear") }
            AssistChip(
                onClick = { showAdvanced = !showAdvanced },
                label = { Text(if (showAdvanced) "Hide filters" else "Filters") },
            )
        }

        if (recentQueries.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                recentQueries.forEach { recent ->
                    AssistChip(
                        onClick = { query = recent },
                        label = { Text(recent, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }

        AnimatedVisibility(visible = showAdvanced) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Filters", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = imageIdQuery,
                        onValueChange = { imageIdQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Image ID") },
                    )
                    OutlinedTextField(
                        value = fullText,
                        onValueChange = { fullText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Full text") },
                    )
                    OutlinedTextField(
                        value = tagsCsv,
                        onValueChange = { tagsCsv = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Tags") },
                    )
                    OutlinedTextField(
                        value = collection,
                        onValueChange = { collection = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Collection") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = taxonomyKey,
                            onValueChange = { taxonomyKey = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Taxonomy key") },
                        )
                        OutlinedTextField(
                            value = taxonomyValue,
                            onValueChange = { taxonomyValue = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Value") },
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box {
                            AssistChip(
                                onClick = { sortMenuExpanded = true },
                                label = { Text("Sort: $sortBy") },
                            )
                            DropdownMenu(
                                expanded = sortMenuExpanded,
                                onDismissRequest = { sortMenuExpanded = false },
                            ) {
                                listOf("import_order", "filename", "date", "size", "resolution", "random").forEach { key ->
                                    DropdownMenuItem(
                                        text = { Text(key.replace('_', ' ')) },
                                        onClick = {
                                            sortBy = key
                                            sortMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        AssistChip(
                            onClick = { sortDirection = if (sortDirection == "asc") "desc" else "asc" },
                            label = { Text(sortDirection.uppercase()) },
                        )
                        AssistChip(
                            onClick = { includeInactive = !includeInactive },
                            label = { Text(if (includeInactive) "Inactive included" else "Active only") },
                        )
                    }
                    Button(onClick = { runAdvanced() }) { Text("Apply filters") }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${results.size} results", style = MaterialTheme.typography.titleSmall)
            Text(
                "$sortBy · ${sortDirection.uppercase()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.lastActionMessage?.let { AsterionStatusNotice(it) }
        state.errorMessage?.let { AsterionStatusNotice(it, isError = true) }

        if (state.loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (results.isEmpty()) {
            AsterionEmptyState(
                title = "No results",
                detail = "Try a broader query or remove filters.",
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(results) { item ->
                    val caption = item["filename"]?.toString().orEmpty().ifBlank {
                        item["path"]?.toString().orEmpty().ifBlank { "Untitled" }
                    }
                    val thumbnail = item["thumbnail_url"]?.toString()?.takeIf { it.isNotBlank() }
                        ?: item["file_url"]?.toString()?.takeIf { it.isNotBlank() }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenImage(item) },
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!thumbnail.isNullOrBlank()) {
                                ImageTile(
                                    model = thumbnail,
                                    contentDescription = caption,
                                    modifier = Modifier
                                        .width(92.dp)
                                        .height(72.dp),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    item["path"]?.toString().orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                item.imageId()?.let {
                                    Text(
                                        "ID $it",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onNavigate(AppDestination.Dashboard) }) { Text("Dashboard") }
            TextButton(onClick = { onNavigate(AppDestination.LibraryBrowser) }) { Text("Library") }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: AppUiState,
    onChooseFolder: () -> Unit,
    onAddFolder: () -> Unit,
    onConfigureTeraBox: (String, String, String) -> Unit,
    onStartTeraBoxLogin: () -> Unit,
    onRefreshTeraBox: () -> Unit,
    onDisconnectTeraBox: () -> Unit,
    onAddTeraBoxLibraryRoot: (String) -> Unit,
    onSetFolderEnabled: (String, Boolean) -> Unit,
    onRemoveFolder: (String) -> Unit,
    onRescanFolder: (String) -> Unit,
    onRescanEnabledFolders: () -> Unit,
    onRebuildSearchIndex: () -> Unit,
    onOptimizeDatabase: () -> Unit,
    onMaintainThumbnailCache: (Int) -> Unit,
    onClearThumbnailCache: () -> Unit,
    onUpdateSetting: (String, String) -> Unit,
    onLoadSetting: (String, String) -> Unit,
    onRefresh: () -> Unit,
    onNavigate: (AppDestination) -> Unit,
) {
    var cacheMbText by rememberSaveable { mutableStateOf("256") }
    var settingKey by rememberSaveable { mutableStateOf("") }
    var settingValue by rememberSaveable { mutableStateOf("") }
    var teraBoxClientId by rememberSaveable { mutableStateOf("") }
    var teraBoxClientSecret by rememberSaveable { mutableStateOf("") }
    var teraBoxPrivateSecret by rememberSaveable { mutableStateOf("") }
    var teraBoxRootPath by rememberSaveable { mutableStateOf("/") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRefresh) {
                Text("Refresh Data")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Runtime mode: Android standalone")
                Text("Library folder:")
                Text(
                    text = state.selectedLibraryUri.displayFolderLabel("No folder selected."),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onChooseFolder) {
                        Text("Choose / Change Library Folder")
                    }
                    Button(onClick = onAddFolder) {
                        Text("Add Folder")
                    }
                    Button(onClick = onRescanEnabledFolders) {
                        Text("Rescan Enabled")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Folder Manager", style = MaterialTheme.typography.titleMedium)
                if (state.libraryFolders.isEmpty()) {
                    Text("No folders in manager.")
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.libraryFolders) { folder ->
                            val folderUri = folder["folder_uri"]?.toString().orEmpty()
                            val enabled = folder["enabled"] as? Boolean ?: false
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(folderUri.displayFolderLabel("(no folder)"), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("Enabled: $enabled")
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { onSetFolderEnabled(folderUri, !enabled) }) {
                                            Text(if (enabled) "Disable" else "Enable")
                                        }
                                        Button(onClick = { onRescanFolder(folderUri) }) {
                                            Text("Rescan")
                                        }
                                        Button(onClick = { onRemoveFolder(folderUri) }) {
                                            Text("Remove")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TeraBox Open Platform", style = MaterialTheme.typography.titleMedium)
                val configured = state.teraBoxStatus["configured"] == true
                val connected = state.teraBoxStatus["connected"] == true
                Text(
                    when {
                        connected -> "Connected"
                        configured -> "Credentials saved — login required"
                        else -> "Not configured"
                    },
                    color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = teraBoxClientId,
                    onValueChange = { teraBoxClientId = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("client_id") },
                )
                OutlinedTextField(
                    value = teraBoxClientSecret,
                    onValueChange = { teraBoxClientSecret = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("client_secret") },
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = teraBoxPrivateSecret,
                    onValueChange = { teraBoxPrivateSecret = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("private_secret") },
                    visualTransformation = PasswordVisualTransformation(),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = {
                            onConfigureTeraBox(teraBoxClientId, teraBoxClientSecret, teraBoxPrivateSecret)
                            teraBoxClientSecret = ""
                            teraBoxPrivateSecret = ""
                        },
                        enabled = teraBoxClientId.isNotBlank() &&
                            teraBoxClientSecret.isNotBlank() &&
                            teraBoxPrivateSecret.isNotBlank(),
                    ) {
                        Text("Save Credentials")
                    }
                    Button(onClick = onStartTeraBoxLogin, enabled = configured) {
                        Text(if (connected) "Reconnect" else "Login to TeraBox")
                    }
                    Button(onClick = onRefreshTeraBox) {
                        Text("Refresh Status")
                    }
                    if (connected) {
                        Button(onClick = onDisconnectTeraBox) {
                            Text("Disconnect")
                        }
                    }
                }

                if (connected) {
                    OutlinedTextField(
                        value = teraBoxRootPath,
                        onValueChange = { teraBoxRootPath = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("TeraBox assigned app library path") },
                    )
                    Text(
                        "Use the Open Platform path assigned to this TeraBox app. Asterion will scan and modify files in that API-visible tree directly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { onAddTeraBoxLibraryRoot(teraBoxRootPath) }) {
                        Text("Add TeraBox as Library")
                    }
                    val domain = state.teraBoxStatus["api_domain"]?.toString().orEmpty()
                    val expires = state.teraBoxStatus["expires_at_ms"]?.toString().orEmpty()
                    if (domain.isNotBlank()) Text("API domain: $domain", style = MaterialTheme.typography.bodySmall)
                    if (expires.isNotBlank() && expires != "0") {
                        Text("Token expiry: $expires", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Text(
                    "Credentials and OAuth tokens are stored with Android Keystore-backed encryption. TeraBox authorization returns through asterioncore://teraboxOauth.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Library Maintenance", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRebuildSearchIndex) {
                        Text("Rebuild Search Index")
                    }
                    Button(onClick = onOptimizeDatabase) {
                        Text("Optimize Database")
                    }
                }
                OutlinedTextField(
                    value = cacheMbText,
                    onValueChange = { cacheMbText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Thumbnail cache max MB") },
                )
                Button(onClick = { onMaintainThumbnailCache(cacheMbText.toIntOrNull() ?: 256) }) {
                    Text("Maintain Thumbnail Cache")
                }
                Button(onClick = onClearThumbnailCache) {
                    Text("Clear Thumbnail Cache")
                }
                if (state.lastMaintenanceResult.isNotEmpty()) {
                    state.lastMaintenanceResult.entries.forEach { entry ->
                        Text("${entry.key}: ${entry.value}")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Runtime Settings", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = settingKey,
                    onValueChange = { settingKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Setting key") },
                )
                OutlinedTextField(
                    value = settingValue,
                    onValueChange = { settingValue = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Setting value") },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onUpdateSetting(settingKey, settingValue) }) {
                        Text("Save Setting")
                    }
                    Button(onClick = { onLoadSetting(settingKey, "") }) {
                        Text("Load Setting")
                    }
                }
                val loaded = state.settingsValues[settingKey.trim()]
                if (!loaded.isNullOrBlank()) {
                    Text("Loaded value: $loaded")
                }
            }
        }

        if (state.lastActionMessage != null) {
            Text(state.lastActionMessage)
        }
        if (state.errorMessage != null) {
            Text("Error: ${state.errorMessage}")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onNavigate(AppDestination.Dashboard) }) {
                Text("Dashboard")
            }
            Button(onClick = { onNavigate(AppDestination.FirstLaunchWizard) }) {
                Text("Setup Wizard")
            }
        }
    }
}

@Composable
private fun StatisticsScreen(
    state: AppUiState,
    onRefresh: () -> Unit,
    onNavigate: (AppDestination) -> Unit,
) {
    val imageStats = remember(state.images) { computeImageStats(state.images) }
    val scanStats = remember(state.scanRuns) { computeScanStats(state.scanRuns) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Statistics", style = MaterialTheme.typography.headlineMedium)

        Button(onClick = onRefresh) {
            Text("Refresh Statistics")
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Health: ${state.health["status"] ?: state.health["healthy"] ?: "unknown"}")
                Text("Total images: ${state.stats["total_images"] ?: state.images.size}")
                Text("Total folders: ${state.stats["total_folders"] ?: state.libraryFolders.size}")
                Text("Tagged images: ${imageStats.taggedImages}")
                Text("Average resolution: ${imageStats.avgResolution}")
                Text("Storage used: ${imageStats.storageUsedMb} MB")
                Text("Largest image: ${imageStats.largestImage}")
                Text("Smallest image: ${imageStats.smallestImage}")
                Text("Scan duration: ${scanStats.lastDuration}")
                Text("Last scan: ${scanStats.lastScan}")
                Text("Indexed images: ${state.stats["total_images"] ?: state.images.size}")
                Text("Current scan status: ${state.scanStatus}")
                Text("Current scan discovered images: ${state.scanDiscoveredImages}")
            }
        }

        Text("Scan Statistics", style = MaterialTheme.typography.titleMedium)
        if (state.scanRuns.isEmpty()) {
            Text("No scan runs yet.")
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 4.dp),
            ) {
                items(state.scanRuns) { run ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("scan_id: ${run["scan_id"]}")
                            Text("folder: ${run["folder_uri"]?.toString().orEmpty().displayFolderLabel("(no folder)")}")
                            Text("status: ${run["status"]}")
                            Text("discovered_count: ${run["discovered_count"]} | skipped_count: ${run["skipped_count"]}")
                            Text("started_at_ms: ${run["started_at_ms"]}")
                            Text("completed_at_ms: ${run["completed_at_ms"]}")
                            val error = run["error_message"]?.toString().orEmpty()
                            if (error.isNotBlank()) {
                                Text("error_message: $error")
                            }
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Library statistics", style = MaterialTheme.typography.titleMedium)
                state.stats.entries.take(10).forEach { entry ->
                    Text("${entry.key}: ${entry.value}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private data class ImageAggregateStats(
    val taggedImages: Int,
    val avgResolution: String,
    val storageUsedMb: String,
    val largestImage: String,
    val smallestImage: String,
)

private data class ScanAggregateStats(
    val lastDuration: String,
    val lastScan: String,
)

private fun computeImageStats(images: List<Map<String, Any>>): ImageAggregateStats {
    if (images.isEmpty()) {
        return ImageAggregateStats(0, "n/a", "0.00", "n/a", "n/a")
    }

    var tagged = 0
    var totalArea = 0L
    var areaCount = 0
    var totalSize = 0L
    var largest: Pair<String, Long>? = null
    var smallest: Pair<String, Long>? = null

    for (image in images) {
        val metadata = image["metadata"] as? Map<*, *> ?: emptyMap<String, Any>()
        val tags = (metadata["tags"] as? List<*>) ?: emptyList<Any>()
        if (tags.isNotEmpty()) {
            tagged += 1
        }

        val width = metadata["width"].asIntNullable()
        val height = metadata["height"].asIntNullable()
        if (width != null && height != null && width > 0 && height > 0) {
            totalArea += width.toLong() * height.toLong()
            areaCount += 1
        }

        val size = metadata["size_bytes"].asLongNullable() ?: 0L
        totalSize += size
        val name = image["filename"]?.toString().orEmpty().ifBlank { image["path"]?.toString().orEmpty() }
        if (largest == null || size > largest!!.second) {
            largest = name to size
        }
        if (smallest == null || size < smallest!!.second) {
            smallest = name to size
        }
    }

    val avgResolution = if (areaCount == 0) {
        "n/a"
    } else {
        val avgArea = totalArea / max(areaCount, 1)
        "${avgArea} px^2"
    }

    return ImageAggregateStats(
        taggedImages = tagged,
        avgResolution = avgResolution,
        storageUsedMb = "%.2f".format(totalSize.toDouble() / (1024.0 * 1024.0)),
        largestImage = largest?.let { "${it.first} (${humanBytes(it.second)})" } ?: "n/a",
        smallestImage = smallest?.let { "${it.first} (${humanBytes(it.second)})" } ?: "n/a",
    )
}

private fun computeScanStats(scanRuns: List<Map<String, Any>>): ScanAggregateStats {
    val latest = scanRuns.firstOrNull()
    if (latest == null) {
        return ScanAggregateStats("n/a", "n/a")
    }

    val started = latest["started_at_ms"].asLongNullable() ?: 0L
    val completed = latest["completed_at_ms"].asLongNullable() ?: 0L
    val durationMs = if (completed > started && started > 0L) completed - started else 0L
    val durationSec = durationMs.toDouble() / 1000.0

    return ScanAggregateStats(
        lastDuration = if (durationMs > 0L) "${durationSec.roundToInt()} sec" else "n/a",
        lastScan = latest["started_at_ms"]?.toString() ?: "n/a",
    )
}

private fun sortComparator(sortBy: String, sortDirection: String): Comparator<Map<String, Any>> {
    val direction = if (sortDirection.equals("asc", ignoreCase = true)) 1 else -1
    return Comparator { a, b ->
        fun cmpLong(left: Long?, right: Long?): Int {
            return when {
                left == null && right == null -> 0
                left == null -> -1 * direction
                right == null -> 1 * direction
                left < right -> -1 * direction
                left > right -> 1 * direction
                else -> 0
            }
        }

        fun cmpInt(left: Int?, right: Int?): Int {
            return when {
                left == null && right == null -> 0
                left == null -> -1 * direction
                right == null -> 1 * direction
                left < right -> -1 * direction
                left > right -> 1 * direction
                else -> 0
            }
        }

        val result = when (sortBy) {
            "filename" -> a["filename"]?.toString().orEmpty().compareTo(b["filename"]?.toString().orEmpty()) * direction
            "date_added" -> cmpLong(a["date_indexed_ms"].asLongNullable(), b["date_indexed_ms"].asLongNullable())
            "date_modified" -> cmpLong((a["metadata"] as? Map<*, *>)?.get("modified_at_ms").asLongNullable(), (b["metadata"] as? Map<*, *>)?.get("modified_at_ms").asLongNullable())
            "size" -> cmpLong((a["metadata"] as? Map<*, *>)?.get("size_bytes").asLongNullable(), (b["metadata"] as? Map<*, *>)?.get("size_bytes").asLongNullable())
            "resolution" -> {
                val aMeta = a["metadata"] as? Map<*, *>
                val bMeta = b["metadata"] as? Map<*, *>
                val aArea = ((aMeta?.get("width").asIntNullable() ?: 0) * (aMeta?.get("height").asIntNullable() ?: 0)).toLong()
                val bArea = ((bMeta?.get("width").asIntNullable() ?: 0) * (bMeta?.get("height").asIntNullable() ?: 0)).toLong()
                cmpLong(aArea, bArea)
            }
            "random" -> if (Math.random() < 0.5) -1 else 1
            else -> 0
        }

        if (result != 0) {
            result
        } else {
            a["filename"]?.toString().orEmpty().compareTo(b["filename"]?.toString().orEmpty())
        }
    }
}

private fun Any?.asIntNullable(): Int? = when (this) {
    is Number -> this.toInt()
    is String -> this.toIntOrNull() ?: this.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 }?.toInt()
    else -> null
}

private fun Any?.asLongNullable(): Long? = when (this) {
    is Number -> this.toLong()
    is String -> this.toLongOrNull()
    else -> null
}

private fun humanBytes(value: Long): String {
    if (value <= 0L) {
        return "0 B"
    }
    val kb = 1024.0
    val mb = kb * 1024.0
    return when {
        value >= mb -> "%.2f MB".format(value / mb)
        value >= kb -> "%.1f KB".format(value / kb)
        else -> "$value B"
    }
}

@Composable
private fun ScanControlsCard(
    state: AppUiState,
    onStartScan: () -> Unit,
    onPauseScan: () -> Unit,
    onResumeScan: () -> Unit,
    onCancelScan: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AsterionProgressCard(
            title = "Library scan",
            progress = (state.scanProgress / 100.0).toFloat(),
            detail = "Stage: ${state.scanStatus.replace('_', ' ')} | Processed: ${state.scanDiscoveredImages} images | ${state.scanProgress.toInt()}% complete",
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStartScan, enabled = state.selectedLibraryUri.isNotBlank()) {
                    Text("Start")
                }
                Button(onClick = onPauseScan, enabled = state.scanStatus == "running") {
                    Text("Pause")
                }
                Button(onClick = onResumeScan, enabled = state.scanStatus == "paused") {
                    Text("Resume")
                }
                Button(
                    onClick = onCancelScan,
                    enabled = state.scanStatus in setOf("running", "paused", "queued", "in_progress"),
                ) {
                    Text("Cancel")
                }
            }
            }
        }
    }
}

@Composable
private fun MapListScreen(
    title: String,
    items: List<Map<String, Any>>,
    subtitle: String? = null,
    onNavigate: (AppDestination) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle)
        }

        if (items.isEmpty()) {
            AsterionEmptyState(
                title = "Nothing to show yet",
                detail = "Add a library folder or run a scan to begin.",
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 4.dp),
            ) {
                items(items) { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            item.entries.take(10).forEach { entry ->
                                Text("${entry.key}: ${entry.value}")
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onNavigate(AppDestination.Dashboard) }) {
                Text("Dashboard")
            }
            Button(onClick = { onNavigate(AppDestination.Settings) }) {
                Text("Settings")
            }
        }
    }
}

@Composable
private fun CollectionsScreen(
    collections: List<Map<String, Any>>,
    onOpenCollection: (String) -> Unit,
    onNavigate: (AppDestination) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Collections", style = MaterialTheme.typography.headlineMedium)

        if (collections.isEmpty()) {
            AsterionEmptyState(
                title = "No collections yet",
                detail = "Collections will appear after you organize or scan your library.",
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 4.dp),
            ) {
                items(collections) { collection ->
                    val metadata = collection["metadata"] as? Map<*, *>
                    val folderUri = metadata?.get("folder_uri")?.toString().orEmpty()
                    val name = collection["name"]?.toString().orEmpty().ifBlank { folderLabelFromUri(folderUri) }
                    val imageCount = collection["image_count"]?.toString().orEmpty().ifBlank { "0" }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(name)
                            Text("Images: $imageCount", style = MaterialTheme.typography.bodySmall)
                            if (folderUri.isNotBlank()) {
                                Text(folderUri, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Button(onClick = { onOpenCollection(folderUri) }) {
                                    Text("Open In Search")
                                }
                            }
                        }
                    }
                }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onNavigate(AppDestination.Search) }) {
                Text("Search")
            }
            Button(onClick = { onNavigate(AppDestination.Dashboard) }) {
                Text("Dashboard")
            }
        }
    }
}

@Composable
private fun TagScreen(
    tags: List<String>,
    onSearchTag: (String) -> Unit,
    onNavigate: (AppDestination) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Tags", style = MaterialTheme.typography.headlineMedium)

        if (tags.isEmpty()) {
            AsterionEmptyState(
                title = "No tags yet",
                detail = "Tags will appear after images are scanned or updated.",
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 4.dp),
            ) {
                items(tags) { tag ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = tag,
                                modifier = Modifier.weight(1f),
                            )
                            Button(onClick = { onSearchTag(tag) }) {
                                Text("Search")
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onNavigate(AppDestination.Dashboard) }) {
                Text("Dashboard")
            }
            Button(onClick = { onNavigate(AppDestination.Settings) }) {
                Text("Settings")
            }
        }
    }
}

@Composable
private fun AsterionAboutScreen(onNavigate: (AppDestination) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Image(
            painter = painterResource(R.drawable.asterioncore_logo),
            contentDescription = "AsterionCore logo",
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
            contentScale = ContentScale.Fit,
        )
        Text("AsterionCore", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Illustration intelligence, organized.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Android standalone edition", style = MaterialTheme.typography.titleMedium)
                Text("Version 2.0.0", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Button(onClick = { onNavigate(AppDestination.Dashboard) }) {
            Text("Open Dashboard")
        }
    }
}

@Composable
private fun FileManagerScreen(
    state: AppUiState,
    onPreview: (Map<String, Any>) -> Unit,
    onExecute: (Map<String, Any>) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onNavigate: (AppDestination) -> Unit,
) {
    var action by rememberSaveable { mutableStateOf("batch_move") }
    var selectedImageIds by rememberSaveable { mutableStateOf(setOf<Int>()) }
    var selectedFolderUri by rememberSaveable { mutableStateOf("") }
    var targetFolderUri by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var pattern by rememberSaveable { mutableStateOf("renamed_{n}") }
    var folderName by rememberSaveable { mutableStateOf("") }
    var conflictMode by rememberSaveable { mutableStateOf("rename") }
    var actionMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var targetFolderMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var conflictMenuExpanded by rememberSaveable { mutableStateOf(false) }

    val actionOptions = listOf(
        "rename_image" to "Rename Image",
        "batch_rename_images" to "Batch Rename Images",
        "rename_folder" to "Rename Folder",
        "create_folder" to "Create Folder",
        "delete_folder" to "Delete Folder",
        "delete_images" to "Delete Images",
        "move_images" to "Move Images",
        "copy_images" to "Copy Images",
        "batch_move" to "Batch Move",
        "batch_copy" to "Batch Copy",
        "batch_delete" to "Batch Delete",
    )

    val folderRows = state.libraryFolders
        .sortedBy { it["folder_uri"]?.toString().orEmpty() }
    val imageRows = state.images
    val selectedFolder = selectedFolderUri.ifBlank { null }
    val shownImages = if (selectedFolder == null) {
        imageRows
    } else {
        imageRows.filter { it.folderUriValue() == selectedFolder }
    }

    val selectedIdsCsv = selectedImageIds.joinToString(",")
    val targetFolder = when {
        targetFolderUri.isNotBlank() -> targetFolderUri
        selectedFolder != null -> selectedFolder
        else -> ""
    }

    val needsImageSelection = action in setOf(
        "rename_image",
        "batch_rename_images",
        "delete_images",
        "batch_delete",
        "move_images",
        "copy_images",
        "batch_move",
        "batch_copy",
    )
    val needsSingleImage = action == "rename_image"
    val needsFolderSelection = action in setOf("rename_folder", "delete_folder")
    val needsTargetFolder = action in setOf("create_folder", "move_images", "copy_images", "batch_move", "batch_copy")
    val needsFolderName = action in setOf("create_folder", "rename_folder")
    val needsImageName = action == "rename_image"
    val needsPattern = action == "batch_rename_images"

    val validationMessage = when {
        needsSingleImage && selectedImageIds.size != 1 -> "Select exactly one image for Rename Image."
        needsImageSelection && selectedImageIds.isEmpty() -> "Select one or more images."
        needsFolderSelection && selectedFolder == null -> "Select a folder in the left panel."
        needsTargetFolder && targetFolder.isBlank() -> "Select a target folder."
        needsImageName && name.isBlank() -> "Image new name is required."
        needsPattern && pattern.isBlank() -> "Batch rename pattern is required."
        needsFolderName && folderName.isBlank() -> "Folder name is required."
        else -> null
    }

    val isCompactLayout = LocalConfiguration.current.screenWidthDp < 840
    val panelRowModifier = if (isCompactLayout) {
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    } else {
        Modifier.fillMaxWidth()
    }

    fun buildPayload(): Map<String, Any> {
        val payload = mutableMapOf<String, Any>(
            "action" to action,
            "conflict_mode" to conflictMode,
        )
        if (needsImageSelection && selectedIdsCsv.isNotBlank()) payload["image_ids"] = selectedIdsCsv
        if (action in setOf("rename_folder", "delete_folder") && selectedFolder != null) payload["source_folder_uri"] = selectedFolder
        if (needsTargetFolder && targetFolder.isNotBlank()) payload["target_folder_uri"] = targetFolder
        if (needsImageName && name.isNotBlank()) payload["name"] = name.trim()
        if (needsPattern && pattern.isNotBlank()) payload["pattern"] = pattern.trim()
        if (needsFolderName && folderName.isNotBlank()) payload["folder_name"] = folderName.trim()
        return payload
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("File Manager", style = MaterialTheme.typography.headlineMedium)
        Text("Select folders and images, then run SAF file operations from the right panel.")

        Row(
            modifier = Modifier
                .weight(1f)
                .then(panelRowModifier),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = if (isCompactLayout) {
                    Modifier.width(280.dp).fillMaxHeight()
                } else {
                    Modifier.weight(0.26f).fillMaxHeight()
                },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Folders", style = MaterialTheme.typography.titleMedium)
                    Button(
                        onClick = { selectedFolderUri = "" },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (selectedFolder == null) "All Folders Selected" else "Clear Folder Selection")
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(folderRows) { folder ->
                            val folderUri = folder["folder_uri"]?.toString().orEmpty()
                            val selected = folderUri == selectedFolder
                            val depth = folderUri.split('/').count { it.isNotBlank() }.coerceAtMost(5)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = ((depth - 1).coerceAtLeast(0) * 6).dp)
                                    .clickable {
                                        selectedFolderUri = if (selected) "" else folderUri
                                        if (targetFolderUri.isBlank()) {
                                            targetFolderUri = folderUri
                                        }
                                    },
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        if (selected) "[Selected] ${folderLabelFromUri(folderUri)}" else folderLabelFromUri(folderUri),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(folderUri, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }

            Card(
                modifier = if (isCompactLayout) {
                    Modifier.width(420.dp).fillMaxHeight()
                } else {
                    Modifier.weight(0.44f).fillMaxHeight()
                },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Images", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Selected ${selectedImageIds.size} image(s)${if (selectedFolder != null) " in ${folderLabelFromUri(selectedFolder)}" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = {
                                selectedImageIds = shownImages.mapNotNull { it.imageId() }.toSet()
                            },
                            label = { Text("Select Visible") },
                        )
                        AssistChip(
                            onClick = { selectedImageIds = emptySet() },
                            label = { Text("Clear Selection") },
                        )
                    }

                    if (shownImages.isEmpty()) {
                        Text("No images for current folder selection.")
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 132.dp),
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 8.dp),
                        ) {
                            items(items = shownImages, key = { item -> item.imageId() ?: item["uri"].toString() }) { item ->
                                val id = item.imageId()
                                val selected = id != null && selectedImageIds.contains(id)
                                val title = item["filename"]?.toString().orEmpty().ifBlank { "Image" }
                                val thumbnailUrl = item["thumbnail_url"]?.toString()?.takeIf { it.isNotBlank() }
                                    ?: item["file_url"]?.toString()?.takeIf { it.isNotBlank() }
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (id != null) {
                                                selectedImageIds = if (selected) {
                                                    selectedImageIds - id
                                                } else {
                                                    selectedImageIds + id
                                                }
                                            }
                                        },
                                ) {
                                    Column(
                                        modifier = Modifier.padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        if (!thumbnailUrl.isNullOrBlank()) {
                                            ImageTile(
                                                model = thumbnailUrl,
                                                contentDescription = title,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(96.dp),
                                                contentScale = ContentScale.Crop,
                                            )
                                        }
                                        Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text("ID ${id ?: 0}", style = MaterialTheme.typography.bodySmall)
                                        if (selected) {
                                            Text("Selected", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Card(
                modifier = if (isCompactLayout) {
                    Modifier.width(300.dp).fillMaxHeight()
                } else {
                    Modifier.weight(0.30f).fillMaxHeight()
                },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Operations", style = MaterialTheme.typography.titleMedium)
                    Box {
                        Button(onClick = { actionMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(actionOptions.firstOrNull { it.first == action }?.second ?: action)
                        }
                        DropdownMenu(expanded = actionMenuExpanded, onDismissRequest = { actionMenuExpanded = false }) {
                            actionOptions.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        action = value
                                        actionMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    if (needsTargetFolder) {
                        Box {
                            Button(onClick = { targetFolderMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    if (targetFolder.isBlank()) {
                                        "Select Target Folder"
                                    } else {
                                        "Target: ${folderLabelFromUri(targetFolder)}"
                                    },
                                )
                            }
                            DropdownMenu(expanded = targetFolderMenuExpanded, onDismissRequest = { targetFolderMenuExpanded = false }) {
                                folderRows.forEach { folder ->
                                    val folderUri = folder["folder_uri"]?.toString().orEmpty()
                                    DropdownMenuItem(
                                        text = { Text(folderLabelFromUri(folderUri)) },
                                        onClick = {
                                            targetFolderUri = folderUri
                                            targetFolderMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }

                    if (needsImageName) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Image new name") },
                        )
                    }
                    if (needsPattern) {
                        OutlinedTextField(
                            value = pattern,
                            onValueChange = { pattern = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Batch rename pattern") },
                        )
                    }
                    if (needsFolderName) {
                        OutlinedTextField(
                            value = folderName,
                            onValueChange = { folderName = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = {
                                Text(
                                    if (action == "rename_folder") "Folder new name" else "Folder name",
                                )
                            },
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Conflict")
                        Box {
                            Button(onClick = { conflictMenuExpanded = true }) {
                                Text(
                                    when (conflictMode) {
                                        "overwrite" -> "Overwrite"
                                        "skip" -> "Skip"
                                        else -> "Keep both"
                                    },
                                )
                            }
                            DropdownMenu(expanded = conflictMenuExpanded, onDismissRequest = { conflictMenuExpanded = false }) {
                                listOf(
                                    "rename" to "Keep both",
                                    "overwrite" to "Overwrite",
                                    "skip" to "Skip",
                                ).forEach { (mode, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            conflictMode = mode
                                            conflictMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }

                    if (validationMessage != null) {
                        Text(validationMessage, style = MaterialTheme.typography.bodySmall)
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Button(
                            onClick = { onPreview(buildPayload()) },
                            enabled = validationMessage == null && !state.fileOperationRunning,
                        ) { Text("Preview") }
                        Button(
                            onClick = { onExecute(buildPayload()) },
                            enabled = validationMessage == null && !state.fileOperationRunning,
                        ) { Text("Execute") }
                        Button(onClick = onUndo, enabled = state.fileOperationUndoAvailable && !state.fileOperationRunning) { Text("Undo") }
                        Button(onClick = onClear) { Text("Clear") }
                    }

                    LinearProgressIndicator(
                        progress = { state.fileOperationProgress.toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${(state.fileOperationProgress * 100.0).toInt()}%")

                    if (state.fileOperationPreview.isNotEmpty()) {
                        Text("Pending", style = MaterialTheme.typography.titleMedium)
                        LazyColumn(modifier = Modifier.heightIn(max = 140.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(state.fileOperationPreview) { row ->
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("#${row["index"]} ${row["action"]}")
                                        Text("id=${row["image_id"]} conflict=${row["conflict"]}")
                                    }
                                }
                            }
                        }
                    }

                    if (state.fileOperationResults.isNotEmpty()) {
                        Text("Results", style = MaterialTheme.typography.titleMedium)
                        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(state.fileOperationResults) { row ->
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("${if (row["ok"] == true) "PASS" else "FAIL"} ${row["action"]}")
                                        row.entries.take(5).forEach { (k, v) ->
                                            Text("$k=$v", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.lastActionMessage != null) {
            Text(state.lastActionMessage)
        }
        if (state.errorMessage != null) {
            Text("Error: ${state.errorMessage}")
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { onNavigate(AppDestination.LibraryBrowser) }) { Text("Library") }
            Button(onClick = { onNavigate(AppDestination.FolderBrowser) }) { Text("Folder Browser") }
            Button(onClick = { onNavigate(AppDestination.Dashboard) }) { Text("Dashboard") }
        }
    }
}

private fun Map<String, Any>.imageId(): Int? {
    val primary = this["image_id"].asIntNullable() ?: this["id"].asIntNullable()
    if (primary != null && primary > 0) {
        return primary
    }
    val metadata = this["metadata"] as? Map<*, *> ?: return null
    val nested = metadata["image_id"].asIntNullable() ?: metadata["id"].asIntNullable()
    return nested?.takeIf { it > 0 }
}

private fun reviewCandidateSummary(payloadJson: String): String {
    if (payloadJson.isBlank()) return ""
    return runCatching {
        val root = org.json.JSONObject(payloadJson)
        val subjects = root.optJSONArray("subjects") ?: return@runCatching ""
        buildList {
            for (subjectIndex in 0 until subjects.length()) {
                val subject = subjects.optJSONObject(subjectIndex) ?: continue
                val candidates = subject.optJSONArray("candidates") ?: continue
                val top = buildList {
                    for (candidateIndex in 0 until minOf(candidates.length(), 3)) {
                        val candidate = candidates.optJSONObject(candidateIndex) ?: continue
                        val name = candidate.optString("canonical_name").ifBlank { candidate.optString("character_id") }
                        val confidence = candidate.optDouble("confidence", 0.0)
                        if (name.isNotBlank()) add(name + " " + "%.0f%%".format(confidence * 100.0))
                    }
                }
                if (top.isNotEmpty()) add("Subject " + (subjectIndex + 1) + ": " + top.joinToString(", "))
            }
        }.joinToString(" • ")
    }.getOrDefault("")
}

private fun Map<String, Any>.reviewItemId(): String = this["id"]?.toString()
    ?: this["item_id"]?.toString()
    ?: this["path"]?.toString()
    ?: hashCode().toString()

private fun Map<String, Any>.folderUriValue(): String {
    val raw = this["folder_uri"]?.toString().orEmpty()
    if (raw.isNotBlank()) {
        return raw
    }
    return this["parent_uri"]?.toString().orEmpty()
}

private fun folderLabelFromUri(uri: String): String {
    return uri.displayFolderLabel("(none)")
}

private fun String.displayFolderLabel(emptyLabel: String): String = FolderUriUtils.displayName(this).ifBlank { emptyLabel }

private fun metadataRows(selected: Map<String, Any>): List<String> {
    val lines = mutableListOf<String>()
    val metadata = selected["metadata"] as? Map<*, *> ?: emptyMap<String, Any>()
    val tags = when (val nested = metadata["tags"]) {
        is List<*> -> nested.mapNotNull { it?.toString()?.trim() }.filter { it.isNotBlank() }
        is String -> nested.split('|').map { it.trim() }.filter { it.isNotBlank() }
        else -> emptyList()
    }
    lines += "Filename: ${selected["filename"]?.toString().orEmpty().ifBlank { "n/a" }}"
    lines += "Image ID: ${selected["image_id"] ?: "n/a"}"
    lines += "Width: ${metadata["width"] ?: "n/a"}"
    lines += "Height: ${metadata["height"] ?: "n/a"}"
    lines += "Resolution: ${metadata["resolution"] ?: "n/a"}"
    lines += "Aspect ratio: ${metadata["aspect_ratio"] ?: "n/a"}"
    lines += "Orientation: ${metadata["orientation"] ?: "n/a"}"
    lines += "Extension: ${selected["format"] ?: metadata["format"] ?: "n/a"}"
    lines += "File size: ${humanBytes(metadata["size_bytes"].asLongNullable() ?: 0L)}"
    lines += "Modified date: ${formatTimestamp(metadata["modified_at_ms"] ?: metadata["last_modified_ms"])}"
    lines += "Indexed date: ${formatTimestamp(metadata["date_indexed_ms"])}"
    lines += "Folder: ${displayFolderName(metadata)}"
    lines += "Tags: ${tags.joinToString(separator = ", ") { it }.ifBlank { "none" }}"
    return lines
}

private fun displayFolderName(metadata: Map<*, *>): String {
    val raw = metadata["folder_name"]?.toString()
        ?: metadata["folder_uri"]?.toString()
        ?: metadata["scan_source"]?.toString()
        ?: return "n/a"
    return raw.displayFolderLabel("n/a")
}

private fun formatTimestamp(value: Any?): String {
    val raw = value.asLongNullable() ?: return "n/a"
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        sdf.format(Date(raw))
    } catch (_: Exception) {
        "n/a"
    }
}

@Composable
private fun ImageTile(
    model: Any?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(GifDecoder.Factory()) }
            .build()
    }
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        imageLoader = imageLoader,
        modifier = modifier,
        contentScale = contentScale,
    )
}
