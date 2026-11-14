package com.ailm.android.runtime

import java.util.Locale

/**
 * Family-scoped machine-observation replacement.
 *
 * A model that cannot score a Fusion family has no authority to erase that
 * family's earlier observations. Conversely, a formerly auto-approved family
 * now deferred as contradictory/subject-ambiguous must leave the trusted
 * image-tag index and become Review evidence. Manual tags are never deleted.
 */
internal object FusionTagUpdatePolicy {
    private val code = Regex("^[A-Z]{2}$")

    fun replaceFamilies(
        incoming: List<CanonicalTagObservation>,
        coverage: Any?,
    ): Set<String> {
        val families = linkedSetOf<String>()
        incoming.forEach { tag ->
            if (tag.source != "manual") {
                val family = tag.tagId.take(2).uppercase(Locale.ROOT)
                if (code.matches(family)) families += family
            }
        }
        (coverage as? List<*>)?.forEach { item ->
            val row = item as? Map<*, *> ?: return@forEach
            val family = row["family"]?.toString()?.uppercase(Locale.ROOT) ?: return@forEach
            val status = row["status"]?.toString().orEmpty()
            if (code.matches(family) && status in setOf("accepted", "deferred_review")) {
                families += family
            }
        }
        return families
    }
}
