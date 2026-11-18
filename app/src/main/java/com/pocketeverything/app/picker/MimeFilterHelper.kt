package com.pocketeverything.app.picker

import java.util.Locale

/**
 * Pure Kotlin MIME normalization and extension matching helper for system file picker filtering
 * and SAF DocumentsProvider MIME resolution.
 */
object MimeFilterHelper {

    private val EXT_TO_MIMES: Map<String, List<String>> = mapOf(
        // Images
        "jpg" to listOf("image/jpeg"),
        "jpeg" to listOf("image/jpeg"),
        "png" to listOf("image/png"),
        "gif" to listOf("image/gif"),
        "webp" to listOf("image/webp"),
        "bmp" to listOf("image/bmp", "image/x-ms-bmp"),
        "heic" to listOf("image/heic"),
        "heif" to listOf("image/heif"),
        "avif" to listOf("image/avif"),
        "svg" to listOf("image/svg+xml"),
        "ico" to listOf("image/x-icon", "image/vnd.microsoft.icon"),
        "tif" to listOf("image/tiff"),
        "tiff" to listOf("image/tiff"),

        // Video
        "mp4" to listOf("video/mp4"),
        "m4v" to listOf("video/x-m4v", "video/mp4"),
        "mkv" to listOf("video/x-matroska"),
        "mov" to listOf("video/quicktime"),
        "avi" to listOf("video/x-msvideo"),
        "webm" to listOf("video/webm"),
        "3gp" to listOf("video/3gpp"),
        "ts" to listOf("video/mp2ts"),
        "flv" to listOf("video/x-flv"),
        "wmv" to listOf("video/x-ms-wmv"),

        // Audio
        "mp3" to listOf("audio/mpeg", "audio/mp3"),
        "m4a" to listOf("audio/mp4", "audio/x-m4a"),
        "aac" to listOf("audio/aac", "audio/x-aac"),
        "flac" to listOf("audio/flac", "audio/x-flac"),
        "wav" to listOf("audio/wav", "audio/x-wav"),
        "ogg" to listOf("audio/ogg"),
        "oga" to listOf("audio/ogg"),
        "opus" to listOf("audio/opus"),
        "wma" to listOf("audio/x-ms-wma"),
        "amr" to listOf("audio/amr"),
        "mid" to listOf("audio/midi"),
        "midi" to listOf("audio/midi"),

        // Text / Code / Config
        "txt" to listOf("text/plain"),
        "log" to listOf("text/plain"),
        "conf" to listOf("text/plain"),
        "cfg" to listOf("text/plain"),
        "ini" to listOf("text/plain"),
        "properties" to listOf("text/plain"),
        "env" to listOf("text/plain"),
        "pro" to listOf("text/plain"),
        "srt" to listOf("text/plain", "application/x-subrip"),
        "lrc" to listOf("text/plain"),
        "ass" to listOf("text/plain"),
        "vtt" to listOf("text/vtt", "text/plain"),
        "md" to listOf("text/markdown", "text/x-markdown", "text/plain"),
        "markdown" to listOf("text/markdown", "text/x-markdown", "text/plain"),
        "csv" to listOf("text/csv", "text/comma-separated-values"),
        "tsv" to listOf("text/tab-separated-values"),
        "html" to listOf("text/html"),
        "htm" to listOf("text/html"),
        "css" to listOf("text/css"),
        "xml" to listOf("text/xml", "application/xml"),
        "json" to listOf("application/json", "text/json"),
        "js" to listOf("text/javascript", "application/javascript"),
        "mjs" to listOf("text/javascript", "application/javascript"),
        "kt" to listOf("text/x-kotlin", "text/plain"),
        "kts" to listOf("text/x-kotlin", "text/plain"),
        "java" to listOf("text/x-java-source", "text/plain"),
        "py" to listOf("text/x-python", "text/plain"),
        "c" to listOf("text/x-c", "text/plain"),
        "cpp" to listOf("text/x-c++src", "text/plain"),
        "h" to listOf("text/x-chdr", "text/plain"),
        "hpp" to listOf("text/x-c++hdr", "text/plain"),
        "rs" to listOf("text/rust", "text/plain"),
        "go" to listOf("text/x-go", "text/plain"),
        "sh" to listOf("text/x-shellscript", "application/x-sh", "text/plain"),
        "yml" to listOf("text/yaml", "application/x-yaml", "text/plain"),
        "yaml" to listOf("text/yaml", "application/x-yaml", "text/plain"),
        "toml" to listOf("application/toml", "text/plain"),
        "sql" to listOf("application/sql", "text/plain"),

        // Documents & Office
        "pdf" to listOf("application/pdf"),
        "doc" to listOf("application/msword"),
        "docx" to listOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        "xls" to listOf("application/vnd.ms-excel"),
        "xlsx" to listOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        "ppt" to listOf("application/vnd.ms-powerpoint"),
        "pptx" to listOf("application/vnd.openxmlformats-officedocument.presentationml.presentation"),
        "rtf" to listOf("application/rtf", "text/rtf"),
        "odt" to listOf("application/vnd.oasis.opendocument.text"),
        "ods" to listOf("application/vnd.oasis.opendocument.spreadsheet"),
        "odp" to listOf("application/vnd.oasis.opendocument.presentation"),
        "epub" to listOf("application/epub+zip"),

        // Archives & Packages
        "zip" to listOf("application/zip", "application/x-zip-compressed"),
        "rar" to listOf("application/vnd.rar", "application/x-rar-compressed"),
        "7z" to listOf("application/x-7z-compressed"),
        "tar" to listOf("application/x-tar"),
        "gz" to listOf("application/gzip", "application/x-gzip"),
        "tgz" to listOf("application/gzip", "application/x-gzip"),
        "xz" to listOf("application/x-xz"),
        "apk" to listOf("application/vnd.android.package-archive"),
        "jar" to listOf("application/java-archive"),
        "bin" to listOf("application/octet-stream")
    )

    fun extractAcceptedMimes(primaryType: String?, extraTypes: Array<String>?): Set<String> {
        val out = LinkedHashSet<String>()
        fun collect(raw: String?) {
            if (raw.isNullOrBlank()) return
            for (part in raw.split(',')) {
                val clean = part.substringBefore(';').trim().lowercase(Locale.ROOT)
                if (clean.isEmpty() || clean == "*/*" || !clean.contains('/')) continue
                out.add(clean)
            }
        }
        collect(primaryType)
        extraTypes?.forEach(::collect)
        return out
    }

    fun mimeTypeForExtension(ext: String): String {
        val clean = ext.trim().trimStart('.').lowercase(Locale.ROOT)
        if (clean.isEmpty()) return "application/octet-stream"
        return EXT_TO_MIMES[clean]?.firstOrNull() ?: "application/octet-stream"
    }

    fun matches(ext: String, acceptedMimes: Set<String>): Boolean {
        if (acceptedMimes.isEmpty() || "*/*" in acceptedMimes) return true
        val cleanExt = ext.trim().trimStart('.').lowercase(Locale.ROOT)
        if (cleanExt.isEmpty()) return false
        val candidates = EXT_TO_MIMES[cleanExt] ?: return false
        for (accepted in acceptedMimes) {
            if (accepted.endsWith("/*")) {
                val prefix = accepted.dropLast(1) // keeps trailing '/'
                if (candidates.any { it.startsWith(prefix) }) return true
            } else if (accepted in candidates) {
                return true
            }
        }
        return false
    }

    fun matchingExtensions(acceptedMimes: Set<String>): Set<String> {
        if (acceptedMimes.isEmpty() || "*/*" in acceptedMimes) return emptySet()
        val out = LinkedHashSet<String>()
        for (ext in EXT_TO_MIMES.keys) {
            if (matches(ext, acceptedMimes)) out.add(ext)
        }
        return out
    }

    fun describeAcceptedMimes(acceptedMimes: Set<String>): String {
        if (acceptedMimes.isEmpty()) return "全部格式"
        return when {
            acceptedMimes == setOf("image/*") -> "仅图片"
            acceptedMimes == setOf("video/*") -> "仅视频"
            acceptedMimes == setOf("audio/*") -> "仅音频"
            acceptedMimes == setOf("text/*") -> "仅文本"
            acceptedMimes == setOf("application/pdf") -> "仅 PDF"
            acceptedMimes.size == 1 -> {
                val sub = acceptedMimes.first().substringAfter('/').uppercase(Locale.ROOT)
                if (sub.length <= 12) "仅 $sub" else "限定格式"
            }
            else -> "限定格式 (${acceptedMimes.size})"
        }
    }
}
