package com.pocketeverything.app.parser

import com.pocketeverything.app.search.Tier2Status
import java.io.FilterInputStream
import java.io.InputStream

sealed interface ExtractionResult {
    data class Success(val text: String) : ExtractionResult
    data class Failed(val status: Tier2Status, val message: String? = null) : ExtractionResult
}

interface DocumentExtractor {
    fun extract(inputStream: InputStream, maxBytes: Long): ExtractionResult
}

/**
 * Wraps [input] to read at most [maxBytes] bytes when `maxBytes > 0`, or unlimited when `maxBytes <= 0`.
 */
internal fun InputStream.boundedBy(maxBytes: Long): InputStream {
    if (maxBytes <= 0L) return this
    return object : FilterInputStream(this) {
        private var remaining = maxBytes

        override fun read(): Int {
            if (remaining <= 0L) return -1
            val b = super.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val capped = minOf(len.toLong(), remaining).toInt()
            val n = super.read(b, off, capped)
            if (n > 0) remaining -= n
            return n
        }
    }
}
