package com.ailm.android.runtime

internal object ReviewEventPolicy {
    // A ranker's confidence may vary slightly on repeat inference. Preserve
    // subject indices, candidate IDs, destinations and missing family names.
    private val confidence = Regex("""(below the character confidence threshold) \([01]\.\d+\)""")
    fun acknowledged(status: String, priorCause: String, incomingCause: String): Boolean =
        status !in setOf("pending", "auto_resolved") && priorCause == incomingCause

    fun cause(reason: String): String =
        confidence.replace(reason.trim(), "$1").split("; ").sorted().joinToString("; ")
}
