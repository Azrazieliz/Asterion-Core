package com.ailm.android.runtime

import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

class FusionModelBuilder {
    fun build(model: CanonicalKnowledgeModel): List<FusionRowCandidate> {
        val rows = mutableListOf<FusionRowCandidate>()
        model.characters.forEachIndexed { index, character ->
            rows += FusionRowCandidate(
                table = FusionDatabaseSchema.TABLE_CHARACTERS,
                row = linkedMapOf(
                    "character_id" to stableCharacterId(character.id, model.canonicalSignature),
                    "primary_series_code" to "",
                    "canonical_name" to character.name,
                    "localized_name" to character.name,
                    "romaji_name" to character.name,
                    "gender" to "",
                    "description" to character.metadata["description"].orEmpty(),
                    "recognition_profile_json" to JSONObject(mapOf("aliases" to character.aliases, "taxonomy_ids" to character.taxonomyIds)).toString(),
                    "metadata_json" to JSONObject(mapOf(
                        "source_pack_id" to character.sourcePackId,
                        "source_pack_name" to character.sourcePackName,
                        "source_version" to character.sourceVersion,
                        "categories" to character.categories,
                        "provenance" to character.provenance.map { it.packId },
                    )).toString(),
                    "workbook_row" to index + 1,
                    "created_at_ms" to model.builtAtMs,
                    "updated_at_ms" to model.builtAtMs,
                ),
                sourceId = character.id,
                ordering = index,
            )
        }

        model.taxonomies.forEachIndexed { index, taxonomy ->
            rows += FusionRowCandidate(
                table = FusionDatabaseSchema.TABLE_TAGS,
                row = linkedMapOf(
                    "tag_id" to stableTagId(taxonomy.id, model.canonicalSignature),
                    "canonical_name" to taxonomy.name,
                    "category" to taxonomy.metadata["category"].orEmpty().ifBlank { taxonomy.id.take(2) },
                    "parent_tag_id" to (taxonomy.parentId ?: ""),
                    "metadata_json" to JSONObject(mapOf(
                        "source_pack_id" to taxonomy.sourcePackId,
                        "source_pack_name" to taxonomy.sourcePackName,
                        "source_version" to taxonomy.sourceVersion,
                        "parent_id" to taxonomy.parentId,
                        "provenance" to taxonomy.provenance.map { it.packId },
                    )).toString(),
                    "workbook_row" to index + 1,
                    "created_at_ms" to model.builtAtMs,
                    "updated_at_ms" to model.builtAtMs,
                ),
                sourceId = taxonomy.id,
                ordering = index,
            )
        }

        return rows.sortedWith(compareBy<FusionRowCandidate> { it.table }.thenBy { it.ordering })
    }

    // Canonical IDs must survive Knowledge source version / signature changes.
    // Only entities without valid canonical IDs receive legacy deterministic IDs.
    private fun stableCharacterId(id: String, signature: String): String =
        id.takeIf { Regex("^CH[0-9]{6}(?:-[0-9]+)*$").matches(it) }
            ?: deterministicFusionId("character", id, signature)

    private fun stableTagId(id: String, signature: String): String =
        id.takeIf { Regex("^[A-Z]{2}[0-9]{2,}(?:-[0-9]+)*$").matches(it) }
            ?: deterministicFusionId("tag", id, signature)

    private fun deterministicFusionId(prefix: String, entityId: String, signature: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = "$prefix:$entityId:$signature".toByteArray()
        val hash = digest.digest(input).joinToString("") { "%02x".format(it) }
        return "${prefix.uppercase(Locale.US)}_$hash"
    }
}

data class FusionRowCandidate(
    val table: String,
    val row: Map<String, Any?>,
    val sourceId: String,
    val ordering: Int,
)
