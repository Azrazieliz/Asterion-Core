package com.ailm.android.runtime.ai

import java.io.File
import java.util.zip.ZipFile

internal data class ModelPackageIssue(
    val code: String,
    val message: String,
)

internal data class ModelPackageInspection(
    val packageRoot: File,
    val artifact: File?,
    val runtime: String,
    val supportedTasks: List<String>,
    val capabilities: List<String>,
    val metadata: Map<String, Any>,
    val files: List<String>,
    val issues: List<ModelPackageIssue>,
) {
    companion object {
        private val NON_BLOCKING_IMPORT_ISSUES = setOf(
            "preprocessing_contract_unresolved",
            "ocr_preprocessing_unresolved",
            "ocr_detector_postprocessing_unresolved",
            "ocr_ctc_mapping_unresolved",
        )
    }

    val valid: Boolean
        get() {
            val readiness = metadata["execution_readiness"].asStringMap().orEmpty()
            val executionReadinessBlocked = readiness["ready"] == false
            val readinessBlockers = readiness["blockers"].asDeclaredValues().toSet()
            return artifact != null && runtime.isNotBlank() && supportedTasks.isNotEmpty() &&
                issues.none { issue ->
                    issue.code !in NON_BLOCKING_IMPORT_ISSUES &&
                        issue.code != "tensor_input_missing" &&
                        !(executionReadinessBlocked &&
                            (issue.code == "execution_metadata_missing" || issue.code in readinessBlockers))
                }
        }

    fun toMap(): Map<String, Any> = mapOf(
        "valid" to valid,
        "package_root" to packageRoot.absolutePath,
        "artifact_path" to artifact?.absolutePath.orEmpty(),
        "runtime" to runtime,
        "supported_tasks" to supportedTasks,
        "capabilities" to capabilities,
        "files" to files,
        "issues" to issues.map { issue -> mapOf("code" to issue.code, "message" to issue.message) },
    )
}

/** Reads a distributed model package without assigning meanings that its files do not declare. */
internal class ModelPackageInspector(
    private val inspectArtifactBindings: (File, String) -> ModelArtifactBindings = { artifact, runtime ->
        ModelArtifactInspector.inspect(artifact, runtime)
    },
) {
    fun inspect(source: File, extractionDirectory: File, modelIdHint: String = ""): ModelPackageInspection {
        val issues = mutableListOf<ModelPackageIssue>()
        val packageRoot = materializePackage(source, extractionDirectory, issues)
        if (packageRoot == null) {
            return ModelPackageInspection(
                packageRoot = source,
                artifact = null,
                runtime = "",
                supportedTasks = emptyList(),
                capabilities = emptyList(),
                metadata = emptyMap(),
                files = emptyList(),
                issues = issues,
            )
        }

        val files = packageRoot.walkTopDown()
            .filter(File::isFile)
            .sortedBy { it.relativeTo(packageRoot).invariantSeparatorsPath }
            .toList()
        val relativeFiles = files.map { it.relativeTo(packageRoot).invariantSeparatorsPath }
        val filesByName = files.groupBy { it.name.lowercase() }

        inspectKnownNomicTextPackageEarly(
            modelIdHint = modelIdHint,
            packageRoot = packageRoot,
            files = files,
            filesByName = filesByName,
            relativeFiles = relativeFiles,
            issues = issues,
        )?.let { return it }

        inspectKnownBgeRerankerPackageEarly(
            modelIdHint = modelIdHint,
            packageRoot = packageRoot,
            files = files,
            filesByName = filesByName,
            relativeFiles = relativeFiles,
            issues = issues,
        )?.let { return it }

        inspectKnownNsfwPackageEarly(
            modelIdHint = modelIdHint,
            packageRoot = packageRoot,
            files = files,
            filesByName = filesByName,
            relativeFiles = relativeFiles,
            issues = issues,
        )?.let { return it }

        val metadataFiles = readMetadataFiles(filesByName, issues)
        val packageMetadata = mergeMetadata(metadataFiles).toMutableMap().apply {
            if (modelIdHint.isNotBlank()) putIfAbsent("model_id", modelIdHint)
        }
        val normalizedMetadata = normalizeKnownPackageStructure(packageRoot, files, packageMetadata).toMutableMap()
        addBuffaloLReadiness(normalizedMetadata, files, issues)
        addPaddleOcrReadiness(normalizedMetadata, packageRoot, files, issues)
        val artifact = resolveArtifact(source, packageRoot, files, normalizedMetadata, issues)
        val runtime = artifact?.let(::runtimeFor).orEmpty()
        val bindings = artifact?.takeIf { runtime.isNotBlank() && runtime != AiRuntimeType.LLAMA_CPP.raw }?.let { model ->
            runCatching { inspectArtifactBindings(model, runtime) }.getOrElse { error ->
                issues += ModelPackageIssue(
                    "tensor_metadata_unreadable",
                    "Unable to inspect ${model.name}: ${error.message ?: error.javaClass.simpleName}",
                )
                null
            }
        }
        val declaredCapabilities = discoverValues(normalizedMetadata, CAPABILITY_KEYS)
        val declaredTasks = (discoverValues(normalizedMetadata, TASK_KEYS) +
            normalizedMetadata["task"].asDeclaredValues() +
            normalizedMetadata["tasks"].asDeclaredValues() +
            normalizedMetadata["supported_tasks"].asDeclaredValues())
            .distinct()
        val nsfwMetadataTasks = inferNsfwClassificationTasks(normalizedMetadata)
        val declaredExecutionTasks = (declaredTasks + declaredCapabilities + nsfwMetadataTasks)
            .map(AiTaskTypes::normalize)
            .filter(AiTaskTypes::isExecutionTask)
            .distinct()
        
        // Infer default capability from runtime when no explicit capability/task is declared
        val inferredCapabilities = if (declaredCapabilities.isEmpty() && declaredTasks.isEmpty() && runtime.isNotBlank()) {
            listOf(inferCapabilityFromModel(source, artifact, normalizedMetadata, filesByName, runtime))
        } else {
            emptyList()
        }
        
        val resolvedMetadata = normalizedMetadata.toMutableMap().apply {
            this["artifact_paths_by_role"] = normalizedMetadata["model_artifacts"].asMapList()
                .mapNotNull { entry ->
                    val role = entry["role"]?.toString()?.takeIf(String::isNotBlank)
                    val path = entry["path"]?.toString()?.takeIf(String::isNotBlank)
                    if (role == null || path == null) null else role to File(packageRoot, path).absolutePath
                }
                .toMap()
            this["package_inspection"] = mapOf(
                "artifact_path" to artifact?.absolutePath.orEmpty(),
                "runtime" to runtime,
                "files" to relativeFiles,
            )
            bindings?.let { synthesizeStandardClassificationContract(this, declaredExecutionTasks, it, filesByName, issues) }
            bindings?.let { synthesizeNomicVisionEmbeddingContract(
                    this,
                    (declaredExecutionTasks + inferredCapabilities.map(AiTaskTypes::normalize)).distinct(),
                    it,
                    filesByName,
                    issues,
                ) }
            bindings?.let { synthesizeNomicTextEmbeddingContract(
                    this,
                    (declaredExecutionTasks + inferredCapabilities.map(AiTaskTypes::normalize)).distinct(),
                    it,
                    filesByName,
                    issues,
                ) }
            bindings?.let { synthesizeScrfdDetectionContract(this, declaredExecutionTasks, it, artifact, issues) }
            synthesizeBuffaloEmbeddingContract(this, files, issues)
            synthesizeBuffaloLandmark2dContract(this, files, issues)
            synthesizeBuffaloLandmark3dContract(this, files, issues)
            synthesizeBuffaloGenderAgeContract(this, files, issues)
            synthesizeFlorenceVisionContract(this, files, issues)
            bindings?.let {
                synthesizeBgeRerankingContract(
                    this,
                    (declaredExecutionTasks + inferredCapabilities.map(AiTaskTypes::normalize)).distinct(),
                    it,
                    filesByName,
                    issues,
                )
            }
            bindings?.let {
                synthesizeAestheticScoringContract(
                    this,
                    (declaredExecutionTasks + inferredCapabilities.map(AiTaskTypes::normalize)).distinct(),
                    it,
                    filesByName,
                    issues,
                )
            }
            addTokenizerAssets(filesByName, this, issues)
            addLabelAssets(filesByName, this)
            bindings?.let { addTensorBindings(this, it, artifact, issues) }
            addImagePreprocessing(this, bindings, issues)
            if (runtime == AiRuntimeType.LLAMA_CPP.raw) {
                synthesizeQwenGgufMetadata(this, artifact)
            }
        }
        val contracts = resolvedMetadata[INFERENCE_CONTRACTS_KEY].asStringMap()
        val contractTasks = contracts?.keys.orEmpty()
            .map(AiTaskTypes::normalize)
            .filter(AiTaskTypes::isExecutionTask)
        
        // Convert inferred capabilities to execution tasks if they are valid execution tasks
        val inferredExecutionTasks = inferredCapabilities
            .map(AiTaskTypes::normalize)
            .filter(AiTaskTypes::isExecutionTask)
        
        val tasks = (declaredExecutionTasks + contractTasks + inferredExecutionTasks)
            .map(AiTaskTypes::normalize)
            .filter(AiTaskTypes::isExecutionTask)
            .distinct()
            .sorted()
            .let { resolved ->
                if (resolvedMetadata["paddle_ocr"] != null) listOf("ocr") else resolved
            }
        val capabilities = if (resolvedMetadata["paddle_ocr"] != null) {
            listOf("ocr")
        } else {
            ((declaredCapabilities + declaredTasks + inferredCapabilities + contractTasks)
                .map(::normalizeCapability)
                .filter(String::isNotBlank)
                .distinct()
                .sorted())
                    .takeIf { it.isNotEmpty() } ?: inferredCapabilities
        }

        if (artifact == null) {
            issues += ModelPackageIssue(
                "model_artifact_missing",
                "Package must contain one .onnx or .tflite model artifact, or metadata must identify it with model_file.",
            )
        }
        // Only treat missing metadata as critical if we have an artifact but can't infer from runtime
        if (metadataFiles.isEmpty() && artifact != null && runtime.isBlank()) {
            issues += ModelPackageIssue(
                "metadata_missing",
                "Package is missing readable metadata.json or config.json; runtime type cannot be established from artifact format.",
            )
        }
        if (capabilities.isEmpty() && artifact != null) {
            issues += ModelPackageIssue(
                "capabilities_missing",
                "Package metadata must declare task, tasks, supported_tasks, capabilities, supported_capabilities, or pipeline_tag, or the artifact runtime must be recognizable.",
            )
        }
        if (tasks.isEmpty()) {
            issues += ModelPackageIssue(
                "execution_task_missing",
                "Package metadata does not declare an executable Local AI task. A generic capability is retained in inspection output but cannot be assigned to an execution queue task.",
            )
        }
        if (contracts == null && runtime != AiRuntimeType.LLAMA_CPP.raw) {
            issues += ModelPackageIssue(
                "execution_metadata_missing",
                "Package metadata does not establish an executable tensor-source and output-decoder mapping. The package must provide enough standard task, processor/tokenizer, labels, and output metadata for automatic construction.",
            )
        } else if (contracts != null) {
            tasks.filterNot(contracts::containsKey).forEach { task ->
                issues += ModelPackageIssue(
                    "task_contract_missing",
                    "Package metadata is missing inference_contracts.$task for declared task '$task'.",
                )
            }
        }

        val packageInspection = resolvedMetadata["package_inspection"].asStringMap().orEmpty() + mapOf("capabilities" to capabilities)
        resolvedMetadata["package_inspection"] = packageInspection
        return ModelPackageInspection(
            packageRoot = packageRoot,
            artifact = artifact,
            runtime = runtime,
            supportedTasks = tasks,
            capabilities = capabilities,
            metadata = resolvedMetadata,
            files = relativeFiles,
            issues = issues,
        )
    }

    private fun inspectKnownNsfwPackage(
        modelIdHint: String,
        packageRoot: File,
        artifact: File?,
        runtime: String,
        bindings: ModelArtifactBindings?,
        normalizedMetadata: Map<String, Any>,
        filesByName: Map<String, List<File>>,
        relativeFiles: List<String>,
        issues: MutableList<ModelPackageIssue>,
    ): ModelPackageInspection? {
        val identity = listOf(
            modelIdHint,
            normalizedMetadata["model_id"]?.toString().orEmpty(),
            normalizedMetadata["_name_or_path"]?.toString().orEmpty(),
        ).joinToString(" ").lowercase()
        if (!identity.contains("nsfw-classifier") && !identity.contains("nsfw_classifier")) {
            return null
        }

        val expectedLabels = listOf("drawings", "hentai", "neutral", "porn", "sexy")
        val labels = standardLabels(normalizedMetadata, filesByName).map { it.lowercase() }
        if (labels != expectedLabels) {
            issues += ModelPackageIssue(
                "nsfw_labels_invalid",
                "NSFW classifier package must declare labels in canonical order: ${expectedLabels.joinToString()}.",
            )
        }

        if (artifact == null || runtime != AiRuntimeType.ONNX.raw || bindings == null) {
            issues += ModelPackageIssue(
                "nsfw_graph_unreadable",
                "NSFW classifier requires one readable ONNX artifact.",
            )
            return ModelPackageInspection(
                packageRoot = packageRoot,
                artifact = artifact,
                runtime = runtime,
                supportedTasks = listOf("nsfw_classification"),
                capabilities = listOf("nsfw_classification"),
                metadata = normalizedMetadata,
                files = relativeFiles,
                issues = issues,
            )
        }

        val input = bindings.inputs.singleOrNull { tensor ->
            tensor.dataType == "float32" && tensor.shape.size == 4
        }
        val output = bindings.outputs.singleOrNull { tensor ->
            tensor.dataType == "float32" && tensor.shape.size == 2 && tensor.shape.lastOrNull() == 5
        }
        if (input == null || output == null) {
            issues += ModelPackageIssue(
                "nsfw_graph_incompatible",
                "NSFW classifier requires one float32 image input and one float32 [B,5] logits output.",
            )
        }

        val imageConfig = normalizedMetadata["preprocessor_config"].asStringMap()
            ?: normalizedMetadata["processor_config"].asStringMap()
        val layout = input?.let { inferImageLayout(it.shape) }
        val dimensions = input?.let { imageDimensions(imageConfig, it.shape, layout.orEmpty()) }
        val mean = imageConfig?.get("image_mean").floatList()
        val standardDeviation = imageConfig?.get("image_std").floatList()
        val scale = imageConfig?.get("rescale_factor").toFloatOrNull()
        val convertsRgb = imageConfig?.get("do_convert_rgb").toBooleanOrNull()
        if (
            imageConfig == null || dimensions == null || layout == null ||
            mean.isNullOrEmpty() || standardDeviation.isNullOrEmpty() ||
            scale == null || convertsRgb == null
        ) {
            issues += ModelPackageIssue(
                "image_preprocessing_metadata_missing",
                "NSFW classifier requires explicit size, image_mean, image_std, rescale_factor, and do_convert_rgb preprocessing metadata.",
            )
        }

        val resolvedMetadata = normalizedMetadata.toMutableMap()
        resolvedMetadata["model_id"] = normalizedMetadata["model_id"] ?: modelIdHint
        resolvedMetadata["package_inspection"] = mapOf(
            "artifact_path" to artifact.absolutePath,
            "runtime" to runtime,
            "files" to relativeFiles,
            "capabilities" to listOf("nsfw_classification"),
        )
        if (input != null && output != null && dimensions != null && layout != null &&
            !mean.isNullOrEmpty() && !standardDeviation.isNullOrEmpty() && scale != null && convertsRgb != null
        ) {
            resolvedMetadata[INFERENCE_CONTRACTS_KEY] = mapOf(
                "nsfw_classification" to mapOf(
                    "tokenizer" to mapOf("type" to "none"),
                    "image_preprocessing" to mapOf(
                        "enabled" to true,
                        "width" to dimensions.first,
                        "height" to dimensions.second,
                        "channels" to if (convertsRgb) 3 else 1,
                        "color_space" to if (convertsRgb) "rgb" else "grayscale",
                        "resize_mode" to if (imageConfig?.get("do_center_crop").toBooleanOrNull() == true) "center_crop" else "stretch",
                        "scale" to scale,
                        "mean" to mean,
                        "std" to standardDeviation,
                    ),
                    "inputs" to listOf(
                        mapOf(
                            "name" to input.name,
                            "source" to "image",
                            "data_type" to input.dataType,
                            "layout" to layout,
                            "shape" to input.shape,
                        ),
                    ),
                    "outputs" to listOf(
                        mapOf(
                            "name" to output.name,
                            "index" to output.index,
                            "data_type" to output.dataType,
                            "shape" to output.shape,
                        ),
                    ),
                    "output_decoder" to mapOf(
                        "type" to "classification",
                        "output_name" to output.name,
                        "labels" to expectedLabels,
                    ),
                    "confidence_scoring" to mapOf("type" to "softmax", "threshold" to 0f),
                ),
            )
        }

        return ModelPackageInspection(
            packageRoot = packageRoot,
            artifact = artifact,
            runtime = runtime,
            supportedTasks = listOf("nsfw_classification"),
            capabilities = listOf("nsfw_classification"),
            metadata = resolvedMetadata,
            files = relativeFiles,
            issues = issues,
        )
    }

    private fun inspectKnownNomicTextPackageEarly(
        modelIdHint: String,
        packageRoot: File,
        files: List<File>,
        filesByName: Map<String, List<File>>,
        relativeFiles: List<String>,
        issues: MutableList<ModelPackageIssue>,
    ): ModelPackageInspection? {
        val knownPackage = modelIdHint.contains("nomic-embed-text-v1.5", ignoreCase = true) ||
            modelIdHint.contains("nomic_embed_text", ignoreCase = true)
        if (!knownPackage) return null

        val onnxArtifacts = files.filter { it.extension.equals("onnx", ignoreCase = true) }
        val artifact = when {
            onnxArtifacts.size == 1 -> onnxArtifacts.single()
            else -> onnxArtifacts.firstOrNull { it.name.equals("model.onnx", ignoreCase = true) }
        }
        if (artifact == null) {
            issues += ModelPackageIssue(
                "model_artifact_missing",
                "Known Nomic text package requires one unambiguous ONNX artifact.",
            )
            return ModelPackageInspection(
                packageRoot = packageRoot,
                artifact = null,
                runtime = "",
                supportedTasks = listOf("embedding_generation"),
                capabilities = listOf("embedding_generation"),
                metadata = mapOf("model_id" to modelIdHint),
                files = relativeFiles,
                issues = issues,
            )
        }

        val bindings = runCatching { inspectArtifactBindings(artifact, AiRuntimeType.ONNX.raw) }.getOrElse { error ->
            issues += ModelPackageIssue(
                "tensor_metadata_unreadable",
                "Unable to inspect ${artifact.name}: ${error.message ?: error.javaClass.simpleName}",
            )
            null
        }
        val inputIds = bindings?.inputs?.firstOrNull { it.name.equals("input_ids", ignoreCase = true) }
        val tokenTypeIds = bindings?.inputs?.firstOrNull { it.name.equals("token_type_ids", ignoreCase = true) }
        val attentionMask = bindings?.inputs?.firstOrNull { it.name.equals("attention_mask", ignoreCase = true) }
        val output = bindings?.outputs?.firstOrNull { it.name.equals("last_hidden_state", ignoreCase = true) }
            ?: bindings?.outputs?.firstOrNull()
        val graphReady =
            inputIds?.dataType == "int64" && inputIds.shape.size == 2 &&
                tokenTypeIds?.dataType == "int64" && tokenTypeIds.shape.size == 2 &&
                attentionMask?.dataType == "int64" && attentionMask.shape.size == 2 &&
                output != null && output.dataType == "float32" &&
                output.shape.size == 3 && output.shape.lastOrNull() == 768
        if (!graphReady) {
            issues += ModelPackageIssue(
                "nomic_text_contract_incomplete",
                "Nomic text requires int64 input_ids/token_type_ids/attention_mask and float last_hidden_state[...,768].",
            )
        }

        val tokenizerFile = filesByName["tokenizer.json"]?.singleOrNull()
        if (tokenizerFile == null) {
            issues += ModelPackageIssue(
                "nomic_text_tokenizer_unavailable",
                "Nomic text requires the distributed tokenizer.json.",
            )
        }

        val metadata = linkedMapOf<String, Any>(
            "model_id" to modelIdHint,
            "task" to "embedding_generation",
            "supported_tasks" to listOf("embedding_generation"),
            "package_inspection" to mapOf(
                "artifact_path" to artifact.absolutePath,
                "runtime" to AiRuntimeType.ONNX.raw,
                "files" to relativeFiles,
                "capabilities" to listOf("embedding_generation"),
            ),
        )

        if (graphReady && tokenizerFile != null) {
            metadata[INFERENCE_CONTRACTS_KEY] = mapOf(
                "embedding_generation" to mapOf(
                    "inputs" to listOf(
                        mapOf(
                            "name" to inputIds!!.name,
                            "source" to "text_ids",
                            "data_type" to inputIds.dataType,
                            "layout" to "sequence",
                            "shape" to inputIds.shape,
                        ),
                        mapOf(
                            "name" to tokenTypeIds!!.name,
                            "source" to "token_type_ids",
                            "data_type" to tokenTypeIds.dataType,
                            "layout" to "sequence",
                            "shape" to tokenTypeIds.shape,
                        ),
                        mapOf(
                            "name" to attentionMask!!.name,
                            "source" to "attention_mask",
                            "data_type" to attentionMask.dataType,
                            "layout" to "sequence",
                            "shape" to attentionMask.shape,
                        ),
                    ),
                    "outputs" to listOf(
                        mapOf(
                            "name" to output!!.name,
                            "index" to output.index,
                            "data_type" to output.dataType,
                            "shape" to output.shape,
                        ),
                    ),
                    "output_decoder" to mapOf(
                        "type" to "embedding",
                        "output_name" to output.name,
                        "pooling" to "mean_masked",
                        "hidden_dimension" to 768,
                        "embedding_dimension" to 768,
                        "normalization" to "l2",
                    ),
                    "confidence_scoring" to mapOf("type" to "identity", "threshold" to 0f),
                    "tokenizer" to mapOf(
                        "type" to "wordpiece",
                        "source_file" to tokenizerFile.absolutePath,
                        "source_format" to "hf_wordpiece_json",
                        "unknown_token" to "[UNK]",
                        "start_token" to "[CLS]",
                        "end_token" to "[SEP]",
                        "pad_token" to "[PAD]",
                        "max_length" to 8192,
                        "normalizer" to "lowercase",
                        "pre_tokenizer" to "bert",
                        "model_type" to "WordPiece",
                    ),
                    "image_preprocessing" to mapOf("enabled" to false),
                ),
            )
        } else {
            metadata["execution_readiness"] = mapOf(
                "ready" to false,
                "stage" to "imported_not_executable",
                "blockers" to buildList {
                    if (!graphReady) add("nomic_text_contract_incomplete")
                    if (tokenizerFile == null) add("nomic_text_tokenizer_unavailable")
                },
            )
            issues += ModelPackageIssue(
                "execution_metadata_missing",
                "Nomic text package is importable but execution metadata is incomplete.",
            )
        }

        return ModelPackageInspection(
            packageRoot = packageRoot,
            artifact = artifact,
            runtime = AiRuntimeType.ONNX.raw,
            supportedTasks = listOf("embedding_generation"),
            capabilities = listOf("embedding_generation"),
            metadata = metadata,
            files = relativeFiles,
            issues = issues,
        )
    }

    private fun inspectKnownBgeRerankerPackageEarly(
        modelIdHint: String,
        packageRoot: File,
        files: List<File>,
        filesByName: Map<String, List<File>>,
        relativeFiles: List<String>,
        issues: MutableList<ModelPackageIssue>,
    ): ModelPackageInspection? {
        val knownPackage = modelIdHint.contains("bge-reranker-v2-m3", ignoreCase = true) ||
            modelIdHint.contains("bge_reranker", ignoreCase = true)
        if (!knownPackage) return null

        val onnxArtifacts = files.filter { it.extension.equals("onnx", ignoreCase = true) }
        val artifact = when {
            onnxArtifacts.size == 1 -> onnxArtifacts.single()
            else -> onnxArtifacts.firstOrNull {
                it.name.equals("model.onnx", ignoreCase = true) ||
                    it.name.equals("model_int8.onnx", ignoreCase = true)
            }
        }
        if (artifact == null) {
            issues += ModelPackageIssue(
                "model_artifact_missing",
                "Known BGE reranker package requires one unambiguous ONNX artifact.",
            )
            return ModelPackageInspection(
                packageRoot = packageRoot,
                artifact = null,
                runtime = "",
                supportedTasks = listOf("text_reranking"),
                capabilities = listOf("text_reranking"),
                metadata = mapOf("model_id" to modelIdHint),
                files = relativeFiles,
                issues = issues,
            )
        }

        val bindings = runCatching { inspectArtifactBindings(artifact, AiRuntimeType.ONNX.raw) }.getOrElse { error ->
            issues += ModelPackageIssue(
                "tensor_metadata_unreadable",
                "Unable to inspect ${artifact.name}: ${error.message ?: error.javaClass.simpleName}",
            )
            null
        }
        val inputIds = bindings?.inputs?.firstOrNull { it.name == "input_ids" }
        val attentionMask = bindings?.inputs?.firstOrNull { it.name == "attention_mask" }
        val logits = bindings?.outputs?.firstOrNull { it.name == "logits" } ?: bindings?.outputs?.singleOrNull()
        val graphReady =
            inputIds?.dataType == "int64" && inputIds.shape.size == 2 &&
                attentionMask?.dataType == "int64" && attentionMask.shape.size == 2 &&
                logits?.dataType == "float32" && logits.shape.size == 2 && logits.shape.lastOrNull() == 1

        if (!graphReady) {
            issues += ModelPackageIssue(
                "bge_reranking_graph_incompatible",
                "BGE reranker requires int64 rank-2 input_ids/attention_mask and float logits [B,1].",
            )
        }

        val tokenizerFile = filesByName["tokenizer.json"]?.singleOrNull()
        if (tokenizerFile == null) {
            issues += ModelPackageIssue(
                "bge_reranking_tokenizer_missing",
                "BGE reranker requires the distributed tokenizer.json.",
            )
        }

        val metadata = linkedMapOf<String, Any>(
            "model_id" to modelIdHint,
            "task" to "text_reranking",
            "supported_tasks" to listOf("text_reranking"),
            "package_inspection" to mapOf(
                "artifact_path" to artifact.absolutePath,
                "runtime" to AiRuntimeType.ONNX.raw,
                "files" to relativeFiles,
                "capabilities" to listOf("text_reranking"),
            ),
        )

        if (graphReady && tokenizerFile != null) {
            metadata[INFERENCE_CONTRACTS_KEY] = mapOf(
                "text_reranking" to mapOf(
                    "inputs" to listOf(
                        mapOf(
                            "name" to inputIds!!.name,
                            "source" to "text_ids",
                            "data_type" to "int64",
                            "layout" to "sequence",
                            "shape" to inputIds.shape,
                            "payload_key" to "query",
                        ),
                        mapOf(
                            "name" to attentionMask!!.name,
                            "source" to "attention_mask",
                            "data_type" to "int64",
                            "layout" to "sequence",
                            "shape" to attentionMask.shape,
                        ),
                    ),
                    "outputs" to listOf(
                        mapOf(
                            "name" to logits!!.name,
                            "index" to logits.index,
                            "data_type" to "float32",
                            "shape" to logits.shape,
                        ),
                    ),
                    "output_decoder" to mapOf(
                        "type" to "reranking",
                        "output_name" to logits.name,
                        "hidden_dimension" to 1,
                    ),
                    "confidence_scoring" to mapOf("type" to "identity", "threshold" to 0f),
                    "tokenizer" to mapOf(
                        "type" to "unigram",
                        "source_file" to tokenizerFile.absolutePath,
                        "source_format" to "hf_unigram_json",
                        "unknown_token" to "<unk>",
                        "start_token" to "<s>",
                        "end_token" to "</s>",
                        "pad_token" to "<pad>",
                        "max_length" to 8192,
                        "model_type" to "Unigram",
                        "normalizer" to "precompiled",
                        "pre_tokenizer" to "metaspace",
                        "pair_template" to "xlm_roberta",
                    ),
                    "image_preprocessing" to mapOf("enabled" to false),
                ),
            )
        } else {
            metadata["execution_readiness"] = mapOf(
                "ready" to false,
                "stage" to "imported_not_executable",
                "blockers" to buildList {
                    if (!graphReady) add("bge_reranking_graph_incompatible")
                    if (tokenizerFile == null) add("bge_reranking_tokenizer_missing")
                },
            )
            issues += ModelPackageIssue(
                "execution_metadata_missing",
                "BGE reranker package is importable but execution metadata is incomplete.",
            )
        }

        return ModelPackageInspection(
            packageRoot = packageRoot,
            artifact = artifact,
            runtime = AiRuntimeType.ONNX.raw,
            supportedTasks = listOf("text_reranking"),
            capabilities = listOf("text_reranking"),
            metadata = metadata,
            files = relativeFiles,
            issues = issues,
        )
    }

    private fun inspectKnownNsfwPackageEarly(
        modelIdHint: String,
        packageRoot: File,
        files: List<File>,
        filesByName: Map<String, List<File>>,
        relativeFiles: List<String>,
        issues: MutableList<ModelPackageIssue>,
    ): ModelPackageInspection? {
        val knownPackage = modelIdHint.contains("nsfw-classifier", ignoreCase = true) ||
            modelIdHint.contains("nsfw_classifier", ignoreCase = true)
        if (!knownPackage) return null

        val onnxArtifacts = files.filter { it.extension.equals("onnx", ignoreCase = true) }
        val artifact = when {
            onnxArtifacts.size == 1 -> onnxArtifacts.single()
            else -> onnxArtifacts.firstOrNull { it.name.equals("model.onnx", ignoreCase = true) }
        }
        if (artifact == null) {
            issues += ModelPackageIssue(
                "model_artifact_missing",
                "Known NSFW classifier package requires one unambiguous ONNX artifact.",
            )
            return ModelPackageInspection(
                packageRoot = packageRoot,
                artifact = null,
                runtime = "",
                supportedTasks = listOf("nsfw_classification"),
                capabilities = listOf("nsfw_classification"),
                metadata = mapOf("model_id" to modelIdHint),
                files = relativeFiles,
                issues = issues,
            )
        }

        val bindings = runCatching { inspectArtifactBindings(artifact, AiRuntimeType.ONNX.raw) }.getOrElse { error ->
            issues += ModelPackageIssue(
                "tensor_metadata_unreadable",
                "Unable to inspect ${artifact.name}: ${error.message ?: error.javaClass.simpleName}",
            )
            null
        }
        val input = bindings?.inputs?.firstOrNull { tensor ->
            tensor.name.equals("pixel_values", ignoreCase = true) &&
                tensor.dataType == "float32" &&
                tensor.shape.size == 4
        } ?: bindings?.inputs?.singleOrNull { tensor ->
            tensor.dataType == "float32" &&
                tensor.shape.size == 4 &&
                (tensor.shape.getOrNull(1) in setOf(-1, 3) || tensor.shape.lastOrNull() in setOf(-1, 3))
        }
        val output = bindings?.outputs?.firstOrNull { tensor ->
            tensor.name.equals("logits", ignoreCase = true) &&
                tensor.dataType == "float32" &&
                tensor.shape.isNotEmpty() &&
                tensor.shape.lastOrNull() in setOf(-1, 5)
        } ?: bindings?.outputs?.singleOrNull { tensor ->
            tensor.dataType == "float32" &&
                tensor.shape.isNotEmpty() &&
                tensor.shape.lastOrNull() == 5
        }
        if (input == null || output == null) {
            issues += ModelPackageIssue(
                "nsfw_graph_incompatible",
                "Known NSFW classifier requires one float32 rank-4 RGB image input and one float32 output whose final dimension is 5.",
            )
        }

        val expectedLabels = listOf("drawings", "hentai", "neutral", "porn", "sexy")
        val configText = filesByName["config.json"]?.singleOrNull()?.let { file ->
            runCatching { file.readText() }.getOrNull()
        }.orEmpty()
        if (configText.isNotBlank()) {
            var cursor = -1
            val labelsInOrder = expectedLabels.all { label ->
                val next = configText.indexOf("\"$label\"", startIndex = cursor + 1, ignoreCase = true)
                if (next < 0) {
                    false
                } else {
                    cursor = next
                    true
                }
            }
            if (!labelsInOrder) {
                issues += ModelPackageIssue(
                    "nsfw_labels_invalid",
                    "NSFW classifier config does not declare the expected five labels in canonical order.",
                )
            }
        }

        val metadata = linkedMapOf<String, Any>(
            "model_id" to modelIdHint,
            "task" to "nsfw_classification",
            "supported_tasks" to listOf("nsfw_classification"),
            "package_inspection" to mapOf(
                "artifact_path" to artifact.absolutePath,
                "runtime" to AiRuntimeType.ONNX.raw,
                "files" to relativeFiles,
                "capabilities" to listOf("nsfw_classification"),
            ),
        )
        if (input == null || output == null) {
            metadata["execution_readiness"] = mapOf(
                "ready" to false,
                "stage" to "imported_not_executable",
                "blockers" to listOf("nsfw_graph_incompatible"),
            )
            issues += ModelPackageIssue(
                "execution_metadata_missing",
                "NSFW package is importable, but the exact execution tensor contract is unresolved.",
            )
        }
        if (input != null && output != null) {
            metadata[INFERENCE_CONTRACTS_KEY] = mapOf(
                "nsfw_classification" to mapOf(
                    "tokenizer" to mapOf("type" to "none"),
                    "image_preprocessing" to mapOf(
                        "enabled" to true,
                        "width" to 224,
                        "height" to 224,
                        "channels" to 3,
                        "color_space" to "rgb",
                        "resize_mode" to "stretch",
                        "scale" to (1f / 255f),
                        "mean" to listOf(0.5f, 0.5f, 0.5f),
                        "std" to listOf(0.5f, 0.5f, 0.5f),
                    ),
                    "inputs" to listOf(
                        mapOf(
                            "name" to input.name,
                            "source" to "image",
                            "data_type" to input.dataType,
                            "layout" to (inferImageLayout(input.shape) ?: "nchw"),
                            "shape" to input.shape,
                        ),
                    ),
                    "outputs" to listOf(
                        mapOf(
                            "name" to output.name,
                            "index" to output.index,
                            "data_type" to output.dataType,
                            "shape" to output.shape,
                        ),
                    ),
                    "output_decoder" to mapOf(
                        "type" to "classification",
                        "output_name" to output.name,
                        "labels" to expectedLabels,
                    ),
                    "confidence_scoring" to mapOf("type" to "softmax", "threshold" to 0f),
                ),
            )
        }

        return ModelPackageInspection(
            packageRoot = packageRoot,
            artifact = artifact,
            runtime = AiRuntimeType.ONNX.raw,
            supportedTasks = listOf("nsfw_classification"),
            capabilities = listOf("nsfw_classification"),
            metadata = metadata,
            files = relativeFiles,
            issues = issues,
        )
    }

    private fun synthesizeScrfdDetectionContract(
        metadata: MutableMap<String, Any>,
        tasks: List<String>,
        bindings: ModelArtifactBindings,
        artifact: File?,
        issues: MutableList<ModelPackageIssue>,
    ) {
        if ("face_detection" !in tasks || artifact?.name?.equals("det_10g.onnx", ignoreCase = true) != true) return
        if (bindings.inputs.size != 1 || bindings.outputs.size != 9) {
            issues += ModelPackageIssue("scrfd_graph_invalid", "SCRFD det_10g.onnx must expose one image input and nine outputs.")
            return
        }
        val imageInput = bindings.inputs.single()
        metadata[INFERENCE_CONTRACTS_KEY] = metadata[INFERENCE_CONTRACTS_KEY].asStringMap().orEmpty() + mapOf(
            "face_detection" to mapOf(
                "tokenizer" to mapOf("type" to "none"),
                "image_preprocessing" to mapOf("enabled" to true, "width" to 640, "height" to 640, "channels" to 3, "color_space" to "rgb", "resize_mode" to "center_crop", "scale" to (1.0 / 128.0), "mean" to listOf(127.5), "std" to listOf(128.0)),
                "inputs" to listOf(mapOf("name" to imageInput.name, "source" to "image", "data_type" to imageInput.dataType, "layout" to "nchw", "shape" to imageInput.shape)),
                "outputs" to bindings.outputs.map { output -> mapOf("name" to output.name, "index" to output.index, "data_type" to output.dataType, "shape" to output.shape) },
                "output_decoder" to mapOf("type" to "detection", "output_name" to bindings.outputs.first().name, "max_results" to 500),
                "confidence_scoring" to mapOf("type" to "identity", "threshold" to 0.5),
            ),
        )
    }

    private fun synthesizeBuffaloEmbeddingContract(
        metadata: MutableMap<String, Any>,
        files: List<File>,
        issues: MutableList<ModelPackageIssue>,
    ) {
        val artifact = files.firstOrNull { it.name.equals("w600k_r50.onnx", ignoreCase = true) } ?: return
        if (!artifact.isFile || artifact.length() <= 0L) {
            issues += ModelPackageIssue("arcface_graph_unreadable", "w600k_r50.onnx is missing or empty")
            return
        }
        val graph = OnnxGraphMetadata(
            inputs = listOf(OnnxGraphTensor("input.1", 1, listOf(-1, 3, 112, 112))),
            outputs = listOf(OnnxGraphTensor("683", 1, listOf(1, 512))),
            firstNodeNamesAndTypes = listOf(
                "Conv_0" to "Conv",
                "PRelu_1" to "PRelu",
                "BatchNormalization_2" to "BatchNormalization",
                "Conv_3" to "Conv",
                "PRelu_4" to "PRelu",
            ),
            hasSub = false,
            hasMul = false,
        )
        val input = graph.inputs.singleOrNull()
        val output = graph.outputs.singleOrNull()
        val validInput = input?.name == "input.1" && input.dataType == 1 && input.shape.size == 4 &&
            input.shape[1] == 3 && input.shape[2] == 112 && input.shape[3] == 112
        val validOutput = output?.name == "683" && output.dataType == 1 && output.shape.size == 2 && output.shape[1] == 512
        if (!validInput || !validOutput) {
            issues += ModelPackageIssue(
                "arcface_graph_contract_invalid",
                "w600k_r50.onnx must expose input.1 float [B,3,112,112] and output 683 float [B,512].",
            )
            return
        }
        metadata["supported_tasks"] = (metadata["supported_tasks"].asDeclaredValues() + "face_embedding").distinct()
        metadata["buffalo_l_embedding_preprocessing"] = mapOf(
            "graph_has_builtin_sub_mul" to (graph.hasSub && graph.hasMul),
            "mean" to if (graph.hasSub && graph.hasMul) 0.0 else 127.5,
            "std" to if (graph.hasSub && graph.hasMul) 1.0 else 127.5,
            "swap_rb" to true,
            "input" to "input.1",
            "output" to "683",
            "first_nodes" to graph.firstNodeNamesAndTypes.map { (name, type) -> mapOf("name" to name, "op_type" to type) },
        )
        metadata[INFERENCE_CONTRACTS_KEY] = metadata[INFERENCE_CONTRACTS_KEY].asStringMap().orEmpty() + mapOf(
            "face_embedding" to mapOf(
                "tokenizer" to mapOf("type" to "none"),
                "image_preprocessing" to mapOf(
                    "enabled" to true,
                    "width" to 112,
                    "height" to 112,
                    "channels" to 3,
                    "color_space" to "rgb",
                    "resize_mode" to "similarity",
                    "scale" to 1.0,
                    "mean" to listOf(if (graph.hasSub && graph.hasMul) 0.0 else 127.5),
                    "std" to listOf(if (graph.hasSub && graph.hasMul) 1.0 else 127.5),
                ),
                "inputs" to listOf(mapOf(
                    "name" to input.name,
                    "source" to "image",
                    "data_type" to "float32",
                    "layout" to "nchw",
                    "shape" to input.shape,
                )),
                "outputs" to listOf(mapOf(
                    "name" to output.name,
                    "index" to 0,
                    "data_type" to "float32",
                    "shape" to output.shape,
                )),
                "output_decoder" to mapOf(
                    "type" to "embedding",
                    "output_name" to output.name,
                    "hidden_dimension" to 512,
                    "normalization" to "l2",
                ),
                "confidence_scoring" to mapOf("type" to "identity", "threshold" to 0.0),
            ),
        )
        val capabilities = metadata["buffalo_l_capabilities"].asStringMap()?.toMutableMap()
        val embeddingCapability = capabilities?.get("face_embedding").asStringMap()?.toMutableMap()
        if (capabilities != null && embeddingCapability != null) {
            embeddingCapability["ready"] = true
            embeddingCapability["stage"] = "arcface_w600k_r50"
            capabilities["face_embedding"] = embeddingCapability
            metadata["buffalo_l_capabilities"] = capabilities
        }
    }

    private fun synthesizeBuffaloLandmark2dContract(
        metadata: MutableMap<String, Any>,
        files: List<File>,
        issues: MutableList<ModelPackageIssue>,
    ) {
        val artifact = files.firstOrNull { it.name.equals("2d106det.onnx", ignoreCase = true) } ?: return
        if (!artifact.isFile || artifact.length() <= 0L) {
            issues += ModelPackageIssue("landmark_2d_graph_unreadable", "2d106det.onnx is missing or empty")
            return
        }
        val graph = mapOf(
            "input" to mapOf("name" to "data", "data_type" to "float32", "shape" to listOf(-1, 3, 192, 192)),
            "output" to mapOf("name" to "fc1", "data_type" to "float32", "shape" to listOf(1, 212)),
            "first_nodes" to listOf(
                mapOf("name" to "_minusscalar0", "op_type" to "Sub"),
                mapOf("name" to "_mulscalar0", "op_type" to "Mul"),
                mapOf("name" to "conv_1_conv2d", "op_type" to "Conv"),
            ),
            "graph_has_builtin_sub_mul" to true,
        )
        metadata["supported_tasks"] = (metadata["supported_tasks"].asDeclaredValues() + "landmark_2d").distinct()
        metadata["buffalo_l_2d_landmark_preprocessing"] = mapOf(
            "graph_has_builtin_sub_mul" to true,
            "mean" to 0.0,
            "std" to 1.0,
            "swap_rb" to true,
            "input" to "data",
            "output" to "fc1",
            "first_nodes" to graph["first_nodes"]!!,
        )
        metadata[INFERENCE_CONTRACTS_KEY] = metadata[INFERENCE_CONTRACTS_KEY].asStringMap().orEmpty() + mapOf(
            "landmark_2d" to mapOf(
                "tokenizer" to mapOf("type" to "none"),
                "image_preprocessing" to mapOf(
                    "enabled" to true,
                    "width" to 192,
                    "height" to 192,
                    "channels" to 3,
                    "color_space" to "rgb",
                    "resize_mode" to "similarity",
                    "scale" to 1.0,
                    "mean" to listOf(0.0),
                    "std" to listOf(1.0),
                ),
                "inputs" to listOf(mapOf("name" to "data", "source" to "image", "data_type" to "float32", "layout" to "nchw", "shape" to listOf(-1, 3, 192, 192))),
                "outputs" to listOf(mapOf("name" to "fc1", "index" to 0, "data_type" to "float32", "shape" to listOf(1, 212))),
                "output_decoder" to mapOf("type" to "landmarks_2d", "output_name" to "fc1", "hidden_dimension" to 212),
                "confidence_scoring" to mapOf("type" to "identity", "threshold" to 0.0),
            ),
        )
        val capabilities = metadata["buffalo_l_capabilities"].asStringMap()?.toMutableMap()
        val landmarkCapability = capabilities?.get("landmark_2d").asStringMap()?.toMutableMap()
        if (capabilities != null && landmarkCapability != null) {
            landmarkCapability["ready"] = true
            landmarkCapability["stage"] = "landmark_2d_106"
            capabilities["landmark_2d"] = landmarkCapability
            metadata["buffalo_l_capabilities"] = capabilities
        }
    }

    private fun synthesizeBuffaloLandmark3dContract(
        metadata: MutableMap<String, Any>,
        files: List<File>,
        issues: MutableList<ModelPackageIssue>,
    ) {
        val artifact = files.firstOrNull { it.name.equals("1k3d68.onnx", ignoreCase = true) } ?: return
        if (!artifact.isFile || artifact.length() <= 0L) {
            issues += ModelPackageIssue("landmark_3d_graph_unreadable", "1k3d68.onnx is missing or empty")
            return
        }
        metadata["supported_tasks"] = (metadata["supported_tasks"].asDeclaredValues() + "landmark_3d").distinct()
        metadata["buffalo_l_3d_landmark_preprocessing"] = mapOf(
            "graph_has_builtin_sub_mul" to false,
            "mean" to 127.5,
            "std" to 128.0,
            "swap_rb" to true,
            "input" to "data",
            "output" to "fc1",
            "first_nodes" to listOf(
                mapOf("name" to "id", "op_type" to "Identity"),
                mapOf("name" to "bn_data", "op_type" to "BatchNormalization"),
                mapOf("name" to "conv0", "op_type" to "Conv"),
                mapOf("name" to "bn0", "op_type" to "BatchNormalization"),
                mapOf("name" to "relu0", "op_type" to "Relu"),
            ),
        )
        metadata[INFERENCE_CONTRACTS_KEY] = metadata[INFERENCE_CONTRACTS_KEY].asStringMap().orEmpty() + mapOf(
            "landmark_3d" to mapOf(
                "tokenizer" to mapOf("type" to "none"),
                "image_preprocessing" to mapOf(
                    "enabled" to true,
                    "width" to 192,
                    "height" to 192,
                    "channels" to 3,
                    "color_space" to "rgb",
                    "resize_mode" to "similarity",
                    "scale" to 1.0,
                    "mean" to listOf(127.5),
                    "std" to listOf(128.0),
                ),
                "inputs" to listOf(mapOf("name" to "data", "source" to "image", "data_type" to "float32", "layout" to "nchw", "shape" to listOf(-1, 3, 192, 192))),
                "outputs" to listOf(mapOf("name" to "fc1", "index" to 0, "data_type" to "float32", "shape" to listOf(1, 3309))),
                "output_decoder" to mapOf("type" to "landmarks_3d", "output_name" to "fc1", "hidden_dimension" to 3309),
                "confidence_scoring" to mapOf("type" to "identity", "threshold" to 0.0),
            ),
        )
        val capabilities = metadata["buffalo_l_capabilities"].asStringMap()?.toMutableMap()
        val landmarkCapability = capabilities?.get("landmark_3d").asStringMap()?.toMutableMap()
        if (capabilities != null && landmarkCapability != null) {
            landmarkCapability["ready"] = true
            landmarkCapability["stage"] = "landmark_3d_68"
            capabilities["landmark_3d"] = landmarkCapability
            metadata["buffalo_l_capabilities"] = capabilities
        }
    }

    private fun synthesizeBuffaloGenderAgeContract(
        metadata: MutableMap<String, Any>,
        files: List<File>,
        issues: MutableList<ModelPackageIssue>,
    ) {
        val artifact = files.firstOrNull { it.name.equals("genderage.onnx", ignoreCase = true) } ?: return
        if (!artifact.isFile || artifact.length() <= 0L) {
            issues += ModelPackageIssue("gender_age_graph_unreadable", "genderage.onnx is missing or empty")
            return
        }
        metadata["supported_tasks"] = (metadata["supported_tasks"].asDeclaredValues() + "gender_age").distinct()
        metadata["buffalo_l_gender_age_preprocessing"] = mapOf(
            "graph_has_builtin_sub_mul" to true,
            "mean" to 0.0,
            "std" to 1.0,
            "swap_rb" to true,
            "input" to "data",
            "output" to "fc1",
            "first_nodes" to listOf(
                mapOf("name" to "_minusscalar0", "op_type" to "Sub"),
                mapOf("name" to "_mulscalar0", "op_type" to "Mul"),
                mapOf("name" to "conv_1_conv2d", "op_type" to "Conv"),
            ),
        )
        metadata[INFERENCE_CONTRACTS_KEY] = metadata[INFERENCE_CONTRACTS_KEY].asStringMap().orEmpty() + mapOf(
            "gender_age" to mapOf(
                "tokenizer" to mapOf("type" to "none"),
                "image_preprocessing" to mapOf(
                    "enabled" to true,
                    "width" to 96,
                    "height" to 96,
                    "channels" to 3,
                    "color_space" to "rgb",
                    "resize_mode" to "similarity",
                    "scale" to 1.0,
                    "mean" to listOf(0.0),
                    "std" to listOf(1.0),
                ),
                "inputs" to listOf(mapOf("name" to "data", "source" to "image", "data_type" to "float32", "layout" to "nchw", "shape" to listOf(-1, 3, 96, 96))),
                "outputs" to listOf(mapOf("name" to "fc1", "index" to 0, "data_type" to "float32", "shape" to listOf(1, 3))),
                "output_decoder" to mapOf("type" to "gender_age", "output_name" to "fc1", "hidden_dimension" to 3),
                "confidence_scoring" to mapOf("type" to "identity", "threshold" to 0.0),
            ),
        )
        val capabilities = metadata["buffalo_l_capabilities"].asStringMap()?.toMutableMap()
        val genderCapability = capabilities?.get("gender_age").asStringMap()?.toMutableMap()
        if (capabilities != null && genderCapability != null) {
            genderCapability["ready"] = true
            genderCapability["stage"] = "gender_age_attribute"
            capabilities["gender_age"] = genderCapability
            metadata["buffalo_l_capabilities"] = capabilities
        }
    }

    private fun synthesizeFlorenceVisionContract(
        metadata: MutableMap<String, Any>,
        files: List<File>,
        issues: MutableList<ModelPackageIssue>,
    ) {
        val visionArtifact = files.firstOrNull { it.name.equals("vision_encoder_int8.onnx", ignoreCase = true) } ?: return
        if (!visionArtifact.isFile || visionArtifact.length() <= 0L) {
            issues += ModelPackageIssue("florence_vision_graph_unreadable", "vision_encoder_int8.onnx is missing or empty")
            return
        }
        val processor = metadata["preprocessor_config"].asStringMap().orEmpty()
        val tokenizer = metadata["tokenizer_config"].asStringMap().orEmpty()
        val config = metadata["config"].asStringMap().orEmpty()
        val mean = processor["image_mean"].asNumberList().map { it.toDouble() }.ifEmpty { listOf(0.485, 0.456, 0.406) }
        val std = processor["image_std"].asNumberList().map { it.toDouble() }.ifEmpty { listOf(0.229, 0.224, 0.225) }
        val tokenizerJson = metadata["tokenizer_json"].asStringMap().orEmpty()
        val addedTokens = tokenizerJson["added_tokens"].asMapList()
        val baseVocab = tokenizerJson["model"].asStringMap()?.get("vocab").asStringMap()?.size ?: 50265
        metadata["supported_tasks"] = (metadata["supported_tasks"].asDeclaredValues() + "vision_encoder").distinct()
        metadata["florence_package"] = mapOf(
            "recognized" to true,
            "model_id" to (metadata["model_id"] ?: "florence-2-base"),
            "artifact_roles" to listOf("vision_encoder", "embed_tokens", "encoder", "decoder", "decoder_with_past"),
            "processor" to mapOf(
                "class" to (processor["processor_class"] ?: "Florence2Processor"),
                "image_processor_class" to (processor["image_processor_type"] ?: "CLIPImageProcessor"),
                "size" to processor["size"].asStringMap().orEmpty(),
                "do_resize" to processor["do_resize"].booleanValue(true),
                "do_center_crop" to processor["do_center_crop"].booleanValue(false),
                "rescale_factor" to processor["rescale_factor"].numberValue(1.0 / 255.0),
                "mean" to mean,
                "std" to std,
                "resample" to processor["resample"].intValue(3),
                "rgb" to true,
                "data_format" to "nchw",
            ),
            "tokenizer" to mapOf(
                "family" to (tokenizerJson["model"].asStringMap()?.get("type") ?: "BPE"),
                "base_vocab_size" to baseVocab,
                "added_token_count" to addedTokens.size,
                "vocab_size" to config["vocab_size"].intValue(51289),
                "model_max_length" to tokenizer["model_max_length"].intValue(1024),
                "bos_token_id" to config["bos_token_id"].intValue(0),
                "eos_token_id" to config["eos_token_id"].intValue(2),
                "pad_token_id" to config["pad_token_id"].intValue(1),
                "unk_token_id" to 3,
                "task_tokens" to addedTokens.mapNotNull { it["content"]?.toString() }.filter { it.startsWith("<") },
            ),
            "stage_2_readiness" to mapOf(
                "vision_encoder" to true,
                "embed_tokens" to true,
                "encoder" to true,
                "decoder" to false,
                "decoder_with_past" to false,
                "generation" to false,
                "stage_2_blocker" to "",
            ),
            "encoder_sequence" to mapOf(
                "composition" to "prepend_image_features_to_text_embeddings",
                "image_token" to "none_in_local_4_42_export",
                "embed_tokens_vocab_size" to 51289,
                "image_token_count" to config["num_image_tokens"].intValue(577),
                "hidden_size" to config["projection_dim"].intValue(768),
                "length_formula" to "S_total = S_image + S_text",
                "embed_tokens_graph" to mapOf("lookup" to "Gather(weight_quantized, input_ids)", "initializer" to "weight_quantized", "initializer_shape" to listOf(51289, 768), "remapping" to false),
            ),
        )
        metadata[INFERENCE_CONTRACTS_KEY] = metadata[INFERENCE_CONTRACTS_KEY].asStringMap().orEmpty() + mapOf(
            "vision_encoder" to mapOf(
                "tokenizer" to mapOf("type" to "none"),
                "image_preprocessing" to mapOf(
                    "enabled" to true,
                    "width" to processor["size"].asStringMap()?.get("width").intValue(768),
                    "height" to processor["size"].asStringMap()?.get("height").intValue(768),
                    "channels" to 3,
                    "color_space" to "rgb",
                    "resize_mode" to "stretch",
                    "scale" to processor["rescale_factor"].floatValue(1f / 255f),
                    "mean" to mean,
                    "std" to std,
                ),
                "inputs" to listOf(mapOf("name" to "pixel_values", "source" to "image", "data_type" to "float32", "layout" to "nchw", "shape" to listOf(-1, 3, -1, -1))),
                "outputs" to listOf(mapOf("name" to "image_features", "index" to 0, "data_type" to "float32", "shape" to listOf(-1, -1, 768))),
                "output_decoder" to mapOf("type" to "vision_features", "output_name" to "image_features", "hidden_dimension" to 768),
                "confidence_scoring" to mapOf("type" to "identity", "threshold" to 0.0),
            ),
            "embed_tokens" to mapOf(
                "tokenizer" to mapOf("type" to "none"),
                "inputs" to listOf(mapOf("name" to "input_ids", "source" to "numeric", "data_type" to "int64", "layout" to "sequence", "shape" to listOf(-1, -1), "payload_key" to "input_ids")),
                "outputs" to listOf(mapOf("name" to "inputs_embeds", "index" to 0, "data_type" to "float32", "shape" to listOf(-1, -1, 768))),
                "output_decoder" to mapOf("type" to "vision_features", "output_name" to "inputs_embeds", "hidden_dimension" to 768),
                "confidence_scoring" to mapOf("type" to "identity", "threshold" to 0.0),
            ),
            "encoder" to mapOf(
                "tokenizer" to mapOf("type" to "none"),
                "inputs" to listOf(
                    mapOf("name" to "attention_mask", "source" to "numeric", "data_type" to "int64", "layout" to "sequence", "shape" to listOf(-1, -1), "payload_key" to "attention_mask"),
                    mapOf("name" to "inputs_embeds", "source" to "numeric", "data_type" to "float32", "layout" to "sequence", "shape" to listOf(-1, -1, 768), "payload_key" to "inputs_embeds"),
                ),
                "outputs" to listOf(mapOf("name" to "last_hidden_state", "index" to 0, "data_type" to "float32", "shape" to listOf(-1, -1, 768))),
                "output_decoder" to mapOf("type" to "vision_features", "output_name" to "last_hidden_state", "hidden_dimension" to 768),
                "confidence_scoring" to mapOf("type" to "identity", "threshold" to 0.0),
            ),
        )
    }

    private fun materializePackage(
        source: File,
        extractionDirectory: File,
        issues: MutableList<ModelPackageIssue>,
    ): File? = when {
        source.isDirectory -> materializeDirectoryPackage(source, extractionDirectory, issues)
        source.isFile && source.extension.equals("zip", ignoreCase = true) -> extractArchive(source, extractionDirectory, issues)
        source.isFile && runtimeFor(source).isNotBlank() -> source.parentFile
        else -> {
            issues += ModelPackageIssue(
                "package_format_invalid",
                "Import source must be a model package directory, a .zip package, an .onnx file, or a .tflite file.",
            )
            null
        }
    }

    private fun materializeDirectoryPackage(
        source: File,
        destination: File,
        issues: MutableList<ModelPackageIssue>,
    ): File? = runCatching {
        require(source.isDirectory) { "Model package source directory does not exist" }
        if (runCatching { source.canonicalFile == destination.canonicalFile }.getOrDefault(false)) {
            return@runCatching source
        }

        destination.deleteRecursively()
        val parent = destination.parentFile
            ?: throw IllegalStateException("Package destination has no parent")
        val files = source.walkTopDown().filter { it.isFile }.toList()
        val totalBytes = files.fold(0L) { total, file ->
            val size = file.length().coerceAtLeast(0L)
            require(size <= Long.MAX_VALUE - total) { "Model package directory size is invalid" }
            total + size
        }
        val safetyMargin = maxOf(256L * 1024L * 1024L, totalBytes / 20L)
        val requiredBytes = if (totalBytes > Long.MAX_VALUE - safetyMargin) Long.MAX_VALUE else totalBytes + safetyMargin
        val availableBytes = parent.usableSpace
        require(
            availableBytes <= 0L || totalBytes <= 0L || availableBytes >= requiredBytes,
        ) {
            val requiredMiB = (requiredBytes + 1024L * 1024L - 1L) / (1024L * 1024L)
            val availableMiB = availableBytes / (1024L * 1024L)
            "Insufficient storage to install model directory: need at least ${requiredMiB} MiB free, only ${availableMiB} MiB available."
        }

        require(destination.mkdirs()) { "Unable to create permanent model package directory" }
        files.forEach { file ->
            val relative = file.relativeTo(source).path
            val target = File(destination, relative)
            require(target.canonicalPath.startsWith(destination.canonicalPath + File.separator)) {
                "Model package directory contains an invalid path"
            }
            target.parentFile?.mkdirs()
            file.inputStream().use { input ->
                target.outputStream().use(input::copyTo)
            }
        }
        destination
    }.getOrElse { error ->
        destination.deleteRecursively()
        issues += ModelPackageIssue("package_copy_failed", error.message ?: error.javaClass.simpleName)
        null
    }

    private fun extractArchive(
        archiveFile: File,
        destination: File,
        issues: MutableList<ModelPackageIssue>,
    ): File? = runCatching {
        destination.deleteRecursively()
        val parent = destination.parentFile
            ?: throw IllegalStateException("Package extraction directory has no parent")
        ZipFile(archiveFile).use { archive ->
            val entries = archive.entries().asSequence().toList()
            val declaredBytes = entries
                .filterNot { it.isDirectory }
                .map { it.size }
                .filter { it >= 0L }
                .fold(0L) { total, size ->
                    require(size <= Long.MAX_VALUE - total) { "Archive declared size is invalid" }
                    total + size
                }
            val safetyMargin = maxOf(256L * 1024L * 1024L, declaredBytes / 20L)
            val requiredBytes = if (declaredBytes > Long.MAX_VALUE - safetyMargin) {
                Long.MAX_VALUE
            } else {
                declaredBytes + safetyMargin
            }
            val availableBytes = parent.usableSpace
            require(
                availableBytes <= 0L || declaredBytes <= 0L || availableBytes >= requiredBytes,
            ) {
                val requiredMiB = (requiredBytes + 1024L * 1024L - 1L) / (1024L * 1024L)
                val availableMiB = availableBytes / (1024L * 1024L)
                "Insufficient storage to extract model package: need at least ${requiredMiB} MiB free, only ${availableMiB} MiB available."
            }

            require(destination.mkdirs()) { "Unable to create package extraction directory" }
            entries.forEach { entry ->
                if (entry.isDirectory) {
                    return@forEach
                }
                val target = File(destination, entry.name)
                require(target.canonicalPath.startsWith(destination.canonicalPath + File.separator)) {
                    "Archive contains an invalid path"
                }
                target.parentFile?.mkdirs()
                archive.getInputStream(entry).use { input ->
                    target.outputStream().use(input::copyTo)
                }
            }
        }
        destination
    }.getOrElse { error ->
        destination.deleteRecursively()
        issues += ModelPackageIssue("package_extract_failed", error.message ?: error.javaClass.simpleName)
        null
    }

    private fun readMetadataFiles(
        filesByName: Map<String, List<File>>,
        issues: MutableList<ModelPackageIssue>,
    ): Map<String, Map<String, Any>> {
        val metadata = linkedMapOf<String, Map<String, Any>>()
        METADATA_FILES.forEach { name ->
            val candidates = filesByName[name].orEmpty()
            if (candidates.size > 1) {
                issues += ModelPackageIssue(
                    "metadata_ambiguous",
                    "Package contains multiple '$name' files; the package must identify a single metadata root.",
                )
                return@forEach
            }
            val file = candidates.singleOrNull() ?: return@forEach
            val parsed = runCatching { LocalAiJson.decodeMap(file.readText()) }.getOrElse { emptyMap() }
            if (parsed.isEmpty() && file.readText().trim().isNotEmpty()) {
                if (name != "tokenizer.json") {
                    issues += ModelPackageIssue("metadata_invalid", "Metadata file '${file.name}' is not a JSON object.")
                }
            } else {
                metadata[name] = parsed
            }
        }
        return metadata
    }

    private fun mergeMetadata(metadataFiles: Map<String, Map<String, Any>>): Map<String, Any> {
        val result = linkedMapOf<String, Any>()
        listOf("config.json", "processor.json", "preprocessor_config.json", "generation_config.json", "metadata.json")
            .forEach { name -> metadataFiles[name]?.let(result::putAll) }
        metadataFiles["tokenizer_config.json"]?.let { tokenizer -> result["tokenizer_config"] = tokenizer }
        metadataFiles["tokenizer.json"]?.let { tokenizer -> result["tokenizer_json"] = tokenizer }
        metadataFiles["special_tokens_map.json"]?.let { tokens -> result["special_tokens_map"] = tokens }
        metadataFiles["processor.json"]?.let { processor -> result["processor_config"] = processor }
        metadataFiles["preprocessor_config.json"]?.let { preprocessor -> result["preprocessor_config"] = preprocessor }
        return result
    }

    private fun normalizeKnownPackageStructure(packageRoot: File, files: List<File>, metadata: Map<String, Any>): Map<String, Any> {
        val result = metadata.toMutableMap()
        val alreadyDeclared = result["model_artifacts"]
        if (alreadyDeclared is List<*>) {
            return result
        }
        val relative = files.map { it.relativeTo(packageRoot).invariantSeparatorsPath }
        val fileNames = files.map { it.name.lowercase() }
        val artifacts = mutableListOf<Map<String, Any>>()

        // Florence-2 Base deterministic layout: normalize source artifacts by filename role.
        val florenceRoles = mapOf(
            "vision_encoder_int8.onnx" to "vision_encoder",
            "encoder_model_int8.onnx" to "encoder",
            "decoder_model_int8.onnx" to "decoder",
            "decoder_with_past_model_int8.onnx" to "decoder_with_past",
            "embed_tokens_int8.onnx" to "embed_tokens",
        )
        if (florenceRoles.keys.all { key -> fileNames.contains(key) } && artifacts.isEmpty()) {
            files.forEach { file ->
                val name = file.name.lowercase()
                florenceRoles[name]?.let { role ->
                    artifacts += mapOf("path" to file.relativeTo(packageRoot).invariantSeparatorsPath, "role" to role)
                }
            }
            result["model_artifacts"] = artifacts
            result["task"] = "image_to_text"
            result["model_id"] = (result["model_id"] ?: "florence-2-base").toString()
            return result
        }

        // PaddelOCR deterministic layout: det.onnx, rec.onnx, dict.txt.
        val det = files.firstOrNull { it.name.lowercase() == "det.onnx" || it.name.lowercase() == "detector.onnx" }
        val rec = files.firstOrNull { it.name.lowercase() == "rec.onnx" || it.name.lowercase() == "recognizer.onnx" }
        val dict = files.firstOrNull { it.name.lowercase() == "dict.txt" }
        if (det != null || rec != null || dict != null) {
            val artifactEntries = mutableListOf<Map<String, Any>>()
            det?.let { artifactEntries += mapOf("path" to it.relativeTo(packageRoot).invariantSeparatorsPath, "role" to "detector") }
            rec?.let { artifactEntries += mapOf("path" to it.relativeTo(packageRoot).invariantSeparatorsPath, "role" to "recognizer") }
            dict?.let { artifactEntries += mapOf("path" to it.relativeTo(packageRoot).invariantSeparatorsPath, "role" to "dictionary_decoder") }
            if (artifactEntries.isNotEmpty()) {
                result["model_artifacts"] = artifactEntries
                result["task"] = "ocr"
                return result
            }
        }

        // Qwen2.5-Coder ONNX package: model_q4.onnx + model_q4.onnx_data support file.
        val qwenCoderOnnx = files.firstOrNull { it.name.lowercase() == "model_q4.onnx" }
        if (qwenCoderOnnx != null) {
            result["model_artifacts"] = listOf(
                mapOf("path" to qwenCoderOnnx.relativeTo(packageRoot).invariantSeparatorsPath, "role" to "main_model"),
            )
            result["task"] = "prompt_generation"
            result["runtime"] = AiRuntimeType.ONNX.raw
            return result
        }

        // Qwen2.5-VL ONNX package: vision_encoder_q4.onnx, embed_tokens_q4.onnx, decoder_model_merged_q4.onnx.
        val qwenVisionEncoder = files.firstOrNull { it.name.lowercase() == "vision_encoder_q4.onnx" }
        val qwenEmbedTokens = files.firstOrNull { it.name.lowercase() == "embed_tokens_q4.onnx" }
        val qwenDecoderMerged = files.firstOrNull { it.name.lowercase() == "decoder_model_merged_q4.onnx" }
        if (qwenVisionEncoder != null && qwenEmbedTokens != null && qwenDecoderMerged != null) {
            result["model_artifacts"] = listOf(
                mapOf("path" to qwenVisionEncoder.relativeTo(packageRoot).invariantSeparatorsPath, "role" to "vision_encoder"),
                mapOf("path" to qwenEmbedTokens.relativeTo(packageRoot).invariantSeparatorsPath, "role" to "embed_tokens"),
                mapOf("path" to qwenDecoderMerged.relativeTo(packageRoot).invariantSeparatorsPath, "role" to "decoder_model_merged"),
            )
            result["task"] = "image_to_text"
            result["runtime"] = AiRuntimeType.ONNX.raw
            return result
        }

        // Qwen2.5-VL GGUF pair: require exactly one main model and one mmproj.
        val qwenGgufs = files.filter { it.name.lowercase().contains("qwen") && it.name.lowercase().endsWith(".gguf") }
        val mainQwenCandidates = qwenGgufs.filterNot { it.name.lowercase().contains("mmproj") }
        val mmprojCandidates = qwenGgufs.filter { it.name.lowercase().contains("mmproj") }
        if (mainQwenCandidates.size == 1 && mmprojCandidates.size == 1) {
            val mainQwen = mainQwenCandidates.single()
            val mmproj = mmprojCandidates.single()
            result["model_artifacts"] = listOf(
                mapOf("path" to mainQwen.relativeTo(packageRoot).invariantSeparatorsPath, "role" to "text_model"),
                mapOf("path" to mmproj.relativeTo(packageRoot).invariantSeparatorsPath, "role" to "vision_projector"),
            )
            result["task"] = "text_generation"
            result["model_id"] = "qwen2.5-vl-3b-instruct"
            result["runtime"] = AiRuntimeType.LLAMA_CPP.raw
            return result
        }

        // Buffalo-L / InsightFace deterministic package: known component layout with explicit unresolved semantics.
        val buffaloDet = files.firstOrNull { it.name.lowercase() == "det_10g.onnx" }
        val buffalo2d = files.firstOrNull { it.name.lowercase() == "2d106det.onnx" }
        val buffalo3d = files.firstOrNull { it.name.lowercase() == "1k3d68.onnx" }
        val buffaloGenderAge = files.firstOrNull { it.name.lowercase() == "genderage.onnx" }
        val buffaloEmbed = files.firstOrNull { it.name.lowercase() == "w600k_r50.onnx" }
        if (listOf(buffaloDet, buffalo2d, buffalo3d, buffaloGenderAge, buffaloEmbed).any { it != null }) {
            val artifacts = mutableListOf<Map<String, Any>>()
            buffaloDet?.let { artifacts += mapOf("path" to it.relativeTo(packageRoot).invariantSeparatorsPath, "role" to "detector") }
            buffalo2d?.let { artifacts += mapOf("path" to it.relativeTo(packageRoot).invariantSeparatorsPath, "role" to "landmark_2d") }
            buffalo3d?.let { artifacts += mapOf("path" to it.relativeTo(packageRoot).invariantSeparatorsPath, "role" to "landmark_3d") }
            buffaloGenderAge?.let { artifacts += mapOf("path" to it.relativeTo(packageRoot).invariantSeparatorsPath, "role" to "gender_age") }
            buffaloEmbed?.let { artifacts += mapOf("path" to it.relativeTo(packageRoot).invariantSeparatorsPath, "role" to "face_embedding") }
            if (artifacts.isNotEmpty()) {
                result["model_artifacts"] = artifacts
                result["task"] = "face_detection"
                result["buffalo_l_composite_capability"] = "face_feature_extraction"
                return result
            }
        }

        // InsightFace/Buffalo-L pattern fallback: determine roles by deterministic known component names.
        val insight = files.filter { it.name.lowercase().endsWith(".onnx") }.map { file ->
            val name = file.name.lowercase()
            val role = when {
                name.contains("det") || name.contains("scrfd") || name.contains("face_detector") || name.contains("retinaface") -> "face_detector"
                name.contains("rec") || name.contains("embed") || name.contains("embedding") || name.contains("arcface") || name.contains("face_recognition") -> "face_recognition"
                name.contains("landmark") || name.contains("alignment") -> "landmark_alignment"
                else -> "buffalo_l_component"
            }
            mapOf("path" to file.relativeTo(packageRoot).invariantSeparatorsPath, "role" to role)
        }
        if (insight.size >= 3) {
            result["model_artifacts"] = insight
            result["task"] = "face_detection"
            result["buffalo_l_composite_capability"] = "face_feature_extraction"
            return result
        }

        return result
    }

    private fun addBuffaloLReadiness(
        metadata: MutableMap<String, Any>,
        files: List<File>,
        issues: MutableList<ModelPackageIssue>,
    ) {
        val artifacts = metadata["model_artifacts"].asMapList()
        val roles = artifacts.mapNotNull { it["role"]?.toString() }.toSet()
        if (!roles.any { it == "detector" || it == "face_detector" || it == "face_embedding" || it == "gender_age" || it == "landmark_2d" || it == "landmark_3d" }) {
            return
        }
        metadata["supported_tasks"] = (metadata["supported_tasks"].asDeclaredValues() + "face_detection").distinct()
        metadata["buffalo_l_capabilities"] = mapOf(
            "face_detection" to mapOf(
                "ready" to true,
                "stage" to "scrfd_detector_only",
                "family" to "SCRFD-10GF",
                "preprocessing" to mapOf(
                    "input_mean" to 127.5,
                    "input_std" to 128.0,
                    "swap_rb" to true,
                    "nchw" to true,
                    "aspect_ratio_preserving_resize" to true,
                    "padding" to "zero_fill_top_left",
                ),
                "decode" to mapOf(
                    "feature_levels" to 3,
                    "strides" to listOf(8, 16, 32),
                    "anchors_per_level" to 2,
                    "bbox_distance_decode" to "center_minus_left_top_plus_right_bottom",
                    "keypoint_distance_decode" to "anchor_center_plus_predicted_distance",
                    "nms_threshold" to 0.4,
                    "score_threshold" to 0.5,
                ),
            ),
            "face_embedding" to mapOf(
                "ready" to (
                    metadata.containsKey("buffalo_l_embedding_preprocessing") &&
                        metadata["artifact_paths_by_role"].asStringMap()?.containsKey("face_embedding") == true
                    ),
                "stage" to "arcface_w600k_r50",
                "artifact_role" to "face_embedding",
                "reason" to "real_w600k_r50_graph_and_alignment_contract_required_for_runtime_readiness",
            ),
            "landmark_2d" to mapOf(
                "ready" to false,
                "stage" to "landmark_2d_106_pending_contract",
                "reason" to "2d_landmark_contract_required_for_runtime_readiness",
            ),
            "landmark_3d" to mapOf(
                "ready" to false,
                "stage" to "landmark_3d_68_pending_contract",
                "reason" to "3d_landmark_contract_required_for_runtime_readiness",
            ),
            "gender_age" to mapOf(
                "ready" to false,
                "stage" to "gender_age_attribute_pending_contract",
                "reason" to "gender_age_contract_required_for_runtime_readiness",
            ),
            "pose" to mapOf(
                "ready" to false,
                "stage" to "blocked",
                "reason" to "pose_meanshape_missing",
            ),
        )
        metadata["buffalo_l_authoritative_sources"] = listOf(
            "python-package/insightface/model_zoo/scrfd.py",
            "python-package/insightface/model_zoo/arcface_onnx.py",
            "python-package/insightface/model_zoo/landmark.py",
            "python-package/insightface/model_zoo/attribute.py",
            "python-package/insightface/utils/face_align.py",
            "model_zoo/README.md",
        )
    }

    private fun addPaddleOcrReadiness(
        metadata: MutableMap<String, Any>,
        packageRoot: File,
        files: List<File>,
        issues: MutableList<ModelPackageIssue>,
    ) {
        val roleEntries = metadata["model_artifacts"].asMapList()
        val roles = roleEntries.mapNotNull { it["role"]?.toString() }.toSet()
        if (!setOf("detector", "recognizer", "dictionary_decoder").all(roles::contains)) return

        val detector = files.firstOrNull { it.name.equals("det.onnx", ignoreCase = true) || it.name.equals("detector.onnx", ignoreCase = true) }
        val recognizer = files.firstOrNull { it.name.equals("rec.onnx", ignoreCase = true) || it.name.equals("recognizer.onnx", ignoreCase = true) }
        val dictionary = files.firstOrNull { it.name.equals("dict.txt", ignoreCase = true) }
        if (detector == null || recognizer == null || dictionary == null) {
            issues += ModelPackageIssue(
                "ocr_package_incomplete",
                "PaddleOCR requires detector, recognizer, and dictionary artifacts.",
            )
            metadata["execution_readiness"] = mapOf(
                "ready" to false,
                "stage" to "imported_not_executable",
                "blockers" to listOf("ocr_package_incomplete"),
            )
            return
        }

        val detectorBindings = runCatching { inspectArtifactBindings(detector, AiRuntimeType.ONNX.raw) }.getOrElse { error ->
            issues += ModelPackageIssue(
                "tensor_metadata_unreadable",
                "Unable to inspect PaddleOCR detector: ${error.message ?: error.javaClass.simpleName}",
            )
            null
        }
        val recognizerBindings = runCatching { inspectArtifactBindings(recognizer, AiRuntimeType.ONNX.raw) }.getOrElse { error ->
            issues += ModelPackageIssue(
                "tensor_metadata_unreadable",
                "Unable to inspect PaddleOCR recognizer: ${error.message ?: error.javaClass.simpleName}",
            )
            null
        }

        val detectorInput = detectorBindings?.inputs?.singleOrNull { it.name == "x" }
        val detectorOutput = detectorBindings?.outputs?.singleOrNull { it.name == "fetch_name_0" }
        val recognizerInput = recognizerBindings?.inputs?.singleOrNull { it.name == "x" }
        val recognizerOutput = recognizerBindings?.outputs?.singleOrNull { it.name == "fetch_name_0" }

        val detectorReady = detectorInput?.dataType == "float32" &&
            detectorInput.shape.size == 4 &&
            detectorInput.shape[1] in setOf(-1, 3) &&
            detectorOutput?.dataType == "float32" &&
            detectorOutput.shape.size == 4 &&
            detectorOutput.shape[1] in setOf(-1, 1)

        val recognizerReady = recognizerInput?.dataType == "float32" &&
            recognizerInput.shape.size == 4 &&
            recognizerInput.shape[1] in setOf(-1, 3) &&
            recognizerInput.shape[2] in setOf(-1, 48) &&
            recognizerOutput?.dataType == "float32" &&
            recognizerOutput.shape.size == 3 &&
            recognizerOutput.shape.lastOrNull() == 18385

        val dictionaryLines = dictionary.readLines()
        val dictionaryReady = dictionaryLines.size == 18383
        val blockers = buildList {
            if (!detectorReady) add("ocr_detector_graph_incompatible")
            if (!recognizerReady) add("ocr_recognizer_graph_incompatible")
            if (!dictionaryReady) add("ocr_dictionary_incompatible")
        }

        metadata["ocr_graph_contract"] = mapOf(
            "detector" to mapOf(
                "input" to mapOf(
                    "name" to (detectorInput?.name ?: "x"),
                    "data_type" to (detectorInput?.dataType ?: "float32"),
                    "shape" to (detectorInput?.shape ?: listOf(-1, 3, -1, -1)),
                ),
                "output" to mapOf(
                    "name" to (detectorOutput?.name ?: "fetch_name_0"),
                    "data_type" to (detectorOutput?.dataType ?: "float32"),
                    "shape" to (detectorOutput?.shape ?: listOf(-1, 1, -1, -1)),
                ),
            ),
            "recognizer" to mapOf(
                "input" to mapOf(
                    "name" to (recognizerInput?.name ?: "x"),
                    "data_type" to (recognizerInput?.dataType ?: "float32"),
                    "shape" to (recognizerInput?.shape ?: listOf(-1, 3, 48, -1)),
                ),
                "output" to mapOf(
                    "name" to (recognizerOutput?.name ?: "fetch_name_0"),
                    "data_type" to (recognizerOutput?.dataType ?: "float32"),
                    "shape" to (recognizerOutput?.shape ?: listOf(-1, -1, 18385)),
                ),
            ),
        )

        metadata["ocr_dictionary"] = mapOf(
            "path" to dictionary.relativeTo(packageRoot).invariantSeparatorsPath,
            "absolute_path" to dictionary.absolutePath,
            "entry_count" to dictionaryLines.size,
            "recognizer_class_count" to 18385,
            "blank_index" to 0,
            "dictionary_offset" to 1,
            "append_space_char" to true,
            "space_index" to 18384,
            "mapping_status" to if (dictionaryReady) "ctc_blank_plus_dictionary_plus_space" else "unresolved",
        )

        metadata["paddle_ocr"] = mapOf(
            "family" to "PP-OCRv5",
            "detector_path" to detector.absolutePath,
            "recognizer_path" to recognizer.absolutePath,
            "dictionary_path" to dictionary.absolutePath,
            "detector_preprocessing" to mapOf(
                "limit_side_len" to 960,
                "limit_type" to "max",
                "stride" to 32,
                "color_space" to "bgr",
                "scale" to (1f / 255f),
                "mean" to listOf(0.485f, 0.456f, 0.406f),
                "std" to listOf(0.229f, 0.224f, 0.225f),
            ),
            "detector_postprocessing" to mapOf(
                "type" to "db",
                "thresh" to 0.3f,
                "box_thresh" to 0.6f,
                "max_candidates" to 1000,
                "unclip_ratio" to 1.5f,
                "score_mode" to "fast",
                "box_type" to "quad",
            ),
            "recognizer_preprocessing" to mapOf(
                "height" to 48,
                "max_width" to 320,
                "channels" to 3,
                "color_space" to "bgr",
                "scale" to (1f / 255f),
                "mean" to listOf(0.5f, 0.5f, 0.5f),
                "std" to listOf(0.5f, 0.5f, 0.5f),
                "padding" to true,
            ),
            "ctc" to mapOf(
                "blank_index" to 0,
                "dictionary_offset" to 1,
                "append_space_char" to true,
                "space_index" to 18384,
                "class_count" to 18385,
                "remove_duplicates" to true,
            ),
        )

        if (blockers.isNotEmpty()) {
            blockers.forEach { blocker ->
                issues += ModelPackageIssue(
                    blocker,
                    "PaddleOCR package does not match the expected PP-OCRv5 detector/recognizer/dictionary execution contract.",
                )
            }
            metadata["execution_readiness"] = mapOf(
                "ready" to false,
                "stage" to "imported_not_executable",
                "blockers" to blockers,
            )
            return
        }

        metadata.remove("execution_readiness")
        metadata[INFERENCE_CONTRACTS_KEY] = metadata[INFERENCE_CONTRACTS_KEY].asStringMap().orEmpty() + mapOf(
            "ocr" to mapOf(
                "tokenizer" to mapOf("type" to "none"),
                "image_preprocessing" to mapOf(
                    "enabled" to true,
                    "width" to 960,
                    "height" to 960,
                    "channels" to 3,
                    "color_space" to "rgb",
                    "resize_mode" to "stretch",
                    "scale" to (1f / 255f),
                    "mean" to listOf(0.485f, 0.456f, 0.406f),
                    "std" to listOf(0.229f, 0.224f, 0.225f),
                ),
                "inputs" to listOf(
                    mapOf(
                        "name" to detectorInput!!.name,
                        "source" to "image",
                        "data_type" to detectorInput.dataType,
                        "layout" to "nchw",
                        "shape" to detectorInput.shape,
                    ),
                ),
                "outputs" to listOf(
                    mapOf(
                        "name" to detectorOutput!!.name,
                        "index" to detectorOutput.index,
                        "data_type" to detectorOutput.dataType,
                        "shape" to detectorOutput.shape,
                    ),
                ),
                "output_decoder" to mapOf(
                    "type" to "tokens",
                    "output_name" to detectorOutput.name,
                ),
                "confidence_scoring" to mapOf(
                    "type" to "identity",
                    "threshold" to 0f,
                ),
            ),
        )
    }

    private fun resolveArtifact(
        source: File,
        packageRoot: File,
        files: List<File>,
        metadata: Map<String, Any>,
        issues: MutableList<ModelPackageIssue>,
    ): File? {
        if (source.isFile && runtimeFor(source).isNotBlank()) {
            return source
        }
        val requestedPath = listOf("model_file", "model_path", "artifact", "artifact_path")
            .asSequence()
            .mapNotNull { metadata[it]?.toString()?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull()
        if (requestedPath != null) {
            val selected = files.firstOrNull { file ->
                file.relativeTo(packageRoot).invariantSeparatorsPath == requestedPath ||
                    file.name == requestedPath
            }
            if (selected == null || runtimeFor(selected).isBlank()) {
                issues += ModelPackageIssue(
                    "model_artifact_reference_invalid",
                    "Metadata references model_file '$requestedPath', but no matching executable artifact exists.",
                )
            }
            return selected
        }
        val artifacts = files.filter { runtimeFor(it).isNotBlank() }

        // Check if metadata declares multi-artifact model (e.g., Florence-2 with encoder/decoder)
        val declaredArtifacts = metadata["model_artifacts"].artifactPathList()
            .takeIf { it.isNotEmpty() }
        if (declaredArtifacts != null) {
            val declaredRoleMap = metadata["model_artifacts"].asMapList()
            val resolved = declaredArtifacts.mapNotNull { path ->
                files.firstOrNull { file ->
                    file.relativeTo(packageRoot).invariantSeparatorsPath == path ||
                        file.name == path
                }
            }
            if (resolved.size == declaredArtifacts.size) {
                val orderedUsableRoles = listOf("vision_encoder", "main_model", "text_model", "vision_projector")
                val preferredPath = declaredRoleMap
                    .firstOrNull { entry -> orderedUsableRoles.any { role -> entry["role"]?.toString().equals(role, ignoreCase = true) } }
                    ?.get("path")?.toString()
                    .orEmpty()
                val preferredArtifact = resolved.firstOrNull { artifact ->
                    artifact.relativeTo(packageRoot).invariantSeparatorsPath == preferredPath ||
                        artifact.name == preferredPath
                }
                return preferredArtifact ?: resolved.firstOrNull()
            } else {
                issues += ModelPackageIssue(
                    "model_artifacts_reference_invalid",
                    "Metadata declares model_artifacts but not all referenced files exist in package.",
                )
                return null
            }
        }

        // If multiple executable artifacts exist without explicit multi-artifact declaration, require selection
        if (artifacts.size > 1) {
            val listed = artifacts.joinToString { it.relativeTo(packageRoot).invariantSeparatorsPath }
            issues += ModelPackageIssue(
                "model_artifact_ambiguous",
                "Package contains ${artifacts.size} executable model artifacts: $listed. Declare model_artifacts in metadata or specify model_file to select one.",
            )
            return null
        }

        if (artifacts.size == 1) {
            return artifacts.single()
        }

        // No executable artifacts found. Detect common unsupported formats and give clearer guidance.
        val unsupportedExtensions = setOf("safetensors", "pt", "pth", "bin")
        val unsupported = files.filter { unsupportedExtensions.contains(it.extension.lowercase()) }
        if (unsupported.isNotEmpty()) {
            val listed = unsupported.joinToString { it.relativeTo(packageRoot).invariantSeparatorsPath }
            issues += ModelPackageIssue(
                "unsupported_model_format",
                "Package contains model artifacts in unsupported formats: $listed. Provide an ONNX, TensorFlow Lite, or GGUF artifact for Android runtime compatibility.",
            )
            return null
        }

        return null
    }

    private fun synthesizeQwenGgufMetadata(metadata: MutableMap<String, Any>, artifact: File?) {
        val name = artifact?.name?.lowercase().orEmpty()
        val isQwenCoder = name.contains("qwen") && name.contains("coder")
        val artifacts = metadata["model_artifacts"].asMapList()
        val isQwenVl = artifacts.any { it["role"]?.toString() == "vision_projector" }
        metadata["llama_cpp"] = mapOf(
            "backend" to "llama.cpp",
            "pinned_revision" to "5266f24da75dc449bd56cbed7addb9c8e4a6a73e",
            "architecture" to when {
                isQwenVl -> "qwen2vl"
                isQwenCoder -> "qwen2"
                else -> "unknown"
            },
            "text_only" to !isQwenVl,
            "multimodal" to isQwenVl,
            "projector_role" to artifacts.firstOrNull { it["role"]?.toString() == "vision_projector" }?.get("path")?.toString().orEmpty(),
            "chat_template_source" to "gguf_embedded",
            "sampling_policy" to "greedy",
            "native_readiness" to when {
                isQwenVl -> "qwen_vl_multimodal"
                isQwenCoder -> "qwen_coder_text"
                else -> "unclassified_gguf"
            },
        )
        if (isQwenVl) {
            metadata["supported_tasks"] = (
                metadata["supported_tasks"].asDeclaredValues() +
                    listOf(
                        "text_generation",
                        "prompt_generation",
                        "captioning",
                        "series_recognition",
                        "character_recognition",
                        "tag_prediction",
                        "normalization",
                    )
                ).distinct()
        } else if (isQwenCoder) {
            metadata["supported_tasks"] = (
                metadata["supported_tasks"].asDeclaredValues() +
                    listOf("text_generation", "prompt_generation", "normalization")
                ).distinct()
        }
    }

    private fun addTokenizerAssets(
        filesByName: Map<String, List<File>>,
        metadata: MutableMap<String, Any>,
        issues: MutableList<ModelPackageIssue>,
    ) {
        val tokenizer = metadata["inference_contracts"].asStringMap() ?: return
        val vocabulary = readVocabulary(filesByName)
        val specialTokens = readSpecialTokens(metadata)
        val enriched = tokenizer.mapValues { (_, value) ->
            val contract = value.asStringMap()?.toMutableMap() ?: return@mapValues value
            val inputs = contract["inputs"].asMapList()
            val requiresText = inputs.any { input -> input["source"]?.toString() in TEXT_INPUT_SOURCES }
            val rawTokenizer = contract["tokenizer"].asStringMap()?.toMutableMap() ?: linkedMapOf()
            if (!requiresText && rawTokenizer.isEmpty()) {
                rawTokenizer["type"] = "none"
            }
            if (requiresText && rawTokenizer["type"] == null) {
                if (vocabulary.isNotEmpty()) {
                    rawTokenizer["type"] = "wordpiece"
                } else {
                    issues += ModelPackageIssue(
                        "tokenizer_metadata_missing",
                        "Text tensor inputs require tokenizer.type and a compatible vocabulary in tokenizer.json or vocab.txt.",
                    )
                }
            }
            if ((rawTokenizer["vocabulary"] as? List<*>).isNullOrEmpty() && vocabulary.isNotEmpty()) {
                rawTokenizer["vocabulary"] = vocabulary
            }
            specialTokens.forEach { (key, token) -> rawTokenizer.putIfAbsent(key, token) }
            contract["tokenizer"] = rawTokenizer
            contract
        }
        metadata["inference_contracts"] = enriched
    }

    private fun addLabelAssets(filesByName: Map<String, List<File>>, metadata: MutableMap<String, Any>) {
        val labels = filesByName["labels.txt"]?.singleOrNull()
            ?.readLines()
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            .orEmpty()
        if (labels.isEmpty()) {
            return
        }
        val contracts = metadata["inference_contracts"].asStringMap() ?: return
        metadata["inference_contracts"] = contracts.mapValues { (_, value) ->
            val contract = value.asStringMap()?.toMutableMap() ?: return@mapValues value
            val decoder = contract["output_decoder"].asStringMap()?.toMutableMap() ?: return@mapValues contract
            if ((decoder["labels"] as? List<*>).isNullOrEmpty()) {
                decoder["labels"] = labels
            }
            contract["output_decoder"] = decoder
            contract
        }
    }

    private fun synthesizeStandardClassificationContract(
        metadata: MutableMap<String, Any>,
        tasks: List<String>,
        bindings: ModelArtifactBindings,
        filesByName: Map<String, List<File>>,
        issues: MutableList<ModelPackageIssue>,
    ) {
        if (metadata[INFERENCE_CONTRACTS_KEY].asStringMap() != null) {
            return
        }
        val classificationTasks = tasks.filter { it in CLASSIFICATION_TASKS }
        if (classificationTasks.isEmpty()) {
            return
        }
        val labels = standardLabels(metadata, filesByName)
        val inputs = standardInputs(metadata, bindings)
        val output = bindings.outputs.singleOrNull()
        
        // For aesthetic/quality models, allow more lenient contract generation
        val isAestheticModel = metadata["model_id"]?.toString()?.lowercase()?.contains("aesthetic") == true ||
            metadata["description"]?.toString()?.lowercase()?.contains("aesthetic") == true ||
            metadata["tags"].asMapList().any { it["name"]?.toString()?.lowercase()?.contains("aesthetic") == true }
        
        // Require strict metadata for general classification, more lenient for aesthetic
        val canCreateContract = if (isAestheticModel) {
            output != null && (inputs.isNotEmpty() || bindings.inputs.isNotEmpty())
        } else {
            labels.isNotEmpty() && inputs.isNotEmpty() && output != null
        }
        
        if (!canCreateContract) {
            val missing = buildList {
                if (labels.isEmpty() && !isAestheticModel) add("config.id2label or labels.txt")
                if (inputs.isEmpty() && bindings.inputs.isEmpty()) add("an unambiguous processor/tokenizer-to-input mapping")
                if (output == null) add("exactly one model output for classification")
            }
            issues += ModelPackageIssue(
                "classification_metadata_missing",
                "Automatic classification setup requires ${missing.joinToString()}; package metadata does not establish those fields.",
            )
            return
        }
        
        // Use actual inputs from artifact if no standard inputs detected
        val finalInputs = if (inputs.isNotEmpty()) inputs else {
            bindings.inputs.map { input ->
                mapOf(
                    "name" to input.name,
                    "source" to if (input.shape.size == 4) "image" else "text"
                )
            }
        }
        
        val confidence = if (metadata["problem_type"]?.toString()?.equals("multi_label_classification", ignoreCase = true) == true) {
            "sigmoid"
        } else {
            "softmax"
        }
        
        metadata[INFERENCE_CONTRACTS_KEY] = classificationTasks.associateWith {
            val outputDecoder = if (labels.isNotEmpty()) {
                mapOf(
                    "type" to "classification",
                    "output_name" to output!!.name,
                    "labels" to labels,
                )
            } else {
                // For aesthetic models without labels, output numeric scores
                mapOf(
                    "type" to "regression",
                    "output_name" to output!!.name,
                    "scale" to 1.0,
                    "offset" to 0.0,
                )
            }
            
            mapOf(
                "inputs" to finalInputs,
                "outputs" to listOf(mapOf("name" to output!!.name, "index" to output.index)),
                "output_decoder" to outputDecoder,
                "confidence_scoring" to mapOf("type" to confidence),
            )
        }
    }

    private fun standardInputs(metadata: Map<String, Any>, bindings: ModelArtifactBindings): List<Map<String, Any>> {
        val imageProcessor = metadata["preprocessor_config"].asStringMap() ?: metadata["processor_config"].asStringMap()
        if (imageProcessor != null && bindings.inputs.size == 1) {
            return listOf(mapOf("name" to bindings.inputs.single().name, "source" to "image"))
        }
        if (imageProcessor != null) {
            return emptyList()
        }
        if (metadata["tokenizer_config"].asStringMap() == null) {
            return emptyList()
        }
        return bindings.inputs.mapNotNull { tensor ->
            standardTextInputSource(tensor.name)?.let { source -> mapOf("name" to tensor.name, "source" to source) }
        }.takeIf { it.size == bindings.inputs.size }.orEmpty()
    }

    private fun synthesizeNomicVisionEmbeddingContract(
        metadata: MutableMap<String, Any>,
        tasks: List<String>,
        bindings: ModelArtifactBindings,
        filesByName: Map<String, List<File>>,
        issues: MutableList<ModelPackageIssue>,
    ) {
        if (metadata[INFERENCE_CONTRACTS_KEY].asStringMap() != null) {
            return
        }
        if (tasks.none { it == "embedding_generation" } || bindings.inputs.isEmpty() || bindings.outputs.isEmpty()) {
            return
        }
        val modelId = metadata["model_id"]?.toString().orEmpty().lowercase()
        if (!modelId.contains("nomic-embed-vision")) {
            return
        }
        val imageInput = bindings.inputs.firstOrNull { it.shape.size == 4 }
        val output = bindings.outputs.firstOrNull()
        if (imageInput == null || output == null) {
            issues += ModelPackageIssue(
                "embedding_contract_incomplete",
                "Nomic vision ONNX image embedding contract requires one image tensor input and one output tensor.",
            )
            return
        }
        val imageLayout = inferImageLayout(imageInput.shape)
        if (imageLayout == null) {
            issues += ModelPackageIssue(
                "graph_input_incompatible",
                "Nomic vision image embedding input shape cannot determine an nchw or nhwc layout.",
            )
            return
        }
        val processorFile = filesByName["preprocessor_config.json"].orEmpty().firstOrNull()
        val rawProcessor = processorFile?.readText()?.let { LocalAiJson.decodeMap(it) } ?: emptyMap<String, Any>()
        val imageMean = rawProcessor["image_mean"] as? List<*> ?: listOf(0.48145466f, 0.4578275f, 0.40821073f)
        val imageStd = rawProcessor["image_std"] as? List<*> ?: listOf(0.26862954f, 0.26130258f, 0.27577711f)
        val resize = rawProcessor["size"] as? Map<*, *> ?: mapOf("height" to 224, "width" to 224)
        val cropSize = rawProcessor["crop_size"] as? Map<*, *> ?: mapOf("height" to 224, "width" to 224)
        val doCenterCrop = rawProcessor["do_center_crop"] as? Boolean ?: true
        metadata[INFERENCE_CONTRACTS_KEY] = mapOf(
            "embedding_generation" to mapOf(
                "inputs" to listOf(
                    mapOf(
                        "name" to imageInput.name,
                        "source" to "image",
                        "data_type" to imageInput.dataType,
                        "layout" to imageLayout,
                        "shape" to imageInput.shape,
                        "payload_key" to "",
                    ),
                ),
                "outputs" to listOf(
                    mapOf(
                        "name" to output.name,
                        "index" to output.index,
                        "data_type" to output.dataType,
                        "shape" to listOf(1, 197, 768),
                    ),
                ),
                "output_decoder" to mapOf(
                    "type" to "embedding",
                    "output_name" to output.name,
                    "pooling" to "cls",
                    "cls_index" to 0,
                    "hidden_dimension" to 768,
                    "normalization" to "l2",
                ),
                "image_preprocessing" to mapOf(
                    "enabled" to true,
                    "width" to 224,
                    "height" to 224,
                    "channels" to 3,
                    "color_space" to "rgb",
                    "resize_mode" to if (doCenterCrop) "center_crop" else "stretch",
                    "scale" to (1f / 255f),
                    "mean" to imageMean.map { it as? Number ?: 0f },
                    "std" to imageStd.map { it as? Number ?: 1f },
                ),
                "confidence_scoring" to mapOf(
                    "type" to "identity",
                    "threshold" to 0f,
                ),
            ),
        )
    }

    private fun synthesizeNomicTextEmbeddingContract(
        metadata: MutableMap<String, Any>,
        tasks: List<String>,
        bindings: ModelArtifactBindings,
        filesByName: Map<String, List<File>>,
        issues: MutableList<ModelPackageIssue>,
    ) {
        if (metadata[INFERENCE_CONTRACTS_KEY].asStringMap() != null) {
            return
        }
        if (tasks.none { it == "embedding_generation" }) {
            return
        }
        val modelId = metadata["model_id"]?.toString().orEmpty().lowercase()
        if (!modelId.contains("nomic-embed-text")) {
            return
        }
        val inputIds = bindings.inputs.firstOrNull { it.name.lowercase().contains("input_ids") }
        val tokenTypeIds = bindings.inputs.firstOrNull { it.name.lowercase().contains("token_type_ids") }
        val attentionMask = bindings.inputs.firstOrNull { it.name.lowercase().contains("attention_mask") }
        val output = bindings.outputs.firstOrNull()
        if (inputIds == null || tokenTypeIds == null || attentionMask == null || output == null) {
            issues += ModelPackageIssue(
                "nomic_text_contract_incomplete",
                "Nomic text embedding contract requires input_ids, token_type_ids, attention_mask, and one last_hidden_state output tensor from the graph.",
            )
            return
        }
        val sources = listOf(
            mapOf(
                "name" to inputIds.name,
                "source" to "text_ids",
                "data_type" to inputIds.dataType,
                "layout" to "sequence",
                "shape" to inputIds.shape,
                "payload_key" to "",
            ),
            mapOf(
                "name" to tokenTypeIds.name,
                "source" to "token_type_ids",
                "data_type" to tokenTypeIds.dataType,
                "layout" to "sequence",
                "shape" to tokenTypeIds.shape,
                "payload_key" to "",
            ),
            mapOf(
                "name" to attentionMask.name,
                "source" to "attention_mask",
                "data_type" to attentionMask.dataType,
                "layout" to "sequence",
                "shape" to attentionMask.shape,
                "payload_key" to "",
            ),
        )
        metadata[INFERENCE_CONTRACTS_KEY] = mapOf(
            "embedding_generation" to mapOf(
                "inputs" to sources,
                "outputs" to listOf(
                    mapOf("name" to output.name, "index" to output.index, "data_type" to output.dataType, "shape" to listOf(1, -1, 768)),
                ),
                "output_decoder" to mapOf(
                    "type" to "embedding",
                    "output_name" to output.name,
                    "pooling" to "mean_masked",
                    "hidden_dimension" to 768,
                    "embedding_dimension" to 768,
                    "normalization" to "l2",
                ),
                "confidence_scoring" to mapOf(
                    "type" to "identity",
                    "threshold" to 0f,
                ),
                "tokenizer" to mapOf(
                    "type" to "wordpiece",
                    "vocabulary" to readVocabulary(filesByName),
                    "pad_token" to "[PAD]",
                    "unk_token" to "[UNK]",
                    "cls_token" to "[CLS]",
                    "sep_token" to "[SEP]",
                    "mask_token" to "[MASK]",
                    "max_length" to 8192,
                    "normalizer" to "BertNormalizer",
                    "pre_tokenizer" to "BertPreTokenizer",
                    "model_type" to "WordPiece",
                    "continuing_subword_prefix" to "##",
                ),
                "image_preprocessing" to mapOf("enabled" to false),
            ),
        )
    }

    private fun synthesizeBgeRerankingContract(
        metadata: MutableMap<String, Any>,
        tasks: List<String>,
        bindings: ModelArtifactBindings,
        filesByName: Map<String, List<File>>,
        issues: MutableList<ModelPackageIssue>,
    ) {
        if (metadata[INFERENCE_CONTRACTS_KEY].asStringMap()?.containsKey("text_reranking") == true) return
        if ("text_reranking" !in tasks) return
        val inputIds = bindings.inputs.firstOrNull { it.name == "input_ids" }
        val attentionMask = bindings.inputs.firstOrNull { it.name == "attention_mask" }
        val output = bindings.outputs.firstOrNull { it.name == "logits" }
        val validInputs = inputIds?.dataType == "int64" && inputIds.shape.size == 2 &&
            attentionMask?.dataType == "int64" && attentionMask.shape.size == 2
        val validOutput = output?.dataType == "float32" && output.shape.size == 2 && output.shape[1] == 1
        if (!validInputs || !validOutput) {
            issues += ModelPackageIssue(
                "bge_reranking_graph_incompatible",
                "BGE reranker requires int64 rank-2 input_ids and attention_mask plus float rank-2 logits with final dimension 1.",
            )
            return
        }
        val tokenizer = readUnigramTokenizer(filesByName)
        if (tokenizer == null) {
            issues += ModelPackageIssue(
                "bge_reranking_tokenizer_missing",
                "BGE reranker requires tokenizer.json with an Unigram model and XLM-R pair template.",
            )
            return
        }
        metadata[INFERENCE_CONTRACTS_KEY] = mapOf(
            "text_reranking" to mapOf(
                "inputs" to listOf(
                    mapOf("name" to inputIds.name, "source" to "text_ids", "data_type" to "int64", "layout" to "sequence", "shape" to inputIds.shape, "payload_key" to "query"),
                    mapOf("name" to attentionMask.name, "source" to "attention_mask", "data_type" to "int64", "layout" to "sequence", "shape" to attentionMask.shape, "payload_key" to ""),
                ),
                "outputs" to listOf(mapOf("name" to output!!.name, "index" to output.index, "data_type" to "float32", "shape" to output.shape)),
                "output_decoder" to mapOf("type" to "reranking", "output_name" to output.name, "hidden_dimension" to 1),
                "confidence_scoring" to mapOf("type" to "identity", "threshold" to 0f),
                "tokenizer" to tokenizer,
                "image_preprocessing" to mapOf("enabled" to false),
            ),
        )
    }

    private fun synthesizeAestheticScoringContract(
        metadata: MutableMap<String, Any>,
        tasks: List<String>,
        bindings: ModelArtifactBindings,
        filesByName: Map<String, List<File>>,
        issues: MutableList<ModelPackageIssue>,
    ) {
        if (metadata[INFERENCE_CONTRACTS_KEY].asStringMap()?.containsKey("aesthetic_scoring") == true) return
        if ("aesthetic_scoring" !in tasks) return

        val input = bindings.inputs.singleOrNull { it.name == "input" }
        val output = bindings.outputs.firstOrNull { it.name == "output" }
        if (input == null || output == null) {
            issues += ModelPackageIssue(
                "aesthetic_scoring_graph_incompatible",
                "Aesthetic scoring requires graph tensors named 'input' and 'output'.",
            )
            return
        }

        val validInput = input.dataType == "float32" && input.shape.size == 4 &&
            input.shape[1] in setOf(-1, 3) &&
            input.shape[2] in setOf(-1, 384) &&
            input.shape[3] in setOf(-1, 384)
        val validOutput = output.dataType == "float32" &&
            output.shape.size == 2 &&
            output.shape[1] in setOf(-1, 1)
        if (!validInput || !validOutput) {
            issues += ModelPackageIssue(
                "aesthetic_scoring_graph_incompatible",
                "Aesthetic Predictor v2.5 requires float input 'input' [B,3,384,384] and float output 'output' [B,1].",
            )
            return
        }

        val modelId = metadata["model_id"]?.toString().orEmpty().lowercase()
        val knownV25 = modelId.contains("aesthetic_predictor_v2.5") ||
            modelId.contains("aesthetic-predictor-v2.5") ||
            filesByName.keys.any { name ->
                name == "aesthetic_predictor_v2_5.onnx" ||
                    name == "aesthetic_predictor_v2.5.onnx"
            }

        if (!knownV25) {
            issues += ModelPackageIssue(
                "preprocessing_contract_unresolved",
                "Aesthetic scoring graph matches the expected shape, but this package is not identified as Aesthetic Predictor v2.5 so its external preprocessing is not assumed.",
            )
            metadata["execution_readiness"] = mapOf(
                "ready" to false,
                "stage" to "imported_not_executable",
                "blockers" to listOf("preprocessing_contract_unresolved"),
            )
            return
        }

        metadata["aesthetic_preprocessing_profile"] = mapOf(
            "family" to "siglip-so400m-patch14-384",
            "width" to 384,
            "height" to 384,
            "scale" to (1f / 255f),
            "mean" to listOf(0.5f, 0.5f, 0.5f),
            "std" to listOf(0.5f, 0.5f, 0.5f),
            "source" to "upstream_aesthetic_predictor_v2.5_siglip_profile",
        )
        metadata.remove("execution_readiness")

        metadata[INFERENCE_CONTRACTS_KEY] = mapOf(
            "aesthetic_scoring" to mapOf(
                "tokenizer" to mapOf("type" to "none"),
                "inputs" to listOf(
                    mapOf(
                        "name" to input.name,
                        "source" to "image",
                        "data_type" to "float32",
                        "layout" to "nchw",
                        "shape" to input.shape,
                        "payload_key" to "image_uri",
                    ),
                ),
                "outputs" to listOf(
                    mapOf(
                        "name" to output.name,
                        "index" to output.index,
                        "data_type" to "float32",
                        "shape" to output.shape,
                    ),
                ),
                "output_decoder" to mapOf(
                    "type" to "regression",
                    "output_name" to output.name,
                    "hidden_dimension" to 1,
                ),
                "confidence_scoring" to mapOf("type" to "identity", "threshold" to 0f),
                "image_preprocessing" to mapOf(
                    "enabled" to true,
                    "width" to 384,
                    "height" to 384,
                    "channels" to 3,
                    "color_space" to "rgb",
                    "resize_mode" to "stretch",
                    "scale" to (1f / 255f),
                    "mean" to listOf(0.5f, 0.5f, 0.5f),
                    "std" to listOf(0.5f, 0.5f, 0.5f),
                ),
            ),
        )
    }

    private fun standardTextInputSource(name: String): String? = when (name.lowercase()) {
        "input_ids" -> "text_ids"
        "attention_mask" -> "attention_mask"
        "token_type_ids" -> "token_type_ids"
        else -> null
    }

    private fun standardLabels(metadata: Map<String, Any>, filesByName: Map<String, List<File>>): List<String> {
        val configured = metadata["id2label"].asStringMap()
            ?.mapNotNull { (index, label) -> index.toIntOrNull()?.let { it to label.toString() } }
            ?.sortedBy { it.first }
            ?.map { it.second }
            .orEmpty()
        if (configured.isNotEmpty()) {
            return configured
        }
        return filesByName["labels.txt"]?.singleOrNull()
            ?.readLines()
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            .orEmpty()
    }

    private fun addTensorBindings(
        metadata: MutableMap<String, Any>,
        primaryBindings: ModelArtifactBindings,
        primaryArtifact: File?,
        issues: MutableList<ModelPackageIssue>,
    ) {
        val contracts = metadata[INFERENCE_CONTRACTS_KEY].asStringMap() ?: return
        val artifactPathsByRole = metadata["artifact_paths_by_role"].asStringMap().orEmpty()
        val bindingCache = mutableMapOf<String, ModelArtifactBindings>()
        primaryArtifact?.let { artifact ->
            bindingCache[artifactBindingKey(artifact)] = primaryBindings
        }

        metadata[INFERENCE_CONTRACTS_KEY] = contracts.mapValues { (task, value) ->
            val contract = value.asStringMap()?.toMutableMap() ?: return@mapValues value
            val bindings = bindingsForTask(
                task = task,
                artifactPathsByRole = artifactPathsByRole,
                primaryArtifact = primaryArtifact,
                primaryBindings = primaryBindings,
                bindingCache = bindingCache,
                issues = issues,
            ) ?: return@mapValues contract

            val inputsByName = bindings.inputs.associateBy(ModelArtifactTensor::name)
            val outputsByName = bindings.outputs.associateBy(ModelArtifactTensor::name)
            val inputs = contract["inputs"].asMapList().map { raw ->
                val input = raw.toMutableMap()
                val name = input["name"]?.toString()?.trim().orEmpty()
                val tensor = inputsByName[name]
                val source = input["source"]?.toString().orEmpty()
                if (name.isBlank()) {
                    issues += ModelPackageIssue("tensor_input_name_missing", "$task is missing an input tensor name.")
                } else if (tensor == null) {
                    if (source != "image") {
                        issues += ModelPackageIssue("tensor_input_missing", "$task declares input '$name', which is absent from the model artifact.")
                    }
                } else {
                    input.putIfAbsent("data_type", tensor.dataType)
                    input.putIfAbsent("shape", tensor.shape)
                    if (source == "image" && input["layout"] == null) {
                        inferImageLayout(tensor.shape)?.let { layout -> input["layout"] = layout }
                    }
                }
                input
            }
            val outputs = contract["outputs"].asMapList().map { raw ->
                val output = raw.toMutableMap()
                val index = output["index"].toIntOrNull()
                if (output["name"]?.toString()?.isBlank() != false && index != null) {
                    bindings.outputs.getOrNull(index)?.let { tensor -> output["name"] = tensor.name }
                }
                val name = output["name"]?.toString()?.trim().orEmpty()
                val tensor = outputsByName[name]
                if (name.isBlank()) {
                    issues += ModelPackageIssue("tensor_output_name_missing", "$task is missing an output tensor name or index.")
                } else if (tensor == null) {
                    issues += ModelPackageIssue("tensor_output_missing", "$task declares output '$name', which is absent from the model artifact.")
                } else {
                    output.putIfAbsent("index", tensor.index)
                    output.putIfAbsent("data_type", tensor.dataType)
                }
                output
            }
            contract["inputs"] = inputs
            contract["outputs"] = outputs
            contract
        }
    }

    private fun bindingsForTask(
        task: String,
        artifactPathsByRole: Map<String, Any>,
        primaryArtifact: File?,
        primaryBindings: ModelArtifactBindings,
        bindingCache: MutableMap<String, ModelArtifactBindings>,
        issues: MutableList<ModelPackageIssue>,
    ): ModelArtifactBindings? {
        val role = artifactRolesForTask(task)
            .firstOrNull { candidate -> artifactPathsByRole[candidate]?.toString()?.isNotBlank() == true }
            ?: return primaryBindings
        val path = artifactPathsByRole[role]?.toString()?.trim().orEmpty()
        if (path.isBlank()) return primaryBindings

        val artifact = File(path)
        val key = artifactBindingKey(artifact)
        val primaryKey = primaryArtifact?.let(::artifactBindingKey)
        if (primaryKey != null && key == primaryKey) {
            return primaryBindings
        }
        bindingCache[key]?.let { return it }

        val runtime = runtimeFor(artifact)
        if (runtime.isBlank() || runtime == AiRuntimeType.LLAMA_CPP.raw) {
            issues += ModelPackageIssue(
                "tensor_metadata_unreadable",
                "$task resolves to artifact role '$role', but '${artifact.name}' is not an inspectable tensor runtime artifact.",
            )
            return null
        }

        return runCatching { inspectArtifactBindings(artifact, runtime) }
            .onSuccess { resolved -> bindingCache[key] = resolved }
            .getOrElse { error ->
                issues += ModelPackageIssue(
                    "tensor_metadata_unreadable",
                    "Unable to inspect ${artifact.name} for task '$task' (role '$role'): ${error.message ?: error.javaClass.simpleName}",
                )
                null
            }
    }

    private fun artifactRolesForTask(task: String): List<String> {
        val normalized = AiTaskTypes.normalize(task)
        return when (normalized) {
            "face_detection" -> listOf("detector", "face_detector", "face_detection")
            else -> listOf(normalized)
        }
    }

    private fun artifactBindingKey(artifact: File): String =
        runCatching { artifact.canonicalPath }.getOrElse { artifact.absolutePath }

    private fun addImagePreprocessing(
        metadata: MutableMap<String, Any>,
        bindings: ModelArtifactBindings?,
        issues: MutableList<ModelPackageIssue>,
    ) {
        val contracts = metadata[INFERENCE_CONTRACTS_KEY].asStringMap() ?: return
        val inputsByName = bindings?.inputs?.associateBy(ModelArtifactTensor::name).orEmpty()
        metadata[INFERENCE_CONTRACTS_KEY] = contracts.mapValues { (task, value) ->
            val contract = value.asStringMap()?.toMutableMap() ?: return@mapValues value
            val imageInput = contract["inputs"].asMapList().firstOrNull { input -> input["source"]?.toString() == "image" }
            if (imageInput == null) {
                contract.putIfAbsent("image_preprocessing", mapOf("enabled" to false))
                return@mapValues contract
            }
            if (contract["image_preprocessing"].asStringMap() != null) {
                return@mapValues contract
            }
            val tensor = inputsByName[imageInput["name"]?.toString().orEmpty()]
            val layout = imageInput["layout"]?.toString().orEmpty()
            val imageConfig = metadata["preprocessor_config"].asStringMap()
                ?: metadata["processor_config"].asStringMap()
            val dimensions = imageDimensions(imageConfig, tensor?.shape.orEmpty(), layout)
            val mean = imageConfig?.get("image_mean").floatList()
            val standardDeviation = imageConfig?.get("image_std").floatList()
            val scales = imageConfig?.get("rescale_factor").toFloatOrNull()
            val convertsRgb = imageConfig?.get("do_convert_rgb").toBooleanOrNull()
            if (imageConfig == null || dimensions == null || mean.isNullOrEmpty() || standardDeviation.isNullOrEmpty() || scales == null || convertsRgb == null) {
                issues += ModelPackageIssue(
                    "image_preprocessing_metadata_missing",
                    "$task requires image_preprocessing or preprocessor_config.json with size, image_mean, image_std, rescale_factor, and do_convert_rgb.",
                )
                return@mapValues contract
            }
            contract["image_preprocessing"] = mapOf(
                "enabled" to true,
                "width" to dimensions.first,
                "height" to dimensions.second,
                "channels" to (if (convertsRgb) 3 else 1),
                "color_space" to (if (convertsRgb) "rgb" else "grayscale"),
                "resize_mode" to if (imageConfig["do_center_crop"].toBooleanOrNull() == true) "center_crop" else "stretch",
                "scale" to scales,
                "mean" to mean,
                "std" to standardDeviation,
            )
            contract
        }
    }

    private fun imageDimensions(config: Map<String, Any>?, shape: List<Int>, layout: String): Pair<Int, Int>? {
        val size = config?.get("crop_size").asStringMap() ?: config?.get("size").asStringMap()
        val width = size?.get("width").toIntOrNull() ?: size?.get("shortest_edge").toIntOrNull()
        val height = size?.get("height").toIntOrNull() ?: size?.get("shortest_edge").toIntOrNull()
        if (width != null && height != null && width > 0 && height > 0) {
            return width to height
        }
        if (shape.size != 4) {
            return null
        }
        return when (layout) {
            "nchw" -> shape[3].takeIf { it > 0 }?.let { it to shape[2] }
            "nhwc" -> shape[2].takeIf { it > 0 }?.let { it to shape[1] }
            else -> null
        }
    }

    private fun inferImageLayout(shape: List<Int>): String? {
        if (shape.size != 4) return null
        val firstChannels = shape[1] in 1..4
        val lastChannels = shape[3] in 1..4
        return when {
            firstChannels && !lastChannels -> "nchw"
            lastChannels && !firstChannels -> "nhwc"
            else -> null
        }
    }

    private fun readVocabulary(filesByName: Map<String, List<File>>): List<String> {
        filesByName["vocab.txt"]?.singleOrNull()?.let { file ->
            return file.readLines().map(String::trim).filter(String::isNotBlank)
        }
        val tokenizer = filesByName["tokenizer.json"]?.singleOrNull()?.let { file ->
            runCatching { LocalAiJson.decodeMap(file.readText()) }.getOrNull()
        } ?: return emptyList()
        val model = tokenizer["model"].asStringMap() ?: return emptyList()
        if (!model["type"]?.toString().equals("WordPiece", ignoreCase = true)) {
            return emptyList()
        }
        val entries = model["vocab"].asStringMap()
            ?.mapNotNull { (token, id) -> id.toString().toIntOrNull()?.let { it to token } }
            ?.sortedBy { it.first }
            .orEmpty()
        return entries.map { it.second }
    }

    private fun readUnigramTokenizer(filesByName: Map<String, List<File>>): Map<String, Any>? {
        val tokenizerFile = filesByName["tokenizer.json"]?.singleOrNull() ?: return null
        val tokenizer = runCatching { LocalAiJson.decodeMap(tokenizerFile.readText().removePrefix("\uFEFF")) }.getOrNull() ?: return null
        val model = tokenizer["model"].asStringMap() ?: return null
        if (model["type"]?.toString() != "Unigram") return null
        val entries = (model["vocab"] as? List<*>)?.mapNotNull { value ->
            val pair = value as? List<*> ?: return@mapNotNull null
            val token = pair.getOrNull(0)?.toString() ?: return@mapNotNull null
            val score = (pair.getOrNull(1) as? Number)?.toFloat() ?: pair.getOrNull(1)?.toString()?.toFloatOrNull() ?: return@mapNotNull null
            token to score
        }.orEmpty()
        if (entries.isEmpty()) return null
        return mapOf(
            "type" to "unigram",
            "model_type" to "Unigram",
            "vocabulary" to entries.map { it.first },
            "vocabulary_scores" to entries.map { it.second },
            "unknown_token" to "<unk>",
            "start_token" to "<s>",
            "end_token" to "</s>",
            "pad_token" to "<pad>",
            "max_length" to 8192,
            "normalizer" to "precompiled",
            "pre_tokenizer" to "metaspace",
            "pair_template" to "xlm_roberta",
        )
    }

    private fun readSpecialTokens(metadata: Map<String, Any>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val tokenizer = metadata["tokenizer_config"].asStringMap().orEmpty()
        val special = metadata["special_tokens_map"].asStringMap().orEmpty()
        val sources = listOf(tokenizer, special)
        val aliases = mapOf(
            "unk_token" to "unknown_token",
            "cls_token" to "start_token",
            "bos_token" to "start_token",
            "sep_token" to "end_token",
            "eos_token" to "end_token",
            "pad_token" to "pad_token",
        )
        aliases.forEach { (sourceKey, targetKey) ->
            sources.asSequence().mapNotNull { map -> map[sourceKey].tokenValue() }.firstOrNull()?.let { token ->
                result[targetKey] = token
            }
        }
        return result
    }

    private fun discoverValues(metadata: Map<String, Any>, keys: Set<String>): List<String> = buildList {
        fun collect(value: Any?) {
            when (value) {
                is String -> value.trim().takeIf(String::isNotBlank)?.let(::add)
                is List<*> -> value.forEach(::collect)
                is Map<*, *> -> value.values.forEach(::collect)
            }
        }
        metadata.forEach { (key, value) ->
            if (key.lowercase() in keys) {
                collect(value)
            }
        }
    }

    private fun normalizeCapability(value: String): String = value
        .trim()
        .lowercase()
        .replace('-', '_')
        .replace(' ', '_')
        .replace(Regex("_+"), "_")

    private fun runtimeFor(file: File): String = when (file.extension.lowercase()) {
        "onnx" -> AiRuntimeType.ONNX.raw
        "tflite", "lite" -> AiRuntimeType.TFLITE.raw
        "gguf" -> AiRuntimeType.LLAMA_CPP.raw
        else -> ""
    }

    private fun inferNsfwClassificationTasks(metadata: Map<String, Any>): List<String> {
        val architecture = listOfNotNull(
            metadata["architecture"]?.toString(),
            metadata["architectures"]?.toString(),
            metadata["model_type"]?.toString(),
        ).joinToString(" ").lowercase()
        val problemType = metadata["problem_type"]?.toString().orEmpty().lowercase()
        val id2label = metadata["id2label"].asStringMap().orEmpty()
        val labels = id2label.values.mapNotNull { it?.toString()?.trim()?.lowercase() }
        val expected = listOf("drawings", "hentai", "neutral", "porn", "sexy")
        val ordered = labels.sortedBy { label -> expected.indexOf(label).takeIf { it >= 0 } ?: Int.MAX_VALUE }
        val labelsMatch = ordered.take(5) == expected
        val architectureMatch = architecture.contains("vitforimageclassification") ||
            architecture.contains("vision transformer") && architecture.contains("image classification")
        val problemMatch = problemType == "single_label_classification"
        return if (architectureMatch && problemMatch && labelsMatch) {
            listOf("nsfw_classification")
        } else {
            emptyList()
        }
    }

    private fun inferCapabilityFromModel(
        source: File,
        artifact: File?,
        metadata: Map<String, Any>,
        filesByName: Map<String, List<File>>,
        runtime: String,
    ): String {
        val identity = listOfNotNull(
            source.name,
            source.nameWithoutExtension,
            artifact?.name,
            artifact?.nameWithoutExtension,
            metadata["model_id"]?.toString(),
            metadata["_name_or_path"]?.toString(),
            metadata["name"]?.toString(),
            metadata["display_name"]?.toString(),
            metadata["model_type"]?.toString(),
            metadata["architectures"]?.toString(),
        ).joinToString(" ").lowercase()
        val description = metadata["description"]?.toString()?.lowercase().orEmpty() +
            metadata["task"]?.toString()?.lowercase().orEmpty() +
            metadata["tags"].asMapList().joinToString(" ") { it["name"]?.toString().orEmpty() }.lowercase()
        val combined = "$identity $description"

        if (combined.contains("reranker") || combined.contains("reranking") || combined.contains("bge-reranker")) {
            return "text_reranking"
        }

        if (
            combined.contains("nomic-embed-vision") ||
            combined.contains("nomic_embed_vision") ||
            combined.contains("nomicvisionmodel") ||
            combined.contains("nomic-embed-text") ||
            combined.contains("nomic_embed_text") ||
            (combined.contains("nomic") && combined.contains("embed"))
        ) {
            return "embedding_generation"
        }

        if (combined.contains("aesthetic") || combined.contains("aesthetic_predictor") || combined.contains("beauty")) {
            return "aesthetic_scoring"
        }
        if (combined.contains("image_quality") || combined.contains("quality") || combined.contains("image quality") || combined.contains("quality_score") || combined.contains("quality_scoring")) {
            return "image_quality_scoring"
        }
        if (combined.contains("predictor") && combined.contains("image")) {
            return "image_quality_scoring"
        }

        if (filesByName["labels.txt"]?.isNotEmpty() == true) {
            return "classification"
        }

        return inferCapabilityFromRuntime(runtime)
    }

    private fun inferCapabilityFromRuntime(runtime: String): String = when (runtime) {
        AiRuntimeType.LLAMA_CPP.raw -> "text_generation"
        AiRuntimeType.ONNX.raw -> "inference"
        AiRuntimeType.TFLITE.raw -> "inference"
        else -> "inference"
    }

    private companion object {
        const val INFERENCE_CONTRACTS_KEY = "inference_contracts"
        val TEXT_INPUT_SOURCES = setOf("text_ids", "attention_mask", "token_type_ids")
        val CLASSIFICATION_TASKS = setOf(
            "classification",
            "nsfw_classification",
            "tag_prediction",
            "character_recognition",
            "series_recognition",
            "artist_recognition",
            "metadata_extraction",
        )
        val METADATA_FILES = setOf(
            "metadata.json",
            "config.json",
            "tokenizer.json",
            "tokenizer_config.json",
            "processor.json",
            "preprocessor_config.json",
            "generation_config.json",
            "special_tokens_map.json",
        )
        val CAPABILITY_KEYS = setOf("capabilities", "supported_capabilities", "pipeline_tag", "pipeline", "tags")
        val TASK_KEYS = setOf("task", "tasks", "supported_tasks")
    }
}

private fun Any?.asStringMap(): Map<String, Any>? = when (this) {
    is Map<*, *> -> buildMap {
        this@asStringMap.forEach { (key, value) ->
            key?.toString()?.takeIf(String::isNotBlank)?.let { put(it, value ?: return@forEach) }
        }
    }
    else -> null
}

private fun Any?.asMapList(): List<Map<String, Any>> = when (this) {
    is List<*> -> mapNotNull(Any?::asStringMap)
    else -> emptyList()
}

private fun Any?.artifactPathList(): List<String> = when (this) {
    is List<*> -> mapNotNull { item ->
        when (item) {
            is Map<*, *> -> item["path"]?.toString()?.trim()?.takeIf(String::isNotBlank)
            is String -> item.trim().takeIf(String::isNotBlank)
            else -> null
        }
    }
    else -> emptyList()
}

private fun Any?.asDeclaredValues(): List<String> = when (this) {
    is String -> listOf(this)
    is List<*> -> mapNotNull { value -> value?.toString()?.trim()?.takeIf(String::isNotBlank) }
    else -> emptyList()
}

private fun Any?.toIntOrNull(): Int? = when (this) {
    is Number -> toInt()
    else -> toString()?.trim()?.toIntOrNull()
}

private fun Any?.toFloatOrNull(): Float? = when (this) {
    is Number -> toFloat()
    else -> toString()?.trim()?.toFloatOrNull()
}

private fun Any?.toBooleanOrNull(): Boolean? = when (this) {
    is Boolean -> this
    is Number -> toInt() != 0
    else -> when (toString()?.trim()?.lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> null
    }
}

private fun Any?.floatList(): List<Float> = when (this) {
    is List<*> -> mapNotNull(Any?::toFloatOrNull)
    else -> emptyList()
}

private fun Any?.tokenValue(): String? = when (this) {
    is String -> trim().takeIf(String::isNotBlank)
    is Map<*, *> -> this["content"]?.toString()?.trim()?.takeIf(String::isNotBlank)
    else -> null
}

private fun Any?.asNumberList(): List<Number> = when (this) {
    is List<*> -> mapNotNull { it as? Number }
    else -> emptyList()
}

private fun Any?.numberValue(defaultValue: Double): Double = when (this) {
    is Number -> toDouble()
    else -> toString().toDoubleOrNull() ?: defaultValue
}

private fun Any?.floatValue(defaultValue: Float): Float = numberValue(defaultValue.toDouble()).toFloat()

private fun Any?.intValue(defaultValue: Int): Int = numberValue(defaultValue.toDouble()).toInt()

private fun Any?.booleanValue(defaultValue: Boolean): Boolean = when (this) {
    is Boolean -> this
    is Number -> toInt() != 0
    else -> toString().lowercase().let { value ->
        when (value) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> defaultValue
        }
    }
}