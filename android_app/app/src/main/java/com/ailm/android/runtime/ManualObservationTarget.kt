package com.ailm.android.runtime

/** Resolve the persisted scope once, before either editing or training. */
internal data class ManualObservationTarget(val scope: String, val subjectIndex: Int?) {
    companion object {
        fun resolve(tag: FusionTaxonomyEngine.Candidate, selected: Int?, subjects: List<Int>): ManualObservationTarget? {
            val local = FusionVisualLabelMapper.acceptsRegion(tag, true)
            val scene = FusionVisualLabelMapper.acceptsRegion(tag, false)
            if (selected != null && selected !in subjects) return null
            if (tag.family == "RT") return ManualObservationTarget("content_rating", null)
            if (scene && (selected == null || !local)) return ManualObservationTarget("illustration", null)
            if (!local) return null
            val index = selected ?: subjects.singleOrNull()
            if (index == null && subjects.isNotEmpty()) return null
            // Legacy manual anatomy predating detection has no verified crop.
            return ManualObservationTarget("character_observed", index)
        }
    }
}
