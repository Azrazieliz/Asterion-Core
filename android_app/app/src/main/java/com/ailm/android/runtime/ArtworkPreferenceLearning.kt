package com.ailm.android.runtime

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Incremental preference learning from explicit personal stars and verified
 * Fusion visual features. Per-image evidence is retractable and persisted
 * without changing model weights, original files or explicit user ratings.
 */
internal class ArtworkPreferenceLearning(private val prefs: SharedPreferences) {
    private val canonical = Regex("^[A-Z]{2}[0-9]{2,}(?:-[0-9]+)*$")
    fun adjustment(features: Collection<String>): Double {
        val votes = features.asSequence().filter(canonical::matches)
            .distinct().take(40).mapNotNull { id ->
                val count = prefs.getInt("count_" + id, 0)
                if (count <= 0) null else
                    prefs.getFloat("sum_" + id, 0f).toDouble() / (count + 4.0)
            }.toList()
        return if (votes.isEmpty()) 0.0 else votes.average().coerceIn(-1.0, 1.0)
    }
    fun examples(): Int = prefs.getInt("examples", 0).coerceAtLeast(0)
    fun revise(imageId: Int, stars: Int, baseline: Int?, features: Collection<String>) {
        require(stars in 0..5)
        val key = "image_" + imageId
        val old = prefs.getString(key, null)?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }
        val corrections = linkedMapOf<String, Pair<Int, Double>>()
        fun change(ids: List<String>, amount: Int, residual: Double) {
            for (id in ids.filter(canonical::matches).distinct()) {
                val before = corrections[id] ?: (0 to 0.0)
                corrections[id] = (before.first + amount) to
                    (before.second + amount * residual)
            }
        }
        old?.let { prior ->
            val list = prior.optJSONArray("features") ?: JSONArray()
            change((0 until list.length()).map(list::optString), -1,
                (prior.optInt("stars") - prior.optInt("baseline")).toDouble())
        }
        val current = features.filter(canonical::matches).distinct().take(40)
        val valid = stars in 1..5 && baseline != null && baseline in 1..5 &&
            current.isNotEmpty()
        if (valid) change(current, 1, (stars - baseline!!).toDouble())
        val edit = prefs.edit()
        for ((id, delta) in corrections) {
            val count = (prefs.getInt("count_" + id, 0) + delta.first).coerceAtLeast(0)
            val sum = prefs.getFloat("sum_" + id, 0f).toDouble() + delta.second
            edit.putInt("count_" + id, count)
                .putFloat("sum_" + id, if (count == 0) 0f else sum.toFloat())
        }
        if (valid) edit.putString(key, JSONObject()
            .put("stars", stars).put("baseline", baseline)
            .put("features", JSONArray(current)).toString())
        else edit.remove(key)
        edit.putInt("examples", (examples() + (if (valid) 1 else 0) -
            (if (old == null) 0 else 1)).coerceAtLeast(0))
            .apply()
    }
}
