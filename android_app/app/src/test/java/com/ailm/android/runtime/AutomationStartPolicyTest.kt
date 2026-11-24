package com.ailm.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomationStartPolicyTest {
    @org.junit.Test fun watchdogResumesInterruptedRetryButStopsAfterTerminalRetryFailure() {
        for (status in listOf("running", "queued")) {
            org.junit.Assert.assertFalse(AutomationStartPolicy.exhaustedRecovery(status, true))
        }
        for (status in listOf("failed", "completed_with_errors")) {
            org.junit.Assert.assertFalse(AutomationStartPolicy.exhaustedRecovery(status, false))
            org.junit.Assert.assertTrue(AutomationStartPolicy.exhaustedRecovery(status, true))
        }
    }
    @Test fun preparingKnowledgeDefersWithoutFailure() {
        assertEquals(AutomationStartPolicy.Decision.WAIT_FOR_KNOWLEDGE,
            AutomationStartPolicy.decide(mapOf(
                "ready" to false,
                "deferred_knowledge_reconciliation" to true,
                "missing_stages" to listOf("fusion_knowledge_reconciliation"),
            )))
    }
    @Test fun finishedMigrationAllowsActualInference() {
        assertEquals(AutomationStartPolicy.Decision.RUN,
            AutomationStartPolicy.decide(mapOf("ready" to true)))
    }
    @Test fun permanentMigrationOrMissingModelIsNotRetriedForever() {
        assertEquals(AutomationStartPolicy.Decision.BLOCKED,
            AutomationStartPolicy.decide(mapOf(
                "ready" to false, "deferred_knowledge_reconciliation" to false,
            )))
        assertEquals(AutomationStartPolicy.Decision.BLOCKED,
            AutomationStartPolicy.decide(mapOf("ready" to false,
                "missing_stages" to listOf("native_visual_tagger"))))
    }
    @Test fun readyPrecedenceAvoidsStaleDeferMarker() {
        assertEquals(AutomationStartPolicy.Decision.RUN,
            AutomationStartPolicy.decide(mapOf("ready" to true,
                "deferred_knowledge_reconciliation" to true)))
    }
}
