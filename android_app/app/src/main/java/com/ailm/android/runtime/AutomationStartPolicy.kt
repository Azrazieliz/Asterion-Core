package com.ailm.android.runtime

/** A running safe Knowledge migration is a dependency, never an image failure. */
internal object AutomationStartPolicy {
    enum class Decision { RUN, WAIT_FOR_KNOWLEDGE, BLOCKED }

    fun exhaustedRecovery(status: String, failedOnly: Boolean): Boolean =
        failedOnly && status in setOf("failed", "completed_with_errors")

    fun decide(readiness: Map<String, Any>): Decision = when {
        readiness["ready"] == true -> Decision.RUN
        readiness["deferred_knowledge_reconciliation"] == true -> Decision.WAIT_FOR_KNOWLEDGE
        else -> Decision.BLOCKED
    }
}
