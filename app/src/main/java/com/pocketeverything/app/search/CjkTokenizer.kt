package com.pocketeverything.app.search

/**
 * High-speed CJK N-gram (1-gram + 2-gram) + ASCII word tokenizer designed for zero-miss,
 * millisecond substring search across filenames, paths, and full-text documents.
 */
object CjkTokenizer {

    data class TokenizedOutput(
        val biAndAscii: String,
        val cjkUnigrams: String
    )

    data class QueryFragments(
        /** Ordered phrase sequences of 2-gram / ASCII tokens for exact FTS5 matching */
        val phraseSequences: List<List<String>>,
        /** Single CJK characters that do not belong to a >=2-char CJK run */
        val requiredUnigrams: List<String>,
        /** Lowercase normalized query literal for exact verification and highlighting */
        val normalizedLiteral: String
    )

    fun isCjkChar(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x4E00..0x9FFF) ||
            (code in 0x3400..0x4DBF) ||
            (code in 0xF900..0xFAFF) ||
            (code in 0x2E80..0x2EFF)
    }

    fun normalizeChar(ch: Char): Char {
        return when {
            ch in 'A'..'Z' -> (ch.code + 32).toChar()
            ch in '\uFF21'..'\uFF3A' -> (ch.code - 0xFF21 + 'a'.code).toChar()
            ch in '\uFF41'..'\uFF5A' -> (ch.code - 0xFF41 + 'a'.code).toChar()
            ch in '\uFF10'..'\uFF19' -> (ch.code - 0xFF10 + '0'.code).toChar()
            else -> ch
        }
    }

    fun normalizeString(input: String): String {
        if (input.isEmpty()) return input
        val sb = StringBuilder(input.length)
        for (i in input.indices) {
            sb.append(normalizeChar(input[i]))
        }
        return sb.toString()
    }

    fun isAsciiWordChar(ch: Char): Boolean {
        return (ch in 'a'..'z') || (ch in '0'..'9')
    }

    /**
     * Tokenizes [text] into:
     * - [TokenizedOutput.biAndAscii]: contiguous ASCII tokens and CJK 2-grams in positional order.
     * - [TokenizedOutput.cjkUnigrams]: distinct or ordered CJK 1-grams (used for 1-char CJK search).
     */
    fun tokenizeForIndex(
        text: String,
        dedupUnigrams: Boolean = false
    ): TokenizedOutput {
        if (text.isEmpty()) return TokenizedOutput("", "")

        val biOut = StringBuilder(text.length * 2)
        val uniOut = StringBuilder(text.length)
        val seenUni = if (dedupUnigrams) HashSet<Char>(256) else null

        val len = text.length
        var i = 0
        while (i < len) {
            val c = normalizeChar(text[i])
            if (isCjkChar(c)) {
                val start = i
                i++
                while (i < len && isCjkChar(normalizeChar(text[i]))) {
                    i++
                }
                val runLen = i - start
                for (j in start until i) {
                    val uc = normalizeChar(text[j])
                    if (seenUni == null || seenUni.add(uc)) {
                        if (uniOut.isNotEmpty()) uniOut.append(' ')
                        uniOut.append(uc)
                    }
                }
                if (runLen >= 2) {
                    for (j in start until i - 1) {
                        if (biOut.isNotEmpty()) biOut.append(' ')
                        biOut.append(normalizeChar(text[j]))
                        biOut.append(normalizeChar(text[j + 1]))
                    }
                }
            } else if (isAsciiWordChar(c)) {
                val start = i
                i++
                while (i < len && isAsciiWordChar(normalizeChar(text[i]))) {
                    i++
                }
                if (biOut.isNotEmpty()) biOut.append(' ')
                for (j in start until i) {
                    biOut.append(normalizeChar(text[j]))
                }
            } else {
                i++
            }
        }
        return TokenizedOutput(biOut.toString(), uniOut.toString())
    }

    /**
     * Decomposes a user search literal (e.g., "采购合同", "2026年报", "税", "budget")
     * into FTS5 phrase sequences and required CJK unigrams.
     */
    fun analyzeQueryLiteral(rawLiteral: String): QueryFragments {
        val norm = normalizeString(rawLiteral)
        val sequences = mutableListOf<List<String>>()
        val unigrams = mutableListOf<String>()

        val len = norm.length
        var i = 0
        while (i < len) {
            val c = norm[i]
            if (isCjkChar(c)) {
                val start = i
                i++
                while (i < len && isCjkChar(norm[i])) {
                    i++
                }
                val runLen = i - start
                if (runLen == 1) {
                    unigrams.add(norm.substring(start, i))
                } else {
                    val bigrams = ArrayList<String>(runLen - 1)
                    for (j in start until i - 1) {
                        bigrams.add(norm.substring(j, j + 2))
                    }
                    sequences.add(bigrams)
                }
            } else if (isAsciiWordChar(c)) {
                val start = i
                i++
                while (i < len && isAsciiWordChar(norm[i])) {
                    i++
                }
                sequences.add(listOf(norm.substring(start, i)))
            } else {
                i++
            }
        }
        return QueryFragments(
            phraseSequences = sequences,
            requiredUnigrams = unigrams,
            normalizedLiteral = norm
        )
    }
}
