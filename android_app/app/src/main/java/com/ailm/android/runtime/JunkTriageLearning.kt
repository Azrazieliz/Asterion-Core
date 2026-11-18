package com.ailm.android.runtime

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Reversible feedback calibration for explicit Junk triage decisions.
 * A proposal is never auto-routed until repeated *distinct* owner decisions
 * support its evidence kind; subsequent rejections can disable automation.
 *
 * This is confidence calibration of verified evidence rules, not a fabricated
 * trained vision classifier. Per-image decisions can be revised or undone.
 */
internal class JunkTriageLearning(private val prefs: SharedPreferences) {
    companion object {
        const val MIN_CONFIRMED_EXAMPLES = 6
        private val kinds = setOf("promotion", "junk", "uncertain_identity")
    }

    data class Evidence(val confirmed: Int, val rejected: Int) {
        val total: Int get() = confirmed + rejected
        val conservativePrecision: Double get() =
            (confirmed + 1.0) / (total + 2.0)
        val learned: Boolean get() =
            confirmed >= MIN_CONFIRMED_EXAMPLES && conservativePrecision >= 0.82
    }

    @Synchronized
    fun evidence(kind: String): Evidence =
        if (kind !in kinds) Evidence(0, 0)
        else Evidence(prefs.getInt("yes_$kind", 0).coerceAtLeast(0),
            prefs.getInt("no_$kind", 0).coerceAtLeast(0))

    fun learnedKinds(): Set<String> = kinds.filterTo(linkedSetOf()) { evidence(it).learned }

    /** Last correction supersedes prior evidence from the same image. */
    @Synchronized
    fun revise(imageId: Int, kind: String, confirmed: Boolean): Boolean {
        if (imageId <= 0 || kind !in kinds) return false
        val key = "image_$imageId"
        val prior = prefs.getString(key, null)?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }
        val oldKind = prior?.optString("kind", "").orEmpty()
        val oldAccepted = prior?.optBoolean("confirmed", false) ?: false
        val edit = prefs.edit()
        if (oldKind in kinds) {
            val oldKey = (if (oldAccepted) "yes_" else "no_") + oldKind
            edit.putInt(oldKey, (prefs.getInt(oldKey, 0) - 1).coerceAtLeast(0))
        }
        val newKey = (if (confirmed) "yes_" else "no_") + kind
        edit.putInt(newKey,
            (prefs.getInt(newKey, 0) + 1 -
                (if (oldKind == kind && oldAccepted == confirmed) 1 else 0)).coerceAtLeast(0))
        // If previous and next evidence refer to same counter, the subtraction
        // above needs a single net-zero write, not two contradictory edits.
        if (oldKind == kind && oldAccepted == confirmed) {
            edit.putInt(newKey, prefs.getInt(newKey, 0).coerceAtLeast(0))
        }
        edit.putString(key, JSONObject().put("kind", kind)
            .put("confirmed", confirmed).toString())
        return edit.commit()
    }

    /** Used when the user undoes the last Review disposition. */
    @Synchronized
    fun forget(imageId: Int): Boolean {
        val key = "image_$imageId"
        val prior = prefs.getString(key, null)?.let {
            runCatching { JSONObject(it) }.getOrNull()
        } ?: return false
        val kind = prior.optString("kind", "")
        if (kind !in kinds) return false
        val counter = (if (prior.optBoolean("confirmed")) "yes_" else "no_") + kind
        return prefs.edit().putInt(
            counter, (prefs.getInt(counter, 0) - 1).coerceAtLeast(0)
        ).remove(key).commit()
    }
}
