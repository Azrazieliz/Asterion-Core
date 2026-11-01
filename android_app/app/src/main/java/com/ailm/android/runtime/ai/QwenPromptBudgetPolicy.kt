package com.ailm.android.runtime.ai

/**
 * Prevent a large canonical taxonomy from exhausting the shared GGUF
 * multimodal image/context window before generation can begin.
 *
 * Spreads representative IDs across every taxonomy category rather than
 * taking a prefix dominated by just the first category. Uncertain image
 * observations remain subject to Fusion confidence/review, not guesses.
 */
internal object QwenPromptBudgetPolicy {
    fun compactTaxonomy(raw: String, maxChars: Int): String {
        if (raw.length <= maxChars) return raw
        val groups = raw.lineSequence().mapNotNull { line ->
            val marker = line.indexOf(":[")
            if (marker < 1 || !line.endsWith("]")) return@mapNotNull null
            val name = line.substring(0, marker)
            val values = line.substring(marker + 2, line.length - 1)
                .split("|").filter { "=" in it && it.length <= 90 }
            if (values.isEmpty()) null else name to values
        }.toList()
        if (groups.isEmpty()) return ""
        val selected = groups.map { (key, _) -> key to mutableListOf<String>() }
        val spent = groups.sumOf { it.first.length + 3 }
        var remaining = (maxChars - spent).coerceAtLeast(0)
        var row = 0
        while (remaining > 0 && row < groups.maxOf { it.second.size }) {
            var added = false
            groups.forEachIndexed { index, (_, values) ->
                if (row >= values.size) return@forEachIndexed
                val candidate = values[row]
                val cost = candidate.length + 1
                if (cost <= remaining) {
                    selected[index].second += candidate
                    remaining -= cost
                    added = true
                }
            }
            if (!added) break
            row++
        }
        return selected.filter { it.second.isNotEmpty() }.joinToString("\n") {
            (category, values) -> category + ":[" + values.joinToString("|") + "]"
        }
    }
}
