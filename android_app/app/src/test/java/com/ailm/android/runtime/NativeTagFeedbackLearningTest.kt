package com.ailm.android.runtime

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercising real Android SharedPreferences calls without device-only state. */
class NativeTagFeedbackLearningTest {
    private fun preferences(): SharedPreferences {
        val values = mutableMapOf<String, Any>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putInt", "putString" -> {
                    values[args!![0] as String] = args[1]!!
                    editor
                }
                "apply" -> null
                "commit" -> true
                else -> error("Unexpected editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getInt" -> values[args!![0] as String] as? Int ?: args[1] as Int
                "getString" -> values[args!![0] as String] as? String ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preferences call: ${method.name}")
            }
        } as SharedPreferences
    }

    private fun original(
        scored: List<Map<String, Any>>,
        near: List<Map<String, Any>> = emptyList(),
        scoped: Boolean = true,
        status: String = "evaluated_no_confident_match",
    ) = mapOf<String, Any>(
        "source" to "native_classifier",
        "subject_scoped" to scoped,
        "scored_tags" to scored,
        "near_threshold_scored_tags" to near,
        "deferred_scored_tags" to emptyList<Map<String, Any>>(),
        "family_coverage" to listOf(
            mapOf<String, Any>(
                "family" to "HL", "status" to status,
                "accepted_count" to scored.size, "deferred_count" to 0,
            ),
        ),
    )

    private fun evidence(tag: String, score: Double) =
        mapOf<String, Any>("tag" to tag, "score" to score)

    @Test fun learnedRecoveryUpdatesFamilyReceiptAndKeepsOnlyStrongestExclusiveCandidate() {
        val learner = NativeTagFeedbackLearning(preferences())
        for (imageId in 1..3) {
            learner.revise(imageId, "HL001", true)
            learner.revise(imageId, "HL002", true)
        }
        val result = learner.calibrate(original(
            scored = emptyList(),
            // Deliberately supplied out of order: choose by native evidence.
            near = listOf(evidence("HL002", .35), evidence("HL001", .36)),
        ), .40)
        assertEquals(listOf("HL001"), result["tags"])
        assertEquals(1, result["feedback_recovered_count"])
        @Suppress("UNCHECKED_CAST")
        val coverage = result["family_coverage"] as List<Map<String, Any>>
        assertEquals("accepted", coverage.single()["status"])
        assertEquals(1, coverage.single()["accepted_count"])
        assertEquals(0, coverage.single()["deferred_count"])
    }

    @Test fun negativeCorrectionsRetractWeakEvidenceAndRepairReviewStatus() {
        val learner = NativeTagFeedbackLearning(preferences())
        for (imageId in 1..3) learner.revise(imageId, "HL001", false)
        val result = learner.calibrate(original(
            scored = listOf(evidence("HL001", .41)),
            status = "accepted",
        ), .40)
        assertEquals(emptyList<String>(), result["tags"])
        @Suppress("UNCHECKED_CAST")
        val coverage = result["family_coverage"] as List<Map<String, Any>>
        assertEquals("deferred_review", coverage.single()["status"])
        assertEquals(1, coverage.single()["deferred_count"])
        assertEquals(0, coverage.single()["accepted_count"])
    }

    @Test fun wholeImageEvidenceCannotBecomePrimaryCharacterLearnedObservation() {
        val learner = NativeTagFeedbackLearning(preferences())
        for (imageId in 1..3) learner.revise(imageId, "HL001", true)
        val result = learner.calibrate(original(
            scored = emptyList(),
            near = listOf(evidence("HL001", .39)),
            scoped = false,
        ), .40)
        assertEquals(emptyList<String>(), result["tags"])
        assertEquals(0, result["feedback_recovered_count"])
    }

    @Test fun changingAnEarlierCorrectionRetractsItsVote() {
        val prefs = preferences()
        val learner = NativeTagFeedbackLearning(prefs)
        for (imageId in 1..3) learner.revise(imageId, "HL001", true)
        learner.revise(3, "HL001", false)
        val result = learner.calibrate(original(
            scored = emptyList(),
            near = listOf(evidence("HL001", .39)),
        ), .40)
        assertEquals(emptyList<String>(), result["tags"])
        assertFalse((result["tags"] as List<*>).contains("HL001"))
        assertTrue(TagFeedbackCalibration.delta(2, 1) == 0.0)
    }
}
