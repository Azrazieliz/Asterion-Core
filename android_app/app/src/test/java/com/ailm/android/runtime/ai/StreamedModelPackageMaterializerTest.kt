package com.ailm.android.runtime.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

class StreamedModelPackageMaterializerTest {
    @Test
    fun `zip stream materializes directly into final install directory`() {
        val root = createTempDirectory("streamed-model-").toFile()
        try {
            val zipBytes = zipOf(
                "Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf" to byteArrayOf(1, 2, 3, 4),
                "mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf" to byteArrayOf(5, 6, 7),
            )
            val destination = File(root, "install")
            val result = StreamedModelPackageMaterializer.materialize(
                input = ByteArrayInputStream(zipBytes),
                displayName = "Qwen2.5-VL-3B-Instruct.zip",
                mimeType = "application/octet-stream",
                sourceSizeHint = 0L,
                destination = destination,
            )

            assertTrue(result.archive)
            assertEquals(destination.canonicalFile, result.packageRoot.canonicalFile)
            assertEquals(7L, result.materializedBytes)
            assertArrayEquals(
                byteArrayOf(1, 2, 3, 4),
                File(destination, "Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf").readBytes(),
            )
            assertArrayEquals(
                byteArrayOf(5, 6, 7),
                File(destination, "mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf").readBytes(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `zip magic is recognized even when cloud provider reports generic mime`() {
        val root = createTempDirectory("streamed-model-magic-").toFile()
        try {
            val zipBytes = zipOf("model.onnx" to byteArrayOf(9, 8, 7))
            val result = StreamedModelPackageMaterializer.materialize(
                input = ByteArrayInputStream(zipBytes),
                displayName = "remote-content.bin",
                mimeType = "application/octet-stream",
                sourceSizeHint = 0L,
                destination = File(root, "install"),
            )

            assertTrue(result.archive)
            assertTrue(File(result.packageRoot, "model.onnx").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `single streamed model file is preserved without archive staging`() {
        val root = createTempDirectory("streamed-model-single-").toFile()
        try {
            val bytes = byteArrayOf(11, 12, 13, 14)
            val result = StreamedModelPackageMaterializer.materialize(
                input = ByteArrayInputStream(bytes),
                displayName = "model_q4.onnx",
                mimeType = "application/octet-stream",
                sourceSizeHint = 0L,
                destination = File(root, "install"),
            )

            assertFalse(result.archive)
            assertEquals(4L, result.materializedBytes)
            assertArrayEquals(bytes, File(result.packageRoot, "model_q4.onnx").readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `archive traversal is rejected and partial install directory is removed`() {
        val root = createTempDirectory("streamed-model-traversal-").toFile()
        try {
            val zipBytes = zipOf("../escape.gguf" to byteArrayOf(1))
            val destination = File(root, "install")
            try {
                StreamedModelPackageMaterializer.materialize(
                    input = ByteArrayInputStream(zipBytes),
                    displayName = "bad.zip",
                    mimeType = "application/zip",
                    sourceSizeHint = 0L,
                    destination = destination,
                )
                fail("Expected traversal archive to be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
            assertFalse(destination.exists())
            assertFalse(File(root, "escape.gguf").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun zipOf(vararg files: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
