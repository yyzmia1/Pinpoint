package com.pocketeverything.app

import com.pocketeverything.app.search.FileTypeFilter
import com.pocketeverything.app.search.MatchField
import com.pocketeverything.app.search.QueryNode
import com.pocketeverything.app.search.QuerySyntaxParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
            """(合同 | 协议) ext:pdf,docx -草稿 size:>1m dm:today path:"My Folder" content:违约金 2026*.pdf""",
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
        assertEquals("My Folder", pathNode.rawText)

        val contentNode = root.children[6] as QueryNode.TextTerm
        assertEquals(MatchField.BODY_ONLY, contentNode.field)
        assertEquals("违约金", contentNode.rawText)

        val wildcardNode = root.children[7] as QueryNode.TextTerm
        assertTrue(wildcardNode.hasWildcard)
        assertEquals("2026*.pdf", wildcardNode.rawText)
    }

    @Test
    fun `parses size ranges and file vs folder type filters accurately`() {
        val parsed = QuerySyntaxParser.parse("type:folder size:100k..5m")
        val and = parsed.root as QueryNode.And
        val typeFilter = and.children[0] as QueryNode.TypeFilter
        val sizeFilter = and.children[1] as QueryNode.SizeRangeFilter
        assertEquals(FileTypeFilter.FOLDER_ONLY, typeFilter.type)
        assertEquals(100L * 1024L, sizeFilter.minBytes)
        assertEquals(5L * 1024L * 1024L, sizeFilter.maxBytes)

        val fileParsed = QuerySyntaxParser.parse("type:file")
        assertEquals(FileTypeFilter.FILE_ONLY, (fileParsed.root as QueryNode.TypeFilter).type)
    }

    @Test
    fun `parses strict regex literals without splitting internal parens or pipes`() {
        val p1 = QuerySyntaxParser.parse("""regex:^2026(01|02)\.(pdf|docx)$ name:regex:报告_\d+ content:cregex:ERR_[0-9]{3}""")
        assertNull(p1.parseError)
        val and = p1.root as QueryNode.And
        assertEquals(3, and.children.size)

        val r1 = and.children[0] as QueryNode.RegexTerm
        assertEquals("""^2026(01|02)\.(pdf|docx)$""", r1.pattern)
        assertEquals(MatchField.ANY, r1.field)
        assertFalse(r1.caseSensitive)
        assertTrue(r1.isValid)

        val r2 = and.children[1] as QueryNode.RegexTerm
        assertEquals("""报告_\d+""", r2.pattern)
        assertEquals(MatchField.NAME_ONLY, r2.field)

        val r3 = and.children[2] as QueryNode.RegexTerm
        assertEquals("""ERR_[0-9]{3}""", r3.pattern)
        assertEquals(MatchField.BODY_ONLY, r3.field)
        assertTrue(r3.caseSensitive)
    }

    @Test
    fun `distinguishes slash regex from filesystem paths and flags invalid regex`() {
        val slash = QuerySyntaxParser.parse("""/^item_(a|b)$/c /storage/emulated/0""")
        assertNull(slash.parseError)
        val and = slash.root as QueryNode.And
        val regexNode = and.children[0] as QueryNode.RegexTerm
        assertEquals("^item_(a|b)$", regexNode.pattern)
        assertTrue(regexNode.caseSensitive)

        val pathLiteral = and.children[1] as QueryNode.TextTerm
        assertEquals("/storage/emulated/0", pathLiteral.rawText)

        val invalid = QuerySyntaxParser.parse("regex:[unclosed")
        assertNotNull(invalid.parseError)
        assertFalse((invalid.root as QueryNode.RegexTerm).isValid)
    }
}
