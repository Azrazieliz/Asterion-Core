package com.ailm.android.runtime

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.util.Locale

/** The native Fusion taxonomy editor. Models cannot create entries or allocate IDs. */
internal class FusionTaxonomyEngine(private val database: LocalDatabase) {
    private val canonicalId = Regex("^([A-Z]{2})([0-9]{2,})$")

    data class Candidate(
        val id: String,
        val name: String,
        val category: String,
        val aliases: List<String> = emptyList(),
    ) {
        val family: String get() = id.take(2)
    }

    @Volatile private var cachedCandidates: List<Candidate>? = null
    private val cacheLock = Any()

    /** Cache the immutable per-release taxonomy, never per-image full scans. */
    fun invalidateCache() {
        synchronized(cacheLock) { cachedCandidates = null }
    }

    fun allTags(db: SQLiteDatabase = database.readableDatabase, refresh: Boolean = false): List<Candidate> {
        if (!refresh) cachedCandidates?.let { return it }
        val found = linkedMapOf<String, Candidate>()
        db.rawQuery("SELECT tag_id, canonical_name, category FROM fusion_tags ORDER BY tag_id", emptyArray())
            .use { rows ->
                while (rows.moveToNext()) {
                    val id = rows.getString(0).orEmpty().uppercase(Locale.ROOT)
                    if (!canonicalId.matches(id)) continue
                    found[id] = Candidate(id, rows.getString(1).orEmpty(), rows.getString(2).orEmpty())
                }
            }
        val aliases = mutableMapOf<String, MutableList<String>>()
        db.rawQuery("SELECT tag_id, alias_value FROM fusion_tag_aliases", emptyArray())
            .use { rows ->
                while (rows.moveToNext()) {
                    val id = rows.getString(0).orEmpty().uppercase(Locale.ROOT)
                    if (id in found) aliases.getOrPut(id) { mutableListOf() } += rows.getString(1).orEmpty()
                }
            }
        val snapshot = found.values.map { it.copy(aliases = aliases[it.id].orEmpty().distinct()) }
        if (!refresh) {
            synchronized(cacheLock) { cachedCandidates = snapshot }
        }
        return snapshot
    }

    fun families(input: String = "", limit: Int = 32): List<Map<String, Any>> {
        val query = normalize(input)
        val tokens = query.split(' ').filter { it.length >= 2 }
        return allTags().groupBy(Candidate::family).map { (code, entries) ->
            val category = entries.map(Candidate::category).filter(String::isNotBlank)
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: code
            val score = entries.maxOfOrNull { tag ->
                (listOf(tag.name, tag.category) + tag.aliases).maxOfOrNull { text ->
                    val value = normalize(text)
                    when {
                        query.isEmpty() -> 0
                        value == query -> 16
                        value.startsWith(query) -> 12
                        query in value -> 9
                        tokens.isNotEmpty() && tokens.all { it in value } -> 7
                        else -> tokens.count { it in value }
                    }
                } ?: 0
            } ?: 0
            mapOf<String, Any>("code" to code, "name" to category, "count" to entries.size,
                "examples" to entries.take(4).map(Candidate::name), "score" to score)
        }.sortedWith(compareByDescending<Map<String, Any>> { it["score"] as Int }
            .thenByDescending { it["count"] as Int }.thenBy { it["code"].toString() })
            .take(limit.coerceIn(1, 100))
    }

    fun matches(input: String, limit: Int = 12): List<Map<String, Any>> {
        val query = normalize(input)
        if (query.isEmpty()) return emptyList()
        return allTags().mapNotNull { tag ->
            val descriptions = listOf(tag.name) + tag.aliases
            val score = when {
                descriptions.any { normalize(it) == query } -> 3
                descriptions.any { normalize(it).startsWith(query) } -> 2
                descriptions.any { query in normalize(it) } -> 1
                else -> 0
            }
            if (score == 0) null else mapOf<String, Any>(
                "id" to tag.id, "name" to tag.name, "family" to tag.family,
                "category" to tag.category, "score" to score,
            )
        }.sortedWith(compareByDescending<Map<String, Any>> { it["score"] as Int }
            .thenBy { it["name"].toString() })
            .take(limit.coerceIn(1, 100))
    }

    /** Exact or unique alias match only. Never invent fuzzy canonical IDs. */
    fun resolveByFamily(rawFamily: String, rawLabel: String): Candidate? {
        val family = rawFamily.trim().uppercase(Locale.ROOT)
        if (!Regex("^[A-Z]{2}$").matches(family)) return null
        val label = normalize(rawLabel)
        if (label.isBlank()) return null
        // Scope every model-time lookup to one indexed Fusion family instead
        // of materializing the entire taxonomy for every single observation.
        val db = database.readableDatabase
        val entries = linkedMapOf<String, Candidate>()
        db.rawQuery(
            "SELECT tag_id,canonical_name,category FROM fusion_tags " +
                "WHERE substr(tag_id,1,2)=? ORDER BY tag_id",
            arrayOf(family),
        ).use { rows ->
            while (rows.moveToNext()) {
                val id = rows.getString(0).orEmpty().uppercase(Locale.ROOT)
                if (canonicalId.matches(id)) entries[id] = Candidate(
                    id, rows.getString(1).orEmpty(), rows.getString(2).orEmpty(),
                )
            }
        }
        val aliases = mutableMapOf<String, MutableList<String>>()
        db.rawQuery(
            "SELECT a.tag_id,a.alias_value FROM fusion_tag_aliases a " +
                "JOIN fusion_tags t ON t.tag_id=a.tag_id WHERE substr(t.tag_id,1,2)=?",
            arrayOf(family),
        ).use { rows ->
            while (rows.moveToNext()) {
                val id = rows.getString(0).orEmpty().uppercase(Locale.ROOT)
                if (id in entries) aliases.getOrPut(id) { mutableListOf() } +=
                    rows.getString(1).orEmpty()
            }
        }
        val candidates = entries.values.map {
            it.copy(aliases = aliases[it.id].orEmpty())
        }
        fun exact(value: String): Candidate? = candidates.filter { tag ->
            (listOf(tag.id, tag.name) + tag.aliases).any { normalize(it) == value }
        }.singleOrNull()
        exact(label)?.let { return it }
        val suffixes = when (family) {
            "HC", "HL", "HS" -> listOf(" hair", " hairstyle", " hair style")
            "EC", "ET" -> listOf(" eye", " eyes", " eye color", " eye colour")
            "SC" -> listOf(" skin", " skin color", " skin colour")
            "BH" -> listOf(" height")
            "BS" -> listOf(" breasts", " breast size")
            else -> emptyList()
        }
        suffixes.forEach { suffix ->
            if (label.endsWith(suffix)) exact(label.removeSuffix(suffix).trim())?.let { return it }
            else exact(label + suffix)?.let { return it }
        }
        return null
    }

    fun createTag(input: String, rawFamily: String): Map<String, Any> {
        val name = input.trim().replace(Regex("\\s+"), " ")
        val family = rawFamily.trim().uppercase(Locale.ROOT)
        require(name.length in 2..100 && '\n' !in name && '\r' !in name) {
            "Tag name must be 2–100 characters."
        }
        require(Regex("^[A-Z]{2}$").matches(family)) { "Select a valid tag category." }
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val tags = allTags(db, refresh = true)
            val familyTags = tags.filter { it.family == family }
            require(familyTags.isNotEmpty()) { "The selected category is not available in Fusion." }
            val existing = familyTags.firstOrNull { tag ->
                (listOf(tag.name) + tag.aliases).any { normalize(it) == normalize(name) }
            }
            if (existing != null) {
                db.setTransactionSuccessful()
                return mapOf("ok" to false, "status" to "already_exists",
                    "id" to existing.id, "message" to ("Already exists: " + existing.name + " (" + existing.id + ")."))
            }
            val next = familyTags.mapNotNull {
                canonicalId.matchEntire(it.id)?.groupValues?.get(2)?.toIntOrNull()
            }.maxOrNull()?.plus(1) ?: 1
            val id = family + next.toString().padStart(maxOf(3, next.toString().length), '0')
            val now = System.currentTimeMillis()
            val category = familyTags.first().category.ifBlank { family }
            val values = ContentValues().apply {
                put("tag_id", id)
                put("canonical_name", name)
                put("category", category)
                putNull("parent_tag_id")
                put("metadata_json", JSONObject(mapOf("source" to "native_editor",
                    "user_approved" to true)).toString())
                put("created_at_ms", now)
                put("updated_at_ms", now)
            }
            check(db.insertOrThrow("fusion_tags", null, values) != -1L) { "Failed to create tag." }
            db.setTransactionSuccessful()
            invalidateCache()
            return mapOf("ok" to true, "status" to "created", "id" to id,
                "name" to name, "category" to category, "message" to ("Added " + name + "."))
        } finally {
            db.endTransaction()
        }
    }

    private fun normalize(text: String): String = text.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
