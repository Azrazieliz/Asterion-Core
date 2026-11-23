package com.ailm.android.runtime

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Real, local preference supervision from the user's explicit 0..100 scores.
 *
 * Two complementary learners:
 * - Retractable per-Fusion-feature residuals (subject/theme preferences).
 * - Retractable ridge regression on measurable *image pixel features*
 *   (lightness, palette, detail and spatial composition).
 *
 * Neither changes canonical IDs, RT classifications, originals or the native
 * model's weights. Prediction includes evidence and sample-size confidence.
 */
internal class ArtworkPreferenceLearning(private val prefs: SharedPreferences) {
    private val canonical = Regex("^[A-Z]{2}[0-9]{2,}(?:-[0-9]+)*$")
    private val modelKey = "visual_ridge_model_v1"

    data class Evidence(
        val points: Double,
        val matchingTags: Int,
        val establishedTags: Int,
        val maximumTagVotes: Int,
    )
    data class VisualEvidence(
        val points: Double,
        val confidence: Double,
        val examples: Int,
    )

    fun evidence(features: Collection<String>): Evidence {
        val votes = features.asSequence().filter(canonical::matches)
            .distinct().mapNotNull { id ->
                val count = prefs.getInt("count_" + id, 0)
                if (count <= 0) null else count to
                    (prefs.getFloat("sum_" + id, 0f).toDouble() / (count + 4.0))
            }.toList()
        if (votes.isEmpty()) return Evidence(0.0, 0, 0, 0)
        return Evidence(
            points = votes.map { it.second }.average().coerceIn(-15.0, 15.0),
            matchingTags = votes.size,
            establishedTags = votes.count { it.first >= 3 },
            maximumTagVotes = votes.maxOf { it.first },
        )
    }

    fun adjustment(features: Collection<String>): Double = evidence(features).points
    fun examples(): Int = prefs.getInt("examples", 0).coerceAtLeast(0)

    fun visualEvidence(features: List<Double>): VisualEvidence {
        val model = readVisualModel()
        val prediction = ArtworkVisualPreferenceModel.predict(model, features)
        return VisualEvidence(
            points = prediction.adjustment,
            confidence = prediction.confidence,
            examples = prediction.trainingExamples,
        )
    }

    private fun readJsonList(array: JSONArray?): List<Double> {
        if (array == null) return emptyList()
        val result = (0 until array.length()).map { index ->
            array.optDouble(index, Double.NaN)
        }
        return result.takeIf { it.all(Double::isFinite) }.orEmpty()
    }

    private fun readVisualModel(): ArtworkVisualPreferenceModel.TrainingState {
        val raw = prefs.getString(modelKey, null) ?: return ArtworkVisualPreferenceModel.TrainingState()
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return ArtworkVisualPreferenceModel.TrainingState()
        val a = readJsonList(json.optJSONArray("gram"))
        val b = readJsonList(json.optJSONArray("target"))
        val c = readJsonList(json.optJSONArray("coefficients"))
        val n = ArtworkVisualPreferenceModel.WEIGHTS
        if (a.size != n * n || b.size != n || c.size != n) {
            return ArtworkVisualPreferenceModel.TrainingState()
        }
        return ArtworkVisualPreferenceModel.TrainingState(
            examples = json.optInt("examples", 0).coerceAtLeast(0),
            gram = a.toDoubleArray(),
            target = b.toDoubleArray(),
            coefficients = c.toDoubleArray(),
        )
    }

    private fun visualModelJson(state: ArtworkVisualPreferenceModel.TrainingState): String =
        JSONObject()
            .put("examples", state.examples)
            .put("gram", JSONArray(state.gram.toList()))
            .put("target", JSONArray(state.target.toList()))
            .put("coefficients", JSONArray(state.coefficients.toList()))
            .toString()

    /**
     * Every image has exactly one retractable contribution: updating a rating,
     * clearing it or correcting its tags cannot double-count manual evidence.
     * Persist after recomputing the small visual regressor, not per gallery scan.
     */
    fun revise(
        imageId: Int,
        stars: Int,
        baseline: Int?,
        features: Collection<String>,
        visualFeatures: List<Double> = emptyList(),
    ) = synchronized(prefs) {
        require(imageId > 0 && stars in -1..100)
        val key = "image_$imageId"
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

        val oldManual = old?.optInt("stars", -1) ?: -1
        val oldBase = old?.optInt("baseline", -1) ?: -1
        val oldDifference = (oldManual - oldBase).toDouble()
        val oldTags = old?.optJSONArray("features")?.let { list ->
            (0 until list.length()).map(list::optString)
        }.orEmpty()
        val oldVisual = readJsonList(old?.optJSONArray("visual"))
        val oldLabelValid = oldManual in 0..100 && oldBase in 0..100
        if (oldLabelValid && oldTags.isNotEmpty()) change(oldTags, -1, oldDifference)

        val validLabel = stars in 0..100 && baseline != null && baseline in 0..100
        val currentTags = features.filter(canonical::matches).distinct().sorted()
        val currentVisual = when {
            ArtworkVisualPreferenceModel.valid(visualFeatures) -> visualFeatures
            ArtworkVisualPreferenceModel.valid(oldVisual) -> oldVisual
            else -> emptyList()
        }
        val tagValid = validLabel && currentTags.isNotEmpty()
        val visualValid = validLabel && ArtworkVisualPreferenceModel.valid(currentVisual)
        if (old != null && validLabel && stars == oldManual && baseline == oldBase &&
            oldTags == currentTags && oldVisual == currentVisual) return@synchronized
        if (old == null && !tagValid && !visualValid) return@synchronized
        if (tagValid) change(currentTags, 1, (stars - baseline!!).toDouble())

        var model = readVisualModel()
        if (oldLabelValid && ArtworkVisualPreferenceModel.valid(oldVisual)) {
            model = ArtworkVisualPreferenceModel.update(
                model, oldVisual, oldDifference, -1)
        }
        if (visualValid) {
            model = ArtworkVisualPreferenceModel.update(
                model, currentVisual, (stars - baseline!!).toDouble(), 1)
        }
        val edit = prefs.edit()
        for ((id, delta) in corrections) {
            val count = (prefs.getInt("count_$id", 0) + delta.first).coerceAtLeast(0)
            val sum = prefs.getFloat("sum_$id", 0f).toDouble() + delta.second
            edit.putInt("count_$id", count)
                .putFloat("sum_$id", if (count == 0) 0f else sum.toFloat())
        }
        val currentValid = tagValid || visualValid
        if (currentValid) {
            edit.putString(key, JSONObject()
                .put("stars", stars)
                .put("baseline", baseline)
                .put("features", JSONArray(if (tagValid) currentTags else emptyList<String>()))
                .put("visual", JSONArray(if (visualValid) currentVisual else emptyList<Double>()))
                .toString())
        } else edit.remove(key)
        edit.putString(modelKey, visualModelJson(model))
        edit.putInt("examples", (examples() +
            (if (currentValid) 1 else 0) -
            (if (old == null) 0 else 1)).coerceAtLeast(0))
            .apply()
    }
}
