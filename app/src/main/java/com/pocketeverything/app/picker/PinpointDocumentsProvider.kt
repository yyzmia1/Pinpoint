package com.pocketeverything.app.picker

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Environment
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.pocketeverything.app.PocketEverythingApp
import com.pocketeverything.app.R
import com.pocketeverything.app.di.AppContainer
import com.pocketeverything.app.fs.StorageRootsHelper
import com.pocketeverything.app.search.FileMetaRow
import com.pocketeverything.app.search.QuickScope
import com.pocketeverything.app.search.SearchResultItem
import com.pocketeverything.app.search.SortOrder
import com.pocketeverything.app.search.SqliteSearchDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

sealed interface PinpointDocId {
    data object Root : PinpointDocId
    data object Recent : PinpointDocId
    data class Dir(val path: String) : PinpointDocId
    data class File(val path: String) : PinpointDocId

    fun encode(): String = when (this) {
        Root -> ROOT_DOC_ID
        Recent -> RECENT_DOC_ID
        is Dir -> "$DIR_PREFIX${normalizePath(path)}"
        is File -> "$FILE_PREFIX${normalizePath(path)}"
    }

    companion object {
        const val ROOT_DOC_ID = "root"
        const val RECENT_DOC_ID = "recent"
        const val DIR_PREFIX = "dir:"
        const val FILE_PREFIX = "file:"

        fun normalizePath(raw: String): String {
            val trimmed = raw.trim()
            return if (trimmed.length > 1) trimmed.trimEnd('/').ifEmpty { "/" } else trimmed
        }

        private fun isValidAbsolutePath(raw: String): Boolean {
            if (raw.isBlank() || raw.contains('\u0000')) return false
            val trimmed = raw.trim()
            if (!trimmed.startsWith('/')) return false
            val segments = trimmed.split('/')
            return segments.none { it == ".." || it == "." }
        }

        fun parse(raw: String?): PinpointDocId? {
            if (raw.isNullOrBlank()) return null
            return when {
                raw == ROOT_DOC_ID -> Root
                raw == RECENT_DOC_ID -> Recent
                raw.startsWith(DIR_PREFIX) -> {
                    val path = raw.removePrefix(DIR_PREFIX)
                    if (!isValidAbsolutePath(path)) null else Dir(normalizePath(path))
                }
                raw.startsWith(FILE_PREFIX) -> {
                    val path = raw.removePrefix(FILE_PREFIX)
                    if (!isValidAbsolutePath(path)) null else {
                        val normalized = normalizePath(path)
                        if (normalized == "/") null else File(normalized)
                    }
                }
                else -> null
            }
        }

        fun fromItem(item: SearchResultItem): PinpointDocId =
            if (item.isDir) Dir(item.path) else File(item.path)

        fun fromMeta(row: FileMetaRow): PinpointDocId =
            if (row.isDir) Dir(row.path) else File(row.path)
    }
}

/**
 * Pure JVM-testable SAF document ID, projection, MIME, and permission-boundary helpers.
 */
object PinpointDocContract {
    const val ROOT_ID = "pinpoint_root"
    const val ROOT_TITLE = "Pinpoint"
    const val ROOT_SUMMARY = "离线全盘索引与文档库"
    const val RECENT_TITLE = "最近修改文件"
    const val MIME_TYPE_DIR = "vnd.android.document/directory"

    val DEFAULT_ROOT_PROJECTION: Array<String> = arrayOf(
        "root_id",
        "mime_types",
        "flags",
        "icon",
        "title",
        "summary",
        "document_id"
    )

    val DEFAULT_DOCUMENT_PROJECTION: Array<String> = arrayOf(
        "document_id",
        "mime_type",
        "_display_name",
        "last_modified",
        "flags",
        "_size"
    )

    data class DocumentRow(
        val documentId: String,
        val mimeType: String,
        val displayName: String,
        val lastModified: Long?,
        val flags: Int,
        val size: Long?
    )

    fun resolveRootProjection(projection: Array<out String>?): Array<String> =
        if (projection == null || projection.isEmpty()) DEFAULT_ROOT_PROJECTION
        else Array(projection.size) { projection[it] }

    fun resolveDocumentProjection(projection: Array<out String>?): Array<String> =
        if (projection == null || projection.isEmpty()) DEFAULT_DOCUMENT_PROJECTION
        else Array(projection.size) { projection[it] }

    fun resolveDocumentMimeType(
        ext: String,
        isDir: Boolean,
        fallbackLookup: ((String) -> String?)? = null
    ): String {
        if (isDir) return MIME_TYPE_DIR
        val primary = MimeFilterHelper.mimeTypeForExtension(ext)
        if (primary != "application/octet-stream" || ext.isBlank()) return primary
        val fallback = fallbackLookup?.invoke(ext.trim().trimStart('.').lowercase(Locale.ROOT))
        return if (!fallback.isNullOrBlank()) fallback else primary
    }

    fun toDocumentRow(
        item: SearchResultItem,
        fallbackLookup: ((String) -> String?)? = null
    ): DocumentRow = DocumentRow(
        documentId = PinpointDocId.fromItem(item).encode(),
        mimeType = resolveDocumentMimeType(item.ext, item.isDir, fallbackLookup),
        displayName = item.name,
        lastModified = item.mtime.takeIf { it > 0L },
        flags = 0,
        size = if (item.isDir) null else item.size
    )

    fun toDocumentRow(
        row: FileMetaRow,
        fallbackLookup: ((String) -> String?)? = null
    ): DocumentRow = DocumentRow(
        documentId = PinpointDocId.fromMeta(row).encode(),
        mimeType = resolveDocumentMimeType(row.ext, row.isDir, fallbackLookup),
        displayName = row.name,
        lastModified = row.mtime.takeIf { it > 0L },
        flags = 0,
        size = if (row.isDir) null else row.size
    )

    fun buildRootRowValues(
        projection: Array<out String>,
        flags: Int,
        iconResId: Int,
        availableBytes: Long? = null
    ): Array<Any?> = Array(projection.size) { idx ->
        when (projection[idx]) {
            "root_id" -> ROOT_ID
            "mime_types" -> "*/*"
            "flags" -> flags
            "icon" -> iconResId
            "title" -> ROOT_TITLE
            "summary" -> ROOT_SUMMARY
            "document_id" -> PinpointDocId.ROOT_DOC_ID
            "available_bytes" -> availableBytes
            else -> null
        }
    }

    fun buildDocumentRowValues(
        projection: Array<out String>,
        doc: DocumentRow
    ): Array<Any?> = Array(projection.size) { idx ->
        when (projection[idx]) {
            "document_id" -> doc.documentId
            "mime_type" -> doc.mimeType
            "_display_name" -> doc.displayName
            "last_modified" -> doc.lastModified
            "flags" -> doc.flags
            "_size" -> doc.size
            else -> null
        }
    }

    fun resolveOpenableFileMeta(
        db: SqliteSearchDatabase,
        documentId: String?,
        mode: String
    ): FileMetaRow {
        if (!mode.startsWith("r") || mode.contains('w') || mode.contains('t') || mode.contains('a')) {
            throw UnsupportedOperationException("Pinpoint 仅支持只读打开文件 (mode=$mode)")
        }
        val parsed = PinpointDocId.parse(documentId)
        if (parsed !is PinpointDocId.File) {
            throw FileNotFoundException("不支持打开目录或无效文档 ID：$documentId")
        }
        val meta = db.getFileMetaByPath(parsed.path)
        if (meta == null || meta.isDir) {
            throw FileNotFoundException("文件未在索引库中或为目录：${parsed.path}")
        }
        return meta
    }

    fun isChildDocument(
        parentDocumentId: String?,
        documentId: String?,
        allowedRootPaths: Set<String> = emptySet()
    ): Boolean {
        val parent = PinpointDocId.parse(parentDocumentId) ?: return false
        val child = PinpointDocId.parse(documentId) ?: return false
        return when (parent) {
            PinpointDocId.Root -> when (child) {
                PinpointDocId.Root -> false
                PinpointDocId.Recent -> true
                is PinpointDocId.Dir ->
                    allowedRootPaths.isEmpty() ||
                        child.path in allowedRootPaths ||
                        allowedRootPaths.any { isStrictDescendant(it, child.path) }
                is PinpointDocId.File ->
                    allowedRootPaths.isEmpty() ||
                        allowedRootPaths.any { isStrictDescendant(it, child.path) }
            }
            PinpointDocId.Recent -> child is PinpointDocId.File
            is PinpointDocId.Dir -> {
                val childPath = when (child) {
                    is PinpointDocId.Dir -> child.path
                    is PinpointDocId.File -> child.path
                    else -> return false
                }
                isStrictDescendant(parent.path, childPath)
            }
            is PinpointDocId.File -> false
        }
    }

    private fun isStrictDescendant(parentDir: String, candidatePath: String): Boolean {
        val cleanParent = PinpointDocId.normalizePath(parentDir)
        val cleanChild = PinpointDocId.normalizePath(candidatePath)
        if (cleanParent == "/") return cleanChild != "/" && cleanChild.startsWith("/")
        return cleanChild.startsWith("$cleanParent/")
    }
}

class PinpointDocumentsProvider : DocumentsProvider() {

    private val androidMimeFallback: (String) -> String? = { ext ->
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    override fun onCreate(): Boolean = true

    private fun requireContainer(): AppContainer {
        val app = context?.applicationContext as? PocketEverythingApp
            ?: throw FileNotFoundException("Pinpoint application context unavailable")
        return app.container
    }

    private fun allowedRootEntries(ctx: Context, container: AppContainer): List<Pair<String, String>> {
        val db = container.database
        val settings = container.settingsRepository.settings.value
        val primaryRoot = runCatching { Environment.getExternalStorageDirectory().absolutePath }.getOrNull()
        val roots = LinkedHashMap<String, String>()
        for (root in StorageRootsHelper.getNormalStorageRoots(ctx)) {
            val label = if (root == primaryRoot) "内部存储" else File(root).name.ifBlank { root }
            roots[PinpointDocId.normalizePath(root)] = label
        }
        val filter = com.pocketeverything.app.filter.FilterEngine(settings.filterConfig)
        if (filter.isDataPotentiallyIndexed(settings.accessMode)) {
            for (privRoot in listOf("/data", "/data/adb", "/data/local", "/data/misc", "/data/app", "/data/system")) {
                if (!filter.shouldSkipTier1Path(privRoot, privRoot.substringAfterLast('/'), isDir = true, settings.accessMode) &&
                    (db.getFileMetaByPath(privRoot) != null || db.listChildren(privRoot, 1).isNotEmpty())
                ) {
                    roots[privRoot] = privRoot
                }
            }
        }
        return roots.entries.map { it.key to it.value }
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = PinpointDocContract.resolveRootProjection(projection)
        val cursor = MatrixCursor(cols)
        val container = runCatching { requireContainer() }.getOrNull() ?: return cursor
        if (!container.settingsRepository.settings.value.systemFilePickerEnabled) {
            return cursor
        }
        val flags = DocumentsContract.Root.FLAG_SUPPORTS_RECENTS or
            DocumentsContract.Root.FLAG_SUPPORTS_SEARCH or
            DocumentsContract.Root.FLAG_LOCAL_ONLY
        val availBytes = runCatching { Environment.getExternalStorageDirectory().usableSpace }.getOrNull()
        cursor.addRow(
            PinpointDocContract.buildRootRowValues(
                projection = cols,
                flags = flags,
                iconResId = R.mipmap.ic_launcher,
                availableBytes = availBytes
            )
        )
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val ctx = context ?: throw FileNotFoundException("Context unavailable")
        val container = requireContainer()
        val db = container.database
        val cols = PinpointDocContract.resolveDocumentProjection(projection)
        val parsed = PinpointDocId.parse(documentId)
            ?: throw FileNotFoundException("Invalid documentId: $documentId")

        val row = when (parsed) {
            PinpointDocId.Root -> PinpointDocContract.DocumentRow(
                documentId = PinpointDocId.ROOT_DOC_ID,
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                displayName = PinpointDocContract.ROOT_TITLE,
                lastModified = null,
                flags = 0,
                size = null
            )
            PinpointDocId.Recent -> PinpointDocContract.DocumentRow(
                documentId = PinpointDocId.RECENT_DOC_ID,
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                displayName = PinpointDocContract.RECENT_TITLE,
                lastModified = null,
                flags = 0,
                size = null
            )
            is PinpointDocId.Dir -> {
                val meta = db.getFileMetaByPath(parsed.path)
                if (meta != null && meta.isDir) {
                    PinpointDocContract.toDocumentRow(meta, androidMimeFallback)
                } else {
                    val rootEntry = allowedRootEntries(ctx, container).firstOrNull { it.first == parsed.path }
                    val displayName = when {
                        rootEntry != null -> rootEntry.second
                        db.listChildren(parsed.path, 1).isNotEmpty() -> File(parsed.path).name.ifBlank { parsed.path }
                        else -> throw FileNotFoundException("Directory not indexed: ${parsed.path}")
                    }
                    PinpointDocContract.DocumentRow(
                        documentId = parsed.encode(),
                        mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                        displayName = displayName,
                        lastModified = null,
                        flags = 0,
                        size = null
                    )
                }
            }
            is PinpointDocId.File -> {
                val meta = db.getFileMetaByPath(parsed.path)
                if (meta == null || meta.isDir) {
                    throw FileNotFoundException("File not indexed: ${parsed.path}")
                }
                PinpointDocContract.toDocumentRow(meta, androidMimeFallback)
            }
        }

        return MatrixCursor(cols).apply {
            addRow(PinpointDocContract.buildDocumentRowValues(cols, row))
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val ctx = context ?: throw FileNotFoundException("Context unavailable")
        val container = requireContainer()
        val db = container.database
        val cols = PinpointDocContract.resolveDocumentProjection(projection)
        val parsed = PinpointDocId.parse(parentDocumentId)
            ?: throw FileNotFoundException("Invalid parentDocumentId: $parentDocumentId")

        val cursor = MatrixCursor(cols)
        when (parsed) {
            PinpointDocId.Root -> {
                cursor.addRow(
                    PinpointDocContract.buildDocumentRowValues(
                        cols,
                        PinpointDocContract.DocumentRow(
                            documentId = PinpointDocId.RECENT_DOC_ID,
                            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                            displayName = PinpointDocContract.RECENT_TITLE,
                            lastModified = null,
                            flags = 0,
                            size = null
                        )
                    )
                )
                for ((rootPath, label) in allowedRootEntries(ctx, container)) {
                    cursor.addRow(
                        PinpointDocContract.buildDocumentRowValues(
                            cols,
                            PinpointDocContract.DocumentRow(
                                documentId = PinpointDocId.Dir(rootPath).encode(),
                                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                                displayName = label,
                                lastModified = null,
                                flags = 0,
                                size = null
                            )
                        )
                    )
                }
            }
            PinpointDocId.Recent -> {
                val recentItems = db.search(
                    rawQuery = "",
                    quickScope = QuickScope.FILES,
                    sortOrder = SortOrder.MODIFIED_DESC,
                    limit = 80,
                    showRecentOnEmpty = true
                ).items
                for (item in recentItems) {
                    cursor.addRow(
                        PinpointDocContract.buildDocumentRowValues(
                            cols,
                            PinpointDocContract.toDocumentRow(item, androidMimeFallback)
                        )
                    )
                }
            }
            is PinpointDocId.Dir -> {
                val meta = db.getFileMetaByPath(parsed.path)
                val allowed = (meta != null && meta.isDir) ||
                    allowedRootEntries(ctx, container).any { it.first == parsed.path } ||
                    db.listChildren(parsed.path, 1).isNotEmpty()
                if (!allowed) {
                    throw FileNotFoundException("Directory not indexed: ${parsed.path}")
                }
                val children = db.listChildren(parsed.path, limit = 500)
                for (item in children) {
                    cursor.addRow(
                        PinpointDocContract.buildDocumentRowValues(
                            cols,
                            PinpointDocContract.toDocumentRow(item, androidMimeFallback)
                        )
                    )
                }
            }
            is PinpointDocId.File -> throw FileNotFoundException("Document is not a directory: $parentDocumentId")
        }
        return cursor
    }

    override fun queryRecentDocuments(rootId: String, projection: Array<out String>?): Cursor {
        if (rootId != PinpointDocContract.ROOT_ID) {
            throw FileNotFoundException("Unknown rootId: $rootId")
        }
        val container = requireContainer()
        val cols = PinpointDocContract.resolveDocumentProjection(projection)
        val cursor = MatrixCursor(cols)
        val recentItems = container.database.search(
            rawQuery = "",
            quickScope = QuickScope.FILES,
            sortOrder = SortOrder.MODIFIED_DESC,
            limit = 60,
            showRecentOnEmpty = true
        ).items
        for (item in recentItems) {
            cursor.addRow(
                PinpointDocContract.buildDocumentRowValues(
                    cols,
                    PinpointDocContract.toDocumentRow(item, androidMimeFallback)
                )
            )
        }
        return cursor
    }

    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?
    ): Cursor {
        if (rootId != PinpointDocContract.ROOT_ID) {
            throw FileNotFoundException("Unknown rootId: $rootId")
        }
        val container = requireContainer()
        val cols = PinpointDocContract.resolveDocumentProjection(projection)
        val cursor = MatrixCursor(cols)
        val items = container.database.search(
            rawQuery = query,
            limit = 100
        ).items
        for (item in items) {
            cursor.addRow(
                PinpointDocContract.buildDocumentRowValues(
                    cols,
                    PinpointDocContract.toDocumentRow(item, androidMimeFallback)
                )
            )
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        val ctx = context ?: return false
        val container = runCatching { requireContainer() }.getOrNull() ?: return false
        val allowedRoots = allowedRootEntries(ctx, container).map { it.first }.toSet()
        return PinpointDocContract.isChildDocument(parentDocumentId, documentId, allowedRoots)
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        val container = requireContainer()
        val meta = PinpointDocContract.resolveOpenableFileMeta(container.database, documentId, mode)
        signal?.throwIfCanceled()

        return try {
            runBlocking(Dispatchers.IO) {
                val job = coroutineContext[Job]
                signal?.setOnCancelListener { job?.cancel() }
                try {
                    container.fileOpener.openReadableDescriptor(meta.path, meta.accessMode)
                } finally {
                    signal?.setOnCancelListener(null)
                }
            }
        } catch (oce: OperationCanceledException) {
            throw oce
        } catch (ce: CancellationException) {
            throw OperationCanceledException().apply { initCause(ce) }
        } catch (fnfe: FileNotFoundException) {
            throw fnfe
        } catch (t: Throwable) {
            throw FileNotFoundException(t.message ?: "无法读取文件：${meta.path}").apply { initCause(t) }
        }
    }
}
