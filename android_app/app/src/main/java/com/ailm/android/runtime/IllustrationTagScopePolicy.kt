package com.ailm.android.runtime

/**
 * The classifier may describe the illustration and each detected subject.
 * Never let a character's appearance masquerade as image-wide evidence; never
 * discard a genuine scene tag (notably GE) when characters are present.
 */
internal object IllustrationTagScopePolicy {
    private val subjectDependentFamilies = setOf("EY", "MO", "EX", "PO", "AC", "OF", "WP")

    fun isImageLevel(
        tag: FusionTaxonomyEngine.Candidate,
        hasDetectedSubjects: Boolean,
    ): Boolean = !CharacterProfileCompleteness.isCharacterAttribute(tag) &&
        (!hasDetectedSubjects || tag.family !in subjectDependentFamilies)
}
