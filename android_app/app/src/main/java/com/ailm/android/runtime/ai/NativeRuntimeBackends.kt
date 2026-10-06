package com.ailm.android.runtime.ai

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.tensorflow.lite.Interpreter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

class OnnxRuntimeBackend(
    private val modelResolver: (String, String) -> AiModelDescriptor?,
    private val context: Context,
) : BaseAiRuntimeProvider() {
    override val runtimeId = AiRuntimeType.ONNX.raw
    override val runtimeType = AiRuntimeType.ONNX
    override val supportedTasks = AiTaskTypes.EXECUTION_TASKS

    init {
        initializeProvider()
    }

    override fun initializeProvider(): AiRuntimeProviderState {
        providerState = AiRuntimeProviderState.INITIALIZING
        return runCatching {
            require(FileSupport.currentApiCompatible()) { "Android API ${android.os.Build.VERSION.SDK_INT} is unsupported" }
            require(FileSupport.currentAbiCompatible()) { "No supported device ABI" }
            OrtEnvironment.getEnvironment()
            providerMessage = "ONNX Runtime initialized"
            providerState = AiRuntimeProviderState.AVAILABLE
            providerState
        }.getOrElse { error ->
            providerMessage = error.message ?: error.javaClass.simpleName
            providerState = AiRuntimeProviderState.UNAVAILABLE
            providerState
        }
    }

    override suspend fun loadModel(model: AiModelDescriptor): AiRuntimeModelHandle? =
        if (supportsModel(model)) AiRuntimeModelHandle(model.modelId, model.version, runtimeId) else null

    override suspend fun unloadModel(handle: AiRuntimeModelHandle): Boolean = handle.providerId == runtimeId
    override suspend fun cancel(sessionId: String): Boolean = false
    override suspend fun release(): Boolean = true
    override fun queryMemory(): Map<String, Any> = mapOf("session_cache_bytes" to 0L, "provider_state" to providerState.name.lowercase())
    override suspend fun benchmark(model: AiModelDescriptor): Map<String, Any> = mapOf("status" to "ready", "provider" to runtimeId, "model_id" to model.modelId)
    override suspend fun health(): AiRuntimeProviderHealth = AiRuntimeProviderHealth(providerState, providerMessage, 0L)
    override fun queryCapabilities(): AiRuntimeProviderCapabilities = AiRuntimeProviderCapabilities(
        providerId = runtimeId,
        backendName = "ONNX Runtime Android",
        runtimeType = runtimeType,
        supportedFormats = setOf("onnx"),
        supportedTasks = supportedTasks,
        supportedPrecisions = setOf("fp32", "fp16", "int8", "uint8"),
        supportedDevices = setOf("cpu", "gpu", "npu"),
        supportedDelegates = setOf("nnapi", "xnnpack", "cpu"),
        supportedQuantizations = setOf("int8", "uint8", "dynamic", "none"),
        supportedTensorLayouts = setOf("nchw", "nhwc", "sequence"),
        supportedInputTypes = setOf("float", "int64", "int32", "uint8", "text", "image"),
        supportedOutputTypes = setOf("float", "int64", "int32", "text", "image"),
        maximumContext = 0,
        maximumImageResolution = 0,
        runtimeVersion = OrtEnvironment::class.java.`package`?.implementationVersion ?: "android",
        abiCompatible = FileSupport.currentAbiCompatible(),
        androidApiCompatible = FileSupport.currentApiCompatible(),
        metadata = mapOf("transport" to "onnxruntime-android"),
    )

    override suspend fun execute(request: AiExecutionRequest, reporter: AiProgressReporter): AiExecutionResult {
        val registeredModel = modelResolver(request.modelId, request.version) ?: return unavailable("Model is not installed")
        if (AiTaskTypes.normalize(request.taskType) == "ocr" && registeredModel.metadata["paddle_ocr"] != null) {
            return PaddleOcrRuntime(context).execute(
                model = registeredModel,
                request = request,
                reporter = reporter,
                runtimeId = runtimeId,
            )
        }
        if (isFlorenceStage3Request(registeredModel, request)) {
            return try {
                reporter.report(0.15, "Preparing Florence Stage 4 decoder-with-past generation inputs")
                val stage2 = FlorenceStage2Coordinator(context, RealFlorenceOnnxExecutor).execute(registeredModel, request)
                val stage3 = FlorenceStage3Coordinator(RealFlorenceOnnxExecutor).execute(registeredModel, request, stage2)
                val stage4 = FlorenceStage4Coordinator(RealFlorenceOnnxExecutor).execute(registeredModel, request, stage3)
                reporter.report(1.0, "Florence Stage 4 decoder-with-past generation complete")
                AiExecutionResult(
                    ok = true,
                    status = "succeeded",
                    message = "Florence generation completed",
                    details = mapOf(
                        "runtime" to runtimeId,
                        "task_type" to AiTaskTypes.normalize(request.taskType),
                        "florence_stage" to 4,
                        "execution_order" to stage4.executionOrder,
                        "result" to stage4.generation.toMap(),
                    ),
                )
            } catch (error: Throwable) {
                failure(error, runtimeId)
            }
        }
        if (isFlorenceStage2Request(registeredModel, request)) {
            return try {
                reporter.report(0.15, "Preparing Florence Stage 2 encoder inputs")
                val stage2 = FlorenceStage2Coordinator(context, RealFlorenceOnnxExecutor).execute(registeredModel, request)
                reporter.report(1.0, "Florence Stage 2 encoder complete")
                AiExecutionResult(
                    ok = true,
                    status = "succeeded",
                    message = "Florence encoder completed",
                    details = mapOf(
                        "runtime" to runtimeId,
                        "task_type" to AiTaskTypes.normalize(request.taskType),
                        "florence_stage" to 2,
                        "execution_order" to stage2.executionOrder,
                        "result" to stage2.encoder.toMap(),
                    ),
                )
            } catch (error: Throwable) {
                failure(error, runtimeId)
            }
        }
        val model = resolveRoleArtifact(registeredModel, request.taskType)
        val contract = runCatching { ModelInferenceContract.resolve(model, request.taskType) }
            .getOrElse { return incompatible(it.message ?: "Model inference contract is invalid") }
        val handle = loadModel(model) ?: return incompatible("ONNX Runtime cannot execute this model on the current device")
        reporter.report(0.15, "Preparing ONNX Runtime inputs")
        return try {
            val normalizedTask = AiTaskTypes.normalize(request.taskType)
            val effectivePayload = if (normalizedTask == "face_embedding" &&
                request.payload["face_keypoints"] == null && request.payload["kps"] == null
            ) {
                detectSingleFacePayload(registeredModel, request)
            } else if (normalizedTask in setOf("landmark_2d", "landmark_3d", "gender_age") && request.payload["face_bbox"] == null && request.payload["bbox"] == null) {
                detectSingleFacePayload(registeredModel, request)
            } else {
                request.payload
            }
            val inputs = ModelInputPreprocessor(context).prepare(contract, effectivePayload)
            val output = OnnxRuntimeClient.run(model, inputs)
            reporter.report(0.90, "Decoding ONNX Runtime output")
            reporter.report(1.0, "ONNX Runtime inference complete")
            RuntimeOutputMapper.toResult(request, model, contract, output, runtimeId)
        } catch (error: Throwable) {
            failure(error, runtimeId)
        } finally {
            unloadModel(handle)
        }
    }

    private fun detectSingleFacePayload(model: AiModelDescriptor, request: AiExecutionRequest): Map<String, Any> {
        val detectorModel = resolveRoleArtifact(model, "face_detection")
        val detectorContract = ModelInferenceContract.resolve(detectorModel, "face_detection")
        val detectorInputs = ModelInputPreprocessor(context).prepare(detectorContract, request.payload)
        val detectorOutputs = OnnxRuntimeClient.run(detectorModel, detectorInputs)
        val detectorRequest = request.copy(taskType = "face_detection")
        val detectorResult = RuntimeOutputMapper.toResult(
            detectorRequest,
            detectorModel,
            detectorContract,
            detectorOutputs,
            runtimeId,
        )
        val faces = (detectorResult.details["result"] as? Map<*, *>)?.get("faces") as? List<*> ?: emptyList<Any>()
        require(faces.size == 1) {
            if (faces.isEmpty()) "face_embedding requires exactly one detected face, but no face was detected"
            else "face_embedding requires explicit face selection when multiple faces are detected"
        }
        val face = faces.single() as? Map<*, *> ?: error("Detector returned an invalid face result")
        val keypoints = face["kps"] as? List<*> ?: error("Detector result is missing five face keypoints")
        return request.payload + mapOf(
            "face_keypoints" to keypoints,
            "face_bbox" to (face["bbox"] ?: error("Detector result is missing face bbox")),
        )
    }

    private fun resolveRoleArtifact(model: AiModelDescriptor, taskType: String): AiModelDescriptor {
        val role = when (AiTaskTypes.normalize(taskType)) {
            "face_embedding" -> "face_embedding"
            "face_detection" -> "detector"
            "landmark_2d" -> "landmark_2d"
            "landmark_3d" -> "landmark_3d"
            "gender_age" -> "gender_age"
            "vision_encoder" -> "vision_encoder"
            else -> return model
        }
        val path = (model.metadata["artifact_paths_by_role"] as? Map<*, *>)
            ?.get(role)
            ?.toString()
            ?.takeIf(String::isNotBlank)
            ?: throw ModelInferenceContractException("Model package has no artifact for role '$role'")
        return model.copy(installPath = path)
    }

    private fun isFlorenceStage3Request(model: AiModelDescriptor, request: AiExecutionRequest): Boolean =
        AiTaskTypes.normalize(request.taskType) == "prompt_generation" &&
            model.metadata["florence_package"] != null &&
            (request.payload["text"] != null || request.payload["prompt"] != null)

    private fun isFlorenceStage2Request(model: AiModelDescriptor, request: AiExecutionRequest): Boolean =
        AiTaskTypes.normalize(request.taskType) == "vision_encoder" &&
            model.metadata["florence_package"] != null &&
            (request.payload["text"] != null || request.payload["prompt"] != null)
}

class TensorFlowLiteBackend(
    private val modelResolver: (String, String) -> AiModelDescriptor?,
    private val context: Context,
) : BaseAiRuntimeProvider() {
    override val runtimeId = AiRuntimeType.TFLITE.raw
    override val runtimeType = AiRuntimeType.TFLITE
    override val supportedTasks = AiTaskTypes.EXECUTION_TASKS

    init {
        initializeProvider()
    }

    override fun initializeProvider(): AiRuntimeProviderState {
        providerState = AiRuntimeProviderState.INITIALIZING
        return runCatching {
            require(FileSupport.currentApiCompatible()) { "Android API ${android.os.Build.VERSION.SDK_INT} is unsupported" }
            require(FileSupport.currentAbiCompatible()) { "No supported device ABI" }
            Interpreter.Options()
            providerMessage = "TensorFlow Lite initialized"
            providerState = AiRuntimeProviderState.AVAILABLE
            providerState
        }.getOrElse { error ->
            providerMessage = error.message ?: error.javaClass.simpleName
            providerState = AiRuntimeProviderState.UNAVAILABLE
            providerState
        }
    }

    override suspend fun loadModel(model: AiModelDescriptor): AiRuntimeModelHandle? =
        if (supportsModel(model)) AiRuntimeModelHandle(model.modelId, model.version, runtimeId) else null

    override suspend fun unloadModel(handle: AiRuntimeModelHandle): Boolean = handle.providerId == runtimeId
    override suspend fun cancel(sessionId: String): Boolean = false
    override suspend fun release(): Boolean = true
    override fun queryMemory(): Map<String, Any> = mapOf("session_cache_bytes" to 0L, "provider_state" to providerState.name.lowercase())
    override suspend fun benchmark(model: AiModelDescriptor): Map<String, Any> = mapOf("status" to "ready", "provider" to runtimeId, "model_id" to model.modelId)
    override suspend fun health(): AiRuntimeProviderHealth = AiRuntimeProviderHealth(providerState, providerMessage, 0L)
    override fun queryCapabilities(): AiRuntimeProviderCapabilities = AiRuntimeProviderCapabilities(
        providerId = runtimeId,
        backendName = "TensorFlow Lite",
        runtimeType = runtimeType,
        supportedFormats = setOf("tflite", "lite"),
        supportedTasks = supportedTasks,
        supportedPrecisions = setOf("fp32", "fp16", "int8", "uint8"),
        supportedDevices = setOf("cpu", "gpu", "npu"),
        supportedDelegates = setOf("nnapi", "gpu", "xnnpack", "cpu"),
        supportedQuantizations = setOf("int8", "uint8", "dynamic", "none"),
        supportedTensorLayouts = setOf("nhwc", "nchw", "sequence"),
        supportedInputTypes = setOf("float", "int32", "uint8", "text", "image"),
        supportedOutputTypes = setOf("float", "int32", "uint8", "text", "image"),
        maximumContext = 0,
        maximumImageResolution = 0,
        runtimeVersion = Interpreter::class.java.`package`?.implementationVersion ?: "android",
        abiCompatible = FileSupport.currentAbiCompatible(),
        androidApiCompatible = FileSupport.currentApiCompatible(),
        metadata = mapOf("transport" to "tensorflow-lite"),
    )

    override suspend fun execute(request: AiExecutionRequest, reporter: AiProgressReporter): AiExecutionResult {
        val model = modelResolver(request.modelId, request.version) ?: return unavailable("Model is not installed")
        val contract = runCatching { ModelInferenceContract.resolve(model, request.taskType) }
            .getOrElse { return incompatible(it.message ?: "Model inference contract is invalid") }
        val handle = loadModel(model) ?: return incompatible("TensorFlow Lite cannot execute this model on the current device")
        reporter.report(0.15, "Preparing TensorFlow Lite inputs")
        return try {
            val inputs = ModelInputPreprocessor(context).prepare(contract, request.payload)
            val output = TensorFlowLiteClient.run(model, inputs, contract.outputs)
            reporter.report(0.90, "Decoding TensorFlow Lite output")
            reporter.report(1.0, "TensorFlow Lite inference complete")
            RuntimeOutputMapper.toResult(request, model, contract, output, runtimeId)
        } catch (error: Throwable) {
            failure(error, runtimeId)
        } finally {
            unloadModel(handle)
        }
    }
}

internal class LlamaCppBackend(
    private val modelResolver: (String, String) -> AiModelDescriptor?,
    private val context: Context,
    private val bridge: LlamaCppRuntimeBridge = RealLlamaCppRuntimeBridge,
) : BaseAiRuntimeProvider() {
    private val activeHandles = ConcurrentHashMap<String, Pair<LlamaCppRuntimeBridge, Long>>()
    override val runtimeId = AiRuntimeType.LLAMA_CPP.raw
    override val runtimeType = AiRuntimeType.LLAMA_CPP
    private val nativeQwenTasks = setOf(
        "text_generation",
        "prompt_generation",
        "captioning",
        "series_recognition",
        "character_recognition",
        "tag_prediction",
        "normalization",
    )
    override val supportedTasks = nativeQwenTasks

    init {
        initializeProvider()
    }

    override fun initializeProvider(): AiRuntimeProviderState {
        providerState = AiRuntimeProviderState.INITIALIZING
        providerMessage = runCatching { "llama.cpp ${bridge.probe()}" }
            .getOrElse { error -> "native_backend_unavailable: ${error.message ?: error.javaClass.simpleName}" }
        providerState = if (providerMessage.startsWith("llama.cpp ")) {
            AiRuntimeProviderState.AVAILABLE
        } else {
            AiRuntimeProviderState.UNAVAILABLE
        }
        return providerState
    }

    override fun supportsModel(model: AiModelDescriptor): Boolean {
        val metadata = model.metadata["llama_cpp"] as? Map<*, *> ?: return false
        return providerState == AiRuntimeProviderState.AVAILABLE &&
            FileSupport.modelFormat(model) == "gguf" &&
            (metadata["text_only"] == true || metadata["multimodal"] == true) &&
            (model.supportedTasks.isEmpty() || model.supportedTasks.any { task ->
                AiTaskTypes.normalize(task) in nativeQwenTasks
            })
    }

    override suspend fun loadModel(model: AiModelDescriptor): AiRuntimeModelHandle? {
        if (!supportsModel(model)) return null
        val llamaMetadata = model.metadata["llama_cpp"] as? Map<*, *> ?: return null
        val contextSize = ((model.metadata["llama_cpp_context"] as? Number)?.toInt() ?: 32768).coerceIn(256, 32768)
        val threads = ((model.metadata["llama_cpp_threads"] as? Number)?.toInt() ?: 2).coerceIn(1, 16)
        val multimodal = llamaMetadata["multimodal"] == true
        val rolePaths = model.metadata["artifact_paths_by_role"] as? Map<*, *>
        val projectorPath = rolePaths?.get("vision_projector")?.toString()?.takeIf(String::isNotBlank)
        if (multimodal && projectorPath == null) return null
        val nativeHandle = runCatching {
            if (multimodal) bridge.loadMultimodalModel(model.installPath, projectorPath!!, contextSize, threads)
            else bridge.loadModel(model.installPath, contextSize, threads)
        }.getOrElse { return null }
        if (nativeHandle == 0L) return null
        return AiRuntimeModelHandle(model.modelId, model.version, runtimeId, mapOf("native_handle" to nativeHandle, "context_size" to contextSize, "threads" to threads, "multimodal" to multimodal))
    }

    override suspend fun unloadModel(handle: AiRuntimeModelHandle): Boolean {
        if (handle.providerId != runtimeId) return false
        val nativeHandle = (handle.metadata["native_handle"] as? Number)?.toLong() ?: return false
        bridge.release(nativeHandle)
        return true
    }

    override suspend fun cancel(sessionId: String): Boolean = activeHandles.remove(sessionId)?.let { (activeBridge, nativeHandle) ->
        activeBridge.cancel(nativeHandle)
        true
    } ?: false
    override suspend fun release(): Boolean = true
    override fun queryMemory(): Map<String, Any> = mapOf("session_cache_bytes" to 0L, "provider_state" to providerState.name.lowercase())
    override suspend fun benchmark(model: AiModelDescriptor): Map<String, Any> = mapOf("status" to "ready", "provider" to runtimeId, "model_id" to model.modelId)
    override suspend fun health(): AiRuntimeProviderHealth = AiRuntimeProviderHealth(providerState, providerMessage, 0L)
    override fun queryCapabilities(): AiRuntimeProviderCapabilities = AiRuntimeProviderCapabilities(
        providerId = runtimeId,
        backendName = "LLAMA.CPP Runtime",
        runtimeType = runtimeType,
        supportedFormats = setOf("gguf"),
        supportedTasks = supportedTasks,
        supportedPrecisions = setOf("fp32", "fp16", "int4", "int5", "int8"),
        supportedDevices = setOf("cpu"),
        supportedDelegates = emptySet(),
        supportedQuantizations = setOf("int4", "int5", "int8", "none"),
        supportedTensorLayouts = setOf("sequence"),
        supportedInputTypes = setOf("text", "image"),
        supportedOutputTypes = setOf("float", "text"),
        maximumContext = 0,
        maximumImageResolution = 0,
        runtimeVersion = providerMessage.removePrefix("llama.cpp "),
        abiCompatible = FileSupport.currentAbiCompatible(),
        androidApiCompatible = FileSupport.currentApiCompatible(),
        metadata = mapOf("transport" to "llama-cpp-android"),
    )

    override suspend fun execute(request: AiExecutionRequest, reporter: AiProgressReporter): AiExecutionResult {
        val model = modelResolver(request.modelId, request.version) ?: return unavailable("Model is not installed")
        val normalizedTask = AiTaskTypes.normalize(request.taskType)
        if (normalizedTask !in nativeQwenTasks) {
            return incompatible("Qwen native backend does not support task $normalizedTask")
        }
        val handle = loadModel(model) ?: return incompatible("model_load_failed: native llama.cpp could not load the GGUF artifact")
        val nativeHandle = (handle.metadata["native_handle"] as Number).toLong()
        val prompt = semanticQwenPrompt(normalizedTask, request.payload)
        if (prompt.isBlank()) {
            return incompatible("tokenization_failed: request text is missing")
        }
        val maxNewTokens = ((request.payload["max_new_tokens"] as? Number)?.toInt()
            ?: if (normalizedTask == "tag_prediction") 192 else 128).coerceIn(1, 4096)
        return try {
            activeHandles[request.sessionId] = bridge to nativeHandle
            val multimodal = handle.metadata["multimodal"] == true
            val generated = if (multimodal) {
                val image = readSingleRgbImage(request.payload)
                    ?: return incompatible("image_decode_failed: multimodal request requires one image input")
                reporter.report(0.15, "Loading Qwen-VL image projector with llama.cpp")
                bridge.generateMultimodal(nativeHandle, prompt, image.rgb, image.width, image.height, maxNewTokens)
            } else {
                reporter.report(0.15, "Loading Qwen GGUF with llama.cpp")
                bridge.generate(nativeHandle, prompt, maxNewTokens)
            }
            reporter.report(1.0, if (multimodal) "Qwen-VL multimodal generation complete" else "Qwen text generation complete")
            AiExecutionResult(
                ok = true,
                status = "succeeded",
                message = if (multimodal) "Qwen-VL multimodal generation completed" else "Qwen coder text generation completed",
                details = mapOf(
                    "runtime" to runtimeId,
                    "model_id" to model.modelId,
                    "generated_text" to generated,
                    "multimodal_prefill" to multimodal,
                    "sampling_policy" to "greedy",
                    "native_backend" to "llama.cpp",
                    "llama_cpp_revision" to "5266f24da75dc449bd56cbed7addb9c8e4a6a73e",
                    "result" to semanticQwenResult(normalizedTask, generated),
                ),
            )
        } catch (error: Throwable) {
            failure(error, runtimeId)
        } finally {
            activeHandles.remove(request.sessionId)
            unloadModel(handle)
        }
    }

    private fun semanticQwenPrompt(taskType: String, payload: Map<String, Any>): String {
        val userPrompt = payload["prompt"]?.toString()?.trim().orEmpty()
        val caption = payload["caption"]?.toString()?.trim().orEmpty()
        val ocr = payload["ocr_text"]?.toString()?.trim().orEmpty()
        val context = buildString {
            if (caption.isNotBlank()) append("\nExisting caption: ").append(caption)
            if (ocr.isNotBlank()) append("\nDetected text: ").append(ocr)
        }
        return when (taskType) {
            "captioning" ->
                "Describe the image accurately in one concise sentence. Mention the main subject, appearance, clothing, pose, and setting when visible. Return only the caption."
            "series_recognition" ->
                "Series identity is not an autonomous source of truth. This diagnostic task may suggest a title, but automation derives series from resolved Character Knowledge. Return only one title or UNKNOWN." + context
            "character_recognition" -> {
                val taxonomy = payload["character_taxonomy_context"]?.toString()?.trim().orEmpty()
                """
                Analyze every visually distinct character/person in the image. Do NOT guess character names or series.
                Resolve only directly visible physical attributes to the supplied canonical taxonomy IDs.
                Return strict JSON only in this form:
                {"subjects":[{"subject_index":0,"prominence":0.0,"bbox":[0.0,0.0,1.0,1.0],"attributes":[{"id":"HC001","confidence":0.0}]}]}
                prominence is 0..1 and indicates visual prominence. bbox is normalized [x,y,width,height]
                for that subject and each value must be 0..1. confidence is 0..1.
                Several IDs from the same attribute family are valid when several values are genuinely visible.
                Colour markers are semantic and mandatory when colours coexist simultaneously:
                - multicoloured hair: emit HC043 plus every identifiable component HC colour ID;
                - heterochromia: emit ET001 plus every identifiable EC colour ID for the two eyes;
                - a multicoloured iris/eye treatment: emit EC027 plus every identifiable component EC colour ID.
                If simultaneous component colours cannot be resolved safely, emit the applicable marker alone.
                Never use two specific HC or EC IDs by themselves to mean simultaneous multicolour. In Character
                Knowledge, multiple specific HC/EC IDs without HC043, EC027, or ET001 are canonical alternatives
                across appearances rather than a simultaneous visual state.
                Omit any attribute that is hidden, ambiguous, perspective-dependent, or not safely distinguishable.
                Use only IDs listed below. Never invent an ID, shade, body measurement, character, or series.
                Taxonomy:
                """.trimIndent() + "\n" + taxonomy + context
            }
            "tag_prediction" -> {
                val taxonomy = payload["illustration_taxonomy_context"]?.toString()?.trim().orEmpty()
                """
                Analyze only what is visibly present in this specific illustration.
                Return a comma-separated list of canonical taxonomy IDs only, with no explanation.
                Use IDs from the supplied taxonomy for visible outfit/accessories, visible weapons, pose,
                gesture, expression, eye/mouth state, environment/weather, framing/camera/lighting,
                action and rating when safely inferable. Do not infer permanent character traits from
                identity, and never invent an ID or free-form synonym. Omit uncertain or absent concepts.
                Taxonomy:
                """.trimIndent() + "\n" + taxonomy + context
            }
            "normalization" ->
                "Normalize the supplied context into concise canonical wording. Preserve meaning and do not invent facts. Return only the normalized text." + context
            else -> payload["text"]?.toString()?.trim().orEmpty().ifBlank { userPrompt }
        }
    }

    private fun semanticQwenResult(taskType: String, generated: String): Map<String, Any> {
        val clean = generated.trim()
        return when (taskType) {
            "captioning" -> mapOf("caption" to clean)
            "character_recognition" -> parseCharacterObservationJson(clean)
            "series_recognition" -> {
                val top = clean.lineSequence().firstOrNull()?.trim()?.trim('"', '\'', '.', ',').orEmpty()
                    .ifBlank { "UNKNOWN" }
                val confidence = if (top.equals("UNKNOWN", ignoreCase = true)) 0.0 else 0.50
                mapOf(
                    "top_match" to top,
                    "confidence" to confidence,
                    "candidates" to listOf(mapOf("name" to top, "confidence" to confidence)),
                    "diagnostic_only" to true,
                )
            }
            "tag_prediction" -> {
                val tags = clean
                    .replace("\n", ",")
                    .split(',')
                    .map { it.trim().trim('-', '*', '•', '"') }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(40)
                mapOf("tags" to tags)
            }
            "normalization" -> mapOf("text" to clean)
            "prompt_generation" -> mapOf("prompt" to clean)
            else -> mapOf("text" to clean)
        }
    }

    private fun parseCharacterObservationJson(generated: String): Map<String, Any> {
        val candidate = generated
            .substringAfter('`', generated)
            .replace("json\n", "", ignoreCase = true)
            .trim()
            .let { text ->
                val start = text.indexOf('{')
                val end = text.lastIndexOf('}')
                if (start >= 0 && end > start) text.substring(start, end + 1) else text
            }
        return runCatching {
            val root = JSONObject(candidate)
            val subjectsArray = root.optJSONArray("subjects") ?: JSONArray()
            val subjects = buildList {
                for (index in 0 until subjectsArray.length()) {
                    val subject = subjectsArray.optJSONObject(index) ?: continue
                    val attributesArray = subject.optJSONArray("attributes") ?: JSONArray()
                    val attributes = buildList {
                        for (attrIndex in 0 until attributesArray.length()) {
                            val attribute = attributesArray.optJSONObject(attrIndex) ?: continue
                            val id = attribute.optString("id").trim()
                            if (id.isBlank()) continue
                            add(
                                mapOf(
                                    "id" to id,
                                    "confidence" to attribute.optDouble("confidence", 0.70).coerceIn(0.0, 1.0),
                                ),
                            )
                        }
                    }
                    val bboxArray = subject.optJSONArray("bbox") ?: JSONArray()
                    val bbox = (0 until minOf(4, bboxArray.length()))
                        .map { bboxArray.optDouble(it, Double.NaN) }
                        .takeIf { values -> values.size == 4 && values.all(Double::isFinite) }
                        ?.map { it.coerceIn(0.0, 1.0) }
                        .orEmpty()
                    add(
                        mapOf(
                            "subject_index" to subject.optInt("subject_index", index),
                            "prominence" to subject.optDouble("prominence", 1.0).coerceIn(0.0, 1.0),
                            "bbox" to bbox,
                            "attributes" to attributes,
                        ),
                    )
                }
            }
            mapOf(
                "subjects" to subjects,
                "raw_observation_json" to candidate,
            )
        }.getOrElse {
            mapOf(
                "subjects" to emptyList<Map<String, Any>>(),
                "raw_observation_json" to generated.trim(),
                "parse_error" to (it.message ?: it.javaClass.simpleName),
            )
        }
    }

    private data class RgbImage(val rgb: ByteArray, val width: Int, val height: Int)

    private fun readSingleRgbImage(payload: Map<String, Any>): RgbImage? {
        val imageCount = (payload["image_count"] as? Number)?.toInt() ?: 1
        require(imageCount == 1) { "unsupported_image_count: Qwen-VL Stage 2 supports exactly one image" }
        val rawUri = listOf("image_uri", "uri", "source_path", "image_path", "file_path", "path")
            .asSequence()
            .mapNotNull { payload[it]?.toString()?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull() ?: return null
        val bitmap = runCatching {
            val uri = Uri.parse(rawUri)
            val stream = if (uri.scheme.equals("content", ignoreCase = true)) {
                context.contentResolver.openInputStream(uri)
            } else {
                FileInputStream(if (uri.scheme.equals("file", ignoreCase = true)) File(uri.path.orEmpty()) else File(rawUri))
            }
            stream?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return null
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val rgb = ByteArray(pixels.size * 3)
        pixels.forEachIndexed { index, pixel ->
            rgb[index * 3] = ((pixel shr 16) and 0xff).toByte()
            rgb[index * 3 + 1] = ((pixel shr 8) and 0xff).toByte()
            rgb[index * 3 + 2] = (pixel and 0xff).toByte()
        }
        bitmap.recycle()
        return RgbImage(rgb, width, height)
    }
}

class OptionalNativeRuntimeProvider(
    override val runtimeType: AiRuntimeType,
    private val libraryName: String,
    private val modelResolver: (String, String) -> AiModelDescriptor?,
    private val providerId: String = runtimeType.raw,
    private val nativeLibraryPath: String = "",
    private val declaredCapabilities: AiRuntimeProviderCapabilities? = null,
) : BaseAiRuntimeProvider() {
    override val runtimeId: String = providerId
    override val supportedTasks = AiTaskTypes.EXECUTION_TASKS

    override fun supportsModel(model: AiModelDescriptor): Boolean {
        if (providerState != AiRuntimeProviderState.AVAILABLE || !FileSupport.isModelFile(model)) {
            return false
        }
        val capabilities = queryCapabilities()
        val declaredRuntimes = (model.supportedRuntimes + model.requiredRuntime)
            .map(String::lowercase)
            .filter(String::isNotBlank)
            .toSet()
        if (declaredRuntimes.isNotEmpty() && runtimeType.raw !in declaredRuntimes) {
            return false
        }
        val format = FileSupport.modelFormat(model)
        if (capabilities.supportedFormats.isNotEmpty() && format !in capabilities.supportedFormats) {
            return false
        }
        val supported = capabilities.supportedTasks.map(AiTaskTypes::normalize).toSet()
        return model.supportedTasks.isEmpty() || supported.isEmpty() || model.supportedTasks.any { task ->
            AiTaskTypes.normalize(task) in supported
        }
    }

    init {
        initializeProvider()
    }

    override fun initializeProvider(): AiRuntimeProviderState {
        providerState = AiRuntimeProviderState.INITIALIZING
        declaredCapabilities?.let { capabilities ->
            if (!capabilities.androidApiCompatible || !capabilities.abiCompatible) {
                providerMessage = "Provider package is unsupported on this device"
                providerState = AiRuntimeProviderState.UNSUPPORTED
                return providerState
            }
        }
        val plugin = NativeRuntimePluginRegistry.resolve(runtimeType) ?: runCatching {
            if (nativeLibraryPath.isNotBlank()) {
                System.load(nativeLibraryPath)
            } else {
                System.loadLibrary(libraryName)
            }
            NativeRuntimePluginRegistry.resolve(runtimeType)
        }.getOrNull()
        if (plugin == null) {
            providerMessage = "JNI plugin '$libraryName' is not packaged or registered"
            providerState = AiRuntimeProviderState.UNAVAILABLE
            return providerState
        }
        return runCatching {
            require(FileSupport.currentApiCompatible()) { "Android API ${android.os.Build.VERSION.SDK_INT} is unsupported" }
            require(FileSupport.currentAbiCompatible()) { "No supported device ABI" }
            val health = plugin.probe()
            providerMessage = health.message
            providerState = health.state
            providerState
        }.getOrElse { error ->
            providerMessage = error.message ?: error.javaClass.simpleName
            providerState = AiRuntimeProviderState.FAILED
            providerState
        }
    }

    override suspend fun loadModel(model: AiModelDescriptor): AiRuntimeModelHandle? {
        if (!supportsModel(model)) return null
        return NativeRuntimePluginRegistry.resolve(runtimeType)?.loadModel(model)
    }

    override suspend fun unloadModel(handle: AiRuntimeModelHandle): Boolean =
        NativeRuntimePluginRegistry.resolve(runtimeType)?.unloadModel(handle) ?: false

    override suspend fun cancel(sessionId: String): Boolean =
        NativeRuntimePluginRegistry.resolve(runtimeType)?.cancel(sessionId) ?: false

    override suspend fun release(): Boolean =
        NativeRuntimePluginRegistry.resolve(runtimeType)?.release() ?: true

    override fun queryCapabilities(): AiRuntimeProviderCapabilities {
        val plugin = NativeRuntimePluginRegistry.resolve(runtimeType)
        return plugin?.queryCapabilities() ?: declaredCapabilities ?: AiRuntimeProviderCapabilities(
            providerId = runtimeId,
            backendName = "Optional ${runtimeType.raw} JNI provider",
            runtimeType = runtimeType,
            supportedFormats = defaultFormats(),
            supportedTasks = supportedTasks,
            supportedPrecisions = emptySet(),
            supportedDevices = emptySet(),
            supportedDelegates = emptySet(),
            supportedQuantizations = emptySet(),
            supportedTensorLayouts = emptySet(),
            supportedInputTypes = emptySet(),
            supportedOutputTypes = emptySet(),
            maximumContext = 0,
            maximumImageResolution = 0,
            runtimeVersion = "unavailable",
            abiCompatible = FileSupport.currentAbiCompatible(),
            androidApiCompatible = FileSupport.currentApiCompatible(),
            metadata = mapOf("jni_library" to libraryName, "plugin_registered" to false),
        )
    }

    override fun queryMemory(): Map<String, Any> = NativeRuntimePluginRegistry.resolve(runtimeType)?.queryMemory()
        ?: mapOf("provider_state" to providerState.name.lowercase(), "memory_bytes" to 0L)

    override suspend fun benchmark(model: AiModelDescriptor): Map<String, Any> =
        NativeRuntimePluginRegistry.resolve(runtimeType)?.benchmark(model)
            ?: mapOf("status" to "unavailable", "provider" to runtimeId)

    override suspend fun health(): AiRuntimeProviderHealth = NativeRuntimePluginRegistry.resolve(runtimeType)?.probe()
        ?: AiRuntimeProviderHealth(providerState, providerMessage, 0L)

    override suspend fun execute(request: AiExecutionRequest, reporter: AiProgressReporter): AiExecutionResult {
        val plugin = NativeRuntimePluginRegistry.resolve(runtimeType)
            ?: return unavailable("Native plugin '$libraryName' is not registered")
        return plugin.execute(request, reporter)
    }

    private fun defaultFormats(): Set<String> = when (runtimeType) {
        AiRuntimeType.LLAMA_CPP -> setOf("gguf")
        AiRuntimeType.NCNN -> setOf("param", "bin", "ncnn")
        AiRuntimeType.MNN -> setOf("mnn")
        else -> emptySet()
    }
}

internal data class OnnxRuntimeTensorOutput(
    val values: List<Float>,
    val shape: LongArray = longArrayOf(),
)

internal object OnnxRuntimeClient {
    fun run(model: AiModelDescriptor, inputs: List<PreparedInferenceTensor>): Map<String, List<Float>> =
        runDetailed(model, inputs).mapValues { it.value.values }

    fun runDetailed(model: AiModelDescriptor, inputs: List<PreparedInferenceTensor>): Map<String, OnnxRuntimeTensorOutput> {
        val environment = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            environment.createSession(model.installPath, options).use { session ->
                val tensors = inputs.associate { input -> input.name to createTensor(environment, input) }
                try {
                    session.run(tensors).use { result ->
                        val outputMap = linkedMapOf<String, OnnxRuntimeTensorOutput>()
                        result.forEach { output ->
                            val info = output.value.info as? TensorInfo
                            outputMap[output.key] = OnnxRuntimeTensorOutput(
                                values = RuntimeOutputMapper.flatten(output.value.value),
                                shape = info?.shape ?: longArrayOf(),
                            )
                        }
                        inputs.firstOrNull { it.name.equals("attention_mask", ignoreCase = true) }?.let { tensor ->
                            outputMap["attention_mask"] = OnnxRuntimeTensorOutput(
                                values = when (tensor.dataType) {
                                    "int64" -> tensor.longs.map(Long::toFloat)
                                    "int32" -> tensor.ints.map(Int::toFloat)
                                    "float32" -> tensor.floats.toList()
                                    else -> emptyList()
                                },
                                shape = tensor.shape,
                            )
                        }
                        return outputMap
                    }
                } finally {
                    tensors.values.forEach(OnnxTensor::close)
                }
            }
        }
    }

    private fun createTensor(environment: OrtEnvironment, input: PreparedInferenceTensor): OnnxTensor = when (input.dataType) {
        "float32" -> OnnxTensor.createTensor(environment, FloatBuffer.wrap(input.floats), input.shape)
        "int64" -> OnnxTensor.createTensor(environment, LongBuffer.wrap(input.longs), input.shape)
        "int32" -> OnnxTensor.createTensor(environment, IntBuffer.wrap(input.ints), input.shape)
        "uint8" -> OnnxTensor.createTensor(
            environment,
            ByteBuffer.wrap(input.bytes),
            input.shape,
            OnnxJavaType.UINT8,
        )
        else -> throw ModelInferenceContractException("Unsupported ONNX tensor type '${input.dataType}'")
    }
}

private object TensorFlowLiteClient {
    fun run(
        model: AiModelDescriptor,
        inputs: List<PreparedInferenceTensor>,
        outputContracts: List<TensorOutputContract>,
    ): Map<String, List<Float>> {
        Interpreter(File(model.installPath)).use { interpreter ->
            require(inputs.size == interpreter.inputTensorCount) {
                "Model expects ${interpreter.inputTensorCount} inputs but its inference contract declares ${inputs.size}"
            }
            inputs.forEach { input ->
                interpreter.resizeInput(interpreter.getInputIndex(input.name), input.shape.map(Long::toInt).toIntArray())
            }
            interpreter.allocateTensors()
            val preparedInputs = arrayOfNulls<Any>(interpreter.inputTensorCount)
            inputs.forEach { input ->
                val index = interpreter.getInputIndex(input.name)
                preparedInputs[index] = inputBuffer(input)
            }
            require(preparedInputs.all { it != null }) { "Inference contract did not provide every TensorFlow Lite input" }

            require(outputContracts.size == interpreter.outputTensorCount) {
                "Model exposes ${interpreter.outputTensorCount} outputs but its inference contract declares ${outputContracts.size}"
            }
            val outputs = linkedMapOf<Int, Any>()
            val outputByIndex = linkedMapOf<Int, TensorOutputContract>()
            outputContracts.forEach { output ->
                val index = if (output.index >= 0) output.index else interpreter.getOutputIndex(output.name)
                require(index in 0 until interpreter.outputTensorCount) { "Output '${output.name}' has invalid TensorFlow Lite index $index" }
                outputs[index] = ByteBuffer.allocateDirect(interpreter.getOutputTensor(index).numBytes())
                    .order(ByteOrder.nativeOrder())
                outputByIndex[index] = output
            }
            interpreter.runForMultipleInputsOutputs(preparedInputs.requireNoNulls(), outputs)
            return outputByIndex.entries.associate { (index, output) ->
                output.name to decodeBuffer(outputs.getValue(index) as ByteBuffer, output)
            }
        }
    }

    private fun inputBuffer(input: PreparedInferenceTensor): ByteBuffer {
        val bytes = when (input.dataType) {
            "float32" -> input.floats.size * Float.SIZE_BYTES
            "int64" -> input.longs.size * Long.SIZE_BYTES
            "int32" -> input.ints.size * Int.SIZE_BYTES
            "uint8" -> input.bytes.size
            else -> throw ModelInferenceContractException("Unsupported TensorFlow Lite tensor type '${input.dataType}'")
        }
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()).apply {
            when (input.dataType) {
                "float32" -> input.floats.forEach(::putFloat)
                "int64" -> input.longs.forEach(::putLong)
                "int32" -> input.ints.forEach(::putInt)
                "uint8" -> put(input.bytes)
            }
            rewind()
        }
    }

    private fun decodeBuffer(buffer: ByteBuffer, output: TensorOutputContract): List<Float> {
        buffer.rewind()
        return when (output.dataType) {
            "float32" -> List(buffer.capacity() / Float.SIZE_BYTES) { buffer.float }
            "int64" -> List(buffer.capacity() / Long.SIZE_BYTES) { buffer.long.toFloat() }
            "int32" -> List(buffer.capacity() / Int.SIZE_BYTES) { buffer.int.toFloat() }
            "uint8" -> List(buffer.capacity()) {
                val raw = buffer.get().toInt() and 0xff
                if (output.quantizationScale > 0f) {
                    (raw - output.quantizationZeroPoint) * output.quantizationScale
                } else {
                    raw.toFloat()
                }
            }
            else -> throw ModelInferenceContractException("Unsupported TensorFlow Lite output type '${output.dataType}'")
        }
    }
}

internal object RuntimeOutputMapper {
    fun toResult(
        request: AiExecutionRequest,
        model: AiModelDescriptor,
        contract: ModelInferenceContract,
        outputs: Map<String, List<Float>>,
        runtimeId: String,
    ): AiExecutionResult {
        val taskType = AiTaskTypes.normalize(request.taskType)
        val result = linkedMapOf<String, Any>("runtime" to runtimeId)
        when (contract.outputDecoder.type) {
            "embedding" -> {
                val rawEmbedding = decodeEmbedding(contract, outputs)
                val normalizedEmbedding = normalizeL2(rawEmbedding)
                if (taskType == "face_embedding") {
                    result["raw_embedding"] = rawEmbedding
                    result["normalized_embedding"] = normalizedEmbedding
                    result["embedding"] = normalizedEmbedding
                } else {
                    val embedding = l2Normalize(rawEmbedding)
                    result["embedding"] = embedding
                    if (taskType == "face_feature_extraction") result["feature_vector"] = embedding
                }
            }
            "classification" -> applyClassification(taskType, contract, outputs, result)
            "tokens" -> applyText(taskType, contract, outputs, result)
            "reranking" -> applyReranking(contract, outputs, result)
            "regression" -> applyRegression(contract, outputs, result)
            "detection" -> result["detections"] = decodeDetections(contract, outputs)
            "face_detection" -> result["faces"] = decodeScrfdFaces(request, outputs)
            "landmarks_2d" -> result["landmarks_2d"] = decodeLandmarks2d(request, contract, outputs)
            "landmarks_3d" -> result["landmarks_3d"] = decodeLandmarks3d(request, contract, outputs)
            "gender_age" -> result["gender_age"] = decodeGenderAgeResult(request, contract, outputs)
            "vision_features" -> result.putAll(decodeVisionFeatures(contract, outputs))
            "similarity" -> applySimilarity(taskType, request, contract, outputs, result)
        }
        result["confidence_scoring"] = mapOf(
            "type" to contract.confidence.type,
            "threshold" to contract.confidence.threshold,
        )
        return AiExecutionResult(
            ok = true,
            status = "succeeded",
            message = "$runtimeId inference completed",
            details = mapOf(
                "task_type" to taskType,
                "model_id" to model.modelId,
                "model_version" to model.version,
                "embedding" to (result["embedding"] ?: emptyList<Float>()),
                "result" to result,
            ) + when {
                result["aesthetic_score"] != null -> mapOf(
                    "score" to result.getValue("score"),
                    "aesthetic_score" to result.getValue("aesthetic_score"),
                )
                result["logit"] != null -> mapOf("logit" to result.getValue("logit"), "score" to result.getValue("score"))
                else -> emptyMap()
            },
        )
    }

    private fun applyReranking(
        contract: ModelInferenceContract,
        outputs: Map<String, List<Float>>,
        result: MutableMap<String, Any>,
    ) {
        val output = primaryOutput(contract, outputs)
        val tensor = contract.outputs.firstOrNull { it.name == contract.outputDecoder.outputName }
            ?: throw ModelInferenceContractException("Reranking contract did not wire output '${contract.outputDecoder.outputName}'")
        require(tensor.shape.size == 2 && tensor.shape[1] == 1) {
            "Reranking output must have shape [B,1] but was ${tensor.shape}"
        }
        require(output.size == 1) { "Single-pair reranking output must contain one scalar logit but got ${output.size}" }
        result["logit"] = output.single()
        result["score"] = output.single()
    }

    private fun applyRegression(
        contract: ModelInferenceContract,
        outputs: Map<String, List<Float>>,
        result: MutableMap<String, Any>,
    ) {
        val output = primaryOutput(contract, outputs)
        val tensor = contract.outputs.firstOrNull { it.name == contract.outputDecoder.outputName }
            ?: throw ModelInferenceContractException("Regression contract did not wire output '${contract.outputDecoder.outputName}'")
        require(tensor.shape.size == 2 && tensor.shape[1] == 1) {
            "Regression output must have shape [B,1] but was ${tensor.shape}"
        }
        require(output.size == 1) { "Single-image aesthetic output must contain one scalar but got ${output.size}" }
        result["score"] = output.single()
        result["aesthetic_score"] = output.single()
    }

    fun flatten(value: Any?): List<Float> = when (value) {
        is Number -> listOf(value.toFloat())
        is FloatArray -> value.toList()
        is DoubleArray -> value.map(Double::toFloat)
        is IntArray -> value.map(Int::toFloat)
        is LongArray -> value.map(Long::toFloat)
        is ByteArray -> value.map { (it.toInt() and 0xff).toFloat() }
        is Array<*> -> value.flatMap(::flatten)
        is Iterable<*> -> value.flatMap(::flatten)
        else -> emptyList()
    }

    private fun applyClassification(
        taskType: String,
        contract: ModelInferenceContract,
        outputs: Map<String, List<Float>>,
        result: MutableMap<String, Any>,
    ) {
        val ranked = scores(contract, primaryOutput(contract, outputs))
            .mapIndexed { index, score ->
                mapOf("label" to contract.outputDecoder.labels[index], "score" to score.toDouble())
            }
            .filter { (it["score"] as Double) >= contract.confidence.threshold }
            .sortedByDescending { it["score"] as Double }
        when (taskType) {
            "tag_prediction" -> {
                result["tags"] = ranked.take(contract.outputDecoder.maxResults).map { it["label"].toString() }
                result["scored_tags"] = ranked.take(contract.outputDecoder.maxResults).map { entry ->
                    mapOf("tag" to entry["label"], "score" to entry["score"])
                }
            }
            "character_recognition", "series_recognition", "artist_recognition" -> {
                result["top_match"] = ranked.firstOrNull()?.get("label")?.toString().orEmpty()
                result["candidates"] = ranked.take(contract.outputDecoder.maxResults).map { entry ->
                    mapOf("name" to entry["label"], "confidence" to entry["score"])
                }
            }
            "metadata_extraction" -> result["metadata"] = ranked.associate { entry ->
                entry["label"].toString() to entry["score"] as Double
            }
            else -> {
                result["label"] = ranked.firstOrNull()?.get("label")?.toString().orEmpty()
                result["ranked_labels"] = ranked.take(contract.outputDecoder.maxResults)
            }
        }
    }

    private fun applyText(
        taskType: String,
        contract: ModelInferenceContract,
        outputs: Map<String, List<Float>>,
        result: MutableMap<String, Any>,
    ) {
        val text = decodeTokens(primaryOutput(contract, outputs), contract)
        when (taskType) {
            "ocr" -> result["text"] = text
            "captioning" -> result["caption"] = text
            "prompt_generation" -> result["prompt"] = text
            else -> result["text"] = text
        }
    }

    private fun applySimilarity(
        taskType: String,
        request: AiExecutionRequest,
        contract: ModelInferenceContract,
        outputs: Map<String, List<Float>>,
        result: MutableMap<String, Any>,
    ) {
        val candidates = (request.payload["candidates"] as? List<*>)
            ?.mapNotNull { item -> item as? Map<*, *> }
            ?: throw ModelInferenceContractException("$taskType requires candidates whose scores are produced by the model")
        val scores = scores(contract, primaryOutput(contract, outputs))
        require(scores.size >= candidates.size) { "Model returned ${scores.size} scores for ${candidates.size} candidates" }
        val matches = candidates.mapIndexed { index, candidate ->
            val score = scores[index].toDouble()
            val id = candidate["image_id"] ?: candidate["id"] ?: index
            mapOf("image_id" to id, "score" to score, "rank" to 0)
        }.filter { (it["score"] as Double) >= contract.confidence.threshold }
            .sortedByDescending { it["score"] as Double }
            .take(contract.outputDecoder.maxResults)
            .mapIndexed { index, entry -> entry + mapOf("rank" to (index + 1)) }
        if (taskType == "duplicate_detection") {
            result["duplicate_pairs"] = matches
            result["groups"] = emptyList<Map<String, Any>>()
        } else {
            result["matches"] = matches
        }
    }

    private fun decodeDetections(
        contract: ModelInferenceContract,
        outputs: Map<String, List<Float>>,
    ): List<Map<String, Any>> {
        val decoder = contract.outputDecoder
        require(decoder.boxOutputName.isNotBlank() && decoder.scoreOutputName.isNotBlank() && decoder.labelOutputName.isNotBlank()) {
            "Detection decoder must define box_output_name, score_output_name, and label_output_name"
        }
        val boxes = outputs[decoder.boxOutputName] ?: throw ModelInferenceContractException("Missing detection boxes '${decoder.boxOutputName}'")
        val rawScores = outputs[decoder.scoreOutputName] ?: throw ModelInferenceContractException("Missing detection scores '${decoder.scoreOutputName}'")
        val labelIds = outputs[decoder.labelOutputName] ?: throw ModelInferenceContractException("Missing detection labels '${decoder.labelOutputName}'")
        val confidences = scoreValues(contract.confidence, rawScores)
        val count = minOf(boxes.size / 4, confidences.size, labelIds.size)
        return (0 until count).mapNotNull { index ->
            val confidence = confidences[index]
            if (confidence < contract.confidence.threshold) return@mapNotNull null
            val label = decoder.labels.getOrNull(labelIds[index].toInt()) ?: return@mapNotNull null
            val offset = index * 4
            mapOf(
                "label" to label,
                "confidence" to confidence.toDouble(),
                "bbox" to mapOf(
                    "x" to boxes[offset].toDouble(),
                    "y" to boxes[offset + 1].toDouble(),
                    "w" to boxes[offset + 2].toDouble(),
                    "h" to boxes[offset + 3].toDouble(),
                ),
            )
        }.take(decoder.maxResults)
    }

    private fun decodeScrfdFaces(
        request: AiExecutionRequest,
        outputs: Map<String, List<Float>>,
    ): List<Map<String, Any>> {
        val sourceWidth = (request.payload["image_width"] as? Number)?.toInt() ?: 640
        val sourceHeight = (request.payload["image_height"] as? Number)?.toInt() ?: 640
        return ScrfdDetector().decodeOutputs(outputs.values.toList(), sourceWidth, sourceHeight).map { face ->
            mapOf(
                "bbox" to listOf(face.x1, face.y1, face.x2, face.y2),
                "det_score" to face.score,
                "kps" to face.keypoints.map { point -> listOf(point[0], point[1]) },
            )
        }
    }

    private fun decodeLandmarks2d(
        request: AiExecutionRequest,
        contract: ModelInferenceContract,
        outputs: Map<String, List<Float>>,
    ): Map<String, Any> {
        val values = primaryOutput(contract, outputs)
        require(values.size == 212) { "2D landmark output must contain exactly 212 values" }
        val rawBox = request.payload["face_bbox"] ?: request.payload["bbox"]
        val boxValues = rawBox as? List<*> ?: throw ModelInferenceContractException("2D landmark result requires face bbox")
        require(boxValues.size >= 4) { "2D landmark face bbox must contain four coordinates" }
        val box = FaceBoundingBox(boxValues[0].toString().toFloat(), boxValues[1].toString().toFloat(), boxValues[2].toString().toFloat(), boxValues[3].toString().toFloat())
        val transform = InsightFaceLandmarkAlignment.fromBoundingBox(box)
        val points = InsightFaceLandmarkAlignment.modelOutputToSourceCoordinates(values, transform)
            .map { point -> listOf(point.x, point.y) }
        return mapOf(
            "face_index" to (request.payload["face_index"] ?: 0),
            "bbox" to listOf(box.x1, box.y1, box.x2, box.y2),
            "landmarks_2d" to points,
        )
    }

    private fun decodeLandmarks3d(
        request: AiExecutionRequest,
        contract: ModelInferenceContract,
        outputs: Map<String, List<Float>>,
    ): Map<String, Any> {
        val values = primaryOutput(contract, outputs)
        require(values.size == 3309) { "3D landmark output must contain exactly 3309 values" }
        val rawBox = request.payload["face_bbox"] ?: request.payload["bbox"]
        val boxValues = rawBox as? List<*> ?: throw ModelInferenceContractException("3D landmark result requires face bbox")
        require(boxValues.size >= 4) { "3D landmark face bbox must contain four coordinates" }
        val box = FaceBoundingBox(boxValues[0].toString().toFloat(), boxValues[1].toString().toFloat(), boxValues[2].toString().toFloat(), boxValues[3].toString().toFloat())
        val transform = InsightFaceLandmarkAlignment.fromBoundingBox(box)
        val points = InsightFaceLandmarkAlignment.model3dOutputToSourceCoordinates(values, transform)
            .map { point -> listOf(point.x, point.y, point.z) }
        return mapOf(
            "face_index" to (request.payload["face_index"] ?: 0),
            "bbox" to listOf(box.x1, box.y1, box.x2, box.y2),
            "landmarks_3d" to points,
        )
    }

    private fun decodeGenderAgeResult(
        request: AiExecutionRequest,
        contract: ModelInferenceContract,
        outputs: Map<String, List<Float>>,
    ): Map<String, Any> {
        val prediction = decodeGenderAge(primaryOutput(contract, outputs))
        val rawBox = request.payload["face_bbox"] ?: request.payload["bbox"]
        val boxValues = rawBox as? List<*> ?: throw ModelInferenceContractException("Gender-age result requires face bbox")
        require(boxValues.size >= 4) { "Gender-age face bbox must contain four coordinates" }
        return mapOf(
            "face_index" to (request.payload["face_index"] ?: 0),
            "bbox" to boxValues.take(4),
            "gender_index" to prediction.genderIndex,
            "age" to prediction.age,
            "raw_output" to prediction.rawOutput,
        )
    }

    private fun decodeVisionFeatures(
        contract: ModelInferenceContract,
        outputs: Map<String, List<Float>>,
    ): Map<String, Any> {
        val features = decodeFlorenceImageFeatures(primaryOutput(contract, outputs), contract.outputDecoder.hiddenDimension)
        return mapOf(
            "image_features" to features.tokens,
            "sequence_length" to features.sequenceLength,
            "hidden_size" to features.hiddenSize,
            "pooled" to features.pooled,
            "normalized" to features.normalized,
        )
    }

    private fun primaryOutput(contract: ModelInferenceContract, outputs: Map<String, List<Float>>): List<Float> {
        val name = contract.outputDecoder.outputName.ifBlank {
            throw ModelInferenceContractException("Embedding contract must declare output_decoder.output_name; no first-output fallback is acceptable")
        }
        return outputs[name] ?: throw ModelInferenceContractException("Model did not return declared output '$name'")
    }

    private fun decodeEmbedding(contract: ModelInferenceContract, outputs: Map<String, List<Float>>): List<Float> {
        val raw = primaryOutput(contract, outputs)
        val outputTensor = contract.outputs.firstOrNull { it.name == contract.outputDecoder.outputName }
            ?: throw ModelInferenceContractException("Model contract did not wire output '${contract.outputDecoder.outputName}'")
        val shape = outputTensor.shape.takeIf { it.isNotEmpty() } ?: return raw
        return when (shape.size) {
            1 -> {
                require(raw.size == shape[0]) { "Embedding direct vector expects ${shape[0]} values but got ${raw.size}" }
                raw
            }
            2 -> {
                if (contract.taskType == "face_embedding") {
                    require(shape[1] == 512 && raw.size == 512 && (shape[0] == 1 || shape[0] == -1)) {
                        "ArcFace output must contain exactly 512 values with shape [B,512]"
                    }
                } else {
                    require(shape[0] == 1 || shape[1] == 1) {
                        "Embedding rank-2 output must be [1,D] or [D] but got ${shape}"
                    }
                }
                raw
            }
            3 -> {
                require(contract.outputDecoder.pooling.isNotBlank()) {
                    "Embedding rank-3 output ${shape} requires an explicit inference contract pooling policy"
                }
                require(shape[0] == 1) { "Embedding rank-3 output must be [1,N,D] but got ${shape}" }
                require(shape[2] == 768) { "Embedding hidden dimension must be 768 for real Nomic text package; got ${shape[2]}" }
                val hidden = shape[2]
                when (contract.outputDecoder.pooling.lowercase()) {
                    "cls" -> {
                        require(raw.size >= hidden) { "Token embedding output compressed list has ${raw.size} values but needs at least $hidden for cls token extraction" }
                        raw.take(hidden)
                    }
                    "mean", "mean_pool", "mean_pooling" -> meanPool(raw, shape)
                    "mean_masked" -> meanMaskedPool(raw, shape, outputs["attention_mask"] ?: emptyList())
                    else -> throw ModelInferenceContractException("Unsupported embedding pooling '${contract.outputDecoder.pooling}' for token output ${shape}")
                }
            }
            else -> throw ModelInferenceContractException("Ambiguous token embedding output shape ${shape}; rank-N flattening is rejected")
        }
    }

    private fun meanMaskedPool(values: List<Float>, shape: List<Int>, mask: List<Float>): List<Float> {
        require(shape.size == 3 && shape[0] == 1) { "Mean masked pooling requires a rank-3 token output [1,N,D]" }
        val steps = shape[1]
        val dim = shape[2]
        require(values.size == steps * dim) { "Token embedding flattened size ${values.size} does not match declared [1,$steps,$dim]" }
        require(mask.size == steps) { "Attention mask length ${mask.size} does not match token steps $steps" }
        val valid = mask.map { if (it != 0f) 1 else 0 }.toList()
        val count = valid.sum()
        require(count > 0) { "Mean masked pooling requires at least one valid attention-mask token" }
        val sums = MutableList(dim) { 0f }
        var offset = 0
        for (token in 0 until steps) {
            if (valid[token] == 1) {
                for (d in 0 until dim) {
                    sums[d] += values[offset + d]
                }
            }
            offset += dim
        }
        return sums.map { it / count.toFloat() }
    }

    private fun meanPool(values: List<Float>, shape: List<Int>): List<Float> {
        require(shape.size == 3 && shape[0] == 1) { "Mean pooling requires a rank-3 token output [1,N,D]" }
        val steps = shape[1]
        val dim = shape[2]
        require(values.size == steps * dim) { "Token embedding flattened size ${values.size} does not match declared [1,$steps,$dim]" }
        val sums = MutableList(dim) { 0f }
        var offset = 0
        repeat(steps) {
            for (i in 0 until dim) {
                sums[i] += values[offset + i]
            }
            offset += dim
        }
        val divisor = steps.toFloat().coerceAtLeast(1f)
        return sums.map { it / divisor }
    }

    internal fun scores(contract: ModelInferenceContract, output: List<Float>): List<Float> {
        require(output.size >= contract.outputDecoder.labels.size) {
            "Model returned ${output.size} values for ${contract.outputDecoder.labels.size} declared labels"
        }
        return scoreValues(contract.confidence, output.take(contract.outputDecoder.labels.size))
    }

    internal fun scoreValues(contract: ConfidenceContract, values: List<Float>): List<Float> = when (contract.type) {
        "softmax" -> stableSoftmax(values)
        "sigmoid" -> values.map { (1.0 / (1.0 + exp(-it.toDouble()))).toFloat() }
        "cosine" -> values.map { ((it + 1f) / 2f).coerceIn(0f, 1f) }
        else -> values.map { it.coerceIn(0f, 1f) }
    }

    internal fun stableSoftmax(values: List<Float>): List<Float> {
        val maximum = values.maxOrNull() ?: 0f
        val exponents = values.map { exp((it - maximum).toDouble()).toFloat() }
        val total = exponents.sum().coerceAtLeast(1e-12f)
        return exponents.map { it / total }
    }

    private fun decodeTokens(values: List<Float>, contract: ModelInferenceContract): String {
        val vocabulary = contract.tokenizer.vocabulary
        require(vocabulary.isNotEmpty()) { "Token decoder requires tokenizer vocabulary" }
        return values.map { it.toInt() }
            .takeWhile { tokenId -> contract.outputDecoder.endTokenId == null || tokenId != contract.outputDecoder.endTokenId }
            .mapNotNull(vocabulary::getOrNull)
            .filterNot { token -> token in setOf(contract.tokenizer.startToken, contract.tokenizer.endToken, contract.tokenizer.padToken) }
            .joinToString(" ")
            .replace(" ##", "")
            .trim()
    }

    private fun l2Normalize(values: List<Float>): List<Float> {
        val magnitude = sqrt(values.sumOf { value -> value.toDouble() * value }.coerceAtLeast(0.0))
        return if (magnitude > 0.0) values.map { (it / magnitude).toFloat() } else values
    }
}

private fun nativeCapability(
    runtimeId: String,
    runtimeType: AiRuntimeType,
    available: Boolean,
    transport: String,
    extra: Map<String, Any> = emptyMap(),
): AiBackendCapability = AiBackendCapability(
    runtimeId = runtimeId,
    runtimeType = runtimeType,
    supportedTasks = AiTaskTypes.EXECUTION_TASKS,
    supportsCancellation = false,
    supportsPauseResume = false,
    maxConcurrentTasks = 1,
    metadata = extra + mapOf("available" to available, "transport" to transport),
)

private fun AiBackendRuntime.unavailable(message: String): AiExecutionResult = AiExecutionResult(
    ok = false,
    status = "runtime_unavailable",
    message = message,
    details = mapOf("runtime_id" to runtimeId),
)

private fun AiBackendRuntime.incompatible(message: String): AiExecutionResult = AiExecutionResult(
    ok = false,
    status = "incompatible",
    message = message,
    details = mapOf("runtime_id" to runtimeId),
)

private fun failure(error: Throwable, runtimeId: String): AiExecutionResult = AiExecutionResult(
    ok = false,
    status = "runtime_failure",
    message = error.message ?: error.javaClass.simpleName,
    details = mapOf("runtime_id" to runtimeId),
)

private fun Any?.numberList(): List<Float> = when (this) {
    is List<*> -> mapNotNull { (it as? Number)?.toFloat() ?: it?.toString()?.toFloatOrNull() }
    is FloatArray -> toList()
    is DoubleArray -> map(Double::toFloat)
    else -> emptyList()
}

private fun Any?.stringList(): List<String> = when (this) {
    is List<*> -> mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
    is String -> split(',', '|').map(String::trim).filter(String::isNotBlank)
    else -> emptyList()
}

private object RealFlorenceOnnxExecutor : FlorenceOnnxExecutor {
    override fun run(role: String, model: AiModelDescriptor, inputs: List<PreparedInferenceTensor>): Map<String, List<Float>> =
        OnnxRuntimeClient.run(model, inputs)
}