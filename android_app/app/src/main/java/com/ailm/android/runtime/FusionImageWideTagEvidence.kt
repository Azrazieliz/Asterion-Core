package com.ailm.android.runtime

import java.util.Locale

/**
 * Conserve only finite, scored canonical Fusion IDs from the native
 * classifier's verified mapped output. Never interpret generated names,
 * descriptions or arbitrary prose as image observations.
 */
internal object FusionImageWideTagEvidence {
    private val canonicalId = Regex("^[A-Z]{2}[0-9]{2,}$")

    fun scoredFeatures(raw: Any?): Map<String, Double> {
        val rows = raw as? List<*> ?: return emptyMap()
        val results = linkedMapOf<String, Double>()
        for (row in rows) {
            val value = row as? Map<*, *> ?: continue
            val id = value["tag"]?.toString()?.trim()?.uppercase(Locale.ROOT).orEmpty()
            val score = (value["score"] as? Number)?.toDouble() ?: continue
            if (!canonicalId.matches(id) || !score.isFinite() || score !in 0.0..1.0) continue
            results[id] = maxOf(results[id] ?: 0.0, score)
        }
        return results
    }
}
