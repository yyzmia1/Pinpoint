package com.pocketeverything.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object FormatUtils {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "--:--"
        val totalSec = durationMs / 1000L
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    fun formatDateTime(epochMillis: Long): String {
        if (epochMillis <= 0L) return "--"
        return runCatching {
            Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault())
                .format(dateFormatter)
        }.getOrDefault("--")
    }

    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private val mdhm = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val ymd = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** 今天 12:33 / 昨天 08:10 / 11-02 09:15 / 2024-05-01 */
    fun formatSmartDate(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        if (epochMillis <= 0L) return "--"
        val zone = ZoneId.systemDefault()
        val dt = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val d = dt.toLocalDate()
        return when {
            d == today -> "今天 " + dt.format(hm)
            d == today.minusDays(1) -> "昨天 " + dt.format(hm)
            d.year == today.year -> dt.format(mdhm)
            else -> dt.format(ymd)
        }
    }

    private const val PRIMARY_STORAGE = "/storage/emulated/0"
    private const val PRIMARY_STORAGE_LABEL = "内部存储"

    /** Replaces the noisy primary-storage prefix with a short label and shifts highlight ranges to match. */
    fun shortenPath(path: String, ranges: List<IntRange>): Pair<String, List<IntRange>> {
        if (path != PRIMARY_STORAGE && !path.startsWith("$PRIMARY_STORAGE/")) {
            return path to ranges
        }
        val shift = PRIMARY_STORAGE.length - PRIMARY_STORAGE_LABEL.length
        val shortened = PRIMARY_STORAGE_LABEL + path.substring(PRIMARY_STORAGE.length)
        val shifted = ranges
            .filter { it.first >= PRIMARY_STORAGE.length }
            .map { (it.first - shift)..(it.last - shift) }
        return shortened to shifted
    }

    fun buildHighlightedText(
        text: String,
        ranges: List<IntRange>,
        highlightBg: Color,
        highlightFg: Color,
        activeIndex: Int = -1,
        activeBg: Color = highlightBg,
        activeFg: Color = highlightFg
    ): AnnotatedString {
        if (ranges.isEmpty() || text.isEmpty()) return AnnotatedString(text)
        return buildAnnotatedString {
            append(text)
            ranges.forEachIndexed { idx, range ->
                val start = range.first.coerceIn(0, text.length)
                val end = (range.last + 1).coerceIn(start, text.length)
                if (end > start) {
                    val isActive = idx == activeIndex
                    addStyle(
                        style = SpanStyle(
                            background = if (isActive) activeBg else highlightBg,
                            color = if (isActive) activeFg else highlightFg,
                            fontWeight = FontWeight.Bold
                        ),
                        start = start,
                        end = end
                    )
                }
            }
        }
    }

    /**
     * Lightweight Markdown-to-AnnotatedString renderer for GitHub release notes:
     * supports #/##/### headings, bullet/numbered lists, **bold**, `inline code`, and [label](url).
     */
    fun renderMarkdown(
        markdown: String,
        headingColor: Color = Color.Unspecified,
        codeBg: Color = Color(0x22888888),
        codeFg: Color = Color.Unspecified
    ): AnnotatedString {
        val cleaned = markdown.replace("\uFEFF", "").replace("\r\n", "\n").trim()
        if (cleaned.isBlank()) return AnnotatedString("")
        val rawLines = cleaned.split('\n')
        val lines = rawLines.filter { line ->
            val t = line.trim()
            t != "---" && t != "***"
        }
        return buildAnnotatedString {
            var firstEmitted = true
            lines.forEach { rawLine ->
                val trimmed = rawLine.replace("\uFEFF", "").trimStart()
                if (trimmed.isEmpty() && firstEmitted) return@forEach
                if (!firstEmitted) append('\n')
                firstEmitted = false

                val indentLevel = ((rawLine.length - trimmed.length) / 2).coerceIn(0, 3)
                val hashCount = trimmed.takeWhile { it == '#' }.length
                when {
                    hashCount in 1..6 && (trimmed.length == hashCount || trimmed[hashCount] == ' ') -> {
                        val content = trimmed.drop(hashCount).trimStart()
                        withStyle(
                            SpanStyle(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = when (hashCount) {
                                    1, 2 -> 14.5.sp
                                    else -> 13.5.sp
                                },
                                color = headingColor
                            )
                        ) {
                            appendMarkdownInline(content, codeBg, codeFg)
                        }
                    }
                    trimmed.startsWith("* ") || trimmed.startsWith("- ") || trimmed.startsWith("+ ") -> {
                        append("  ".repeat(indentLevel))
                        append("• ")
                        appendMarkdownInline(trimmed.substring(2).trimStart(), codeBg, codeFg)
                    }
                    else -> {
                        append("  ".repeat(indentLevel))
                        appendMarkdownInline(trimmed, codeBg, codeFg)
                    }
                }
            }
        }
    }

    private fun AnnotatedString.Builder.appendMarkdownInline(
        text: String,
        codeBg: Color,
        codeFg: Color
    ) {
        var i = 0
        val n = text.length
        while (i < n) {
            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, color = codeFg)) {
                        append(text, i + 1, end)
                    }
                    i = end + 1
                    continue
                }
            }
            if (i + 1 < n && text[i] == '*' && text[i + 1] == '*') {
                val end = text.indexOf("**", i + 2)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        appendMarkdownInline(text.substring(i + 2, end), codeBg, codeFg)
                    }
                    i = end + 2
                    continue
                }
            }
            if (text[i] == '[') {
                val closeBracket = text.indexOf(']', i + 1)
                if (closeBracket > i + 1 && closeBracket + 1 < n && text[closeBracket + 1] == '(') {
                    val closeParen = text.indexOf(')', closeBracket + 2)
                    if (closeParen > closeBracket + 1) {
                        appendMarkdownInline(text.substring(i + 1, closeBracket), codeBg, codeFg)
                        i = closeParen + 1
                        continue
                    }
                }
            }
            append(text[i])
            i++
        }
    }
}
