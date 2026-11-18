package com.pocketeverything.app.parser

import com.pocketeverything.app.search.Tier2Status
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.SequenceInputStream
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Streaming text extractor for .txt / .md files with automatic BOM & UTF-8 / GB18030 detection.
 * Keeps peak heap constant even on multi-hundred-MB or unlimited-size files.
 */
class TxtMdExtractor(
    private val maxChars: Int = 2_000_000
) : DocumentExtractor {

    override fun extract(inputStream: InputStream, maxBytes: Long): ExtractionResult {
        return try {
            val bounded = inputStream.boundedBy(maxBytes)
            val probeCap = if (maxBytes > 0L) minOf(PROBE_BYTES.toLong(), maxBytes).toInt() else PROBE_BYTES
            val probe = readUpTo(bounded, probeCap)
            if (probe.isEmpty()) {
                return ExtractionResult.Failed(Tier2Status.SCANNED_OR_EMPTY, "空文本文件")
            }
            val probeTruncated = probe.size == probeCap && (maxBytes <= 0L || maxBytes > PROBE_BYTES)

            val (bomOffset, charset) = detectBomAndCharset(probe, probeTruncated)
            val headStream = ByteArrayInputStream(probe, bomOffset, probe.size - bomOffset)
            val fullStream = SequenceInputStream(headStream, bounded)
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)

            val sb = StringBuilder(minOf(probe.size, 64 * 1024))
            BufferedReader(InputStreamReader(fullStream, decoder), 16 * 1024).use { reader ->
                val buf = CharArray(8192)
                while (sb.length < maxChars) {
                    val toRead = minOf(buf.size, maxChars - sb.length)
                    val n = reader.read(buf, 0, toRead)
                    if (n <= 0) break
                    sb.append(buf, 0, n)
                }
            }

            if (sb.isBlank()) {
                ExtractionResult.Failed(Tier2Status.SCANNED_OR_EMPTY, "空白文本")
            } else {
                ExtractionResult.Success(sb.toString())
            }
        } catch (t: Throwable) {
            ExtractionResult.Failed(Tier2Status.ERROR, t.message ?: "文本读取失败")
        }
    }

    companion object {
        private const val PROBE_BYTES = 256 * 1024

        fun decodeSmart(bytes: ByteArray): String {
            if (bytes.isEmpty()) return ""
            val (offset, charset) = detectBomAndCharset(bytes, allowTrailingSplit = false)
            return String(bytes, offset, bytes.size - offset, charset)
        }

        fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
            if (maxBytes <= 0L) return ByteArray(0)
            val cap = maxBytes.coerceAtMost(16L * 1024L * 1024L).toInt()
            return readUpTo(input, cap)
        }

        private fun readUpTo(input: InputStream, limit: Int): ByteArray {
            if (limit <= 0) return ByteArray(0)
            val out = ByteArrayOutputStream(minOf(limit, 32 * 1024))
            val buf = ByteArray(minOf(limit, 16 * 1024))
            var total = 0
            while (total < limit) {
                val n = input.read(buf, 0, minOf(buf.size, limit - total))
                if (n <= 0) break
                out.write(buf, 0, n)
                total += n
            }
            return out.toByteArray()
        }

        private fun detectBomAndCharset(probe: ByteArray, allowTrailingSplit: Boolean): Pair<Int, Charset> {
            if (probe.size >= 3 && probe[0] == 0xEF.toByte() && probe[1] == 0xBB.toByte() && probe[2] == 0xBF.toByte()) {
                return 3 to Charsets.UTF_8
            }
            if (probe.size >= 2 && probe[0] == 0xFF.toByte() && probe[1] == 0xFE.toByte()) {
                return 2 to Charsets.UTF_16LE
            }
            if (probe.size >= 2 && probe[0] == 0xFE.toByte() && probe[1] == 0xFF.toByte()) {
                return 2 to Charsets.UTF_16BE
            }
            val charset = if (isValidUtf8(probe, allowTrailingSplit)) {
                Charsets.UTF_8
            } else {
                runCatching { Charset.forName("GB18030") }.getOrElse { Charsets.UTF_8 }
            }
            return 0 to charset
        }

        private fun isValidUtf8(bytes: ByteArray, allowTrailingSplit: Boolean): Boolean {
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
                if (i + extra >= len) {
                    if (!allowTrailingSplit) return false
                    for (k in 1 until (len - i)) {
                        val c = bytes[i + k].toInt() and 0xFF
                        if ((c and 0xC0) != 0x80) return false
                    }
                    return true
                }
                for (k in 1..extra) {
                    val c = bytes[i + k].toInt() and 0xFF
                    if ((c and 0xC0) != 0x80) return false
                }
                i += extra + 1
            }
            return true
        }
    }
}
