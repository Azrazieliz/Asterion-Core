package com.ailm.android.runtime.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Describes the exact transformation between an application task and one
 * imported native model. Model descriptors carry this data in
 * metadata.inference_contracts so a model can support more than one task
 * without relying on task-name heuristics.
 */
internal data class ModelInferenceContract(
    val taskType: String,
    val tokenizer: TokenizerContract,
    val imagePreprocessing: ImagePreprocessingContract,
    val inputs: List<TensorInputContract>,
    val outputs: List<TensorOutputContract>,
    val outputDecoder: OutputDecoderContract,
    val confidence: ConfidenceContract,
) {
    companion object {
        fun resolve(model: AiModelDescriptor, rawTaskType: String): ModelInferenceContract {
            val taskType = AiTaskTypes.normalize(rawTaskType)
            val contracts = model.metadata["inference_contracts"].asStringMap()
                ?: throw ModelInferenceContractException("Model ${model.modelId}@${model.version} is missing metadata.inference_contracts")
            val raw = contracts[taskType].asStringMap()
                ?: throw ModelInferenceContractException("Model ${model.modelId}@${model.version} has no inference contract for '$taskType'")

            val tokenizer = TokenizerContract.parse(raw["tokenizer"].asStringMap(), taskType)
            val imagePreprocessing = ImagePreprocessingContract.parse(raw["image_preprocessing"].asStringMap(), taskType)
            val inputs = raw["inputs"].asMapList().map(TensorInputContract::parse)
            require(inputs.isNotEmpty()) { "Inference contract for '$taskType' must define inputs" }
            val outputs = raw["outputs"].asMapList().map(TensorOutputContract::parse)
            require(outputs.isNotEmpty()) { "Inference contract for '$taskType' must define outputs" }
            if (taskType == "face_embedding") {
                require(inputs.size == 1 && inputs.single().name == "input.1" && inputs.single().dataType == "float32") {
                    "ArcFace face_embedding requires one float32 input named input.1"
                }
                require(inputs.single().shape.size == 4 && inputs.single().shape[1] == 3 && inputs.single().shape[2] == 112 && inputs.single().shape[3] == 112) {
                    "ArcFace face_embedding input must have shape [B,3,112,112]"
                }
                require(outputs.size == 1 && outputs.single().name == "683" && outputs.single().dataType == "float32") {
                    "ArcFace face_embedding requires one float32 output named 683"
                }
                require(outputs.single().shape.size == 2 && outputs.single().shape[1] == 512) {
                    "ArcFace face_embedding output must have shape [B,512]"
                }
            }
            if (taskType == "landmark_2d") {
                require(inputs.size == 1 && inputs.single().name == "data" && inputs.single().dataType == "float32") {
                    "2D landmarks require one float32 input named data"
                }
                require(inputs.single().shape.size == 4 && inputs.single().shape[1] == 3 && inputs.single().shape[2] == 192 && inputs.single().shape[3] == 192) {
                    "2D landmark input must have shape [B,3,192,192]"
                }
                require(outputs.size == 1 && outputs.single().name == "fc1" && outputs.single().dataType == "float32") {
                    "2D landmarks require one float32 output named fc1"
                }
                require(outputs.single().shape.size == 2 && outputs.single().shape[1] == 212) {
                    "2D landmark output must have shape [B,212]"
                }
            }
            if (taskType == "landmark_3d") {
                require(inputs.size == 1 && inputs.single().name == "data" && inputs.single().dataType == "float32") {
                    "3D landmarks require one float32 input named data"
                }
                require(inputs.single().shape.size == 4 && inputs.single().shape[1] == 3 && inputs.single().shape[2] == 192 && inputs.single().shape[3] == 192) {
                    "3D landmark input must have shape [B,3,192,192]"
                }
                require(outputs.size == 1 && outputs.single().name == "fc1" && outputs.single().dataType == "float32") {
                    "3D landmarks require one float32 output named fc1"
                }
                require(outputs.single().shape.size == 2 && outputs.single().shape[1] == 3309) {
                    "3D landmark output must have shape [B,3309]"
                }
            }
            if (taskType == "gender_age") {
                require(inputs.size == 1 && inputs.single().name == "data" && inputs.single().dataType == "float32") {
                    "Gender-age requires one float32 input named data"
                }
                require(inputs.single().shape.size == 4 && inputs.single().shape[1] == 3 && inputs.single().shape[2] == 96 && inputs.single().shape[3] == 96) {
                    "Gender-age input must have shape [B,3,96,96]"
                }
                require(outputs.size == 1 && outputs.single().name == "fc1" && outputs.single().dataType == "float32") {
                    "Gender-age requires one float32 output named fc1"
                }
                require(outputs.single().shape.size == 2 && outputs.single().shape[1] == 3) {
                    "Gender-age output must have shape [B,3]"
                }
            }
            if (taskType == "vision_encoder") {
                require(inputs.size == 1 && inputs.single().name == "pixel_values" && inputs.single().dataType == "float32") {
                    "Florence vision encoder requires one float32 input named pixel_values"
                }
                require(inputs.single().shape.size == 4 && inputs.single().shape[1] == 3) {
                    "Florence vision encoder input must have shape [B,3,H,W]"
                }
                require(outputs.size == 1 && outputs.single().name == "image_features" && outputs.single().dataType == "float32") {
                    "Florence vision encoder requires one float32 output named image_features"
                }
                require(outputs.single().shape.size == 3 && outputs.single().shape[2] == 768) {
                    "Florence image_features must have shape [B,T,768]"
                }
            }
            val outputDecoder = OutputDecoderContract.parse(raw["output_decoder"].asStringMap(), taskType)
            val confidence = ConfidenceContract.parse(raw["confidence_scoring"].asStringMap(), taskType)

            inputs.forEach { input ->
                if (input.source == "image" && !imagePreprocessing.enabled) {
                    throw ModelInferenceContractException("Image input '${input.name}' requires enabled image_preprocessing for '$taskType'")
                }
                if (input.source in TEXT_INPUT_SOURCES && tokenizer.type == "none") {
                    throw ModelInferenceContractException("Text input '${input.name}' requires a tokenizer for '$taskType'")
                }
            }
            validateDecoder(taskType, outputDecoder.type)
            return ModelInferenceContract(
                taskType = taskType,
                tokenizer = tokenizer,
                imagePreprocessing = imagePreprocessing,
                inputs = inputs,
                outputs = outputs,
                outputDecoder = outputDecoder,
                confidence = confidence,
            )
        }

        fun validationIssues(model: AiModelDescriptor): List<AiValidationIssue> {
            if (model.supportedTasks.isEmpty()) {
                return listOf(
                    AiValidationIssue(
                        code = "inference_contract_missing_tasks",
                        message = "An executable model must declare supported_tasks",
                    ),
                )
            }
            if (AiRuntimeType.fromRaw(model.requiredRuntime) == AiRuntimeType.LLAMA_CPP) {
                return llamaCppValidationIssues(model)
            }
            return model.supportedTasks.mapNotNull { rawTask ->
                val taskType = AiTaskTypes.normalize(rawTask)
                runCatching { resolve(model, taskType) }.exceptionOrNull()?.let { error ->
                    AiValidationIssue(
                        code = "inference_contract_invalid",
                        message = "${taskType}: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            }
        }

        private fun llamaCppValidationIssues(model: AiModelDescriptor): List<AiValidationIssue> {
            val issues = mutableListOf<AiValidationIssue>()
            val metadata = model.metadata["llama_cpp"] as? Map<*, *>
            if (metadata == null) {
                issues += AiValidationIssue(
                    code = "inference_contract_invalid",
                    message = "llama_cpp: Model ${model.modelId}@${model.version} is missing metadata.llama_cpp",
                )
                return issues
            }

            val textOnly = metadata["text_only"] == true
            val multimodal = metadata["multimodal"] == true
            val allowedTasks = buildSet {
                add("text_generation")
                add("prompt_generation")
                add("normalization")
                if (multimodal) {
                    add("captioning")
                    add("series_recognition")
                    add("character_recognition")
                    add("tag_prediction")
                }
            }
            model.supportedTasks.forEach { rawTask ->
                val taskType = AiTaskTypes.normalize(rawTask)
                if (taskType !in allowedTasks) {
                    issues += AiValidationIssue(
                        code = "inference_contract_invalid",
                        message = "${taskType}: LLAMA_CPP does not expose this task through its native execution contract",
                    )
                }
            }
            if (!textOnly && !multimodal) {
                issues += AiValidationIssue(
                    code = "inference_contract_invalid",
                    message = "llama_cpp: metadata.llama_cpp must declare text_only=true or multimodal=true",
                )
            }
            if (multimodal) {
                val rolePaths = model.metadata["artifact_paths_by_role"] as? Map<*, *>
                val projectorPath = rolePaths?.get("vision_projector")?.toString()?.trim().orEmpty()
                if (projectorPath.isBlank()) {
                    issues += AiValidationIssue(
                        code = "inference_contract_invalid",
                        message = "text_generation: LLAMA_CPP multimodal models require artifact_paths_by_role.vision_projector",
                    )
                }
            }
            return issues
        }

        private fun validateDecoder(taskType: String, decoderType: String) {
            val accepted = when (taskType) {
                "embedding_generation", "face_embedding", "face_feature_extraction" -> setOf("embedding")
                "similarity_search", "duplicate_detection" -> setOf("similarity")
                "detection", "face_detection" -> setOf("detection")
                "landmark_2d" -> setOf("landmarks_2d")
                "landmark_3d" -> setOf("landmarks_3d")
                "gender_age" -> setOf("gender_age")
                "vision_encoder" -> setOf("vision_features")
                "ocr", "captioning", "prompt_generation", "translation", "reasoning", "normalization", "knowledge_pack_execution" -> setOf("tokens")
                "text_reranking" -> setOf("reranking")
                "aesthetic_scoring" -> setOf("regression")
                "character_recognition", "series_recognition", "artist_recognition", "tag_prediction", "metadata_extraction", "classification", "nsfw_classification" -> setOf("classification")
                else -> emptySet()
            }
            if (decoderType !in accepted) {
                throw ModelInferenceContractException(
                    "Output decoder '$decoderType' is not supported for '$taskType'; expected ${accepted.sorted().joinToString()}",
                )
            }
        }

        private val TEXT_INPUT_SOURCES = setOf("text_ids", "attention_mask", "token_type_ids")
    }
}

internal data class TokenizerContract(
    val type: String,
    val vocabulary: List<String>,
    val unknownToken: String,
    val startToken: String,
    val endToken: String,
    val padToken: String,
    val maxLength: Int,
    val modelType: String = "wordpiece",
    val normalizer: String = "lowercase",
    val preTokenizer: String = "whitespace",
    val vocabularyScores: List<Float> = emptyList(),
    val pairTemplate: String = "",
    val bpeMerges: List<String> = emptyList(),
    val sourceFile: String = "",
    val sourceFormat: String = "",
) {
    companion object {
        fun parse(raw: Map<String, Any>?, taskType: String): TokenizerContract {
            val value = raw ?: throw ModelInferenceContractException("Inference contract for '$taskType' must define tokenizer")
            val type = value["type"].text().lowercase()
            require(type in setOf("none", "wordpiece", "character", "bpe", "unigram")) {
                "Tokenizer type '$type' is unsupported for '$taskType'"
            }
            val vocabulary = value["vocabulary"].stringList()
            val sourceFile = value["source_file"]?.toString()?.trim().orEmpty()
            val sourceFormat = value["source_format"]?.toString()?.trim().orEmpty()
            if (type != "none" && vocabulary.isEmpty() && sourceFile.isBlank()) {
                throw ModelInferenceContractException("Tokenizer '$type' for '$taskType' must define vocabulary or source_file")
            }
            if (sourceFile.isNotBlank() && !File(sourceFile).isFile) {
                throw ModelInferenceContractException("Tokenizer source_file for '$taskType' does not exist: $sourceFile")
            }
            return TokenizerContract(
                type = type,
                vocabulary = vocabulary,
                unknownToken = value["unknown_token"].text("[UNK]"),
                startToken = value["start_token"].text("[CLS]"),
                endToken = value["end_token"].text("[SEP]"),
                padToken = value["pad_token"].text("[PAD]"),
                maxLength = value["max_length"].intValue(0).coerceAtLeast(0),
                modelType = value["model_type"].text(type),
                normalizer = value["normalizer"].text("lowercase"),
                preTokenizer = value["pre_tokenizer"].text("whitespace"),
                vocabularyScores = value["vocabulary_scores"].floatList(),
                pairTemplate = value["pair_template"].text(),
                bpeMerges = value["bpe_merges"].stringList(),
                sourceFile = sourceFile,
                sourceFormat = sourceFormat,
            )
        }
    }
}

internal data class ImagePreprocessingContract(
    val enabled: Boolean,
    val width: Int,
    val height: Int,
    val channels: Int,
    val colorSpace: String,
    val resizeMode: String,
    val scale: Float,
    val mean: List<Float>,
    val standardDeviation: List<Float>,
) {
    companion object {
        fun parse(raw: Map<String, Any>?, taskType: String): ImagePreprocessingContract {
            val value = raw ?: throw ModelInferenceContractException("Inference contract for '$taskType' must define image_preprocessing")
            val enabled = value["enabled"].booleanValue(false)
            val width = value["width"].intValue(0)
            val height = value["height"].intValue(0)
            val channels = value["channels"].intValue(3)
            val colorSpace = value["color_space"].text("rgb").lowercase()
            val resizeMode = value["resize_mode"].text("stretch").lowercase()
            val mean = value["mean"].floatList().ifEmpty { listOf(0f) }
            val standardDeviation = value["std"].floatList().ifEmpty { listOf(1f) }
            if (enabled) {
                require(width > 0 && height > 0) { "Image preprocessing for '$taskType' requires positive width and height" }
                require(channels in 1..4) { "Image preprocessing for '$taskType' has unsupported channels: $channels" }
                require(colorSpace in setOf("rgb", "rgba", "grayscale")) { "Image preprocessing for '$taskType' has unsupported color_space '$colorSpace'" }
                require(resizeMode in setOf("stretch", "center_crop", "similarity")) { "Image preprocessing for '$taskType' has unsupported resize_mode '$resizeMode'" }
                require(standardDeviation.all { it > 0f }) { "Image preprocessing for '$taskType' must use a non-zero std" }
            }
            return ImagePreprocessingContract(
                enabled = enabled,
                width = width,
                height = height,
                channels = channels,
                colorSpace = colorSpace,
                resizeMode = resizeMode,
                scale = value["scale"].floatValue(1f / 255f),
                mean = mean,
                standardDeviation = standardDeviation,
            )
        }
    }
}

internal data class TensorInputContract(
    val name: String,
    val source: String,
    val dataType: String,
    val layout: String,
    val shape: List<Int>,
    val payloadKey: String,
    val quantizationScale: Float,
    val quantizationZeroPoint: Int,
) {
    companion object {
        fun parse(raw: Map<String, Any>): TensorInputContract {
            val name = raw["name"].text()
            val source = raw["source"].text().lowercase()
            val dataType = raw["data_type"].text().lowercase()
            val layout = raw["layout"].text("sequence").lowercase()
            val shape = raw["shape"].intList()
            require(name.isNotBlank()) { "Input tensor name is required" }
            require(source in setOf("image", "text_ids", "attention_mask", "token_type_ids", "numeric")) { "Unsupported input source '$source'" }
            require(dataType in setOf("float32", "int64", "int32", "uint8")) { "Unsupported input data_type '$dataType'" }
            require(layout in setOf("nchw", "nhwc", "sequence", "vector")) { "Unsupported input layout '$layout'" }
            require(shape.isNotEmpty() && shape.all { it == -1 || it > 0 }) { "Input '$name' must define a shape using positive dimensions or -1" }
            return TensorInputContract(
                name = name,
                source = source,
                dataType = dataType,
                layout = layout,
                shape = shape,
                payloadKey = raw["payload_key"].text(),
                quantizationScale = raw["quantization_scale"].floatValue(0f),
                quantizationZeroPoint = raw["quantization_zero_point"].intValue(0),
            )
        }
    }
}

internal data class TensorOutputContract(
    val name: String,
    val index: Int,
    val dataType: String,
    val quantizationScale: Float,
    val quantizationZeroPoint: Int,
    val shape: List<Int> = emptyList(),
) {
    companion object {
        fun parse(raw: Map<String, Any>): TensorOutputContract {
            val name = raw["name"].text()
            require(name.isNotBlank()) { "Output tensor name is required" }
            return TensorOutputContract(
                name = name,
                index = raw["index"].intValue(-1),
                dataType = raw["data_type"].text("float32").lowercase(),
                quantizationScale = raw["quantization_scale"].floatValue(0f),
                quantizationZeroPoint = raw["quantization_zero_point"].intValue(0),
                shape = raw["shape"].intList(),
            )
        }
    }
}

internal data class OutputDecoderContract(
    val type: String,
    val outputName: String,
    val labels: List<String>,
    val scoreOutputName: String,
    val labelOutputName: String,
    val boxOutputName: String,
    val maxResults: Int,
    val endTokenId: Int?,
    val pooling: String = "",
    val clsIndex: Int? = null,
    val hiddenDimension: Int = 0,
    val normalization: String = "",
) {
    companion object {
        fun parse(raw: Map<String, Any>?, taskType: String): OutputDecoderContract {
            val value = raw ?: throw ModelInferenceContractException("Inference contract for '$taskType' must define output_decoder")
            val type = value["type"].text().lowercase()
            require(type in setOf("classification", "embedding", "tokens", "detection", "similarity", "reranking", "regression", "landmarks_2d", "landmarks_3d", "gender_age", "vision_features")) {
                "Unsupported output decoder '$type' for '$taskType'"
            }
            val labels = value["labels"].stringList()
            if (type == "classification" && labels.isEmpty()) {
                throw ModelInferenceContractException("Classification decoder for '$taskType' must define labels")
            }
            val pooling = value["pooling"].text().lowercase().ifBlank { value["pooling_type"].text().lowercase() }
            val clsIndex = value["cls_index"].intValueOrNull() ?: value["cls_token_index"].intValueOrNull()
            val hiddenDimension = value["hidden_dimension"].intValue(0).takeIf { it > 0 }
                ?: value["embedding_dimension"].intValue(0).takeIf { it > 0 }
                ?: 0
            return OutputDecoderContract(
                type = type,
                outputName = value["output_name"].text(),
                labels = labels,
                scoreOutputName = value["score_output_name"].text(),
                labelOutputName = value["label_output_name"].text(),
                boxOutputName = value["box_output_name"].text(),
                maxResults = value["max_results"].intValue(20).coerceIn(1, 500),
                endTokenId = value["end_token_id"].intValueOrNull(),
                pooling = pooling,
                clsIndex = clsIndex,
                hiddenDimension = hiddenDimension,
                normalization = value["normalization"].text().lowercase().ifBlank { value["normalize"].text().lowercase() },
            )
        }
    }
}

internal data class ConfidenceContract(
    val type: String,
    val threshold: Float,
) {
    companion object {
        fun parse(raw: Map<String, Any>?, taskType: String): ConfidenceContract {
            val value = raw ?: throw ModelInferenceContractException("Inference contract for '$taskType' must define confidence_scoring")
            val type = value["type"].text().lowercase()
            require(type in setOf("softmax", "sigmoid", "identity", "cosine")) {
                "Unsupported confidence scoring '$type' for '$taskType'"
            }
            return ConfidenceContract(
                type = type,
                threshold = value["threshold"].floatValue(0f).coerceIn(0f, 1f),
            )
        }
    }
}

internal class ModelInferenceContractException(message: String) : IllegalArgumentException(message)

private fun Any?.asStringMap(): Map<String, Any>? = when (this) {
    is Map<*, *> -> buildMap {
        this@asStringMap.forEach { (key, value) ->
            key?.toString()?.takeIf(String::isNotBlank)?.let { put(it, value ?: return@forEach) }
        }
    }
    else -> null
}

private fun Any?.asMapList(): List<Map<String, Any>> = when (this) {
    is List<*> -> mapNotNull { it.asStringMap() }
    else -> emptyList()
}

private fun Any?.text(defaultValue: String = ""): String = toString()?.trim()?.ifBlank { defaultValue } ?: defaultValue

private fun Any?.booleanValue(defaultValue: Boolean): Boolean = when (this) {
    is Boolean -> this
    is Number -> toInt() != 0
    else -> toString()?.trim()?.lowercase()?.let {
        when (it) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> defaultValue
        }
    } ?: defaultValue
}

private fun Any?.intValue(defaultValue: Int): Int = when (this) {
    is Number -> toInt()
    else -> toString()?.trim()?.toIntOrNull() ?: defaultValue
}

private fun Any?.intValueOrNull(): Int? = when (this) {
    is Number -> toInt()
    else -> toString()?.trim()?.toIntOrNull()
}

private fun Any?.floatValue(defaultValue: Float): Float = when (this) {
    is Number -> toFloat()
    else -> toString()?.trim()?.toFloatOrNull() ?: defaultValue
}

private fun Any?.stringList(): List<String> = when (this) {
    is List<*> -> mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
    is String -> split(',', '|').map(String::trim).filter(String::isNotBlank)
    else -> emptyList()
}

private fun Any?.floatList(): List<Float> = when (this) {
    is List<*> -> mapNotNull { (it as? Number)?.toFloat() ?: it?.toString()?.toFloatOrNull() }
    else -> emptyList()
}

private fun Any?.intList(): List<Int> = when (this) {
    is List<*> -> mapNotNull { (it as? Number)?.toInt() ?: it?.toString()?.toIntOrNull() }
    else -> emptyList()
}

internal data class PreparedInferenceTensor(
    val name: String,
    val dataType: String,
    val shape: LongArray,
    val floats: FloatArray = FloatArray(0),
    val longs: LongArray = LongArray(0),
    val ints: IntArray = IntArray(0),
    val bytes: ByteArray = ByteArray(0),
)

/** Converts declared application inputs into typed model tensors. */
internal class ModelInputPreprocessor(
    private val context: Context?,
) {
    fun prepare(
        contract: ModelInferenceContract,
        payload: Map<String, Any>,
    ): List<PreparedInferenceTensor> {
        val encodedText = contract.inputs.any { it.source in setOf("text_ids", "attention_mask", "token_type_ids") }
            .takeIf { it }
            ?.let { tokenizer ->
                val modelTokenizer = ModelTokenizer(materializeTokenizerContract(contract.tokenizer))
                if (contract.taskType == "text_reranking") {
                    modelTokenizer.encodePair(readPairValue(payload, "query"), readPairValue(payload, "document"), sequenceLength(contract))
                } else {
                    modelTokenizer.encode(readText(payload), sequenceLength(contract))
                }
            }

        return contract.inputs.map { input ->
            when (input.source) {
                "image" -> prepareImage(input, contract.imagePreprocessing, contract.taskType, payload)
                "text_ids" -> prepareTokenIds(input, encodedText ?: error("Tokenizer input is missing"))
                "attention_mask" -> prepareMask(input, encodedText ?: error("Tokenizer input is missing"))
                "token_type_ids" -> prepareTokenTypes(input, encodedText ?: error("Tokenizer input is missing"))
                "numeric" -> prepareNumeric(input, payload)
                else -> throw ModelInferenceContractException("Unsupported input source '${input.source}'")
            }
        }
    }

    private fun prepareImage(
        input: TensorInputContract,
        preprocessing: ImagePreprocessingContract,
        taskType: String,
        payload: Map<String, Any>,
    ): PreparedInferenceTensor {
        require(preprocessing.enabled) { "Image preprocessing is disabled for input '${input.name}'" }
        if (taskType == "face_detection") {
            return prepareScrfdImage(input, preprocessing, payload)
        }
        if (taskType == "face_embedding") {
            return prepareArcFaceImage(input, preprocessing, payload)
        }
        if (taskType == "landmark_2d" || taskType == "landmark_3d" || taskType == "gender_age") {
            return prepareLandmarkImage(input, preprocessing, payload, if (taskType == "gender_age") 96 else 192)
        }
        val bitmap = decodeBitmap(payload)
        val scaled = resize(bitmap, preprocessing)
        val values = imageValues(scaled, preprocessing, input.layout)
        val shape = when (input.layout) {
            "nchw" -> longArrayOf(1L, preprocessing.channels.toLong(), preprocessing.height.toLong(), preprocessing.width.toLong())
            "nhwc" -> longArrayOf(1L, preprocessing.height.toLong(), preprocessing.width.toLong(), preprocessing.channels.toLong())
            else -> throw ModelInferenceContractException("Image input '${input.name}' requires nchw or nhwc layout")
        }
        validateShape(input, shape)
        return when (input.dataType) {
            "float32" -> PreparedInferenceTensor(input.name, input.dataType, shape, floats = values)
            "uint8" -> PreparedInferenceTensor(
                input.name,
                input.dataType,
                shape,
                bytes = quantize(values, input.quantizationScale, input.quantizationZeroPoint),
            )
            else -> throw ModelInferenceContractException("Image input '${input.name}' must use float32 or uint8")
        }
    }

    private fun prepareScrfdImage(
        input: TensorInputContract,
        preprocessing: ImagePreprocessingContract,
        payload: Map<String, Any>,
    ): PreparedInferenceTensor {
        require(input.layout == "nchw") { "SCRFD input '${input.name}' must use NCHW layout" }
        val preprocessed = ScrfdDetector().preprocess(decodeBitmap(payload), preprocessing.width)
        val values = FloatArray(preprocessed.width * preprocessed.height * 3)
        val planeSize = preprocessed.width * preprocessed.height
        for (pixel in 0 until planeSize) {
            values[pixel] = preprocessed.tensor[pixel * 3]
            values[planeSize + pixel] = preprocessed.tensor[pixel * 3 + 1]
            values[planeSize * 2 + pixel] = preprocessed.tensor[pixel * 3 + 2]
        }
        val shape = longArrayOf(1L, 3L, preprocessed.height.toLong(), preprocessed.width.toLong())
        validateShape(input, shape)
        return PreparedInferenceTensor(input.name, input.dataType, shape, floats = values)
    }

    private fun prepareArcFaceImage(
        input: TensorInputContract,
        preprocessing: ImagePreprocessingContract,
        payload: Map<String, Any>,
    ): PreparedInferenceTensor {
        require(input.name == "input.1") { "ArcFace input must be input.1" }
        require(input.layout == "nchw" && input.dataType == "float32") { "ArcFace input must be float32 NCHW" }
        val rawPoints = payload["face_keypoints"] ?: payload["kps"]
        val points = (rawPoints as? List<*>)?.mapNotNull { value ->
            when (value) {
                is List<*> -> if (value.size >= 2) FacePoint(value[0].toString().toFloat(), value[1].toString().toFloat()) else null
                is FloatArray -> if (value.size >= 2) FacePoint(value[0], value[1]) else null
                else -> null
            }
        }.orEmpty()
        require(points.size == 5) { "ArcFace face_embedding requires five detector keypoints" }
        val aligned = ArcFaceAlignment.warp(decodeBitmap(payload), points)
        val pixels = IntArray(ArcFaceAlignment.OUTPUT_SIZE * ArcFaceAlignment.OUTPUT_SIZE)
        aligned.getPixels(pixels, 0, ArcFaceAlignment.OUTPUT_SIZE, 0, 0, ArcFaceAlignment.OUTPUT_SIZE, ArcFaceAlignment.OUTPUT_SIZE)
        val planeSize = ArcFaceAlignment.OUTPUT_SIZE * ArcFaceAlignment.OUTPUT_SIZE
        val values = FloatArray(planeSize * 3)
        pixels.forEachIndexed { index, pixel ->
            val red = ((pixel shr 16) and 0xff).toFloat()
            val green = ((pixel shr 8) and 0xff).toFloat()
            val blue = (pixel and 0xff).toFloat()
            values[index] = normalizeArcFaceChannel(blue, preprocessing.mean.single(), preprocessing.standardDeviation.single())
            values[planeSize + index] = normalizeArcFaceChannel(green, preprocessing.mean.single(), preprocessing.standardDeviation.single())
            values[planeSize * 2 + index] = normalizeArcFaceChannel(red, preprocessing.mean.single(), preprocessing.standardDeviation.single())
        }
        val shape = longArrayOf(1L, 3L, 112L, 112L)
        validateShape(input, shape)
        return PreparedInferenceTensor(input.name, input.dataType, shape, floats = values)
    }

    private fun prepareLandmarkImage(
        input: TensorInputContract,
        preprocessing: ImagePreprocessingContract,
        payload: Map<String, Any>,
        outputSize: Int,
    ): PreparedInferenceTensor {
        require(input.name == "data" && input.layout == "nchw" && input.dataType == "float32") {
            "2D landmark input must be float32 NCHW named data"
        }
        val rawBox = payload["face_bbox"] ?: payload["bbox"]
        val box = when (rawBox) {
            is List<*> -> FaceBoundingBox(rawBox[0].toString().toFloat(), rawBox[1].toString().toFloat(), rawBox[2].toString().toFloat(), rawBox[3].toString().toFloat())
            else -> throw ModelInferenceContractException("2D landmarks require a detected face bbox")
        }
        val transform = InsightFaceLandmarkAlignment.fromBoundingBox(box, outputSize)
        val aligned = InsightFaceLandmarkAlignment.warp(decodeBitmap(payload), transform, outputSize)
        val pixels = IntArray(outputSize * outputSize)
        aligned.getPixels(pixels, 0, outputSize, 0, 0, outputSize, outputSize)
        val planeSize = outputSize * outputSize
        val values = FloatArray(planeSize * 3)
        pixels.forEachIndexed { index, pixel ->
            val red = ((pixel shr 16) and 0xff).toFloat()
            val green = ((pixel shr 8) and 0xff).toFloat()
            val blue = (pixel and 0xff).toFloat()
            values[index] = normalizeLandmarkChannel(blue, preprocessing.mean.single(), preprocessing.standardDeviation.single())
            values[planeSize + index] = normalizeLandmarkChannel(green, preprocessing.mean.single(), preprocessing.standardDeviation.single())
            values[planeSize * 2 + index] = normalizeLandmarkChannel(red, preprocessing.mean.single(), preprocessing.standardDeviation.single())
        }
        val shape = longArrayOf(1L, 3L, outputSize.toLong(), outputSize.toLong())
        validateShape(input, shape)
        return PreparedInferenceTensor(input.name, input.dataType, shape, floats = values)
    }

    private fun prepareTokenIds(input: TensorInputContract, tokens: TokenizedText): PreparedInferenceTensor {
        val shape = sequenceShape(input, tokens.ids.size)
        return when (input.dataType) {
            "int64" -> PreparedInferenceTensor(input.name, input.dataType, shape, longs = tokens.ids.map(Int::toLong).toLongArray())
            "int32" -> PreparedInferenceTensor(input.name, input.dataType, shape, ints = tokens.ids.toIntArray())
            else -> throw ModelInferenceContractException("Token input '${input.name}' must use int64 or int32")
        }
    }

    private fun prepareMask(input: TensorInputContract, tokens: TokenizedText): PreparedInferenceTensor {
        val shape = sequenceShape(input, tokens.mask.size)
        return when (input.dataType) {
            "int64" -> PreparedInferenceTensor(input.name, input.dataType, shape, longs = tokens.mask.map(Int::toLong).toLongArray())
            "int32" -> PreparedInferenceTensor(input.name, input.dataType, shape, ints = tokens.mask.toIntArray())
            "float32" -> PreparedInferenceTensor(input.name, input.dataType, shape, floats = tokens.mask.map(Int::toFloat).toFloatArray())
            else -> throw ModelInferenceContractException("Attention mask '${input.name}' must use int64, int32, or float32")
        }
    }

    private fun prepareTokenTypes(input: TensorInputContract, tokens: TokenizedText): PreparedInferenceTensor {
        val shape = sequenceShape(input, tokens.typeIds.size)
        return when (input.dataType) {
            "int64" -> PreparedInferenceTensor(input.name, input.dataType, shape, longs = tokens.typeIds.map(Int::toLong).toLongArray())
            "int32" -> PreparedInferenceTensor(input.name, input.dataType, shape, ints = tokens.typeIds.toIntArray())
            else -> throw ModelInferenceContractException("Token type input '${input.name}' must use int64 or int32")
        }
    }

    private fun prepareNumeric(input: TensorInputContract, payload: Map<String, Any>): PreparedInferenceTensor {
        val key = input.payloadKey.ifBlank { input.name }
        val values = payload[key].floatList()
        require(values.isNotEmpty()) { "Numeric input '$key' is required for '${input.name}'" }
        val shape = resolvedConfiguredShape(input, values.size)
        require(elementCount(shape) == values.size) { "Numeric input '${input.name}' has ${values.size} values but requires ${elementCount(shape)}" }
        return when (input.dataType) {
            "float32" -> PreparedInferenceTensor(input.name, input.dataType, shape, floats = values.toFloatArray())
            "int64" -> PreparedInferenceTensor(input.name, input.dataType, shape, longs = values.map(Float::toLong).toLongArray())
            "int32" -> PreparedInferenceTensor(input.name, input.dataType, shape, ints = values.map(Float::toInt).toIntArray())
            "uint8" -> PreparedInferenceTensor(input.name, input.dataType, shape, bytes = quantize(values.toFloatArray(), input.quantizationScale, input.quantizationZeroPoint))
            else -> throw ModelInferenceContractException("Unsupported numeric type '${input.dataType}'")
        }
    }

    private fun decodeBitmap(payload: Map<String, Any>): Bitmap {
        val rawUri = listOf("image_uri", "uri", "source_path", "image_path", "file_path", "path")
            .asSequence()
            .mapNotNull { payload[it]?.toString()?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?: throw ModelInferenceContractException("Image model requires image_uri, uri, source_path, image_path, file_path, or path")
        val stream = openImageStream(rawUri)
        return stream.use { input ->
            BitmapFactory.decodeStream(input)
                ?: throw ModelInferenceContractException("Unable to decode image at '$rawUri'")
        }
    }

    private fun openImageStream(value: String): InputStream {
        val uri = Uri.parse(value)
        if (uri.scheme.equals("content", ignoreCase = true)) {
            return context?.contentResolver?.openInputStream(uri)
                ?: throw ModelInferenceContractException("Content URI image input requires an Android context: '$value'")
        }
        val file = if (uri.scheme.equals("file", ignoreCase = true)) File(uri.path.orEmpty()) else File(value)
        if (!file.isFile) {
            throw ModelInferenceContractException("Image input does not exist: '$value'")
        }
        return FileInputStream(file)
    }

    private fun resize(bitmap: Bitmap, preprocessing: ImagePreprocessingContract): Bitmap {
        if (bitmap.width == preprocessing.width && bitmap.height == preprocessing.height) {
            return bitmap
        }
        if (preprocessing.resizeMode == "stretch") {
            return Bitmap.createScaledBitmap(bitmap, preprocessing.width, preprocessing.height, true)
        }
        val sourceRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val targetRatio = preprocessing.width.toFloat() / preprocessing.height.toFloat()
        val crop = if (sourceRatio > targetRatio) {
            val width = (bitmap.height * targetRatio).roundToInt().coerceAtMost(bitmap.width)
            Bitmap.createBitmap(bitmap, (bitmap.width - width) / 2, 0, width, bitmap.height)
        } else {
            val height = (bitmap.width / targetRatio).roundToInt().coerceAtMost(bitmap.height)
            Bitmap.createBitmap(bitmap, 0, (bitmap.height - height) / 2, bitmap.width, height)
        }
        return Bitmap.createScaledBitmap(crop, preprocessing.width, preprocessing.height, true)
    }

    private fun imageValues(bitmap: Bitmap, preprocessing: ImagePreprocessingContract, layout: String): FloatArray {
        val width = preprocessing.width
        val height = preprocessing.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val values = FloatArray(width * height * preprocessing.channels)
        fun normalized(channel: Int, raw: Int): Float {
            val scaled = raw.toFloat() * preprocessing.scale
            val mean = preprocessing.mean[channel % preprocessing.mean.size]
            val std = preprocessing.standardDeviation[channel % preprocessing.standardDeviation.size]
            return (scaled - mean) / std
        }
        pixels.forEachIndexed { pixelIndex, pixel ->
            val red = (pixel shr 16) and 0xff
            val green = (pixel shr 8) and 0xff
            val blue = pixel and 0xff
            val alpha = (pixel ushr 24) and 0xff
            val channels = when (preprocessing.colorSpace) {
                "grayscale" -> intArrayOf((0.299f * red + 0.587f * green + 0.114f * blue).roundToInt())
                "rgba" -> intArrayOf(red, green, blue, alpha)
                else -> intArrayOf(red, green, blue)
            }
            for (channel in 0 until preprocessing.channels) {
                val value = normalized(channel, channels[channel % channels.size])
                val destination = if (layout == "nchw") {
                    channel * width * height + pixelIndex
                } else {
                    pixelIndex * preprocessing.channels + channel
                }
                values[destination] = value
            }
        }
        return values
    }

    private fun sequenceLength(contract: ModelInferenceContract): Int {
        return contract.inputs.firstOrNull { it.source in setOf("text_ids", "attention_mask", "token_type_ids") }
            ?.shape
            ?.lastOrNull()
            ?.takeIf { it > 0 }
            ?: contract.tokenizer.maxLength.takeIf { it > 0 }
            ?: throw ModelInferenceContractException("Tokenizer contract for '${contract.taskType}' must define max_length or a fixed sequence input shape")
    }

    private fun sequenceShape(input: TensorInputContract, length: Int): LongArray {
        val shape = resolvedConfiguredShape(input, length)
        require(elementCount(shape) == length) { "Sequence input '${input.name}' must contain exactly $length elements" }
        return shape
    }

    private fun resolvedConfiguredShape(input: TensorInputContract, dynamicSize: Int): LongArray {
        val dynamicDimensions = input.shape.count { it == -1 }
        require(dynamicDimensions <= 1) { "Input '${input.name}' may define at most one dynamic dimension" }
        return input.shape.map { dimension -> if (dimension == -1) dynamicSize else dimension }.map(Int::toLong).toLongArray()
    }

    private fun validateShape(input: TensorInputContract, actual: LongArray) {
        require(input.shape.size == actual.size) { "Image input '${input.name}' shape rank does not match preprocessing" }
        input.shape.zip(actual.toList()).forEach { (configured, observed) ->
            require(configured == -1 || configured.toLong() == observed) { "Image input '${input.name}' shape is incompatible with image preprocessing" }
        }
    }

    private fun elementCount(shape: LongArray): Int = shape.fold(1L) { total, dimension -> total * dimension }
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

    private fun quantize(values: FloatArray, scale: Float, zeroPoint: Int): ByteArray {
        require(scale > 0f) { "uint8 tensor conversion requires quantization_scale" }
        return ByteArray(values.size) { index ->
            ((values[index] / scale).roundToInt() + zeroPoint).coerceIn(0, 255).toByte()
        }
    }

    private fun readText(payload: Map<String, Any>): String {
        val direct = listOf("text", "prompt", "query", "caption", "ocr_text", "knowledge_pack_content")
            .asSequence()
            .mapNotNull { payload[it]?.toString()?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull()
        val explicitPrefix = payload["prefix"]?.toString()?.trim()
        val body = direct ?: throw ModelInferenceContractException("Text model requires text, prompt, query, caption, ocr_text, or knowledge_pack_content")
        return if (explicitPrefix.isNullOrBlank()) body else {
            val prefix = explicitPrefix.trim()
            if (prefix.equals("query", ignoreCase = true)) "query: $body"
            else if (prefix.equals("document", ignoreCase = true)) "document: $body"
            else if (prefix.equals("search_document", ignoreCase = true)) "search_document: $body"
            else "$prefix $body"
        }
    }

    private fun readPairValue(payload: Map<String, Any>, key: String): String =
        payload[key]?.toString()?.trim()?.takeIf(String::isNotBlank)
            ?: throw ModelInferenceContractException("Text reranking requires '$key'")
}

data class TokenizedText(
    val ids: List<Int>,
    val mask: List<Int>,
    val typeIds: List<Int>,
)

private val TOKENIZER_FILE_CACHE = ConcurrentHashMap<String, TokenizerContract>()

internal fun materializeTokenizerContract(contract: TokenizerContract): TokenizerContract {
    if (contract.type == "none" || contract.vocabulary.isNotEmpty()) return contract
    val sourcePath = contract.sourceFile.trim()
    require(sourcePath.isNotBlank()) { "Tokenizer '${contract.type}' has no inline vocabulary or source_file" }
    return TOKENIZER_FILE_CACHE.getOrPut(sourcePath) {
        val source = File(sourcePath)
        require(source.isFile) { "Tokenizer source file does not exist: $sourcePath" }
        val text = source.readText()
        val vocabStart = findTokenizerVocabValueStart(text)
        require(vocabStart >= 0) { "Tokenizer source file is missing model.vocab: $sourcePath" }
        when (contract.sourceFormat.ifBlank { contract.type }.lowercase()) {
            "hf_wordpiece_json", "wordpiece" -> {
                require(text[vocabStart] == '{') { "WordPiece tokenizer model.vocab must be a JSON object" }
                val end = findMatchingJsonDelimiter(text, vocabStart, '{', '}')
                val entries = Regex("\"((?:\\\\.|[^\"\\\\])*)\"\\s*:\\s*(\\d+)")
                    .findAll(text.substring(vocabStart + 1, end))
                    .map { match -> decodeJsonStringBody(match.groupValues[1]) to match.groupValues[2].toInt() }
                    .toList()
                require(entries.isNotEmpty()) { "WordPiece tokenizer vocabulary is empty" }
                val maxId = entries.maxOf { it.second }
                val vocabulary = MutableList(maxId + 1) { contract.unknownToken }
                entries.forEach { (token, id) -> if (id in vocabulary.indices) vocabulary[id] = token }
                contract.copy(vocabulary = vocabulary)
            }
            "hf_unigram_json", "unigram" -> {
                require(text[vocabStart] == '[') { "Unigram tokenizer model.vocab must be a JSON array" }
                val end = findMatchingJsonDelimiter(text, vocabStart, '[', ']')
                val pairPattern = Regex(
                    "\\[\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*,\\s*" +
                        "(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*\\]",
                )
                val pairs = pairPattern.findAll(text.substring(vocabStart + 1, end))
                    .map { match -> decodeJsonStringBody(match.groupValues[1]) to match.groupValues[2].toFloat() }
                    .toList()
                require(pairs.isNotEmpty()) { "Unigram tokenizer vocabulary is empty" }
                contract.copy(
                    vocabulary = pairs.map { it.first },
                    vocabularyScores = pairs.map { it.second },
                )
            }
            else -> throw ModelInferenceContractException(
                "Unsupported tokenizer source_format '${contract.sourceFormat}' for '${contract.type}'",
            )
        }
    }
}

private fun findTokenizerVocabValueStart(text: String): Int {
    val modelIndex = text.indexOf("\"model\"")
    if (modelIndex < 0) return -1
    val vocabIndex = text.indexOf("\"vocab\"", startIndex = modelIndex)
    if (vocabIndex < 0) return -1
    val colon = text.indexOf(':', startIndex = vocabIndex + 7)
    if (colon < 0) return -1
    var cursor = colon + 1
    while (cursor < text.length && text[cursor].isWhitespace()) cursor += 1
    return cursor.takeIf { it < text.length } ?: -1
}

private fun findMatchingJsonDelimiter(text: String, start: Int, open: Char, close: Char): Int {
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until text.length) {
        val ch = text[index]
        if (inString) {
            if (escaped) {
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == '"') {
                inString = false
            }
            continue
        }
        if (ch == '"') {
            inString = true
            continue
        }
        if (ch == open) depth += 1
        if (ch == close) {
            depth -= 1
            if (depth == 0) return index
        }
    }
    throw ModelInferenceContractException("Tokenizer JSON contains an unterminated vocabulary value")
}

private fun decodeJsonStringBody(body: String): String {
    if ('\\' !in body) return body
    val out = StringBuilder(body.length)
    var index = 0
    while (index < body.length) {
        val ch = body[index++]
        if (ch != '\\' || index >= body.length) {
            out.append(ch)
            continue
        }
        when (val escaped = body[index++]) {
            '"', '\\', '/' -> out.append(escaped)
            'b' -> out.append('\b')
            'f' -> out.append('\u000C')
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            't' -> out.append('\t')
            'u' -> {
                require(index + 4 <= body.length) { "Invalid JSON unicode escape in tokenizer vocabulary" }
                out.append(body.substring(index, index + 4).toInt(16).toChar())
                index += 4
            }
            else -> out.append(escaped)
        }
    }
    return out.toString()
}

internal class ModelTokenizer(
    private val contract: TokenizerContract,
) {
    private val tokenIds = contract.vocabulary.withIndex().associate { it.value to it.index }
    val startTokenId: Int get() = tokenIds[contract.startToken] ?: 101
    val endTokenId: Int get() = tokenIds[contract.endToken] ?: 102
    val padTokenId: Int get() = tokenIds[contract.padToken] ?: 0
    val unknownTokenId: Int get() = tokenIds[contract.unknownToken] ?: 100
    val maskTokenId: Int get() = tokenIds["[MASK]"] ?: 103
    private val unigramEntries = contract.vocabulary.mapIndexedNotNull { index, token ->
        contract.vocabularyScores.getOrNull(index)?.let { score -> token to (index to score) }
    }
    private val unigramByPrefix by lazy { unigramEntries.groupBy { it.first.firstOrNull() } }

    fun encode(text: String, length: Int): TokenizedText {
        require(contract.type != "none") { "Tokenizer is disabled" }
        if (contract.type == "bpe") return pad(encodeBpe(text), length)
        if (contract.type == "unigram") return pad(unigramEncode(text), length)
        val normalized = bertNormalize(text)
        val values = mutableListOf<Int>()
        values += (tokenIds[contract.startToken] ?: 101)
        when (contract.type) {
            "wordpiece" -> values += tokenizeWordPiece(normalized)
            "character" -> normalized.toCharArray().map { character -> tokenIds[character.toString()] ?: unknownId() }.forEach(values::add)
        }
        values += (tokenIds[contract.endToken] ?: 102)
        val trimmed = values.take(length)
        val padId = tokenIds[contract.padToken] ?: 0
        val ids = trimmed + List((length - trimmed.size).coerceAtLeast(0)) { padId }
        val mask = List(trimmed.size) { 1 } + List((length - trimmed.size).coerceAtLeast(0)) { 0 }
        val typeIds = List(trimmed.size) { 0 } + List((length - trimmed.size).coerceAtLeast(0)) { 0 }
        return TokenizedText(ids, mask, typeIds)
    }

    fun encodeWithoutPadding(text: String): TokenizedText {
        require(contract.type == "bpe") { "Unpadded encoding is only supported for BPE" }
        val ids = encodeBpe(text)
        return TokenizedText(ids, List(ids.size) { 1 }, List(ids.size) { 0 })
    }

    private fun encodeBpe(text: String): List<Int> {
        val ranks = contract.bpeMerges.withIndex().associate { it.value to it.index }
        val byteEncoder = byteLevelEncoder()
        val pattern = Regex("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\s)|\\s+")
        return pattern.findAll(text).flatMap { match ->
            val symbols = match.value.toByteArray(Charsets.UTF_8).map { byteEncoder[it.toInt() and 0xff].toString() }.toMutableList()
            while (symbols.size > 1) {
                val best = symbols.zipWithNext()
                    .mapNotNull { pair -> ranks["${pair.first} ${pair.second}"]?.let { pair to it } }
                    .minByOrNull { it.second } ?: break
                val merged = best.first.first + best.first.second
                var index = 0
                while (index < symbols.size - 1) {
                    if (symbols[index] == best.first.first && symbols[index + 1] == best.first.second) {
                        symbols[index] = merged
                        symbols.removeAt(index + 1)
                    } else index += 1
                }
            }
            symbols.map { token -> tokenIds[token] ?: unknownId() }.asSequence()
        }.toList()
    }

    private fun byteLevelEncoder(): List<Char> {
        val bytes = mutableListOf<Int>()
        bytes += (33..126)
        bytes += (161..172)
        bytes += (174..255)
        val chars = bytes.map(Int::toChar).toMutableList()
        var next = 0
        for (value in 0..255) {
            if (value !in bytes) {
                bytes += value
                chars += (256 + next++).toChar()
            }
        }
        return List(256) { value -> chars[bytes.indexOf(value)] }
    }

    fun encodePair(first: String, second: String, length: Int): TokenizedText {
        require(contract.type == "unigram" && contract.pairTemplate == "xlm_roberta") {
            "Pair encoding requires the package's XLM-R Unigram tokenizer template"
        }
        val firstIds = unigramEncode(first)
        val secondIds = unigramEncode(second)
        val startId = tokenIds[contract.startToken] ?: 0
        val endId = tokenIds[contract.endToken] ?: 2
        val available = (length - 4).coerceAtLeast(0)
        var firstLimit = firstIds.size
        var secondLimit = secondIds.size
        while (firstLimit + secondLimit > available) {
            if (firstLimit >= secondLimit && firstLimit > 0) firstLimit -= 1 else if (secondLimit > 0) secondLimit -= 1 else break
        }
        return pad(listOf(startId) + firstIds.take(firstLimit) + listOf(endId, endId) + secondIds.take(secondLimit) + listOf(endId), length)
    }

    private fun pad(values: List<Int>, length: Int): TokenizedText {
        require(length > 0) { "Tokenizer sequence length must be positive" }
        val trimmed = values.take(length)
        val padId = tokenIds[contract.padToken] ?: 1
        val padding = (length - trimmed.size).coerceAtLeast(0)
        return TokenizedText(
            ids = trimmed + List(padding) { padId },
            mask = List(trimmed.size) { 1 } + List(padding) { 0 },
            typeIds = List(length) { 0 },
        )
    }

    private fun unigramEncode(text: String): List<Int> {
        val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
            .trim()
            .replace(Regex("\\s+"), "▁")
        val input = if (normalized.startsWith("▁")) normalized else "▁$normalized"
        if (input.isEmpty()) return emptyList()
        val bestScore = MutableList(input.length + 1) { Float.NEGATIVE_INFINITY }
        val bestTokens = MutableList<List<Int>?>(input.length + 1) { null }
        bestScore[0] = 0f
        for (start in input.indices) {
            if (!bestScore[start].isFinite()) continue
            unigramByPrefix[input[start]].orEmpty().forEach { (token, indexedScore) ->
                if (!input.startsWith(token, start)) return@forEach
                val end = start + token.length
                val candidateScore = bestScore[start] + indexedScore.second
                if (candidateScore > bestScore[end]) {
                    bestScore[end] = candidateScore
                    bestTokens[end] = (bestTokens[start].orEmpty() + indexedScore.first)
                }
            }
            if (bestTokens[start + 1] == null && bestScore[start + 1].isFinite()) continue
        }
        return bestTokens[input.length] ?: listOf(tokenIds[contract.unknownToken] ?: 3)
    }

    private fun bertNormalize(text: String): String {
        var cleaned = text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\\s+".toRegex(), " ")
            .trim()
        if (contract.normalizer.equals("lowercase", ignoreCase = true)) {
            cleaned = cleaned.lowercase()
        }
        return cleaned
    }

    private fun tokenizeWordPiece(text: String): List<Int> {
        val tokens = mutableListOf<Int>()
        // BERT BasicTokenizer semantics: keep alphanumeric runs together
        // but split punctuation/symbols one code point at a time before the
        // greedy WordPiece pass.
        val regex = Regex("[\\p{L}\\p{N}]+|[^\\s\\p{L}\\p{N}]")
        regex.findAll(text).forEach { match ->
            val word = match.value
            if (word in tokenIds) {
                tokens += tokenIds.getValue(word)
                return@forEach
            }

            val pieces = mutableListOf<Int>()
            var start = 0
            var failed = false
            while (start < word.length) {
                var end = word.length
                var candidateId: Int? = null
                var candidateEnd = start
                while (end > start) {
                    val part = word.substring(start, end)
                    val token = if (start == 0) part else "##$part"
                    tokenIds[token]?.let {
                        candidateId = it
                        candidateEnd = end
                    }
                    if (candidateId != null) break
                    end -= 1
                }
                if (candidateId == null) {
                    failed = true
                    break
                }
                pieces += candidateId
                start = candidateEnd
            }

            if (failed || pieces.isEmpty()) {
                tokens += unknownId()
            } else {
                tokens += pieces
            }
        }
        return tokens
    }

    private fun unknownId(): Int = tokenIds[contract.unknownToken]
        ?: throw ModelInferenceContractException("Tokenizer vocabulary is missing unknown_token '${contract.unknownToken}'")
}