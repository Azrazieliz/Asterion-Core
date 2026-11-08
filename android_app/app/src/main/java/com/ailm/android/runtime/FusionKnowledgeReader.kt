package com.ailm.android.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Read-only native Fusion authority for visual taxonomy and character matching.
 * The legacy KnowledgeDatabase is not consulted by image recognition.
 */
internal class FusionKnowledgeReader(private val database: LocalDatabase) {
    private val taxonomy by lazy { FusionTaxonomyEngine(database) }

    fun hasCharacters(): Boolean = database.readableDatabase.rawQuery(
        "SELECT EXISTS(SELECT 1 FROM fusion_characters WHERE character_id GLOB 'CH[0-9]*' LIMIT 1)",
        emptyArray(),
    ).use { it.moveToFirst() && it.getInt(0) == 1 }

    fun resolveTag(value: String): ReferenceTagEntry? {
        val label = value.trim()
        if (label.isBlank()) return null
        val db = database.readableDatabase
        db.rawQuery(
            "SELECT tag_id, canonical_name, category, COALESCE(parent_tag_id, '') " +
                "FROM fusion_tags WHERE tag_id=? COLLATE NOCASE LIMIT 1",
            arrayOf(label),
        ).use { rows ->
            if (rows.moveToFirst()) return ReferenceTagEntry(
                rows.getString(0), rows.getString(1), rows.getString(2), rows.getString(3),
                emptyList(),
            )
        }
        // Ambiguous aliases such as Black across HC and EC are NOT resolved.
        val clean = label.lowercase(Locale.ROOT)
        return db.rawQuery(
            "SELECT t.tag_id,t.canonical_name,t.category,COALESCE(t.parent_tag_id,'') " +
                "FROM fusion_tags t LEFT JOIN fusion_tag_aliases a ON a.tag_id=t.tag_id " +
                "WHERE lower(t.canonical_name)=? OR lower(a.alias_value)=? " +
                "GROUP BY t.tag_id LIMIT 2",
            arrayOf(clean, clean),
        ).use { rows ->
            val matches = mutableListOf<ReferenceTagEntry>()
            while (rows.moveToNext()) matches += ReferenceTagEntry(
                rows.getString(0), rows.getString(1), rows.getString(2),
                rows.getString(3), emptyList(),
            )
            matches.singleOrNull()
        }
    }

    fun resolveTagByFamily(family: String, visibleLabel: String): ReferenceTagEntry? {
        val code = family.trim().uppercase(Locale.ROOT)
        if (!Regex("^[A-Z]{2}$").matches(code)) return null
        val source = visibleLabel.trim()
        if (source.isBlank()) return null
        val tags = taxonomy.resolveByFamily(code, source) ?: return null
        return database.readableDatabase.rawQuery(
            "SELECT COALESCE(parent_tag_id, '') FROM fusion_tags WHERE tag_id=? LIMIT 1",
            arrayOf(tags.id),
        ).use { rows ->
            val parent = if (rows.moveToFirst()) rows.getString(0).orEmpty() else ""
            ReferenceTagEntry(tags.id, tags.name, tags.category, parent, tags.aliases)
        }
    }

    fun seriesByCode(rawCode: String): ReferenceSeriesEntry? =
        database.readableDatabase.rawQuery(
            "SELECT series_code,canonical_title,COALESCE(franchise_id,''),aliases_json " +
                "FROM fusion_series WHERE series_code=? LIMIT 1",
            arrayOf(rawCode),
        ).use { rows ->
            if (!rows.moveToFirst()) null else ReferenceSeriesEntry(
                rows.getString(0), rows.getString(1), rows.getString(2),
                jsonStrings(rows.getString(3).orEmpty()),
            )
        }

    fun getCharacter(id: String): KnowledgeCharacterEntry? =
        database.readableDatabase.rawQuery(
            "SELECT character_id,canonical_name,COALESCE(primary_series_code,'')," +
                "recognition_profile_json,metadata_json " +
                "FROM fusion_characters WHERE character_id=? LIMIT 1",
            arrayOf(id),
        ).use { rows ->
            if (!rows.moveToFirst()) return@use null
            val profile = runCatching { JSONObject(rows.getString(3).orEmpty()) }.getOrDefault(JSONObject())
            val metadata = runCatching { JSONObject(rows.getString(4).orEmpty()) }.getOrDefault(JSONObject())
            val attributes = jsonStrings(profile.optJSONArray("taxonomy_ids"))
            val weapons = jsonStrings(profile.optJSONArray("weapons"))
            val outfits = jsonStrings(profile.optJSONArray("outfits"))
            KnowledgeCharacterEntry(
                characterId = rows.getString(0),
                canonicalName = rows.getString(1),
                primarySeriesCode = rows.getString(2).orEmpty(),
                aliases = jsonStrings(profile.optJSONArray("aliases")),
                attributeIds = attributes,
                weaponIds = weapons,
                outfitIds = outfits,
                sheetAssetId = profile.optString("sheet_asset_id"),
                entryType = metadata.optString("entry_type", "identity"),
                parentCharacterId = metadata.optString("parent_character_id", ""),
                identityGroupId = metadata.optString("identity_group_id", ""),
            )
        }

    fun resolveCharacter(raw: String, seriesCode: String? = null): KnowledgeCharacterEntry? {
        val query = raw.trim()
        if (query.isBlank()) return null
        getCharacter(query)?.let { character ->
            if (seriesCode.isNullOrBlank() || character.primarySeriesCode == seriesCode) return character
        }
        val normalized = query.lowercase(Locale.ROOT)
        val ids = mutableSetOf<String>()
        database.readableDatabase.rawQuery(
            "SELECT character_id,canonical_name,recognition_profile_json FROM fusion_characters",
            emptyArray(),
        ).use { rows ->
            while (rows.moveToNext()) {
                val id = rows.getString(0).orEmpty()
                val name = rows.getString(1).orEmpty()
                val profile = runCatching { JSONObject(rows.getString(2).orEmpty()) }
                    .getOrDefault(JSONObject())
                if (name.lowercase(Locale.ROOT) == normalized ||
                    jsonStrings(profile.optJSONArray("aliases")).any {
                        it.lowercase(Locale.ROOT) == normalized
                    }) ids += id
            }
        }
        val matches = ids.mapNotNull(::getCharacter).filter { character ->
            seriesCode.isNullOrBlank() || character.primarySeriesCode == seriesCode
        }
        return matches.singleOrNull()
    }

    fun rankByFeatures(observed: Map<String, Double>, limit: Int = 80): List<KnowledgeCandidate> {
        if (observed.isEmpty()) return emptyList()
        val ids = observed.keys.filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val sql = "SELECT character_id, SUM(canonical_weight) FROM fusion_character_features " +
            "WHERE feature_id IN (" + placeholders + ") GROUP BY character_id " +
            "ORDER BY SUM(canonical_weight) DESC LIMIT ?"
        val rankedIds = mutableListOf<String>()
        database.readableDatabase.rawQuery(sql, (ids + limit.coerceIn(1, 500).toString()).toTypedArray())
            .use { rows ->
                while (rows.moveToNext()) rankedIds += rows.getString(0)
            }
        return rankedIds.mapNotNull { id ->
            val character = getCharacter(id) ?: return@mapNotNull null
            val features = (character.attributeIds + character.weaponIds + character.outfitIds).toSet()
            KnowledgeCandidate(character, CanonicalAttributeSemantics.coherenceScore(
                observed = observed, candidateFeatures = features, weight = KnowledgeDatabase::featureWeight,
            ))
        }.sortedByDescending(KnowledgeCandidate::attributeScore)
    }

    private fun jsonStrings(source: String): List<String> =
        jsonStrings(runCatching { JSONArray(source) }.getOrNull())

    private fun jsonStrings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { i ->
            array.optString(i).trim().takeIf(String::isNotBlank)
        }
}
