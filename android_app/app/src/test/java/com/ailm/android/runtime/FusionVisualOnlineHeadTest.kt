package com.ailm.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behavior over actual frozen-feature vectors, never tag names or WD labels. */
class FusionVisualOnlineHeadTest {
    @Test fun featureParsingPreservesEveryCoordinateOrAbstains() {
        val pixels = List(768) { it.toDouble() }
        assertEquals(pixels, FusionVisualLinearMath.readFeature(pixels))
        assertTrue(FusionVisualLinearMath.readFeature(listOf(null) + pixels) == null)
        assertTrue(FusionVisualLinearMath.readFeature(pixels.drop(1) + Double.NaN) == null)
    }
    private fun pixel(positive: Boolean, seed: Int): FloatArray =
        FloatArray(FusionVisualLinearMath.FEATURE_WIDTH).also { features ->
            features[0] = if (positive) 3f else -3f
            features[1] = if (positive) -1.0f else 1.0f
            features[2] = (seed % 7).toFloat() * 0.015f
        }

    private fun rows(): List<FusionVisualLinearMath.Example> = buildList {
        for (index in 0 until 20) {
            add(FusionVisualLinearMath.Example(
                source = "positive-source-" + index, positive = true,
                pixels = pixel(true, index), subjectIndex = index % 2))
            add(FusionVisualLinearMath.Example(
                source = "negative-source-" + index, positive = false,
                pixels = pixel(false, index), subjectIndex = index % 2))
        }
    }

    @Test fun updatesActualVisualWeightsAndScoresUnmatchedFusionId() {
        val data = rows()
        assertNull(FusionVisualLinearMath.fit(data.take(12)))
        val model = FusionVisualLinearMath.fit(data)
        assertNotNull(model)
        val head = model!!
        assertTrue(head.ready)
        assertTrue(head.precision >= 0.90)
        assertTrue(head.recall >= 0.60)
        val pos = FusionVisualLinearMath.predict(head.weights, head.bias, pixel(true, 200))!!
        val neg = FusionVisualLinearMath.predict(head.weights, head.bias, pixel(false, 200))!!
        assertTrue("trained scores must depend on pixels", pos > neg + 0.40)
        val shared = FusionVisualLinearMath.normalise(pixel(true, 200))!!
        assertEquals(pos, FusionVisualLinearMath.predictNormalised(head.weights, head.bias, shared)!!, 0.0)
        assertTrue(pos >= 0.75)
        assertTrue(neg < 0.75)
        assertEquals(head.precision, FusionVisualLinearMath.fit(data.reversed())!!.precision, 0.0)
    }

    @Test fun reversalMatchesRetrainingFromTheCurrentLedger() {
        val initial = rows()
        val adjusted = initial.drop(1)
        val first = FusionVisualLinearMath.fit(adjusted)!!
        val reloaded = FusionVisualLinearMath.fit(adjusted.reversed())!!
        assertEquals(first.bias, reloaded.bias, 1e-10)
        for (index in first.weights.indices) {
            assertEquals(first.weights[index], reloaded.weights[index], 1e-6f)
        }
        assertTrue(first.ready)
    }

    @Test fun oneArtworkSubjectsCanHaveDifferentVisualLabelsWithoutSourceLeakage() {
        val base = rows()
        val sameImageTwoCharacters = listOf(
            FusionVisualLinearMath.Example(
                "shared-source", true, pixel(true, 100), subjectIndex = 0),
            FusionVisualLinearMath.Example(
                "shared-source", false, pixel(false, 100), subjectIndex = 1),
        )
        val head = FusionVisualLinearMath.fit(base + sameImageTwoCharacters)
        assertNotNull(head)
        assertTrue(head!!.ready)
        assertTrue(FusionVisualLinearMath.predict(head.weights, head.bias, pixel(true, 101))!! >
            FusionVisualLinearMath.predict(head.weights, head.bias, pixel(false, 101))!!)
        // Exact duplicate review votes are never an additional training sample.
        val repeated = FusionVisualLinearMath.fit(base + base[0])!!
        val original = FusionVisualLinearMath.fit(base)!!
        assertTrue(original.weights.contentEquals(repeated.weights))
        assertEquals(original.positives, repeated.positives)
        val conflict = base[0].copy(positive = !base[0].positive)
        val withoutConflict = FusionVisualLinearMath.fit(base.drop(1))!!
        assertTrue(withoutConflict.weights.contentEquals(FusionVisualLinearMath.fit(base + conflict)!!.weights))
    }

    @Test fun invalidOrUnsupportedFeatureVectorsCannotProducePredictions() {
        val head = FusionVisualLinearMath.fit(rows())!!
        assertNull(FusionVisualLinearMath.predict(head.weights, head.bias, FloatArray(15)))
        assertNull(FusionVisualLinearMath.predict(head.weights, head.bias,
            FloatArray(FusionVisualLinearMath.FEATURE_WIDTH) { Float.NaN }))
        assertNull(FusionVisualLinearMath.fit(rows().take(10)))
        assertFalse(head.weights.all { it == 0f })
    }
}
