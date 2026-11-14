package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class ReviewDecisionPolicyTest {
    @Test fun rejectedMandatoryReviewStaysDismissedUntilExplicitUndo() {
        assertFalse(ReviewDecisionPolicy.shouldRequeue("rejected", true))
        assertFalse(ReviewDecisionPolicy.shouldRequeue("rejected", false))
        assertFalse(ReviewDecisionPolicy.shouldRequeue("skipped", true))
    }
    @Test fun newOrUnresolvedReviewsStayActionable() {
        assertTrue(ReviewDecisionPolicy.shouldRequeue(null, true))
        assertTrue(ReviewDecisionPolicy.shouldRequeue("pending", true))
        assertTrue(ReviewDecisionPolicy.shouldRequeue("approved", true))
        assertFalse(ReviewDecisionPolicy.shouldRequeue("approved", false))
    }
}
