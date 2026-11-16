package com.pocketeverything.app.parser

import com.pocketeverything.app.search.Tier2Status
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Extracts text from .txt / .md files with automatic BOM & UTF-8 / GB18030 charset detection.
 */
class TxtMdExtractor(
    private val maxChars: Int = 1_500_000
) : DocumentExtractor {

    override fun extract(inputStream: InputStream, maxBytes: Long): ExtractionResult {
        return try {
            val bytes = readBounded(inputStream, maxBytes)
            if (bytes.isEmpty()) {
                return ExtractionResult.Failed(Tier2Status.SCANNED_OR_EMPTY, "空文本文件")
            }

            val decoded = decodeSmart(bytes)
            val trimmed = if (decoded.length > maxChars) decoded.substring(0, maxChars) else decoded
            if (trimmed.isBlank()) {
                ExtractionResult.Failed(Tier2Status.SCANNED_OR_EMPTY, "空白文本")
            } else {
                ExtractionResult.Success(trimmed)
            }
        } catch (t: Throwable) {
            ExtractionResult.Failed(Tier2Status.ERROR, t.message ?: "文本读取失败")
        }
    }

    private fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
        val cap = maxBytes.coerceAtMost(64L * 1024L * 1024L).toInt()
        val out = ByteArrayOutputStream(minOf(cap, 64 * 1024))
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (total < cap) {
            val toRead = minOf(buf.size, cap - total)
            val n = input.read(buf, 0, toRead)
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun decodeSmart(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }

        val charset = if (isValidUtf8(bytes)) {
            Charsets.UTF_8
        } else {
            runCatching { Charset.forName("GB18030") }.getOrElse { Charsets.UTF_8 }
        }
        return String(bytes, charset)
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        val len = bytes.size
        while (i < len) {
            val b = bytes[i].toInt() and 0xFF
            if (b <= 0x7F) {
                i++
                continue
            }
            val extra = when {
                (b and 0xE0) == 0xC0 && b >= 0xC2 -> 1
                (b and 0xF0) == 0xE0 -> 2
                (b and 0xF8) == 0xF0 && b <= 0xF4 -> 3
                else -> return false
            }
            if (i + extra >= len) return false
            for (k in 1..extra) {
                val c = bytes[i + k].toInt() and 0xFF
                if ((c and 0xC0) != 0x80) return false
            }
            i += extra + 1
        }
        return true
    }
}
