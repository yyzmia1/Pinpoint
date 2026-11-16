package com.pocketeverything.app.search

enum class MatchField {
    ANY,        // filename, path, or document body
    NAME_ONLY,  // filename only
    PATH_ONLY,  // full path
    BODY_ONLY   // document body only
}

enum class FileTypeFilter {
    FILE_ONLY,
    FOLDER_ONLY
}

enum class CompareOp {
    EQ, GT, GTE, LT, LTE
}

sealed interface QueryNode {
    data class And(val children: List<QueryNode>) : QueryNode
    data class Or(val children: List<QueryNode>) : QueryNode
    data class Not(val child: QueryNode) : QueryNode

    data class TextTerm(
        val rawText: String,
        val field: MatchField = MatchField.ANY,
        val isQuotedPhrase: Boolean = false,
        val hasWildcard: Boolean = false
    ) : QueryNode

    data class ExtFilter(val extensions: Set<String>) : QueryNode
    data class TypeFilter(val type: FileTypeFilter) : QueryNode

    data class SizeRangeFilter(
        val minBytes: Long?,
        val maxBytes: Long?
    ) : QueryNode

    data class DateModifiedFilter(
        val minMillis: Long?,
        val maxMillis: Long?
    ) : QueryNode
}

data class ParsedQuery(
    val root: QueryNode?,
    val positiveHighlightTerms: List<String>,
    val parseError: String? = null
)
