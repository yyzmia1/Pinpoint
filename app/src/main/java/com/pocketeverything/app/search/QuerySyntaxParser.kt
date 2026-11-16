package com.pocketeverything.app.search

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Recursive-descent Everything-style search syntax parser supporting:
 * - Implicit AND (whitespace), OR ('|' or uppercase 'OR'), NOT ('-' or '!')
 * - Parentheses '(...)' and exact quotes '"..."'
 * - Strict wildcards (* and ?) and strict regular expressions (regex:, re:, cregex:, /pattern/)
 * - Filters: ext:, path:, name:, content:/body:, type:, size:, dm:
 */
object QuerySyntaxParser {

    private sealed interface Token {
        data object LParen : Token
        data object RParen : Token
        data object OrOp : Token
        data object NotOp : Token
        data class Word(val text: String, val quoted: Boolean, val regexLiteral: Boolean = false) : Token
    }

    fun parse(
        rawQuery: String,
        zoneId: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis()
    ): ParsedQuery {
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) {
            return ParsedQuery(root = null, positiveHighlightTerms = emptyList(), positiveHighlightRules = emptyList())
        }

        val tokens = tokenize(trimmed)
        if (tokens.isEmpty()) {
            return ParsedQuery(root = null, positiveHighlightTerms = emptyList(), positiveHighlightRules = emptyList())
        }

        val state = ParserState(tokens, zoneId, nowMillis)
        val ast = state.parseOr()
        val (terms, rules) = collectPositiveHighlights(ast)
        return ParsedQuery(
            root = ast,
            positiveHighlightTerms = terms.distinct(),
            positiveHighlightRules = rules.distinct(),
            parseError = state.firstError
        )
    }

    private fun tokenize(input: String): List<Token> {
        val result = ArrayList<Token>()
        var i = 0
        val n = input.length
        while (i < n) {
            val c = input[i]
            when {
                c.isWhitespace() -> i++
                c == '(' -> {
                    result.add(Token.LParen)
                    i++
                }
                c == ')' -> {
                    result.add(Token.RParen)
                    i++
                }
                c == '|' -> {
                    result.add(Token.OrOp)
                    i++
                }
                (c == '-' || c == '!') && i + 1 < n && !input[i + 1].isWhitespace() -> {
                    result.add(Token.NotOp)
                    i++
                }
                c == '"' -> {
                    i++
                    val sb = StringBuilder()
                    while (i < n && input[i] != '"') {
                        sb.append(input[i])
                        i++
                    }
                    if (i < n && input[i] == '"') i++
                    if (sb.isNotEmpty()) {
                        result.add(Token.Word(sb.toString(), quoted = true))
                    }
                }
                c == '/' -> {
                    val slashToken = tryReadSlashRegex(input, i)
                    if (slashToken != null) {
                        result.add(Token.Word(slashToken.first, quoted = false, regexLiteral = true))
                        i = slashToken.second
                    } else {
                        val (word, nextIdx) = readStandardWord(input, i)
                        i = nextIdx
                        emitWordToken(word, result)
                    }
                }
                else -> {
                    val regexEnd = tryReadPrefixedRegex(input, i)
                    if (regexEnd != null) {
                        result.add(Token.Word(regexEnd.first, quoted = false, regexLiteral = true))
                        i = regexEnd.second
                    } else {
                        val (word, nextIdx) = readStandardWord(input, i)
                        i = nextIdx
                        emitWordToken(word, result)
                    }
                }
            }
        }
        return result
    }

    /**
     * Reads `/pattern/` or `/pattern/i` / `/pattern/c` only if closing `/` is followed by a token delimiter
     * so filesystem paths like `/storage/emulated/0` are NOT misclassified as regex literals.
     */
    private fun tryReadSlashRegex(input: String, start: Int): Pair<String, Int>? {
        val n = input.length
        var i = start + 1
        var inCharClass = false
        var escaped = false
        while (i < n) {
            val ch = input[i]
            if (escaped) {
                escaped = false
                i++
                continue
            }
            if (ch == '\\') {
                escaped = true
                i++
                continue
            }
            if (ch == '[' && !inCharClass) {
                inCharClass = true
                i++
                continue
            }
            if (ch == ']' && inCharClass) {
                inCharClass = false
                i++
                continue
            }
            if (ch == '/' && !inCharClass) {
                if (i == start + 1) return null
                var end = i + 1
                while (end < n && (input[end] == 'i' || input[end] == 'c')) {
                    end++
                }
                val atBoundary = end == n || input[end].isWhitespace() ||
                    input[end] == ')' || input[end] == '|'
                if (!atBoundary) return null
                return input.substring(start, end) to end
            }
            if (ch.isWhitespace() && !inCharClass) return null
            i++
        }
        return null
    }

    /**
     * Reads `regex:...`, `re:...`, `cregex:...`, `name:regex:...`, `path:regex:...`, `content:regex:...`
     * while respecting balanced `(...)`, `[...]`, `\"`, and escaped operators so internal `|`/`(` don't split.
     */
    private fun tryReadPrefixedRegex(input: String, start: Int): Pair<String, Int>? {
        val prefixes = listOf(
            "name:cregex:", "path:cregex:", "content:cregex:", "body:cregex:",
            "name:regex:", "path:regex:", "content:regex:", "body:regex:",
            "name:re:", "path:re:", "content:re:", "body:re:",
            "cregex:", "regex:", "re:"
        )
        val matchedPrefix = prefixes.firstOrNull { prefix ->
            input.regionMatches(start, prefix, 0, prefix.length, ignoreCase = true)
        } ?: return null

        val n = input.length
        var i = start + matchedPrefix.length
        if (i >= n) return null

        if (input[i] == '"') {
            i++
            val sb = StringBuilder(matchedPrefix)
            while (i < n && input[i] != '"') {
                sb.append(input[i])
                i++
            }
            if (i < n && input[i] == '"') i++
            return sb.toString() to i
        }

        val sb = StringBuilder(matchedPrefix)
        var parenDepth = 0
        var inCharClass = false
        var escaped = false
        while (i < n) {
            val ch = input[i]
            if (escaped) {
                sb.append(ch)
                escaped = false
                i++
                continue
            }
            if (ch == '\\') {
                sb.append(ch)
                escaped = true
                i++
                continue
            }
            if (ch == '[' && !inCharClass) {
                inCharClass = true
                sb.append(ch)
                i++
                continue
            }
            if (ch == ']' && inCharClass) {
                inCharClass = false
                sb.append(ch)
                i++
                continue
            }
            if (!inCharClass) {
                if (ch == '(') parenDepth++
                else if (ch == ')') {
                    if (parenDepth == 0) break
                    parenDepth--
                } else if (ch.isWhitespace() && parenDepth == 0) {
                    break
                } else if (ch == '|' && parenDepth == 0) {
                    // Only split on '|' at top level if it does not look like part of an unparenthesized regex alternation
                    // Wait: users writing regex:foo|bar expect foo|bar inside the regex unless whitespace separates `|`!
                    // Keep '|' inside regex unless preceded by whitespace.
                }
            }
            sb.append(ch)
            i++
        }
        return sb.toString() to i
    }

    private fun readStandardWord(input: String, start: Int): Pair<String, Int> {
        var i = start
        val n = input.length
        val sb = StringBuilder()
        while (i < n) {
            val ch = input[i]
            if (ch.isWhitespace() || ch == '(' || ch == ')' || ch == '|') {
                break
            }
            if (ch == '"') {
                i++
                while (i < n && input[i] != '"') {
                    sb.append(input[i])
                    i++
                }
                if (i < n && input[i] == '"') i++
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString() to i
    }

    private fun emitWordToken(word: String, out: MutableList<Token>) {
        if (word == "OR") {
            out.add(Token.OrOp)
        } else if (word == "AND") {
            // Implicit AND already applies between adjacent expressions
        } else if (word.isNotEmpty()) {
            out.add(Token.Word(word, quoted = false))
        }
    }

    private class ParserState(
        private val tokens: List<Token>,
        private val zoneId: ZoneId,
        private val nowMillis: Long
    ) {
        private var pos = 0
        var firstError: String? = null

        private fun peek(): Token? = tokens.getOrNull(pos)
        private fun consume(): Token = tokens[pos++]

        fun parseOr(): QueryNode? {
            val branches = mutableListOf<QueryNode>()
            parseAnd()?.let { branches.add(it) }
            while (peek() is Token.OrOp) {
                consume()
                parseAnd()?.let { branches.add(it) }
            }
            return when (branches.size) {
                0 -> null
                1 -> branches[0]
                else -> QueryNode.Or(branches)
            }
        }

        private fun parseAnd(): QueryNode? {
            val items = mutableListOf<QueryNode>()
            while (true) {
                val next = peek() ?: break
                if (next is Token.RParen || next is Token.OrOp) break
                val node = parseUnary()
                if (node != null) {
                    items.add(node)
                } else {
                    break
                }
            }
            return when (items.size) {
                0 -> null
                1 -> items[0]
                else -> QueryNode.And(items)
            }
        }

        private fun parseUnary(): QueryNode? {
            val t = peek() ?: return null
            if (t is Token.NotOp) {
                consume()
                val inner = parseUnary() ?: return null
                return QueryNode.Not(inner)
            }
            return parsePrimary()
        }

        private fun parsePrimary(): QueryNode? {
            val t = peek() ?: return null
            return when (t) {
                is Token.LParen -> {
                    consume()
                    val inner = parseOr()
                    if (peek() is Token.RParen) {
                        consume()
                    } else if (firstError == null) {
                        firstError = "括号未闭合"
                    }
                    inner
                }
                is Token.Word -> {
                    consume()
                    if (t.quoted) {
                        QueryNode.TextTerm(
                            rawText = t.text,
                            field = MatchField.ANY,
                            isQuotedPhrase = true,
                            hasWildcard = t.text.contains('*') || t.text.contains('?')
                        )
                    } else if (t.regexLiteral) {
                        parseRegexWord(t.text)
                    } else {
                        parseModifierOrTerm(t.text)
                    }
                }
                else -> null
            }
        }

        private fun parseRegexWord(text: String): QueryNode {
            if (text.startsWith("/")) {
                val lastSlash = text.lastIndexOf('/')
                val rawPattern = text.substring(1, lastSlash)
                val flags = text.substring(lastSlash + 1)
                val caseSensitive = flags.contains('c') && !flags.contains('i')
                return buildValidatedRegex(rawPattern, MatchField.ANY, caseSensitive)
            }

            val lower = text.lowercase()
            val (field, rest) = when {
                lower.startsWith("name:") -> MatchField.NAME_ONLY to text.substring(5)
                lower.startsWith("path:") -> MatchField.PATH_ONLY to text.substring(5)
                lower.startsWith("content:") -> MatchField.BODY_ONLY to text.substring(8)
                lower.startsWith("body:") -> MatchField.BODY_ONLY to text.substring(5)
                else -> MatchField.ANY to text
            }
            val restLower = rest.lowercase()
            return when {
                restLower.startsWith("cregex:") -> buildValidatedRegex(rest.substring(7), field, caseSensitive = true)
                restLower.startsWith("regex:") -> buildValidatedRegex(rest.substring(6), field, caseSensitive = false)
                restLower.startsWith("re:") -> buildValidatedRegex(rest.substring(3), field, caseSensitive = false)
                else -> buildValidatedRegex(rest, field, caseSensitive = false)
            }
        }

        private fun buildValidatedRegex(
            pattern: String,
            field: MatchField,
            caseSensitive: Boolean
        ): QueryNode.RegexTerm {
            if (pattern.isEmpty()) {
                if (firstError == null) firstError = "正则表达式不能为空"
                return QueryNode.RegexTerm(pattern, field, caseSensitive, isValid = false)
            }
            val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            return try {
                Regex(pattern, options)
                QueryNode.RegexTerm(pattern, field, caseSensitive, isValid = true)
            } catch (e: Throwable) {
                if (firstError == null) {
                    firstError = "正则语法错误: ${e.message?.lineSequence()?.firstOrNull() ?: pattern}"
                }
                QueryNode.RegexTerm(pattern, field, caseSensitive, isValid = false)
            }
        }

        private fun parseModifierOrTerm(word: String): QueryNode {
            val colonIdx = word.indexOf(':')
            if (colonIdx <= 0 || colonIdx == word.lastIndex) {
                return buildTerm(word, MatchField.ANY)
            }
            val prefix = word.substring(0, colonIdx).lowercase()
            val value = word.substring(colonIdx + 1)
            return when (prefix) {
                "ext" -> {
                    val exts = value.split(',', ';')
                        .map { it.trim().trimStart('.').lowercase() }
                        .filter { it.isNotEmpty() }
                        .toSet()
                    if (exts.isEmpty()) buildTerm(word, MatchField.ANY)
                    else QueryNode.ExtFilter(exts)
                }
                "path" -> buildTerm(value, MatchField.PATH_ONLY)
                "name" -> buildTerm(value, MatchField.NAME_ONLY)
                "content", "body" -> buildTerm(value, MatchField.BODY_ONLY)
                "type" -> when (value.lowercase()) {
                    "file", "files", "f" -> QueryNode.TypeFilter(FileTypeFilter.FILE_ONLY)
                    "folder", "folders", "dir", "d" -> QueryNode.TypeFilter(FileTypeFilter.FOLDER_ONLY)
                    else -> {
                        if (firstError == null) firstError = "未知 type 过滤: $value"
                        buildTerm(word, MatchField.ANY)
                    }
                }
                "size" -> parseSizeFilter(value) ?: run {
                    if (firstError == null) firstError = "无法解析 size 条件: $value"
                    buildTerm(word, MatchField.ANY)
                }
                "dm", "date" -> parseDateModifiedFilter(value) ?: run {
                    if (firstError == null) firstError = "无法解析 dm 日期条件: $value"
                    buildTerm(word, MatchField.ANY)
                }
                else -> buildTerm(word, MatchField.ANY)
            }
        }

        private fun buildTerm(raw: String, field: MatchField): QueryNode.TextTerm {
            return QueryNode.TextTerm(
                rawText = raw,
                field = field,
                isQuotedPhrase = false,
                hasWildcard = raw.contains('*') || raw.contains('?')
            )
        }

        private fun parseSizeFilter(expr: String): QueryNode.SizeRangeFilter? {
            val s = expr.trim().lowercase()
            if (".." in s) {
                val parts = s.split("..", limit = 2)
                val min = if (parts[0].isBlank()) null else parseSizeBytes(parts[0]) ?: return null
                val max = if (parts[1].isBlank()) null else parseSizeBytes(parts[1]) ?: return null
                return QueryNode.SizeRangeFilter(min, max)
            }
            return when {
                s.startsWith(">=") -> {
                    val b = parseSizeBytes(s.drop(2)) ?: return null
                    QueryNode.SizeRangeFilter(b, null)
                }
                s.startsWith("<=") -> {
                    val b = parseSizeBytes(s.drop(2)) ?: return null
                    QueryNode.SizeRangeFilter(null, b)
                }
                s.startsWith(">") -> {
                    val b = parseSizeBytes(s.drop(1)) ?: return null
                    QueryNode.SizeRangeFilter(b + 1, null)
                }
                s.startsWith("<") -> {
                    val b = parseSizeBytes(s.drop(1)) ?: return null
                    QueryNode.SizeRangeFilter(null, (b - 1).coerceAtLeast(0L))
                }
                else -> {
                    val b = parseSizeBytes(s) ?: return null
                    QueryNode.SizeRangeFilter(b, b)
                }
            }
        }

        private fun parseSizeBytes(raw: String): Long? {
            val s = raw.trim().lowercase()
            if (s.isEmpty()) return null
            var numEnd = 0
            while (numEnd < s.length && (s[numEnd].isDigit() || s[numEnd] == '.')) {
                numEnd++
            }
            if (numEnd == 0) return null
            val number = s.substring(0, numEnd).toDoubleOrNull() ?: return null
            val unit = s.substring(numEnd).trim()
            val multiplier = when (unit) {
                "", "b" -> 1L
                "k", "kb" -> 1024L
                "m", "mb" -> 1024L * 1024L
                "g", "gb" -> 1024L * 1024L * 1024L
                else -> return null
            }
            return (number * multiplier).toLong().coerceAtLeast(0L)
        }

        private fun parseDateModifiedFilter(expr: String): QueryNode.DateModifiedFilter? {
            val s = expr.trim().lowercase()
            val today = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), zoneId)
            val startOfToday = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val endOfToday = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1

            return when {
                s == "today" -> QueryNode.DateModifiedFilter(startOfToday, endOfToday)
                s == "yesterday" -> {
                    val start = today.minusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                    QueryNode.DateModifiedFilter(start, startOfToday - 1)
                }
                s.endsWith("d") && s.dropLast(1).toLongOrNull() != null -> {
                    val days = s.dropLast(1).toLong()
                    val start = today.minusDays(days).atStartOfDay(zoneId).toInstant().toEpochMilli()
                    QueryNode.DateModifiedFilter(start, null)
                }
                ".." in s -> {
                    val parts = s.split("..", limit = 2)
                    val min = if (parts[0].isBlank()) null else parseDayStartMillis(parts[0]) ?: return null
                    val max = if (parts[1].isBlank()) null else parseDayEndMillis(parts[1]) ?: return null
                    QueryNode.DateModifiedFilter(min, max)
                }
                s.startsWith(">=") -> parseDayStartMillis(s.drop(2))?.let { QueryNode.DateModifiedFilter(it, null) }
                s.startsWith(">") -> parseDayEndMillis(s.drop(1))?.let { QueryNode.DateModifiedFilter(it + 1, null) }
                s.startsWith("<=") -> parseDayEndMillis(s.drop(2))?.let { QueryNode.DateModifiedFilter(null, it) }
                s.startsWith("<") -> parseDayStartMillis(s.drop(1))?.let { QueryNode.DateModifiedFilter(null, it - 1) }
                else -> {
                    val min = parseDayStartMillis(s) ?: return null
                    val max = parseDayEndMillis(s) ?: return null
                    QueryNode.DateModifiedFilter(min, max)
                }
            }
        }

        private fun parseDayStartMillis(dateStr: String): Long? = try {
            LocalDate.parse(dateStr.trim()).atStartOfDay(zoneId).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }

        private fun parseDayEndMillis(dateStr: String): Long? = try {
            LocalDate.parse(dateStr.trim()).plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun collectPositiveHighlights(node: QueryNode?): Pair<List<String>, List<HighlightRule>> {
        if (node == null) return emptyList<String>() to emptyList()
        val terms = mutableListOf<String>()
        val rules = mutableListOf<HighlightRule>()

        fun visit(cur: QueryNode, negated: Boolean) {
            if (negated) return
            when (cur) {
                is QueryNode.TextTerm -> {
                    if (cur.hasWildcard) {
                        val parts = cur.rawText.split('*', '?').filter { it.isNotEmpty() }
                        terms.addAll(parts)
                        parts.forEach { rules.add(HighlightRule.Literal(it)) }
                    } else {
                        terms.add(cur.rawText)
                        rules.add(HighlightRule.Literal(cur.rawText))
                    }
                }
                is QueryNode.RegexTerm -> {
                    if (cur.isValid) {
                        rules.add(HighlightRule.RegexRule(cur.pattern, cur.caseSensitive))
                    }
                }
                is QueryNode.And -> cur.children.forEach { visit(it, false) }
                is QueryNode.Or -> cur.children.forEach { visit(it, false) }
                is QueryNode.Not -> Unit
                else -> Unit
            }
        }

        visit(node, false)
        return terms to rules
    }
}
