package com.pocketeverything.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object FormatUtils {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
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
}
