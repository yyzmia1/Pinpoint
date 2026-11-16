package com.pocketeverything.app.parser

import com.pocketeverything.app.search.Tier2Status
import java.io.InputStream

sealed interface ExtractionResult {
    data class Success(val text: String) : ExtractionResult
    data class Failed(val status: Tier2Status, val message: String? = null) : ExtractionResult
}

interface DocumentExtractor {
    fun extract(inputStream: InputStream, maxBytes: Long): ExtractionResult
}
