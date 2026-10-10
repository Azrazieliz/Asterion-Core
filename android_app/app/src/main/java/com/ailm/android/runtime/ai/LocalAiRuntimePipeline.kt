package com.ailm.android.runtime.ai

import android.content.Context
import android.os.Debug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

class LocalAiBackendManager {
    private val backends = ConcurrentHashMap<String, AiBackendRuntime>()

    fun registerBackend(backend: AiBackendRuntime) {
        backends[backend.runtimeId.lowercase()] = backend
    }

    fun unregisterBackend(runtimeId: String): Boolean {
        return backends.remove(runtimeId.trim().lowercase()) != null
    }

    fun clearBackends() {
        backends.clear()
    }

    fun snapshotBackends(): List<AiBackendRuntime> {
        return backends.values.sortedBy { it.runtimeId }
    }

    fun snapshotProviders(availableOnly: Boolean = false): List<AiRuntimeProvider> {
        return snapshotBackends()
            .filterIsInstance<AiRuntimeProvider>()
            .filter { !availableOnly || it.providerState == AiRuntimeProviderState.AVAILABLE }
    }

    fun listBackends(): List<Map<String, Any>> {
        return snapshotBackends().map { backend ->
            val capability = backend.detectCapabilities()
            mapOf(
                "runtime_id" to backend.runtimeId,
                "runtime_type" to backend.runtimeType.raw,
                "supported_tasks" to capability.supportedTasks.sorted(),
                "supports_cancellation" to capability.supportsCancellation,
                "supports_pause_resume" to capability.supportsPauseResume,
                "max_concurrent_tasks" to capability.maxConcurrentTasks,
                "metadata" to capability.metadata,
            )
        }
    }

    fun hasRuntime(runtimeName: String): Boolean {
        val normalized = runtimeName.trim().lowercase()
        if (normalized.isBlank()) {
            return false
        }
        return snapshotBackends().any {
            it.runtimeId.equals(normalized, ignoreCase = true) ||
                it.runtimeType.raw.equals(normalized, ignoreCase = true)
        }
    }

    fun resolveByRuntime(runtimeName: String): List<AiBackendRuntime> {
        val normalized = runtimeName.trim().lowercase()
        if (normalized.isBlank()) {
            return snapshotBackends()
        }
        return snapshotBackends().filter {
            it.runtimeId.equals(normalized, ignoreCase = true) ||
                it.runtimeType.raw.equals(normalized, ignoreCase = true)
        }
    }
}

internal fun expectedInstalledArtifactHash(model: AiModelDescriptor): String {
    val packageHash = model.metadata["package_hash_sha256"]?.toString()?.trim().orEmpty()
    val artifactHash = model.metadata["artifact_hash_sha256"]?.toString()?.trim().orEmpty()
    val descriptorHash = model.hashSha256.trim()
    return artifactHash.ifBlank {
        descriptorHash.takeUnless { hash ->
            packageHash.isNotBlank() && hash.equals(packageHash, ignoreCase = true)
        }.orEmpty()
    }
}

class LocalAiValidationService(
    private val repository: LocalAiRepository,
    private val backendManager: LocalAiBackendManager,
    private val resourceManager: LocalAiResourceManager,
) {
    private fun normalizeRuntimeName(raw: String): String {
        val candidate = raw.trim()
            .lowercase()
            .replace('-', '_')
            .replace('.', '_')
            .replace(' ', '_')
            .replace(Regex("_+"), "_")
        return when (candidate) {
            "tflite", "tensorflow_lite", "lite" -> AiRuntimeType.TFLITE.raw
            "onnx" -> AiRuntimeType.ONNX.raw
            "llama_cpp", "llama_cpp", "llama_cpp" -> AiRuntimeType.LLAMA_CPP.raw
            "gguf" -> AiRuntimeType.LLAMA_CPP.raw
            else -> candidate
        }
    }

    fun validateModelDescriptor(model: AiModelDescriptor): AiValidationReport {
        val issues = mutableListOf<AiValidationIssue>()
        if (model.modelId.isBlank()) {
            issues += AiValidationIssue("model_id_missing", "model_id is required")
        }
        if (model.version.isBlank()) {
            issues += AiValidationIssue("model_version_missing", "version is required")
        }
        if (model.displayName.isBlank()) {
            issues += AiValidationIssue("display_name_missing", "display_name is required")
        }
        if (model.sizeBytes < 0L) {
            issues += AiValidationIssue("size_negative", "size_bytes must be non-negative")
        }
        if (model.hashSha256.isNotBlank() && !model.hashSha256.matches(Regex("^[a-fA-F0-9]{64}$"))) {
            issues += AiValidationIssue("hash_invalid", "hash_sha256 must be a 64-char hex string")
        }
        val requiredRuntime = normalizeRuntimeName(model.requiredRuntime)
        if (requiredRuntime.isNotBlank() && requiredRuntime !in EXECUTABLE_RUNTIMES) {
            issues += AiValidationIssue(
                "runtime_incompatible",
                "Model runtime '${model.requiredRuntime}' is not recognized as an executable runtime",
            )
        }
        val unsupportedRuntimes = model.supportedRuntimes
            .map { normalizeRuntimeName(it) }
            .filterNot { it in EXECUTABLE_RUNTIMES }
        if (unsupportedRuntimes.isNotEmpty()) {
            issues += AiValidationIssue(
                "runtime_incompatible",
                "Model declares unsupported runtimes: ${unsupportedRuntimes.distinct().joinToString()}",
            )
        }
        if (model.installed) {
            val format = FileSupport.modelFormat(model)
            if (format !in EXECUTABLE_FORMATS) {
                issues += AiValidationIssue(
                    "model_format_incompatible",
                    "Model format '$format' is incompatible; only supported executable artifact formats are allowed",
                )
            }
            if (requiredRuntime == AiRuntimeType.ONNX.raw && format != "onnx") {
                issues += AiValidationIssue("model_format_runtime_mismatch", "ONNX runtime requires an .onnx artifact")
            }
            if (requiredRuntime == AiRuntimeType.TFLITE.raw && format !in setOf("tflite", "lite")) {
                issues += AiValidationIssue("model_format_runtime_mismatch", "TensorFlow Lite runtime requires a .tflite artifact")
            }
            if (requiredRuntime == AiRuntimeType.LLAMA_CPP.raw && format != "gguf") {
                issues += AiValidationIssue("model_format_runtime_mismatch", "LLAMA_CPP runtime requires a .gguf artifact")
            }
        }
        if (model.supportedTasks.isEmpty()) {
            issues += AiValidationIssue(
                "supported_tasks_empty",
                "Model must declare supported_tasks for compatibility checks",
            )
        }
        issues += ModelInferenceContract.validationIssues(model)

        val compatibleProviders = backendManager.snapshotProviders(availableOnly = true)
            .filter { it.supportsModel(model) }
        if (compatibleProviders.isEmpty()) {
            issues += AiValidationIssue(
                "provider_unavailable",
                "No available runtime provider supports this model's format, tasks, and device capabilities",
                severity = "warning",
            )
        }

        val hardwareReport = resourceManager.validateHardwareRequirements(model.requiredHardware)
        issues += hardwareReport.issues

        return AiValidationReport(
            valid = issues.none { it.severity == "error" },
            issues = issues,
            metadata = mapOf(
                "model_id" to model.modelId,
                "version" to model.version,
            ),
        )
    }

    fun validateInstalledModel(modelId: String, version: String): AiValidationReport {
        val model = repository.getModel(modelId, version)
        if (model == null) {
            return AiValidationReport(
                valid = false,
                issues = listOf(
                    AiValidationIssue("model_not_found", "Model $modelId@$version is not registered"),
                ),
            )
        }

        val issues = mutableListOf<AiValidationIssue>()
        if (!model.installed) {
            issues += AiValidationIssue("model_not_installed", "Model $modelId@$version is not marked as installed")
        }

        if (model.installPath.isBlank()) {
            issues += AiValidationIssue("install_path_missing", "Installed model is missing install_path")
        } else {
            val file = File(model.installPath)
            if (!file.exists()) {
                issues += AiValidationIssue("file_missing", "Installed model file does not exist: ${model.installPath}")
            } else {
                if (model.sizeBytes > 0 && file.length() != model.sizeBytes) {
                    issues += AiValidationIssue(
                        "size_mismatch",
                        "File size ${file.length()} does not match expected ${model.sizeBytes}",
                    )
                }
                val packageHash = model.metadata["package_hash_sha256"]?.toString()?.trim().orEmpty()
                val descriptorHash = model.hashSha256.trim()
                val expectedArtifactHash = expectedInstalledArtifactHash(model)
                if (expectedArtifactHash.isNotBlank()) {
                    val actual = sha256Hex(file)
                    if (!actual.equals(expectedArtifactHash, ignoreCase = true)) {
                        issues += AiValidationIssue("hash_mismatch", "Computed artifact SHA-256 does not match registry artifact hash")
                    }
                } else if (descriptorHash.isNotBlank() && packageHash.isNotBlank() &&
                    descriptorHash.equals(packageHash, ignoreCase = true)
                ) {
                    val packageSource = model.metadata["package_source_path"]?.toString()?.trim().orEmpty()
                    val packageFile = packageSource.takeIf(String::isNotBlank)?.let(::File)
                    if (packageFile != null && packageFile.isFile) {
                        val actualPackageHash = sha256Hex(packageFile)
                        if (!actualPackageHash.equals(packageHash, ignoreCase = true)) {
                            issues += AiValidationIssue("package_hash_mismatch", "Computed package SHA-256 does not match stored package hash")
                        }
                    } else {
                        issues += AiValidationIssue(
                            "artifact_hash_legacy_unavailable",
                            "Legacy ZIP import has no stored artifact hash; file existence and size were verified, but artifact hash cannot be reconstructed without re-import.",
                            severity = "warning",
                        )
                    }
                }
            }
        }

        model.dependencies.forEach { dependency ->
            val dependencyModelId = dependency.substringBefore('@').trim()
            if (dependencyModelId.isBlank()) {
                return@forEach
            }
            val installedDependency = repository.listModels(installedOnly = true).any { it.modelId == dependencyModelId }
            if (!installedDependency) {
                issues += AiValidationIssue(
                    "dependency_missing",
                    "Required dependency model '$dependencyModelId' is not installed",
                )
            }
        }

        val runtimeReport = validateModelDescriptor(model)
        issues += runtimeReport.issues.filter {
            it.code == "runtime_unavailable" ||
                it.code == "supported_runtime_unavailable" ||
                it.code == "simd_missing"
        }

        return AiValidationReport(
            valid = issues.none { it.severity == "error" },
            issues = issues,
            metadata = mapOf("model" to model.toMap()),
        )
    }

    fun validateExecutionRequest(request: AiExecutionRequest): AiValidationReport {
        val issues = mutableListOf<AiValidationIssue>()
        val normalizedTaskType = AiTaskTypes.normalize(request.taskType)

        if (normalizedTaskType.isBlank()) {
            issues += AiValidationIssue("task_type_missing", "task_type is required")
        } else if (!AiTaskTypes.isSupported(normalizedTaskType)) {
            issues += AiValidationIssue(
                "task_type_unsupported",
                "task_type '$normalizedTaskType' is not supported by Local AI execution layer",
            )
        }

        if (AiTaskTypes.isExecutionTask(normalizedTaskType)) {
            if (request.modelId.isBlank()) {
                issues += AiValidationIssue("task_model_missing", "Execution tasks must provide model_id")
            }
            if (request.version.isBlank()) {
                issues += AiValidationIssue("task_model_version_missing", "Execution tasks must provide version")
            }
        }

        if (request.runtimeHint.isNotBlank() && !backendManager.hasRuntime(request.runtimeHint)) {
            issues += AiValidationIssue(
                "runtime_unavailable",
                "Requested runtime '${request.runtimeHint}' is not registered",
            )
        }

        if (request.deadlineAtMs > 0 && request.deadlineAtMs <= System.currentTimeMillis()) {
            issues += AiValidationIssue("deadline_expired", "Execution deadline is already expired")
        }

        return AiValidationReport(
            valid = issues.none { it.severity == "error" },
            issues = issues,
            metadata = mapOf(
                "task_id" to request.taskId,
                "task_type" to normalizedTaskType,
            ),
        )
    }

    fun validateModelCompatibility(
        task: AiTaskRecord,
        model: AiModelDescriptor?,
        runtimeId: String,
        backendCapability: AiBackendCapability,
    ): AiValidationReport {
        val issues = mutableListOf<AiValidationIssue>()
        val normalizedTaskType = AiTaskTypes.normalize(task.taskType)

        if (AiTaskTypes.isExecutionTask(normalizedTaskType) && model == null) {
            issues += AiValidationIssue("model_not_found", "Task references a model that is not installed")
            return AiValidationReport(valid = false, issues = issues)
        }

        if (model != null) {
            if (!model.installed && AiTaskTypes.isExecutionTask(normalizedTaskType)) {
                issues += AiValidationIssue("model_not_installed", "Model ${model.modelId}@${model.version} is not installed")
            }

            if (model.supportedTasks.isNotEmpty() && normalizedTaskType !in model.supportedTasks.map { AiTaskTypes.normalize(it) }.toSet()) {
                issues += AiValidationIssue(
                    "model_task_unsupported",
                    "Model ${model.modelId}@${model.version} does not support task $normalizedTaskType",
                )
            }

            val hardwareReport = resourceManager.validateHardwareRequirements(model.requiredHardware)
            issues += hardwareReport.issues
        }

        if (
            backendCapability.supportedTasks.isNotEmpty() &&
            normalizedTaskType !in backendCapability.supportedTasks.map { AiTaskTypes.normalize(it) }.toSet()
        ) {
            issues += AiValidationIssue(
                "backend_task_unsupported",
                "Backend ${backendCapability.runtimeId} does not support task $normalizedTaskType",
            )
        }

        return AiValidationReport(
            valid = issues.none { it.severity == "error" },
            issues = issues,
            metadata = mapOf(
                "task_id" to task.taskId,
                "task_type" to normalizedTaskType,
                "runtime_id" to runtimeId,
                "backend" to backendCapability.toMap(),
            ),
        )
    }

    fun validateInfrastructureSnapshot(): AiValidationReport {
        val issues = mutableListOf<AiValidationIssue>()
        if (backendManager.listBackends().isEmpty()) {
            issues += AiValidationIssue(
                "backend_registry_empty",
                "No backends are registered; execution tasks cannot run until a backend is registered",
                severity = "warning",
            )
        }

        val tasks = repository.listTasks(limit = 500)
        val installedModels = repository.listModels(installedOnly = true).size

        return AiValidationReport(
            valid = issues.none { it.severity == "error" },
            issues = issues,
            metadata = mapOf(
                "queue_pending" to tasks.count { it.status == "pending" },
                "queue_running" to tasks.count { it.status == "running" },
                "queue_paused" to tasks.count { it.status == "paused" },
                "queue_failed" to tasks.count { it.status == "failed" },
                "installed_models" to installedModels,
                "registered_backends" to backendManager.listBackends().size,
            ),
        )
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

    private companion object {
        val EXECUTABLE_RUNTIMES = setOf(
            AiRuntimeType.ONNX.raw,
            AiRuntimeType.TFLITE.raw,
            AiRuntimeType.LLAMA_CPP.raw,
        )
        val EXECUTABLE_FORMATS = setOf("onnx", "tflite", "lite", "gguf")
    }
}

class DefaultLocalAiRuntimeGateway(
    private val validationService: LocalAiValidationService,
) : LocalAiRuntimeGateway {

    override suspend fun execute(
        request: AiExecutionRequest,
        backend: AiBackendRuntime,
        reporter: AiProgressReporter,
    ): AiExecutionResult {
        val validation = validationService.validateExecutionRequest(request)
        if (!validation.valid) {
            return AiExecutionResult(
                ok = false,
                status = "invalid",
                message = "Execution request is invalid",
                details = validation.toMap(),
            )
        }

        val normalizedTaskType = AiTaskTypes.normalize(request.taskType)
        if (
            backend.supportedTasks.isNotEmpty() &&
            normalizedTaskType !in backend.supportedTasks.map { AiTaskTypes.normalize(it) }.toSet()
        ) {
            return AiExecutionResult(
                ok = false,
                status = "unsupported",
                message = "Backend ${backend.runtimeId} does not support task $normalizedTaskType",
                details = mapOf(
                    "runtime_id" to backend.runtimeId,
                    "task_type" to normalizedTaskType,
                ),
            )
        }

        val timeoutWindowMs = if (request.deadlineAtMs <= 0L) {
            0L
        } else {
            request.deadlineAtMs - System.currentTimeMillis()
        }

        if (request.deadlineAtMs > 0L && timeoutWindowMs <= 0L) {
            return AiExecutionResult(
                ok = false,
                status = "timeout",
                message = "Task exceeded configured timeout before execution started",
            )
        }

        return runCatching {
            if (timeoutWindowMs > 0L) {
                withTimeoutOrNull(timeoutWindowMs) {
                    backend.execute(request, reporter)
                } ?: AiExecutionResult(
                    ok = false,
                    status = "timeout",
                    message = "Task exceeded execution timeout",
                )
            } else {
                backend.execute(request, reporter)
            }
        }.getOrElse { error ->
            if (error is CancellationException) {
                AiExecutionResult(
                    ok = false,
                    status = "cancelled",
                    message = error.message ?: "cancelled",
                )
            } else {
                AiExecutionResult(
                    ok = false,
                    status = "runtime_failure",
                    message = error.message ?: error.javaClass.simpleName,
                    details = mapOf("runtime_id" to backend.runtimeId),
                )
            }
        }
    }
}

class LocalAiExecutionQueue(
    private val repository: LocalAiRepository,
    private val settingsProvider: () -> AiSettings,
) {
    fun enqueueTask(
        taskType: String,
        modelId: String = "",
        version: String = "",
        runtimeHint: String = "",
        priority: Int = 0,
        maxRetries: Int = settingsProvider().maxQueueRetries,
        timeoutMs: Long = settingsProvider().defaultTaskTimeoutMs,
        dependencyTaskIds: List<String> = emptyList(),
        payload: Map<String, Any> = emptyMap(),
    ): AiTaskRecord {
        val now = System.currentTimeMillis()
        val normalizedTaskType = AiTaskTypes.normalize(taskType.ifBlank { "custom" })
        val task = AiTaskRecord(
            taskId = UUID.randomUUID().toString(),
            taskType = normalizedTaskType,
            modelId = modelId.trim(),
            version = version.trim(),
            runtimeHint = runtimeHint.trim(),
            priority = priority,
            status = "pending",
            progress = 0.0,
            retryCount = 0,
            maxRetries = maxRetries.coerceAtLeast(0),
            cancellationRequested = false,
            pauseRequested = false,
            dependencyTaskIds = dependencyTaskIds.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            nextRunAtMs = now,
            timeoutMs = timeoutMs.coerceAtLeast(1_000L),
            payload = payload,
            result = emptyMap(),
            createdAtMs = now,
            updatedAtMs = now,
            startedAtMs = 0L,
            finishedAtMs = 0L,
            errorMessage = "",
            sessionId = "",
        )
        repository.upsertTask(task)
        return task
    }

    fun listTasks(limit: Int = 200): List<AiTaskRecord> {
        return repository.listTasks(limit)
    }

    fun getTask(taskId: String): AiTaskRecord? {
        return repository.getTask(taskId)
    }

    fun listRunnableTasks(nowMs: Long, limit: Int): List<AiTaskRecord> {
        return repository.listRunnablePendingTasks(nowMs, limit)
    }

    fun listDependencyStatuses(task: AiTaskRecord): Map<String, String> {
        return repository.listTaskStatuses(task.dependencyTaskIds)
    }

    fun recoverInterruptedTasks(): Int {
        return repository.recoverInterruptedTasks()
    }

    fun markTaskRunning(taskId: String): Boolean {
        return repository.markTaskRunning(taskId)
    }

    fun setTaskSession(taskId: String, sessionId: String): Boolean {
        return repository.setTaskSession(taskId, sessionId)
    }

    fun updateTaskProgress(taskId: String, progress: Double, message: String): Boolean {
        return repository.updateTaskProgress(taskId, progress, message)
    }

    fun markTaskPaused(taskId: String, message: String): Boolean {
        return repository.markTaskPaused(taskId, message)
    }

    fun markTaskCompleted(taskId: String, status: String, result: Map<String, Any>, errorMessage: String = ""): Boolean {
        return repository.markTaskCompleted(taskId, status, result, errorMessage)
    }

    fun scheduleRetry(taskId: String, nextRunAtMs: Long, errorMessage: String): Boolean {
        return repository.scheduleTaskRetry(taskId, nextRunAtMs, errorMessage)
    }

    fun cancelTask(taskId: String): Boolean {
        val normalized = taskId.trim()
        if (normalized.isBlank()) {
            return false
        }
        val cancelledPending = repository.cancelPendingTask(normalized)
        if (cancelledPending) {
            return true
        }
        return repository.requestTaskCancellation(normalized)
    }

    fun pauseTask(taskId: String): Boolean {
        val normalized = taskId.trim()
        if (normalized.isBlank()) {
            return false
        }
        val pausedPending = repository.pausePendingTask(normalized)
        if (pausedPending) {
            return true
        }
        return repository.requestTaskPause(normalized)
    }

    fun resumeTask(taskId: String): Boolean {
        val normalized = taskId.trim()
        if (normalized.isBlank()) {
            return false
        }
        return repository.resumePausedTask(normalized)
    }

    fun retryTask(taskId: String): Boolean {
        val normalized = taskId.trim()
        if (normalized.isBlank()) {
            return false
        }
        return repository.retryTask(normalized)
    }
}

class LocalAiModelRegistry(
    private val repository: LocalAiRepository,
) {
    fun compatibleInstalledModels(
        taskType: String,
        requestedModelId: String = "",
        requestedVersion: String = "",
    ): List<AiModelDescriptor> {
        val normalizedTaskType = AiTaskTypes.normalize(taskType)
        val installed = repository.listModels(installedOnly = true)
        if (requestedModelId.isNotBlank()) {
            val requested = if (requestedVersion.isBlank()) {
                repository.getModel(requestedModelId)
            } else {
                repository.getModel(requestedModelId, requestedVersion)
            }
            if (requested?.installed == true && supportsTask(requested, normalizedTaskType)) {
                return listOf(requested)
            }
            return emptyList()
        }
        return installed
            .filter { model -> supportsTask(model, normalizedTaskType) }
            .sortedWith(compareBy<AiModelDescriptor> { it.modelId }.thenBy { it.version })
    }

    private fun supportsTask(model: AiModelDescriptor, taskType: String): Boolean {
        if (model.supportedTasks.map(AiTaskTypes::normalize).contains(taskType)) {
            return true
        }
        val llama = model.metadata["llama_cpp"] as? Map<*, *>
        val qwenVlSemanticTasks = setOf(
            "captioning",
            "series_recognition",
            "character_recognition",
            "tag_prediction",
            "normalization",
        )
        return llama?.get("multimodal") == true && taskType in qwenVlSemanticTasks
    }
}

class LocalAiExecutionPlanner(
    private val modelRegistry: LocalAiModelRegistry,
    private val hardwareProvider: () -> AiHardwareProfile,
    private val memoryStateProvider: () -> Map<String, Any>,
    private val concurrentTaskProvider: () -> Int,
    private val availableBackendsProvider: () -> List<AiBackendRuntime>,
) {
    data class Plan(
        val model: AiModelDescriptor?,
        val runtimeCandidates: List<String>,
        val hardwarePreference: List<String>,
        val candidates: List<Map<String, Any>>,
    ) {
        fun toMap(): Map<String, Any> = mapOf(
            "model_id" to (model?.modelId ?: ""),
            "version" to (model?.version ?: ""),
            "runtime_candidates" to runtimeCandidates,
            "hardware_preference" to hardwarePreference,
            "candidates" to candidates,
        )
    }

    fun plan(taskType: String, requestedModelId: String = "", requestedVersion: String = ""): Plan {
        val profile = hardwareProvider()
        val hardwarePreference = buildList {
            if (profile.npuAvailable && profile.thermalStatus < 4 && !profile.batterySaverEnabled) add("npu")
            if (profile.gpuAvailable && profile.thermalStatus < 4) add("gpu")
            add("cpu")
        }
        val memory = memoryStateProvider()
        val concurrentTasks = concurrentTaskProvider()
        val scored = modelRegistry.compatibleInstalledModels(taskType, requestedModelId, requestedVersion)
            .filter(::hasExecutableBackend)
            .map { model -> model to scoreModel(model, taskType, profile, memory, concurrentTasks) }
            .sortedWith(compareByDescending<Pair<AiModelDescriptor, Int>> { it.second }.thenByDescending { it.first.updatedAtMs })
        val model = scored.firstOrNull()?.first
        val candidates = model?.let(::providersFor)
            ?.sortedByDescending { providerCapabilityScore(it.queryCapabilities(), profile) }
            ?.map { it.runtimeId }
            ?: emptyList()
        return Plan(
            model = model,
            runtimeCandidates = candidates,
            hardwarePreference = hardwarePreference,
            candidates = scored.map { (candidate, score) ->
                mapOf(
                    "model_id" to candidate.modelId,
                    "version" to candidate.version,
                    "score" to score,
                    "estimated_memory_bytes" to candidate.metadata["memory_requirement_bytes"].toLongValue(candidate.sizeBytes),
                )
            },
        )
    }

    private fun scoreModel(model: AiModelDescriptor, taskType: String, profile: AiHardwareProfile, memory: Map<String, Any>, concurrentTasks: Int): Int {
        val capabilityScore = if (model.supportedTasks.map(AiTaskTypes::normalize).contains(AiTaskTypes.normalize(taskType))) 30 else 0
        val quantizationScore = when (model.metadata["quantization"]?.toString()?.lowercase()) {
            "q4", "q4_k_m", "int8" -> 10
            "q5", "q6", "q8" -> 8
            "fp16" -> 6
            "fp32" -> 3
            else -> 4
        }
        val estimatedMemory = model.metadata["memory_requirement_bytes"].toLongValue(model.sizeBytes.coerceAtLeast(16L * 1024L * 1024L))
        val availableMemory = memory["available_bytes"].toLongValue(profile.availableRamBytes)
        val memoryScore = when {
            estimatedMemory <= availableMemory / 3L -> 18
            estimatedMemory <= availableMemory -> 8
            else -> -30
        }
        val benchmarkScore = ((model.metadata["benchmark_results"] as? Map<*, *>)?.get("score")).toIntValue(0).coerceIn(0, 20)
        val contextScore = (model.metadata["context_length"].toIntValue(0) / 512).coerceIn(0, 10)
        val backendScore = providersFor(model).maxOfOrNull { provider -> providerCapabilityScore(provider.queryCapabilities(), profile) } ?: 0
        val deviceScore = when {
            profile.thermalStatus >= 4 -> -20
            profile.batterySaverEnabled -> -10
            profile.charging -> 3
            else -> 0
        } - (concurrentTasks.coerceAtLeast(0) * 3).coerceAtMost(12)
        val fallbackPenalty = if (model.metadata["builtin"] == true || model.requiredRuntime.equals(AiRuntimeType.CUSTOM.raw, ignoreCase = true)) -40 else 0
        val modelIdentity = (model.modelId + " " + model.displayName + " " + model.installPath).lowercase()
        val imageEmbeddingPreference = if (AiTaskTypes.normalize(taskType) == "embedding_generation") {
            when {
                "nomic" in modelIdentity && "vision" in modelIdentity -> 30
                "nomic" in modelIdentity && "text" in modelIdentity -> -20
                else -> 0
            }
        } else {
            0
        }
        return capabilityScore + quantizationScore + memoryScore + benchmarkScore + contextScore + backendScore + deviceScore + fallbackPenalty + imageEmbeddingPreference
    }

    private fun hasExecutableBackend(model: AiModelDescriptor): Boolean = providersFor(model).isNotEmpty()

    private fun providersFor(model: AiModelDescriptor): List<AiRuntimeProvider> = availableBackendsProvider()
        .filterIsInstance<AiRuntimeProvider>()
        .filter { it.providerState == AiRuntimeProviderState.AVAILABLE && it.supportsModel(model) }

    private fun providerCapabilityScore(capability: AiRuntimeProviderCapabilities, profile: AiHardwareProfile): Int {
        val accelerationScore = when {
            profile.npuAvailable && "npu" in capability.supportedDevices && capability.supportedDelegates.any { it.contains("nnapi", ignoreCase = true) || it.contains("npu", ignoreCase = true) } -> 28
            profile.gpuAvailable && "gpu" in capability.supportedDevices -> 16
            "cpu" in capability.supportedDevices -> 8
            else -> 0
        }
        return accelerationScore + capability.supportedTasks.size.coerceAtMost(20)
    }

    private fun Any?.toLongValue(defaultValue: Long): Long = when (this) {
        is Number -> toLong()
        else -> toString()?.toLongOrNull() ?: defaultValue
    }

    private fun Any?.toIntValue(defaultValue: Int): Int = when (this) {
        is Number -> toInt()
        else -> toString()?.toIntOrNull() ?: defaultValue
    }
}

class LocalAiRuntimeSelector(
    private val hardwareProvider: () -> AiHardwareProfile,
) {
    fun select(
        task: AiTaskRecord,
        model: AiModelDescriptor?,
        availableBackends: List<AiBackendRuntime>,
    ): List<String> {
        val candidates = linkedSetOf<String>()
        val plannedCandidates = ((task.payload["execution_plan"] as? Map<*, *>)?.get("runtime_candidates") as? List<*>)
            ?.mapNotNull { it?.toString()?.trim()?.lowercase()?.takeIf(String::isNotBlank) }
            ?: emptyList()
        plannedCandidates.forEach(candidates::add)
        availableBackends.filterIsInstance<AiRuntimeProvider>()
            .filter { provider -> provider.providerState == AiRuntimeProviderState.AVAILABLE && (model == null || provider.supportsModel(model)) }
            .forEach { candidates += it.runtimeId }

        if (model == null) {
            availableBackends.filterNot { it is AiRuntimeProvider }.forEach { candidates += it.runtimeId }
        }

        return candidates.toList()
    }

}

class LocalAiBackendSelector(
    private val backendManager: LocalAiBackendManager,
) {
    data class Selection(
        val backend: AiBackendRuntime,
        val capability: AiBackendCapability,
        val runtimeId: String,
    )

    fun select(runtimeCandidates: List<String>, taskType: String, model: AiModelDescriptor? = null): Selection? {
        val normalizedTaskType = AiTaskTypes.normalize(taskType)
        val allBackends = backendManager.snapshotBackends()

        runtimeCandidates.forEach { candidate ->
            val runtimeMatches = backendManager.resolveByRuntime(candidate)
            runtimeMatches.forEach { backend ->
                if (backend is AiRuntimeProvider && backend.providerState != AiRuntimeProviderState.AVAILABLE) {
                    return@forEach
                }
                if (backend is ModelAwareAiBackend && model != null && !backend.supportsModel(model)) {
                    return@forEach
                }
                val capability = backend.detectCapabilities()
                if (capability.supportedTasks.isEmpty() || normalizedTaskType in capability.supportedTasks.map { AiTaskTypes.normalize(it) }.toSet()) {
                    return Selection(
                        backend = backend,
                        capability = capability,
                        runtimeId = candidate,
                    )
                }
            }
        }

        allBackends.forEach { backend ->
            if (backend is AiRuntimeProvider && backend.providerState != AiRuntimeProviderState.AVAILABLE) {
                return@forEach
            }
            if (backend is ModelAwareAiBackend && model != null && !backend.supportsModel(model)) {
                return@forEach
            }
            val capability = backend.detectCapabilities()
            if (capability.supportedTasks.isEmpty() || normalizedTaskType in capability.supportedTasks.map { AiTaskTypes.normalize(it) }.toSet()) {
                return Selection(
                    backend = backend,
                    capability = capability,
                    runtimeId = backend.runtimeId,
                )
            }
        }

        return null
    }
}

class LocalAiExecutionHistory(
    private val repository: LocalAiRepository,
) {
    fun startSession(context: AiExecutionContext): AiExecutionSessionRecord {
        val now = System.currentTimeMillis()
        val session = AiExecutionSessionRecord(
            sessionId = context.request.sessionId,
            taskId = context.task.taskId,
            taskType = context.task.taskType,
            modelId = context.task.modelId,
            version = context.task.version,
            runtimeId = context.runtimeId,
            backendId = context.backendId,
            status = "running",
            progress = 0.0,
            retryCount = context.task.retryCount,
            reservationBytes = context.reservationBytes,
            context = context.toMap(),
            result = emptyMap(),
            createdAtMs = now,
            updatedAtMs = now,
            startedAtMs = now,
            finishedAtMs = 0L,
            errorMessage = "",
        )
        repository.upsertExecutionSession(session)
        recordEvent(
            sessionId = session.sessionId,
            taskId = session.taskId,
            eventType = "session_started",
            message = "Execution session created",
            progress = 0.0,
            payload = mapOf(
                "runtime_id" to context.runtimeId,
                "backend_id" to context.backendId,
            ),
        )
        return session
    }

    fun recordEvent(
        sessionId: String,
        taskId: String,
        eventType: String,
        message: String,
        progress: Double,
        payload: Map<String, Any> = emptyMap(),
    ) {
        repository.appendExecutionEvent(
            AiExecutionEventRecord(
                eventId = 0L,
                sessionId = sessionId,
                taskId = taskId,
                eventType = eventType,
                message = message,
                progress = progress.coerceIn(0.0, 1.0),
                payload = payload,
                createdAtMs = System.currentTimeMillis(),
            ),
        )
    }

    fun updateSessionProgress(sessionId: String, progress: Double, message: String, payload: Map<String, Any>) {
        val current = repository.getExecutionSession(sessionId) ?: return
        val updated = current.copy(
            progress = progress.coerceIn(0.0, 1.0),
            updatedAtMs = System.currentTimeMillis(),
            result = current.result + mapOf(
                "last_message" to message,
                "last_payload" to payload,
            ),
        )
        repository.upsertExecutionSession(updated)
    }

    fun completeSession(
        sessionId: String,
        status: String,
        result: Map<String, Any>,
        errorMessage: String,
    ) {
        val current = repository.getExecutionSession(sessionId) ?: return
        val progress = if (status == "succeeded") 1.0 else current.progress
        val finishedAt = System.currentTimeMillis()
        repository.upsertExecutionSession(
            current.copy(
                status = status,
                progress = progress,
                result = result,
                updatedAtMs = finishedAt,
                finishedAtMs = finishedAt,
                errorMessage = errorMessage,
            ),
        )
        recordEvent(
            sessionId = sessionId,
            taskId = current.taskId,
            eventType = "session_completed",
            message = if (errorMessage.isBlank()) "Session completed" else errorMessage,
            progress = progress,
            payload = mapOf("status" to status),
        )
    }

    fun listSessions(limit: Int): List<AiExecutionSessionRecord> {
        return repository.listExecutionSessions(limit)
    }

    fun listEvents(sessionId: String, limit: Int): List<AiExecutionEventRecord> {
        return repository.listExecutionEvents(sessionId, limit)
    }
}

private class AiTaskCancelledException(message: String) : CancellationException(message)

private class AiTaskPausedException(message: String) : CancellationException(message)

class LocalAiProgressManager(
    private val queue: LocalAiExecutionQueue,
    private val history: LocalAiExecutionHistory,
) {
    fun reporter(taskId: String, sessionId: String): AiProgressReporter {
        return AiProgressReporter { progress, message ->
            val normalizedProgress = progress.coerceIn(0.0, 1.0)
            queue.updateTaskProgress(taskId, normalizedProgress, message)
            history.updateSessionProgress(
                sessionId = sessionId,
                progress = normalizedProgress,
                message = message,
                payload = mapOf("source" to "reporter"),
            )
            history.recordEvent(
                sessionId = sessionId,
                taskId = taskId,
                eventType = "progress",
                message = message,
                progress = normalizedProgress,
            )

            val task = queue.getTask(taskId) ?: return@AiProgressReporter
            if (task.cancellationRequested) {
                throw AiTaskCancelledException("Task cancelled by caller")
            }
            if (task.pauseRequested) {
                throw AiTaskPausedException("Task paused by caller")
            }
        }
    }
}

class LocalAiRetryManager(
    private val settingsProvider: () -> AiSettings,
) {
    fun decide(task: AiTaskRecord, status: String, message: String): AiRetryDecision {
        val normalizedStatus = status.trim().lowercase()
        if (task.retryCount >= task.maxRetries) {
            return AiRetryDecision(false, 0L, "max_retries_exceeded")
        }
        if (normalizedStatus in nonRetryableStatuses) {
            return AiRetryDecision(false, 0L, "non_retryable_status")
        }
        if (message.contains("unsupported", ignoreCase = true)) {
            return AiRetryDecision(false, 0L, "unsupported_capability")
        }

        val baseDelayMs = settingsProvider().schedulerPollIntervalMs.coerceAtLeast(250L) * 4L
        val exponent = task.retryCount.coerceAtMost(8)
        var backoffMs = baseDelayMs
        repeat(exponent) {
            backoffMs = (backoffMs * 2L).coerceAtMost(60_000L)
        }
        val nextRunAtMs = System.currentTimeMillis() + backoffMs
        return AiRetryDecision(true, nextRunAtMs, "retry_backoff_${backoffMs}ms")
    }

    private val nonRetryableStatuses = setOf(
        "cancelled",
        "paused",
        "invalid",
        "unsupported",
        "incompatible_model_version",
        "missing_outputs",
        "corrupted_outputs",
    )
}

class LocalAiResultValidator {
    fun validate(context: AiExecutionContext, result: AiExecutionResult): AiValidationReport {
        val issues = mutableListOf<AiValidationIssue>()

        if (!result.ok) {
            val normalizedStatus = result.status.trim().lowercase()
            when {
                normalizedStatus.contains("timeout") -> {
                    issues += AiValidationIssue("timeout", "Execution timed out")
                }

                normalizedStatus.contains("resource") || result.message.contains("out of memory", ignoreCase = true) -> {
                    issues += AiValidationIssue("resource_exhaustion", "Execution failed due to resource exhaustion")
                }

                normalizedStatus.contains("unsupported") -> {
                    issues += AiValidationIssue("unsupported_capabilities", "Execution failed due to unsupported capabilities")
                }

                normalizedStatus.contains("runtime") -> {
                    issues += AiValidationIssue("runtime_failures", result.message.ifBlank { "Runtime failure" })
                }

                else -> {
                    issues += AiValidationIssue("backend_failures", result.message.ifBlank { "Backend execution failed" })
                }
            }
            return AiValidationReport(valid = false, issues = issues)
        }

        if (result.details.isEmpty()) {
            issues += AiValidationIssue("missing_outputs", "Execution completed without result details")
            return AiValidationReport(valid = false, issues = issues)
        }

        val modelVersionFromResult = result.details["model_version"]?.toString().orEmpty()
        if (
            context.request.version.isNotBlank() &&
            modelVersionFromResult.isNotBlank() &&
            !modelVersionFromResult.equals(context.request.version, ignoreCase = true)
        ) {
            issues += AiValidationIssue(
                "incompatible_model_version",
                "Result model_version '$modelVersionFromResult' does not match requested version '${context.request.version}'",
            )
        }

        val outputs = (result.details["outputs"] as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: emptyList()
        val hasDirectResult = result.details.containsKey("result") || result.details.containsKey("output")
        if (outputs.isEmpty() && !hasDirectResult) {
            issues += AiValidationIssue("missing_outputs", "Execution result has no outputs")
        }

        outputs.forEachIndexed { index, rawOutput ->
            val output = rawOutput.entries
                .filter { it.key != null }
                .associate { it.key.toString() to (it.value ?: "") }

            val outputPath = output["path"]?.toString()?.trim().orEmpty()
            if (outputPath.isBlank()) {
                issues += AiValidationIssue("invalid_outputs", "Output at index $index is missing path")
                return@forEachIndexed
            }

            val file = File(outputPath)
            if (!file.exists()) {
                issues += AiValidationIssue("missing_outputs", "Output file does not exist: $outputPath")
                return@forEachIndexed
            }

            val expectedSize = output["size_bytes"]?.toString()?.toLongOrNull()
            if (expectedSize != null && expectedSize > 0 && expectedSize != file.length()) {
                issues += AiValidationIssue("corrupted_outputs", "Output file size mismatch for $outputPath")
            }

            val expectedHash = output["sha256"]?.toString()?.trim().orEmpty().lowercase()
            if (expectedHash.isNotBlank()) {
                val actualHash = sha256Hex(file)
                if (!expectedHash.equals(actualHash, ignoreCase = true)) {
                    issues += AiValidationIssue("corrupted_outputs", "Output file hash mismatch for $outputPath")
                }
            }
        }

        validateTaskSpecificDetails(
            taskType = AiTaskTypes.normalize(context.task.taskType),
            details = result.details,
            issues = issues,
        )

        return AiValidationReport(
            valid = issues.none { it.severity == "error" },
            issues = issues,
            metadata = mapOf(
                "task_id" to context.task.taskId,
                "session_id" to context.request.sessionId,
            ),
        )
    }

    private fun validateTaskSpecificDetails(
        taskType: String,
        details: Map<String, Any>,
        issues: MutableList<AiValidationIssue>,
    ) {
        val result = details["result"].asStringAnyMap()
        when (taskType) {
            "embedding_generation" -> {
                val embedding = (details["embedding"] as? List<*>)?.mapNotNull { it as? Number } ?: emptyList()
                if (embedding.isEmpty()) {
                    issues += AiValidationIssue("missing_outputs", "Embedding generation result is missing embedding vector")
                }
            }

            "similarity_search" -> {
                val hasMatches = details.containsKey("matches") || result.containsKey("matches")
                if (!hasMatches) {
                    issues += AiValidationIssue("missing_outputs", "Similarity search result is missing matches")
                }
            }

            "ocr" -> {
                // Empty OCR text is a valid outcome for an image with no readable text.
                // The runtime only needs to expose the text field; blank content must not
                // fail an autonomous workflow.
                val hasTextField = result.containsKey("text") || details.containsKey("text")
                if (!hasTextField) {
                    issues += AiValidationIssue("missing_outputs", "OCR result is missing the text field")
                }
            }

            "captioning" -> {
                val caption = result["caption"]?.toString().orEmpty().ifBlank { details["caption"]?.toString().orEmpty() }
                if (caption.isBlank()) {
                    issues += AiValidationIssue("missing_outputs", "Captioning result is missing caption text")
                }
            }

            "character_recognition" -> {
                if (!result.containsKey("subjects")) {
                    issues += AiValidationIssue("missing_outputs", "Character observation result is missing subjects")
                }
            }

            "series_recognition",
            "artist_recognition" -> {
                val topMatch = result["top_match"]?.toString().orEmpty()
                val candidates = result["candidates"].asListOfMaps()
                if (topMatch.isBlank() && candidates.isEmpty()) {
                    issues += AiValidationIssue("missing_outputs", "$taskType result has no top_match or candidates")
                }
            }

            "tag_prediction" -> {
                val tags = result["tags"].asStringList()
                if (tags.isEmpty()) {
                    issues += AiValidationIssue("missing_outputs", "Tag prediction result is missing predicted tags")
                }
            }

            "metadata_extraction" -> {
                val metadata = result["metadata"].asStringAnyMap()
                if (metadata.isEmpty()) {
                    issues += AiValidationIssue("missing_outputs", "Metadata extraction result is missing metadata payload")
                }
            }

            "prompt_generation" -> {
                val prompt = result["prompt"]?.toString().orEmpty()
                if (prompt.isBlank()) {
                    issues += AiValidationIssue("missing_outputs", "Prompt generation result is missing prompt text")
                }
            }

            "duplicate_detection" -> {
                if (!result.containsKey("groups")) {
                    issues += AiValidationIssue("missing_outputs", "Duplicate detection result is missing duplicate groups")
                }
            }

            "classification" -> {
                val label = result["label"]?.toString().orEmpty()
                val ranked = result["ranked_labels"].asListOfMaps()
                if (label.isBlank() && ranked.isEmpty()) {
                    issues += AiValidationIssue("missing_outputs", "Classification result is missing labels")
                }
            }

            "detection", "face_detection" -> {
                if (!result.containsKey("detections") && !result.containsKey("faces")) {
                    issues += AiValidationIssue("missing_outputs", "Detection result is missing detection list")
                }
            }

            "face_feature_extraction" -> {
                val features = result["feature_vector"] as? List<*>
                if (features.isNullOrEmpty()) {
                    issues += AiValidationIssue("missing_outputs", "Face feature extraction result is missing feature vector")
                }
            }

            "knowledge_pack_execution" -> {
                val packId = result["knowledge_pack_id"]?.toString().orEmpty()
                val operations = result["operations"].asListOfMaps()
                if (packId.isBlank() || operations.isEmpty()) {
                    issues += AiValidationIssue(
                        "missing_outputs",
                        "Knowledge pack execution result is missing pack identifier or operations",
                    )
                }
            }
        }
    }

    private fun Any?.asStringAnyMap(): Map<String, Any> {
        val source = this as? Map<*, *> ?: return emptyMap()
        val result = linkedMapOf<String, Any>()
        source.forEach { (keyRaw, value) ->
            val key = keyRaw?.toString()?.trim().orEmpty()
            if (key.isBlank() || value == null) {
                return@forEach
            }
            result[key] = value
        }
        return result
    }

    private fun Any?.asListOfMaps(): List<Map<String, Any>> {
        val values = this as? List<*> ?: return emptyList()
        return values.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val normalized = linkedMapOf<String, Any>()
            map.forEach { (keyRaw, value) ->
                val key = keyRaw?.toString()?.trim().orEmpty()
                if (key.isBlank() || value == null) {
                    return@forEach
                }
                normalized[key] = value
            }
            normalized
        }
    }

    private fun Any?.asStringList(): List<String> {
        return when (this) {
            is List<*> -> this.mapNotNull { it?.toString()?.trim() }.filter { it.isNotBlank() }
            is String -> this
                .split(',', '|')
                .map { it.trim() }
                .filter { it.isNotBlank() }

            else -> emptyList()
        }
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
}

class LocalAiExecutionCache(
    context: Context,
    private val modelCache: LocalAiModelCache,
) {
    private val cacheRoot = File(context.filesDir, "local_ai_execution_cache").apply { mkdirs() }

    fun lookup(context: AiExecutionContext): AiExecutionResult? {
        val key = resultCacheKey(context)
        val entry = modelCache.getEntry(key) ?: return null
        val metadataFile = File(entry.artifactPath)
        if (!metadataFile.exists()) {
            modelCache.removeEntry(key)
            return null
        }

        val payload = runCatching {
            LocalAiJson.decodeMap(metadataFile.readText())
        }.getOrElse {
            modelCache.removeEntry(key)
            return null
        }
        modelCache.touch(key)

        val details = payload["details"].toStringMap()
        return AiExecutionResult(
            ok = payload["ok"].toBooleanValue(defaultValue = false),
            status = payload["status"]?.toString().orEmpty(),
            message = payload["message"]?.toString().orEmpty(),
            details = details + mapOf("cache_hit" to true),
        )
    }

    fun store(context: AiExecutionContext, result: AiExecutionResult): Map<String, Any> {
        val key = resultCacheKey(context)
        val now = System.currentTimeMillis()
        val metadataFile = File(cacheRoot, "$key.json")
        val payload = linkedMapOf<String, Any>(
            "ok" to result.ok,
            "status" to result.status,
            "message" to result.message,
            "details" to result.details,
            "cached_at_ms" to now,
        )
        metadataFile.writeText(LocalAiJson.encodeMap(payload))

        modelCache.upsertEntry(
            modelId = context.task.modelId.ifBlank { "_global" },
            cacheKey = key,
            artifactPath = metadataFile.absolutePath,
            sizeBytes = metadataFile.length(),
            pinned = false,
            metadata = mapOf(
                "cache_type" to "execution_result",
                "task_type" to context.task.taskType,
                "session_id" to context.request.sessionId,
            ),
        )

        cacheEmbeddingArtifacts(context, result)
        cacheTensorArtifacts(context, result)
        cacheExecutionMetadata(context, metadataFile)
        cacheTaskArtifacts(context, result)

        return mapOf(
            "cache_key" to key,
            "artifact_path" to metadataFile.absolutePath,
            "size_bytes" to metadataFile.length(),
        )
    }

    private fun cacheEmbeddingArtifacts(context: AiExecutionContext, result: AiExecutionResult) {
        val embeddingVector = result.details["embedding"] as? List<*>
        if (embeddingVector == null || embeddingVector.isEmpty()) {
            return
        }
        val file = File(cacheRoot, "embedding_${context.request.sessionId}.json")
        file.writeText(LocalAiJson.encodeMap(mapOf("embedding" to embeddingVector)))
        modelCache.upsertEntry(
            modelId = context.task.modelId.ifBlank { "_global" },
            cacheKey = "embedding:${context.task.taskId}",
            artifactPath = file.absolutePath,
            sizeBytes = file.length(),
            metadata = mapOf(
                "cache_type" to "embeddings",
                "task_type" to context.task.taskType,
            ),
        )
    }

    private fun cacheTensorArtifacts(context: AiExecutionContext, result: AiExecutionResult) {
        val tensors = (result.details["intermediate_tensors"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        tensors.forEachIndexed { index, tensorPath ->
            val file = File(tensorPath)
            if (!file.exists()) {
                return@forEachIndexed
            }
            modelCache.upsertEntry(
                modelId = context.task.modelId.ifBlank { "_global" },
                cacheKey = "tensor:${context.task.taskId}:$index",
                artifactPath = file.absolutePath,
                sizeBytes = file.length(),
                metadata = mapOf(
                    "cache_type" to "intermediate_tensors",
                    "task_type" to context.task.taskType,
                ),
            )
        }
    }

    private fun cacheExecutionMetadata(context: AiExecutionContext, metadataFile: File) {
        modelCache.upsertEntry(
            modelId = context.task.modelId.ifBlank { "_global" },
            cacheKey = "execution_metadata:${context.request.sessionId}",
            artifactPath = metadataFile.absolutePath,
            sizeBytes = metadataFile.length(),
            metadata = mapOf(
                "cache_type" to "execution_metadata",
                "session_id" to context.request.sessionId,
                "runtime_id" to context.runtimeId,
            ),
        )
    }

    private fun cacheTaskArtifacts(context: AiExecutionContext, result: AiExecutionResult) {
        val normalizedTaskType = AiTaskTypes.normalize(context.task.taskType)
        val supported = setOf(
            "ocr",
            "captioning",
            "character_recognition",
            "series_recognition",
            "artist_recognition",
            "tag_prediction",
            "metadata_extraction",
            "prompt_generation",
            "duplicate_detection",
            "classification",
            "detection",
            "face_feature_extraction",
            "knowledge_pack_execution",
        )
        if (normalizedTaskType !in supported) {
            return
        }

        val artifactPayload = linkedMapOf<String, Any>(
            "task_id" to context.task.taskId,
            "task_type" to normalizedTaskType,
            "session_id" to context.request.sessionId,
            "model_id" to context.task.modelId,
            "model_version" to context.task.version,
            "runtime_id" to context.runtimeId,
            "payload" to context.task.payload,
            "result" to result.details,
            "cached_at_ms" to System.currentTimeMillis(),
        )
        val file = File(cacheRoot, "task_${normalizedTaskType}_${context.request.sessionId}.json")
        file.writeText(LocalAiJson.encodeMap(artifactPayload))

        val cacheType = when (normalizedTaskType) {
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

        modelCache.upsertEntry(
            modelId = context.task.modelId.ifBlank { "_global" },
            cacheKey = "task_output:${normalizedTaskType}:${context.task.taskId}",
            artifactPath = file.absolutePath,
            sizeBytes = file.length(),
            pinned = false,
            metadata = mapOf(
                "cache_type" to cacheType,
                "task_type" to normalizedTaskType,
                "session_id" to context.request.sessionId,
            ),
        )

        val imageId = context.task.payload["image_id"].toIntOrNullValue()
        if (imageId != null) {
            modelCache.upsertEntry(
                modelId = context.task.modelId.ifBlank { "_global" },
                cacheKey = "task_output_latest:${normalizedTaskType}:image:$imageId",
                artifactPath = file.absolutePath,
                sizeBytes = file.length(),
                pinned = false,
                metadata = mapOf(
                    "cache_type" to "${cacheType}_latest",
                    "task_type" to normalizedTaskType,
                    "image_id" to imageId,
                ),
            )
        }
    }

    private fun resultCacheKey(context: AiExecutionContext): String {
        val canonicalPayload = LocalAiJson.encodeMap(
            mapOf(
                "task_type" to context.task.taskType,
                "model_id" to context.task.modelId,
                "version" to context.task.version,
                "runtime_id" to context.runtimeId,
                "payload" to context.task.payload,
            ),
        )
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(canonicalPayload.toByteArray(Charsets.UTF_8))
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return "execution_result:$hash"
    }

    private fun Any?.toStringMap(): Map<String, Any> {
        return when (this) {
            is Map<*, *> -> this.entries
                .filter { it.key != null }
                .associate { it.key.toString() to (it.value ?: "") }
            else -> emptyMap()
        }
    }

    private fun Any?.toBooleanValue(defaultValue: Boolean): Boolean {
        return when (this) {
            is Boolean -> this
            is Number -> this.toInt() != 0
            else -> this?.toString()?.equals("true", ignoreCase = true) ?: defaultValue
        }
    }

    private fun Any?.toIntOrNullValue(): Int? {
        return when (this) {
            is Number -> this.toInt()
            else -> this?.toString()?.toIntOrNull()
        }
    }
}

class LocalAiSessionCache(
    private val modelCache: LocalAiModelCache,
) {
    fun markModelLoaded(model: AiModelDescriptor, runtimeId: String, refCount: Int) {
        val cacheKey = "loaded_model:${model.modelId}:${model.version}:${runtimeId.lowercase()}"
        modelCache.upsertEntry(
            modelId = model.modelId,
            cacheKey = cacheKey,
            artifactPath = model.installPath.ifBlank { "memory://model/${model.modelId}/${model.version}" },
            sizeBytes = model.sizeBytes.coerceAtLeast(0L),
            pinned = refCount > 0,
            metadata = mapOf(
                "cache_type" to "loaded_models",
                "runtime_id" to runtimeId,
                "ref_count" to refCount,
            ),
        )
    }

    fun clearModelLoaded(model: AiModelDescriptor, runtimeId: String) {
        val cacheKey = "loaded_model:${model.modelId}:${model.version}:${runtimeId.lowercase()}"
        modelCache.removeEntry(cacheKey)
    }

    fun markModelWarm(model: AiModelDescriptor, runtimeId: String, idleTimeoutMs: Long) {
        val cacheKey = "loaded_model:${model.modelId}:${model.version}:${runtimeId.lowercase()}"
        modelCache.upsertEntry(
            modelId = model.modelId,
            cacheKey = cacheKey,
            artifactPath = model.installPath.ifBlank { "memory://model/${model.modelId}/${model.version}" },
            sizeBytes = model.sizeBytes.coerceAtLeast(0L),
            pinned = false,
            metadata = mapOf(
                "cache_type" to "loaded_models",
                "runtime_id" to runtimeId,
                "warm" to true,
                "warm_until_ms" to (System.currentTimeMillis() + idleTimeoutMs),
            ),
        )
    }

    fun markCompiledSession(sessionId: String, modelId: String, runtimeId: String, backendId: String) {
        val cacheKey = "compiled_session:$sessionId"
        modelCache.upsertEntry(
            modelId = modelId.ifBlank { "_global" },
            cacheKey = cacheKey,
            artifactPath = "memory://session/$sessionId",
            sizeBytes = 0L,
            pinned = false,
            metadata = mapOf(
                "cache_type" to "compiled_sessions",
                "runtime_id" to runtimeId,
                "backend_id" to backendId,
            ),
        )
    }

    fun clearCompiledSession(sessionId: String) {
        modelCache.removeEntry("compiled_session:$sessionId")
    }
}

class LocalAiMemoryManager(
    private val hardwareProvider: () -> AiHardwareProfile,
    private val settingsProvider: () -> AiSettings,
    private val cacheBytesProvider: () -> Long = { 0L },
) {
    private val reservationMutex = Mutex()
    private val reservations = linkedMapOf<String, Long>()
    @Volatile private var reservedBytesSnapshot = 0L
    @Volatile private var reservationCountSnapshot = 0

    suspend fun reserve(sessionId: String, requestedBytes: Long): Long? {
        return reservationMutex.withLock {
            val normalizedRequested = requestedBytes.coerceAtLeast(1L)
            val budgetBytes = computeReservationBudgetBytes()
            val usedBytes = reservations.values.sum()
            val availableBytes = (budgetBytes - usedBytes).coerceAtLeast(0L)
            if (normalizedRequested > availableBytes) {
                return@withLock null
            }
            reservations[sessionId] = normalizedRequested
            reservedBytesSnapshot = reservations.values.sum()
            reservationCountSnapshot = reservations.size
            normalizedRequested
        }
    }

    suspend fun release(sessionId: String) {
        reservationMutex.withLock {
            reservations.remove(sessionId)
            reservedBytesSnapshot = reservations.values.sum()
            reservationCountSnapshot = reservations.size
        }
    }

    suspend fun snapshot(): Map<String, Any> {
        return reservationMutex.withLock {
            val budgetBytes = computeReservationBudgetBytes()
            val usedBytes = reservations.values.sum()
            mapOf(
                "budget_bytes" to budgetBytes,
                "used_bytes" to usedBytes,
                "available_bytes" to (budgetBytes - usedBytes).coerceAtLeast(0L),
                "active_reservations" to reservations.size,
                "temporary_allocation_bytes" to usedBytes,
                "native_heap_bytes" to Debug.getNativeHeapAllocatedSize(),
                "cache_bytes" to cacheBytesProvider(),
            )
        }
    }

    fun currentState(): Map<String, Any> {
        val budgetBytes = computeReservationBudgetBytes()
        return mapOf(
            "budget_bytes" to budgetBytes,
            "used_bytes" to reservedBytesSnapshot,
            "available_bytes" to (budgetBytes - reservedBytesSnapshot).coerceAtLeast(0L),
            "active_reservations" to reservationCountSnapshot,
            "temporary_allocation_bytes" to reservedBytesSnapshot,
            "native_heap_bytes" to Debug.getNativeHeapAllocatedSize(),
            "cache_bytes" to cacheBytesProvider(),
        )
    }

    private fun computeReservationBudgetBytes(): Long {
        val profile = hardwareProvider()
        val configured = settingsProvider().maxReservedRamBytes
        // availMem includes reclaimable cache, but it is not an allowance to
        // consume every byte. Preserve at least 25% (or 256 MiB) for Android
        // and other apps. The configured cap can lower, never raise, this limit.
        val available = profile.availableRamBytes.coerceAtLeast(0L)
        val systemHeadroom = (available / 4L).coerceAtLeast(256L * 1024L * 1024L)
        val safeBudget = (available - systemHeadroom).coerceAtLeast(0L)
        return if (configured > 0L) configured.coerceAtMost(safeBudget) else safeBudget
    }
}

class LocalAiModelLifetimeManager(
    private val sessionCache: LocalAiSessionCache,
    private val hardwareProvider: () -> AiHardwareProfile,
    private val memoryStateProvider: () -> Map<String, Any>,
    private val scope: CoroutineScope,
) {
    private val lifetimeMutex = Mutex()
    private val refCounts = linkedMapOf<String, Int>()

    suspend fun acquire(model: AiModelDescriptor?, runtimeId: String): Int {
        if (model == null) {
            return 0
        }
        return lifetimeMutex.withLock {
            val key = keyOf(model, runtimeId)
            val refCount = (refCounts[key] ?: 0) + 1
            refCounts[key] = refCount
            sessionCache.markModelLoaded(model, runtimeId, refCount)
            refCount
        }
    }

    suspend fun release(model: AiModelDescriptor?, runtimeId: String): Int {
        if (model == null) {
            return 0
        }
        return lifetimeMutex.withLock {
            val key = keyOf(model, runtimeId)
            val current = (refCounts[key] ?: 0).coerceAtLeast(0)
            val next = (current - 1).coerceAtLeast(0)
            if (next <= 0) {
                refCounts.remove(key)
                if (shouldKeepWarm(model)) {
                    sessionCache.markModelWarm(model, runtimeId, WARM_MODEL_IDLE_TIMEOUT_MS)
                    scope.launch {
                        delay(WARM_MODEL_IDLE_TIMEOUT_MS)
                        lifetimeMutex.withLock {
                            if ((refCounts[key] ?: 0) == 0) sessionCache.clearModelLoaded(model, runtimeId)
                        }
                    }
                } else {
                    sessionCache.clearModelLoaded(model, runtimeId)
                }
            } else {
                refCounts[key] = next
                sessionCache.markModelLoaded(model, runtimeId, next)
            }
            next
        }
    }

    private fun keyOf(model: AiModelDescriptor, runtimeId: String): String {
        return "${model.modelId}:${model.version}:${runtimeId.lowercase()}"
    }

    private fun shouldKeepWarm(model: AiModelDescriptor): Boolean {
        val profile = hardwareProvider()
        val availableBytes = memoryStateProvider()["available_bytes"]?.toString()?.toLongOrNull() ?: 0L
        val modelBytes = model.metadata["memory_requirement_bytes"]?.toString()?.toLongOrNull()
            ?: model.sizeBytes.coerceAtLeast(16L * 1024L * 1024L)
        return profile.thermalStatus < 4 && availableBytes >= modelBytes * 2L
    }

    private companion object {
        const val WARM_MODEL_IDLE_TIMEOUT_MS = 60_000L
    }
}

class LocalAiRuntimeHealthMonitor(
    private val repository: LocalAiRepository,
) {
    suspend fun capture(backend: AiBackendRuntime, phase: String): AiRuntimeHealthSnapshot {
        val startedAtMs = System.currentTimeMillis()
        val backendHealth = runCatching { backend.healthCheck() }.getOrElse { error ->
            AiBackendHealth(
                healthy = false,
                status = "error",
                latencyMs = 0L,
                metadata = mapOf("error" to (error.message ?: error.javaClass.simpleName)),
            )
        }

        val capturedAtMs = System.currentTimeMillis()
        val snapshot = AiRuntimeHealthSnapshot(
            snapshotId = 0L,
            runtimeId = backend.runtimeType.raw,
            backendId = backend.runtimeId,
            status = "$phase:${backendHealth.status}",
            healthy = backendHealth.healthy,
            latencyMs = (capturedAtMs - startedAtMs).coerceAtLeast(backendHealth.latencyMs),
            metadata = backendHealth.metadata + mapOf(
                "phase" to phase,
                "capability" to backend.detectCapabilities().toMap(),
            ),
            capturedAtMs = capturedAtMs,
        )
        repository.saveRuntimeHealthSnapshot(snapshot)
        return snapshot
    }

    fun listSnapshots(limit: Int = 200): List<AiRuntimeHealthSnapshot> {
        return repository.listRuntimeHealthSnapshots(limit)
    }
}

class LocalAiTaskDispatcher(
    private val queue: LocalAiExecutionQueue,
    private val repository: LocalAiRepository,
    private val backendManager: LocalAiBackendManager,
    private val runtimeSelector: LocalAiRuntimeSelector,
    private val backendSelector: LocalAiBackendSelector,
    private val runtimeGateway: LocalAiRuntimeGateway,
    private val validationService: LocalAiValidationService,
    private val resultValidator: LocalAiResultValidator,
    private val progressManager: LocalAiProgressManager,
    private val retryManager: LocalAiRetryManager,
    private val executionHistory: LocalAiExecutionHistory,
    private val executionCache: LocalAiExecutionCache,
    private val sessionCache: LocalAiSessionCache,
    private val memoryManager: LocalAiMemoryManager,
    private val modelLifetimeManager: LocalAiModelLifetimeManager,
    private val runtimeHealthMonitor: LocalAiRuntimeHealthMonitor,
    private val settingsProvider: () -> AiSettings,
) {
    private data class ActiveExecution(
        val backend: AiBackendRuntime,
        val sessionId: String,
    )

    private val activeExecutions = ConcurrentHashMap<String, ActiveExecution>()

    suspend fun requestCancellation(taskId: String): Boolean {
        val active = activeExecutions[taskId.trim()] ?: return false
        return active.backend.requestCancellation(active.sessionId)
    }

    suspend fun dispatch(task: AiTaskRecord) {
        val currentTask = queue.getTask(task.taskId) ?: return
        if (currentTask.status != "pending") {
            return
        }
        if (!queue.markTaskRunning(currentTask.taskId)) {
            return
        }

        val runningTask = queue.getTask(currentTask.taskId) ?: return
        val model = resolveTaskModel(runningTask)
        val availableBackends = backendManager.snapshotBackends()
        val runtimeCandidates = runtimeSelector.select(runningTask, model, availableBackends)
        val backendSelection = backendSelector.select(runtimeCandidates, runningTask.taskType, model)

        val sessionId = UUID.randomUUID().toString()
        queue.setTaskSession(runningTask.taskId, sessionId)

        var activeBackend: AiBackendRuntime? = backendSelection?.backend
        var memoryReservationBytes = 0L
        var context: AiExecutionContext? = null
        var keepCompiledSessionCache = false

        try {
            if (AiTaskTypes.isExecutionTask(runningTask.taskType) && backendSelection == null) {
                handleFailure(
                    task = runningTask,
                    sessionId = sessionId,
                    status = "unsupported",
                    message = "No compatible backend is registered for task ${runningTask.taskType}",
                    details = mapOf("runtime_candidates" to runtimeCandidates),
                )
                return
            }

            if (!AiTaskTypes.isInfrastructureTask(runningTask.taskType) && backendSelection == null) {
                handleFailure(
                    task = runningTask,
                    sessionId = sessionId,
                    status = "unsupported",
                    message = "No backend resolved for task ${runningTask.taskType}",
                    details = mapOf("runtime_candidates" to runtimeCandidates),
                )
                return
            }

            if (AiTaskTypes.isInfrastructureTask(runningTask.taskType)) {
                val pseudoContext = AiExecutionContext(
                    request = AiExecutionRequest(
                        sessionId = sessionId,
                        taskId = runningTask.taskId,
                        taskType = runningTask.taskType,
                        modelId = runningTask.modelId,
                        version = runningTask.version,
                        runtimeHint = runningTask.runtimeHint,
                        attempt = runningTask.retryCount,
                        deadlineAtMs = System.currentTimeMillis() + runningTask.timeoutMs,
                        payload = runningTask.payload,
                    ),
                    task = runningTask,
                    model = model,
                    runtimeId = "infrastructure",
                    runtimeType = AiRuntimeType.CUSTOM,
                    backendId = "local_ai_manager",
                    reservationBytes = 0L,
                    startedAtMs = System.currentTimeMillis(),
                    metadata = mapOf("mode" to "infrastructure"),
                )
                executionHistory.startSession(pseudoContext)
                executionHistory.recordEvent(
                    sessionId = sessionId,
                    taskId = runningTask.taskId,
                    eventType = "infrastructure_task",
                    message = "Infrastructure task executed by manager",
                    progress = 1.0,
                )
                val result = AiExecutionResult(
                    ok = true,
                    status = "completed",
                    message = "Infrastructure task completed",
                    details = mapOf("task_type" to runningTask.taskType),
                )
                queue.markTaskCompleted(
                    taskId = runningTask.taskId,
                    status = "succeeded",
                    result = result.details + mapOf(
                        "status" to result.status,
                        "message" to result.message,
                        "session_id" to sessionId,
                    ),
                )
                executionHistory.completeSession(
                    sessionId = sessionId,
                    status = "succeeded",
                    result = result.details,
                    errorMessage = "",
                )
                return
            }

            val runtimeId = backendSelection?.runtimeId ?: ""
            val backendCapability = backendSelection?.capability ?: AiBackendCapability(
                runtimeId = "",
                runtimeType = AiRuntimeType.CUSTOM,
                supportedTasks = emptySet(),
                supportsCancellation = false,
                supportsPauseResume = false,
                maxConcurrentTasks = 1,
            )

            val compatibilityReport = validationService.validateModelCompatibility(
                task = runningTask,
                model = model,
                runtimeId = runtimeId,
                backendCapability = backendCapability,
            )
            if (!compatibilityReport.valid) {
                handleFailure(
                    task = runningTask,
                    sessionId = sessionId,
                    status = "invalid",
                    message = "Model/runtime compatibility validation failed",
                    details = mapOf("validation" to compatibilityReport.toMap()),
                )
                return
            }

            val reservationRequestBytes = estimateReservationBytes(runningTask, model)
            val reservation = memoryManager.reserve(sessionId, reservationRequestBytes)
            if (reservation == null) {
                val memoryState = memoryManager.currentState()
                val availableBytes = (memoryState["available_bytes"] as? Long) ?: 0L
                val mib = 1024L * 1024L
                handleFailure(
                    task = runningTask,
                    sessionId = sessionId,
                    status = "resource_exhaustion",
                    message = "Insufficient RAM reservation for ${runningTask.taskType}: " +
                        "${reservationRequestBytes / mib} MiB required, ${availableBytes / mib} MiB available. " +
                        "Close memory-intensive apps or select a smaller model.",
                    details = mapOf(
                        "requested_bytes" to reservationRequestBytes,
                        "memory" to memoryState,
                    ),
                )
                return
            }
            memoryReservationBytes = reservation

            val timeoutMs = if (runningTask.timeoutMs > 0L) {
                runningTask.timeoutMs
            } else {
                settingsProvider().defaultTaskTimeoutMs
            }
            val executionRequest = AiExecutionRequest(
                sessionId = sessionId,
                taskId = runningTask.taskId,
                taskType = runningTask.taskType,
                modelId = runningTask.modelId,
                version = runningTask.version,
                runtimeHint = runningTask.runtimeHint,
                attempt = runningTask.retryCount,
                deadlineAtMs = System.currentTimeMillis() + timeoutMs,
                payload = runningTask.payload,
            )
            context = AiExecutionContext(
                request = executionRequest,
                task = runningTask,
                model = model,
                runtimeId = runtimeId,
                runtimeType = backendSelection?.backend?.runtimeType ?: AiRuntimeType.CUSTOM,
                backendId = backendSelection?.backend?.runtimeId ?: "",
                reservationBytes = memoryReservationBytes,
                startedAtMs = System.currentTimeMillis(),
                metadata = mapOf(
                    "runtime_candidates" to runtimeCandidates,
                    "backend_capability" to backendCapability.toMap(),
                ),
            )

            executionHistory.startSession(context)
            runtimeHealthMonitor.capture(backendSelection!!.backend, phase = "before_execute")
            modelLifetimeManager.acquire(model, runtimeId)

            val cachedResult = executionCache.lookup(context)
            if (cachedResult != null) {
                queue.markTaskCompleted(
                    taskId = runningTask.taskId,
                    status = "succeeded",
                    result = cachedResult.details + mapOf(
                        "status" to cachedResult.status,
                        "message" to cachedResult.message,
                        "session_id" to sessionId,
                        "cache_hit" to true,
                    ),
                )
                executionHistory.recordEvent(
                    sessionId = sessionId,
                    taskId = runningTask.taskId,
                    eventType = "cache_hit",
                    message = "Execution served from result cache",
                    progress = 1.0,
                )
                executionHistory.completeSession(
                    sessionId = sessionId,
                    status = "succeeded",
                    result = cachedResult.details,
                    errorMessage = "",
                )
                sessionCache.markCompiledSession(sessionId, runningTask.modelId, runtimeId, backendSelection.backend.runtimeId)
                keepCompiledSessionCache = true
                runtimeHealthMonitor.capture(backendSelection.backend, phase = "after_execute")
                return
            }

            val progressReporter = progressManager.reporter(runningTask.taskId, sessionId)
            activeBackend?.let { backend ->
                activeExecutions[runningTask.taskId] = ActiveExecution(backend, sessionId)
            }
            val latestBeforeExecute = queue.getTask(runningTask.taskId)
            if (latestBeforeExecute?.cancellationRequested == true) {
                throw AiTaskCancelledException("Task cancelled by caller")
            }
            if (latestBeforeExecute?.pauseRequested == true) {
                throw AiTaskPausedException("Task paused by caller")
            }
            val runtimeResult = try {
                runtimeGateway.execute(
                    request = executionRequest,
                    backend = backendSelection.backend,
                    reporter = progressReporter,
                )
            } catch (e: Exception) {
                // A native backend may surface cancellation as a runtime
                // exception after requestCancellation() interrupts generation.
                // Preserve the caller's intent instead of turning Stop into a
                // retryable backend failure.
                val latestTask = queue.getTask(runningTask.taskId)
                if (latestTask?.cancellationRequested == true) {
                    throw AiTaskCancelledException("Task cancelled by caller")
                }
                if (latestTask?.pauseRequested == true) {
                    throw AiTaskPausedException("Task paused by caller")
                }

                // Non-fatal runtime exceptions during backend execution should mark the task failed
                // and preserve diagnostic information rather than crash the scheduler.
                val message = e.message ?: e.javaClass.simpleName
                handleFailure(
                    task = runningTask,
                    sessionId = sessionId,
                    status = "runtime_failure",
                    message = "Runtime execution error: $message",
                    details = mapOf("exception" to e.javaClass.name, "stack" to e.stackTraceToString()),
                )
                runtimeHealthMonitor.capture(backendSelection.backend, phase = "after_execute_error")
                // release reservation and model lifetime in finally section
                return
            }

            val latestTask = queue.getTask(runningTask.taskId)
            if (latestTask?.cancellationRequested == true) {
                throw AiTaskCancelledException("Task cancelled by caller")
            }
            if (latestTask?.pauseRequested == true) {
                throw AiTaskPausedException("Task paused by caller")
            }
            runtimeHealthMonitor.capture(backendSelection.backend, phase = "after_execute")

            val resultValidation = resultValidator.validate(context, runtimeResult)
            if (!resultValidation.valid) {
                val validationMessage = resultValidation.issues.joinToString("; ") { it.message }
                handleFailure(
                    task = runningTask,
                    sessionId = sessionId,
                    status = if (runtimeResult.ok) "invalid_output" else runtimeResult.status,
                    message = validationMessage.ifBlank { runtimeResult.message },
                    details = mapOf(
                        "runtime_result" to runtimeResult.details,
                        "validation" to resultValidation.toMap(),
                    ),
                )
                return
            }

            val cacheReceipt = executionCache.store(context, runtimeResult)
            sessionCache.markCompiledSession(sessionId, runningTask.modelId, runtimeId, backendSelection.backend.runtimeId)
            keepCompiledSessionCache = true
            queue.markTaskCompleted(
                taskId = runningTask.taskId,
                status = "succeeded",
                result = runtimeResult.details + mapOf(
                    "status" to runtimeResult.status,
                    "message" to runtimeResult.message,
                    "session_id" to sessionId,
                    "cache" to cacheReceipt,
                ),
            )
            executionHistory.completeSession(
                sessionId = sessionId,
                status = "succeeded",
                result = runtimeResult.details + mapOf("cache" to cacheReceipt),
                errorMessage = "",
            )
        } catch (paused: AiTaskPausedException) {
            activeBackend?.requestPause(sessionId)
            queue.markTaskPaused(runningTask.taskId, paused.message ?: "paused")
            executionHistory.completeSession(
                sessionId = sessionId,
                status = "paused",
                result = mapOf("paused" to true),
                errorMessage = paused.message ?: "paused",
            )
        } catch (cancelled: AiTaskCancelledException) {
            activeBackend?.requestCancellation(sessionId)
            queue.markTaskCompleted(
                taskId = runningTask.taskId,
                status = "cancelled",
                result = mapOf(
                    "status" to "cancelled",
                    "message" to (cancelled.message ?: "cancelled"),
                    "session_id" to sessionId,
                ),
                errorMessage = cancelled.message ?: "cancelled",
            )
            executionHistory.completeSession(
                sessionId = sessionId,
                status = "cancelled",
                result = mapOf("cancelled" to true),
                errorMessage = cancelled.message ?: "cancelled",
            )
        } catch (error: Throwable) {
            handleFailure(
                task = runningTask,
                sessionId = sessionId,
                status = "runtime_failure",
                message = error.message ?: error.javaClass.simpleName,
                details = mapOf("exception" to error.javaClass.simpleName),
            )
        } finally {
            activeExecutions.remove(runningTask.taskId)
            val runtimeId = context?.runtimeId.orEmpty()
            modelLifetimeManager.release(model, runtimeId)
            memoryManager.release(sessionId)
            if (!keepCompiledSessionCache) {
                sessionCache.clearCompiledSession(sessionId)
            }
        }
    }

    private suspend fun handleFailure(
        task: AiTaskRecord,
        sessionId: String,
        status: String,
        message: String,
        details: Map<String, Any>,
    ) {
        val retryDecision = retryManager.decide(task, status = status, message = message)
        val resultPayload = details + mapOf(
            "status" to status,
            "message" to message,
            "session_id" to sessionId,
        )

        if (retryDecision.shouldRetry) {
            queue.scheduleRetry(task.taskId, retryDecision.nextRunAtMs, message)
            queue.updateTaskProgress(task.taskId, 0.0, "Retry scheduled: ${retryDecision.reason}")
            executionHistory.completeSession(
                sessionId = sessionId,
                status = "retry_scheduled",
                result = resultPayload + mapOf("retry" to retryDecision.reason),
                errorMessage = message,
            )
            return
        }

        val finalStatus = if (status == "invalid_output") "failed" else status
        queue.markTaskCompleted(
            taskId = task.taskId,
            status = if (finalStatus == "runtime_failure") "failed" else finalStatus,
            result = resultPayload,
            errorMessage = message,
        )
        executionHistory.completeSession(
            sessionId = sessionId,
            status = if (finalStatus == "runtime_failure") "failed" else finalStatus,
            result = resultPayload,
            errorMessage = message,
        )
    }

    private fun resolveTaskModel(task: AiTaskRecord): AiModelDescriptor? {
        if (task.modelId.isBlank()) {
            return null
        }
        return if (task.version.isBlank()) {
            repository.getModel(task.modelId)
        } else {
            repository.getModel(task.modelId, task.version)
        }
    }

    private fun estimateReservationBytes(task: AiTaskRecord, model: AiModelDescriptor?): Long {
        val explicitReservation = (task.payload["reserve_ram_bytes"] as? Number)?.toLong()
        if (explicitReservation != null && explicitReservation > 0L) {
            return explicitReservation
        }

        // GGUF is memory-mapped by llama.cpp. Multiplying the on-disk model
        // size by 4 treats every mapped page as committed heap and can reject a
        // model that the OS can run comfortably. Reserve the expected working
        // set instead: most of the mapped weights/projector plus context/image
        // headroom. This remains a scheduling guard, not an artificial
        // requirement that all mapped bytes be free RAM before execution.
        val llamaMetadata = model?.metadata?.get("llama_cpp") as? Map<*, *>
        if (model != null && llamaMetadata != null) {
            val rolePaths = model.metadata["artifact_paths_by_role"] as? Map<*, *>
            val projectorBytes = rolePaths
                ?.get("vision_projector")
                ?.toString()
                ?.takeIf(String::isNotBlank)
                ?.let { java.io.File(it) }
                ?.takeIf { it.isFile }
                ?.length()
                ?: 0L
            val mappedBytes = (model.sizeBytes.coerceAtLeast(0L) + projectorBytes)
                .coerceAtLeast(256L * 1024L * 1024L)
            val contextSize = ((model.metadata["llama_cpp_context"] as? Number)?.toLong() ?: 32_768L)
                .coerceIn(256L, 32_768L)
            val contextHeadroom = (contextSize * 32L * 1024L)
                .coerceIn(256L * 1024L * 1024L, 1L * 1024L * 1024L * 1024L)
            val imageHeadroom = if (llamaMetadata["multimodal"] == true) {
                512L * 1024L * 1024L
            } else {
                128L * 1024L * 1024L
            }
            return ((mappedBytes * 3L) / 4L + contextHeadroom + imageHeadroom)
                .coerceIn(
                    768L * 1024L * 1024L,
                    3L * 1024L * 1024L * 1024L,
                )
        }

        // An ONNX regression model is loaded once; reserving four copies of
        // its entire package rejects models that fit on otherwise capable
        // phones. Include one model, a 50% execution allowance and 256 MiB
        // for preprocessing/tensors instead. Never bypass the RAM guard.
        if (AiTaskTypes.normalize(task.taskType) == "aesthetic_scoring" &&
            (model?.requiredRuntime?.contains("onnx", ignoreCase = true) == true ||
                model?.supportedRuntimes?.any { it.contains("onnx", ignoreCase = true) } == true ||
                task.runtimeHint.contains("onnx", ignoreCase = true))
        ) {
            return estimateAestheticOnnxReservationBytes(model?.sizeBytes ?: 0L)
        }

        val modelSize = model?.sizeBytes ?: 8L * 1024L * 1024L
        val multiplier = when (AiTaskTypes.normalize(task.taskType)) {
            "embedding_generation", "similarity_search", "duplicate_detection", "face_feature_extraction" -> 3L
            "ocr", "captioning", "metadata_extraction", "classification", "detection", "knowledge_pack_execution" -> 2L
            else -> 4L
        }
        return (modelSize * multiplier).coerceAtLeast(16L * 1024L * 1024L)
    }
}

internal fun estimateAestheticOnnxReservationBytes(modelSizeBytes: Long): Long {
    val effectiveModelBytes = modelSizeBytes.coerceAtLeast(32L * 1024L * 1024L)
    return effectiveModelBytes + effectiveModelBytes / 2L + 256L * 1024L * 1024L
}

class LocalAiExecutionScheduler(
    private val queue: LocalAiExecutionQueue,
    private val dispatcher: LocalAiTaskDispatcher,
    private val settingsProvider: () -> AiSettings,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) {
    private val schedulerMutex = Mutex()
    private val activeJobs = ConcurrentHashMap<String, Job>()

    @Volatile
    private var schedulerPaused = false

    @Volatile
    private var schedulerStarted = false

    private var schedulerJob: Job? = null

    fun start() {
        resume()
    }

    fun pause() {
        schedulerPaused = true
    }

    fun resume() {
        schedulerPaused = false
        if (schedulerStarted) {
            return
        }
        scope.launch {
            schedulerMutex.withLock {
                if (schedulerStarted) {
                    return@withLock
                }
                schedulerStarted = true
            }
            schedulerJob = launchSchedulerLoop()
        }
    }

    fun enqueue(
        taskType: String,
        modelId: String = "",
        version: String = "",
        runtimeHint: String = "",
        priority: Int = 0,
        maxRetries: Int = settingsProvider().maxQueueRetries,
        timeoutMs: Long = settingsProvider().defaultTaskTimeoutMs,
        dependencyTaskIds: List<String> = emptyList(),
        payload: Map<String, Any> = emptyMap(),
    ): AiTaskRecord {
        val task = queue.enqueueTask(
            taskType = taskType,
            modelId = modelId,
            version = version,
            runtimeHint = runtimeHint,
            priority = priority,
            maxRetries = maxRetries,
            timeoutMs = timeoutMs,
            dependencyTaskIds = dependencyTaskIds,
            payload = payload,
        )
        resume()
        return task
    }

    fun listTasks(limit: Int = 200): List<AiTaskRecord> {
        return queue.listTasks(limit)
    }

    fun cancelTask(taskId: String): Boolean {
        val cancelled = queue.cancelTask(taskId)
        if (cancelled) {
            scope.launch {
                dispatcher.requestCancellation(taskId)
            }
        }
        return cancelled
    }

    fun pauseTask(taskId: String): Boolean {
        return queue.pauseTask(taskId)
    }

    fun resumeTask(taskId: String): Boolean {
        val resumed = queue.resumeTask(taskId)
        if (resumed) {
            resume()
        }
        return resumed
    }

    fun retryTask(taskId: String): Boolean {
        val retried = queue.retryTask(taskId)
        if (retried) {
            resume()
        }
        return retried
    }

    private fun launchSchedulerLoop(): Job {
        return scope.launch {
            try {
                queue.recoverInterruptedTasks()
                while (isActive) {
                    if (schedulerPaused) {
                        delay(settingsProvider().schedulerPollIntervalMs)
                        continue
                    }

                    cleanupInactiveJobs()
                    val maxConcurrentTasks = settingsProvider().maxConcurrentTasks.coerceAtLeast(1)
                    if (activeJobs.size >= maxConcurrentTasks) {
                        delay(settingsProvider().schedulerPollIntervalMs)
                        continue
                    }

                    val launchSlots = (maxConcurrentTasks - activeJobs.size).coerceAtLeast(1)
                    val candidates = queue.listRunnableTasks(
                        nowMs = System.currentTimeMillis(),
                        limit = launchSlots * 4,
                    )

                    var launched = 0
                    candidates.forEach { task ->
                        if (launched >= launchSlots) {
                            return@forEach
                        }
                        if (activeJobs.containsKey(task.taskId)) {
                            return@forEach
                        }

                        when (resolveDependencyState(task)) {
                            DependencyState.BLOCKED_WAITING -> return@forEach
                            DependencyState.BLOCKED_FAILED -> {
                                queue.markTaskCompleted(
                                    taskId = task.taskId,
                                    status = "failed",
                                    result = mapOf(
                                        "status" to "failed",
                                        "message" to "Task dependency failed",
                                        "dependency_task_ids" to task.dependencyTaskIds,
                                    ),
                                    errorMessage = "Task dependency failed",
                                )
                                return@forEach
                            }

                            DependencyState.READY -> Unit
                        }

                        val job = scope.launch {
                            dispatcher.dispatch(task)
                        }
                        activeJobs[task.taskId] = job
                        job.invokeOnCompletion {
                            activeJobs.remove(task.taskId)
                        }
                        launched += 1
                    }

                    if (launched > 0) {
                        delay(settingsProvider().schedulerPollIntervalMs)
                    } else {
                        delay(settingsProvider().schedulerIdleDelayMs)
                    }
                }
            } finally {
                schedulerMutex.withLock {
                    schedulerStarted = false
                }
            }
        }
    }

    private fun cleanupInactiveJobs() {
        val finishedTaskIds = activeJobs.entries
            .filter { (_, job) -> !job.isActive }
            .map { (taskId, _) -> taskId }
        finishedTaskIds.forEach { activeJobs.remove(it) }
    }

    private fun resolveDependencyState(task: AiTaskRecord): DependencyState {
        if (task.dependencyTaskIds.isEmpty()) {
            return DependencyState.READY
        }
        val statuses = queue.listDependencyStatuses(task)
        task.dependencyTaskIds.forEach { dependencyTaskId ->
            val dependencyStatus = statuses[dependencyTaskId] ?: return DependencyState.BLOCKED_FAILED
            if (dependencyStatus in setOf("failed", "cancelled")) {
                return DependencyState.BLOCKED_FAILED
            }
            if (dependencyStatus != "succeeded") {
                return DependencyState.BLOCKED_WAITING
            }
        }
        return DependencyState.READY
    }

    private enum class DependencyState {
        READY,
        BLOCKED_WAITING,
        BLOCKED_FAILED,
    }
}

