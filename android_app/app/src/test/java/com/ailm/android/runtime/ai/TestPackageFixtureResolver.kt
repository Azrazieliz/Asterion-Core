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
            ?: throw org.junit.AssumptionViolatedException(
                "OPTIONAL REAL-MODEL FIXTURE MISSING: $zipName; " +
                    "no model ZIP/inspection directory in CI. " +
                    "The package contract requires a separate fixture-enabled job."
            )

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
