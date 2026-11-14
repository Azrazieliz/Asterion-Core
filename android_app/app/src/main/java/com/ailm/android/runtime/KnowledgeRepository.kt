package com.ailm.android.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class KnowledgeRepository(
    private val parser: KnowledgePackParser = KnowledgePackParser(),
    private val validator: KnowledgeValidator = KnowledgeValidator(),
    private val canonicalBuilder: KnowledgeCanonicalBuilder = KnowledgeCanonicalBuilder(),
    private val fusionBuilder: FusionModelBuilder = FusionModelBuilder(),
) {
    data class InstalledKnowledgePackArtifact(
        val file: File,
        val rawContent: String,
        val contentHash: String,
        val parsed: ParsedKnowledgePack? = null,
        val validation: KnowledgeValidationReport? = null,
    )

    data class DiscoveryResult(
        val artifacts: List<InstalledKnowledgePackArtifact>,
        val rebuildSignature: String,
    )

    data class RebuildPlan(
        val artifactCount: Int,
        val rebuildSignature: String,
        val packIds: List<String>,
    )

    fun discoverInstalledPacks(directory: File): DiscoveryResult {
        val files = directory.listFiles()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) &&
                !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase(Locale.US) }
            ?: emptyList()
        // Legacy Reference JSONs are first-class installed sources, but they
        // use a different canonical schema and are reconciled directly into
        // Fusion. Do not misparse them as generic KnowledgePackParser entries
        // or omit them from the overall 15-source inventory.
        val artifacts = files.mapNotNull { file ->
            val rawContent = file.readText(Charsets.UTF_8)
            if (ReferenceKnowledgeParser.looksLikeReferenceDocument(file.name, rawContent)) {
                return@mapNotNull null
            }
            val hash = sha256Hex(rawContent.toByteArray())
            val parsed = runCatching { parser.parse(rawContent) }.getOrNull()
            val validation = parsed?.let(validator::validate)
            InstalledKnowledgePackArtifact(file = file, rawContent = rawContent,
                contentHash = hash, parsed = parsed, validation = validation)
        }
        val signatures = artifacts.map { "${it.file.name}:${it.contentHash}" }
        val rebuildSignature = sha256Hex(signatures.sorted().joinToString("|").toByteArray())
        return DiscoveryResult(artifacts = artifacts, rebuildSignature = rebuildSignature)
    }

    fun buildRebuildPlan(artifacts: List<InstalledKnowledgePackArtifact>): RebuildPlan {
        val validPacks = artifacts.filter { it.validation?.ok == true }.mapNotNull { it.parsed }
        val packIds = validPacks.map { it.packId }.distinct().sorted()
        val payload = packIds.joinToString("|") + "|" + artifacts.map { it.contentHash }.sorted().joinToString("|")
        val rebuildSignature = sha256Hex(payload.toByteArray())
        return RebuildPlan(artifactCount = artifacts.size, rebuildSignature = rebuildSignature, packIds = packIds)
    }

    fun parseAndValidate(raw: String): ParsedKnowledgePack {
        val parsed = parser.parse(raw)
        val report = validator.validate(parsed)
        if (!report.ok) {
            throw IllegalArgumentException(report.issues.joinToString("\n") { it.message })
        }
        return parsed
    }

    fun buildCanonicalModel(raw: String): CanonicalKnowledgeModel {
        val parsed = parseAndValidate(raw)
        return canonicalBuilder.build(listOf(parsed))
    }

    fun buildCanonicalModelFromArtifacts(artifacts: List<InstalledKnowledgePackArtifact>): CanonicalKnowledgeModel {
        val parsedPacks = artifacts.filter { it.validation?.ok == true }.mapNotNull { it.parsed }
        return canonicalBuilder.build(parsedPacks)
    }

    fun buildFusionRows(model: CanonicalKnowledgeModel): List<FusionRowCandidate> {
        return fusionBuilder.build(model)
    }

    fun toJson(model: CanonicalKnowledgeModel): JSONObject {
        val payload = JSONObject()
        payload.put("pack_id", model.packId)
        payload.put("pack_name", model.packName)
        payload.put("version", model.version)
        payload.put("knowledge_type", model.knowledgeType)
        payload.put("author", model.author)
        payload.put("creation_date", model.creationDate)
        payload.put("description", model.description)
        payload.put("dependencies", JSONArray(model.dependencies))
        payload.put("supported_categories", JSONArray(model.supportedCategories))
        payload.put("built_at_ms", model.builtAtMs)
        payload.put("canonical_signature", model.canonicalSignature)
        val entries = JSONArray()
        model.entries.forEach { entry ->
            val obj = JSONObject()
            obj.put("id", entry.id)
            obj.put("name", entry.name)
            obj.put("categories", JSONArray(entry.categories))
            obj.put("taxonomy", JSONObject(entry.taxonomy))
            obj.put("metadata", JSONObject(entry.metadata))
            obj.put("provenance", JSONArray(entry.provenance))
            obj.put("aliases", JSONArray(entry.aliases))
            obj.put("tags", JSONArray(entry.tags))
            entries.put(obj)
        }
        payload.put("entries", entries)
        return payload
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }
}
