package com.pocketeverything.app.search

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.pocketeverything.app.filter.FilterConfig
import com.pocketeverything.app.filter.Tier2PriorityEntry
import com.pocketeverything.app.filter.Tier2PriorityRule
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.concurrent.withLock

data class FileUpsertEntry(
    val path: String,
    val parentPath: String,
    val name: String,
    val ext: String,
    val size: Long,
    val mtime: Long,
    val isDir: Boolean,
    val accessMode: AccessMode,
    val needsTier2: Boolean,
    val initialTier2Status: Tier2Status
)

data class PendingTier2Task(
    val fileId: Long,
    val path: String,
    val name: String,
    val ext: String,
    val size: Long,
    val mtime: Long,
    val accessMode: AccessMode
)

data class PruneSummary(
    val removedEntries: Int,
    val clearedDocBodies: Int = 0,
    val requeuedDocs: Int = 0,
    val freedBytes: Long = 0L
)

data class FileMetaRow(
    val id: Long,
    val path: String,
    val parentPath: String,
    val name: String,
    val ext: String,
    val size: Long,
    val mtime: Long,
    val isDir: Boolean,
    val accessMode: AccessMode,
    val tier2Status: Tier2Status,
    val tier2Error: String?
)

/**
 * Thread-safe, WAL-enabled SQLite FTS5 search engine backed by androidx.sqlite:sqlite-bundled.
 * Separates write transactions from read queries so background indexing never blocks UI typing.
 */
class SqliteSearchDatabase(
    private val dbPath: String,
    private val visibleMode: () -> Int = { AccessMode.ROOT.code }
) : AutoCloseable {

    private val driver = BundledSQLiteDriver()
    private val isInMemory = dbPath == ":memory:"

    private val writeLock = ReentrantLock()
    private val readLock = if (isInMemory) writeLock else ReentrantLock()

    private val writeConn: SQLiteConnection = driver.open(dbPath)
    private val readConn: SQLiteConnection = if (isInMemory) writeConn else driver.open(dbPath)

    init {
        writeLock.withLock {
            configureWriteConnection(writeConn)
            createSchema(writeConn)
        }
        if (!isInMemory) {
            readLock.withLock {
                configureReadConnection(readConn)
            }
        }
    }

    val visibilityKey: String get() = visibleMode().toString()

    private fun prepareVisible(sql: String): SQLiteStatement {
        val maxMode = visibleMode().coerceIn(AccessMode.NORMAL.code, AccessMode.ROOT.code)
        if (maxMode == AccessMode.ROOT.code) return readConn.prepare(sql)
        val visible = "SELECT * FROM files_meta WHERE access_mode <= $maxMode"
        val scoped = if ("files_meta" in sql) {
            sql.replace("files_meta", "($visible)")
        } else if (maxMode < AccessMode.ROOT.code &&
            Regex("FROM (files_fts|docs_fts|docs_uni_fts)\\b").containsMatchIn(sql)) {
            val clause = " AND rowid IN (SELECT id FROM files_meta WHERE access_mode <= $maxMode) "
            val limitAt = sql.indexOf("LIMIT", ignoreCase = true)
            if (limitAt < 0) sql + clause else sql.substring(0, limitAt) + clause + sql.substring(limitAt)
        } else sql
        return readConn.prepare(scoped)
    }

    private fun configureWriteConnection(conn: SQLiteConnection) {
        if (!isInMemory) {
            conn.execSQL("PRAGMA journal_mode = WAL;")
        }
        conn.execSQL("PRAGMA synchronous = NORMAL;")
        conn.execSQL("PRAGMA temp_store = MEMORY;")
        conn.execSQL("PRAGMA mmap_size = 134217728;")
        conn.execSQL("PRAGMA cache_size = -16384;")
        conn.execSQL("PRAGMA busy_timeout = 3000;")
    }

    private fun configureReadConnection(conn: SQLiteConnection) {
        conn.execSQL("PRAGMA query_only = ON;")
        conn.execSQL("PRAGMA temp_store = MEMORY;")
        conn.execSQL("PRAGMA mmap_size = 134217728;")
        conn.execSQL("PRAGMA cache_size = -16384;")
        conn.execSQL("PRAGMA busy_timeout = 1000;")
    }

    private fun createSchema(conn: SQLiteConnection) {
        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS files_meta (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                path TEXT NOT NULL UNIQUE,
                parent_path TEXT NOT NULL,
                name TEXT NOT NULL,
                ext TEXT NOT NULL,
                size INTEGER NOT NULL,
                mtime INTEGER NOT NULL,
                is_dir INTEGER NOT NULL,
                access_mode INTEGER NOT NULL,
                tier2_status INTEGER NOT NULL DEFAULT 0,
                tier2_error TEXT
            );
            """.trimIndent()
        )
        conn.execSQL("CREATE INDEX IF NOT EXISTS idx_files_parent ON files_meta(parent_path);")
        conn.execSQL("CREATE INDEX IF NOT EXISTS idx_files_ext ON files_meta(ext);")
        conn.execSQL("CREATE INDEX IF NOT EXISTS idx_files_mtime ON files_meta(mtime DESC);")
        conn.execSQL("CREATE INDEX IF NOT EXISTS idx_files_size_desc ON files_meta(size DESC) WHERE is_dir = 0 AND size > 0;")
        conn.execSQL("CREATE INDEX IF NOT EXISTS idx_files_tier2_pending ON files_meta(tier2_status, mtime DESC) WHERE tier2_status = 1;")

        conn.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS files_fts USING fts5(
                name_bi,
                name_uni,
                path_bi,
                path_uni,
                content='',
                contentless_delete=1,
                tokenize='unicode61 remove_diacritics 0'
            );
            """.trimIndent()
        )

        conn.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS docs_fts USING fts5(
                body_bi,
                content='',
                contentless_delete=1,
                tokenize='unicode61 remove_diacritics 0'
            );
            """.trimIndent()
        )

        conn.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS docs_uni_fts USING fts5(
                body_uni,
                content='',
                contentless_delete=1,
                detail=none,
                tokenize='unicode61 remove_diacritics 0'
            );
            """.trimIndent()
        )

        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS docs_raw (
                file_id INTEGER PRIMARY KEY,
                compressed_body BLOB NOT NULL,
                char_len INTEGER NOT NULL,
                indexed_mtime INTEGER NOT NULL
            );
            """.trimIndent()
        )

        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS dir_snapshots (
                path TEXT PRIMARY KEY,
                dir_mtime INTEGER NOT NULL,
                access_mode INTEGER NOT NULL
            );
            """.trimIndent()
        )

        // Materialized persistent WizTree storage analyzer cache (O(1) JVM heap on cold start)
        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS analyzer_dir_stats (
                path TEXT PRIMARY KEY,
                parent_path TEXT NOT NULL,
                name TEXT NOT NULL,
                total_bytes INTEGER NOT NULL,
                file_count INTEGER NOT NULL,
                dir_count INTEGER NOT NULL,
                direct_file_count INTEGER NOT NULL,
                mtime INTEGER NOT NULL,
                access_mode INTEGER NOT NULL
            );
            """.trimIndent()
        )
        conn.execSQL("CREATE INDEX IF NOT EXISTS idx_analyzer_dir_parent ON analyzer_dir_stats(parent_path, total_bytes DESC);")

        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS analyzer_ext_stats (
                ext TEXT PRIMARY KEY,
                total_bytes INTEGER NOT NULL,
                file_count INTEGER NOT NULL
            );
            """.trimIndent()
        )

        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS analyzer_app_stats (
                package_name TEXT PRIMARY KEY,
                fallback_label TEXT,
                total_bytes INTEGER NOT NULL,
                file_count INTEGER NOT NULL,
                locations_blob TEXT NOT NULL
            );
            """.trimIndent()
        )

        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS analyzer_meta (
                meta_key TEXT PRIMARY KEY,
                meta_value TEXT NOT NULL
            );
            """.trimIndent()
        )
    }

    /**
     * Batch upserts Tier 1 file/directory metadata and updates FTS5 indexes incrementally.
     * Unchanged files (same size, mtime, isDir, accessMode) are skipped without rewriting FTS5.
     */
    fun upsertFileBatch(entries: List<FileUpsertEntry>): Int {
        if (entries.isEmpty()) return 0
        return writeLock.withLock {
            var changedCount = 0
            writeConn.execSQL("BEGIN IMMEDIATE;")
            try {
                val selectExisting = writeConn.prepare(
                    "SELECT id, size, mtime, is_dir, access_mode, tier2_status FROM files_meta WHERE path = ?"
                )
                val insertMeta = writeConn.prepare(
                    """
                    INSERT INTO files_meta(path, parent_path, name, ext, size, mtime, is_dir, access_mode, tier2_status, tier2_error)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                    """.trimIndent()
                )
                val updateMeta = writeConn.prepare(
                    """
                    UPDATE files_meta
                    SET parent_path = ?, name = ?, ext = ?, size = ?, mtime = ?, is_dir = ?, access_mode = ?, tier2_status = ?, tier2_error = ?
                    WHERE id = ?
                    """.trimIndent()
                )
                val lastIdStmt = writeConn.prepare("SELECT last_insert_rowid()")
                val delFilesFts = writeConn.prepare("DELETE FROM files_fts WHERE rowid = ?")
                val insFilesFts = writeConn.prepare(
                    "INSERT INTO files_fts(rowid, name_bi, name_uni, path_bi, path_uni) VALUES (?, ?, ?, ?, ?)"
                )
                val delDocsFts = writeConn.prepare("DELETE FROM docs_fts WHERE rowid = ?")
                val delDocsUni = writeConn.prepare("DELETE FROM docs_uni_fts WHERE rowid = ?")
                val delDocsRaw = writeConn.prepare("DELETE FROM docs_raw WHERE file_id = ?")

                try {
                    for (e in entries) {
                        var existingId = -1L
                        var sameContent = false
                        var sameMode = false
                        var oldTier2Status = 0

                        selectExisting.reset()
                        selectExisting.bindText(1, e.path)
                        if (selectExisting.step()) {
                            existingId = selectExisting.getLong(0)
                            val oldSize = selectExisting.getLong(1)
                            val oldMtime = selectExisting.getLong(2)
                            val oldIsDir = selectExisting.getLong(3) != 0L
                            val oldMode = selectExisting.getLong(4).toInt()
                            oldTier2Status = selectExisting.getLong(5).toInt()
                            sameContent = (oldSize == e.size && oldMtime == e.mtime && oldIsDir == e.isDir)
                            sameMode = (oldMode == e.accessMode.code)
                        }

                        val nextTier2Status = if (!e.needsTier2) {
                            e.initialTier2Status.code
                        } else if (sameContent && oldTier2Status == Tier2Status.INDEXED.code) {
                            Tier2Status.INDEXED.code
                        } else {
                            e.initialTier2Status.code
                        }

                        if (sameContent && sameMode && nextTier2Status == oldTier2Status) {
                            continue
                        }

                        val rowId: Long
                        if (existingId < 0) {
                            insertMeta.reset()
                            insertMeta.bindText(1, e.path)
                            insertMeta.bindText(2, e.parentPath)
                            insertMeta.bindText(3, e.name)
                            insertMeta.bindText(4, e.ext)
                            insertMeta.bindLong(5, e.size)
                            insertMeta.bindLong(6, e.mtime)
                            insertMeta.bindLong(7, if (e.isDir) 1L else 0L)
                            insertMeta.bindLong(8, e.accessMode.code.toLong())
                            insertMeta.bindLong(9, nextTier2Status.toLong())
                            insertMeta.step()

                            lastIdStmt.reset()
                            lastIdStmt.step()
                            rowId = lastIdStmt.getLong(0)
                        } else {
                            rowId = existingId
                            updateMeta.reset()
                            updateMeta.bindText(1, e.parentPath)
                            updateMeta.bindText(2, e.name)
                            updateMeta.bindText(3, e.ext)
                            updateMeta.bindLong(4, e.size)
                            updateMeta.bindLong(5, e.mtime)
                            updateMeta.bindLong(6, if (e.isDir) 1L else 0L)
                            updateMeta.bindLong(7, e.accessMode.code.toLong())
                            updateMeta.bindLong(8, nextTier2Status.toLong())
                            updateMeta.bindNull(9)
                            updateMeta.bindLong(10, rowId)
                            updateMeta.step()

                            if (oldTier2Status == Tier2Status.INDEXED.code &&
                                nextTier2Status != Tier2Status.INDEXED.code
                            ) {
                                delDocsFts.reset(); delDocsFts.bindLong(1, rowId); delDocsFts.step()
                                delDocsUni.reset(); delDocsUni.bindLong(1, rowId); delDocsUni.step()
                                delDocsRaw.reset(); delDocsRaw.bindLong(1, rowId); delDocsRaw.step()
                            }

                            if (!sameContent) {
                                delFilesFts.reset()
                                delFilesFts.bindLong(1, rowId)
                                delFilesFts.step()
                            }
                        }

                        if (existingId < 0 || !sameContent) {
                            val nameTok = CjkTokenizer.tokenizeForIndex(e.name, dedupUnigrams = false)
                            val pathTok = CjkTokenizer.tokenizeForIndex(e.path, dedupUnigrams = true)
                            insFilesFts.reset()
                            insFilesFts.bindLong(1, rowId)
                            insFilesFts.bindText(2, nameTok.biAndAscii)
                            insFilesFts.bindText(3, nameTok.cjkUnigrams)
                            insFilesFts.bindText(4, pathTok.biAndAscii)
                            insFilesFts.bindText(5, pathTok.cjkUnigrams)
                            insFilesFts.step()
                        }

                        changedCount++
                    }
                } finally {
                    selectExisting.close()
                    insertMeta.close()
                    updateMeta.close()
                    lastIdStmt.close()
                    delFilesFts.close()
                    insFilesFts.close()
                    delDocsFts.close()
                    delDocsUni.close()
                    delDocsRaw.close()
                }
                writeConn.execSQL("COMMIT;")
            } catch (t: Throwable) {
                runCatching { writeConn.execSQL("ROLLBACK;") }
                throw t
            }
            changedCount
        }
    }

    fun pruneMissingEntries(
        seenPaths: Set<String>, scopeMode: AccessMode? = null,
        canPrune: (String, AccessMode) -> Boolean = { _, _ -> true }
    ): Int {
        return writeLock.withLock {
            val staleIds = ArrayList<Long>()
            val sql = if (scopeMode != null) {
                "SELECT id, path, access_mode FROM files_meta WHERE access_mode = ?"
            } else {
                "SELECT id, path, access_mode FROM files_meta ORDER BY length(path)"
            }
            writeConn.prepare(sql).use { stmt ->
                if (scopeMode != null) stmt.bindLong(1, scopeMode.code.toLong())
                while (stmt.step()) {
                    val id = stmt.getLong(0)
                    val p = stmt.getText(1)
                    if (p !in seenPaths && canPrune(p, AccessMode.fromCode(stmt.getLong(2).toInt()))) {
                        staleIds.add(id)
                    }
                }
            }
            if (staleIds.isEmpty()) return@withLock 0

            deleteEntriesByIdsLocked(staleIds)
            staleIds.size
        }
    }

    fun pruneDirectorySubtree(dirPrefix: String, seenUnderDir: Set<String>): Int {
        return writeLock.withLock {
            pruneDirectorySubtreeLocked(dirPrefix, seenUnderDir)
        }
    }

    private fun deleteEntriesByIdsLocked(ids: List<Long>) {
        if (ids.isEmpty()) return
        for (chunk in ids.chunked(1000)) {
            writeConn.execSQL("BEGIN IMMEDIATE;")
            try {
                val delMeta = writeConn.prepare("DELETE FROM files_meta WHERE id = ?")
                val delFilesFts = writeConn.prepare("DELETE FROM files_fts WHERE rowid = ?")
                val delDocsFts = writeConn.prepare("DELETE FROM docs_fts WHERE rowid = ?")
                val delDocsUni = writeConn.prepare("DELETE FROM docs_uni_fts WHERE rowid = ?")
                val delDocsRaw = writeConn.prepare("DELETE FROM docs_raw WHERE file_id = ?")
                try {
                    for (id in chunk) {
                        delFilesFts.reset(); delFilesFts.bindLong(1, id); delFilesFts.step()
                        delDocsFts.reset(); delDocsFts.bindLong(1, id); delDocsFts.step()
                        delDocsUni.reset(); delDocsUni.bindLong(1, id); delDocsUni.step()
                        delDocsRaw.reset(); delDocsRaw.bindLong(1, id); delDocsRaw.step()
                        delMeta.reset(); delMeta.bindLong(1, id); delMeta.step()
                    }
                } finally {
                    delMeta.close()
                    delFilesFts.close()
                    delDocsFts.close()
                    delDocsUni.close()
                    delDocsRaw.close()
                }
                writeConn.execSQL("COMMIT;")
            } catch (t: Throwable) {
                runCatching { writeConn.execSQL("ROLLBACK;") }
                throw t
            }
        }
    }

    fun fetchPendingTier2Tasks(
        limit: Int = 32,
        priorityOrder: List<Tier2PriorityEntry> = FilterConfig.DEFAULT_TIER2_PRIORITY_ORDER,
        maxMode: AccessMode = AccessMode.ROOT
    ): List<PendingTier2Task> {
        val orderSql = buildTier2OrderClause(priorityOrder)
        return readLock.withLock {
            val result = ArrayList<PendingTier2Task>(limit)
            readConn.prepare(
                """
                SELECT id, path, name, ext, size, mtime, access_mode
                FROM files_meta
                WHERE tier2_status = ? AND access_mode <= ?
                ORDER BY $orderSql
                LIMIT ?
                """.trimIndent()
            ).use { stmt ->
                stmt.bindLong(1, Tier2Status.PENDING.code.toLong())
                stmt.bindLong(2, maxMode.code.toLong())
                stmt.bindLong(3, limit.toLong())
                while (stmt.step()) {
                    result.add(
                        PendingTier2Task(
                            fileId = stmt.getLong(0),
                            path = stmt.getText(1),
                            name = stmt.getText(2),
                            ext = stmt.getText(3),
                            size = stmt.getLong(4),
                            mtime = stmt.getLong(5),
                            accessMode = AccessMode.fromCode(stmt.getLong(6).toInt())
                        )
                    )
                }
            }
            result
        }
    }

    private fun buildTier2OrderClause(priorityOrder: List<Tier2PriorityEntry>): String {
        val clauses = ArrayList<String>(6)
        var hasMtime = false
        var hasSize = false
        for (entry in FilterConfig.normalizePriorityOrder(priorityOrder)) {
            if (!entry.enabled) continue
            when (entry.rule) {
                Tier2PriorityRule.USER_DIRS_FIRST -> clauses.add(
                    """
                    CASE WHEN instr(lower(path), '/download/') > 0
                           OR instr(lower(path), '/documents/') > 0
                           OR instr(lower(path), '/micromsg/download/') > 0
                           OR instr(lower(path), '/qqfile_recv/') > 0
                           OR instr(lower(path), '/weixin/') > 0
                           OR instr(lower(path), '/telegram/') > 0
                           OR instr(lower(path), '/notes/') > 0
                         THEN 0 ELSE 1 END ASC
                    """.trimIndent().replace('\n', ' ')
                )
                Tier2PriorityRule.MTIME_DESC -> {
                    clauses.add("mtime DESC")
                    hasMtime = true
                }
                Tier2PriorityRule.FAST_FORMAT_FIRST -> clauses.add(
                    "CASE WHEN lower(ext) IN ('txt', 'md', 'markdown') THEN 0 WHEN lower(ext) = 'docx' THEN 1 WHEN lower(ext) = 'pdf' THEN 2 ELSE 3 END ASC"
                )
                Tier2PriorityRule.SMALL_SIZE_FIRST -> {
                    clauses.add("size ASC")
                    hasSize = true
                }
            }
        }
        if (!hasMtime) clauses.add("mtime DESC")
        if (!hasSize) clauses.add("size ASC")
        clauses.add("id ASC")
        return clauses.joinToString(", ")
    }

    fun saveTier2Result(
        fileId: Long,
        mtime: Long,
        status: Tier2Status,
        extractedText: String? = null,
        errorMessage: String? = null
    ) {
        val tokenized = if (status == Tier2Status.INDEXED && !extractedText.isNullOrEmpty()) {
            CjkTokenizer.tokenizeForIndex(extractedText, dedupUnigrams = true)
        } else {
            null
        }
        val compressed = if (status == Tier2Status.INDEXED && !extractedText.isNullOrEmpty()) {
            compressUtf8(extractedText)
        } else {
            null
        }

        writeLock.withLock {
            writeConn.execSQL("BEGIN IMMEDIATE;")
            try {
                writeConn.prepare(
                    "UPDATE files_meta SET tier2_status = ?, tier2_error = ? WHERE id = ?"
                ).use { stmt ->
                    stmt.bindLong(1, status.code.toLong())
                    if (errorMessage != null) stmt.bindText(2, errorMessage) else stmt.bindNull(2)
                    stmt.bindLong(3, fileId)
                    stmt.step()
                }

                writeConn.prepare("DELETE FROM docs_fts WHERE rowid = ?").use {
                    it.bindLong(1, fileId); it.step()
                }
                writeConn.prepare("DELETE FROM docs_uni_fts WHERE rowid = ?").use {
                    it.bindLong(1, fileId); it.step()
                }

                if (tokenized != null && compressed != null && extractedText != null) {
                    if (tokenized.biAndAscii.isNotEmpty()) {
                        writeConn.prepare("INSERT INTO docs_fts(rowid, body_bi) VALUES (?, ?)").use {
                            it.bindLong(1, fileId)
                            it.bindText(2, tokenized.biAndAscii)
                            it.step()
                        }
                    }
                    if (tokenized.cjkUnigrams.isNotEmpty()) {
                        writeConn.prepare("INSERT INTO docs_uni_fts(rowid, body_uni) VALUES (?, ?)").use {
                            it.bindLong(1, fileId)
                            it.bindText(2, tokenized.cjkUnigrams)
                            it.step()
                        }
                    }
                    writeConn.prepare(
                        "INSERT OR REPLACE INTO docs_raw(file_id, compressed_body, char_len, indexed_mtime) VALUES (?, ?, ?, ?)"
                    ).use {
                        it.bindLong(1, fileId)
                        it.bindBlob(2, compressed)
                        it.bindLong(3, extractedText.length.toLong())
                        it.bindLong(4, mtime)
                        it.step()
                    }
                } else {
                    writeConn.prepare("DELETE FROM docs_raw WHERE file_id = ?").use {
                        it.bindLong(1, fileId); it.step()
                    }
                }
                writeConn.execSQL("COMMIT;")
            } catch (t: Throwable) {
                runCatching { writeConn.execSQL("ROLLBACK;") }
                throw t
            }
        }
    }

    fun getRawDocumentText(fileId: Long): String? {
        return readLock.withLock {
            readConn.prepare("SELECT compressed_body FROM docs_raw WHERE file_id = ?").use { stmt ->
                stmt.bindLong(1, fileId)
                if (stmt.step()) {
                    decompressUtf8(stmt.getBlob(0))
                } else {
                    null
                }
            }
        }
    }

    /**
     * Fast indexed lookup of a single entry in `files_meta` by its exact [path].
     */
    fun getFileMetaByPath(path: String): FileMetaRow? {
        if (path.isEmpty()) return null
        return readLock.withLock {
            val sql = """
                SELECT id, path, parent_path, name, ext, size, mtime, is_dir, access_mode, tier2_status, tier2_error
                FROM files_meta
                WHERE path = ?
                LIMIT 1
            """.trimIndent()
            readConn.prepare(sql).use { stmt ->
                stmt.bindText(1, path)
                if (stmt.step()) readMetaRow(stmt) else null
            }
        }
    }

    /**
     * Lists indexed direct children of [parentPath], folders first, sorted case-insensitively by name.
     */
    fun listChildren(parentPath: String, limit: Int = 250): List<SearchResultItem> {
        val safeLimit = limit.coerceIn(1, 1000)
        return readLock.withLock {
            val out = ArrayList<SearchResultItem>()
            val sql = """
                SELECT id, path, parent_path, name, ext, size, mtime, is_dir, access_mode, tier2_status, tier2_error
                FROM files_meta
                WHERE parent_path = ?
                ORDER BY is_dir DESC, name COLLATE NOCASE ASC
                LIMIT ?
            """.trimIndent()
            prepareVisible(sql).use { stmt ->
                stmt.bindText(1, parentPath)
                stmt.bindLong(2, safeLimit.toLong())
                while (stmt.step()) {
                    val row = readMetaRow(stmt)
                    out.add(
                        toSearchResultItem(
                            row = row,
                            highlightRules = emptyList(),
                            rawBody = null,
                            tier1NameHit = false,
                            tier1PathHit = false,
                            tier2Hit = false
                        )
                    )
                }
            }
            out
        }
    }

    fun getDirSnapshots(paths: List<String>): Map<String, Long> {
        if (paths.isEmpty()) return emptyMap()
        return readLock.withLock {
            val out = HashMap<String, Long>(paths.size)
            readConn.prepare("SELECT dir_mtime FROM dir_snapshots WHERE path = ?").use { stmt ->
                for (p in paths) {
                    stmt.reset()
                    stmt.bindText(1, p)
                    if (stmt.step()) {
                        out[p] = stmt.getLong(0)
                    }
                }
            }
            out
        }
    }

    /**
     * Returns tracked mutable directories (`path -> mtime`) from `files_meta` for incremental sync,
     * covering all user storage dirs (and indexed `/data/...` dirs when [includeData] is true),
     * while skipping immutable ROM partitions (`/system`, `/vendor`, etc.).
     */
    fun getTrackedMutableDirectories(includeData: Boolean): Map<String, Long> {
        val result = HashMap<String, Long>(1024)
        result.putAll(getTrackedStorageDirectories())
        if (includeData) {
            result.putAll(getTrackedDataDirectories())
        }
        return result
    }

    /**
     * Phase 1 hot-tier directories: `/storage/...` user storage only (typically fast < 1s).
     */
    fun getTrackedStorageDirectories(): Map<String, Long> {
        return readLock.withLock {
            val out = HashMap<String, Long>(1024)
            readConn.prepare(
                "SELECT path, mtime FROM files_meta WHERE is_dir = 1 AND path >= '/storage/' AND path < '/storage0'"
            ).use { stmt ->
                while (stmt.step()) {
                    val p = stmt.getText(0)
                    val isContainerDir = p == "/storage/emulated" || p == "/storage/self" || p == "/storage/enc_emulated"
                    if (!isContainerDir) {
                        out[p] = stmt.getLong(1)
                    }
                }
            }
            out
        }
    }

    /**
     * Phase 2 deep-tier directories: `/data` and `/data/...` (excluding dalvik-cache).
     */
    fun getTrackedDataDirectories(): Map<String, Long> {
        return readLock.withLock {
            val out = HashMap<String, Long>(4096)
            readConn.prepare(
                "SELECT path, mtime FROM files_meta WHERE is_dir = 1 AND (path = '/data' OR (path >= '/data/' AND path < '/data0'))"
            ).use { stmt ->
                while (stmt.step()) {
                    val p = stmt.getText(0)
                    if (p != "/data/dalvik-cache" && !p.startsWith("/data/dalvik-cache/")) {
                        out[p] = stmt.getLong(1)
                    }
                }
            }
            out
        }
    }

    /**
     * Phase 3 Root-mode remaining `/` partition directories (e.g. `/system`, `/vendor`, `/product`, `/mnt`, `/cache`),
     * excluding `/storage/...` and `/data/...` which are already handled in Phase 1 & Phase 2.
     */
    fun getTrackedRootOtherDirectories(): Map<String, Long> {
        return readLock.withLock {
            val out = HashMap<String, Long>(2048)
            readConn.prepare(
                """
                SELECT path, mtime
                FROM files_meta
                WHERE is_dir = 1
                  AND path != '/storage'
                  AND NOT (path >= '/storage/' AND path < '/storage0')
                  AND path != '/data'
                  AND NOT (path >= '/data/' AND path < '/data0')
                """.trimIndent()
            ).use { stmt ->
                while (stmt.step()) {
                    out[stmt.getText(0)] = stmt.getLong(1)
                }
            }
            out
        }
    }

    fun getTrackedDocFiles(limit: Int = 15000): List<PendingTier2Task> =
        getRecentTrackedDocFiles(sinceMtimeMs = 0L, limit = limit)

    fun getRecentTrackedDocFiles(sinceMtimeMs: Long, limit: Int = 1000): List<PendingTier2Task> {
        return readLock.withLock {
            val out = ArrayList<PendingTier2Task>()
            val sql = if (sinceMtimeMs > 0L) {
                """
                SELECT id, path, name, ext, size, mtime, access_mode
                FROM files_meta
                WHERE is_dir = 0 AND tier2_status IN (1, 2) AND mtime >= ?
                ORDER BY mtime DESC
                LIMIT ?
                """.trimIndent()
            } else {
                """
                SELECT id, path, name, ext, size, mtime, access_mode
                FROM files_meta
                WHERE is_dir = 0 AND tier2_status IN (1, 2)
                ORDER BY mtime DESC
                LIMIT ?
                """.trimIndent()
            }
            readConn.prepare(sql).use { stmt ->
                if (sinceMtimeMs > 0L) {
                    stmt.bindLong(1, sinceMtimeMs)
                    stmt.bindLong(2, limit.toLong())
                } else {
                    stmt.bindLong(1, limit.toLong())
                }
                while (stmt.step()) {
                    out.add(
                        PendingTier2Task(
                            fileId = stmt.getLong(0),
                            path = stmt.getText(1),
                            name = stmt.getText(2),
                            ext = stmt.getText(3),
                            size = stmt.getLong(4),
                            mtime = stmt.getLong(5),
                            accessMode = AccessMode.fromCode(stmt.getLong(6).toInt())
                        )
                    )
                }
            }
            out
        }
    }

    fun markTier2Stale(updates: List<Pair<Long, Long>>) {
        if (updates.isEmpty()) return
        writeLock.withLock {
            writeConn.execSQL("BEGIN IMMEDIATE;")
            try {
                val updMeta = writeConn.prepare("UPDATE files_meta SET mtime = ?, tier2_status = 1, tier2_error = NULL WHERE id = ?")
                val delDocsFts = writeConn.prepare("DELETE FROM docs_fts WHERE rowid = ?")
                val delDocsUni = writeConn.prepare("DELETE FROM docs_uni_fts WHERE rowid = ?")
                val delDocsRaw = writeConn.prepare("DELETE FROM docs_raw WHERE file_id = ?")
                try {
                    for ((id, newMtime) in updates) {
                        updMeta.reset(); updMeta.bindLong(1, newMtime); updMeta.bindLong(2, id); updMeta.step()
                        delDocsFts.reset(); delDocsFts.bindLong(1, id); delDocsFts.step()
                        delDocsUni.reset(); delDocsUni.bindLong(1, id); delDocsUni.step()
                        delDocsRaw.reset(); delDocsRaw.bindLong(1, id); delDocsRaw.step()
                    }
                } finally {
                    updMeta.close()
                    delDocsFts.close()
                    delDocsUni.close()
                    delDocsRaw.close()
                }
                writeConn.execSQL("COMMIT;")
            } catch (t: Throwable) {
                runCatching { writeConn.execSQL("ROLLBACK;") }
                throw t
            }
        }
    }

    fun updateDirectoryMtimes(updates: Map<String, Long>) {
        if (updates.isEmpty()) return
        writeLock.withLock {
            writeConn.execSQL("BEGIN IMMEDIATE;")
            try {
                writeConn.prepare("UPDATE files_meta SET mtime = ? WHERE path = ? AND is_dir = 1").use { stmt ->
                    for ((p, mtime) in updates) {
                        stmt.reset()
                        stmt.bindLong(1, mtime)
                        stmt.bindText(2, p)
                        stmt.step()
                    }
                }
                writeConn.execSQL("COMMIT;")
            } catch (t: Throwable) {
                runCatching { writeConn.execSQL("ROLLBACK;") }
                throw t
            }
        }
    }

    fun pruneDirectChildren(parentPath: String, seenDirectPaths: Set<String>, maxMode: AccessMode = AccessMode.ROOT): Int {
        return writeLock.withLock {
            val staleFileIds = ArrayList<Long>()
            val staleDirPaths = ArrayList<String>()
            writeConn.prepare("SELECT id, path, is_dir, access_mode FROM files_meta WHERE parent_path = ?").use { stmt ->
                stmt.bindText(1, parentPath)
                while (stmt.step()) {
                    val id = stmt.getLong(0)
                    val p = stmt.getText(1)
                    val isDir = stmt.getLong(2) != 0L
                    if (p !in seenDirectPaths && stmt.getLong(3) <= maxMode.code) {
                        staleFileIds.add(id)
                        if (isDir) staleDirPaths.add(p)
                    }
                }
            }
            if (staleFileIds.isEmpty()) return@withLock 0
            deleteEntriesByIdsLocked(staleFileIds)
            var removed = staleFileIds.size
            for (dir in staleDirPaths) {
                removed += pruneDirectorySubtreeLocked(dir, emptySet())
            }
            removed
        }
    }

    fun deleteSubtree(dirPath: String): Int {
        val clean = if (dirPath.length > 1) dirPath.trimEnd('/') else dirPath
        return writeLock.withLock {
            var count = pruneDirectorySubtreeLocked(clean, emptySet())
            val selfIds = ArrayList<Long>(1)
            writeConn.prepare("SELECT id FROM files_meta WHERE path = ?").use { stmt ->
                stmt.bindText(1, clean)
                if (stmt.step()) selfIds.add(stmt.getLong(0))
            }
            if (selfIds.isNotEmpty()) {
                deleteEntriesByIdsLocked(selfIds)
                count += selfIds.size
            }
            count
        }
    }

    fun pruneMatchingFilter(filter: com.pocketeverything.app.filter.FilterEngine, mode: AccessMode): Int =
        pruneMatchingFilterDetailed(filter, mode).removedEntries

    /**
     * Truly cleans the index database according to [filter]:
     * 1. Deletes all Tier-1 excluded files/subtrees from `files_meta`, `files_fts`, `docs_*`, and `dir_snapshots`.
     * 2. Re-evaluates remaining documents against Tier-2 whitelist (`tier2AllowedPrefixes`) and size caps:
     *    - Purges `docs_fts`, `docs_uni_fts`, and `docs_raw` for documents no longer eligible.
     *    - Re-queues previously skipped documents (`SKIPPED_FILTER` / `SKIPPED_SIZE`) that are now eligible.
     * 3. Runs FTS5 `optimize`, `PRAGMA wal_checkpoint(TRUNCATE)`, and `VACUUM` so `.db` file size shrinks on disk immediately.
     */
    fun pruneMatchingFilterDetailed(
        filter: com.pocketeverything.app.filter.FilterEngine,
        mode: AccessMode
    ): PruneSummary {
        return writeLock.withLock {
            val beforeBytes = computeDiskFootprintBytes()
            val staleIds = ArrayList<Long>()
            val downgradedDocs = ArrayList<Pair<Long, Int>>()
            val requeuedDocIds = ArrayList<Long>()

            writeConn.prepare(
                "SELECT id, path, name, ext, size, is_dir, tier2_status FROM files_meta"
            ).use { stmt ->
                while (stmt.step()) {
                    val id = stmt.getLong(0)
                    val path = stmt.getText(1)
                    val name = stmt.getText(2)
                    val ext = stmt.getText(3)
                    val size = stmt.getLong(4)
                    val isDir = stmt.getLong(5) != 0L
                    val oldTier2Status = stmt.getLong(6).toInt()

                    if (filter.shouldSkipTier1Path(path, name, isDir, mode)) {
                        staleIds.add(id)
                    } else if (!isDir) {
                        val desired = filter.evaluateTier2Status(path, ext, size, isDir = false)
                        if ((oldTier2Status == Tier2Status.INDEXED.code || oldTier2Status == Tier2Status.PENDING.code) &&
                            (desired == Tier2Status.SKIPPED_FILTER || desired == Tier2Status.SKIPPED_SIZE)
                        ) {
                            downgradedDocs.add(id to desired.code)
                        } else if ((oldTier2Status == Tier2Status.SKIPPED_FILTER.code || oldTier2Status == Tier2Status.SKIPPED_SIZE.code) &&
                            desired == Tier2Status.PENDING
                        ) {
                            requeuedDocIds.add(id)
                        }
                    }
                }
            }

            val staleSnapshotPaths = ArrayList<String>()
            writeConn.prepare("SELECT path FROM dir_snapshots").use { stmt ->
                while (stmt.step()) {
                    val p = stmt.getText(0)
                    val name = p.substringAfterLast('/')
                    if (filter.shouldSkipTier1Path(p, name, isDir = true, mode = mode)) {
                        staleSnapshotPaths.add(p)
                    }
                }
            }

            if (staleIds.isNotEmpty()) {
                deleteEntriesByIdsLocked(staleIds)
            }

            if (staleSnapshotPaths.isNotEmpty()) {
                writeConn.execSQL("BEGIN IMMEDIATE;")
                try {
                    writeConn.prepare("DELETE FROM dir_snapshots WHERE path = ?").use { delSnap ->
                        for (p in staleSnapshotPaths) {
                            delSnap.reset()
                            delSnap.bindText(1, p)
                            delSnap.step()
                        }
                    }
                    writeConn.execSQL("COMMIT;")
                } catch (t: Throwable) {
                    runCatching { writeConn.execSQL("ROLLBACK;") }
                    throw t
                }
            }

            if (downgradedDocs.isNotEmpty() || requeuedDocIds.isNotEmpty()) {
                writeConn.execSQL("BEGIN IMMEDIATE;")
                try {
                    val updStatus = writeConn.prepare(
                        "UPDATE files_meta SET tier2_status = ?, tier2_error = NULL WHERE id = ?"
                    )
                    val delDocsFts = writeConn.prepare("DELETE FROM docs_fts WHERE rowid = ?")
                    val delDocsUni = writeConn.prepare("DELETE FROM docs_uni_fts WHERE rowid = ?")
                    val delDocsRaw = writeConn.prepare("DELETE FROM docs_raw WHERE file_id = ?")
                    try {
                        for ((docId, newCode) in downgradedDocs) {
                            updStatus.reset()
                            updStatus.bindLong(1, newCode.toLong())
                            updStatus.bindLong(2, docId)
                            updStatus.step()

                            delDocsFts.reset(); delDocsFts.bindLong(1, docId); delDocsFts.step()
                            delDocsUni.reset(); delDocsUni.bindLong(1, docId); delDocsUni.step()
                            delDocsRaw.reset(); delDocsRaw.bindLong(1, docId); delDocsRaw.step()
                        }
                        for (docId in requeuedDocIds) {
                            updStatus.reset()
                            updStatus.bindLong(1, Tier2Status.PENDING.code.toLong())
                            updStatus.bindLong(2, docId)
                            updStatus.step()
                        }
                    } finally {
                        updStatus.close()
                        delDocsFts.close()
                        delDocsUni.close()
                        delDocsRaw.close()
                    }
                    writeConn.execSQL("COMMIT;")
                } catch (t: Throwable) {
                    runCatching { writeConn.execSQL("ROLLBACK;") }
                    throw t
                }
            }

            if (staleIds.isNotEmpty() || downgradedDocs.isNotEmpty() || staleSnapshotPaths.isNotEmpty()) {
                reclaimDiskSpaceLocked()
            }

            val afterBytes = computeDiskFootprintBytes()
            PruneSummary(
                removedEntries = staleIds.size,
                clearedDocBodies = downgradedDocs.size,
                requeuedDocs = requeuedDocIds.size,
                freedBytes = (beforeBytes - afterBytes).coerceAtLeast(0L)
            )
        }
    }

    private fun computeDiskFootprintBytes(): Long {
        if (isInMemory) return 0L
        val mainFile = File(dbPath)
        val walFile = File("$dbPath-wal")
        return (if (mainFile.exists()) mainFile.length() else 0L) +
            (if (walFile.exists()) walFile.length() else 0L)
    }

    private fun reclaimDiskSpaceLocked() {
        if (isInMemory) return
        readLock.withLock {
            runCatching { writeConn.execSQL("INSERT INTO files_fts(files_fts) VALUES('optimize');") }
            runCatching { writeConn.execSQL("INSERT INTO docs_fts(docs_fts) VALUES('optimize');") }
            runCatching { writeConn.execSQL("INSERT INTO docs_uni_fts(docs_uni_fts) VALUES('optimize');") }
            runCatching { writeConn.execSQL("PRAGMA wal_checkpoint(TRUNCATE);") }
            runCatching { writeConn.execSQL("VACUUM;") }
            runCatching { writeConn.execSQL("PRAGMA wal_checkpoint(TRUNCATE);") }
        }
    }

    private fun pruneDirectorySubtreeLocked(dirPrefix: String, seenUnderDir: Set<String>): Int {
        val normalizedPrefix = if (dirPrefix == "/") "/" else dirPrefix.trimEnd('/') + "/"
        val upperBound = normalizedPrefix.dropLast(1) + '0'
        val sql = if (dirPrefix == "/") {
            "SELECT id, path FROM files_meta WHERE path > ? AND path < ?"
        } else {
            "SELECT id, path FROM files_meta WHERE path >= ? AND path < ?"
        }
        val staleIds = ArrayList<Long>()
        writeConn.prepare(sql).use { stmt ->
            stmt.bindText(1, normalizedPrefix)
            stmt.bindText(2, upperBound)
            while (stmt.step()) {
                val id = stmt.getLong(0)
                val p = stmt.getText(1)
                if (p !in seenUnderDir) {
                    staleIds.add(id)
                }
            }
        }
        if (staleIds.isEmpty()) return 0
        deleteEntriesByIdsLocked(staleIds)
        return staleIds.size
    }

    fun saveDirSnapshots(snapshots: Map<String, Long>, mode: AccessMode) {
        if (snapshots.isEmpty()) return
        writeLock.withLock {
            writeConn.execSQL("BEGIN IMMEDIATE;")
            try {
                writeConn.prepare(
                    "INSERT OR REPLACE INTO dir_snapshots(path, dir_mtime, access_mode) VALUES (?, ?, ?)"
                ).use { stmt ->
                    for ((p, mtime) in snapshots) {
                        stmt.reset()
                        stmt.bindText(1, p)
                        stmt.bindLong(2, mtime)
                        stmt.bindLong(3, mode.code.toLong())
                        stmt.step()
                    }
                }
                writeConn.execSQL("COMMIT;")
            } catch (t: Throwable) {
                runCatching { writeConn.execSQL("ROLLBACK;") }
                throw t
            }
        }
    }

    fun clearAll() {
        writeLock.withLock {
            writeConn.execSQL("BEGIN IMMEDIATE;")
            try {
                writeConn.execSQL("DELETE FROM files_meta;")
                writeConn.execSQL("DELETE FROM files_fts;")
                writeConn.execSQL("DELETE FROM docs_fts;")
                writeConn.execSQL("DELETE FROM docs_uni_fts;")
                writeConn.execSQL("DELETE FROM docs_raw;")
                writeConn.execSQL("DELETE FROM dir_snapshots;")
                writeConn.execSQL("COMMIT;")
            } catch (t: Throwable) {
                runCatching { writeConn.execSQL("ROLLBACK;") }
                throw t
            }
        }
    }

    fun getStats(): DatabaseStats {
        return readLock.withLock {
            var totalEntries = 0L
            var totalDirs = 0L
            var indexedDocs = 0L
            var pendingDocs = 0L
            var skippedDocs = 0L

            readConn.prepare(
                """
                SELECT
                    COUNT(*),
                    COALESCE(SUM(CASE WHEN is_dir = 1 THEN 1 ELSE 0 END), 0),
                    COALESCE(SUM(CASE WHEN tier2_status = 2 THEN 1 ELSE 0 END), 0),
                    COALESCE(SUM(CASE WHEN tier2_status = 1 THEN 1 ELSE 0 END), 0),
                    COALESCE(SUM(CASE WHEN tier2_status IN (3,4,5,6,7,8) THEN 1 ELSE 0 END), 0)
                FROM files_meta
                """.trimIndent()
            ).use { stmt ->
                if (stmt.step()) {
                    totalEntries = stmt.getLong(0)
                    totalDirs = stmt.getLong(1)
                    indexedDocs = stmt.getLong(2)
                    pendingDocs = stmt.getLong(3)
                    skippedDocs = stmt.getLong(4)
                }
            }

            val sizeBytes = if (isInMemory) {
                0L
            } else {
                val mainFile = File(dbPath)
                val walFile = File("$dbPath-wal")
                (if (mainFile.exists()) mainFile.length() else 0L) +
                    (if (walFile.exists()) walFile.length() else 0L)
            }

            DatabaseStats(
                totalEntries = totalEntries,
                totalFiles = (totalEntries - totalDirs).coerceAtLeast(0L),
                totalDirs = totalDirs,
                indexedDocs = indexedDocs,
                pendingDocs = pendingDocs,
                skippedDocs = skippedDocs,
                dbSizeBytes = sizeBytes
            )
        }
    }

    /**
     * Executes an Everything-syntax search across Tier 1 filenames/paths and Tier 2 document contents.
     */
    fun search(
        rawQuery: String,
        quickScope: QuickScope = QuickScope.ALL,
        sortOrder: SortOrder = SortOrder.RELEVANCE,
        limit: Int = 120,
        showRecentOnEmpty: Boolean = true
    ): SearchResponse {
        val startNs = System.nanoTime()
        val parsed = QuerySyntaxParser.parse(rawQuery)
        val effectiveAst = applyQuickScope(parsed.root, quickScope)

        return readLock.withLock {
            if (parsed.root == null && !showRecentOnEmpty && quickScope == QuickScope.ALL) {
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                return@withLock SearchResponse(emptyList(), 0, elapsedMs, parsed.parseError)
            }

            if (effectiveAst == null) {
                val recent = fetchRecentRows(limit)
                val items = recent.map { row ->
                    toSearchResultItem(
                        row = row,
                        highlightRules = emptyList(),
                        rawBody = null,
                        tier1NameHit = false,
                        tier1PathHit = false,
                        tier2Hit = false
                    )
                }
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                return@withLock SearchResponse(items, items.size, elapsedMs, parsed.parseError)
            }

            val bodyCache = HashMap<Long, String?>()
            val bodyFtsCache = HashMap<String, Set<Long>?>()
            val regexCache = HashMap<String, Regex?>()

            val evalResult = evaluateNode(effectiveAst, emptyList(), bodyCache, regexCache)
            val candidateIds = evalResult.candidateIds

            val rows: List<FileMetaRow> = if (candidateIds == null) {
                fetchMetaRowsWithFilter(effectiveAst, limit * 2)
            } else if (candidateIds.isEmpty()) {
                emptyList()
            } else {
                fetchRowsByIds(candidateIds)
            }

            val highlightRules = parsed.positiveHighlightRules
            val scored = ArrayList<SearchResultItem>(minOf(rows.size, limit))

            for (row in rows) {
                val verification = verifyRowAgainstAst(
                    row = row,
                    node = effectiveAst,
                    bodyCache = bodyCache,
                    bodyFtsCache = bodyFtsCache,
                    regexCache = regexCache
                )
                if (!verification.matched) continue

                val body = if (verification.bodyHit) {
                    bodyCache.getOrPut(row.id) { loadRawTextLocked(row.id) }
                } else {
                    null
                }

                scored.add(
                    toSearchResultItem(
                        row = row,
                        highlightRules = highlightRules,
                        rawBody = body,
                        tier1NameHit = verification.nameHit,
                        tier1PathHit = verification.pathHit,
                        tier2Hit = verification.bodyHit
                    )
                )
            }

            val comparator: Comparator<SearchResultItem> = when (sortOrder) {
                SortOrder.RELEVANCE -> compareByDescending<SearchResultItem> { it.score }
                    .thenByDescending { it.mtime }
                SortOrder.MODIFIED_DESC -> compareByDescending<SearchResultItem> { it.mtime }
                    .thenByDescending { it.score }
                SortOrder.SIZE_DESC -> compareByDescending<SearchResultItem> { it.size }
                    .thenByDescending { it.score }
                SortOrder.NAME_ASC -> compareBy<SearchResultItem> { it.name.lowercase() }
                    .thenByDescending { it.score }
            }
            scored.sortWith(comparator)

            val trimmed = if (scored.size > limit) scored.subList(0, limit).toMutableList() else scored.toMutableList()
            if (highlightRules.isNotEmpty()) {
                for (idx in trimmed.indices) {
                    val item = trimmed[idx]
                    if (item.snippets.isEmpty() && item.tier2Status == Tier2Status.INDEXED && idx < 40) {
                        val rawBody = bodyCache.getOrPut(item.id) { loadRawTextLocked(item.id) }
                        if (!rawBody.isNullOrEmpty()) {
                            val (snips, cnt) = SnippetHighlighter.buildSnippetsForRules(rawBody, highlightRules)
                            if (snips.isNotEmpty()) {
                                trimmed[idx] = item.copy(
                                    snippets = snips,
                                    contentMatchCount = cnt
                                )
                            }
                        }
                    }
                }
            }

            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            SearchResponse(
                items = trimmed,
                totalReturned = trimmed.size,
                elapsedMs = elapsedMs,
                queryError = parsed.parseError
            )
        }
    }

    private fun applyQuickScope(root: QueryNode?, quickScope: QuickScope): QueryNode? {
        val scopeFilter: QueryNode? = when (quickScope) {
            QuickScope.ALL, QuickScope.NAME_ONLY, QuickScope.CONTENT_ONLY -> null
            QuickScope.FOLDERS -> QueryNode.TypeFilter(FileTypeFilter.FOLDER_ONLY)
            QuickScope.FILES -> QueryNode.TypeFilter(FileTypeFilter.FILE_ONLY)
            QuickScope.DOCS -> QueryNode.ExtFilter(setOf("txt", "md", "markdown", "docx", "pdf"))
            QuickScope.PDF -> QueryNode.ExtFilter(setOf("pdf"))
            QuickScope.DOCX -> QueryNode.ExtFilter(setOf("docx"))
            QuickScope.TEXT -> QueryNode.ExtFilter(setOf("txt", "md", "markdown"))
        }
        val scopedRoot = if (root != null && (quickScope == QuickScope.NAME_ONLY || quickScope == QuickScope.CONTENT_ONLY)) {
            rewriteAnyField(
                root,
                if (quickScope == QuickScope.NAME_ONLY) MatchField.NAME_ONLY else MatchField.BODY_ONLY
            )
        } else {
            root
        }
        return when {
            scopedRoot == null && scopeFilter == null -> null
            scopedRoot == null -> scopeFilter
            scopeFilter == null -> scopedRoot
            else -> QueryNode.And(listOf(scopedRoot, scopeFilter))
        }
    }

    private fun rewriteAnyField(node: QueryNode, target: MatchField): QueryNode {
        return when (node) {
            is QueryNode.TextTerm -> if (node.field == MatchField.ANY) node.copy(field = target) else node
            is QueryNode.RegexTerm -> if (node.field == MatchField.ANY) node.copy(field = target) else node
            is QueryNode.And -> node.copy(children = node.children.map { rewriteAnyField(it, target) })
            is QueryNode.Or -> node.copy(children = node.children.map { rewriteAnyField(it, target) })
            is QueryNode.Not -> node.copy(child = rewriteAnyField(node.child, target))
            else -> node
        }
    }

    private data class CandidateEval(
        val candidateIds: LinkedHashSet<Long>?
    )

    private fun evaluateNode(
        node: QueryNode,
        inheritedMetaFilters: List<QueryNode>,
        bodyCache: MutableMap<Long, String?>,
        regexCache: MutableMap<String, Regex?>
    ): CandidateEval {
        return when (node) {
            is QueryNode.TextTerm -> CandidateEval(
                queryCandidatesForTerm(node, inheritedMetaFilters)
            )
            is QueryNode.RegexTerm -> CandidateEval(
                queryCandidatesForRegex(node, inheritedMetaFilters, bodyCache, regexCache)
            )
            is QueryNode.And -> {
                val textOrComplex = ArrayList<QueryNode>()
                val metaFilters = ArrayList<QueryNode>(inheritedMetaFilters)
                for (c in node.children) {
                    if (isPureMetaFilter(c)) metaFilters.add(c) else textOrComplex.add(c)
                }

                if (textOrComplex.isEmpty()) {
                    return CandidateEval(queryPureMetadataIds(metaFilters, limit = 1500))
                }

                var acc: LinkedHashSet<Long>? = null
                for (c in textOrComplex) {
                    if (c is QueryNode.Not) continue
                    val sub = evaluateNode(c, metaFilters, bodyCache, regexCache).candidateIds ?: continue
                    acc = if (acc == null) {
                        sub
                    } else {
                        acc.retainAll(sub)
                        acc
                    }
                    if (acc.isEmpty()) break
                }

                if (acc == null) {
                    return CandidateEval(queryPureMetadataIds(metaFilters, limit = 1500))
                }
                CandidateEval(acc)
            }
            is QueryNode.Or -> {
                val union = LinkedHashSet<Long>()
                for (c in node.children) {
                    val sub = evaluateNode(c, inheritedMetaFilters, bodyCache, regexCache).candidateIds
                    if (sub == null) {
                        return CandidateEval(null)
                    }
                    union.addAll(sub)
                }
                CandidateEval(union)
            }
            is QueryNode.Not -> CandidateEval(null)
            is QueryNode.ExtFilter,
            is QueryNode.TypeFilter,
            is QueryNode.SizeRangeFilter,
            is QueryNode.DateModifiedFilter -> {
                CandidateEval(queryPureMetadataIds(inheritedMetaFilters + node, limit = 1500))
            }
        }
    }

    private fun isPureMetaFilter(node: QueryNode): Boolean {
        return node is QueryNode.ExtFilter ||
            node is QueryNode.TypeFilter ||
            node is QueryNode.SizeRangeFilter ||
            node is QueryNode.DateModifiedFilter
    }

    private fun buildMetaWhereParts(
        metaFilters: List<QueryNode>,
        tableAlias: String
    ): Pair<List<String>, List<Any>> {
        val clauses = ArrayList<String>()
        val args = ArrayList<Any>()
        val p = if (tableAlias.isEmpty()) "" else "$tableAlias."
        for (f in metaFilters) {
            when (f) {
                is QueryNode.ExtFilter -> {
                    val placeholders = f.extensions.joinToString(",") { "?" }
                    clauses.add("${p}ext IN ($placeholders)")
                    args.addAll(f.extensions)
                }
                is QueryNode.TypeFilter -> {
                    clauses.add("${p}is_dir = ?")
                    args.add(if (f.type == FileTypeFilter.FOLDER_ONLY) 1L else 0L)
                }
                is QueryNode.SizeRangeFilter -> {
                    f.minBytes?.let { clauses.add("${p}size >= ?"); args.add(it) }
                    f.maxBytes?.let { clauses.add("${p}size <= ?"); args.add(it) }
                }
                is QueryNode.DateModifiedFilter -> {
                    f.minMillis?.let { clauses.add("${p}mtime >= ?"); args.add(it) }
                    f.maxMillis?.let { clauses.add("${p}mtime <= ?"); args.add(it) }
                }
                else -> Unit
            }
        }
        return clauses to args
    }

    private fun bindDynamicArgs(stmt: SQLiteStatement, startIndex: Int, args: List<Any>): Int {
        var idx = startIndex
        for (arg in args) {
            when (arg) {
                is Long -> stmt.bindLong(idx++, arg)
                is String -> stmt.bindText(idx++, arg)
            }
        }
        return idx
    }

    private fun queryCandidatesForTerm(
        term: QueryNode.TextTerm,
        metaFilters: List<QueryNode>
    ): LinkedHashSet<Long> {
        if (term.hasWildcard) {
            return queryCandidatesForWildcard(term, metaFilters)
        }

        val result = LinkedHashSet<Long>()
        val frags = CjkTokenizer.analyzeQueryLiteral(term.rawText)
        if (frags.phraseSequences.isEmpty() && frags.requiredUnigrams.isEmpty()) {
            // Punctuation-only literal (e.g., "." or "_")
            val likePattern = "%" + CjkTokenizer.normalizeString(term.rawText)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%"
            if (term.field != MatchField.BODY_ONLY) {
                val col = if (term.field == MatchField.PATH_ONLY) "path" else "name"
                val (metaClauses, metaArgs) = buildMetaWhereParts(metaFilters, "")
                val extraWhere = if (metaClauses.isEmpty()) "" else " AND " + metaClauses.joinToString(" AND ")
                val sql = "SELECT id FROM files_meta WHERE LOWER($col) LIKE ? ESCAPE '\\'$extraWhere LIMIT 2000"
                prepareVisible(sql).use { stmt ->
                    stmt.bindText(1, likePattern)
                    bindDynamicArgs(stmt, 2, metaArgs)
                    while (stmt.step()) result.add(stmt.getLong(0))
                }
            }
            return result
        }

        // 1. Query Tier 1 (files_fts) joined with files_meta for metadata pushdown
        if (term.field != MatchField.BODY_ONLY) {
            val tier1MatchExpr = buildFilesFtsMatchExpr(frags, term.field, allowPrefix = !term.isQuotedPhrase)
            if (tier1MatchExpr != null) {
                val (metaClauses, metaArgs) = buildMetaWhereParts(metaFilters, "m")
                val sql = if (metaClauses.isEmpty()) {
                    "SELECT rowid FROM files_fts WHERE files_fts MATCH ? LIMIT 4000"
                } else {
                    "SELECT m.id FROM files_fts f JOIN files_meta m ON m.id = f.rowid WHERE files_fts MATCH ? AND " +
                        metaClauses.joinToString(" AND ") + " LIMIT 4000"
                }
                prepareVisible(sql).use { stmt ->
                    stmt.bindText(1, tier1MatchExpr)
                    bindDynamicArgs(stmt, 2, metaArgs)
                    while (stmt.step()) result.add(stmt.getLong(0))
                }
            }
        }

        // 2. Query Tier 2 (docs_fts / docs_uni_fts) with metadata pushdown
        if (term.field == MatchField.ANY || term.field == MatchField.BODY_ONLY) {
            queryTier2BodyCandidates(frags, allowPrefix = !term.isQuotedPhrase, metaFilters = metaFilters, outIds = result)
        }

        return result
    }

    /**
     * Lossless candidate retrieval for wildcards (`*` and `?`):
     * - Extracts literal extension hint (`*.ext`) when applicable to leverage `idx_files_ext`.
     * - Uses FTS5 only when a segment is guaranteed lossless (CJK >=2 bigram or start-anchored ASCII prefix `foo*`).
     * - Otherwise scans `files_meta` directly (with SQL metadata pushdown) so `*.pdf` or `*SU.apk` never drops hits.
     */
    private fun queryCandidatesForWildcard(
        term: QueryNode.TextTerm,
        metaFilters: List<QueryNode>
    ): LinkedHashSet<Long> {
        val result = LinkedHashSet<Long>()
        val normGlob = CjkTokenizer.normalizeString(term.rawText)

        if (term.field == MatchField.BODY_ONLY) {
            val longestChunk = term.rawText.split('*', '?').maxByOrNull { it.length }.orEmpty()
            val frags = CjkTokenizer.analyzeQueryLiteral(longestChunk)
            val hasSafeCjkBigram = frags.phraseSequences.any { seq -> seq.size >= 2 }
            if (hasSafeCjkBigram) {
                queryTier2BodyCandidates(frags, allowPrefix = false, metaFilters = metaFilters, outIds = result)
                return result
            }
            val (metaClauses, metaArgs) = buildMetaWhereParts(metaFilters, "m")
            val whereSql = if (metaClauses.isEmpty()) "" else " WHERE " + metaClauses.joinToString(" AND ")
            val sql = "SELECT d.file_id FROM docs_raw d JOIN files_meta m ON m.id = d.file_id$whereSql"
            prepareVisible(sql).use { stmt ->
                bindDynamicArgs(stmt, 1, metaArgs)
                while (stmt.step()) result.add(stmt.getLong(0))
            }
            return result
        }

        // Check for extension optimization (e.g. `2026*.pdf` or `*.pdf`)
        val effectiveMeta = ArrayList(metaFilters)
        if (!normGlob.contains('/') && term.field != MatchField.PATH_ONLY) {
            val lastDot = normGlob.lastIndexOf('.')
            if (lastDot >= 0 && lastDot < normGlob.lastIndex) {
                val tailExt = normGlob.substring(lastDot + 1)
                if (tailExt.isNotEmpty() && !tailExt.contains('*') && !tailExt.contains('?')) {
                    effectiveMeta.add(QueryNode.ExtFilter(setOf(tailExt)))
                }
            }
        }

        // Check whether FTS can safely pre-narrow without missing hits:
        // 1) Any pure CJK run of length >= 2 inside any literal segment is lossless via bigrams.
        // 2) An ASCII prefix at index 0 immediately followed by `*` (e.g. `Baka*.apk` -> `baka*`) is lossless.
        val segments = normGlob.split('*', '?')
        val firstSeg = segments.firstOrNull().orEmpty()
        val hasPathSep = normGlob.contains('/')
        val bestCjkSeg = segments
            .flatMap { CjkTokenizer.analyzeQueryLiteral(it).phraseSequences }
            .firstOrNull { seq -> seq.size >= 2 }

        if (bestCjkSeg != null) {
            val frags = CjkTokenizer.QueryFragments(
                phraseSequences = listOf(bestCjkSeg),
                requiredUnigrams = emptyList(),
                normalizedLiteral = bestCjkSeg.joinToString("")
            )
            val ftsField = if (hasPathSep || term.field == MatchField.PATH_ONLY) MatchField.PATH_ONLY else MatchField.NAME_ONLY
            val matchExpr = buildFilesFtsMatchExpr(frags, ftsField, allowPrefix = false)
            if (matchExpr != null) {
                val (metaClauses, metaArgs) = buildMetaWhereParts(effectiveMeta, "m")
                val sql = if (metaClauses.isEmpty()) {
                    "SELECT rowid FROM files_fts WHERE files_fts MATCH ? LIMIT 8000"
                } else {
                    "SELECT m.id FROM files_fts f JOIN files_meta m ON m.id = f.rowid WHERE files_fts MATCH ? AND " +
                        metaClauses.joinToString(" AND ") + " LIMIT 8000"
                }
                prepareVisible(sql).use { stmt ->
                    stmt.bindText(1, matchExpr)
                    bindDynamicArgs(stmt, 2, metaArgs)
                    while (stmt.step()) result.add(stmt.getLong(0))
                }
                return result
            }
        }

        if (!hasPathSep && firstSeg.length >= 2 && firstSeg.all { CjkTokenizer.isAsciiWordChar(it) } &&
            term.field != MatchField.PATH_ONLY
        ) {
            val frags = CjkTokenizer.analyzeQueryLiteral(firstSeg)
            val matchExpr = buildFilesFtsMatchExpr(frags, MatchField.NAME_ONLY, allowPrefix = true)
            if (matchExpr != null) {
                val (metaClauses, metaArgs) = buildMetaWhereParts(effectiveMeta, "m")
                val sql = if (metaClauses.isEmpty()) {
                    "SELECT rowid FROM files_fts WHERE files_fts MATCH ? LIMIT 8000"
                } else {
                    "SELECT m.id FROM files_fts f JOIN files_meta m ON m.id = f.rowid WHERE files_fts MATCH ? AND " +
                        metaClauses.joinToString(" AND ") + " LIMIT 8000"
                }
                prepareVisible(sql).use { stmt ->
                    stmt.bindText(1, matchExpr)
                    bindDynamicArgs(stmt, 2, metaArgs)
                    while (stmt.step()) result.add(stmt.getLong(0))
                }
                return result
            }
        }

        // Lossless direct scan of files_meta (filtered by effectiveMeta such as ext index!)
        val regex = buildStrictWildcardRegex(normGlob, anchored = !hasPathSep || normGlob.startsWith("/"))
        val (metaClauses, metaArgs) = buildMetaWhereParts(effectiveMeta, "")
        val whereSql = if (metaClauses.isEmpty()) "" else " WHERE " + metaClauses.joinToString(" AND ")
        val selectCol = if (hasPathSep || term.field == MatchField.PATH_ONLY) "path" else "name"
        val sql = "SELECT id, $selectCol FROM files_meta$whereSql ORDER BY mtime DESC"
        prepareVisible(sql).use { stmt ->
            bindDynamicArgs(stmt, 1, metaArgs)
            while (stmt.step()) {
                val target = CjkTokenizer.normalizeString(stmt.getText(1))
                val hit = if (hasPathSep && !normGlob.startsWith("/")) {
                    regex.containsMatchIn(target)
                } else {
                    regex.matches(target)
                }
                if (hit) {
                    result.add(stmt.getLong(0))
                    if (result.size >= 4000) break
                }
            }
        }
        return result
    }

    private fun queryCandidatesForRegex(
        term: QueryNode.RegexTerm,
        metaFilters: List<QueryNode>,
        bodyCache: MutableMap<Long, String?>,
        regexCache: MutableMap<String, Regex?>
    ): LinkedHashSet<Long> {
        val result = LinkedHashSet<Long>()
        if (!term.isValid) return result
        val regex = getCompiledRegex(term, regexCache) ?: return result

        if (term.field != MatchField.BODY_ONLY) {
            val (metaClauses, metaArgs) = buildMetaWhereParts(metaFilters, "")
            val whereSql = if (metaClauses.isEmpty()) "" else " WHERE " + metaClauses.joinToString(" AND ")
            val sql = "SELECT id, name, path FROM files_meta$whereSql ORDER BY mtime DESC"
            prepareVisible(sql).use { stmt ->
                bindDynamicArgs(stmt, 1, metaArgs)
                while (stmt.step()) {
                    val id = stmt.getLong(0)
                    val name = stmt.getText(1)
                    val path = stmt.getText(2)
                    val hit = when (term.field) {
                        MatchField.NAME_ONLY -> regex.containsMatchIn(name)
                        MatchField.PATH_ONLY -> regex.containsMatchIn(path)
                        MatchField.ANY -> regex.containsMatchIn(name) || regex.containsMatchIn(path)
                        MatchField.BODY_ONLY -> false
                    }
                    if (hit) {
                        result.add(id)
                        if (result.size >= 3000) break
                    }
                }
            }
        }

        if (term.field == MatchField.BODY_ONLY || term.field == MatchField.ANY) {
            val (metaClauses, metaArgs) = buildMetaWhereParts(metaFilters, "m")
            val whereSql = if (metaClauses.isEmpty()) "" else " WHERE " + metaClauses.joinToString(" AND ")
            val sql = "SELECT d.file_id, d.compressed_body FROM docs_raw d JOIN files_meta m ON m.id = d.file_id$whereSql"
            prepareVisible(sql).use { stmt ->
                bindDynamicArgs(stmt, 1, metaArgs)
                while (stmt.step()) {
                    val fileId = stmt.getLong(0)
                    val text = bodyCache.getOrPut(fileId) { decompressUtf8(stmt.getBlob(1)) }
                    if (!text.isNullOrEmpty() && regex.containsMatchIn(text)) {
                        result.add(fileId)
                        if (result.size >= 3000) break
                    }
                }
            }
        }

        return result
    }

    private fun getCompiledRegex(
        term: QueryNode.RegexTerm,
        cache: MutableMap<String, Regex?>
    ): Regex? {
        if (!term.isValid) return null
        val key = "${term.caseSensitive}:${term.pattern}"
        return cache.getOrPut(key) {
            runCatching {
                val opts = if (term.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                Regex(term.pattern, opts)
            }.getOrNull()
        }
    }

    private fun buildFilesFtsMatchExpr(
        frags: CjkTokenizer.QueryFragments,
        field: MatchField,
        allowPrefix: Boolean
    ): String? {
        val biParts = ArrayList<String>()
        val uniParts = ArrayList<String>()

        for ((idx, seq) in frags.phraseSequences.withIndex()) {
            if (seq.size == 1) {
                val tok = seq[0]
                val isLastSeq = idx == frags.phraseSequences.lastIndex && frags.requiredUnigrams.isEmpty()
                if (allowPrefix && isLastSeq && tok.length >= 2 && tok.all { CjkTokenizer.isAsciiWordChar(it) }) {
                    biParts.add("${ftsQuote(tok)}*")
                } else {
                    biParts.add(ftsQuote(tok))
                }
            } else {
                biParts.add(ftsQuote(seq.joinToString(" ")))
            }
        }

        for (u in frags.requiredUnigrams) {
            uniParts.add(ftsQuote(u))
        }

        fun combineForColumns(biCol: String, uniCol: String): String? {
            val clauses = ArrayList<String>()
            for (b in biParts) clauses.add("$biCol : $b")
            for (u in uniParts) clauses.add("$uniCol : $u")
            return if (clauses.isEmpty()) null else clauses.joinToString(" AND ")
        }

        return when (field) {
            MatchField.NAME_ONLY -> combineForColumns("name_bi", "name_uni")
            MatchField.PATH_ONLY -> combineForColumns("path_bi", "path_uni")
            MatchField.ANY -> {
                val nameExpr = combineForColumns("name_bi", "name_uni")
                val pathExpr = combineForColumns("path_bi", "path_uni")
                when {
                    nameExpr != null && pathExpr != null -> "($nameExpr) OR ($pathExpr)"
                    nameExpr != null -> nameExpr
                    else -> pathExpr
                }
            }
            MatchField.BODY_ONLY -> null
        }
    }

    private fun queryTier2BodyCandidates(
        frags: CjkTokenizer.QueryFragments,
        allowPrefix: Boolean,
        metaFilters: List<QueryNode> = emptyList(),
        outIds: LinkedHashSet<Long>
    ) {
        val biExprParts = ArrayList<String>()
        for ((idx, seq) in frags.phraseSequences.withIndex()) {
            if (seq.size == 1) {
                val tok = seq[0]
                val isLastSeq = idx == frags.phraseSequences.lastIndex && frags.requiredUnigrams.isEmpty()
                if (allowPrefix && isLastSeq && tok.length >= 2 && tok.all { CjkTokenizer.isAsciiWordChar(it) }) {
                    biExprParts.add("${ftsQuote(tok)}*")
                } else {
                    biExprParts.add(ftsQuote(tok))
                }
            } else {
                biExprParts.add(ftsQuote(seq.joinToString(" ")))
            }
        }

        val (metaClauses, metaArgs) = buildMetaWhereParts(metaFilters, "m")

        val biMatchIds: LinkedHashSet<Long>? = if (biExprParts.isNotEmpty()) {
            val ids = LinkedHashSet<Long>()
            val expr = biExprParts.joinToString(" AND ")
            val sql = if (metaClauses.isEmpty()) {
                "SELECT rowid FROM docs_fts WHERE body_bi MATCH ? LIMIT 2500"
            } else {
                "SELECT m.id FROM docs_fts d JOIN files_meta m ON m.id = d.rowid WHERE body_bi MATCH ? AND " +
                    metaClauses.joinToString(" AND ") + " LIMIT 2500"
            }
            prepareVisible(sql).use { stmt ->
                stmt.bindText(1, expr)
                bindDynamicArgs(stmt, 2, metaArgs)
                while (stmt.step()) ids.add(stmt.getLong(0))
            }
            ids
        } else {
            null
        }

        val uniMatchIds: LinkedHashSet<Long>? = if (frags.requiredUnigrams.isNotEmpty()) {
            val ids = LinkedHashSet<Long>()
            val expr = frags.requiredUnigrams.joinToString(" AND ")
            val sql = if (metaClauses.isEmpty()) {
                "SELECT rowid FROM docs_uni_fts WHERE docs_uni_fts MATCH ? LIMIT 2500"
            } else {
                "SELECT m.id FROM docs_uni_fts u JOIN files_meta m ON m.id = u.rowid WHERE docs_uni_fts MATCH ? AND " +
                    metaClauses.joinToString(" AND ") + " LIMIT 2500"
            }
            prepareVisible(sql).use { stmt ->
                stmt.bindText(1, expr)
                bindDynamicArgs(stmt, 2, metaArgs)
                while (stmt.step()) ids.add(stmt.getLong(0))
            }
            ids
        } else {
            null
        }

        when {
            biMatchIds != null && uniMatchIds != null -> {
                biMatchIds.retainAll(uniMatchIds)
                outIds.addAll(biMatchIds)
            }
            biMatchIds != null -> outIds.addAll(biMatchIds)
            uniMatchIds != null -> outIds.addAll(uniMatchIds)
        }
    }

    private fun queryPureMetadataIds(filters: List<QueryNode>, limit: Int): LinkedHashSet<Long> {
        val (whereClauses, bindArgs) = buildMetaWhereParts(filters, "")
        val whereSql = if (whereClauses.isEmpty()) "" else "WHERE " + whereClauses.joinToString(" AND ")
        val sql = "SELECT id FROM files_meta $whereSql ORDER BY mtime DESC LIMIT ?"
        val out = LinkedHashSet<Long>()
        prepareVisible(sql).use { stmt ->
            val nextIdx = bindDynamicArgs(stmt, 1, bindArgs)
            stmt.bindLong(nextIdx, limit.toLong())
            while (stmt.step()) out.add(stmt.getLong(0))
        }
        return out
    }

    private fun fetchMetaRowsWithFilter(node: QueryNode, limit: Int): List<FileMetaRow> {
        val filters = if (node is QueryNode.And) node.children else listOf(node)
        val (whereClauses, bindArgs) = buildMetaWhereParts(filters, "")
        val whereSql = if (whereClauses.isEmpty()) "" else "WHERE " + whereClauses.joinToString(" AND ")
        val sql = """
            SELECT id, path, parent_path, name, ext, size, mtime, is_dir, access_mode, tier2_status, tier2_error
            FROM files_meta $whereSql ORDER BY mtime DESC LIMIT ?
        """.trimIndent()
        val rows = ArrayList<FileMetaRow>()
        prepareVisible(sql).use { stmt ->
            val nextIdx = bindDynamicArgs(stmt, 1, bindArgs)
            stmt.bindLong(nextIdx, limit.toLong())
            while (stmt.step()) rows.add(readMetaRow(stmt))
        }
        return rows
    }

    private fun fetchRecentRows(limit: Int): List<FileMetaRow> {
        val rows = ArrayList<FileMetaRow>(limit)
        prepareVisible(
            """
            SELECT id, path, parent_path, name, ext, size, mtime, is_dir, access_mode, tier2_status, tier2_error
            FROM files_meta
            ORDER BY mtime DESC
            LIMIT ?
            """.trimIndent()
        ).use { stmt ->
            stmt.bindLong(1, limit.toLong())
            while (stmt.step()) rows.add(readMetaRow(stmt))
        }
        return rows
    }

    private fun fetchRowsByIds(ids: Set<Long>): List<FileMetaRow> {
        if (ids.isEmpty()) return emptyList()
        val rows = ArrayList<FileMetaRow>(ids.size)
        val idList = ids.toList()
        for (chunk in idList.chunked(800)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val sql = """
                SELECT id, path, parent_path, name, ext, size, mtime, is_dir, access_mode, tier2_status, tier2_error
                FROM files_meta
                WHERE id IN ($placeholders)
            """.trimIndent()
            prepareVisible(sql).use { stmt ->
                chunk.forEachIndexed { index, id -> stmt.bindLong(index + 1, id) }
                while (stmt.step()) rows.add(readMetaRow(stmt))
            }
        }
        return rows
    }

    private fun readMetaRow(stmt: SQLiteStatement): FileMetaRow {
        return FileMetaRow(
            id = stmt.getLong(0),
            path = stmt.getText(1),
            parentPath = stmt.getText(2),
            name = stmt.getText(3),
            ext = stmt.getText(4),
            size = stmt.getLong(5),
            mtime = stmt.getLong(6),
            isDir = stmt.getLong(7) != 0L,
            accessMode = AccessMode.fromCode(stmt.getLong(8).toInt()),
            tier2Status = Tier2Status.fromCode(stmt.getLong(9).toInt()),
            tier2Error = if (stmt.isNull(10)) null else stmt.getText(10)
        )
    }

    private data class VerifyOutcome(
        val matched: Boolean,
        val nameHit: Boolean = false,
        val pathHit: Boolean = false,
        val bodyHit: Boolean = false
    )

    private fun verifyRowAgainstAst(
        row: FileMetaRow,
        node: QueryNode,
        bodyCache: MutableMap<Long, String?>,
        bodyFtsCache: MutableMap<String, Set<Long>?>,
        regexCache: MutableMap<String, Regex?>
    ): VerifyOutcome {
        return when (node) {
            is QueryNode.And -> {
                var nameHit = false
                var pathHit = false
                var bodyHit = false
                for (c in node.children) {
                    val sub = verifyRowAgainstAst(row, c, bodyCache, bodyFtsCache, regexCache)
                    if (!sub.matched) return VerifyOutcome(false)
                    nameHit = nameHit || sub.nameHit
                    pathHit = pathHit || sub.pathHit
                    bodyHit = bodyHit || sub.bodyHit
                }
                VerifyOutcome(true, nameHit, pathHit, bodyHit)
            }
            is QueryNode.Or -> {
                var anyMatched = false
                var nameHit = false
                var pathHit = false
                var bodyHit = false
                for (c in node.children) {
                    val sub = verifyRowAgainstAst(row, c, bodyCache, bodyFtsCache, regexCache)
                    if (sub.matched) {
                        anyMatched = true
                        nameHit = nameHit || sub.nameHit
                        pathHit = pathHit || sub.pathHit
                        bodyHit = bodyHit || sub.bodyHit
                    }
                }
                VerifyOutcome(anyMatched, nameHit, pathHit, bodyHit)
            }
            is QueryNode.Not -> {
                val inner = verifyRowAgainstAst(row, node.child, bodyCache, bodyFtsCache, regexCache)
                VerifyOutcome(!inner.matched)
            }
            is QueryNode.ExtFilter -> VerifyOutcome(row.ext in node.extensions)
            is QueryNode.TypeFilter -> VerifyOutcome(
                when (node.type) {
                    FileTypeFilter.FILE_ONLY -> !row.isDir
                    FileTypeFilter.FOLDER_ONLY -> row.isDir
                }
            )
            is QueryNode.SizeRangeFilter -> {
                val minOk = node.minBytes == null || row.size >= node.minBytes
                val maxOk = node.maxBytes == null || row.size <= node.maxBytes
                VerifyOutcome(minOk && maxOk)
            }
            is QueryNode.DateModifiedFilter -> {
                val minOk = node.minMillis == null || row.mtime >= node.minMillis
                val maxOk = node.maxMillis == null || row.mtime <= node.maxMillis
                VerifyOutcome(minOk && maxOk)
            }
            is QueryNode.TextTerm -> verifyTextTerm(row, node, bodyCache, bodyFtsCache)
            is QueryNode.RegexTerm -> verifyRegexTerm(row, node, bodyCache, regexCache)
        }
    }

    private fun verifyRegexTerm(
        row: FileMetaRow,
        term: QueryNode.RegexTerm,
        bodyCache: MutableMap<Long, String?>,
        regexCache: MutableMap<String, Regex?>
    ): VerifyOutcome {
        val regex = getCompiledRegex(term, regexCache) ?: return VerifyOutcome(false)
        val nameHit = (term.field == MatchField.ANY || term.field == MatchField.NAME_ONLY) &&
            regex.containsMatchIn(row.name)
        val pathHit = (term.field == MatchField.ANY || term.field == MatchField.PATH_ONLY) &&
            regex.containsMatchIn(row.path)
        var bodyHit = false
        val needBody = (term.field == MatchField.BODY_ONLY) ||
            (term.field == MatchField.ANY && !nameHit && !pathHit)
        if (needBody && row.tier2Status == Tier2Status.INDEXED) {
            val rawBody = bodyCache.getOrPut(row.id) { loadRawTextLocked(row.id) }
            if (!rawBody.isNullOrEmpty() && regex.containsMatchIn(rawBody)) {
                bodyHit = true
            }
        }
        return VerifyOutcome(
            matched = nameHit || pathHit || bodyHit,
            nameHit = nameHit,
            pathHit = pathHit,
            bodyHit = bodyHit
        )
    }

    private fun verifyTextTerm(
        row: FileMetaRow,
        term: QueryNode.TextTerm,
        bodyCache: MutableMap<Long, String?>,
        bodyFtsCache: MutableMap<String, Set<Long>?>
    ): VerifyOutcome {
        val normNeedle = CjkTokenizer.normalizeString(term.rawText)
        if (!term.hasWildcard) {
            val nameHit = (term.field == MatchField.ANY || term.field == MatchField.NAME_ONLY) &&
                CjkTokenizer.normalizeString(row.name).contains(normNeedle)
            val pathHit = (term.field == MatchField.ANY || term.field == MatchField.PATH_ONLY) &&
                CjkTokenizer.normalizeString(row.path).contains(normNeedle)
            var bodyHit = false
            val needBody = (term.field == MatchField.BODY_ONLY) ||
                (term.field == MatchField.ANY && !nameHit && !pathHit)
            if (needBody && row.tier2Status == Tier2Status.INDEXED) {
                val ftsCandidateSet = bodyFtsCache.getOrPut(
                    "${term.rawText}|${term.isQuotedPhrase}|false"
                ) {
                    val frags = CjkTokenizer.analyzeQueryLiteral(term.rawText)
                    if (frags.phraseSequences.isEmpty() && frags.requiredUnigrams.isEmpty()) {
                        null
                    } else {
                        val out = LinkedHashSet<Long>()
                        queryTier2BodyCandidates(
                            frags = frags,
                            allowPrefix = !term.isQuotedPhrase,
                            outIds = out
                        )
                        if (out.size >= 2500) null else out
                    }
                }
                if (ftsCandidateSet == null || row.id in ftsCandidateSet) {
                    val rawBody = bodyCache.getOrPut(row.id) { loadRawTextLocked(row.id) }
                    if (!rawBody.isNullOrEmpty() &&
                        CjkTokenizer.normalizeString(rawBody).contains(normNeedle)
                    ) {
                        bodyHit = true
                    }
                }
            }
            return VerifyOutcome(
                matched = nameHit || pathHit || bodyHit,
                nameHit = nameHit,
                pathHit = pathHit,
                bodyHit = bodyHit
            )
        }

        // Strict wildcard verification:
        // - Filename matching is strictly anchored (^...$).
        // - Path matching anchors at start only if glob starts with '/'; otherwise matches full path.
        // - Body matching (when BODY_ONLY) is single-line wildcard ([^\r\n]*).
        val hasPathSep = normNeedle.contains('/')
        val nameRegex = buildStrictWildcardRegex(normNeedle, anchored = true)
        val pathRegex = buildStrictWildcardRegex(normNeedle, anchored = normNeedle.startsWith("/"))

        val nameHit = !hasPathSep &&
            (term.field == MatchField.ANY || term.field == MatchField.NAME_ONLY) &&
            nameRegex.matches(CjkTokenizer.normalizeString(row.name))

        val pathHit = (term.field == MatchField.PATH_ONLY || (term.field == MatchField.ANY && hasPathSep)) &&
            run {
                val normPath = CjkTokenizer.normalizeString(row.path)
                if (normNeedle.startsWith("/")) pathRegex.matches(normPath)
                else pathRegex.containsMatchIn(normPath)
            }

        var bodyHit = false
        if (term.field == MatchField.BODY_ONLY && row.tier2Status == Tier2Status.INDEXED) {
            val rawBody = bodyCache.getOrPut(row.id) { loadRawTextLocked(row.id) }
            if (!rawBody.isNullOrEmpty()) {
                val bodyRegex = buildStrictWildcardRegex(normNeedle, anchored = false, singleLine = true)
                bodyHit = bodyRegex.containsMatchIn(CjkTokenizer.normalizeString(rawBody))
            }
        }

        return VerifyOutcome(
            matched = nameHit || pathHit || bodyHit,
            nameHit = nameHit,
            pathHit = pathHit,
            bodyHit = bodyHit
        )
    }

    private fun loadRawTextLocked(fileId: Long): String? {
        prepareVisible("SELECT compressed_body FROM docs_raw WHERE file_id = ?").use { stmt ->
            stmt.bindLong(1, fileId)
            return if (stmt.step()) decompressUtf8(stmt.getBlob(0)) else null
        }
    }

    /**
     * Aggregates file sizes & counts per directory inside SQLite native C++ (zero per-file JVM heap).
     * Even with 1M+ indexed files, only ~10k-40k unique directory rows cross into JVM.
     */
    fun streamDirectoryRollupsForAnalyzer(
        visitor: (
            parentPath: String,
            directFileBytes: Long,
            directFileCount: Long,
            directSubdirCount: Long,
            maxMtime: Long,
            accessModeCode: Int
        ) -> Unit
    ) {
        readLock.withLock {
            prepareVisible(
                """
                SELECT parent_path,
                       SUM(CASE WHEN is_dir = 0 THEN CASE WHEN size > 0 THEN size ELSE 0 END ELSE 0 END),
                       SUM(CASE WHEN is_dir = 0 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN is_dir = 1 THEN 1 ELSE 0 END),
                       MAX(mtime),
                       MAX(access_mode)
                FROM files_meta
                GROUP BY parent_path
                """.trimIndent()
            ).use { stmt ->
                while (stmt.step()) {
                    visitor(
                        stmt.getText(0),
                        stmt.getLong(1),
                        stmt.getLong(2),
                        stmt.getLong(3),
                        stmt.getLong(4),
                        stmt.getLong(5).toInt()
                    )
                }
            }
        }
    }

    /**
     * Streams only directory metadata rows (is_dir = 1) so empty or nested-only directories
     * preserve accurate mtime, accessMode, and tree linkage without touching file rows.
     */
    fun streamDirectoriesForAnalyzer(
        visitor: (
            path: String,
            parentPath: String,
            name: String,
            mtime: Long,
            accessModeCode: Int
        ) -> Unit
    ) {
        readLock.withLock {
            prepareVisible(
                "SELECT path, parent_path, name, mtime, access_mode FROM files_meta WHERE is_dir = 1"
            ).use { stmt ->
                while (stmt.step()) {
                    visitor(
                        stmt.getText(0),
                        stmt.getText(1),
                        stmt.getText(2),
                        stmt.getLong(3),
                        stmt.getLong(4).toInt()
                    )
                }
            }
        }
    }

    data class AnalyzerExtRow(
        val ext: String,
        val totalBytes: Long,
        val fileCount: Long
    )

    fun queryExtensionStatsForAnalyzer(limit: Int = 36): List<AnalyzerExtRow> {
        val out = ArrayList<AnalyzerExtRow>(limit)
        readLock.withLock {
            prepareVisible(
                """
                SELECT LOWER(ext) AS norm_ext,
                       SUM(CASE WHEN size > 0 THEN size ELSE 0 END) AS total_bytes,
                       COUNT(*) AS file_cnt
                FROM files_meta
                WHERE is_dir = 0
                GROUP BY norm_ext
                ORDER BY total_bytes DESC
                LIMIT ?
                """.trimIndent()
            ).use { stmt ->
                stmt.bindLong(1, limit.toLong())
                while (stmt.step()) {
                    out.add(
                        AnalyzerExtRow(
                            ext = stmt.getText(0),
                            totalBytes = stmt.getLong(1),
                            fileCount = stmt.getLong(2)
                        )
                    )
                }
            }
        }
        return out
    }

    data class AnalyzerFileRow(
        val path: String,
        val parentPath: String,
        val name: String,
        val ext: String,
        val size: Long,
        val mtime: Long,
        val accessModeCode: Int
    )

    fun queryTopLargeFilesForAnalyzer(extFilter: String? = null, limit: Int = 200): List<AnalyzerFileRow> {
        val out = ArrayList<AnalyzerFileRow>(minOf(limit, 200))
        readLock.withLock {
            val sql = if (extFilter == null) {
                """
                SELECT path, parent_path, name, ext, size, mtime, access_mode
                FROM files_meta
                WHERE is_dir = 0 AND size > 0
                ORDER BY size DESC
                LIMIT ?
                """.trimIndent()
            } else {
                """
                SELECT path, parent_path, name, ext, size, mtime, access_mode
                FROM files_meta
                WHERE is_dir = 0 AND LOWER(ext) = ? AND size > 0
                ORDER BY size DESC
                LIMIT ?
                """.trimIndent()
            }
            prepareVisible(sql).use { stmt ->
                if (extFilter == null) {
                    stmt.bindLong(1, limit.toLong())
                } else {
                    stmt.bindText(1, extFilter.trim().trimStart('.').lowercase(java.util.Locale.ROOT))
                    stmt.bindLong(2, limit.toLong())
                }
                while (stmt.step()) {
                    out.add(
                        AnalyzerFileRow(
                            path = stmt.getText(0),
                            parentPath = stmt.getText(1),
                            name = stmt.getText(2),
                            ext = stmt.getText(3),
                            size = stmt.getLong(4),
                            mtime = stmt.getLong(5),
                            accessModeCode = stmt.getLong(6).toInt()
                        )
                    )
                }
            }
        }
        return out
    }

    fun queryDirectFilesInDirForAnalyzer(
        dirPath: String,
        orderByClause: String = "size DESC, name COLLATE NOCASE ASC",
        limit: Int = 500
    ): List<AnalyzerFileRow> {
        val normalized = if (dirPath.length > 1) dirPath.trimEnd('/') else dirPath
        val safeOrderBy = when (orderByClause) {
            "size DESC, name COLLATE NOCASE ASC",
            "size ASC, name COLLATE NOCASE ASC",
            "mtime DESC, name COLLATE NOCASE ASC",
            "mtime ASC, name COLLATE NOCASE ASC",
            "name COLLATE NOCASE ASC",
            "name COLLATE NOCASE DESC" -> orderByClause
            else -> "size DESC, name COLLATE NOCASE ASC"
        }
        val out = ArrayList<AnalyzerFileRow>(64)
        readLock.withLock {
            prepareVisible(
                """
                SELECT path, parent_path, name, ext, size, mtime, access_mode
                FROM files_meta
                WHERE parent_path = ? AND is_dir = 0
                ORDER BY $safeOrderBy
                LIMIT ?
                """.trimIndent()
            ).use { stmt ->
                stmt.bindText(1, normalized)
                stmt.bindLong(2, limit.toLong())
                while (stmt.step()) {
                    out.add(
                        AnalyzerFileRow(
                            path = stmt.getText(0),
                            parentPath = stmt.getText(1),
                            name = stmt.getText(2),
                            ext = stmt.getText(3),
                            size = stmt.getLong(4),
                            mtime = stmt.getLong(5),
                            accessModeCode = stmt.getLong(6).toInt()
                        )
                    )
                }
            }
        }
        return out
    }

    /**
     * Streams every row of files_meta in a single read pass (retained for small diagnostic streams).
     */
    fun streamAllEntriesForAnalyzer(
        visitor: (
            path: String,
            parentPath: String,
            name: String,
            ext: String,
            size: Long,
            mtime: Long,
            isDir: Boolean,
            accessModeCode: Int
        ) -> Unit
    ) {
        readLock.withLock {
            prepareVisible(
                "SELECT path, parent_path, name, ext, size, mtime, is_dir, access_mode FROM files_meta"
            ).use { stmt ->
                while (stmt.step()) {
                    visitor(
                        stmt.getText(0),
                        stmt.getText(1),
                        stmt.getText(2),
                        stmt.getText(3),
                        stmt.getLong(4),
                        stmt.getLong(5),
                        stmt.getLong(6) != 0L,
                        stmt.getLong(7).toInt()
                    )
                }
            }
        }
    }

    data class AnalyzerDirCacheRow(
        val path: String,
        val parentPath: String,
        val name: String,
        val totalBytes: Long,
        val fileCount: Long,
        val dirCount: Long,
        val directFileCount: Long,
        val mtime: Long,
        val accessModeCode: Int
    )

    data class AnalyzerAppCacheRow(
        val packageName: String,
        val fallbackLabel: String?,
        val totalBytes: Long,
        val fileCount: Long,
        val locationsBlob: String
    )

    fun replaceAnalyzerCache(
        meta: Map<String, String>,
        dirs: Iterable<AnalyzerDirCacheRow>,
        exts: List<AnalyzerExtRow>,
        apps: List<AnalyzerAppCacheRow>
    ) {
        writeLock.withLock {
            writeConn.execSQL("BEGIN IMMEDIATE;")
            try {
                writeConn.execSQL("DELETE FROM analyzer_dir_stats;")
                writeConn.execSQL("DELETE FROM analyzer_ext_stats;")
                writeConn.execSQL("DELETE FROM analyzer_app_stats;")
                writeConn.execSQL("DELETE FROM analyzer_meta;")

                writeConn.prepare(
                    """
                    INSERT OR REPLACE INTO analyzer_dir_stats(
                        path, parent_path, name, total_bytes, file_count, dir_count, direct_file_count, mtime, access_mode
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { stmt ->
                    for (d in dirs) {
                        stmt.reset()
                        stmt.bindText(1, d.path)
                        stmt.bindText(2, d.parentPath)
                        stmt.bindText(3, d.name)
                        stmt.bindLong(4, d.totalBytes)
                        stmt.bindLong(5, d.fileCount)
                        stmt.bindLong(6, d.dirCount)
                        stmt.bindLong(7, d.directFileCount)
                        stmt.bindLong(8, d.mtime)
                        stmt.bindLong(9, d.accessModeCode.toLong())
                        stmt.step()
                    }
                }

                writeConn.prepare(
                    "INSERT OR REPLACE INTO analyzer_ext_stats(ext, total_bytes, file_count) VALUES (?, ?, ?)"
                ).use { stmt ->
                    for (e in exts) {
                        stmt.reset()
                        stmt.bindText(1, e.ext)
                        stmt.bindLong(2, e.totalBytes)
                        stmt.bindLong(3, e.fileCount)
                        stmt.step()
                    }
                }

                writeConn.prepare(
                    """
                    INSERT OR REPLACE INTO analyzer_app_stats(
                        package_name, fallback_label, total_bytes, file_count, locations_blob
                    ) VALUES (?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { stmt ->
                    for (a in apps) {
                        stmt.reset()
                        stmt.bindText(1, a.packageName)
                        if (a.fallbackLabel != null) stmt.bindText(2, a.fallbackLabel) else stmt.bindNull(2)
                        stmt.bindLong(3, a.totalBytes)
                        stmt.bindLong(4, a.fileCount)
                        stmt.bindText(5, a.locationsBlob)
                        stmt.step()
                    }
                }

                writeConn.prepare(
                    "INSERT OR REPLACE INTO analyzer_meta(meta_key, meta_value) VALUES (?, ?)"
                ).use { stmt ->
                    for ((k, v) in meta) {
                        stmt.reset()
                        stmt.bindText(1, k)
                        stmt.bindText(2, v)
                        stmt.step()
                    }
                }
                writeConn.execSQL("COMMIT;")
            } catch (t: Throwable) {
                runCatching { writeConn.execSQL("ROLLBACK;") }
                throw t
            }
        }
    }

    fun markAnalyzerCacheDirty() {
        writeLock.withLock {
            writeConn.prepare(
                "INSERT OR REPLACE INTO analyzer_meta(meta_key, meta_value) VALUES ('dirty', '1')"
            ).use { it.step() }
        }
    }

    fun loadAnalyzerMeta(): Map<String, String> = readLock.withLock {
        val out = HashMap<String, String>(16)
        readConn.prepare("SELECT meta_key, meta_value FROM analyzer_meta").use { stmt ->
            while (stmt.step()) {
                out[stmt.getText(0)] = stmt.getText(1)
            }
        }
        out
    }

    fun loadAnalyzerDirRow(path: String): AnalyzerDirCacheRow? {
        val normalized = if (path.length > 1) path.trimEnd('/') else path
        return readLock.withLock {
            readConn.prepare(
                """
                SELECT path, parent_path, name, total_bytes, file_count, dir_count, direct_file_count, mtime, access_mode
                FROM analyzer_dir_stats
                WHERE path = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.bindText(1, normalized)
                if (stmt.step()) {
                    AnalyzerDirCacheRow(
                        path = stmt.getText(0),
                        parentPath = stmt.getText(1),
                        name = stmt.getText(2),
                        totalBytes = stmt.getLong(3),
                        fileCount = stmt.getLong(4),
                        dirCount = stmt.getLong(5),
                        directFileCount = stmt.getLong(6),
                        mtime = stmt.getLong(7),
                        accessModeCode = stmt.getLong(8).toInt()
                    )
                } else {
                    null
                }
            }
        }
    }

    fun queryAnalyzerChildDirRows(
        parentPath: String,
        orderByClause: String = "total_bytes DESC, name COLLATE NOCASE ASC",
        limit: Int = 2000
    ): List<AnalyzerDirCacheRow> {
        val normalized = if (parentPath.length > 1) parentPath.trimEnd('/') else parentPath
        val safeOrderBy = when (orderByClause) {
            "total_bytes DESC, name COLLATE NOCASE ASC",
            "total_bytes ASC, name COLLATE NOCASE ASC",
            "(file_count + dir_count) DESC, total_bytes DESC, name COLLATE NOCASE ASC",
            "(file_count + dir_count) ASC, total_bytes ASC, name COLLATE NOCASE ASC",
            "mtime DESC, name COLLATE NOCASE ASC",
            "mtime ASC, name COLLATE NOCASE ASC",
            "name COLLATE NOCASE ASC",
            "name COLLATE NOCASE DESC" -> orderByClause
            else -> "total_bytes DESC, name COLLATE NOCASE ASC"
        }
        val out = ArrayList<AnalyzerDirCacheRow>(32)
        readLock.withLock {
            readConn.prepare(
                """
                SELECT path, parent_path, name, total_bytes, file_count, dir_count, direct_file_count, mtime, access_mode
                FROM analyzer_dir_stats
                WHERE parent_path = ? AND path != ?
                ORDER BY $safeOrderBy
                LIMIT ?
                """.trimIndent()
            ).use { stmt ->
                stmt.bindText(1, normalized)
                stmt.bindText(2, normalized)
                stmt.bindLong(3, limit.toLong())
                while (stmt.step()) {
                    out.add(
                        AnalyzerDirCacheRow(
                            path = stmt.getText(0),
                            parentPath = stmt.getText(1),
                            name = stmt.getText(2),
                            totalBytes = stmt.getLong(3),
                            fileCount = stmt.getLong(4),
                            dirCount = stmt.getLong(5),
                            directFileCount = stmt.getLong(6),
                            mtime = stmt.getLong(7),
                            accessModeCode = stmt.getLong(8).toInt()
                        )
                    )
                }
            }
        }
        return out
    }

    fun loadAnalyzerExtRows(): List<AnalyzerExtRow> = readLock.withLock {
        val out = ArrayList<AnalyzerExtRow>(36)
        readConn.prepare(
            "SELECT ext, total_bytes, file_count FROM analyzer_ext_stats ORDER BY total_bytes DESC"
        ).use { stmt ->
            while (stmt.step()) {
                out.add(AnalyzerExtRow(stmt.getText(0), stmt.getLong(1), stmt.getLong(2)))
            }
        }
        out
    }

    fun loadAnalyzerAppRows(): List<AnalyzerAppCacheRow> = readLock.withLock {
        val out = ArrayList<AnalyzerAppCacheRow>(64)
        readConn.prepare(
            "SELECT package_name, fallback_label, total_bytes, file_count, locations_blob FROM analyzer_app_stats ORDER BY total_bytes DESC, file_count DESC"
        ).use { stmt ->
            while (stmt.step()) {
                val fb = if (stmt.isNull(1)) null else stmt.getText(1)
                out.add(AnalyzerAppCacheRow(stmt.getText(0), fb, stmt.getLong(2), stmt.getLong(3), stmt.getText(4)))
            }
        }
        out
    }

    /**
     * Incrementally deducts a deleted file or directory from the persisted analyzer cache
     * along its ancestor chain in O(depth) (< 2ms), avoiding full-disk analyzer rebuilds.
     */
    fun applyAnalyzerDeletionDelta(
        deletedPath: String,
        isDir: Boolean,
        knownFileBytes: Long = 0L,
        knownFileExt: String = ""
    ) {
        val clean = if (deletedPath.length > 1) deletedPath.trimEnd('/') else deletedPath
        writeLock.withLock {
            val hasCache = writeConn.prepare("SELECT 1 FROM analyzer_meta WHERE meta_key = 'total_bytes'").use { it.step() }
            if (!hasCache) return@withLock

            var removedBytes = knownFileBytes.coerceAtLeast(0L)
            var removedFiles = if (isDir) 0L else 1L
            var removedDirs = if (isDir) 1L else 0L

            if (isDir) {
                writeConn.prepare(
                    "SELECT total_bytes, file_count, dir_count FROM analyzer_dir_stats WHERE path = ?"
                ).use { stmt ->
                    stmt.bindText(1, clean)
                    if (stmt.step()) {
                        removedBytes = stmt.getLong(0).coerceAtLeast(0L)
                        removedFiles = stmt.getLong(1).coerceAtLeast(0L)
                        removedDirs = 1L + stmt.getLong(2).coerceAtLeast(0L)
                    }
                }
            }

            val directParent = run {
                val idx = clean.lastIndexOf('/')
                when {
                    idx < 0 -> ""
                    idx == 0 -> "/"
                    else -> clean.substring(0, idx)
                }
            }

            writeConn.execSQL("BEGIN IMMEDIATE;")
            try {
                if (isDir) {
                    writeConn.prepare("DELETE FROM analyzer_dir_stats WHERE path = ?").use {
                        it.bindText(1, clean)
                        it.step()
                    }
                    val lo = "$clean/"
                    val hi = "$clean" + '0'
                    writeConn.prepare("DELETE FROM analyzer_dir_stats WHERE path >= ? AND path < ?").use {
                        it.bindText(1, lo)
                        it.bindText(2, hi)
                        it.step()
                    }
                } else if (knownFileBytes > 0L) {
                    val normExt = knownFileExt.trim().trimStart('.').lowercase(java.util.Locale.ROOT)
                    writeConn.prepare(
                        """
                        UPDATE analyzer_ext_stats
                        SET total_bytes = MAX(0, total_bytes - ?),
                            file_count = MAX(0, file_count - 1)
                        WHERE ext = ?
                        """.trimIndent()
                    ).use { stmt ->
                        stmt.bindLong(1, removedBytes)
                        stmt.bindText(2, normExt)
                        stmt.step()
                    }
                }

                // Walk ancestor chain and deduct delta in O(depth)
                var cur = directParent
                writeConn.prepare(
                    """
                    UPDATE analyzer_dir_stats
                    SET total_bytes = MAX(0, total_bytes - ?),
                        file_count = MAX(0, file_count - ?),
                        dir_count = MAX(0, dir_count - ?),
                        direct_file_count = CASE WHEN path = ? THEN MAX(0, direct_file_count - ?) ELSE direct_file_count END
                    WHERE path = ?
                    """.trimIndent()
                ).use { upd ->
                    val directFileDelta = if (isDir) 0L else 1L
                    var safetyGuard = 0
                    while (cur.isNotEmpty() && safetyGuard++ < 64) {
                        upd.reset()
                        upd.bindLong(1, removedBytes)
                        upd.bindLong(2, removedFiles)
                        upd.bindLong(3, removedDirs)
                        upd.bindText(4, directParent)
                        upd.bindLong(5, directFileDelta)
                        upd.bindText(6, cur)
                        upd.step()
                        if (cur == "/") break
                        val idx = cur.lastIndexOf('/')
                        cur = when {
                            idx < 0 -> ""
                            idx == 0 -> "/"
                            else -> cur.substring(0, idx)
                        }
                    }
                }

                // Update top-level totals in analyzer_meta
                fun decrementMeta(key: String, delta: Long) {
                    if (delta <= 0L) return
                    var prev = 0L
                    writeConn.prepare("SELECT meta_value FROM analyzer_meta WHERE meta_key = ?").use { s ->
                        s.bindText(1, key)
                        if (s.step()) prev = s.getText(0).toLongOrNull() ?: 0L
                    }
                    val next = (prev - delta).coerceAtLeast(0L)
                    writeConn.prepare("INSERT OR REPLACE INTO analyzer_meta(meta_key, meta_value) VALUES (?, ?)").use { s ->
                        s.bindText(1, key)
                        s.bindText(2, next.toString())
                        s.step()
                    }
                }
                decrementMeta("total_bytes", removedBytes)
                decrementMeta("total_files", removedFiles)
                decrementMeta("total_dirs", removedDirs)
                if (isDir) {
                    // Mark app/ext summaries slightly stale after recursive dir deletion so next background refresh reconciles them
                    writeConn.prepare("INSERT OR REPLACE INTO analyzer_meta(meta_key, meta_value) VALUES ('dirty', '1')").use { it.step() }
                }
                writeConn.execSQL("COMMIT;")
            } catch (t: Throwable) {
                runCatching { writeConn.execSQL("ROLLBACK;") }
                throw t
            }
        }
    }

    private fun toSearchResultItem(
        row: FileMetaRow,
        highlightRules: List<HighlightRule>,
        rawBody: String?,
        tier1NameHit: Boolean,
        tier1PathHit: Boolean,
        tier2Hit: Boolean
    ): SearchResultItem {
        val nameRanges = if (highlightRules.isEmpty()) emptyList() else SnippetHighlighter.findMatchRangesForRules(row.name, highlightRules)
        val pathRanges = if (highlightRules.isEmpty()) emptyList() else SnippetHighlighter.findMatchRangesForRules(row.path, highlightRules)
        val (snippets, count) = if (!rawBody.isNullOrEmpty() && highlightRules.isNotEmpty()) {
            SnippetHighlighter.buildSnippetsForRules(rawBody, highlightRules)
        } else {
            emptyList<MatchSnippet>() to 0
        }

        var score = 0.0
        if (tier1NameHit || nameRanges.isNotEmpty()) {
            score += 100.0
            if (highlightRules.any { it is HighlightRule.Literal && row.name.equals(it.text, ignoreCase = true) }) score += 50.0
            if (highlightRules.any { it is HighlightRule.Literal && row.name.startsWith(it.text, ignoreCase = true) }) score += 20.0
        }
        if (tier1PathHit || pathRanges.isNotEmpty()) score += 35.0
        if (tier2Hit || snippets.isNotEmpty()) score += 50.0 + minOf(count, 20) * 1.5
        if (!row.isDir) score += 5.0

        return SearchResultItem(
            id = row.id,
            name = row.name,
            path = row.path,
            parentPath = row.parentPath,
            ext = row.ext,
            size = row.size,
            mtime = row.mtime,
            isDir = row.isDir,
            accessMode = row.accessMode,
            tier2Status = row.tier2Status,
            matchedNameRanges = nameRanges,
            matchedPathRanges = pathRanges,
            snippets = snippets,
            contentMatchCount = count,
            score = score
        )
    }

    private fun buildStrictWildcardRegex(
        glob: String,
        anchored: Boolean,
        singleLine: Boolean = false
    ): Regex {
        val star = if (singleLine) "[^\\r\\n]*" else ".*"
        val qMark = if (singleLine) "[^\\r\\n]" else "."
        val sb = StringBuilder(glob.length * 2 + 4)
        if (anchored) sb.append('^')
        for (ch in glob) {
            when (ch) {
                '*' -> sb.append(star)
                '?' -> sb.append(qMark)
                '\\', '.', '[', ']', '{', '}', '(', ')', '+', '^', '$', '|' -> sb.append('\\').append(ch)
                else -> sb.append(ch)
            }
        }
        if (anchored) sb.append('$')
        val opts = if (singleLine) emptySet() else setOf(RegexOption.DOT_MATCHES_ALL)
        return Regex(sb.toString(), opts)
    }

    private fun ftsQuote(token: String): String = "\"" + token.replace("\"", "\"\"") + "\""

    override fun close() {
        writeLock.withLock {
            runCatching { writeConn.close() }
        }
        if (!isInMemory) {
            readLock.withLock {
                runCatching { readConn.close() }
            }
        }
    }

    companion object {
        fun compressUtf8(text: String): ByteArray {
            val raw = text.toByteArray(Charsets.UTF_8)
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
            try {
                deflater.setInput(raw)
                deflater.finish()
                val out = ByteArrayOutputStream(raw.size / 2 + 64)
                val buf = ByteArray(4096)
                while (!deflater.finished()) {
                    val n = deflater.deflate(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            } finally {
                deflater.end()
            }
        }

        fun decompressUtf8(blob: ByteArray): String {
            val inflater = Inflater()
            return try {
                inflater.setInput(blob)
                val out = ByteArrayOutputStream(blob.size * 2)
                val buf = ByteArray(4096)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            } catch (_: Throwable) {
                ""
            } finally {
                inflater.end()
            }
        }
    }
}
