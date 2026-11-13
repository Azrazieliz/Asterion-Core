package com.ailm.android.runtime

import java.util.Locale

/**
 * Exhaustive, ordered evaluation of the current Fusion taxonomy.
 *
 * The established families define priority, NOT an allowlist; imported new
 * families are automatically included in stable order without a new APK.
 */
internal object FusionTagFamilyOrder {
    val established: List<String> = listOf(
        "SA", "SP", "SX", "AG", "SC", "BH", "BT", "BS", "BY",
        "HC", "HL", "HS", "EC", "ET",
        "EY", "MO", "EX", "GE", "PO", "AC", "OF", "WP",
        "EN", "WE", "LI", "FR", "OR", "CA", "RT",
    )
    private val code = Regex("^[A-Z]{2}$")

    fun ordered(currentFamilies: Collection<String>): List<String> {
        val available = currentFamilies.asSequence()
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter(code::matches)
            .toSet()
        return established.filter(available::contains) +
            (available - established.toSet()).sorted()
    }
}
