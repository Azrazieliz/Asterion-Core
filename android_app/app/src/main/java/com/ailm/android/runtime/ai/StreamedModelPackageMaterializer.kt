package com.ailm.android.runtime.ai

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

internal data class StreamedModelPackageMaterialization(
    val packageRoot: File,
    val sourceName: String,
    val archive: Boolean,
    val sourceSizeHint: Long,
    val materializedBytes: Long,
)

/**
 * Materializes a model package straight from a content stream into its final
 * private install directory.
 *
 * Cloud providers can therefore keep the source archive remote: AsterionCore
 * never creates a second staged copy of the archive on device storage. Only
 * the extracted/installed model artifacts are persisted locally, which is
 * required by the native inference runtimes.
 */
internal object StreamedModelPackageMaterializer {
    private const val BUFFER_SIZE = 256 * 1024
    private const val SAFETY_MARGIN_BYTES = 256L * 1024L * 1024L
    private const val UNKNOWN_SOURCE_MAX_EXTRACTED_BYTES = 64L * 1024L * 1024L * 1024L
    private const val MAX_ARCHIVE_EXPANSION_MULTIPLIER = 32L
    private const val EXPANSION_ALLOWANCE_BYTES = 1024L * 1024L * 1024L

    fun materialize(
        input: InputStream,
        displayName: String,
        mimeType: String?,
        sourceSizeHint: Long,
        destination: File,
    ): StreamedModelPackageMaterialization {
        val safeName = sanitizeFileName(displayName)
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, BUFFER_SIZE)
        val archive = looksLikeZip(buffered, safeName, mimeType)
        val parent = destination.parentFile
            ?: throw IllegalArgumentException("Model install directory has no parent.")

        destination.deleteRecursively()
        require(parent.exists() || parent.mkdirs()) { "Unable to prepare model install storage." }
        ensureInitialSpace(parent, sourceSizeHint)

        return try {
            require(destination.mkdirs()) { "Unable to prepare model install directory." }
            val written = if (archive) {
                extractZip(
                    input = buffered,
                    destination = destination,
                    sourceSizeHint = sourceSizeHint,
                    spaceRoot = parent,
                )
            } else {
                copySingleFile(
                    input = buffered,
                    destination = destination,
                    filename = safeName,
                    sourceSizeHint = sourceSizeHint,
                    spaceRoot = parent,
                )
            }
            require(written > 0L) { "Selected model package is empty." }
            StreamedModelPackageMaterialization(
                packageRoot = destination,
                sourceName = safeName,
                archive = archive,
                sourceSizeHint = sourceSizeHint.coerceAtLeast(0L),
                materializedBytes = written,
            )
        } catch (error: Throwable) {
            destination.deleteRecursively()
            throw error
        }
    }

    private fun extractZip(
        input: InputStream,
        destination: File,
        sourceSizeHint: Long,
        spaceRoot: File,
    ): Long {
        val root = destination.canonicalFile
        val maximumExtractedBytes = maximumExtractedBytes(sourceSizeHint)
        var totalWritten = 0L
        var fileCount = 0

        ZipInputStream(input).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                val rawName = entry.name.replace('\\', '/')
                require(rawName.isNotBlank()) { "Archive contains an unnamed entry." }
                val target = File(root, rawName).canonicalFile
                require(
                    target.path == root.path ||
                        target.path.startsWith(root.path + File.separator),
                ) { "Archive contains an invalid path." }

                if (entry.isDirectory) {
                    require(target.exists() || target.mkdirs()) {
                        "Unable to create model package directory."
                    }
                    archive.closeEntry()
                    continue
                }

                fileCount += 1
                require(fileCount <= 100_000) { "Model archive contains too many files." }
                target.parentFile?.let { parent ->
                    require(parent.exists() || parent.mkdirs()) {
                        "Unable to create model package directory."
                    }
                }

                target.outputStream().buffered(BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = archive.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        totalWritten = checkedAdd(totalWritten, read.toLong())
                        require(totalWritten <= maximumExtractedBytes) {
                            "Model archive expands beyond the safe extraction limit."
                        }
                        ensureStreamingSpace(spaceRoot, read.toLong())
                        output.write(buffer, 0, read)
                    }
                }
                archive.closeEntry()
            }
        }

        require(fileCount > 0) { "Selected ZIP contains no model files." }
        return totalWritten
    }

    private fun copySingleFile(
        input: InputStream,
        destination: File,
        filename: String,
        sourceSizeHint: Long,
        spaceRoot: File,
    ): Long {
        val target = File(destination, filename).canonicalFile
        require(target.parentFile?.canonicalFile == destination.canonicalFile) {
            "Invalid model filename."
        }
        var totalWritten = 0L
        val maximum = if (sourceSizeHint > 0L) {
            checkedAdd(sourceSizeHint, EXPANSION_ALLOWANCE_BYTES)
        } else {
            UNKNOWN_SOURCE_MAX_EXTRACTED_BYTES
        }

        target.outputStream().buffered(BUFFER_SIZE).use { output ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                totalWritten = checkedAdd(totalWritten, read.toLong())
                require(totalWritten <= maximum) { "Selected model file exceeds the safe import limit." }
                ensureStreamingSpace(spaceRoot, read.toLong())
                output.write(buffer, 0, read)
            }
        }
        return totalWritten
    }

    private fun looksLikeZip(input: BufferedInputStream, displayName: String, mimeType: String?): Boolean {
        if (displayName.endsWith(".zip", ignoreCase = true)) return true
        if (mimeType?.contains("zip", ignoreCase = true) == true) return true

        input.mark(8)
        val header = ByteArray(4)
        val count = input.read(header)
        input.reset()
        if (count < 4) return false

        return header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte() &&
            (
                (header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) ||
                    (header[2] == 0x05.toByte() && header[3] == 0x06.toByte()) ||
                    (header[2] == 0x07.toByte() && header[3] == 0x08.toByte())
                )
    }

    private fun ensureInitialSpace(spaceRoot: File, sourceSizeHint: Long) {
        if (sourceSizeHint <= 0L) return
        val required = checkedAdd(sourceSizeHint, SAFETY_MARGIN_BYTES)
        val available = spaceRoot.usableSpace
        require(available <= 0L || available >= required) {
            val requiredMiB = toMiB(required)
            val availableMiB = toMiB(available.coerceAtLeast(0L))
            "Insufficient storage to import cloud model: need at least ${requiredMiB} MiB free, only ${availableMiB} MiB available."
        }
    }

    private fun ensureStreamingSpace(spaceRoot: File, upcomingBytes: Long) {
        val available = spaceRoot.usableSpace
        if (available <= 0L) return
        val required = checkedAdd(SAFETY_MARGIN_BYTES, upcomingBytes)
        if (available < required) {
            throw IOException(
                "Insufficient storage while importing cloud model: keeping " +
                    "${toMiB(SAFETY_MARGIN_BYTES)} MiB safety reserve.",
            )
        }
    }

    private fun maximumExtractedBytes(sourceSizeHint: Long): Long {
        if (sourceSizeHint <= 0L) return UNKNOWN_SOURCE_MAX_EXTRACTED_BYTES
        val multiplied = if (sourceSizeHint > Long.MAX_VALUE / MAX_ARCHIVE_EXPANSION_MULTIPLIER) {
            Long.MAX_VALUE
        } else {
            sourceSizeHint * MAX_ARCHIVE_EXPANSION_MULTIPLIER
        }
        return if (multiplied > Long.MAX_VALUE - EXPANSION_ALLOWANCE_BYTES) {
            Long.MAX_VALUE
        } else {
            multiplied + EXPANSION_ALLOWANCE_BYTES
        }
    }

    private fun checkedAdd(left: Long, right: Long): Long {
        require(right >= 0L && left <= Long.MAX_VALUE - right) { "Model package size overflow." }
        return left + right
    }

    private fun sanitizeFileName(name: String): String {
        return name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .ifBlank { "model-package.bin" }
    }

    private fun toMiB(bytes: Long): Long =
        if (bytes <= 0L) 0L else (bytes + 1024L * 1024L - 1L) / (1024L * 1024L)
}
