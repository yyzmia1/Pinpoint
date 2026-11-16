package com.pocketeverything.app

import com.pocketeverything.app.search.FileTypeFilter
import com.pocketeverything.app.search.MatchField
import com.pocketeverything.app.search.QueryNode
import com.pocketeverything.app.search.QuerySyntaxParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class QuerySyntaxParserTest {

    @Test
    fun `parses complex boolean Everything query with filters`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 11, 16, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

        val parsed = QuerySyntaxParser.parse(
            """(合同 | 协议) ext:pdf,docx -草稿 size:>1m dm:today path:"WeChat File" content:违约金 2026*.pdf""",
            zoneId = zone,
            nowMillis = now
        )

        assertNull(parsed.parseError)
        val root = parsed.root as QueryNode.And
        assertEquals(8, root.children.size)

        val orNode = root.children[0] as QueryNode.Or
        assertEquals("合同", (orNode.children[0] as QueryNode.TextTerm).rawText)
        assertEquals("协议", (orNode.children[1] as QueryNode.TextTerm).rawText)

        val extNode = root.children[1] as QueryNode.ExtFilter
        assertEquals(setOf("pdf", "docx"), extNode.extensions)

        val notNode = root.children[2] as QueryNode.Not
        assertEquals("草稿", (notNode.child as QueryNode.TextTerm).rawText)

        val sizeNode = root.children[3] as QueryNode.SizeRangeFilter
        assertEquals(1024L * 1024L + 1L, sizeNode.minBytes)
        assertNull(sizeNode.maxBytes)

        val dmNode = root.children[4] as QueryNode.DateModifiedFilter
        assertTrue(dmNode.minMillis != null && dmNode.maxMillis != null && dmNode.maxMillis!! > dmNode.minMillis!!)

        val pathNode = root.children[5] as QueryNode.TextTerm
        assertEquals(MatchField.PATH_ONLY, pathNode.field)
        assertEquals("WeChat File", pathNode.rawText)

        val contentNode = root.children[6] as QueryNode.TextTerm
        assertEquals(MatchField.BODY_ONLY, contentNode.field)
        assertEquals("违约金", contentNode.rawText)

        val wildcardNode = root.children[7] as QueryNode.TextTerm
        assertTrue(wildcardNode.hasWildcard)
        assertEquals("2026*.pdf", wildcardNode.rawText)
    }

    @Test
    fun `parses size ranges and type filters accurately`() {
        val parsed = QuerySyntaxParser.parse("type:folder size:100k..5m")
        val and = parsed.root as QueryNode.And
        val typeFilter = and.children[0] as QueryNode.TypeFilter
        val sizeFilter = and.children[1] as QueryNode.SizeRangeFilter
        assertEquals(FileTypeFilter.FOLDER_ONLY, typeFilter.type)
        assertEquals(100L * 1024L, sizeFilter.minBytes)
        assertEquals(5L * 1024L * 1024L, sizeFilter.maxBytes)
    }
}
