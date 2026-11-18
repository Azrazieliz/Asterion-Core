package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ArtworkVisualPreferenceModelTest {
    private val vivid = listOf(.8, .6, .7, .3, .9, -.2, .5, .1, -.2, .5, .7, -.3)
    private val dark = listOf(-.8, -.3, -.7, -.3, -.6, .5, -.5, -.1, .2, -.5, -.7, .3)

    @Test fun differentVisualPatternsLearnDifferentUserPreferences() {
        var model = ArtworkVisualPreferenceModel.TrainingState()
        repeat(40) {
            model = ArtworkVisualPreferenceModel.update(model, vivid, 38.0, 1)
            model = ArtworkVisualPreferenceModel.update(model, dark, -27.0, 1)
        }
        val brightPrediction = ArtworkVisualPreferenceModel.predict(model, vivid)
        val darkPrediction = ArtworkVisualPreferenceModel.predict(model, dark)
        assertTrue(brightPrediction.adjustment > 5.0)
        assertTrue(darkPrediction.adjustment < -5.0)
        assertTrue(brightPrediction.confidence > 0)
        assertTrue(brightPrediction.confidence <= 1)
        assertTrue(brightPrediction.adjustment > darkPrediction.adjustment + 10)
    }

    @Test fun ratingCorrectionRetractsOldSupervision() {
        var model = ArtworkVisualPreferenceModel.TrainingState()
        repeat(20) { model = ArtworkVisualPreferenceModel.update(model, vivid, 65.0, 1) }
        val before = ArtworkVisualPreferenceModel.predict(model, vivid).adjustment
        repeat(20) { model = ArtworkVisualPreferenceModel.update(model, vivid, 65.0, -1) }
        assertEquals(0, model.examples)
        assertEquals(0.0, ArtworkVisualPreferenceModel.predict(model, vivid).adjustment, 0.0)
        assertTrue(before > 0)
    }

    @Test fun noInventedPersonalizationForUnknownOrSparseVisualEvidence() {
        val model = ArtworkVisualPreferenceModel.TrainingState()
        assertEquals(0.0, ArtworkVisualPreferenceModel.predict(model, vivid).adjustment, 0.0)
        assertFalse(ArtworkVisualPreferenceModel.valid(listOf(0.0)))
        assertFalse(ArtworkVisualPreferenceModel.valid(List(12) { Double.NaN }))
    }

    @Test fun theSolverIsStableForRepeatedNearlyIdenticalImages() {
        var model = ArtworkVisualPreferenceModel.TrainingState()
        repeat(50) { model = ArtworkVisualPreferenceModel.update(model, vivid, 25.0, 1) }
        val pred = ArtworkVisualPreferenceModel.predict(model, vivid)
        assertTrue(pred.adjustment.isFinite())
        assertTrue(abs(pred.adjustment) <= 50.0)
    }
}
