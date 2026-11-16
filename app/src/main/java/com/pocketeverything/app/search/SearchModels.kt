package com.pocketeverything.app.search

enum class AccessMode(val code: Int, val label: String) {
    NORMAL(0, "普通模式"),
    SHIZUKU(1, "Shizuku 模式"),
    ROOT(2, "Root 全盘模式");

    companion object {
        fun fromCode(code: Int): AccessMode = entries.firstOrNull { it.code == code } ?: NORMAL
    }
}

enum class Tier2Status(val code: Int, val label: String) {
    NOT_APPLICABLE(0, "非正文文件"),
    PENDING(1, "等待抽取"),
    INDEXED(2, "已索引正文"),
    SKIPPED_SIZE(3, "超出大小上限"),
    SKIPPED_FILTER(4, "路径已过滤"),
    SCANNED_OR_EMPTY(5, "扫描件或无文字层"),
    ENCRYPTED(6, "已加密受保护"),
    UNSUPPORTED_LEGACY_DOC(7, "旧版 .doc 暂不支持正文，建议转为 .docx 或 PDF"),
    ERROR(8, "解析失败");

    companion object {
        fun fromCode(code: Int): Tier2Status = entries.firstOrNull { it.code == code } ?: NOT_APPLICABLE
    }
}

enum class SortOrder(val label: String) {
    RELEVANCE("相关度优先"),
    MODIFIED_DESC("最近修改"),
    SIZE_DESC("体积从大到小"),
    NAME_ASC("文件名 A-Z")
}

enum class QuickScope(val label: String) {
    ALL("全部"),
    DOCS("文档正文"),
    FOLDERS("文件夹"),
    NAME_ONLY("仅文件名"),
    CONTENT_ONLY("仅正文"),
    PDF("PDF"),
    DOCX("Word"),
    TEXT("TXT/MD")
}

data class MatchSnippet(
    val text: String,
    val highlightRanges: List<IntRange>,
    val charOffsetInDoc: Int
)

data class SearchResultItem(
    val id: Long,
    val name: String,
    val path: String,
    val parentPath: String,
    val ext: String,
    val size: Long,
    val mtime: Long,
    val isDir: Boolean,
    val accessMode: AccessMode,
    val tier2Status: Tier2Status,
    val matchedNameRanges: List<IntRange>,
    val matchedPathRanges: List<IntRange>,
    val snippets: List<MatchSnippet>,
    val contentMatchCount: Int,
    val score: Double
)

data class SearchResponse(
    val items: List<SearchResultItem>,
    val totalReturned: Int,
    val elapsedMs: Long,
    val queryError: String? = null
)

data class DatabaseStats(
    val totalEntries: Long,
    val totalFiles: Long,
    val totalDirs: Long,
    val indexedDocs: Long,
    val pendingDocs: Long,
    val skippedDocs: Long,
    val dbSizeBytes: Long
)
