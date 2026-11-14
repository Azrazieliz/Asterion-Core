package com.ailm.android.runtime

import java.util.Locale

/**
 * Second-stage guard for *already mapped* native Fusion observations.
 *
 * The native WD ViT v3 classifier was admitted at 0.40 by
 * FusionVisualLabelMapper. The old workflow then discarded every score < 0.82.
 * Preserve this model's threshold without weakening the gate for other models
 * or accepting free-form Qwen/unknown-source text as canonical evidence.
 */
internal object FusionClassifierTagGate {
    private val canonicalTag = Regex("^[A-Z]{2}[0-9]{2,}(?:-[0-9]+)*$")

    fun acceptedScores(result: Map<String, Any>): Map<String, Double> {
        if (result["source"] != "native_classifier") return emptyMap()
        val minimum = when (result["model_id"]?.toString()) {
            "asterion-wd-vit-tagger-v3" -> 0.40
            else -> 0.82
        }
        val records = result["scored_tags"] as? List<*> ?: return emptyMap()
        val accepted = linkedMapOf<String, Double>()
        for (item in records) {
            val row = item as? Map<*, *> ?: continue
            val id = row["tag"]?.toString()?.trim()?.uppercase(Locale.ROOT).orEmpty()
            val confidence = (row["score"] as? Number)?.toDouble() ?: continue
            if (!canonicalTag.matches(id) || !confidence.isFinite() ||
                confidence < minimum || confidence > 1.0) continue
            accepted[id] = maxOf(accepted[id] ?: 0.0, confidence)
        }
        return accepted
    }
}
