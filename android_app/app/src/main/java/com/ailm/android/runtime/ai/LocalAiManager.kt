package com.ailm.android.runtime.ai

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ailm.android.runtime.LocalDatabase
import com.ailm.android.workers.ModelDownloadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

class LocalAiManager(
    context: Context,
    database: LocalDatabase,
    scope: CoroutineScope,
) {
    private val runtimeScope = scope
    private val appContext = context.applicationContext
    private val repository = LocalAiRepository(database)
    private val settingsManager = LocalAiSettingsManager(repository)
    private val hardwareDetector = LocalAiHardwareDetector(appContext)
    private val backendManager = LocalAiBackendManager()
    private val providerPackageManager = RuntimeProviderPackageManager(
        context = appContext,
        repository = repository,
        backendManager = backendManager,
        modelResolver = { modelId, version ->
            if (version.isBlank()) repository.getModel(modelId) else repository.getModel(modelId, version)
        },
    )
    private val resourceManager = LocalAiResourceManager(
        hardwareProvider = { hardwareDetector.detectProfile() },
        settingsProvider = { settingsManager.getSettings() },
    )
    private val validationService = LocalAiValidationService(repository, backendManager, resourceManager)
    private val runtimeGateway = DefaultLocalAiRuntimeGateway(
        validationService = validationService,
    )
    private val modelRegistry = LocalAiModelRegistry(repository)
    private val packageInspector = ModelPackageInspector()
    private val runtimeSelector = LocalAiRuntimeSelector(
        hardwareProvider = { hardwareDetector.detectProfile() },
    )
    private val backendSelector = LocalAiBackendSelector(backendManager)
    private val resultValidator = LocalAiResultValidator()
    private val executionQueue = LocalAiExecutionQueue(
        repository = repository,
        settingsProvider = { settingsManager.getSettings() },
    )
    private val modelCache = LocalAiModelCache(repository)
    private val executionCache = LocalAiExecutionCache(appContext, modelCache)
    private val sessionCache = LocalAiSessionCache(modelCache)
    private val executionHistory = LocalAiExecutionHistory(repository)
    private val progressManager = LocalAiProgressManager(executionQueue, executionHistory)
    private val retryManager = LocalAiRetryManager { settingsManager.getSettings() }
    private val memoryManager = LocalAiMemoryManager(
        hardwareProvider = { hardwareDetector.detectProfile() },
        settingsProvider = { settingsManager.getSettings() },
        cacheBytesProvider = modelCache::totalBytes,
    )
    private val executionPlanner = LocalAiExecutionPlanner(
        modelRegistry = modelRegistry,
        hardwareProvider = { hardwareDetector.detectProfile() },
        memoryStateProvider = memoryManager::currentState,
        concurrentTaskProvider = { repository.listTasks(limit = 200).count { it.status == "running" } },
        availableBackendsProvider = backendManager::snapshotBackends,
    )
    private val modelLifetimeManager = LocalAiModelLifetimeManager(
        sessionCache = sessionCache,
        hardwareProvider = { hardwareDetector.detectProfile() },
        memoryStateProvider = memoryManager::currentState,
        scope = runtimeScope,
    )
    private val runtimeHealthMonitor = LocalAiRuntimeHealthMonitor(repository)
    private val taskDispatcher = LocalAiTaskDispatcher(
        queue = executionQueue,
        repository = repository,
        backendManager = backendManager,
        runtimeSelector = runtimeSelector,
        backendSelector = backendSelector,
        runtimeGateway = runtimeGateway,
        validationService = validationService,
        resultValidator = resultValidator,
        progressManager = progressManager,
        retryManager = retryManager,
        executionHistory = executionHistory,
        executionCache = executionCache,
        sessionCache = sessionCache,
        memoryManager = memoryManager,
        modelLifetimeManager = modelLifetimeManager,
        runtimeHealthMonitor = runtimeHealthMonitor,
        settingsProvider = { settingsManager.getSettings() },
    )
    private val executionScheduler = LocalAiExecutionScheduler(
        queue = executionQueue,
        dispatcher = taskDispatcher,
        settingsProvider = { settingsManager.getSettings() },
        scope = scope,
    )

    @Volatile
    private var initialized = false

    fun initialize() {
        if (initialized) {
            return
        }
        synchronized(this) {
            if (initialized) {
                return
            }
            recoverInterruptedLocalImports()
            bootstrapNativeBackends()
            providerPackageManager.discover()
            bootstrapAssetCapabilities()
            settingsManager.getSettings()
            executionScheduler.start()
            initialized = true
        }
    }

    fun executionChain(): Map<String, Any> {
        return mapOf(
            "entrypoint" to "StandaloneRuntime",
            "manager" to "LocalAiManager",
            "chain" to listOf(
                "StandaloneRuntime",
                "LocalAiManager",
                "LocalAiExecutionScheduler",
                "LocalAiExecutionQueue",
                "LocalAiRuntimeSelector",
                "LocalAiBackendSelector",
                "LocalAiTaskDispatcher",
                "LocalAiResultValidator",
                "LocalAiExecutionCache",
                "LocalAiRepository",
                "Caller",
            ),
            "module4_components" to mapOf(
                "ai_manager" to "LocalAiManager",
                "repository" to "LocalAiRepository",
                "schema" to "LocalAiSchema",
                "runtime_gateway" to "DefaultLocalAiRuntimeGateway",
                "backend_registry" to "LocalAiBackendManager",
                "queue" to "LocalAiExecutionQueue",
                "cache" to "LocalAiModelCache",
                "settings_manager" to "LocalAiSettingsManager",
                "hardware_detector" to "LocalAiHardwareDetector",
                "resource_manager" to "LocalAiResourceManager",
                "validation_service" to "LocalAiValidationService",
            ),
            "module5_components" to mapOf(
                "execution_scheduler" to "LocalAiExecutionScheduler",
                "task_dispatcher" to "LocalAiTaskDispatcher",
                "runtime_selector" to "LocalAiRuntimeSelector",
                "backend_selector" to "LocalAiBackendSelector",
                "execution_session" to "AiExecutionSessionRecord",
                "progress_manager" to "LocalAiProgressManager",
                "retry_manager" to "LocalAiRetryManager",
                "result_validator" to "LocalAiResultValidator",
                "execution_history" to "LocalAiExecutionHistory",
                "model_lifetime_manager" to "LocalAiModelLifetimeManager",
                "memory_manager" to "LocalAiMemoryManager",
                "execution_cache" to "LocalAiExecutionCache",
                "session_cache" to "LocalAiSessionCache",
                "runtime_health_monitor" to "LocalAiRuntimeHealthMonitor",
                "model_registry" to "LocalAiModelRegistry",
                "execution_planner" to "LocalAiExecutionPlanner",
            ),
        )
    }

    fun overview(): Map<String, Any> {
        ensureInitialized()
        val available = repository.listModels(installedOnly = false).size
        val installed = repository.listModels(installedOnly = true).size
        val tasks = repository.listTasks(limit = 500)
        val cacheBytes = repository.totalCacheBytes()
        val plugins = repository.listPlugins().size
        val capabilities = repository.listCapabilities().size
        return mapOf(
            "status" to "ok",
            "available_models" to available,
            "installed_models" to installed,
            "queue_pending" to tasks.count { it.status == "pending" },
            "queue_running" to tasks.count { it.status == "running" },
            "queue_paused" to tasks.count { it.status == "paused" },
            "queue_failed" to tasks.count { it.status == "failed" },
            "cache_bytes" to cacheBytes,
            "plugins" to plugins,
            "capabilities" to capabilities,
            "backends" to backendManager.listBackends(),
            "execution_sessions" to repository.listExecutionSessions(limit = 500).size,
            "runtime_health_samples" to repository.listRuntimeHealthSnapshots(limit = 200).size,
            "execution_chain" to executionChain(),
        )
    }

    fun detectHardwareProfile(): Map<String, Any> {
        ensureInitialized()
        val profile = hardwareDetector.detectProfile()
        repository.saveHardwareProfile(profile)
        return profile.toMap()
    }

    fun latestHardwareProfile(): Map<String, Any> {
        ensureInitialized()
        val profile = repository.latestHardwareProfile() ?: hardwareDetector.detectProfile()
        return profile.toMap()
    }

    fun getSettings(): Map<String, Any> {
        ensureInitialized()
        return settingsManager.getSettings().toMap()
    }

    fun updateSettings(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        return settingsManager.updateSettings(payload).toMap()
    }

    fun listBackends(): List<Map<String, Any>> {
        ensureInitialized()
        return backendManager.listBackends()
    }

    fun taskExecutionReadiness(
        taskTypes: List<String>,
        imageInputTasks: Set<String> = emptySet(),
    ): Map<String, Any> {
        ensureInitialized()
        val normalizedTasks = taskTypes
            .map(AiTaskTypes::normalize)
            .filter(AiTaskTypes::isExecutionTask)
            .distinct()
        val normalizedImageInputTasks = imageInputTasks.map(AiTaskTypes::normalize).toSet()
        val providers = backendManager.snapshotBackends()
            .filterIsInstance<AiRuntimeProvider>()
            .filter { it.providerState == AiRuntimeProviderState.AVAILABLE }

        val taskPlans = normalizedTasks.associateWith { taskType ->
            val candidates = modelRegistry.compatibleInstalledModels(taskType)
                .filter { model ->
                    taskType !in normalizedImageInputTasks || modelSupportsImageInput(model, taskType)
                }
            val selected = candidates.firstNotNullOfOrNull { model ->
                val runtimes = providers
                    .filter { provider ->
                        provider.supportsModel(model) &&
                            taskType in provider.queryCapabilities().supportedTasks.map(AiTaskTypes::normalize)
                    }
                    .map { it.runtimeId }
                    .distinct()
                if (runtimes.isEmpty()) null else model to runtimes
            }
            mapOf(
                "ready" to (selected != null),
                "model_id" to (selected?.first?.modelId ?: ""),
                "version" to (selected?.first?.version ?: ""),
                "runtime_candidates" to (selected?.second ?: emptyList<String>()),
            )
        }
        val missing = normalizedTasks.filter { taskPlans[it]?.get("ready") != true }
        return mapOf(
            "ready" to missing.isEmpty(),
            "tasks" to taskPlans,
            "ready_tasks" to normalizedTasks.filterNot(missing::contains),
            "missing_tasks" to missing,
        )
    }

    private fun modelSupportsImageInput(model: AiModelDescriptor, taskType: String): Boolean {
        val contracts = model.metadata["inference_contracts"] as? Map<*, *> ?: return false
        val contract = contracts[AiTaskTypes.normalize(taskType)] as? Map<*, *> ?: return false
        val inputs = contract["inputs"] as? List<*> ?: emptyList<Any>()
        if (inputs.any { raw ->
                val input = raw as? Map<*, *> ?: return@any false
                input["source"]?.toString()?.equals("image", ignoreCase = true) == true
            }
        ) {
            return true
        }
        return (contract["image_preprocessing"] as? Map<*, *>)?.get("enabled") == true
    }

    fun discoverRuntimeProviderPackages(): List<Map<String, Any>> {
        ensureInitialized()
        return providerPackageManager.discover().map(RuntimeProviderPackageResult::toMap)
    }

    fun installRuntimeProviderPackage(packagePath: String): Map<String, Any> {
        ensureInitialized()
        return providerPackageManager.install(File(packagePath)).toMap()
    }

    fun updateRuntimeProviderPackage(packagePath: String): Map<String, Any> {
        ensureInitialized()
        return providerPackageManager.update(File(packagePath)).toMap()
    }

    fun removeRuntimeProviderPackage(providerId: String): Map<String, Any> {
        ensureInitialized()
        return providerPackageManager.remove(providerId).toMap()
    }

    fun setRuntimeProviderPackageEnabled(providerId: String, enabled: Boolean): Map<String, Any> {
        ensureInitialized()
        return providerPackageManager.setEnabled(providerId, enabled).toMap()
    }

    fun runtimeProviderPackageHealth(): List<Map<String, Any>> {
        ensureInitialized()
        return providerPackageManager.health()
    }

    fun registerAvailableModel(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val descriptor = payloadToModelDescriptor(
            payload = payload,
            installed = false,
            installState = payload["install_state"]?.toString()?.ifBlank { "available" } ?: "available",
            installPath = "",
        )
        val validation = validationService.validateModelDescriptor(descriptor)
        if (!validation.valid) {
            return mapOf(
                "ok" to false,
                "status" to "incompatible",
                "message" to "Model descriptor is invalid",
                "validation" to validation.toMap(),
            )
        }

        repository.upsertModel(descriptor)
        return mapOf(
            "ok" to true,
            "model" to descriptor.toMap(),
            "validation" to validation.toMap(),
        )
    }

    fun listAvailableModels(): List<Map<String, Any>> {
        ensureInitialized()
        return repository.listModels(installedOnly = false).map { it.toMap() }
    }

    fun listInstalledModels(): List<Map<String, Any>> {
        ensureInitialized()
        return repository.listModels(installedOnly = true).map { it.toMap() }
    }

    fun importLocalModel(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val modelId = payload["model_id"]?.toString()?.trim().orEmpty()
        val version = payload["version"]?.toString()?.trim().orEmpty().ifBlank { "1.0.0" }
        val sourcePath = payload["source_path"]?.toString()?.trim().orEmpty()
        if (modelId.isBlank() || sourcePath.isBlank()) {
            return mapOf(
                "ok" to false,
                "message" to "model_id and source_path are required",
            )
        }

        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) {
            return mapOf(
                "ok" to false,
                "message" to "Model package source does not exist: $sourcePath",
            )
        }

        val installId = payload["install_id"]?.toString()?.trim().orEmpty()
            .ifBlank { UUID.randomUUID().toString() }
        val now = System.currentTimeMillis()
        val existingRun = repository.getInstallRun(installId)
        val expectedHash = payload["hash_sha256"]?.toString()?.trim().orEmpty().lowercase()
        val installAction = payload["install_action"]?.toString()?.trim().orEmpty().ifBlank { "local_import" }
        val sourceUri = payload["source_uri"]?.toString()?.trim().orEmpty().ifBlank { sourcePath }

        repository.upsertInstallRun(
            AiInstallRunRecord(
                installId = installId,
                modelId = modelId,
                version = version,
                action = installAction,
                sourceUri = sourceUri,
                expectedHash = expectedHash,
                actualHash = "",
                status = "running",
                details = payload,
                retryCount = existingRun?.retryCount ?: 0,
                createdAtMs = existingRun?.createdAtMs ?: now,
                startedAtMs = now,
                finishedAtMs = 0L,
                errorMessage = "",
            ),
        )

        val extractionDirectory = File(appContext.filesDir, "model-packages/$installId")
        val result = runCatching {
            val packageHash = if (sourceFile.isFile) sha256Hex(sourceFile) else ""
            if (expectedHash.isNotBlank() && sourceFile.isFile && !packageHash.equals(expectedHash, ignoreCase = true)) {
                val details = mapOf(
                    "source_path" to sourcePath,
                    "size_bytes" to sourceFile.length(),
                )
                repository.updateInstallRun(
                    installId = installId,
                    status = "failed",
                    actualHash = packageHash,
                    details = details,
                    errorMessage = "SHA-256 mismatch",
                    retryCount = 0,
                    startedAtMs = now,
                    finishedAtMs = System.currentTimeMillis(),
                )
                return@runCatching mapOf(
                    "ok" to false,
                    "message" to "SHA-256 mismatch",
                    "install_id" to installId,
                    "expected_hash" to expectedHash,
                    "actual_hash" to packageHash,
                )
            }

            val inspection = packageInspector.inspect(sourceFile, extractionDirectory, modelIdHint = modelId)
            if (!inspection.valid) {
                val issueSummary = inspection.issues.joinToString("; ") { "${it.code}: ${it.message}" }
                val errorMessage = "Model package inspection failed: $issueSummary"
                repository.updateInstallRun(
                    installId = installId,
                    status = "failed",
                    actualHash = packageHash,
                    details = inspection.toMap(),
                    errorMessage = errorMessage,
                    retryCount = 0,
                    startedAtMs = now,
                    finishedAtMs = System.currentTimeMillis(),
                )
                return@runCatching mapOf(
                    "ok" to false,
                    "status" to "rejected",
                    "message" to "Model package cannot be executed from its distributed files",
                    "inspection" to inspection.toMap(),
                    "error" to errorMessage,
                )
            }

            val primary = inspection.artifact ?: throw IllegalStateException("No executable artifact found in package")
            val sizeBytes = primary.length()
            val artifactHash = sha256Hex(primary)
            val computedHash = artifactHash
            val inspectedMetadata = payload["metadata"].toStringMap() + inspection.metadata + mapOf(
                "package_source_path" to sourcePath,
                "package_hash_sha256" to packageHash,
                "artifact_hash_sha256" to artifactHash,
            )

            // Record artifact path and dependencies so runtimes can load auxiliary files.
            val artifactFiles = listOf(primary.absolutePath)
            val dependencyPaths = emptyList<String>()

            val descriptor = payloadToModelDescriptor(
                payload = payload + mapOf(
                    "version" to version,
                    "size_bytes" to sizeBytes,
                    "hash_sha256" to computedHash,
                    "source_uri" to sourceUri,
                    "source_path" to primary.absolutePath,
                    "required_runtime" to inspection.runtime,
                    "supported_runtimes" to listOf(inspection.runtime),
                    "supported_tasks" to inspection.supportedTasks,
                    "metadata" to inspectedMetadata + mapOf("artifact_files" to artifactFiles),
                    "dependencies" to dependencyPaths,
                ),
                installed = true,
                installState = "installed",
                installPath = primary.absolutePath,
            )

            val validation = validationService.validateModelDescriptor(descriptor)
            val executionReadinessBlocked =
                inspection.metadata["execution_readiness"].toStringMap()["ready"] == false
            val blockingValidationIssues = validation.issues.filter { issue ->
                issue.severity == "error" &&
                    !(executionReadinessBlocked && issue.code == "inference_contract_invalid")
            }
            if (blockingValidationIssues.isNotEmpty()) {
                val reason = blockingValidationIssues.joinToString(" | ") { issue ->
                    "descriptor_validation_failed:${issue.code}:${issue.message}"
                }
                repository.updateInstallRun(
                    installId = installId,
                    status = "failed",
                    actualHash = computedHash,
                    details = validation.toMap(),
                    errorMessage = reason.ifBlank { "Model descriptor validation failed" },
                    retryCount = 0,
                    startedAtMs = now,
                    finishedAtMs = System.currentTimeMillis(),
                )
                return@runCatching mapOf(
                    "ok" to false,
                    "status" to "rejected",
                    "message" to reason.ifBlank { "Model package validation failed" },
                    "validation" to validation.toMap(),
                )
            }

            repository.upsertModel(descriptor)
            registerModelCapabilities(descriptor, inspection)
            repository.updateInstallRun(
                installId = installId,
                status = "succeeded",
                actualHash = computedHash,
                details = mapOf(
                    "source_path" to sourcePath,
                    "artifact_path" to primary.absolutePath,
                    "size_bytes" to sizeBytes,
                    "package" to inspection.toMap(),
                    "imported_at_ms" to System.currentTimeMillis(),
                ),
                errorMessage = "",
                retryCount = 0,
                startedAtMs = now,
                finishedAtMs = System.currentTimeMillis(),
            )

            // Local import is storage + registration only. Do not enqueue model work here:
            // execution/benchmarking must happen only after explicit readiness verification/activation.
            mapOf(
                "ok" to true,
                "install_id" to installId,
                "model" to descriptor.toMap(),
            )
        }.getOrElse { error ->
            repository.updateInstallRun(
                installId = installId,
                status = "failed",
                actualHash = "",
                details = mapOf("source_path" to sourcePath),
                errorMessage = error.message ?: error.javaClass.simpleName,
                retryCount = 0,
                startedAtMs = now,
                finishedAtMs = System.currentTimeMillis(),
            )
            mapOf(
                "ok" to false,
                "install_id" to installId,
                "message" to (error.message ?: error.javaClass.simpleName),
            )
        }

        if (result["ok"] != true) {
            extractionDirectory.deleteRecursively()
        }
        return result
    }

    fun registerModelDownload(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val modelId = payload["model_id"]?.toString()?.trim().orEmpty()
        val version = payload["version"]?.toString()?.trim().orEmpty().ifBlank { "1.0.0" }
        val sourceUri = payload["source_uri"]?.toString()?.trim().orEmpty()
        val expectedHash = payload["hash_sha256"]?.toString()?.trim().orEmpty().lowercase()
        if (modelId.isBlank()) {
            return mapOf("ok" to false, "message" to "model_id is required")
        }
        if (Uri.parse(sourceUri).scheme?.lowercase() != "https") {
            return mapOf("ok" to false, "message" to "source_uri must be a direct HTTPS model package URL")
        }
        if (!expectedHash.matches(Regex("[0-9a-f]{64}"))) {
            return mapOf("ok" to false, "message" to "hash_sha256 must be the expected 64-character SHA-256 for the package")
        }

        val descriptor = payloadToModelDescriptor(
            payload = payload + mapOf("version" to version),
            installed = false,
            installState = "queued",
            installPath = "",
        )
        val validation = validationService.validateModelDescriptor(descriptor)
        if (!validation.valid) {
            return mapOf(
                "ok" to false,
                "status" to "incompatible",
                "message" to "Model descriptor validation failed",
                "validation" to validation.toMap(),
            )
        }
        repository.upsertModel(descriptor)

        val installId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        repository.upsertInstallRun(
            AiInstallRunRecord(
                installId = installId,
                modelId = modelId,
                version = version,
                action = "download",
                sourceUri = sourceUri,
                expectedHash = expectedHash,
                actualHash = "",
                status = "registered",
                details = payload,
                retryCount = 0,
                createdAtMs = now,
                startedAtMs = 0L,
                finishedAtMs = 0L,
                errorMessage = "",
            ),
        )
        enqueueModelDownload(installId)

        return mapOf(
            "ok" to true,
            "install_id" to installId,
            "status" to "queued",
            "model" to descriptor.toMap(),
        )
    }

    private fun enqueueModelDownload(installId: String) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInputData(workDataOf(ModelDownloadWorker.INSTALL_ID_KEY to installId))
            .addTag("ailm_model_download")
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            "ailm.model.download.$installId",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun downloadPackage(run: AiInstallRunRecord): File {
        val sourceUri = Uri.parse(run.sourceUri)
        require(sourceUri.scheme?.lowercase() == "https") { "Model downloads require an HTTPS source URI" }
        val extension = sourceUri.lastPathSegment
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z0-9]{1,16}")) }
            ?: throw IllegalArgumentException("Model download URL must identify a package file extension")
        val downloadsDirectory = File(appContext.filesDir, "model-downloads").apply { mkdirs() }
        val target = File(downloadsDirectory, "${run.installId}.$extension")
        val temporary = File(downloadsDirectory, "${run.installId}.$extension.part")
        temporary.delete()

        val connection = (URL(run.sourceUri).openConnection() as? HttpURLConnection)
            ?: throw IOException("Model download URL is not an HTTP connection")
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.requestMethod = "GET"
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("Model download failed with HTTP $responseCode")
            }
            connection.inputStream.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            target.delete()
            if (!temporary.renameTo(target)) {
                throw IOException("Could not finalize downloaded model package")
            }
            return target
        } finally {
            temporary.delete()
            connection.disconnect()
        }
    }

    fun downloadRegisteredModel(installId: String): Map<String, Any> {
        ensureInitialized()
        val run = repository.getInstallRun(installId)
            ?: return mapOf("ok" to false, "message" to "Model download run was not found", "retryable" to false)
        if (run.action != "download") {
            return mapOf("ok" to false, "message" to "Install run is not a model download", "retryable" to false)
        }

        return runCatching {
            val downloaded = downloadPackage(run)
            importLocalModel(
                run.details + mapOf(
                    "install_id" to run.installId,
                    "install_action" to "download",
                    "source_uri" to run.sourceUri,
                    "source_path" to downloaded.absolutePath,
                    "hash_sha256" to run.expectedHash,
                ),
            )
        }.getOrElse { error ->
            repository.updateInstallRun(
                installId = run.installId,
                status = "failed",
                actualHash = "",
                details = run.details + mapOf("source_uri" to run.sourceUri),
                errorMessage = error.message ?: error.javaClass.simpleName,
                retryCount = run.retryCount + 1,
                startedAtMs = System.currentTimeMillis(),
                finishedAtMs = System.currentTimeMillis(),
            )
            mapOf(
                "ok" to false,
                "message" to (error.message ?: error.javaClass.simpleName),
                "retryable" to (error is IOException),
            )
        }
    }

    fun removeModel(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val modelId = payload["model_id"]?.toString()?.trim().orEmpty()
        if (modelId.isBlank()) {
            return mapOf("ok" to false, "message" to "model_id is required")
        }
        val version = payload["version"]?.toString()?.trim().orEmpty()
        val deleteFile = payload["delete_file"].toBooleanValue(defaultValue = false)

        val target = if (version.isBlank()) {
            repository.listModels(installedOnly = true)
                .filter { it.modelId == modelId }
                .maxWithOrNull(compareBy<AiModelDescriptor> { it.version })
        } else {
            repository.getModel(modelId, version)
        }

        if (target == null) {
            return mapOf("ok" to false, "message" to "Model not found")
        }

        if (deleteFile && target.installPath.isNotBlank()) {
            runCatching {
                val file = File(target.installPath)
                if (file.exists() && file.isFile) {
                    file.delete()
                }
            }.onFailure {
                Log.w(TAG, "Failed to delete model file: ${target.installPath}", it)
            }
        }

        repository.setModelInstallState(
            modelId = target.modelId,
            version = target.version,
            installed = false,
            installState = "removed",
            installPath = "",
        )

        val cacheEntries = modelCache.listEntries(limit = 500).filter { it.modelId == target.modelId }
        cacheEntries.forEach { modelCacheEntry ->
            repository.removeCacheEntry(modelCacheEntry.cacheKey)
        }

        return mapOf(
            "ok" to true,
            "model_id" to target.modelId,
            "version" to target.version,
            "cache_entries_removed" to cacheEntries.size,
            "file_deleted" to deleteFile,
        )
    }

    fun verifyInstalledModel(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val modelId = payload["model_id"]?.toString()?.trim().orEmpty()
        val version = payload["version"]?.toString()?.trim().orEmpty()
        if (modelId.isBlank() || version.isBlank()) {
            return mapOf(
                "ok" to false,
                "message" to "model_id and version are required",
            )
        }

        val report = validationService.validateInstalledModel(modelId, version)
        val status = if (report.valid) "succeeded" else "failed"
        val failureMessage = report.issues
            .filter { it.severity == "error" }
            .joinToString(" | ") { issue -> "${issue.code}: ${issue.message}" }
            .ifBlank { if (report.valid) "" else "Verification failed" }
        val now = System.currentTimeMillis()
        val installId = UUID.randomUUID().toString()
        repository.upsertInstallRun(
            AiInstallRunRecord(
                installId = installId,
                modelId = modelId,
                version = version,
                action = "verify",
                sourceUri = "",
                expectedHash = "",
                actualHash = "",
                status = status,
                details = report.toMap(),
                retryCount = 0,
                createdAtMs = now,
                startedAtMs = now,
                finishedAtMs = now,
                errorMessage = failureMessage,
            ),
        )

        return mapOf(
            "ok" to report.valid,
            "status" to status,
            "install_id" to installId,
            "message" to failureMessage,
            "validation" to report.toMap(),
        )
    }

    fun activateInstalledModel(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val modelId = payload["model_id"]?.toString()?.trim().orEmpty()
        val version = payload["version"]?.toString()?.trim().orEmpty()
        val taskType = AiTaskTypes.normalize(payload["task_type"]?.toString()?.trim().orEmpty())
        if (modelId.isBlank() || version.isBlank()) {
            return mapOf("ok" to false, "status" to "invalid", "message" to "model_id and version are required")
        }

        val model = repository.getModel(modelId, version)
            ?: return mapOf("ok" to false, "status" to "not_found", "message" to "Model $modelId@$version is not registered")

        val integrity = validationService.validateInstalledModel(modelId, version)
        if (!integrity.valid) {
            return mapOf(
                "ok" to false,
                "status" to "verification_failed",
                "message" to "Model must pass installed-file verification before activation",
                "validation" to integrity.toMap(),
            )
        }

        val readiness = model.metadata["execution_readiness"].toStringMap()
        if (readiness["ready"] == false) {
            return mapOf(
                "ok" to false,
                "status" to "not_ready",
                "message" to "Model is imported but not execution-ready",
                "readiness" to readiness,
            )
        }

        if (taskType.isNotBlank() && taskType !in model.supportedTasks.map(AiTaskTypes::normalize).toSet()) {
            return mapOf(
                "ok" to false,
                "status" to "task_unsupported",
                "message" to "Model $modelId@$version does not support task '$taskType'",
            )
        }

        val descriptorValidation = validationService.validateModelDescriptor(model)
        val blockingDescriptorIssues = descriptorValidation.issues.filter { it.severity == "error" }
        if (blockingDescriptorIssues.isNotEmpty()) {
            return mapOf(
                "ok" to false,
                "status" to "incompatible",
                "message" to "Model descriptor is not execution-compatible",
                "validation" to descriptorValidation.toMap(),
            )
        }

        val suffix = if (taskType.isBlank()) "" else ".$taskType"
        val settings = settingsManager.getSettings().toMap().toMutableMap().apply {
            this["active_model_id$suffix"] = model.modelId
            this["active_model_version$suffix"] = model.version
        }

        return mapOf(
            "ok" to true,
            "status" to "selected",
            "activation_scope" to "session",
            "runtime_initialized" to false,
            "model_id" to model.modelId,
            "version" to model.version,
            "task_type" to taskType,
            "settings" to settings,
            "validation" to integrity.toMap(),
        )
    }

    fun detectModelUpdates(): List<Map<String, Any>> {
        ensureInitialized()
        val all = repository.listModels(installedOnly = null)
        val byModelId = all.groupBy { it.modelId }
        val updates = mutableListOf<Map<String, Any>>()

        byModelId.forEach { (modelId, versions) ->
            val installedVersions = versions.filter { it.installed }
            if (installedVersions.isEmpty()) {
                return@forEach
            }
            val availableVersions = versions
            if (availableVersions.isEmpty()) {
                return@forEach
            }
            val installedLatest = installedVersions.maxWithOrNull { a, b -> compareVersions(a.version, b.version) } ?: return@forEach
            val availableLatest = availableVersions.maxWithOrNull { a, b -> compareVersions(a.version, b.version) } ?: return@forEach
            if (compareVersions(availableLatest.version, installedLatest.version) > 0) {
                updates += mapOf(
                    "model_id" to modelId,
                    "installed_version" to installedLatest.version,
                    "latest_version" to availableLatest.version,
                    "installed" to installedLatest.toMap(),
                    "latest" to availableLatest.toMap(),
                )
            }
        }

        return updates.sortedBy { it["model_id"]?.toString() }
    }

    fun enqueueTask(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val taskType = AiTaskTypes.normalize(payload["task_type"]?.toString()?.trim().orEmpty().ifBlank { "custom" })
        val requestedModelId = payload["model_id"]?.toString()?.trim().orEmpty()
        val requestedVersion = payload["version"]?.toString()?.trim().orEmpty()
        val executionPlan = if (AiTaskTypes.isExecutionTask(taskType)) {
            executionPlanner.plan(taskType, requestedModelId, requestedVersion)
        } else {
            null
        }
        if (AiTaskTypes.isExecutionTask(taskType) && executionPlan?.model == null) {
            return mapOf(
                "ok" to false,
                "status" to "incompatible",
                "task_type" to taskType,
                "message" to "No installed ONNX or TensorFlow Lite model is compatible with '$taskType'",
            )
        }
        val modelId = executionPlan?.model?.modelId ?: requestedModelId
        val version = executionPlan?.model?.version ?: requestedVersion
        val runtimeHint = executionPlan?.runtimeCandidates?.firstOrNull().orEmpty()
        val priority = (payload["priority"] as? Number)?.toInt() ?: 0
        val maxRetries = (payload["max_retries"] as? Number)?.toInt() ?: settingsManager.getSettings().maxQueueRetries
        val timeoutMs = (payload["timeout_ms"] as? Number)?.toLong() ?: settingsManager.getSettings().defaultTaskTimeoutMs
        val dependencyTaskIds = ((payload["dependency_task_ids"] as? List<*>)
            ?: (payload["depends_on"] as? List<*>))
            ?.mapNotNull { it?.toString()?.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val taskPayload = ((payload["payload"] as? Map<*, *>)?.toStringKeyMap() ?: payload) +
            (executionPlan?.let { mapOf("execution_plan" to it.toMap()) } ?: emptyMap())

        val task = executionScheduler.enqueue(
            taskType = taskType,
            modelId = modelId,
            version = version,
            runtimeHint = runtimeHint,
            priority = priority,
            maxRetries = maxRetries,
            timeoutMs = timeoutMs,
            dependencyTaskIds = dependencyTaskIds,
            payload = taskPayload,
        )
        return task.toMap()
    }

    fun semanticSearch(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val rawCandidates = (payload["candidates"] as? List<*>)
            ?.mapNotNull { (it as? Map<*, *>)?.toStringKeyMap() }
            ?.filter { candidate -> candidate["image_id"]?.toIntOrNullValue() != null }
            ?: emptyList()
        if (rawCandidates.isEmpty()) {
            return mapOf(
                "ok" to true,
                "status" to "succeeded",
                "matches" to emptyList<Map<String, Any>>(),
                "result" to mapOf("matches" to emptyList<Map<String, Any>>()),
                "message" to "No candidates were provided for semantic ranking",
            )
        }

        val topKDefault = minOf(200, rawCandidates.size)
        val topK = payload["top_k"].toIntValue(defaultValue = topKDefault)
            .coerceIn(1, rawCandidates.size)
        val timeoutMs = payload["timeout_ms"].toLongValue(defaultValue = DEFAULT_SEMANTIC_TIMEOUT_MS)
            .coerceIn(1_000L, 60_000L)

        val plan = executionPlanner.plan("similarity_search")
        val model = plan.model ?: return mapOf(
            "ok" to false,
            "status" to "incompatible",
            "matches" to emptyList<Map<String, Any>>(),
            "result" to mapOf("matches" to emptyList<Map<String, Any>>()),
            "message" to "No installed ONNX or TensorFlow Lite model is compatible with similarity_search",
        )
        val taskPayload = linkedMapOf<String, Any>(
            "query" to payload["query"]?.toString().orEmpty(),
            "candidates" to rawCandidates,
            "top_k" to topK,
        )
        val queryEmbedding = (payload["query_embedding"] as? List<*>)
            ?.mapNotNull { it.toDoubleOrNullValue() }
            ?: emptyList()
        if (queryEmbedding.isNotEmpty()) {
            taskPayload["query_embedding"] = queryEmbedding
        }

        val task = executionScheduler.enqueue(
            taskType = "similarity_search",
            modelId = model.modelId,
            version = model.version,
            runtimeHint = plan.runtimeCandidates.firstOrNull().orEmpty().ifBlank { model.requiredRuntime },
            priority = 20,
            maxRetries = 0,
            timeoutMs = timeoutMs,
            payload = taskPayload,
        )

        val completedTask = awaitTaskTerminalState(task.taskId, timeoutMs + TASK_SETTLE_WINDOW_MS)
            ?: return mapOf(
                "ok" to false,
                "status" to "timeout",
                "task_id" to task.taskId,
                "matches" to emptyList<Map<String, Any>>(),
                "result" to mapOf("matches" to emptyList<Map<String, Any>>()),
                "message" to "Semantic search task timed out while waiting for completion",
            )

        val matches = extractSemanticMatches(completedTask.result).take(topK)
        val ok = completedTask.status == "succeeded"
        val fallbackMessage = if (ok) {
            "Semantic search completed"
        } else {
            completedTask.errorMessage.ifBlank { "Semantic search failed" }
        }
        val message = completedTask.result["message"]?.toString().orEmpty().ifBlank { fallbackMessage }

        return mapOf(
            "ok" to ok,
            "status" to completedTask.status,
            "task_id" to completedTask.taskId,
            "matches" to matches,
            "result" to mapOf("matches" to matches),
            "message" to message,
        )
    }

    fun runPipeline(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        if (payload["items"] is List<*>) {
            return runBatchPipeline(payload)
        }

        val normalizedTaskType = AiTaskTypes.normalize(
            payload["task_type"]?.toString()
                ?: payload["pipeline_type"]?.toString()
                ?: "",
        )
        if (normalizedTaskType.isBlank()) {
            return mapOf("ok" to false, "status" to "invalid", "message" to "task_type is required")
        }
        if (!AiTaskTypes.isExecutionTask(normalizedTaskType)) {
            return mapOf(
                "ok" to false,
                "status" to "unsupported",
                "message" to "task_type '$normalizedTaskType' is not supported by execution pipeline",
            )
        }

        val stages = resolvePipelineStages(normalizedTaskType, payload)
        if (stages.isEmpty()) {
            return mapOf(
                "ok" to false,
                "status" to "invalid",
                "message" to "No executable stages resolved for task_type '$normalizedTaskType'",
            )
        }

        val requestedModelId = payload["model_id"]?.toString()?.trim().orEmpty()
        val requestedVersion = payload["version"]?.toString()?.trim().orEmpty()
        val priority = payload["priority"].toIntValue(defaultValue = DEFAULT_PIPELINE_PRIORITY)
        val maxRetries = payload["max_retries"].toIntValue(defaultValue = 1).coerceAtLeast(0)
        val timeoutMs = payload["timeout_ms"].toLongValue(defaultValue = DEFAULT_PIPELINE_STAGE_TIMEOUT_MS)
            .coerceIn(1_000L, 180_000L)
        val pipelineId = payload["pipeline_id"]?.toString()?.trim().orEmpty().ifBlank { UUID.randomUUID().toString() }

        return executePipelineStages(
            pipelineId = pipelineId,
            pipelineType = normalizedTaskType,
            payload = payload,
            stages = stages,
            requestedModelId = requestedModelId,
            requestedVersion = requestedVersion,
            priority = priority,
            maxRetries = maxRetries,
            timeoutMs = timeoutMs,
        )
    }

    fun runBatchPipeline(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val rawItems = (payload["items"] as? List<*>)
            ?.mapNotNull { (it as? Map<*, *>)?.toStringKeyMap() }
            ?: emptyList()
        if (rawItems.isEmpty()) {
            return mapOf("ok" to false, "status" to "invalid", "message" to "items are required for batch execution")
        }
        if (rawItems.size > MAX_BATCH_ITEMS) {
            return mapOf(
                "ok" to false,
                "status" to "invalid",
                "message" to "batch size ${rawItems.size} exceeds max $MAX_BATCH_ITEMS",
            )
        }

        val shared = payload.toMutableMap().apply { remove("items") }
        val results = mutableListOf<Map<String, Any>>()
        rawItems.forEachIndexed { index, item ->
            val merged = linkedMapOf<String, Any>()
            merged.putAll(shared)
            merged.putAll(item)
            merged.remove("items")
            if (merged["task_type"] == null && merged["pipeline_type"] == null) {
                val fallbackTaskType = payload["task_type"]?.toString()?.trim().orEmpty()
                    .ifBlank { payload["pipeline_type"]?.toString()?.trim().orEmpty() }
                if (fallbackTaskType.isNotBlank()) {
                    merged["task_type"] = fallbackTaskType
                }
            }

            val itemResult = runPipeline(merged)
            results += mapOf(
                "index" to index,
                "ok" to (itemResult["ok"] as? Boolean ?: false),
                "result" to itemResult,
            )
        }

        val succeeded = results.count { it["ok"] == true }
        val failed = results.size - succeeded
        return mapOf(
            "ok" to (failed == 0),
            "status" to if (failed == 0) "succeeded" else "partial",
            "batch_size" to results.size,
            "succeeded" to succeeded,
            "failed" to failed,
            "items" to results,
        )
    }

    fun executeKnowledgePack(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val merged = linkedMapOf<String, Any>()
        merged.putAll(payload)
        merged["task_type"] = "knowledge_pack_execution"
        return runPipeline(merged)
    }

    fun runMultiStagePipeline(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val stages = (payload["stages"] as? List<*>)
            ?.mapNotNull { it?.toString()?.trim() }
            ?.filter { it.isNotBlank() }
            ?.map(AiTaskTypes::normalize)
            ?.filter(AiTaskTypes::isExecutionTask)
            ?.distinct()
            ?: emptyList()
        if (stages.isEmpty()) {
            return mapOf("ok" to false, "status" to "invalid", "message" to "stages are required for multi-stage pipeline")
        }

        val pipelineType = payload["pipeline_type"]?.toString()?.trim().orEmpty()
            .ifBlank { payload["task_type"]?.toString()?.trim().orEmpty() }
            .ifBlank { "multi_stage" }
        val requestedModelId = payload["model_id"]?.toString()?.trim().orEmpty()
        val requestedVersion = payload["version"]?.toString()?.trim().orEmpty()
        val priority = payload["priority"].toIntValue(defaultValue = DEFAULT_PIPELINE_PRIORITY)
        val maxRetries = payload["max_retries"].toIntValue(defaultValue = 1).coerceAtLeast(0)
        val timeoutMs = payload["timeout_ms"].toLongValue(defaultValue = DEFAULT_PIPELINE_STAGE_TIMEOUT_MS)
            .coerceIn(1_000L, 180_000L)
        val pipelineId = payload["pipeline_id"]?.toString()?.trim().orEmpty().ifBlank { UUID.randomUUID().toString() }

        return executePipelineStages(
            pipelineId = pipelineId,
            pipelineType = pipelineType,
            payload = payload,
            stages = stages,
            requestedModelId = requestedModelId,
            requestedVersion = requestedVersion,
            priority = priority,
            maxRetries = maxRetries,
            timeoutMs = timeoutMs,
        )
    }

    fun cancelTask(taskId: String): Boolean {
        ensureInitialized()
        return executionScheduler.cancelTask(taskId)
    }

    fun pauseTask(taskId: String): Boolean {
        ensureInitialized()
        return executionScheduler.pauseTask(taskId)
    }

    fun resumeTask(taskId: String): Boolean {
        ensureInitialized()
        return executionScheduler.resumeTask(taskId)
    }

    fun retryTask(taskId: String): Boolean {
        ensureInitialized()
        return executionScheduler.retryTask(taskId)
    }

    fun listTasks(limit: Int = 200): List<Map<String, Any>> {
        ensureInitialized()
        return executionScheduler.listTasks(limit).map { it.toMap() }
    }

    fun resumeQueue() {
        ensureInitialized()
        executionScheduler.resume()
    }

    fun pauseQueue() {
        ensureInitialized()
        executionScheduler.pause()
    }

    fun listInstallRuns(limit: Int = 100): List<Map<String, Any>> {
        ensureInitialized()
        return repository.listInstallRuns(limit).map { it.toMap() }
    }

    fun listExecutionSessions(limit: Int = 200): List<Map<String, Any>> {
        ensureInitialized()
        return executionHistory.listSessions(limit).map { it.toMap() }
    }

    fun listExecutionEvents(sessionId: String, limit: Int = 500): List<Map<String, Any>> {
        ensureInitialized()
        return executionHistory.listEvents(sessionId, limit).map { it.toMap() }
    }

    fun listRuntimeHealthSnapshots(limit: Int = 200): List<Map<String, Any>> {
        ensureInitialized()
        return runtimeHealthMonitor.listSnapshots(limit).map { it.toMap() }
    }

    fun registerPlugin(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val now = System.currentTimeMillis()
        val pluginId = payload["plugin_id"]?.toString()?.trim().orEmpty()
        if (pluginId.isBlank()) {
            return mapOf("ok" to false, "message" to "plugin_id is required")
        }
        val plugin = AiPluginDescriptor(
            pluginId = pluginId,
            version = payload["version"]?.toString()?.trim().orEmpty().ifBlank { "1.0.0" },
            displayName = payload["display_name"]?.toString()?.trim().orEmpty().ifBlank { pluginId },
            enabled = payload["enabled"].toBooleanValue(defaultValue = true),
            capabilities = (payload["capabilities"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
            metadata = payload["metadata"].toStringMap(),
            registeredAtMs = (payload["registered_at_ms"] as? Number)?.toLong() ?: now,
            updatedAtMs = now,
        )
        repository.upsertPlugin(plugin)
        return mapOf("ok" to true, "plugin" to plugin.toMap())
    }

    fun listPlugins(enabledOnly: Boolean? = null): List<Map<String, Any>> {
        ensureInitialized()
        return repository.listPlugins(enabledOnly).map { it.toMap() }
    }

    fun registerCapability(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val now = System.currentTimeMillis()
        val capabilityId = payload["capability_id"]?.toString()?.trim().orEmpty()
        if (capabilityId.isBlank()) {
            return mapOf("ok" to false, "message" to "capability_id is required")
        }
        val capability = AiCapabilityDescriptor(
            capabilityId = capabilityId,
            providerId = payload["provider_id"]?.toString()?.trim().orEmpty().ifBlank { "local_ai_manager" },
            capabilityType = payload["capability_type"]?.toString()?.trim().orEmpty().ifBlank { "generic" },
            status = payload["status"]?.toString()?.trim().orEmpty().ifBlank { "available" },
            metadata = payload["metadata"].toStringMap(),
            registeredAtMs = (payload["registered_at_ms"] as? Number)?.toLong() ?: now,
            updatedAtMs = now,
        )
        repository.upsertCapability(capability)
        return mapOf("ok" to true, "capability" to capability.toMap())
    }

    fun listCapabilities(providerId: String = ""): List<Map<String, Any>> {
        ensureInitialized()
        return repository.listCapabilities(providerId.trim()).map { it.toMap() }
    }

    fun listCacheEntries(limit: Int = 200): List<Map<String, Any>> {
        ensureInitialized()
        return modelCache.listEntries(limit).map { it.toMap() }
    }

    fun upsertCacheEntry(payload: Map<String, Any>): Map<String, Any> {
        ensureInitialized()
        val modelId = payload["model_id"]?.toString()?.trim().orEmpty()
        val cacheKey = payload["cache_key"]?.toString()?.trim().orEmpty()
        val artifactPath = payload["artifact_path"]?.toString()?.trim().orEmpty()
        val sizeBytes = (payload["size_bytes"] as? Number)?.toLong() ?: File(artifactPath).length()
        if (modelId.isBlank() || cacheKey.isBlank() || artifactPath.isBlank()) {
            return mapOf("ok" to false, "message" to "model_id, cache_key, and artifact_path are required")
        }

        modelCache.upsertEntry(
            modelId = modelId,
            cacheKey = cacheKey,
            artifactPath = artifactPath,
            sizeBytes = sizeBytes,
            pinned = payload["pinned"].toBooleanValue(defaultValue = false),
            metadata = payload["metadata"].toStringMap(),
        )
        return mapOf("ok" to true, "cache_key" to cacheKey)
    }

    fun pruneCache(): Map<String, Any> {
        ensureInitialized()
        val budget = resourceManager.computeCacheBudgetBytes()
        return modelCache.pruneToBudget(budget)
    }

    fun validateInfrastructure(): Map<String, Any> {
        ensureInitialized()
        val infrastructure = validationService.validateInfrastructureSnapshot()
        val installedReports = repository.listModels(installedOnly = true).map {
            val report = validationService.validateInstalledModel(it.modelId, it.version)
            mapOf(
                "model_id" to it.modelId,
                "version" to it.version,
                "valid" to report.valid,
                "issues" to report.issues.map { issue ->
                    mapOf(
                        "code" to issue.code,
                        "message" to issue.message,
                        "severity" to issue.severity,
                    )
                },
            )
        }
        val sessions = repository.listExecutionSessions(limit = 200)
        val runtimeHealth = repository.listRuntimeHealthSnapshots(limit = 200)

        return mapOf(
            "ok" to (infrastructure.valid && installedReports.none { (it["valid"] as? Boolean) == false }),
            "infrastructure" to infrastructure.toMap(),
            "models" to installedReports,
            "execution_history" to mapOf(
                "total_sessions" to sessions.size,
                "failed_sessions" to sessions.count { it.status == "failed" },
                "paused_sessions" to sessions.count { it.status == "paused" },
                "retry_scheduled_sessions" to sessions.count { it.status == "retry_scheduled" },
            ),
            "runtime_health" to mapOf(
                "samples" to runtimeHealth.size,
                "unhealthy_samples" to runtimeHealth.count { !it.healthy },
            ),
            "supported_execution_task_types" to AiTaskTypes.EXECUTION_TASKS.sorted(),
        )
    }

    private fun bootstrapAssetCapabilities() {
        runCatching {
            val modelCapabilities = loadAssetJson("model_manager_capabilities.json")
            if (modelCapabilities != null) {
                val supports = modelCapabilities.optJSONArray("supports")
                if (supports != null) {
                    for (i in 0 until supports.length()) {
                        val capability = supports.optString(i, "").trim()
                        if (capability.isBlank()) {
                            continue
                        }
                        repository.upsertCapability(
                            AiCapabilityDescriptor(
                                capabilityId = "asset.support.$capability",
                                providerId = "asset:model_manager_capabilities",
                                capabilityType = "model_support",
                                status = "available",
                                metadata = mapOf("value" to capability),
                                registeredAtMs = System.currentTimeMillis(),
                                updatedAtMs = System.currentTimeMillis(),
                            ),
                        )
                    }
                }

                val operations = modelCapabilities.optJSONArray("operations")
                if (operations != null) {
                    for (i in 0 until operations.length()) {
                        val operation = operations.optString(i, "").trim()
                        if (operation.isBlank()) {
                            continue
                        }
                        repository.upsertCapability(
                            AiCapabilityDescriptor(
                                capabilityId = "asset.operation.$operation",
                                providerId = "asset:model_manager_capabilities",
                                capabilityType = "model_operation",
                                status = "available",
                                metadata = mapOf("value" to operation),
                                registeredAtMs = System.currentTimeMillis(),
                                updatedAtMs = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            }

            val storageSupport = loadAssetJson("storage_support.json")
            if (storageSupport != null) {
                val providers = storageSupport.optJSONArray("android_storage")
                val implemented = storageSupport.optJSONArray("implemented_storage")
                    ?.let { values ->
                        buildSet {
                            for (i in 0 until values.length()) {
                                values.optString(i, "").trim().takeIf(String::isNotBlank)?.let(::add)
                            }
                        }
                    }
                    ?: emptySet()
                val integration = storageSupport.optString("integration", "adapter_only")
                    .trim()
                    .ifBlank { "adapter_only" }
                if (providers != null) {
                    for (i in 0 until providers.length()) {
                        val provider = providers.optString(i, "").trim()
                        if (provider.isBlank()) {
                            continue
                        }
                        repository.upsertCapability(
                            AiCapabilityDescriptor(
                                capabilityId = "asset.storage.$provider",
                                providerId = "asset:storage_support",
                                capabilityType = "storage_provider",
                                status = if (provider in implemented) "available" else integration,
                                metadata = mapOf(
                                    "value" to provider,
                                    "integration" to integration,
                                    "implemented" to (provider in implemented),
                                ),
                                registeredAtMs = System.currentTimeMillis(),
                                updatedAtMs = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            }
        }.onFailure {
            Log.w(TAG, "Failed to bootstrap AI capabilities from assets", it)
        }
    }

    private fun bootstrapNativeBackends() {
        val resolver: (String, String) -> AiModelDescriptor? = { modelId, version ->
            if (version.isBlank()) repository.getModel(modelId) else repository.getModel(modelId, version)
        }
        listOf(
            OnnxRuntimeBackend(resolver, appContext),
            TensorFlowLiteBackend(resolver, appContext),
            LlamaCppBackend(resolver, appContext),
        ).forEach { backend ->
            if (backendManager.snapshotBackends().none { it.runtimeId.equals(backend.runtimeId, ignoreCase = true) }) {
                backendManager.registerBackend(backend)
            }
        }
    }

    private fun executePipelineStages(
        pipelineId: String,
        pipelineType: String,
        payload: Map<String, Any>,
        stages: List<String>,
        requestedModelId: String,
        requestedVersion: String,
        priority: Int,
        maxRetries: Int,
        timeoutMs: Long,
    ): Map<String, Any> {
        val stageRecords = mutableListOf<Map<String, Any>>()
        val stageOutputs = linkedMapOf<String, Map<String, Any>>()
        val taskIds = mutableListOf<String>()
        val continueOnStageError = payload["continue_on_stage_error"].toBooleanValue(defaultValue = false)
        var dependencyTaskId = ""
        var finalStageModel: AiModelDescriptor? = null
        var succeededStageCount = 0

        stages.forEachIndexed { index, stageType ->
            val stagePlan = executionPlanner.plan(stageType, requestedModelId, requestedVersion)
            val stageModel = stagePlan.model
            if (stageModel == null) {
                val missing = mapOf(
                    "stage_type" to stageType,
                    "status" to "incompatible",
                    "message" to "No installed model is compatible with '$stageType'",
                )
                stageRecords += missing
                if (continueOnStageError) {
                    dependencyTaskId = ""
                    return@forEachIndexed
                }
                return mapOf(
                    "ok" to false,
                    "status" to "incompatible",
                    "pipeline_id" to pipelineId,
                    "pipeline_type" to pipelineType,
                    "task_ids" to taskIds,
                    "stages" to stageRecords,
                    "message" to missing["message"].toString(),
                )
            }
            val stageRuntimeHint = stagePlan.runtimeCandidates.firstOrNull().orEmpty()
                .ifBlank { stageModel.requiredRuntime }
            val stagePayload = buildStagePayload(
                payload = payload,
                pipelineId = pipelineId,
                pipelineType = pipelineType,
                stageType = stageType,
                stageIndex = index + 1,
                stageTotal = stages.size,
                stageOutputs = stageOutputs,
            )
            val task = executionScheduler.enqueue(
                taskType = stageType,
                modelId = stageModel.modelId,
                version = stageModel.version,
                runtimeHint = stageRuntimeHint,
                priority = priority,
                maxRetries = maxRetries,
                timeoutMs = timeoutMs,
                dependencyTaskIds = if (dependencyTaskId.isBlank()) emptyList() else listOf(dependencyTaskId),
                payload = stagePayload,
            )
            taskIds += task.taskId
            finalStageModel = stageModel

            val completedTask = awaitTaskTerminalState(task.taskId, timeoutMs + TASK_SETTLE_WINDOW_MS)
            if (completedTask == null) {
                stageRecords += mapOf(
                    "stage_type" to stageType,
                    "task_id" to task.taskId,
                    "status" to "timeout",
                    "message" to "Stage timed out while waiting for completion",
                )
                return mapOf(
                    "ok" to false,
                    "status" to "timeout",
                    "pipeline_id" to pipelineId,
                    "pipeline_type" to pipelineType,
                    "task_ids" to taskIds,
                    "stages" to stageRecords,
                )
            }

            stageRecords += mapOf(
                "stage_type" to stageType,
                "task_id" to completedTask.taskId,
                "model_id" to stageModel.modelId,
                "model_version" to stageModel.version,
                "runtime_hint" to stageRuntimeHint,
                "status" to completedTask.status,
                "message" to completedTask.result["message"]?.toString().orEmpty().ifBlank {
                    completedTask.errorMessage.ifBlank { completedTask.status }
                },
            )

            if (completedTask.status != "succeeded") {
                if (continueOnStageError) {
                    dependencyTaskId = ""
                    return@forEachIndexed
                }
                return mapOf(
                    "ok" to false,
                    "status" to completedTask.status,
                    "pipeline_id" to pipelineId,
                    "pipeline_type" to pipelineType,
                    "task_ids" to taskIds,
                    "stages" to stageRecords,
                    "result" to completedTask.result,
                    "message" to completedTask.errorMessage.ifBlank { "Pipeline stage '$stageType' failed" },
                )
            }

            stageOutputs[stageType] = completedTask.result
            succeededStageCount += 1
            dependencyTaskId = completedTask.taskId
        }

        if (succeededStageCount == 0 || finalStageModel == null) {
            return mapOf(
                "ok" to false,
                "status" to "failed",
                "pipeline_id" to pipelineId,
                "pipeline_type" to pipelineType,
                "task_ids" to taskIds,
                "stages" to stageRecords,
                "message" to "No automation stage completed successfully",
            )
        }

        val finalStage = stages.lastOrNull { it in stageOutputs } ?: stageOutputs.keys.last()
        val finalStageOutput = stageOutputs[finalStage] ?: emptyMap()
        val cacheReceipt = persistPipelineOutputCache(
            pipelineId = pipelineId,
            pipelineType = pipelineType,
            model = checkNotNull(finalStageModel),
            payload = payload,
            stages = stageRecords,
            finalStageOutput = finalStageOutput,
        )
        val failedStages = stageRecords.filter { it["status"]?.toString() != "succeeded" }

        return mapOf(
            "ok" to true,
            "status" to if (failedStages.isEmpty()) "succeeded" else "partial",
            "pipeline_id" to pipelineId,
            "pipeline_type" to pipelineType,
            "task_ids" to taskIds,
            "stages" to stageRecords,
            "stage_outputs" to stageOutputs,
            "failed_stages" to failedStages,
            "result" to (finalStageOutput["result"] ?: finalStageOutput),
            "raw_result" to finalStageOutput,
            "cache" to cacheReceipt,
        )
    }

    private fun resolvePipelineStages(taskType: String, payload: Map<String, Any>): List<String> {
        val explicit = (payload["stages"] as? List<*>)
            ?.mapNotNull { it?.toString()?.trim() }
            ?.filter { it.isNotBlank() }
            ?.map { AiTaskTypes.normalize(it) }
            ?.filter { AiTaskTypes.isExecutionTask(it) }
            ?.distinct()
            ?: emptyList()
        if (explicit.isNotEmpty()) {
            return explicit
        }

        val defaults = when (taskType) {
            "ocr" -> listOf("metadata_extraction", "ocr")
            "captioning" -> listOf("metadata_extraction", "ocr", "captioning")
            "character_recognition" -> listOf("metadata_extraction", "ocr", "character_recognition")
            "series_recognition" -> listOf("metadata_extraction", "ocr", "series_recognition")
            "artist_recognition" -> listOf("metadata_extraction", "ocr", "artist_recognition")
            "tag_prediction" -> listOf("metadata_extraction", "ocr", "tag_prediction")
            "metadata_extraction" -> listOf("metadata_extraction")
            "prompt_generation" -> {
                listOf(
                    "metadata_extraction",
                    "ocr",
                    "captioning",
                    "tag_prediction",
                    "character_recognition",
                    "series_recognition",
                    "artist_recognition",
                    "prompt_generation",
                )
            }
            "embedding_generation" -> listOf("metadata_extraction", "embedding_generation")
            "similarity_search" -> listOf("embedding_generation", "similarity_search")
            "duplicate_detection" -> listOf("metadata_extraction", "embedding_generation", "duplicate_detection")
            "classification" -> listOf("metadata_extraction", "tag_prediction", "classification")
            "detection" -> listOf("metadata_extraction", "detection")
            "face_feature_extraction" -> listOf("metadata_extraction", "detection", "face_feature_extraction")
            "knowledge_pack_execution" -> listOf("knowledge_pack_execution")
            else -> listOf(taskType)
        }

        return defaults
            .map { AiTaskTypes.normalize(it) }
            .filter { AiTaskTypes.isExecutionTask(it) }
            .distinct()
    }

    private fun buildStagePayload(
        payload: Map<String, Any>,
        pipelineId: String,
        pipelineType: String,
        stageType: String,
        stageIndex: Int,
        stageTotal: Int,
        stageOutputs: Map<String, Map<String, Any>>,
    ): Map<String, Any> {
        val stagePayload = linkedMapOf<String, Any>()
        val nestedPayload = (payload["payload"] as? Map<*, *>)?.toStringKeyMap() ?: emptyMap()

        stagePayload.putAll(payload)
        stagePayload.putAll(nestedPayload)
        stagePayload.remove("items")
        stagePayload.remove("stages")

        stagePayload["pipeline_id"] = pipelineId
        stagePayload["pipeline_type"] = pipelineType
        stagePayload["stage_type"] = stageType
        stagePayload["stage_index"] = stageIndex
        stagePayload["stage_total"] = stageTotal

        if (stageOutputs.isNotEmpty()) {
            stagePayload["upstream_results"] = stageOutputs
        }

        stageOutputs["metadata_extraction"]?.let { metadataStage ->
            val metadata = extractResultMap(metadataStage)["metadata"].toStringMap()
            if (metadata.isNotEmpty() && stagePayload["metadata"].toStringMap().isEmpty()) {
                stagePayload["metadata"] = metadata
            }
        }

        stageOutputs["tag_prediction"]?.let { tagStage ->
            val tags = extractResultMap(tagStage)["tags"] as? List<*>
            if (tags != null && tags.isNotEmpty() && stagePayload["tags"] !is List<*>) {
                stagePayload["tags"] = tags.mapNotNull { it?.toString() }
            }
        }

        stageOutputs["captioning"]?.let { captionStage ->
            val caption = extractResultMap(captionStage)["caption"]?.toString().orEmpty()
            if (caption.isNotBlank() && stagePayload["caption"]?.toString().orEmpty().isBlank()) {
                stagePayload["caption"] = caption
            }
        }

        stageOutputs["ocr"]?.let { ocrStage ->
            val text = extractResultMap(ocrStage)["text"]?.toString().orEmpty()
            if (text.isNotBlank() && stagePayload["ocr_text"]?.toString().orEmpty().isBlank()) {
                stagePayload["ocr_text"] = text
            }
        }

        stageOutputs["embedding_generation"]?.let { embeddingStage ->
            val embedding = embeddingStage["embedding"] as? List<*>
            if (embedding != null && embedding.isNotEmpty() && stagePayload["query_embedding"] !is List<*>) {
                stagePayload["query_embedding"] = embedding
            }
        }

        stagePayload["task_type"] = stageType
        return stagePayload
    }

    private fun persistPipelineOutputCache(
        pipelineId: String,
        pipelineType: String,
        model: AiModelDescriptor,
        payload: Map<String, Any>,
        stages: List<Map<String, Any>>,
        finalStageOutput: Map<String, Any>,
    ): Map<String, Any> {
        val outputDir = File(appContext.filesDir, "local_ai_pipeline_outputs").apply { mkdirs() }
        val outputFile = File(outputDir, "${pipelineType}_${pipelineId}.json")
        val snapshot = linkedMapOf<String, Any>(
            "pipeline_id" to pipelineId,
            "pipeline_type" to pipelineType,
            "model_id" to model.modelId,
            "model_version" to model.version,
            "payload" to payload,
            "stages" to stages,
            "result" to finalStageOutput,
            "persisted_at_ms" to System.currentTimeMillis(),
        )
        outputFile.writeText(LocalAiJson.encodeMap(snapshot))

        val cacheType = when (pipelineType) {
            "ocr" -> "ocr_results"
            "captioning" -> "caption_results"
            "character_recognition", "series_recognition", "artist_recognition" -> "recognition_results"
            "tag_prediction" -> "tag_results"
            "metadata_extraction" -> "metadata_results"
            "prompt_generation" -> "prompt_results"
            "duplicate_detection" -> "duplicate_results"
            "classification" -> "classification_results"
            "detection" -> "detection_results"
            "face_feature_extraction" -> "face_feature_results"
            "knowledge_pack_execution" -> "knowledge_pack_results"
            else -> "pipeline_results"
        }

        val baseCacheKey = "pipeline_output:${pipelineType}:$pipelineId"
        modelCache.upsertEntry(
            modelId = model.modelId,
            cacheKey = baseCacheKey,
            artifactPath = outputFile.absolutePath,
            sizeBytes = outputFile.length(),
            pinned = false,
            metadata = mapOf(
                "cache_type" to cacheType,
                "pipeline_id" to pipelineId,
                "pipeline_type" to pipelineType,
            ),
        )

        val imageId = payload["image_id"].toIntOrNullValue()
        if (imageId != null) {
            modelCache.upsertEntry(
                modelId = model.modelId,
                cacheKey = "pipeline_output_latest:${pipelineType}:image:$imageId",
                artifactPath = outputFile.absolutePath,
                sizeBytes = outputFile.length(),
                pinned = false,
                metadata = mapOf(
                    "cache_type" to "${cacheType}_latest",
                    "pipeline_type" to pipelineType,
                    "image_id" to imageId,
                ),
            )
        }

        return mapOf(
            "cache_key" to baseCacheKey,
            "artifact_path" to outputFile.absolutePath,
            "size_bytes" to outputFile.length(),
        )
    }

    private fun extractResultMap(stageOutput: Map<String, Any>): Map<String, Any> {
        return (stageOutput["result"] as? Map<*, *>)?.toStringKeyMap() ?: emptyMap()
    }

    private fun awaitTaskTerminalState(taskId: String, timeoutMs: Long): AiTaskRecord? {
        val deadlineAtMs = System.currentTimeMillis() + timeoutMs.coerceAtLeast(1_000L)
        var latest = repository.getTask(taskId)
        while (latest != null && latest.status !in TERMINAL_TASK_STATUSES && System.currentTimeMillis() < deadlineAtMs) {
            Thread.sleep(TASK_POLL_INTERVAL_MS)
            latest = repository.getTask(taskId)
        }
        return latest
    }

    private fun extractSemanticMatches(result: Map<String, Any>): List<Map<String, Any>> {
        val directMatches = (result["matches"] as? List<*>)
            ?.mapNotNull { (it as? Map<*, *>)?.toStringKeyMap() }
            ?: emptyList()
        if (directMatches.isNotEmpty()) {
            return directMatches
        }

        val nestedResult = (result["result"] as? Map<*, *>)?.toStringKeyMap() ?: return emptyList()
        return (nestedResult["matches"] as? List<*>)
            ?.mapNotNull { (it as? Map<*, *>)?.toStringKeyMap() }
            ?: emptyList()
    }

    private fun registerModelCapabilities(
        model: AiModelDescriptor,
        inspection: ModelPackageInspection,
    ) {
        val now = System.currentTimeMillis()
        inspection.capabilities.forEach { capability ->
            repository.upsertCapability(
                AiCapabilityDescriptor(
                    capabilityId = "model.${model.modelId}.${model.version}.$capability",
                    providerId = "model:${model.modelId}@${model.version}",
                    capabilityType = "model_capability",
                    status = "available",
                    metadata = mapOf(
                        "model_id" to model.modelId,
                        "version" to model.version,
                        "runtime" to model.requiredRuntime,
                        "tasks" to model.supportedTasks,
                        "package_files" to inspection.files,
                    ),
                    registeredAtMs = now,
                    updatedAtMs = now,
                ),
            )
        }
    }

    private fun payloadToModelDescriptor(
        payload: Map<String, Any>,
        installed: Boolean,
        installState: String,
        installPath: String,
    ): AiModelDescriptor {
        val now = System.currentTimeMillis()
        val modelId = payload["model_id"]?.toString()?.trim().orEmpty()
        val version = payload["version"]?.toString()?.trim().orEmpty().ifBlank { "1.0.0" }
        val supportedTasks = (payload["supported_tasks"] as? List<*>)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf { item -> item.isNotBlank() } }
            ?.map { AiTaskTypes.normalize(it) }
            ?: emptyList()
        val requiredRuntime = payload["required_runtime"]?.toString()?.trim().orEmpty()
        val supportedRuntimes = (payload["supported_runtimes"] as? List<*>)
            ?.mapNotNull { it?.toString()?.trim()?.lowercase()?.takeIf { runtime -> runtime.isNotBlank() } }
            ?: if (requiredRuntime.isNotBlank()) listOf(requiredRuntime.lowercase()) else emptyList()
        val dependencies = (payload["dependencies"] as? List<*>)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf { dependency -> dependency.isNotBlank() } }
            ?: emptyList()

        val sizeBytes = (payload["size_bytes"] as? Number)?.toLong() ?: payload["size_bytes"]?.toString()?.toLongOrNull() ?: 0L
        val metadata = modelRegistryMetadata(
            payload = payload,
            sizeBytes = sizeBytes,
            requiredRuntime = requiredRuntime,
            supportedRuntimes = supportedRuntimes,
            installed = installed,
            installState = installState,
        )
        return AiModelDescriptor(
            modelId = modelId,
            version = version,
            displayName = payload["display_name"]?.toString()?.trim().orEmpty().ifBlank { modelId },
            sizeBytes = sizeBytes,
            hashSha256 = payload["hash_sha256"]?.toString()?.trim().orEmpty().lowercase(),
            supportedTasks = supportedTasks,
            requiredRuntime = requiredRuntime,
            supportedRuntimes = supportedRuntimes,
            dependencies = dependencies,
            requiredHardware = payload["required_hardware"].toStringMap(),
            compatibility = payload["compatibility"].toStringMap(),
            metadata = metadata,
            source = payload["source"]?.toString()?.trim().orEmpty().ifBlank { "manual" },
            sourceUri = payload["source_uri"]?.toString()?.trim().orEmpty(),
            installed = installed,
            installState = installState,
            installPath = installPath,
            createdAtMs = (payload["created_at_ms"] as? Number)?.toLong() ?: now,
            updatedAtMs = now,
        )
    }

    private fun modelRegistryMetadata(
        payload: Map<String, Any>,
        sizeBytes: Long,
        requiredRuntime: String,
        supportedRuntimes: List<String>,
        installed: Boolean,
        installState: String,
    ): Map<String, Any> {
        val metadata = payload["metadata"].toStringMap().toMutableMap()
        val sourceName = payload["source_path"]?.toString().orEmpty().ifBlank { payload["source_uri"]?.toString().orEmpty() }
        val quantization = payload["quantization"]?.toString()?.trim().orEmpty()
            .ifBlank { Regex("(?i)(q\\d+(?:_[a-z0-9]+)?|fp16|fp32|int8)").find(sourceName)?.value ?: "unknown" }
        val profile = hardwareDetector.detectProfile()
        metadata["quantization"] = quantization
        metadata["context_length"] = payload["context_length"]?.toString()?.toIntOrNull() ?: 0
        metadata["embedding_dimension"] = payload["embedding_dimension"]?.toString()?.toIntOrNull() ?: 0
        metadata["memory_requirement_bytes"] = payload["memory_requirement_bytes"]?.toString()?.toLongOrNull()
            ?: (sizeBytes * 2L).coerceAtLeast(16L * 1024L * 1024L)
        metadata["preferred_backend"] = requiredRuntime.ifBlank { supportedRuntimes.firstOrNull().orEmpty() }
        metadata["supported_backends"] = supportedRuntimes
        metadata["current_status"] = if (installed) installState else "available"
        metadata["hardware_compatibility"] = mapOf(
            "npu_available" to profile.npuAvailable,
            "gpu_available" to profile.gpuAvailable,
            "cpu_cores" to profile.cpuCores,
            "compatible" to true,
        )
        metadata.putIfAbsent("benchmark_results", mapOf("status" to "not_benchmarked"))
        return metadata
    }

    private fun scheduleIdleBenchmark(model: AiModelDescriptor) {
        runtimeScope.launch {
            repeat(60) {
                val busy = repository.listTasks(limit = 200).any { task -> task.status in setOf("pending", "running") }
                if (!busy) {
                    val refreshed = repository.getModel(model.modelId, model.version) ?: return@launch
                    val benchmark = refreshed.metadata["benchmark_results"] as? Map<*, *>
                    if (benchmark?.get("status") == "estimated_idle") return@launch
                    val estimatedMemory = refreshed.metadata["memory_requirement_bytes"]?.toString()?.toLongOrNull()
                        ?: refreshed.sizeBytes.coerceAtLeast(1L)
                    val score = (100 - (estimatedMemory / (128L * 1024L * 1024L)).toInt()).coerceIn(1, 100)
                    repository.upsertModel(
                        refreshed.copy(
                            metadata = refreshed.metadata + mapOf(
                                "benchmark_results" to mapOf(
                                    "status" to "estimated_idle",
                                    "score" to score,
                                    "measured_at_ms" to System.currentTimeMillis(),
                                ),
                            ),
                            updatedAtMs = System.currentTimeMillis(),
                        ),
                    )
                    return@launch
                }
                delay(1_000L)
            }
        }
    }

    private fun compareVersions(a: String, b: String): Int {
        val aParts = normalizeVersion(a)
        val bParts = normalizeVersion(b)
        val max = maxOf(aParts.size, bParts.size)
        for (i in 0 until max) {
            val left = aParts.getOrElse(i) { 0 }
            val right = bParts.getOrElse(i) { 0 }
            if (left != right) {
                return left.compareTo(right)
            }
        }
        return a.compareTo(b)
    }

    private fun normalizeVersion(raw: String): List<Int> {
        return raw
            .trim()
            .removePrefix("v")
            .split('.')
            .map { segment -> segment.takeWhile { it.isDigit() } }
            .map { it.toIntOrNull() ?: 0 }
    }

    private fun loadAssetJson(assetName: String): JSONObject? {
        return runCatching {
            appContext.assets.open(assetName).bufferedReader().use { reader ->
                JSONObject(reader.readText())
            }
        }.getOrNull()
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun Any?.toStringMap(): Map<String, Any> {
        return when (this) {
            is Map<*, *> -> this.toStringKeyMap()
            else -> emptyMap()
        }
    }

    private fun Map<*, *>.toStringKeyMap(): Map<String, Any> {
        val result = linkedMapOf<String, Any>()
        this.forEach { (keyRaw, value) ->
            val key = keyRaw?.toString()?.trim().orEmpty()
            if (key.isBlank() || value == null) {
                return@forEach
            }
            result[key] = when (value) {
                is Map<*, *> -> value.toStringKeyMap()
                is List<*> -> value.mapNotNull { it }
                else -> value
            }
        }
        return result
    }

    private fun Any?.toBooleanValue(defaultValue: Boolean): Boolean {
        return when (this) {
            is Boolean -> this
            is Number -> this.toInt() != 0
            else -> this?.toString()?.let { raw ->
                when (raw.trim().lowercase()) {
                    "true", "1", "yes", "on" -> true
                    "false", "0", "no", "off" -> false
                    else -> defaultValue
                }
            } ?: defaultValue
        }
    }

    private fun Any?.toIntOrNullValue(): Int? {
        return when (this) {
            is Number -> this.toInt()
            else -> this?.toString()?.toIntOrNull()
        }
    }

    private fun Any?.toIntValue(defaultValue: Int): Int {
        return toIntOrNullValue() ?: defaultValue
    }

    private fun Any?.toLongValue(defaultValue: Long): Long {
        return when (this) {
            is Number -> this.toLong()
            else -> this?.toString()?.toLongOrNull() ?: defaultValue
        }
    }

    private fun Any?.toDoubleOrNullValue(): Double? {
        return when (this) {
            is Number -> this.toDouble()
            else -> this?.toString()?.toDoubleOrNull()
        }
    }

    private fun recoverInterruptedLocalImports() {
        val now = System.currentTimeMillis()
        repository.listInstallRuns(limit = 500)
            .filter { run ->
                run.status.equals("running", ignoreCase = true) &&
                    run.action.equals("local_import", ignoreCase = true)
            }
            .forEach { run ->
                val packageDirectory = File(appContext.filesDir, "model-packages/${run.installId}")
                val installedModel = repository.getModel(run.modelId, run.version)
                val installedPath = installedModel?.installPath?.takeIf(String::isNotBlank)?.let(::File)
                val packagePath = runCatching { packageDirectory.canonicalPath }.getOrDefault(packageDirectory.absolutePath)
                val installedPathValue = installedPath?.let {
                    runCatching { it.canonicalPath }.getOrDefault(it.absolutePath)
                }.orEmpty()
                val installedUsesPackage =
                    installedModel?.installed == true &&
                        installedPath?.isFile == true &&
                        (installedPathValue == packagePath || installedPathValue.startsWith(packagePath + File.separator))

                if (!installedUsesPackage) {
                    packageDirectory.deleteRecursively()
                }

                val recoveredStatus = if (installedUsesPackage) "succeeded" else "failed"
                val recoveredMessage = if (installedUsesPackage) {
                    ""
                } else {
                    "Local model import was interrupted before completion; partial extracted files were removed."
                }
                repository.updateInstallRun(
                    installId = run.installId,
                    status = recoveredStatus,
                    actualHash = run.actualHash,
                    details = run.details + mapOf(
                        "recovered_after_interruption" to true,
                        "recovered_at_ms" to now,
                    ),
                    errorMessage = recoveredMessage,
                    retryCount = run.retryCount,
                    startedAtMs = run.startedAtMs,
                    finishedAtMs = now,
                )
            }
    }

    private fun ensureInitialized() {
        check(initialized) { "LocalAiManager is not initialized" }
    }

    companion object {
        private const val TAG = "AilmLocalAiManager"
        private const val DEFAULT_SEMANTIC_TIMEOUT_MS = 7_500L
        private const val DEFAULT_PIPELINE_STAGE_TIMEOUT_MS = 12_000L
        private const val DEFAULT_PIPELINE_PRIORITY = 12
        private const val TASK_SETTLE_WINDOW_MS = 1_500L
        private const val TASK_POLL_INTERVAL_MS = 25L
        private const val MAX_BATCH_ITEMS = 500

        private val TERMINAL_TASK_STATUSES = setOf(
            "succeeded",
            "failed",
            "cancelled",
            "paused",
            "invalid",
            "unsupported",
            "resource_exhaustion",
        )
    }
}
