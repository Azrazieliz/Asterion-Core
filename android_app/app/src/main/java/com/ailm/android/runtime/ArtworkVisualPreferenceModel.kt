package com.ailm.android.runtime

import kotlin.math.abs
import kotlin.math.max

/**
 * Small personal learning-to-score model trained on actual owner ratings and
 * visual characteristics of the decoded artwork, not on genre/RT heuristics.
 *
 * A regularized ridge model uses a 12-dimensional stable pixel descriptor and
 * learns the owner's correction to a technical or aesthetic baseline.
 * Training is reversible when a manual rating is changed or cleared.
 * With 13 coefficients, updates cost O(d³) and inference O(d) on the phone.
 *
 * The native image model's weights remain untouched. This learned regressor is
 * a genuine supervised on-device model, not a fixed tag-frequency adjustment.
 */
internal object ArtworkVisualPreferenceModel {
    const val DESCRIPTOR_SIZE = 12
    const val WEIGHTS = DESCRIPTOR_SIZE + 1
    const val MIN_EXAMPLES = 15
    private const val RIDGE_INTERCEPT = 2.5
    private const val RIDGE_OTHER = 10.0

    data class TrainingState(
        val examples: Int = 0,
        val gram: DoubleArray = DoubleArray(WEIGHTS * WEIGHTS),
        val target: DoubleArray = DoubleArray(WEIGHTS),
        val coefficients: DoubleArray = DoubleArray(WEIGHTS),
    )
    data class Prediction(
        val adjustment: Double,
        val confidence: Double,
        val trainingExamples: Int,
    )
    fun valid(values: List<Double>): Boolean =
        values.size == DESCRIPTOR_SIZE &&
            values.all { it.isFinite() && it in -1.000001..1.000001 }

    private fun vector(values: List<Double>): DoubleArray =
        doubleArrayOf(1.0, *values.toDoubleArray())

    fun update(
        current: TrainingState,
        features: List<Double>,
        difference: Double,
        direction: Int,
    ): TrainingState {
        require(valid(features) && difference.isFinite() && direction in setOf(-1, 1))
        val x = vector(features)
        val a = current.gram.copyOf()
        val b = current.target.copyOf()
        for (i in x.indices) {
            b[i] += direction * x[i] * difference.coerceIn(-100.0, 100.0)
            for (j in x.indices) {
                a[i * WEIGHTS + j] += direction * x[i] * x[j]
            }
        }
        val count = (current.examples + direction).coerceAtLeast(0)
        if (count == 0) return TrainingState()
        return TrainingState(
            examples = count,
            gram = a,
            target = b,
            coefficients = fit(a, b),
        )
    }

    /**
     * Solve (X^T X + lambda I) beta = X^T y using partial-pivot elimination.
     * Regularization and the bounded feature range avoid singular fits when
     * users rate dozens of near-identical screenshots.
     */
    private fun fit(gram: DoubleArray, target: DoubleArray): DoubleArray {
        val size = WEIGHTS
        val aug = Array(size) { row ->
            DoubleArray(size + 1) { col ->
                when {
                    col == size -> target[row]
                    else -> gram[row * size + col] +
                        if (row == col)
                            (if (row == 0) RIDGE_INTERCEPT else RIDGE_OTHER)
                        else 0.0
                }
            }
        }
        for (pivot in 0 until size) {
            val strongest = (pivot until size).maxByOrNull { abs(aug[it][pivot]) } ?: pivot
            if (abs(aug[strongest][pivot]) < 1e-10) return DoubleArray(size)
            val temp = aug[pivot]; aug[pivot] = aug[strongest]; aug[strongest] = temp
            val scale = aug[pivot][pivot]
            for (c in pivot..size) aug[pivot][c] /= scale
            for (row in 0 until size) {
                if (row == pivot) continue
                val factor = aug[row][pivot]
                for (c in pivot..size) aug[row][c] -= factor * aug[pivot][c]
            }
        }
        return DoubleArray(size) { i -> aug[i][size].coerceIn(-100.0, 100.0) }
    }

    fun predict(model: TrainingState, features: List<Double>): Prediction {
        if (model.examples < MIN_EXAMPLES || !valid(features)) {
            return Prediction(0.0, 0.0, model.examples)
        }
        val input = vector(features)
        val raw = model.coefficients.indices.sumOf {
            model.coefficients[it] * input[it]
        }
        // Do not extrapolate confidently into unsupported visual styles.
        // Bound personal shifts and shrink predictions for small datasets.
        val sampleConfidence = model.examples.toDouble() /
            (model.examples + 25.0)
        val distance = (1..DESCRIPTOR_SIZE).sumOf { idx ->
            val trainingMean = model.gram[idx] / max(1, model.examples)
            val featureDiff = input[idx] - trainingMean
            val trainingVariance = (model.gram[idx * WEIGHTS + idx] /
                max(1, model.examples) - trainingMean * trainingMean)
                .coerceAtLeast(0.0)
            featureDiff * featureDiff / (trainingVariance + 0.45)
        } / DESCRIPTOR_SIZE
        val similarityConfidence = (1.0 / (1.0 + distance))
            .coerceIn(0.25, 1.0)
        val confidence = sampleConfidence * similarityConfidence
        return Prediction(
            adjustment = (raw * confidence).coerceIn(-50.0, 50.0),
            confidence = confidence,
            trainingExamples = model.examples,
        )
    }
}
