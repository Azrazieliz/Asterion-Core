package com.ailm.android.runtime

/**
 * Reject contradictory model-only visual evidence before persistence.
 * The Knowledge IDs themselves are immutable and are never changed here.
 *
 * A family can legitimately contain multiple tags (e.g. HC pink + white,
 * OF layered clothing, PO sitting + leaning), so no blanket one-tag cap.
 */
internal object CanonicalImageTagCoherence {
    fun internallyConsistent(family: String, canonicalNames: List<String>): Boolean {
        val normalized = canonicalNames.map { name ->
            name.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        }
        if (normalized.size <= 1) return true
        fun hasWord(value: String) = normalized.any { name ->
            name.split(' ').any { it == value }
        }
        return when (family.uppercase()) {
            "PO" -> {
                // Mutually exclusive stationary poses for one subject.
                val standing = hasWord("standing") || hasWord("stand")
                val sitting = hasWord("sitting") || hasWord("seated")
                val lying = hasWord("lying") || hasWord("reclining")
                listOf(standing, sitting, lying).count { it } <= 1
            }
            "FR" -> {
                // An extreme close-up cannot simultaneously be a knee/full shot.
                val extreme = normalized.any { "extreme close" in it }
                val distant = normalized.any {
                    "knee shot" in it || "full body" in it ||
                        "full shot" in it || "long shot" in it
                }
                !(extreme && distant)
            }
            else -> true
        }
    }
}
