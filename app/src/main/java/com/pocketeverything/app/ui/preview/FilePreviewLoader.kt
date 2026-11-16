package com.pocketeverything.app.ui.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import com.pocketeverything.app.filter.FilterConfig
import com.pocketeverything.app.fs.FileSystemProvider
import com.pocketeverything.app.parser.ExtractionResult
import com.pocketeverything.app.parser.ExtractorDispatcher
import com.pocketeverything.app.parser.TxtMdExtractor
import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.search.HighlightRule
import com.pocketeverything.app.search.SearchResultItem
import com.pocketeverything.app.search.SnippetHighlighter
import com.pocketeverything.app.search.SqliteSearchDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

enum class PreviewKind {
    FOLDER,
    PDF,
    TEXT,
    IMAGE,
    ARCHIVE,
    MEDIA,
    UNKNOWN_SNIFF
}

data class ArchiveEntryItem(
    val path: String,
    val size: Long,
    val compressedSize: Long,
    val isDir: Boolean
)

sealed interface PreviewContent {
    data class Text(
        val text: String,
        val matchRanges: List<IntRange>,
        val isMonospace: Boolean = false,
        val statusNote: String? = null
    ) : PreviewContent

    data class Pdf(
        val pdfFile: File?,
        val pageCount: Int,
        val extractedText: String?,
        val matchRanges: List<IntRange>,
        val statusNote: String? = null
    ) : PreviewContent

    data class Image(
        val bitmap: Bitmap,
        val originalWidth: Int,
        val originalHeight: Int,
        val statusNote: String? = null
    ) : PreviewContent

    data class Archive(
        val entries: List<ArchiveEntryItem>,
        val totalCount: Int,
        val truncated: Boolean,
        val statusNote: String? = null
    ) : PreviewContent

    data class Media(
        val thumbnail: Bitmap?,
        val isVideo: Boolean,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val title: String?,
        val artist: String?,
        val mimeType: String?,
        val bitrate: Long,
        val statusNote: String? = null
    ) : PreviewContent

    data class Folder(
        val children: List<SearchResultItem>,
        val statusNote: String? = null
    ) : PreviewContent

    data class BinaryHex(
        val magicSignature: String,
        val hexDump: String,
        val inspectedBytes: Int,
        val statusNote: String? = null
    ) : PreviewContent
}

data class PreviewPayload(
    val item: SearchResultItem,
    val content: PreviewContent,
    val canNavigateBack: Boolean = false
)

/**
 * Multi-format QuickLook loader supporting folders, documents (including scanned PDF visual rendering),
 * code/logs/configs, images, archives, media metadata, and arbitrary binary hex inspection across all access modes.
 */
class FilePreviewLoader(
    private val context: Context,
    private val db: SqliteSearchDatabase,
    private val extractorDispatcher: ExtractorDispatcher,
    private val providerResolver: (AccessMode) -> FileSystemProvider
) {
    private val renderCacheDir: File
        get() = File(context.cacheDir, "preview_render").apply { mkdirs() }

    suspend fun load(
        item: SearchResultItem,
        rules: List<HighlightRule>,
        filterConfig: FilterConfig,
        canNavigateBack: Boolean = false
    ): PreviewPayload = withContext(Dispatchers.IO) {
        val content = when (classifyPreviewKind(item.ext, item.isDir)) {
            PreviewKind.FOLDER -> loadFolder(item)
            PreviewKind.PDF -> loadPdf(item, rules, filterConfig)
            PreviewKind.TEXT -> loadTextOrFallback(item, rules, filterConfig)
            PreviewKind.IMAGE -> loadImageOrFallback(item)
            PreviewKind.ARCHIVE -> loadArchiveOrFallback(item)
            PreviewKind.MEDIA -> loadMediaOrFallback(item)
            PreviewKind.UNKNOWN_SNIFF -> loadUnknownWithSniff(item, rules)
        }
        PreviewPayload(item = item, content = content, canNavigateBack = canNavigateBack)
    }

    private fun loadFolder(item: SearchResultItem): PreviewContent.Folder {
        val children = db.listChildren(item.path, limit = 250)
        val note = if (children.size >= 250) "目录内容较多，仅展示前 250 项" else null
        return PreviewContent.Folder(children = children, statusNote = note)
    }

    private suspend fun loadPdf(
        item: SearchResultItem,
        rules: List<HighlightRule>,
        filterConfig: FilterConfig
    ): PreviewContent {
        val provider = providerResolver(item.accessMode)
        val cachedText = db.getRawDocumentText(item.id)
        val localFile = obtainReadableFile(item, provider, maxCopyBytes = 120L * 1024L * 1024L)

        var textContent = cachedText
        var textNote: String? = null
        if (textContent == null) {
            val extractLimit = filterConfig.maxPdfBytes.coerceAtMost(80L * 1024L * 1024L)
            val live = runCatching {
                if (localFile != null && localFile.canRead()) {
                    localFile.inputStream().use { extractorDispatcher.extractByExtension("pdf", it, extractLimit) }
                } else {
                    provider.openFileStream(item.path, extractLimit) { input ->
                        extractorDispatcher.extractByExtension("pdf", input, extractLimit)
                    }
                }
            }.getOrNull()
            when (live) {
                is ExtractionResult.Success -> textContent = live.text
                is ExtractionResult.Failed -> textNote = live.status.label
                null -> Unit
            }
        }

        val pageCount = if (localFile != null && localFile.canRead()) {
            runCatching {
                ParcelFileDescriptor.open(localFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer -> renderer.pageCount }
                }
            }.getOrElse { err ->
                val isEncrypted = err is SecurityException || (err.message?.contains("password", ignoreCase = true) == true)
                textNote = if (isEncrypted) "PDF 已加密，无法直接渲染页面" else (textNote ?: "页面渲染失败")
                0
            }
        } else {
            0
        }

        if (pageCount == 0 && textContent == null) {
            return loadBinaryHex(item, provider, reason = textNote ?: "无法渲染此 PDF，已显示文件头十六进制信息")
        }

        val ranges = if (!textContent.isNullOrEmpty() && rules.isNotEmpty()) {
            SnippetHighlighter.findMatchRangesForRules(textContent, rules, maxMatches = 500)
        } else {
            emptyList()
        }

        return PreviewContent.Pdf(
            pdfFile = if (pageCount > 0) localFile else null,
            pageCount = pageCount,
            extractedText = textContent,
            matchRanges = ranges,
            statusNote = textNote
        )
    }

    private suspend fun loadTextOrFallback(
        item: SearchResultItem,
        rules: List<HighlightRule>,
        filterConfig: FilterConfig
    ): PreviewContent {
        val ext = item.ext.lowercase(Locale.US)
        val cached = db.getRawDocumentText(item.id)
        if (cached != null) {
            val ranges = SnippetHighlighter.findMatchRangesForRules(cached, rules, maxMatches = 500)
            return PreviewContent.Text(
                text = cached,
                matchRanges = ranges,
                isMonospace = isCodeExtension(ext)
            )
        }

        val provider = providerResolver(item.accessMode)
        if (ext == "docx") {
            val maxBytes = filterConfig.maxDocxBytes.coerceAtMost(50L * 1024L * 1024L)
            val result = runCatching {
                provider.openFileStream(item.path, maxBytes) { input ->
                    extractorDispatcher.extractByExtension("docx", input, maxBytes)
                }
            }.getOrNull()
            return when (result) {
                is ExtractionResult.Success -> {
                    val ranges = SnippetHighlighter.findMatchRangesForRules(result.text, rules, maxMatches = 500)
                    PreviewContent.Text(text = result.text, matchRanges = ranges)
                }
                else -> loadBinaryHex(item, provider, reason = "无法解析 DOCX 文本结构，已显示文件头信息")
            }
        }

        // Plain text / logs / configs / source code: safely read up to 2 MB preview slice even for huge logs.
        val bytes = readSampleBytes(item, provider, MAX_TEXT_PREVIEW_BYTES.toLong())
        if (bytes == null) {
            return loadBinaryHex(item, provider, reason = "无法读取文件内容")
        }
        if (bytes.isEmpty()) {
            return PreviewContent.Text(text = "", matchRanges = emptyList(), statusNote = "空文件")
        }
        val decoded = TxtMdExtractor.decodeSmart(bytes)
        val truncatedNote = if (item.size > MAX_TEXT_PREVIEW_BYTES) {
            "文件较大，仅展示前 ${MAX_TEXT_PREVIEW_BYTES / (1024 * 1024)} MB 内容"
        } else {
            null
        }
        val ranges = SnippetHighlighter.findMatchRangesForRules(decoded, rules, maxMatches = 500)
        return PreviewContent.Text(
            text = decoded,
            matchRanges = ranges,
            isMonospace = isCodeExtension(ext),
            statusNote = truncatedNote
        )
    }

    private suspend fun loadImageOrFallback(item: SearchResultItem): PreviewContent {
        val provider = providerResolver(item.accessMode)
        if (item.size > MAX_IMAGE_BYTES) {
            return loadBinaryHex(item, provider, reason = "图片超过 32 MB 预览上限，建议使用系统相册打开")
        }
        val bytes = readSampleBytes(item, provider, MAX_IMAGE_BYTES)
        if (bytes == null || bytes.isEmpty()) {
            return loadBinaryHex(item, provider, reason = "无法读取图片数据")
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return loadBinaryHex(item, provider, reason = "图片格式不受内置解码器支持")
        }

        val sampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, MAX_IMAGE_DIMENSION)
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
        }.getOrNull()

        return if (bitmap != null) {
            val note = if (sampleSize > 1) "已按 1:$sampleSize 采样预览（原图 ${bounds.outWidth}×${bounds.outHeight}）" else null
            PreviewContent.Image(
                bitmap = bitmap,
                originalWidth = bounds.outWidth,
                originalHeight = bounds.outHeight,
                statusNote = note
            )
        } else {
            loadBinaryHex(item, provider, reason = "图片解码失败")
        }
    }

    private suspend fun loadArchiveOrFallback(item: SearchResultItem): PreviewContent {
        val provider = providerResolver(item.accessMode)
        val parsed = runCatching {
            provider.openFileStream(item.path, MAX_ARCHIVE_STREAM_BYTES) { input ->
                readArchiveEntries(input, MAX_ARCHIVE_ENTRIES)
            }
        }.getOrNull()

        return if (parsed != null && parsed.first.isNotEmpty()) {
            val (entries, total, truncated) = parsed
            val note = if (truncated) "压缩包条目较多，仅展示前 $MAX_ARCHIVE_ENTRIES 项" else null
            PreviewContent.Archive(entries = entries, totalCount = total, truncated = truncated, statusNote = note)
        } else {
            loadBinaryHex(item, provider, reason = "压缩包读取失败或格式非标准 ZIP")
        }
    }

    private suspend fun loadMediaOrFallback(item: SearchResultItem): PreviewContent {
        val provider = providerResolver(item.accessMode)
        val isVideo = item.ext.lowercase(Locale.US) in VIDEO_EXTENSIONS
        val localFile = obtainReadableFile(item, provider, maxCopyBytes = 32L * 1024L * 1024L)

        if (localFile == null || !localFile.canRead()) {
            return PreviewContent.Media(
                thumbnail = null,
                isVideo = isVideo,
                durationMs = 0L,
                width = 0,
                height = 0,
                title = null,
                artist = null,
                mimeType = null,
                bitrate = 0L,
                statusNote = "受限目录大体积媒体暂不支持提取首帧，请点击下方按钮播放"
            )
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(localFile.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L

            val thumb = if (isVideo) {
                runCatching { retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }.getOrNull()
                    ?: runCatching { retriever.frameAtTime }.getOrNull()
            } else {
                retriever.embeddedPicture?.let { pic ->
                    runCatching { BitmapFactory.decodeByteArray(pic, 0, pic.size) }.getOrNull()
                }
            }

            PreviewContent.Media(
                thumbnail = thumb,
                isVideo = isVideo,
                durationMs = duration,
                width = width,
                height = height,
                title = title,
                artist = artist,
                mimeType = mime,
                bitrate = bitrate
            )
        } catch (_: Throwable) {
            loadBinaryHex(item, provider, reason = "媒体元信息解析失败，建议使用系统播放器打开")
        } finally {
            runCatching { retriever.release() }
        }
    }

    private suspend fun loadUnknownWithSniff(
        item: SearchResultItem,
        rules: List<HighlightRule>
    ): PreviewContent {
        val provider = providerResolver(item.accessMode)
        val probe = readSampleBytes(item, provider, SNIFF_PROBE_BYTES.toLong())
        if (probe != null && probe.isNotEmpty() && isLikelyTextBytes(probe)) {
            val fullBytes = if (item.size <= SNIFF_PROBE_BYTES) {
                probe
            } else {
                readSampleBytes(item, provider, MAX_TEXT_PREVIEW_BYTES.toLong()) ?: probe
            }
            val text = TxtMdExtractor.decodeSmart(fullBytes)
            val ranges = SnippetHighlighter.findMatchRangesForRules(text, rules, maxMatches = 500)
            val note = buildString {
                append("自动识别为文本")
                if (item.size > MAX_TEXT_PREVIEW_BYTES) {
                    append(" · 仅展示前 ${MAX_TEXT_PREVIEW_BYTES / (1024 * 1024)} MB")
                }
            }
            return PreviewContent.Text(
                text = text,
                matchRanges = ranges,
                isMonospace = true,
                statusNote = note
            )
        }
        return loadBinaryHex(item, provider, reason = null, preRead = probe)
    }

    private suspend fun loadBinaryHex(
        item: SearchResultItem,
        provider: FileSystemProvider,
        reason: String?,
        preRead: ByteArray? = null
    ): PreviewContent.BinaryHex {
        val bytes = preRead ?: readSampleBytes(item, provider, HEX_INSPECT_BYTES.toLong()) ?: ByteArray(0)
        val slice = if (bytes.size > HEX_INSPECT_BYTES) bytes.copyOf(HEX_INSPECT_BYTES) else bytes
        val magic = if (slice.isEmpty()) "空文件或无读取权限" else detectMagicSignature(slice, item.ext)
        val dump = if (slice.isEmpty()) "" else formatHexDump(slice)
        return PreviewContent.BinaryHex(
            magicSignature = magic,
            hexDump = dump,
            inspectedBytes = slice.size,
            statusNote = reason
        )
    }

    private suspend fun obtainReadableFile(
        item: SearchResultItem,
        provider: FileSystemProvider,
        maxCopyBytes: Long
    ): File? {
        val direct = File(item.path)
        if (direct.isFile && direct.canRead()) return direct
        if (item.size > maxCopyBytes) return null

        cleanStaleRenderCache()
        val extSuffix = if (item.ext.isNotBlank()) ".${item.ext.lowercase(Locale.US)}" else ""
        val staged = File(renderCacheDir, "preview_${item.id}$extSuffix")
        if (staged.exists() && staged.length() == item.size && staged.lastModified() >= item.mtime) {
            return staged
        }
        val copied = provider.copyFileTo(item.path, staged, maxCopyBytes)
        return if (copied && staged.exists()) staged else null
    }

    private fun cleanStaleRenderCache() {
        val cutoff = System.currentTimeMillis() - 20L * 60L * 1000L
        renderCacheDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                runCatching { file.delete() }
            }
        }
    }

    private suspend fun readSampleBytes(
        item: SearchResultItem,
        provider: FileSystemProvider,
        maxBytes: Long
    ): ByteArray? = runCatching {
        val local = File(item.path)
        if (local.isFile && local.canRead()) {
            local.inputStream().use { input -> TxtMdExtractor.readBounded(input, maxBytes) }
        } else {
            provider.openFileStream(item.path, maxBytes) { input ->
                TxtMdExtractor.readBounded(input, maxBytes)
            }
        }
    }.getOrNull()

    companion object {
        private const val MAX_TEXT_PREVIEW_BYTES = 2 * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 32L * 1024L * 1024L
        private const val MAX_IMAGE_DIMENSION = 1920
        private const val MAX_ARCHIVE_STREAM_BYTES = 128L * 1024L * 1024L
        private const val MAX_ARCHIVE_ENTRIES = 500
        private const val SNIFF_PROBE_BYTES = 8 * 1024
        private const val HEX_INSPECT_BYTES = 2048

        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "docx",
            "log", "json", "json5", "xml", "yml", "yaml", "toml", "ini", "conf", "cfg",
            "properties", "env", "csv", "tsv", "html", "htm", "css", "scss", "less",
            "js", "mjs", "cjs", "ts", "jsx", "tsx", "kt", "kts", "java", "py",
            "c", "cpp", "cc", "cxx", "h", "hpp", "rs", "go", "rb", "php", "swift", "dart",
            "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1", "sql", "gradle", "pro",
            "gitignore", "gitattributes", "dockerignore", "editorconfig", "svg",
            "srt", "ass", "vtt", "lrc", "cue", "tex", "bib", "aidl"
        )

        private val CODE_EXTENSIONS = TEXT_EXTENSIONS - setOf("txt", "md", "markdown", "docx")

        private val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "ico"
        )

        private val ARCHIVE_EXTENSIONS = setOf(
            "zip", "apk", "apks", "xapk", "jar", "aab", "war", "ear"
        )

        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "mov", "avi", "webm", "3gp", "ts", "m4v", "flv"
        )

        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "wav", "m4a", "ogg", "aac", "opus", "wma"
        )

        fun classifyPreviewKind(ext: String, isDir: Boolean): PreviewKind {
            if (isDir) return PreviewKind.FOLDER
            val lower = ext.lowercase(Locale.US)
            return when {
                lower == "pdf" -> PreviewKind.PDF
                lower in TEXT_EXTENSIONS -> PreviewKind.TEXT
                lower in IMAGE_EXTENSIONS -> PreviewKind.IMAGE
                lower in ARCHIVE_EXTENSIONS -> PreviewKind.ARCHIVE
                lower in VIDEO_EXTENSIONS || lower in AUDIO_EXTENSIONS -> PreviewKind.MEDIA
                else -> PreviewKind.UNKNOWN_SNIFF
            }
        }

        fun isCodeExtension(ext: String): Boolean = ext.lowercase(Locale.US) in CODE_EXTENSIONS

        fun renderPdfPage(pdfFile: File, pageIndex: Int, targetWidthPx: Int): Bitmap? {
            return runCatching {
                ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        if (pageIndex !in 0 until renderer.pageCount) return null
                        renderer.openPage(pageIndex).use { page ->
                            val safeW = targetWidthPx.coerceIn(320, 2048)
                            val scale = safeW.toFloat() / page.width.coerceAtLeast(1)
                            val targetH = (page.height * scale).toInt().coerceIn(200, 3200)
                            val bmp = Bitmap.createBitmap(safeW, targetH, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmp
                        }
                    }
                }
            }.getOrNull()
        }

        fun isLikelyTextBytes(bytes: ByteArray): Boolean {
            if (bytes.isEmpty()) return false
            // UTF-16 BOM
            if (bytes.size >= 2) {
                if ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
                    (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
                ) {
                    return true
                }
            }
            var controlCount = 0
            val sampleLen = minOf(bytes.size, 4096)
            for (i in 0 until sampleLen) {
                val b = bytes[i].toInt() and 0xFF
                if (b == 0) return false
                if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D && b != 0x0C) {
                    controlCount++
                }
            }
            return controlCount.toDouble() / sampleLen < 0.03
        }

        fun readArchiveEntries(
            inputStream: java.io.InputStream,
            maxEntries: Int = MAX_ARCHIVE_ENTRIES
        ): Triple<List<ArchiveEntryItem>, Int, Boolean> {
            val list = ArrayList<ArchiveEntryItem>()
            var total = 0
            var truncated = false
            ZipInputStream(inputStream).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    total++
                    if (list.size < maxEntries) {
                        list.add(
                            ArchiveEntryItem(
                                path = entry.name,
                                size = entry.size.coerceAtLeast(0L),
                                compressedSize = entry.compressedSize.coerceAtLeast(0L),
                                isDir = entry.isDirectory
                            )
                        )
                    } else {
                        truncated = true
                        if (total >= maxEntries * 4) break
                    }
                    zis.closeEntry()
                }
            }
            return Triple(list, total, truncated)
        }

        fun detectMagicSignature(bytes: ByteArray, ext: String): String {
            if (bytes.size >= 8) {
                val header = String(bytes, 0, minOf(bytes.size, 16), Charsets.ISO_8859_1)
                if (header.startsWith("SQLite format 3")) return "SQLite 数据库"
                if (header.startsWith("%PDF-")) return "PDF 文档"
            }
            if (bytes.size >= 4) {
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                val b2 = bytes[2].toInt() and 0xFF
                val b3 = bytes[3].toInt() and 0xFF
                if (b0 == 0x7F && b1 == 0x45 && b2 == 0x4C && b3 == 0x46) return "ELF 原生二进制 / 动态库"
                if (b0 == 0xD0 && b1 == 0xCF && b2 == 0x11 && b3 == 0xE0) return "Microsoft OLE2 复合文档 (.doc / .xls / .ppt)"
                if (b0 == 0x50 && b1 == 0x4B && (b2 == 0x03 || b2 == 0x05)) return "ZIP / OpenXML 压缩容器"
                if (b0 == 0x64 && b1 == 0x65 && b2 == 0x78 && b3 == 0x0A) return "Android DEX 字节码"
                if (b0 == 0x4D && b1 == 0x5A) return "PE / Windows 可执行文件"
                if (b0 == 0x1F && b1 == 0x8B) return "GZIP 压缩数据"
                if (b0 == 0x37 && b1 == 0x7A && b2 == 0xBC && b3 == 0xAF) return "7-Zip 压缩归档"
                if (b0 == 0x52 && b1 == 0x61 && b2 == 0x72 && b3 == 0x21) return "RAR 压缩归档"
            }
            val cleanExt = ext.uppercase(Locale.US).ifBlank { "BIN" }
            return "$cleanExt 二进制文件"
        }

        fun formatHexDump(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size * 4)
            var offset = 0
            while (offset < bytes.size) {
                val lineLen = minOf(8, bytes.size - offset)
                sb.append(String.format(Locale.US, "%04X  ", offset))
                for (i in 0 until 8) {
                    if (i < lineLen) {
                        sb.append(String.format(Locale.US, "%02X ", bytes[offset + i].toInt() and 0xFF))
                    } else {
                        sb.append("   ")
                    }
                    if (i == 3) sb.append(' ')
                }
                sb.append(' ')
                for (i in 0 until lineLen) {
                    val v = bytes[offset + i].toInt() and 0xFF
                    sb.append(if (v in 0x20..0x7E) v.toChar() else '.')
                }
                offset += lineLen
                if (offset < bytes.size) sb.append('\n')
            }
            return sb.toString()
        }

        private fun computeInSampleSize(width: Int, height: Int, maxDim: Int): Int {
            var inSample = 1
            while ((width / inSample) > maxDim || (height / inSample) > maxDim) {
                inSample *= 2
            }
            return inSample
        }
    }
}
