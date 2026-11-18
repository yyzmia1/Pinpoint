package com.pocketeverything.app.parser

import com.pocketeverything.app.search.Tier2Status
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

/**
 * Extracts text layers page-by-page using PDFBox-Android with disk-spilled scratch memory
 * so large (hundreds of MB) PDFs never trigger heap OutOfMemoryError.
 */
class PdfExtractor(
    private val maxPages: Int = 1000,
    private val maxChars: Int = 2_000_000,
    private val tempDir: File? = null
) : DocumentExtractor {

    override fun extract(inputStream: InputStream, maxBytes: Long): ExtractionResult {
        var doc: PDDocument? = null
        return try {
            val memSetting = MemoryUsageSetting.setupMixed(MIXED_RAM_BUDGET_BYTES).apply {
                if (tempDir != null && (tempDir.exists() || tempDir.mkdirs())) {
                    setTempDir(tempDir)
                }
            }
            doc = PDDocument.load(BufferedInputStream(inputStream.boundedBy(maxBytes)), memSetting)
            if (doc.isEncrypted) {
                runCatching { doc.setAllSecurityToBeRemoved(true) }
            }

            val totalPages = minOf(doc.numberOfPages, maxPages)
            if (totalPages <= 0) {
                return ExtractionResult.Failed(Tier2Status.SCANNED_OR_EMPTY, "PDF 无页面")
            }

            val stripper = PDFTextStripper().apply {
                sortByPosition = true
            }
            val sb = StringBuilder(8192)
            for (page in 1..totalPages) {
                if (sb.length >= maxChars) break
                stripper.startPage = page
                stripper.endPage = page
                val pageText = stripper.getText(doc)
                if (!pageText.isNullOrBlank()) {
                    val remaining = maxChars - sb.length
                    if (pageText.length > remaining) {
                        sb.append(pageText, 0, remaining)
                    } else {
                        sb.append(pageText)
                    }
                    if (sb.isNotEmpty() && sb.last() != '\n') {
                        sb.append('\n')
                    }
                }
            }

            val nonWhitespaceCount = sb.count { !it.isWhitespace() }
            if (nonWhitespaceCount < 4) {
                ExtractionResult.Failed(Tier2Status.SCANNED_OR_EMPTY, "扫描版 PDF 或无内嵌文字层")
            } else {
                ExtractionResult.Success(sb.toString().trim())
            }
        } catch (_: InvalidPasswordException) {
            ExtractionResult.Failed(Tier2Status.ENCRYPTED, "PDF 已加密")
        } catch (t: Throwable) {
            val msg = t.message.orEmpty()
            if (msg.contains("password", ignoreCase = true) || msg.contains("encrypt", ignoreCase = true)) {
                ExtractionResult.Failed(Tier2Status.ENCRYPTED, "PDF 已加密")
            } else {
                ExtractionResult.Failed(Tier2Status.ERROR, msg.ifBlank { "PDF 解析失败" })
            }
        } finally {
            runCatching { doc?.close() }
        }
    }

    private companion object {
        const val MIXED_RAM_BUDGET_BYTES = 16L * 1024L * 1024L
    }
}
