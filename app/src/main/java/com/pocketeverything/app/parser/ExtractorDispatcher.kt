package com.pocketeverything.app.parser

import com.pocketeverything.app.search.Tier2Status
import java.io.File
import java.io.InputStream

class ExtractorDispatcher(
    tempDir: File? = null,
    private val txtMdExtractor: DocumentExtractor = TxtMdExtractor(),
    private val docxExtractor: DocumentExtractor = DocxExtractor(),
    private val pdfExtractor: DocumentExtractor = PdfExtractor(tempDir = tempDir)
) {
    fun extractByExtension(ext: String, inputStream: InputStream, maxBytes: Long): ExtractionResult {
        return when (ext.lowercase()) {
            "txt", "md", "markdown" -> txtMdExtractor.extract(inputStream, maxBytes)
            "docx" -> docxExtractor.extract(inputStream, maxBytes)
            "pdf" -> pdfExtractor.extract(inputStream, maxBytes)
            "doc" -> ExtractionResult.Failed(
                Tier2Status.UNSUPPORTED_LEGACY_DOC,
                "旧版 .doc 暂不支持正文索引，建议转为 .docx 或 PDF"
            )
            else -> ExtractionResult.Failed(Tier2Status.NOT_APPLICABLE)
        }
    }
}
