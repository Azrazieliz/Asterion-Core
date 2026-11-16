package com.ailm.android.runtime

import kotlin.math.roundToInt

/**
 * Asterion Art-compatible artwork ratings.
 *
 * User feedback is 1..5 stars (0 = no manual decision). Automatic quality
 * evidence is independent of the RT content-safety classification. A manual
 * rating is authoritative and is never replaced by a subsequent model pass.
 *
 * The Asterion aesthetic model emits approximately 0..10; technical scorers
 * emit 0..100. Both are normalized WITHOUT interpreting the safety classifier
 * as a quality preference. Unknown/nonfinite outputs are not ratings.
 */
internal object ArtworkRatingPolicy {
    const val MIN_STARS = 1
    const val MAX_STARS = 5

    fun rawAutomaticStars(score: Double): Int? {
        if (!score.isFinite() || score < 0.0) return null
        val inFive = when {
            score <= 10.0 -> score / 2.0
            score <= 100.0 -> score / 20.0
            else -> return null
        }
        return inFive.roundToInt().coerceIn(MIN_STARS, MAX_STARS)
    }

    fun calibratedStars(score: Double, bias: Double): Int? {
        val base = rawAutomaticStars(score) ?: return null
        if (!bias.isFinite()) return base
        return (base + bias.coerceIn(-1.5, 1.5)).roundToInt()
            .coerceIn(MIN_STARS, MAX_STARS)
    }

    fun preferredStars(manualStars: Int, automaticStars: Int?): Int? =
        manualStars.takeIf { it in MIN_STARS..MAX_STARS } ?: automaticStars

    /** Incremental offset learned ONLY from actual manual user labels. */
    data class Calibration(val count: Int = 0, val bias: Double = 0.0)
    fun revise(
        old: Calibration,
        automaticStars: Int,
        previousManual: Int,
        nextManual: Int,
    ): Calibration {
        if (automaticStars !in MIN_STARS..MAX_STARS) return old
        var count = old.count.coerceAtLeast(0)
        var sum = old.bias.coerceIn(-1.5, 1.5) * count
        if (previousManual in MIN_STARS..MAX_STARS && count > 0) {
            sum -= (previousManual - automaticStars).toDouble()
            count -= 1
        }
        if (nextManual in MIN_STARS..MAX_STARS) {
            sum += (nextManual - automaticStars).toDouble()
            count += 1
        }
        return Calibration(count, if (count == 0) 0.0
            else (sum / count).coerceIn(-1.5, 1.5))
    }
}
