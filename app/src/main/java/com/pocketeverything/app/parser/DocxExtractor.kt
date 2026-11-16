package com.pocketeverything.app.parser

import com.pocketeverything.app.search.Tier2Status
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Lightweight streaming .docx extractor using ZipInputStream + standard SAXParserFactory.
 * Extracts body text, tables, headers, footers, and footnotes without heavy Apache POI bloat.
 */
class DocxExtractor(
    private val maxChars: Int = 1_500_000
) : DocumentExtractor {

    override fun extract(inputStream: InputStream, maxBytes: Long): ExtractionResult {
        return try {
            val sb = StringBuilder(8192)
            val saxFactory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }
            }

            ZipInputStream(BufferedInputStream(inputStream)).use { zip ->
                while (sb.length < maxChars) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && isRelevantXml(entry.name)) {
                        val parser = saxFactory.newSAXParser()
                        val handler = DocxXmlHandler(sb, maxChars)
                        val shield = object : FilterInputStream(zip) {
                            override fun close() {
                                // Keep ZipInputStream open for subsequent entries
                            }
                        }
                        parser.parse(shield, handler)
                        if (sb.isNotEmpty() && sb.last() != '\n') {
                            sb.append('\n')
                        }
                    }
                    zip.closeEntry()
                }
            }

            val text = sb.toString().trim()
            if (text.isEmpty()) {
                ExtractionResult.Failed(Tier2Status.SCANNED_OR_EMPTY, "DOCX 无可提取文字")
            } else {
                ExtractionResult.Success(text)
            }
        } catch (t: Throwable) {
            ExtractionResult.Failed(Tier2Status.ERROR, t.message ?: "DOCX 解析失败")
        }
    }

    private fun isRelevantXml(name: String): Boolean {
        val lower = name.lowercase()
        return lower == "word/document.xml" ||
            lower.startsWith("word/header") && lower.endsWith(".xml") ||
            lower.startsWith("word/footer") && lower.endsWith(".xml") ||
            lower == "word/footnotes.xml" ||
            lower == "word/endnotes.xml"
    }

    private class DocxXmlHandler(
        private val out: StringBuilder,
        private val maxChars: Int
    ) : DefaultHandler() {
        private var captureText = false

        override fun startElement(
            uri: String?,
            localName: String?,
            qName: String?,
            attributes: Attributes?
        ) {
            if (out.length >= maxChars) return
            val tag = localName?.takeIf { it.isNotEmpty() } ?: qName?.substringAfter(':') ?: ""
            when (tag) {
                "t", "delText", "instrText" -> captureText = true
                "tab" -> out.append('\t')
                "br", "cr" -> out.append('\n')
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (!captureText || out.length >= maxChars) return
            val remaining = maxChars - out.length
            val toAppend = minOf(length, remaining)
            if (toAppend > 0) {
                out.append(ch, start, toAppend)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val tag = localName?.takeIf { it.isNotEmpty() } ?: qName?.substringAfter(':') ?: ""
            when (tag) {
                "t", "delText", "instrText" -> captureText = false
                "p", "tr" -> {
                    if (out.length < maxChars && (out.isEmpty() || out.last() != '\n')) {
                        out.append('\n')
                    }
                }
                "tc" -> {
                    if (out.length < maxChars && out.isNotEmpty() && !out.last().isWhitespace()) {
                        out.append(' ')
                    }
                }
            }
        }
    }
}
