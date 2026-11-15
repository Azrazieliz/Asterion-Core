package com.ailm.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomationStartPolicyTest {
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
