package com.ailm.android.runtime

/** Associate detections only when both sides have a unique overlapping region. */
internal object SubjectRegionAssociation {
    fun indices(
        current: List<AnimePersonBoxDecoder.Region>,
        previous: Map<Int, AnimePersonBoxDecoder.Region>,
        storedIndices: Set<Int>,
    ): List<Int>? {
        if (!previous.keys.containsAll(storedIndices)) return null
        if (previous.isEmpty()) return current.indices.toList()
        val matches = current.map { region ->
            previous.filterValues { AnimePersonBoxDecoder.intersectionOverUnion(it, region) >= 0.50 }.keys
        }
        if (matches.any { it.size > 1 }) return null
        val known = matches.mapNotNull { it.singleOrNull() }
        if (known.size != known.distinct().size || !known.containsAll(previous.keys)) return null
        var next = previous.keys.max() + 1
        return matches.map { it.singleOrNull() ?: next++ }
    }
}
