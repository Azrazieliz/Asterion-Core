package com.ailm.android.runtime

/** A review decision and automation eligibility are independent.
 * Rejecting one suggestion must not reject the image or mark required
 * recognition/RT/organization stages as complete.
 */
internal object ReviewDecisionPolicy {
    fun shouldRequeue(existingStatus: String?, mandatory: Boolean): Boolean =
        when (existingStatus) {
            null, "pending" -> true
            "rejected", "skipped" -> false
            else -> mandatory
        }
}
