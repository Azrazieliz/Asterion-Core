package com.ailm.android.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
)

internal object ImageFingerprinting {
    fun inspect(
        storage: StorageProvider,
        uri: String,
        reportedSizeBytes: Long = 0L,
    ): ImageFingerprintInspection {
        val exactHash = contentSha256(storage, uri)
            ?: return ImageFingerprintInspection(status = "unreadable")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        storage.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: return ImageFingerprintInspection(status = "unreadable")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return ImageFingerprintInspection(status = "invalid_image")
        }

        var sample = 1
        while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) {
            sample *= 2
        }
        val bitmap = storage.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(
                input,
                null,
                BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) },
            )
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

    fun contentSha256(storage: StorageProvider, uri: String): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        storage.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        } ?: return null
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }.getOrNull()

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
