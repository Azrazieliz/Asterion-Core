package com.ailm.android.runtime

import java.util.Locale

/**
 * Family-scoped machine-observation replacement.
 *
 * A model that cannot provide an ACCEPTED, subject-correct replacement
 * for a family has no authority to erase that family's earlier observations.
 * Scored image-wide or deferred labels are not replacements for validated
 * per-character observations. Manual tags are never deleted.
 */
internal object FusionTagUpdatePolicy {
    private val code = Regex("^[A-Z]{2}$")

    @Suppress("UNUSED_PARAMETER")
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
        // A family merely marked accepted/deferred by the WHOLE-IMAGE model
        // cannot justify deleting any existing subject-observed tags.
        // Only the actual incoming validated tags authorize replacement.
        return families
    }
}
