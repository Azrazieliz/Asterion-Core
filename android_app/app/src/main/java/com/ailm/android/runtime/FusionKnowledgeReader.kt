package com.ailm.android.runtime

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Read-only native Fusion authority for visual taxonomy and character matching.
 * The legacy KnowledgeDatabase is not consulted by image recognition.
 */
internal class FusionKnowledgeReader(private val database: LocalDatabase) {
    private val taxonomy by lazy { FusionTaxonomyEngine(database) }

    /**
     * Full canonical SP/SA descriptor, including per-species metadata. Avoid
     * resolving arbitrary free text into a model-trusted tag ID.
     */
    fun observationDescriptor(rawId: String): CanonicalObservationPolicy.Descriptor? {
        val id = rawId.trim().uppercase(Locale.ROOT)
        if (!Regex("^[A-Z]{2}[0-9]{2,}(?:-[0-9]+)*$").matches(id)) return null
        val tag = taxonomy.tagById(id) ?: return null
        return CanonicalObservationPolicy.Descriptor(
            tag.id, tag.parentId, tag.name, tag.traitRequirement, tag.speciesRules,
        )
    }

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
        return db.rawQuery(
            "SELECT t.tag_id,t.canonical_name,t.category,COALESCE(t.parent_tag_id,'') " +
                "FROM fusion_tags t WHERE t.canonical_name=? COLLATE NOCASE " +
                "UNION SELECT t.tag_id,t.canonical_name,t.category,COALESCE(t.parent_tag_id,'') " +
                "FROM fusion_tag_aliases a JOIN fusion_tags t ON t.tag_id=a.tag_id " +
                "WHERE a.alias_value=? COLLATE NOCASE LIMIT 2",
            arrayOf(label, label),
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

    /** Map display ratings to existing canonical RT IDs, never to visual media. */
    fun resolveContentRating(level: String): ReferenceTagEntry? =
        FusionNsfwRatingPolicy.existingFusionNames(level)
            .firstNotNullOfOrNull { candidate -> resolveTagByFamily("RT", candidate) }

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
            // Feature records from all imported Knowledge sources are
            // authoritative for retrieval. Older Fusion profiles may predate
            // the other 10 JSON packs; union their indexed features rather
            // than making those characters appear featureless in ranking.
            val recorded = buildList {
                database.readableDatabase.rawQuery(
                    "SELECT feature_id FROM fusion_character_features WHERE character_id=?",
                    arrayOf(id),
                ).use { features ->
                    while (features.moveToNext()) add(features.getString(0).orEmpty())
                }
            }
            val attributes = (jsonStrings(profile.optJSONArray("taxonomy_ids")) +
                recorded.filterNot { it.startsWith("WP") || it.startsWith("OF") }).distinct()
            val weapons = (jsonStrings(profile.optJSONArray("weapons")) +
                recorded.filter { it.startsWith("WP") }).distinct()
            val outfits = (jsonStrings(profile.optJSONArray("outfits")) +
                recorded.filter { it.startsWith("OF") }).distinct()
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

    /**
     * Canonical profile completeness is distinct from visible image tagging.
     * User-authored not_applicable_families are honored but never inferred.
     * This method is read-only: no character ID, field or image is changed.
     */
    fun characterProfileStatus(character: KnowledgeCharacterEntry): Map<String, Any> {
        val exclusions = database.readableDatabase.rawQuery(
            "SELECT recognition_profile_json FROM fusion_characters WHERE character_id=? LIMIT 1",
            arrayOf(character.characterId),
        ).use { rows ->
            if (!rows.moveToFirst()) emptySet() else {
                val profile = runCatching { JSONObject(rows.getString(0).orEmpty()) }
                    .getOrDefault(JSONObject())
                jsonStrings(profile.optJSONArray("not_applicable_families"))
                    .map { it.trim().uppercase(Locale.ROOT) }.toSet()
            }
        }
        val audit = CharacterProfileCompleteness.audit(
            character.attributeIds, taxonomy.allTags(refresh = true), exclusions,
        )
        return audit.toMap() + mapOf(
            "character_id" to character.characterId,
            "has_sheet_reference" to character.sheetAssetId.isNotBlank(),
            // Asset reference presence is NOT proof of a validated visual sheet.
            "visual_reference_verified" to false,
        )
    }

    /**
     * Additively index aliases stored by older Fusion importers. Existing
     * character IDs and profile JSON remain authoritative and unchanged.
     * Called on startup/after imports, never during per-image inference.
     */
    fun refreshCharacterAliasIndex(): Int {
        val db = database.writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            db.rawQuery(
                "SELECT character_id, recognition_profile_json FROM fusion_characters",
                emptyArray(),
            ).use { rows ->
                while (rows.moveToNext()) {
                    val id = rows.getString(0).orEmpty()
                    val profile = runCatching { JSONObject(rows.getString(1).orEmpty()) }
                        .getOrDefault(JSONObject())
                    for (alias in jsonStrings(profile.optJSONArray("aliases"))) {
                        val clean = alias.trim().replace(Regex("\\s+"), " ")
                        if (clean.isBlank()) continue
                        val values = ContentValues().apply {
                            put("character_id", id)
                            put("alias_value", clean)
                        }
                        if (db.insertWithOnConflict(
                                "fusion_character_aliases", null, values,
                                SQLiteDatabase.CONFLICT_IGNORE,
                            ) != -1L) added++
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    /**
     * No per-query full profile scan. Query canonical names and the indexed
     * alias table; remain undecided when two characters share the same name.
     */
    fun resolveCharacter(raw: String, seriesCode: String? = null): KnowledgeCharacterEntry? {
        val query = raw.trim()
        if (query.isBlank()) return null
        getCharacter(query)?.let { character ->
            if (seriesCode.isNullOrBlank() || character.primarySeriesCode == seriesCode) return character
        }
        val series = seriesCode.orEmpty()
        val ids = mutableListOf<String>()
        database.readableDatabase.rawQuery(
            "SELECT c.character_id FROM fusion_characters c JOIN (" +
                "SELECT character_id FROM fusion_characters WHERE canonical_name=? COLLATE NOCASE " +
                "UNION SELECT character_id FROM fusion_character_aliases WHERE alias_value=? COLLATE NOCASE" +
                ") matches ON matches.character_id=c.character_id " +
                "WHERE (?='' OR c.primary_series_code=?) LIMIT 3",
            arrayOf(query, query, series, series),
        ).use { rows ->
            while (rows.moveToNext()) ids += rows.getString(0)
        }
        return ids.singleOrNull()?.let(::getCharacter)
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
