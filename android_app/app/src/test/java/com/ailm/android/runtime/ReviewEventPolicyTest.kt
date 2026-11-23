package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class ReviewEventPolicyTest {
    @Test fun changingRankerConfidenceDoesNotResurfaceAnOwnerDecision() {
        val cause = ReviewEventPolicy.cause("Subject 2 is below the character confidence threshold (0.65); Missing HC")
        val rerun = ReviewEventPolicy.cause("Missing HC; Subject 2 is below the character confidence threshold (0.67)")
        for (status in listOf("approved", "rejected", "dismissed")) {
            assertTrue(ReviewEventPolicy.acknowledged(status, cause, rerun))
            assertFalse(ReviewEventPolicy.acknowledged(status, cause, ReviewEventPolicy.cause("Missing EC")))
        }
        assertFalse(ReviewEventPolicy.acknowledged("auto_resolved", cause, rerun))
    }
}
