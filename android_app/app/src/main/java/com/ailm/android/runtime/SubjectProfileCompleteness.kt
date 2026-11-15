package com.ailm.android.runtime

/**
 * One image may contain many separately indexed characters. A valid attribute
 * on one subject never fills a missing family (or creates a conflict) for
 * another subject. Merge only audit conclusions, never their raw tag IDs.
 */
internal object SubjectProfileCompleteness {
    fun combine(reports: List<CharacterProfileCompleteness.Report>): CharacterProfileCompleteness.Report {
        require(reports.isNotEmpty())
        fun union(values: (CharacterProfileCompleteness.Report) -> List<String>): List<String> =
            reports.flatMap(values).distinct().sorted()
        return CharacterProfileCompleteness.Report(
            filledFamilies = reports.map { it.filledFamilies.toSet() }
                .reduce { left, right -> left intersect right }.sorted(),
            missingFamilies = union { it.missingFamilies },
            conflictingFamilies = union { it.conflictingFamilies },
            notApplicableFamilies = reports.map { it.notApplicableFamilies.toSet() }
                .reduce { left, right -> left intersect right }.sorted(),
            missingRequiredSpeciesTraits = union { it.missingRequiredSpeciesTraits },
            incompatibleSpeciesTraits = union { it.incompatibleSpeciesTraits },
            unlinkedSpeciesTraits = union { it.unlinkedSpeciesTraits },
            unknownIds = union { it.unknownIds },
            profileComplete = reports.all { it.profileComplete },
        )
    }
}
