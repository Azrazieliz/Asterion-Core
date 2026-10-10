package com.ailm.android.runtime.ai

import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory

internal object TestPackageFixtureResolver {
    fun resolvePackageDirectory(packageName: String, zipName: String): File {
        val inspectionCandidates = listOf(
            File("../../AsterionCore/inspection/$packageName"),
            File("../AsterionCore/inspection/$packageName"),
            File("AsterionCore/inspection/$packageName"),
        )
        inspectionCandidates.firstOrNull { it.isDirectory }?.let { return it }

        val zipCandidates = listOf(
            File("../../AsterionCore/$zipName"),
            File("../AsterionCore/$zipName"),
            File("AsterionCore/$zipName"),
        )
        val zip = zipCandidates.firstOrNull { it.isFile }
        if (zip == null) {
            val message = "Missing external real-model fixture: $zipName. Put it in AsterionCore/ or AsterionCore/inspection/$packageName"
            if (System.getenv("ASTERION_REQUIRE_MODEL_FIXTURES") == "1") {
                error(message)
            }
            // Keep the portable unit suite runnable without multi-gigabyte
            // proprietary/external model archives. Never pretend that
            // a model contract was verified without its real test fixture.
            org.junit.Assume.assumeTrue(message, false)
            error(message) // unreachable: assumeTrue throws on false
        }

        val destination = createTempDirectory(prefix = "${packageName.replace(Regex("[^A-Za-z0-9._-]"), "-")}-").toFile()
        destination.deleteRecursively()
        destination.mkdirs()
        ZipFile(zip).use { archive ->
            archive.entries().asSequence().forEach { entry ->
                if (entry.isDirectory) return@forEach
                val target = File(destination, entry.name)
                require(target.canonicalPath.startsWith(destination.canonicalPath + File.separator)) {
                    "Archive contains an invalid path"
                }
                target.parentFile?.mkdirs()
                archive.getInputStream(entry).use { input -> target.outputStream().use(input::copyTo) }
            }
        }
        return destination
    }
}
