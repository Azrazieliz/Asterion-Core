package com.ailm.android.runtime

import android.content.SharedPreferences
import java.util.Locale

/**
 * Tag-confidence calibration from explicit user corrections, not inferred
 * training labels. Changes to a correction retract the earlier vote.
 * Never invents a label the fixed-head classifier did not actually score.
 */
/** Repeated corrections supply a bounded, reversible online score offset.
 * The fixed-head classifier still supplies the visual evidence. */
internal object TagFeedbackCalibration {
    fun delta(positives: Int, negatives: Int): Double {
        val total = positives.coerceAtLeast(0) + negatives.coerceAtLeast(0)
        if (total < 3) return 0.0
        return (0.16 * (positives - negatives).toDouble() / (total + 4.0))
            .coerceIn(-0.12, 0.12)
    }
}

internal class NativeTagFeedbackLearning(private val prefs: SharedPreferences) {
    private val canonical = Regex("^[A-Z]{2}[0-9]{2,}(?:-[0-9]+)*$")
    fun revise(imageId: Int, tag: String, include: Boolean) {
        val id = tag.trim().uppercase(Locale.ROOT)
        if (imageId <= 0 || !canonical.matches(id)) return
        val key = "decision_" + imageId + "_" + id
        val next = if (include) "positive" else "negative"
        val old = prefs.getString(key, null)
        if (old == next) return
        var positive = prefs.getInt("positive_" + id, 0)
        var negative = prefs.getInt("negative_" + id, 0)
        if (old == "positive") positive--
        if (old == "negative") negative--
        if (include) positive++ else negative++
        prefs.edit().putInt("positive_" + id, positive.coerceAtLeast(0))
            .putInt("negative_" + id, negative.coerceAtLeast(0))
            .putString(key, next).apply()
    }

    fun calibrate(result: Map<String, Any>, minimumScore: Double): Map<String, Any> {
        if (result["source"] != "native_classifier") return result
        val saved = mutableListOf<Map<String, Any>>()
        val deferred = (result["deferred_scored_tags"] as? List<*>)
            .orEmpty().mapNotNull { it as? Map<String, Any> }.toMutableList()
        for (raw in (result["scored_tags"] as? List<*>).orEmpty()) {
            val row = raw as? Map<*, *> ?: continue
            val id = row["tag"]?.toString()?.uppercase(Locale.ROOT).orEmpty()
            val score = (row["score"] as? Number)?.toDouble() ?: continue
            if (!canonical.matches(id) || !score.isFinite()) continue
            val plus = prefs.getInt("positive_" + id, 0).coerceAtLeast(0)
            val minus = prefs.getInt("negative_" + id, 0).coerceAtLeast(0)
            val delta = TagFeedbackCalibration.delta(plus, minus)
            val adjusted = (score + delta).coerceIn(0.0, 1.0)
            if (adjusted >= minimumScore) {
                saved += mapOf("tag" to id, "score" to adjusted)
            } else {
                deferred += mapOf("tag" to id, "score" to score,
                    "reason" to "corrected_negative_evidence_review")
            }
        }
        // A positive manual correction cannot conjure an unsupported label.
        // Admit a near-threshold prediction only when its original native
        // primary-crop score is real AND repeated independent corrections
        // support that exact canonical ID. Never apply this to SX, species,
        // clothing, content rating, or whole-image observations.
        val recovered = mutableListOf<Map<String, Any>>()
        if (result["subject_scoped"] == true) {
            val ids = saved.map { it.getValue("tag").toString() }.toMutableSet()
            // Independent HC/EC observations may coexist on the SAME verified
            // subject (multicolour hair, heterochromia). Other appearance
            // families must not gain competing alternatives from feedback.
            val exclusiveRecoveryFamilies = CharacterProfileCompleteness.exclusiveFamilies
            val occupiedFamilies = ids.map { it.take(2) }
                .filter { it in exclusiveRecoveryFamilies }.toMutableSet()
            val allowed = setOf("HC", "HL", "HS", "EC", "ET", "SC",
                "BH", "BT", "BS", "BY", "AG")
            val candidates = (result["near_threshold_scored_tags"] as? List<*>)
                .orEmpty().mapNotNull { raw ->
                    val row = raw as? Map<*, *> ?: return@mapNotNull null
                    val id = row["tag"]?.toString()?.uppercase(Locale.ROOT).orEmpty()
                    val score = (row["score"] as? Number)?.toDouble()
                        ?: return@mapNotNull null
                    if (!canonical.matches(id) || id.take(2) !in allowed ||
                        !score.isFinite() || score !in 0.0..1.0) null
                    else id to score
                }.sortedWith(compareByDescending<Pair<String, Double>> { it.second }
                    .thenBy { it.first })
            for ((id, score) in candidates) {
                val family = id.take(2)
                if (id in ids || family in occupiedFamilies) continue
                val plus = prefs.getInt("positive_" + id, 0).coerceAtLeast(0)
                val minus = prefs.getInt("negative_" + id, 0).coerceAtLeast(0)
                if (plus < 3 || plus <= minus) continue
                val adjusted = (score + TagFeedbackCalibration.delta(plus, minus))
                    .coerceIn(0.0, 1.0)
                if (adjusted >= minimumScore) {
                    val evidence = mapOf<String, Any>("tag" to id, "score" to adjusted)
                    saved += evidence
                    recovered += evidence
                    ids += id
                    if (family in exclusiveRecoveryFamilies) occupiedFamilies += family
                }
            }
        }
        val perFamily = saved.groupingBy { it.getValue("tag").toString().take(2) }.eachCount()
        val deferredPerFamily = deferred.groupingBy {
            it["tag"]?.toString()?.take(2).orEmpty()
        }.eachCount()
        val priorCoverage = (result["family_coverage"] as? List<*>)
            .orEmpty().mapNotNull { it as? Map<String, Any> }
        val correctedCoverage = priorCoverage.map { family ->
            val id = family["family"]?.toString().orEmpty()
            val accepted = perFamily[id] ?: 0
            val deferredCount = deferredPerFamily[id] ?: 0
            val previousStatus = family["status"]?.toString().orEmpty()
            val status = when {
                accepted > 0 && previousStatus in setOf(
                    "evaluated_no_confident_match", "deferred_review",
                ) -> "accepted"
                accepted == 0 && previousStatus == "accepted" ->
                    if (deferredCount > 0) "deferred_review" else "evaluated_no_confident_match"
                else -> previousStatus
            }
            family + mapOf(
                "accepted_count" to accepted,
                "deferred_count" to deferredCount,
                "status" to status,
            )
        }
        return result + mapOf(
            "family_coverage" to correctedCoverage,
            "tags" to saved.map { it.getValue("tag") },
            "scored_tags" to saved,
            "accepted_count" to saved.size,
            "feedback_recovered_count" to recovered.size,
            "deferred_scored_tags" to deferred,
            "deferred_count" to deferred.size,
            // Expose the actual post-calibration admission threshold, not the
            // lower native candidate-collection floor from the mapper.
            "minimum_score" to minimumScore,
        )
    }
}
