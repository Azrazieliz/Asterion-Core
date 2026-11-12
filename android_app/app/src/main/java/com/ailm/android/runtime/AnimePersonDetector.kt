package com.ailm.android.runtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.ailm.android.runtime.ai.RuntimeOutputMapper
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Real local anime person detection, using a pinned MIT-licensed YOLOv8n.
 * Only returns body rectangles. It does not label species, infer identity or
 * mutate original images/Fusion rows. One detector invocation is bounded.
 */
internal class AnimePersonDetector(private val context: Context) {
    data class Detection(
        val bitmap: Bitmap,
        val regions: List<AnimePersonBoxDecoder.Region>,
        val status: String,
    ) : AutoCloseable {
        override fun close() { if (!bitmap.isRecycled) bitmap.recycle() }
    }

    fun detect(image: File): Detection {
        require(image.isFile && image.length() > 0L) { "Source file is unreadable" }
        val bitmap = decodeBounded(image)
            ?: throw IllegalArgumentException("Artwork could not be decoded")
        try {
            val model = ensureInstalledModel()
                ?: return Detection(bitmap, emptyList(), "person_model_unavailable")
            val input = createInput(bitmap)
            val environment = OrtEnvironment.getEnvironment()
            val prediction = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                environment.createSession(model.absolutePath, options).use { session ->
                    val name = session.inputNames.single()
                    OnnxTensor.createTensor(
                        environment, FloatBuffer.wrap(input), longArrayOf(1, 3, 640, 640),
                    ).use { tensor ->
                        session.run(mapOf(name to tensor)).use { result ->
                            val output = result.iterator().next().value
                            val shape = (output.info as? TensorInfo)?.shape ?: longArrayOf()
                            RuntimeOutputMapper.flatten(output.value) to shape
                        }
                    }
                }
            }
            val boxes = AnimePersonBoxDecoder.decode(
                prediction.first, prediction.second, bitmap.width, bitmap.height,
            )
            return Detection(bitmap, boxes, if (boxes.isEmpty()) "no_person_detected" else "detected")
        } catch (error: Exception) {
            if (!bitmap.isRecycled) bitmap.recycle()
            throw error
        }
    }

    /** A bounded, temporary crop for WD ViT inference; the original stays untouched. */
    fun crop(detection: Detection, region: AnimePersonBoxDecoder.Region, directory: File): File {
        val image = detection.bitmap
        val padX = (region.width * 0.04).roundToInt()
        val padY = (region.height * 0.04).roundToInt()
        val left = (region.left - padX).coerceIn(0, image.width - 1)
        val top = (region.top - padY).coerceIn(0, image.height - 1)
        val right = (region.right + padX).coerceIn(left + 1, image.width)
        val bottom = (region.bottom + padY).coerceIn(top + 1, image.height)
        require(right > left && bottom > top)
        require(directory.isDirectory || directory.mkdirs())
        val target = File.createTempFile("asterion-subject-", ".webp", directory)
        try {
            val crop = Bitmap.createBitmap(image, left, top, right - left, bottom - top)
            try {
                target.outputStream().use { output ->
                    require(crop.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, output)) {
                        "Subject crop compression failed"
                    }
                }
            } finally {
                crop.recycle()
            }
            require(target.isFile && target.length() > 0L)
            return target
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    private fun decodeBounded(source: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > 2048) sample *= 2
        return BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }

    private fun createInput(source: Bitmap): FloatArray {
        val canvasBitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        try {
            canvasBitmap.eraseColor(Color.rgb(114, 114, 114))
            val ratio = 640f / max(source.width, source.height)
            val scaledW = source.width * ratio
            val scaledH = source.height * ratio
            val left = (640f - scaledW) / 2f
            val top = (640f - scaledH) / 2f
            val canvas = Canvas(canvasBitmap)
            canvas.drawBitmap(
                source, Rect(0, 0, source.width, source.height),
                RectF(left, top, left + scaledW, top + scaledH),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            val pixels = IntArray(640 * 640)
            canvasBitmap.getPixels(pixels, 0, 640, 0, 0, 640, 640)
            val chw = FloatArray(3 * pixels.size)
            for (i in pixels.indices) {
                val rgb = pixels[i]
                chw[i] = ((rgb ushr 16) and 255) / 255f
                chw[pixels.size + i] = ((rgb ushr 8) and 255) / 255f
                chw[2 * pixels.size + i] = (rgb and 255) / 255f
            }
            return chw
        } finally {
            canvasBitmap.recycle()
        }
    }

    private fun ensureInstalledModel(): File? {
        val target = File(context.noBackupFilesDir, MODEL_ASSET)
        if (target.isFile && target.length() == MODEL_BYTES && sha256(target) == MODEL_SHA) return target
        val asset = runCatching { context.assets.open(MODEL_ASSET) }.getOrNull()
            ?: return null
        val temp = File(target.absolutePath + ".part")
        temp.delete()
        try {
            asset.use { input -> temp.outputStream().use(input::copyTo) }
            require(temp.length() == MODEL_BYTES && sha256(temp) == MODEL_SHA) {
                "Bundled anime person detector failed signed asset digest verification"
            }
            check(temp.renameTo(target)) { "Cannot install verified subject detector" }
            return target
        } finally {
            temp.delete()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val length = input.read(bytes)
                if (length < 0) break
                if (length > 0) md.update(bytes, 0, length)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MODEL_ASSET = "bundled_anime_person_v11_n.onnx"
        private const val MODEL_BYTES = 12_102_560L
        private const val MODEL_SHA = "6fcdfc6af18b3a26aca62aad0e00faffbeea23413abf868808d2d98d399999c9"
    }
}
