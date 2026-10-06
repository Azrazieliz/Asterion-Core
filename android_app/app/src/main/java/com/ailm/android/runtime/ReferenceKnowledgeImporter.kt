package com.ailm.android.runtime

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

internal data class ReferenceSeriesEntry(
    val code: String,
    val name: String,
    val franchise: String,
    val aliases: List<String>,
)

internal data class ReferenceTagEntry(
    val id: String,
    val name: String,
    val category: String,
    val parentId: String,
    val aliases: List<String>,
)

internal data class ReferenceKnowledgeBundle(
    val series: List<ReferenceSeriesEntry>,
    val tags: List<ReferenceTagEntry>,
    val characters: List<KnowledgeCharacterEntry> = emptyList(),
)

internal data class SeriesAliasConflict(
    val alias: String,
    val seriesCode: String,
    val reason: String,
)

internal data class SeriesAliasIndexPlan(
    val canonicalEntries: List<Pair<String, String>>,
    val uniqueAliases: List<Pair<String, String>>,
    val ignoredAliases: List<SeriesAliasConflict>,
)

internal object SeriesAliasPlanner {
    fun plan(series: List<ReferenceSeriesEntry>): SeriesAliasIndexPlan {
        fun key(value: String): String = value
            .trim()
            .lowercase(Locale.US)
            .replace(Regex("\\s+"), " ")

        val canonicalOwners = linkedMapOf<String, MutableSet<String>>()
        series.forEach { entry ->
            listOf(entry.code, entry.name).forEach { value ->
                val normalized = key(value)
                if (normalized.isNotBlank()) {
                    canonicalOwners.getOrPut(normalized) { linkedSetOf() } += entry.code
                }
            }
        }

        val aliasOwners = linkedMapOf<String, MutableSet<String>>()
        val aliasSpellings = linkedMapOf<Pair<String, String>, String>()
        series.forEach { entry ->
            entry.aliases.forEach { alias ->
                val normalized = key(alias)
                if (normalized.isBlank()) return@forEach
                aliasOwners.getOrPut(normalized) { linkedSetOf() } += entry.code
                aliasSpellings.putIfAbsent(entry.code to normalized, alias.trim())
            }
        }

        val canonicalEntries = buildList {
            series.forEach { entry ->
                val codeKey = key(entry.code)
                if (codeKey.isNotBlank()) {
                    add(entry.code to entry.code)
                }
                val nameKey = key(entry.name)
                if (
                    nameKey.isNotBlank() &&
                    nameKey != codeKey &&
                    canonicalOwners[nameKey].orEmpty().size == 1
                ) {
                    add(entry.code to entry.name)
                }
            }
        }

        val uniqueAliases = mutableListOf<Pair<String, String>>()
        val ignored = mutableListOf<SeriesAliasConflict>()

        series.forEach { entry ->
            entry.aliases
                .distinctBy(::key)
                .forEach { alias ->
                    val normalized = key(alias)
                    if (normalized.isBlank()) return@forEach

                    val canonicalOwner = canonicalOwners[normalized]?.singleOrNull()
                    val owners = aliasOwners[normalized].orEmpty()
                    when {
                        canonicalOwner != null && canonicalOwner != entry.code -> {
                            ignored += SeriesAliasConflict(
                                alias = alias,
                                seriesCode = entry.code,
                                reason = "canonical key belongs to $canonicalOwner",
                            )
                        }
                        owners.size > 1 -> {
                            ignored += SeriesAliasConflict(
                                alias = alias,
                                seriesCode = entry.code,
                                reason = "alias is shared by " + owners.sorted().joinToString(", "),
                            )
                        }
                        canonicalOwner == entry.code -> {
                            // Redundant alias for this series' own code/name. The
                            // authoritative canonical entry already indexes it.
                        }
                        else -> uniqueAliases += entry.code to alias
                    }
                }
        }

        return SeriesAliasIndexPlan(
            canonicalEntries = canonicalEntries,
            uniqueAliases = uniqueAliases,
            ignoredAliases = ignored,
        )
    }
}

internal object ReferenceKnowledgeParser {
    fun looksLikeReferenceDocument(filename: String, raw: String): Boolean {
        return runCatching {
            collectObjects(raw).any { obj ->
                val canonicalName = cleanString(obj, "canonical_name")
                    .ifBlank { cleanString(obj, "character_name") }
                if (canonicalName.isBlank()) {
                    false
                } else {
                    val explicitReferenceId = listOf(
                        "series_code",
                        "tag_id",
                        "character_id",
                        "outfit_id",
                        "weapon_id",
                    ).any { key -> cleanId(obj, key).isNotBlank() }
                    val genericId = cleanId(obj, "id")
                    val genericTaxonomyId = genericId.isNotBlank() &&
                        (
                            cleanString(obj, "attribute").isNotBlank() ||
                                hasMeaningfulValue(obj, "parent_tag") ||
                                hasMeaningfulValue(obj, "parent_action") ||
                                hasMeaningfulValue(obj, "parent_outfit") ||
                                hasMeaningfulValue(obj, "parent_weapon") ||
                                inferCategory(filename, obj, genericId) != "tag"
                            )
                    explicitReferenceId || genericTaxonomyId
                }
            }
        }.getOrDefault(false)
    }

    fun parseDocuments(documents: Map<String, String>): ReferenceKnowledgeBundle {
        val series = linkedMapOf<String, ReferenceSeriesEntry>()
        val tags = linkedMapOf<String, ReferenceTagEntry>()
        val characters = linkedMapOf<String, KnowledgeCharacterEntry>()

        documents.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (filename, raw) ->
            collectObjects(raw).forEach { obj ->
                val canonicalName = cleanString(obj, "canonical_name")
                    .ifBlank { cleanString(obj, "character_name") }
                if (canonicalName.isBlank()) return@forEach

                val characterId = cleanId(obj, "character_id")
                if (characterId.isNotBlank()) {
                    val parentCharacterId = cleanId(obj, "parent_character_id")
                    val primarySeries = cleanId(obj, "primary_series_code")
                        .ifBlank { cleanId(obj, "series_code") }
                    val attributes = linkedSetOf<String>().apply {
                        addAll(stringList(obj.opt("attribute_ids")).map(::cleanIdValue).filter(String::isNotBlank))
                        val attributeObject = obj.optJSONObject("attributes")
                        attributeObject?.keys()?.forEach { key ->
                            when (val value = attributeObject.opt(key)) {
                                is JSONArray -> addAll(
                                    stringList(value).map(::cleanIdValue).filter(String::isNotBlank),
                                )
                                is String -> cleanIdValue(value).takeIf(String::isNotBlank)?.let(::add)
                            }
                        }
                    }
                    val entry = KnowledgeCharacterEntry(
                        characterId = characterId,
                        parentCharacterId = parentCharacterId,
                        identityGroupId = cleanId(obj, "identity_group_id"),
                        entryType = cleanString(obj, "entry_type").ifBlank {
                            if (parentCharacterId.isNotBlank()) "transformation" else "identity"
                        },
                        canonicalName = canonicalName,
                        primarySeriesCode = primarySeries,
                        aliases = stringList(obj.opt("aliases")),
                        attributeIds = attributes.toList(),
                        weaponIds = stringList(obj.opt("canonical_weapon_ids"))
                            .ifEmpty { stringList(obj.opt("weapon_ids")) }
                            .map(::cleanIdValue)
                            .filter(String::isNotBlank),
                        outfitIds = stringList(obj.opt("canonical_outfit_ids"))
                            .ifEmpty { stringList(obj.opt("outfit_ids")) }
                            .map(::cleanIdValue)
                            .filter(String::isNotBlank),
                        sheetAssetId = cleanId(obj, "sheet_asset_id"),
                        metadata = emptyMap(),
                    )
                    putUnique(
                        target = characters,
                        key = characterId.lowercase(Locale.US),
                        value = entry,
                        kind = "character_id",
                        displayId = characterId,
                    )
                    return@forEach
                }

                val seriesCode = cleanId(obj, "series_code")
                if (seriesCode.isNotBlank()) {
                    val entry = ReferenceSeriesEntry(
                        code = seriesCode,
                        name = canonicalName,
                        franchise = cleanId(obj, "franchise"),
                        aliases = stringList(obj.opt("aliases")),
                    )
                    putUnique(
                        target = series,
                        key = seriesCode.lowercase(Locale.US),
                        value = entry,
                        kind = "series_code",
                        displayId = seriesCode,
                    )
                    return@forEach
                }

                val id = cleanId(obj, "tag_id")
                    .ifBlank { cleanId(obj, "id") }
                    .ifBlank { cleanId(obj, "outfit_id") }
                    .ifBlank { cleanId(obj, "weapon_id") }
                if (id.isBlank()) return@forEach

                val parent = listOf(
                    "parent_tag",
                    "parent_action",
                    "parent_outfit",
                    "parent_weapon",
                )
                    .asSequence()
                    .map { key -> cleanId(obj, key) }
                    .firstOrNull(String::isNotBlank)
                    .orEmpty()

                val entry = ReferenceTagEntry(
                    id = id,
                    name = canonicalName,
                    category = inferCategory(filename, obj, id),
                    parentId = parent,
                    aliases = stringList(obj.opt("aliases")),
                )
                putUnique(
                    target = tags,
                    key = id.lowercase(Locale.US),
                    value = entry,
                    kind = "tag id",
                    displayId = id,
                )
            }
        }

        return ReferenceKnowledgeBundle(
            series = series.values.toList(),
            tags = tags.values.toList(),
            characters = characters.values.toList(),
        )
    }

    private fun collectObjects(raw: String): List<JSONObject> {
        val trimmed = raw.trim().removePrefix("\uFEFF")
        if (trimmed.isBlank()) return emptyList()

        val root: Any = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> JSONObject(trimmed)
            else -> error("Reference Knowledge JSON must start with an object or array.")
        }

        return buildList {
            fun visit(value: Any?) {
                when (value) {
                    is JSONObject -> {
                        if (value.has("canonical_name") || value.has("character_name")) {
                            add(value)
                        } else {
                            value.keys().forEach { key -> visit(value.opt(key)) }
                        }
                    }
                    is JSONArray -> {
                        for (index in 0 until value.length()) visit(value.opt(index))
                    }
                }
            }
            visit(root)
        }
    }

    private fun inferCategory(filename: String, obj: JSONObject, id: String): String {
        cleanString(obj, "attribute").takeIf(String::isNotBlank)?.let { return slug(it) }

        val prefix = id
            .takeWhile(Char::isLetter)
            .uppercase(Locale.US)
        val byPrefix = when (prefix) {
            "AC" -> "action"
            "RT" -> "rating"
            "EN" -> "environment"
            "WE" -> "weather"
            "EX" -> "expression"
            "EY" -> "eye_state"
            "MO" -> "mouth_state"
            "GE" -> "gesture"
            "PO" -> "pose"
            "FR" -> "framing"
            "OR" -> "orientation"
            "CA" -> "camera"
            "LI" -> "lighting"
            "OF" -> "outfit"
            "WP" -> "weapon"
            "SP" -> "species"
            "SA" -> "species_attribute"
            else -> ""
        }
        if (byPrefix.isNotBlank()) return byPrefix

        val lower = filename.lowercase(Locale.US)
        return when {
            "outfit" in lower -> "outfit"
            "weapon" in lower -> "weapon"
            "hair" in lower -> "hair"
            "eye" in lower && "mouth" !in lower -> "eyes"
            "mouth" in lower -> "eye_mouth_state"
            "skin" in lower -> "skin"
            "body" in lower -> "body"
            "age" in lower -> "age"
            "species" in lower -> "species"
            "expression" in lower -> "expression"
            "gesture" in lower -> "gesture"
            "pose" in lower -> "pose"
            "environment" in lower -> "environment"
            "weather" in lower -> "weather"
            "framing" in lower -> "framing"
            "orientation" in lower -> "orientation"
            "camera" in lower -> "camera"
            "lighting" in lower -> "lighting"
            "action" in lower -> "action"
            "rating" in lower -> "rating"
            else -> "tag"
        }
    }

    private fun cleanString(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) return ""
        return when (val value = obj.opt(key)) {
            null, JSONObject.NULL -> ""
            is String -> value.trim()
            is Number, is Boolean -> value.toString().trim()
            else -> ""
        }
    }

    private fun cleanId(obj: JSONObject, key: String): String =
        cleanIdValue(cleanString(obj, key))

    private fun cleanIdValue(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.lowercase(Locale.US) in NULLISH_ID_VALUES) "" else trimmed
    }

    private fun hasMeaningfulValue(obj: JSONObject, key: String): Boolean =
        cleanId(obj, key).isNotBlank()

    private fun slug(value: String): String = value.trim().lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private fun stringList(value: Any?): List<String> {
        val raw = when (value) {
            null, JSONObject.NULL -> emptyList()
            is JSONArray -> (0 until value.length()).map { index -> value.opt(index) }
            is String -> listOf(value)
            else -> emptyList()
        }
        return raw.mapNotNull { item ->
            when (item) {
                null, JSONObject.NULL -> null
                is String -> item.trim().takeIf(String::isNotBlank)
                else -> null
            }
        }.distinct()
    }

    private fun <T> putUnique(
        target: MutableMap<String, T>,
        key: String,
        value: T,
        kind: String,
        displayId: String,
    ) {
        val existing = target[key]
        require(existing == null || existing == value) {
            "Conflicting duplicate $kind '$displayId' in reference Knowledge documents."
        }
        if (existing == null) target[key] = value
    }

    private val NULLISH_ID_VALUES = setOf("null", "none", "nil", "n/a", "na")
}

internal class ReferenceKnowledgeImporter(
    private val knowledgeDatabase: KnowledgeDatabase,
) {
    fun importZip(input: InputStream, sourceName: String): Map<String, Any> {
        val documents = linkedMapOf<String, String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.lowercase(Locale.US).endsWith(".json")) {
                    documents[entry.name.substringAfterLast('/')] = zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
            }
        }
        if (documents.isEmpty()) {
            return mapOf("ok" to false, "message" to "No JSON knowledge files were found in $sourceName.")
        }
        return importDocuments(documents, sourceName)
    }

    fun importDocuments(documents: Map<String, String>, sourceName: String): Map<String, Any> {
        if (documents.isEmpty()) {
            return mapOf("ok" to false, "message" to "No Knowledge JSON documents were supplied.")
        }

        val bundle = ReferenceKnowledgeParser.parseDocuments(documents)
        if (bundle.series.isEmpty() && bundle.tags.isEmpty() && bundle.characters.isEmpty()) {
            return mapOf("ok" to false, "message" to "No supported Knowledge entries were found in $sourceName.")
        }

        return runCatching {
            var ignoredSeriesAliases = 0
            if (bundle.series.isNotEmpty() || bundle.tags.isNotEmpty()) {
                val merge = knowledgeDatabase.mergeReferenceKnowledge(
                    ReferenceKnowledgeBundle(bundle.series, bundle.tags),
                    sourceName,
                )
                ignoredSeriesAliases = merge["series_aliases_ignored"] ?: 0
            }
            if (bundle.characters.isNotEmpty()) {
                knowledgeDatabase.replaceCharacterKnowledge(bundle.characters, sourceName)
            }
            mapOf(
                "ok" to true,
                "message" to "Knowledge JSON imported without replacing unrelated Knowledge.",
                "kind" to "immutable_knowledge_release",
                "source" to sourceName,
                "documents" to documents.size,
                "series_entries" to bundle.series.size,
                "tag_entries" to bundle.tags.size,
                "series_aliases_ignored" to ignoredSeriesAliases,
                "character_entries" to bundle.characters.size,
                "characters_imported" to bundle.characters.size,
                "fusion_modified" to false,
            )
        }.getOrElse { error ->
            mapOf(
                "ok" to false,
                "message" to (error.message ?: error.javaClass.simpleName),
                "kind" to "immutable_knowledge_release",
                "source" to sourceName,
            )
        }
    }
}
