package com.pocketeverything.app.search

/**
 * Computes exact highlight ranges on raw names, paths, and full-text document contents
 * for both literal terms and strict regular expressions, and builds contextual snippets.
 */
object SnippetHighlighter {

    fun findMatchRanges(source: String, terms: List<String>, maxMatches: Int = 200): List<IntRange> {
        if (source.isEmpty() || terms.isEmpty()) return emptyList()
        return findMatchRangesForRules(source, terms.map { HighlightRule.Literal(it) }, maxMatches)
    }

    fun findMatchRangesForRules(
        source: String,
        rules: List<HighlightRule>,
        maxMatches: Int = 200
    ): List<IntRange> {
        if (source.isEmpty() || rules.isEmpty()) return emptyList()
        val normalizedSource = CjkTokenizer.normalizeString(source)
        val rawRanges = ArrayList<IntRange>()

        for (rule in rules) {
            if (rawRanges.size >= maxMatches) break
            when (rule) {
                is HighlightRule.Literal -> {
                    val needle = CjkTokenizer.normalizeString(rule.text).trim()
                    if (needle.isEmpty()) continue
                    var fromIdx = 0
                    while (fromIdx < normalizedSource.length && rawRanges.size < maxMatches) {
                        val found = normalizedSource.indexOf(needle, startIndex = fromIdx)
                        if (found < 0) break
                        rawRanges.add(found until (found + needle.length))
                        fromIdx = found + needle.length.coerceAtLeast(1)
                    }
                }
                is HighlightRule.RegexRule -> {
                    val regex = runCatching {
                        val opts = if (rule.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                        Regex(rule.pattern, opts)
                    }.getOrNull() ?: continue
                    for (m in regex.findAll(source)) {
                        if (m.range.isEmpty()) continue
                        rawRanges.add(m.range)
                        if (rawRanges.size >= maxMatches) break
                    }
                }
            }
        }

        return mergeRanges(rawRanges)
    }

    private fun mergeRanges(rawRanges: MutableList<IntRange>): List<IntRange> {
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

    fun buildSnippets(
        fullText: String,
        terms: List<String>,
        contextBefore: Int = 28,
        contextAfter: Int = 44,
        maxSnippets: Int = 2
    ): Pair<List<MatchSnippet>, Int> {
        return buildSnippetsForRules(
            fullText = fullText,
            rules = terms.map { HighlightRule.Literal(it) },
            contextBefore = contextBefore,
            contextAfter = contextAfter,
            maxSnippets = maxSnippets
        )
    }

    fun buildSnippetsForRules(
        fullText: String,
        rules: List<HighlightRule>,
        contextBefore: Int = 28,
        contextAfter: Int = 44,
        maxSnippets: Int = 2
    ): Pair<List<MatchSnippet>, Int> {
        if (fullText.isEmpty() || rules.isEmpty()) return emptyList<MatchSnippet>() to 0
        val allRanges = findMatchRangesForRules(fullText, rules, maxMatches = 300)
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
