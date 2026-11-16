package com.pocketeverything.app

import com.pocketeverything.app.ui.FormatUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class FormatUtilsTest {

    @Test
    fun `shortens primary storage prefix and shifts highlight ranges accurately`() {
        val path = "/storage/emulated/0/Download/合同"
        // "合同" sits at index 29..30
        val (shortPath, shifted) = FormatUtils.shortenPath(path, listOf(29..30))
        assertEquals("内部存储/Download/合同", shortPath)
        assertEquals(1, shifted.size)
        assertEquals("合同", shortPath.substring(shifted[0]))

        // Non-matching prefix like /storage/emulated/01 must not be rewritten
        val untouched = "/storage/emulated/01/file"
        assertEquals(untouched, FormatUtils.shortenPath(untouched, emptyList()).first)
    }

    @Test
    fun `formats smart date relative to today and yesterday`() {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.of(2026, 11, 16, 15, 30, 0, 0, zone).toInstant().toEpochMilli()
        val todayMorning = ZonedDateTime.of(2026, 11, 16, 9, 5, 0, 0, zone).toInstant().toEpochMilli()
        val yesterdayEvening = ZonedDateTime.of(2026, 11, 15, 21, 12, 0, 0, zone).toInstant().toEpochMilli()
        val lastYear = ZonedDateTime.of(2025, 3, 1, 10, 0, 0, 0, zone).toInstant().toEpochMilli()

        assertTrue(FormatUtils.formatSmartDate(todayMorning, now).startsWith("今天"))
        assertTrue(FormatUtils.formatSmartDate(yesterdayEvening, now).startsWith("昨天"))
        assertEquals("2025-03-01", FormatUtils.formatSmartDate(lastYear, now))
        assertEquals("--:--", FormatUtils.formatDuration(0L))
        assertEquals("03:25", FormatUtils.formatDuration(205_000L))
        assertEquals("1:01:05", FormatUtils.formatDuration(3_665_000L))
    }

    @Test
    fun `renders release note markdown headings bullets bold and inline code`() {
        val md = """
            ### Pinpoint v1.2.6 更新说明
            * **修复同步崩溃**：优化 `TwoTierIndexManager` 与 [说明](https://example.com)
            - 支持 **Markdown** 渲染
        """.trimIndent()
        val rendered = FormatUtils.renderMarkdown(md)
        val plain = rendered.text
        assertTrue(plain.contains("Pinpoint v1.2.6 更新说明"))
        assertTrue(!plain.contains("###"))
        assertTrue(!plain.contains("**"))
        assertTrue(!plain.contains("`"))
        assertTrue(plain.contains("• 修复同步崩溃：优化 TwoTierIndexManager 与 说明"))
        assertTrue(plain.contains("• 支持 Markdown 渲染"))
        assertTrue(rendered.spanStyles.isNotEmpty())
    }
}
