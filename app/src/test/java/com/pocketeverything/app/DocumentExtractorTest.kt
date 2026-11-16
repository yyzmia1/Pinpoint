package com.pocketeverything.app

import com.pocketeverything.app.parser.DocxExtractor
import com.pocketeverything.app.parser.ExtractionResult
import com.pocketeverything.app.parser.ExtractorDispatcher
import com.pocketeverything.app.parser.PdfExtractor
import com.pocketeverything.app.parser.TxtMdExtractor
import com.pocketeverything.app.search.Tier2Status
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentExtractorTest {

    @Test
    fun `extracts UTF8 and GB18030 Chinese txt files without mojibake`() {
        val extractor = TxtMdExtractor()
        val gbkText = "2026年度采购合同正文：甲乙双方约定违约金比例为5%。"
        val gbkBytes = gbkText.toByteArray(Charset.forName("GB18030"))
        val res = extractor.extract(ByteArrayInputStream(gbkBytes), 1024L * 1024L)
        assertTrue(res is ExtractionResult.Success)
        assertEquals(gbkText, (res as ExtractionResult.Success).text)
    }

    @Test
    fun `extracts docx body headers and tables using streaming zip xml parser`() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("word/header1.xml"))
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:p><w:r><w:t>机密采购文件页眉</w:t></w:r></w:p>
                </w:hdr>
                """.trimIndent().toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>第一条：供应商交付周期为15个工作日。</w:t></w:r></w:p>
                    <w:tbl><w:tr><w:tc><w:p><w:r><w:t>产品型号A</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>单价9800元</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                  </w:body>
                </w:document>
                """.trimIndent().toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
        }

        val res = DocxExtractor().extract(ByteArrayInputStream(baos.toByteArray()), 10L * 1024 * 1024)
        assertTrue(res is ExtractionResult.Success)
        val text = (res as ExtractionResult.Success).text
        assertTrue(text.contains("机密采购文件页眉"))
        assertTrue(text.contains("第一条：供应商交付周期为15个工作日。"))
        assertTrue(text.contains("产品型号A"))
        assertTrue(text.contains("单价9800元"))
    }

    @Test
    fun `legacy doc extension gracefully reports unsupported status`() {
        val res = ExtractorDispatcher().extractByExtension("doc", ByteArrayInputStream(byteArrayOf(1, 2, 3)), 1024L)
        assertTrue(res is ExtractionResult.Failed)
        assertEquals(Tier2Status.UNSUPPORTED_LEGACY_DOC, (res as ExtractionResult.Failed).status)
    }

    @Test
    fun `extracts text PDF and flags empty scanned PDF safely`() {
        val textPdfBytes = ByteArrayOutputStream().use { out ->
            PDDocument().use { doc ->
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 12f)
                    cs.newLineAtOffset(50f, 700f)
                    cs.showText("PocketEverything PDF Verification Contract 2026")
                    cs.endText()
                }
                doc.save(out)
            }
            out.toByteArray()
        }

        val pdfExtractor = PdfExtractor()
        val okRes = pdfExtractor.extract(ByteArrayInputStream(textPdfBytes), 10L * 1024 * 1024)
        assertTrue(okRes is ExtractionResult.Success)
        assertTrue((okRes as ExtractionResult.Success).text.contains("PocketEverything PDF Verification Contract 2026"))

        val blankPdfBytes = ByteArrayOutputStream().use { out ->
            PDDocument().use { doc ->
                doc.addPage(PDPage())
                doc.save(out)
            }
            out.toByteArray()
        }
        val emptyRes = pdfExtractor.extract(ByteArrayInputStream(blankPdfBytes), 10L * 1024 * 1024)
        assertTrue(emptyRes is ExtractionResult.Failed)
        assertEquals(Tier2Status.SCANNED_OR_EMPTY, (emptyRes as ExtractionResult.Failed).status)
    }
}
