package com.ailm.android.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

internal data class ImageFingerprint(
    val sha256: String,
    val perceptualHash: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
)

internal data class ImageFingerprintInspection(
    val status: String,
    val fingerprint: ImageFingerprint? = null,
    val failureCode: String = "",
)

internal object ImageFingerprinting {
    fun inspect(
        storage: StorageProvider,
        uri: String,
        reportedSizeBytes: Long = 0L,
    ): ImageFingerprintInspection = inspectStream({ storage.openInputStream(uri) }, reportedSizeBytes)

    /** Inspect a verified local staging copy without re-downloading a remote SAF source. */
    fun inspectLocal(
        file: File,
        reportedSizeBytes: Long = 0L,
        precomputedSha256: String? = null,
    ): ImageFingerprintInspection =
        inspectStream({ FileInputStream(file) }, reportedSizeBytes, precomputedSha256)

    private fun inspectStream(
        open: () -> InputStream?,
        reportedSizeBytes: Long,
        precomputedSha256: String? = null,
    ): ImageFingerprintInspection {
        var failureCode = "stream_read_failed"
        val checkedOpen: () -> InputStream? = {
            try {
                open().also { if (it == null) failureCode = "provider_returned_no_stream" }
            } catch (_: SecurityException) {
                failureCode = "permission_denied"
                null
            } catch (_: java.io.FileNotFoundException) {
                failureCode = "stale_or_missing_document"
                null
            } catch (_: java.io.IOException) {
                failureCode = "provider_io_failure"
                null
            }
        }
        // For SAF/Rounder sources the SHA was computed during the verified
        // streaming copy. Never re-open cloud bytes to hash a second time.
        val exactHash = precomputedSha256
            ?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
            ?: sha256Stream(checkedOpen) { cause ->
                failureCode = when (cause) {
                    is SecurityException -> "sha256_permission_denied"
                    is java.io.IOException -> "sha256_stream_io_failure"
                    else -> "sha256_stream_exception"
                }
                // Do not log any private source URI, filename or pixels.
                Log.w("AsterionFingerprint",
                    "sha256_stream_failure code=$failureCode cause=${cause.javaClass.simpleName}")
            }
            ?: return ImageFingerprintInspection(status = "unreadable", failureCode = failureCode)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsOpened = try {
            checkedOpen()?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
                true
            } ?: false
        } catch (error: Exception) {
            failureCode = "bounds_decode_${error.javaClass.simpleName}"
            Log.w("AsterionFingerprint", "bounds_decode_failed cause=${error.javaClass.simpleName}")
            false
        }
        if (!boundsOpened) {
            return ImageFingerprintInspection(status = "unreadable", failureCode = failureCode)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return ImageFingerprintInspection(status = "invalid_image")
        }

        var sample = 1
        while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) {
            sample *= 2
        }
        val bitmap = try {
            checkedOpen()?.use { input ->
                BitmapFactory.decodeStream(
                    input,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) },
                )
            }
        } catch (error: Exception) {
            failureCode = "sample_decode_${error.javaClass.simpleName}"
            Log.w("AsterionFingerprint", "sample_decode_failed cause=${error.javaClass.simpleName}")
            return ImageFingerprintInspection(status = "unreadable", failureCode = failureCode)
        } ?: return ImageFingerprintInspection(status = "invalid_image")

        val perceptualHash = try {
            signature(bitmap)
        } finally {
            bitmap.recycle()
        }

        return ImageFingerprintInspection(
            status = "ready",
            fingerprint = ImageFingerprint(
                sha256 = exactHash,
                perceptualHash = perceptualHash,
                width = bounds.outWidth,
                height = bounds.outHeight,
                sizeBytes = reportedSizeBytes.coerceAtLeast(0L),
            ),
        )
    }

    fun isConservativeVisualDuplicate(
        current: ImageFingerprint,
        candidate: ImageFingerprintMatch,
        maxBitDistance: Int = 4,
    ): Boolean {
        if (current.width <= 0 || current.height <= 0) return false
        if (current.width != candidate.width || current.height != candidate.height) return false
        if (current.perceptualHash.isBlank() || candidate.perceptualHash.isBlank()) return false
        return perceptualHashDistance(current.perceptualHash, candidate.perceptualHash) <= maxBitDistance
    }

    fun perceptualHashDistance(first: String, second: String): Int {
        val a = first.trim().lowercase()
        val b = second.trim().lowercase()
        if (a.length != b.length || a.isBlank()) return Int.MAX_VALUE
        var distance = 0
        for (index in a.indices) {
            val left = a[index].digitToIntOrNull(16) ?: return Int.MAX_VALUE
            val right = b[index].digitToIntOrNull(16) ?: return Int.MAX_VALUE
            distance += Integer.bitCount(left xor right)
        }
        return distance
    }

    fun contentSha256(storage: StorageProvider, uri: String): String? =
        sha256Stream { storage.openInputStream(uri) }

    private fun sha256Stream(
        open: () -> InputStream?,
        onFailure: ((Throwable) -> Unit)? = null,
    ): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        open()?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        } ?: return null
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }.onFailure { error -> onFailure?.invoke(error) }.getOrNull()

    /**
     * 128-bit conservative visual fingerprint:
     * 64-bit horizontal difference hash + 64-bit average hash.
     * Asterion only auto-removes a visual duplicate when dimensions and this
     * entire signature match, avoiding aggressive deletion of crops/variants.
     */
    private fun signature(bitmap: Bitmap): String {
        val scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        try {
            val pixels = IntArray(72)
            scaled.getPixels(pixels, 0, 9, 0, 0, 9, 8)
            val luminance = IntArray(72) { index ->
                val pixel = pixels[index]
                val red = (pixel shr 16) and 0xff
                val green = (pixel shr 8) and 0xff
                val blue = pixel and 0xff
                (299 * red + 587 * green + 114 * blue) / 1000
            }

            var differenceHash = 0L
            var bit = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    if (luminance[y * 9 + x] > luminance[y * 9 + x + 1]) {
                        differenceHash = differenceHash or (1L shl bit)
                    }
                    bit += 1
                }
            }

            val core = IntArray(64)
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    core[y * 8 + x] = luminance[y * 9 + x]
                }
            }
            val average = core.average()
            var averageHash = 0L
            core.forEachIndexed { index, value ->
                if (value >= average) averageHash = averageHash or (1L shl index)
            }

            return "%016x%016x".format(differenceHash, averageHash)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }
}
