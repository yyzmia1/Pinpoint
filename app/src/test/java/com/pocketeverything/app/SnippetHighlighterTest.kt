package com.pocketeverything.app

import com.pocketeverything.app.search.SnippetHighlighter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetHighlighterTest {

    @Test
    fun `highlights overlapping terms and builds contextual snippets`() {
        val doc = "甲乙双方于2026年签署本采购合同补充协议，其中明确约定违约金计算标准及交付期限。若有争议以本补充协议为准。"
        val (snippets, totalCount) = SnippetHighlighter.buildSnippets(doc, listOf("补充协议", "违约金"))
        assertEquals(3, totalCount)
        assertTrue(snippets.isNotEmpty())
        val first = snippets[0]
        assertTrue(first.highlightRanges.isNotEmpty())
        val extractedFirstHighlight = first.text.substring(first.highlightRanges[0])
        assertEquals("补充协议", extractedFirstHighlight)
    }
}
