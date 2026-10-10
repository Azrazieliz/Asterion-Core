package com.ailm.android.runtime.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class PaddleOcrBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val score: Float,
)

internal data class PaddleOcrDecodedText(
    val text: String,
    val confidence: Float,
)

internal object PaddleOcrCtcDecoder {
    fun decode(
        logits: List<Float>,
        classCount: Int,
        dictionary: List<String>,
        blankIndex: Int = 0,
        appendSpaceChar: Boolean = true,
    ): PaddleOcrDecodedText {
        require(classCount > 1) { "PaddleOCR class count must be greater than one" }
        require(logits.isNotEmpty() && logits.size % classCount == 0) {
            "PaddleOCR recognizer output size ${logits.size} is incompatible with class count $classCount"
        }

        val characters = buildList {
            addAll(dictionary)
            if (appendSpaceChar) add(" ")
        }
        require(characters.size + 1 == classCount) {
            "PaddleOCR dictionary/CTC mapping expects ${classCount - 1} non-blank classes but found ${characters.size}"
        }

        val steps = logits.size / classCount
        val text = StringBuilder()
        val confidences = mutableListOf<Float>()
        var previous = -1
        for (step in 0 until steps) {
            val offset = step * classCount
            var bestIndex = 0
            var bestValue = logits[offset]
            for (index in 1 until classCount) {
                val value = logits[offset + index]
                if (value > bestValue) {
                    bestValue = value
                    bestIndex = index
                }
            }
            if (bestIndex != blankIndex && bestIndex != previous) {
                val charIndex = bestIndex - 1
                if (charIndex in characters.indices) {
                    text.append(characters[charIndex])
                    confidences += bestValue
                }
            }
            previous = bestIndex
        }
        return PaddleOcrDecodedText(
            text = text.toString(),
            confidence = if (confidences.isEmpty()) 0f else confidences.average().toFloat(),
        )
    }
}

internal object PaddleOcrDbPostProcessor {
    fun boxes(
        probabilities: List<Float>,
        width: Int,
        height: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        threshold: Float = 0.3f,
        boxThreshold: Float = 0.6f,
        unclipRatio: Float = 1.5f,
        maxCandidates: Int = 1000,
    ): List<PaddleOcrBox> {
        require(width > 0 && height > 0)
        require(probabilities.size == width * height) {
            "PaddleOCR detector output size ${probabilities.size} does not match $width x $height"
        }
        val foreground = BooleanArray(probabilities.size) { probabilities[it] > threshold }
        val visited = BooleanArray(probabilities.size)
        val queue = IntArray(probabilities.size)
        val boxes = mutableListOf<PaddleOcrBox>()

        for (start in foreground.indices) {
            if (!foreground[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var minX = start % width
            var maxX = minX
            var minY = start / width
            var maxY = minY

            while (head < tail) {
                val current = queue[head++]
                val x = current % width
                val y = current / width
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)

                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until width || ny !in 0 until height) continue
                        val next = ny * width + nx
                        if (foreground[next] && !visited[next]) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
            }

            val boxWidth = maxX - minX + 1
            val boxHeight = maxY - minY + 1
            if (min(boxWidth, boxHeight) < 3) continue

            var scoreSum = 0.0
            var scoreCount = 0
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    scoreSum += probabilities[y * width + x]
                    scoreCount += 1
                }
            }
            val score = if (scoreCount == 0) 0f else (scoreSum / scoreCount).toFloat()
            if (score < boxThreshold) continue

            val area = boxWidth.toFloat() * boxHeight.toFloat()
            val perimeter = 2f * (boxWidth + boxHeight).coerceAtLeast(1)
            val expansion = area * unclipRatio / perimeter
            // Binary-map coordinates identify pixel cells, not zero-area sample points.
            // The far edge of the component is therefore max + 1; omitting that
            // edge shrinks small text regions enough to discard valid boxes after
            // DB-style unclipping.
            val expandedLeft = (minX - expansion).coerceAtLeast(0f)
            val expandedTop = (minY - expansion).coerceAtLeast(0f)
            val expandedRight = (maxX + 1f + expansion).coerceAtMost(width.toFloat())
            val expandedBottom = (maxY + 1f + expansion).coerceAtMost(height.toFloat())
            if (min(expandedRight - expandedLeft, expandedBottom - expandedTop) < 5f) continue

            val left = (expandedLeft / width * sourceWidth).roundToInt().coerceIn(0, sourceWidth - 1)
            val top = (expandedTop / height * sourceHeight).roundToInt().coerceIn(0, sourceHeight - 1)
            val right = (expandedRight / width * sourceWidth).roundToInt().coerceIn(left + 1, sourceWidth)
            val bottom = (expandedBottom / height * sourceHeight).roundToInt().coerceIn(top + 1, sourceHeight)
            boxes += PaddleOcrBox(left, top, right, bottom, score)
            if (boxes.size >= maxCandidates) break
        }

        val rowTolerance = max(8, sourceHeight / 100)
        return boxes.sortedWith(
            compareBy<PaddleOcrBox> { it.top / rowTolerance }
                .thenBy { it.left }
                .thenBy { it.top },
        )
    }
}

internal class PaddleOcrRuntime(
    private val context: Context,
) {
    suspend fun execute(
        model: AiModelDescriptor,
        request: AiExecutionRequest,
        reporter: AiProgressReporter,
        runtimeId: String,
    ): AiExecutionResult {
        val metadata = model.metadata["paddle_ocr"] as? Map<*, *>
            ?: return incompatible("PaddleOCR metadata.paddle_ocr is missing")

        val detectorPath = metadata["detector_path"]?.toString().orEmpty()
        val recognizerPath = metadata["recognizer_path"]?.toString().orEmpty()
        val dictionaryPath = metadata["dictionary_path"]?.toString().orEmpty()
        if (!File(detectorPath).isFile || !File(recognizerPath).isFile || !File(dictionaryPath).isFile) {
            return incompatible("PaddleOCR detector, recognizer, or dictionary artifact is missing")
        }

        return try {
            reporter.report(0.05, "Decoding image for PaddleOCR")
            val source = decodeBitmap(request.payload)
            val detectorInput = prepareDetector(source)
            reporter.report(0.20, "Running PP-OCRv5 text detector")
            val detectorModel = model.copy(installPath = detectorPath)
            val detectorOutputs = OnnxRuntimeClient.runDetailed(detectorModel, listOf(detectorInput.tensor))
            val detectorOutput = detectorOutputs["fetch_name_0"]
                ?: return incompatible("PaddleOCR detector output fetch_name_0 is missing")
            val probabilityMap = detectorOutput.values
            val detectorShape = detectorOutput.shape
            val outputHeight = detectorShape.getOrNull(detectorShape.size - 2)
                ?.takeIf { it > 0 }
                ?.toInt()
                ?: detectorInput.height
            val outputWidth = detectorShape.lastOrNull()
                ?.takeIf { it > 0 }
                ?.toInt()
                ?: detectorInput.width

            val boxes = PaddleOcrDbPostProcessor.boxes(
                probabilities = probabilityMap,
                width = outputWidth,
                height = outputHeight,
                sourceWidth = source.width,
                sourceHeight = source.height,
            )
            reporter.report(0.45, "Detected ${boxes.size} PaddleOCR text regions")

            val dictionary = File(dictionaryPath).readLines()
            require(dictionary.size == 18383) {
                "PaddleOCR dictionary must contain 18383 entries; found ${dictionary.size}"
            }
            val recognizerModel = model.copy(installPath = recognizerPath)
            val lines = mutableListOf<Map<String, Any>>()

            boxes.forEachIndexed { index, box ->
                val crop = Bitmap.createBitmap(
                    source,
                    box.left,
                    box.top,
                    (box.right - box.left).coerceAtLeast(1),
                    (box.bottom - box.top).coerceAtLeast(1),
                )
                val recognizerInput = prepareRecognizer(crop)
                val outputs = OnnxRuntimeClient.runDetailed(recognizerModel, listOf(recognizerInput))
                val recognizerOutput = outputs["fetch_name_0"]
                    ?: throw ModelInferenceContractException("PaddleOCR recognizer output fetch_name_0 is missing")
                val logits = recognizerOutput.values
                val recognizerShape = recognizerOutput.shape
                val classCount = recognizerShape.lastOrNull()
                    ?.takeIf { it > 1 }
                    ?.toInt()
                    ?: 18385
                require(classCount == 18385) {
                    "PaddleOCR recognizer returned $classCount classes; expected 18385."
                }
                val decoded = PaddleOcrCtcDecoder.decode(
                    logits = logits,
                    classCount = classCount,
                    dictionary = dictionary,
                )
                if (decoded.text.isNotBlank() && (decoded.confidence !in 0f..1f || decoded.confidence >= 0.5f)) {
                    lines += mapOf(
                        "text" to decoded.text,
                        "confidence" to decoded.confidence,
                        "detector_score" to box.score,
                        "bbox" to mapOf(
                            "x" to box.left,
                            "y" to box.top,
                            "w" to (box.right - box.left),
                            "h" to (box.bottom - box.top),
                        ),
                    )
                }
                val progress = 0.45 + (0.5 * (index + 1).toDouble() / boxes.size.coerceAtLeast(1))
                reporter.report(progress.coerceAtMost(0.95), "Recognized PaddleOCR region ${index + 1}/${boxes.size}")
            }

            val text = lines.joinToString("\n") { it["text"].toString() }
            reporter.report(1.0, "PaddleOCR inference complete")
            AiExecutionResult(
                ok = true,
                status = "succeeded",
                message = "PaddleOCR inference completed",
                details = mapOf(
                    "runtime" to runtimeId,
                    "task_type" to "ocr",
                    "text" to text,
                    "result" to mapOf(
                        "text" to text,
                        "lines" to lines,
                        "boxes_detected" to boxes.size,
                    ),
                ),
            )
        } catch (error: Throwable) {
            AiExecutionResult(
                ok = false,
                status = "failed",
                message = error.message ?: error.javaClass.simpleName,
                details = mapOf(
                    "runtime" to runtimeId,
                    "error" to (error.message ?: error.javaClass.simpleName),
                ),
            )
        }
    }

    private data class DetectorInput(
        val tensor: PreparedInferenceTensor,
        val width: Int,
        val height: Int,
    )

    private fun prepareDetector(bitmap: Bitmap): DetectorInput {
        val maxSide = max(bitmap.width, bitmap.height)
        val ratio = if (maxSide > 960) 960f / maxSide.toFloat() else 1f
        var width = max(32, ((bitmap.width * ratio / 32f).roundToInt() * 32))
        var height = max(32, ((bitmap.height * ratio / 32f).roundToInt() * 32))
        width = width.coerceAtMost(960)
        height = height.coerceAtMost(960)
        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val values = bgrNchw(
            resized,
            scale = 1f / 255f,
            mean = floatArrayOf(0.485f, 0.456f, 0.406f),
            std = floatArrayOf(0.229f, 0.224f, 0.225f),
        )
        return DetectorInput(
            tensor = PreparedInferenceTensor(
                name = "x",
                dataType = "float32",
                shape = longArrayOf(1, 3, height.toLong(), width.toLong()),
                floats = values,
            ),
            width = width,
            height = height,
        )
    }

    private fun prepareRecognizer(bitmap: Bitmap): PreparedInferenceTensor {
        val targetHeight = 48
        val targetWidth = 320
        val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat()
        val resizedWidth = min(targetWidth, ceil(targetHeight * ratio).toInt().coerceAtLeast(1))
        val resized = Bitmap.createScaledBitmap(bitmap, resizedWidth, targetHeight, true)
        val pixels = IntArray(resizedWidth * targetHeight)
        resized.getPixels(pixels, 0, resizedWidth, 0, 0, resizedWidth, targetHeight)

        val plane = targetWidth * targetHeight
        val values = FloatArray(plane * 3)
        pixels.forEachIndexed { pixelIndex, pixel ->
            val x = pixelIndex % resizedWidth
            val y = pixelIndex / resizedWidth
            val destination = y * targetWidth + x
            val red = (pixel shr 16) and 0xff
            val green = (pixel shr 8) and 0xff
            val blue = pixel and 0xff
            values[destination] = (blue / 255f - 0.5f) / 0.5f
            values[plane + destination] = (green / 255f - 0.5f) / 0.5f
            values[plane * 2 + destination] = (red / 255f - 0.5f) / 0.5f
        }
        return PreparedInferenceTensor(
            name = "x",
            dataType = "float32",
            shape = longArrayOf(1, 3, targetHeight.toLong(), targetWidth.toLong()),
            floats = values,
        )
    }

    private fun bgrNchw(
        bitmap: Bitmap,
        scale: Float,
        mean: FloatArray,
        std: FloatArray,
    ): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val plane = width * height
        val values = FloatArray(plane * 3)
        pixels.forEachIndexed { index, pixel ->
            val red = (pixel shr 16) and 0xff
            val green = (pixel shr 8) and 0xff
            val blue = pixel and 0xff
            values[index] = (blue * scale - mean[0]) / std[0]
            values[plane + index] = (green * scale - mean[1]) / std[1]
            values[plane * 2 + index] = (red * scale - mean[2]) / std[2]
        }
        return values
    }

    private fun decodeBitmap(payload: Map<String, Any>): Bitmap {
        val rawUri = listOf("image_uri", "uri", "source_path", "image_path", "file_path", "path")
            .asSequence()
            .mapNotNull { payload[it]?.toString()?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?: throw ModelInferenceContractException(
                "PaddleOCR requires image_uri, uri, source_path, image_path, file_path, or path",
            )
        return openImageStream(rawUri).use { input ->
            BitmapFactory.decodeStream(input)
                ?: throw ModelInferenceContractException("Unable to decode OCR image at '$rawUri'")
        }
    }

    private fun openImageStream(value: String): InputStream {
        val uri = Uri.parse(value)
        if (uri.scheme.equals("content", ignoreCase = true)) {
            return context.contentResolver.openInputStream(uri)
                ?: throw ModelInferenceContractException("Unable to open OCR content URI '$value'")
        }
        val file = if (uri.scheme.equals("file", ignoreCase = true)) File(uri.path.orEmpty()) else File(value)
        if (!file.isFile) throw ModelInferenceContractException("OCR image does not exist: '$value'")
        return FileInputStream(file)
    }

    private fun incompatible(message: String): AiExecutionResult = AiExecutionResult(
        ok = false,
        status = "incompatible",
        message = message,
    )
}
