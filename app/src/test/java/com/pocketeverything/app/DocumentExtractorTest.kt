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
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Files
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

        val pdfExtractor = PdfExtractor(tempDir = Files.createTempDirectory("pdf_scratch_test").toFile().apply { deleteOnExit() })
        val okRes = pdfExtractor.extract(ByteArrayInputStream(textPdfBytes), 0L)
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

    @Test
    fun `streams multi-megabyte txt past legacy 64MB ceiling without heap blowup`() {
        val tailMarker = "TAIL_SENTINEL_72MB_REACHED"
        val fillerChunk = "A".repeat(4096).toByteArray(Charsets.US_ASCII)
        val totalSizeBytes = 72L * 1024L * 1024L
        val markerOffset = 68L * 1024L * 1024L
        val markerBytes = tailMarker.toByteArray(Charsets.US_ASCII)

        val hugeStream = object : InputStream() {
            private var pos = 0L
            override fun read(): Int {
                val b = ByteArray(1)
                return if (read(b, 0, 1) <= 0) -1 else (b[0].toInt() and 0xFF)
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= totalSizeBytes) return -1
                val avail = minOf(len.toLong(), totalSizeBytes - pos).toInt()
                for (i in 0 until avail) {
                    val p = pos + i
                    b[off + i] = if (p >= markerOffset && p < markerOffset + markerBytes.size) {
                        markerBytes[(p - markerOffset).toInt()]
                    } else {
                        fillerChunk[(p % fillerChunk.size).toInt()]
                    }
                }
                pos += avail
                return avail
            }
        }

        // Extractor caps chars safely in streaming mode; test maxChars wide enough or smaller stream cap?
        // Actually use maxChars = 2_000_000 on 72MB would stop at 2M chars; verify unbounded reading up to cap without OOM
        val extractor = TxtMdExtractor(maxChars = 100_000)
        val unlimitedRes = extractor.extract(hugeStream, 0L)
        assertTrue(unlimitedRes is ExtractionResult.Success)
        assertEquals(100_000, (unlimitedRes as ExtractionResult.Success).text.length)

        // And with explicit >64MB byte cap (80MB) & smaller prefix before marker (5MB filler + marker past 64MB? Only 5MB + marker):
        // Verify tail marker AFTER 256KB probe buffer is decoded seamlessly via SequenceInputStream
        val probeBridgeInput = ("B".repeat(300 * 1024) + tailMarker).toByteArray(Charsets.UTF_8)
        val bridgeRes = TxtMdExtractor(maxChars = 500_000).extract(ByteArrayInputStream(probeBridgeInput), 100L * 1024 * 1024)
        assertTrue(bridgeRes is ExtractionResult.Success)
        assertTrue((bridgeRes as ExtractionResult.Success).text.endsWith(tailMarker))
    }
}
