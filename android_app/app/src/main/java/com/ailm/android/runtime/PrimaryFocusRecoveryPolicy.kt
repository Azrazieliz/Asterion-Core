package com.ailm.android.runtime

/** Conservative, one-retry primary-character visual focus policy.
 * A shared first pass runs on the entire character crop. A tighter second
 * pass may only replace demonstrably weaker face/hair observations, never
 * infer other people or fabricate unsupported Fusion attributes.
 */
internal object PrimaryFocusRecoveryPolicy {
    val eligibleFamilies = setOf("HC", "HS", "EC", "ET")
    fun shouldAttempt(
        existing: Map<String, Double>,
        overlappingOtherSubject: Boolean,
        remainingMs: Long,
        width: Int,
        height: Int,
    ): Boolean {
        if (overlappingOtherSubject || remainingMs < 2_500L ||
            width < 80 || height < 120) return false
        val strong = existing.filterValues { it.isFinite() && it >= 0.78 }
            .keys.map(String::takeFirstTwo).toSet()
        return eligibleFamilies.any { it !in strong }
    }
    private fun String.takeFirstTwo(): String = take(2)
    fun acceptFocusedEvidence(
        family: String, prior: Double, focused: Double,
    ): Boolean = family in eligibleFamilies &&
        focused.isFinite() && focused in 0.80..1.0 &&
        prior.isFinite() && prior in 0.0..1.0 &&
        focused >= prior + 0.16
}
