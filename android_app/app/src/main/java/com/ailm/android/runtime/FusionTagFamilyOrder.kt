package com.ailm.android.runtime

import java.util.Locale

/**
 * Character anatomy is considered before conditional species attributes and
 * species. These are evidence-evaluation priorities, not a frozen allowlist.
 * A subject's SA evidence can then constrain the SP candidate match; any
 * SP-dependent trait requirements are validated AFTER SP is resolved.
 */
internal object FusionTagFamilyOrder {
    val character: List<String> = listOf(
        "SX",                          // sex
        "HC", "HL", "HS",             // hair colour, length, style
        "EC", "ET",             // eye colour, native ET traits, optional EA extension
        "BH", "SC", "BT", "BS", "BY", // height, skin, build, breast size, body
        "AG", "EA",                    // optional eye extension follows standard attributes
        "SA", "SP",                  // visible species traits, then species
    )
    val illustration: List<String> = listOf(
        "AC", "GE", "PO", "OF", "WP", "FR", "OR", "CA",
        "EN", "WE", "LI", "EY", "MO", "EX", "RT",
    )
    // 26 verified SE0031 families; EA/EY/MO/EX remain optional
    // dynamically installed namespaces, never fabricated canonical entries.
    val originalSe0031Families = setOf(
        "SX", "HC", "HL", "HS", "EC", "ET",
        "BH", "SC", "BT", "BS", "BY", "AG", "SA", "SP",
        "AC", "CA", "EN", "FR", "GE", "LI", "OR", "OF",
        "PO", "RT", "WP", "WE",
    )
    val established: List<String> = character + illustration

    /** Environment and atmospheric evidence leads confirmed scenery-only art. */
    private val sceneryPriority = listOf(
        "EN", "WE", "LI", "FR", "OR", "CA",
        "EY", "MO", "EX", "GE", "PO", "AC", "OF", "WP", "RT",
    )
    private val code = Regex("^[A-Z]{2}$")
    private fun normalized(families: Collection<String>): Set<String> =
        families.asSequence().map { it.trim().uppercase(Locale.ROOT) }
            .filter(code::matches).toSet()

    fun ordered(
        currentFamilies: Collection<String>,
        characterFamilies: Collection<String> = emptyList(),
        sceneryOnly: Boolean = false,
        explicitImageFamilies: Set<String> = emptySet(),
    ): List<String> {
        val available = normalized(currentFamilies)
        val scopedCharacters = normalized(characterFamilies).intersect(available)
        // Dynamically imported character families must be visited BEFORE SA.
        // Runtime "character" or "corporal" metadata defines their scope.
        val extraCharacter = (scopedCharacters - character.toSet()).sorted()
        if (sceneryOnly) {
            // The caller must first verify that there are NO character
            // subjects. Character families are not evaluated on scenery.
            val imageFamilies = (available - character.toSet() - scopedCharacters) +
                available.intersect(explicitImageFamilies)
            return sceneryPriority.filter(imageFamilies::contains) +
                (imageFamilies - sceneryPriority.toSet()).sorted()
        }
        val head = character.takeWhile { it != "SA" }
            .filter(available::contains)
        val tail = listOf("SA", "SP").filter(available::contains)
        val remaining = illustration.filter { it in available && it !in extraCharacter }
        val unknownIllustration = (available -
            character.toSet() - illustration.toSet() - extraCharacter.toSet()).sorted()
        return head + extraCharacter + tail + remaining + unknownIllustration
    }
}
