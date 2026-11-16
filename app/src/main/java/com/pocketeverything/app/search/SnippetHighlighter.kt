package com.pocketeverything.app.search

/**
 * Computes exact highlight ranges on raw names, paths, and full-text document contents,
 * and builds compact multi-line snippets around matched occurrences.
 */
object SnippetHighlighter {

    /**
     * Finds all case-insensitive / fullwidth-normalized character ranges of [terms] in [source].
     * Merges overlapping or adjacent ranges.
     */
    fun findMatchRanges(source: String, terms: List<String>, maxMatches: Int = 200): List<IntRange> {
        if (source.isEmpty() || terms.isEmpty()) return emptyList()
        val normalizedSource = CjkTokenizer.normalizeString(source)
        val rawRanges = ArrayList<IntRange>()

        for (rawTerm in terms) {
            val needle = CjkTokenizer.normalizeString(rawTerm).trim()
            if (needle.isEmpty()) continue
            var fromIdx = 0
            while (fromIdx < normalizedSource.length && rawRanges.size < maxMatches) {
                val found = normalizedSource.indexOf(needle, startIndex = fromIdx)
                if (found < 0) break
                rawRanges.add(found until (found + needle.length))
                fromIdx = found + needle.length.coerceAtLeast(1)
            }
        }

        if (rawRanges.isEmpty()) return emptyList()
        rawRanges.sortWith(compareBy<IntRange> { it.first }.thenBy { it.last })

        val merged = ArrayList<IntRange>(rawRanges.size)
        var current = rawRanges[0]
        for (i in 1 until rawRanges.size) {
            val next = rawRanges[i]
            if (next.first <= current.last + 1) {
                current = current.first..maxOf(current.last, next.last)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    /**
     * Generates up to [maxSnippets] contextual snippets around matched terms in [fullText].
     */
    fun buildSnippets(
        fullText: String,
        terms: List<String>,
        contextBefore: Int = 28,
        contextAfter: Int = 44,
        maxSnippets: Int = 2
    ): Pair<List<MatchSnippet>, Int> {
        if (fullText.isEmpty() || terms.isEmpty()) return emptyList<MatchSnippet>() to 0
        val allRanges = findMatchRanges(fullText, terms, maxMatches = 300)
        if (allRanges.isEmpty()) return emptyList<MatchSnippet>() to 0

        val snippets = ArrayList<MatchSnippet>(maxSnippets)
        var lastWindowEnd = -1

        for (match in allRanges) {
            if (snippets.size >= maxSnippets) break
            if (match.first < lastWindowEnd) continue

            val winStart = (match.first - contextBefore).coerceAtLeast(0)
            val winEnd = (match.last + 1 + contextAfter).coerceAtMost(fullText.length)
            lastWindowEnd = winEnd

            val rawSlice = fullText.substring(winStart, winEnd)
                .replace('\r', ' ')
                .replace('\n', ' ')

            val prefix = if (winStart > 0) "…" else ""
            val suffix = if (winEnd < fullText.length) "…" else ""
            val display = prefix + rawSlice + suffix
            val shift = prefix.length - winStart

            val localHighlights = allRanges
                .filter { it.first >= winStart && it.last < winEnd }
                .map { (it.first + shift)..(it.last + shift) }

            snippets.add(
                MatchSnippet(
                    text = display,
                    highlightRanges = localHighlights,
                    charOffsetInDoc = match.first
                )
            )
        }

        return snippets to allRanges.size
    }
}
