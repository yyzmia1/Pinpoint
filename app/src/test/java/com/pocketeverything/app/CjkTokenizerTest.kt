package com.pocketeverything.app

import com.pocketeverything.app.search.CjkTokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CjkTokenizerTest {

    @Test
    fun `tokenizes pure Chinese continuous run into bigrams and unigrams`() {
        val out = CjkTokenizer.tokenizeForIndex("采购合同补充协议")
        assertEquals("采购 购合 合同 同补 补充 充协 协议", out.biAndAscii)
        assertEquals("采 购 合 同 补 充 协 议", out.cjkUnigrams)
    }

    @Test
    fun `normalizes fullwidth uppercase ASCII and mixes cleanly with CJK`() {
        val out = CjkTokenizer.tokenizeForIndex("２０２６年Ａ股_Report_v2.DOCX")
        assertEquals("2026 a report v2 docx", out.biAndAscii)
        assertEquals("年 股", out.cjkUnigrams)
    }

    @Test
    fun `analyzes query literals across 1-char 2-char and multi-char CJK`() {
        val q1 = CjkTokenizer.analyzeQueryLiteral("税")
        assertTrue(q1.phraseSequences.isEmpty())
        assertEquals(listOf("税"), q1.requiredUnigrams)

        val q2 = CjkTokenizer.analyzeQueryLiteral("合同")
        assertEquals(listOf(listOf("合同")), q2.phraseSequences)
        assertTrue(q2.requiredUnigrams.isEmpty())

        val qMixed = CjkTokenizer.analyzeQueryLiteral("2026年采购")
        assertEquals(listOf(listOf("2026"), listOf("年采", "采购")), qMixed.phraseSequences)
        assertTrue(qMixed.requiredUnigrams.isEmpty())

        val qSingleBoundary = CjkTokenizer.analyzeQueryLiteral("A股")
        assertEquals(listOf(listOf("a")), qSingleBoundary.phraseSequences)
        assertEquals(listOf("股"), qSingleBoundary.requiredUnigrams)
    }
}
