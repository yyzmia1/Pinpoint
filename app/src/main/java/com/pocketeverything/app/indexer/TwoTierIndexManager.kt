package com.pocketeverything.app.indexer

import android.content.Context
import com.pocketeverything.app.data.SettingsRepository
import com.pocketeverything.app.filter.FilterEngine
import com.pocketeverything.app.fs.FileSystemProvider
import com.pocketeverything.app.fs.ModeAvailability
import com.pocketeverything.app.fs.ScannedFileRecord
import com.pocketeverything.app.fs.StorageRootsHelper
import com.pocketeverything.app.parser.ExtractionResult
import com.pocketeverything.app.parser.ExtractorDispatcher
import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.search.DatabaseStats
import com.pocketeverything.app.search.FileUpsertEntry
import com.pocketeverything.app.search.PruneSummary
import com.pocketeverything.app.search.SqliteSearchDatabase
import com.pocketeverything.app.search.Tier2Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

enum class Tier1Phase { IDLE, QUICK_SYNC, FULL_SCAN }
enum class Tier2Phase { IDLE, EXTRACTING }

data class IndexProgressState(
    val tier1Phase: Tier1Phase = Tier1Phase.IDLE,
    val tier1ScannedCount: Int = 0,
    val tier2Phase: Tier2Phase = Tier2Phase.IDLE,
    val tier2ProcessedCount: Int = 0,
    val tier2PendingCount: Long = 0,
    val lastScanDurationMs: Long = 0L,
    val statusMessage: String = "就绪",
    val errorMessage: String? = null,
    val stats: DatabaseStats = DatabaseStats(0, 0, 0, 0, 0, 0, 0)
)

/**
 * Coordinates the two-tier asynchronous indexing pipeline:
 * - Tier 1: Fast path/filename/metadata index, immediately searchable while streaming.
 * - Tier 2: Background low-concurrency content extraction queue for txt/md/docx/pdf.
 */
class TwoTierIndexManager(
    private val context: Context,
    private val db: SqliteSearchDatabase,
    private val settingsRepo: SettingsRepository,
    private val providerResolver: (AccessMode) -> FileSystemProvider,
    private val extractorDispatcher: ExtractorDispatcher = ExtractorDispatcher(),
    private val storageRootsResolver: () -> List<String> = { StorageRootsHelper.getNormalStorageRoots(context) }
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable is CancellationException) return@CoroutineExceptionHandler
        _progress.value = _progress.value.copy(
            tier1Phase = Tier1Phase.IDLE,
            tier2Phase = Tier2Phase.IDLE,
            errorMessage = throwable.message ?: "索引任务异常",
            statusMessage = "同步中断：${throwable.message ?: "请重试"}"
        )
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val tier1Mutex = Mutex()
    private var tier1Job: Job? = null
    private var tier2Job: Job? = null

    private val _progress = MutableStateFlow(IndexProgressState())
    val progress: StateFlow<IndexProgressState> = _progress.asStateFlow()

    fun refreshStats() {
        val stats = runCatching { db.getStats() }.getOrNull() ?: return
        _progress.value = _progress.value.copy(
            stats = stats,
            tier2PendingCount = stats.pendingDocs
        )
    }

    /**
     * Triggered on app launch: performs a quick hot-directory mtime delta check if index already exists,
     * or runs a full initial scan if database is empty.
     */
    fun triggerStartupSync() {
        scope.launch {
            if (tier1Job?.isActive == true) return@launch
            val currentStats = runCatching { db.getStats() }.getOrElse { DatabaseStats(0, 0, 0, 0, 0, 0, 0) }
            if (currentStats.totalEntries == 0L) {
                startFullScan(clearExisting = false)
            } else {
                _progress.value = _progress.value.copy(
                    stats = currentStats,
                    tier2PendingCount = currentStats.pendingDocs
                )
                startQuickHotDirsRefresh()
            }
        }
    }

    fun isTier1Busy(): Boolean = tier1Job?.isActive == true && _progress.value.tier1Phase != Tier1Phase.IDLE

    fun clearErrorMessage() {
        _progress.value = _progress.value.copy(errorMessage = null)
    }

    fun cancelCurrentScan() {
        tier1Job?.cancel()
        tier1Job = null
        _progress.value = _progress.value.copy(
            tier1Phase = Tier1Phase.IDLE,
            statusMessage = "已取消扫描",
            errorMessage = null
        )
        scope.launch { refreshStats() }
    }

    fun pruneByCurrentFilter(): PruneSummary {
        val settings = settingsRepo.settings.value
        val summary = db.pruneMatchingFilterDetailed(FilterEngine(settings.filterConfig), settings.accessMode)
        refreshStats()
        if (summary.requeuedDocs > 0) {
            ensureTier2Running()
        }
        return summary
    }

    fun startQuickHotDirsRefresh(silentIfUnavailable: Boolean = false): Job {
        val active = tier1Job
        if (active?.isActive == true && _progress.value.tier1Phase != Tier1Phase.IDLE) {
            return active
        }
        val job = scope.launch {
            tier1Mutex.withLock {
                val startMs = System.currentTimeMillis()
                try {
                    val settings = settingsRepo.settings.value
                    val mode = settings.accessMode
                    val provider = providerResolver(mode)
                    val availability = provider.checkAvailability()
                    if (availability !is ModeAvailability.Ready) {
                        refreshStats()
                        if (silentIfUnavailable) {
                            _progress.value = _progress.value.copy(tier1Phase = Tier1Phase.IDLE)
                        } else {
                            val reason = availabilityReason(availability)
                            _progress.value = _progress.value.copy(
                                tier1Phase = Tier1Phase.IDLE,
                                errorMessage = reason,
                                statusMessage = reason
                            )
                        }
                        return@withLock
                    }

                    val currentDbStats = db.getStats()
                    if (currentDbStats.totalEntries == 0L || settings.lastFullScanAtMillis == 0L) {
                        runFullScanInternal(clearExisting = false)
                        return@withLock
                    }

                    _progress.value = _progress.value.copy(
                        tier1Phase = Tier1Phase.QUICK_SYNC,
                        tier1ScannedCount = 0,
                        statusMessage = "正在增量校验全部可写目录…",
                        errorMessage = null
                    )

                    val filter = FilterEngine(settings.filterConfig)
                    val includeData = filter.isDataPotentiallyIndexed(mode)
                    val storageRoots = storageRootsResolver()
                    val extraRootDirs = if (includeData) listOf("/data") else emptyList()
                    val snapshotRoots = (storageRoots + extraRootDirs).distinct()
                    val rootSnapshots = db.getDirSnapshots(snapshotRoots)

                    // Populate files_meta directories first so authoritative dir_snapshots win for rootSet.
                    val trackedDirs = LinkedHashMap<String, Long>(1024)
                    for ((dirPath, savedMtime) in db.getTrackedMutableDirectories(includeData = includeData)) {
                        val name = dirPath.substringAfterLast('/')
                        if (!filter.shouldSkipTier1Path(dirPath, name, isDir = true, mode = mode)) {
                            trackedDirs[dirPath] = savedMtime
                        }
                    }
                    for (r in snapshotRoots) {
                        val name = r.substringAfterLast('/')
                        if (!filter.shouldSkipTier1Path(r, name, isDir = true, mode = mode)) {
                            trackedDirs[r] = rootSnapshots[r] ?: trackedDirs[r] ?: -1L
                        }
                    }

                    val liveDirMtimes = provider.statDirectories(trackedDirs.keys.toList())
                    val rootSet = snapshotRoots.toHashSet()
                    val missingSubdirs = ArrayList<String>()
                    val dirtyDirs = ArrayList<String>()

                    for ((dir, savedMtime) in trackedDirs) {
                        val live = liveDirMtimes[dir]
                        if (live == null) {
                            if (shouldPruneMissingDirectory(dir, mode, includeData, rootSet, liveDirMtimes)) {
                                missingSubdirs.add(dir)
                            }
                        } else if (live != savedMtime) {
                            dirtyDirs.add(dir)
                        }
                    }

                    var removedCount = 0
                    // Remove only top-most missing subdirs so nested deletions don't double-scan
                    val sortedMissing = missingSubdirs.sortedBy { it.length }
                    val deletedRoots = ArrayList<String>()
                    for (m in sortedMissing) {
                        if (deletedRoots.none { m.startsWith("$it/") }) {
                            deletedRoots.add(m)
                            removedCount += db.deleteSubtree(m)
                        }
                    }

                    var changedCount = 0
                    if (dirtyDirs.isNotEmpty()) {
                        val dirtySet = dirtyDirs.toHashSet()
                        val listedDirs = HashSet<String>(dirtyDirs.size)
                        val seenByParent = HashMap<String, HashSet<String>>(dirtyDirs.size)
                        val newSubdirs = ArrayList<String>()
                        provider.listDirectChildren(
                            dirs = dirtyDirs,
                            filterEngine = filter,
                            onDirListed = { listedDirs.add(it) }
                        ) { batch ->
                            for (r in batch) {
                                seenByParent.getOrPut(r.parentPath) { HashSet() }.add(r.path)
                                if (r.isDir && r.path !in trackedDirs) {
                                    newSubdirs.add(r.path)
                                }
                            }
                            changedCount += db.upsertFileBatch(batch.map { it.toUpsertEntry(filter) })
                            _progress.value = _progress.value.copy(
                                tier1ScannedCount = changedCount,
                                statusMessage = "正在增量更新变动目录：已处理 $changedCount 项"
                            )
                        }
                        // Only prune children of directories that were actually listed (never on list() == null / EACCES).
                        for (dirty in listedDirs) {
                            removedCount += db.pruneDirectChildren(dirty, seenByParent[dirty].orEmpty())
                        }
                        if (newSubdirs.isNotEmpty()) {
                            provider.scanDirectories(newSubdirs.distinct(), filter) { batch ->
                                changedCount += db.upsertFileBatch(batch.map { it.toUpsertEntry(filter) })
                                _progress.value = _progress.value.copy(
                                    tier1ScannedCount = changedCount,
                                    statusMessage = "正在索引新增目录：已更新 $changedCount 项"
                                )
                            }
                        }
                        val updatedDirMtimes = listedDirs.mapNotNull { d ->
                            liveDirMtimes[d]?.let { d to it }
                        }.toMap()
                        db.updateDirectoryMtimes(updatedDirMtimes)

                        val updatedRootSnapshots = snapshotRoots.mapNotNull { r ->
                            if (r in dirtySet && r !in listedDirs) return@mapNotNull null
                            liveDirMtimes[r]?.let { r to it }
                        }.toMap()
                        db.saveDirSnapshots(updatedRootSnapshots, mode)
                    } else if (rootSnapshots.size < snapshotRoots.size) {
                        val missingSnapshots = snapshotRoots.mapNotNull { r ->
                            if (r in rootSnapshots) null else liveDirMtimes[r]?.let { r to it }
                        }.toMap()
                        db.saveDirSnapshots(missingSnapshots, mode)
                    }

                    // Check existing tracked Tier-2 documents for in-place content edits
                    val trackedDocs = db.getTrackedDocFiles()
                    if (trackedDocs.isNotEmpty()) {
                        val docMtimes = provider.statPaths(trackedDocs.map { it.path })
                        val staleDocs = trackedDocs.filter { doc ->
                            val live = docMtimes[doc.path]
                            live != null && live != doc.mtime
                        }
                        if (staleDocs.isNotEmpty()) {
                            val staleByPath = staleDocs.associateBy { it.path }
                            val staleParents = staleDocs.map {
                                val idx = it.path.lastIndexOf('/')
                                if (idx <= 0) "/" else it.path.substring(0, idx)
                            }.distinct()
                            val refreshedEntries = ArrayList<FileUpsertEntry>(staleDocs.size)
                            provider.listDirectChildren(staleParents, filter) { batch ->
                                for (r in batch) {
                                    if (r.path in staleByPath) {
                                        refreshedEntries.add(r.toUpsertEntry(filter))
                                    }
                                }
                            }
                            if (refreshedEntries.isNotEmpty()) {
                                changedCount += db.upsertFileBatch(refreshedEntries)
                            }
                            val refreshedPaths = refreshedEntries.mapTo(HashSet(refreshedEntries.size)) { it.path }
                            val fallbackStale = staleDocs.mapNotNull { doc ->
                                if (doc.path in refreshedPaths) null
                                else docMtimes[doc.path]?.let { doc.fileId to it }
                            }
                            if (fallbackStale.isNotEmpty()) {
                                db.markTier2Stale(fallbackStale)
                                changedCount += fallbackStale.size
                            }
                        }
                    }

                    val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    val updatedStats = db.getStats()
                    val msg = if (dirtyDirs.isEmpty() && removedCount == 0 && changedCount == 0) {
                        "增量校验完成：检查 ${trackedDirs.size} 个目录，均无变动 (${elapsed}ms)"
                    } else {
                        "增量同步完成：更新 $changedCount / 清理 $removedCount 项 (${elapsed}ms)"
                    }
                    _progress.value = _progress.value.copy(
                        tier1Phase = Tier1Phase.IDLE,
                        tier1ScannedCount = changedCount,
                        lastScanDurationMs = elapsed,
                        stats = updatedStats,
                        tier2PendingCount = updatedStats.pendingDocs,
                        statusMessage = msg
                    )
                    ensureTier2Running()
                } catch (ce: CancellationException) {
                    _progress.value = _progress.value.copy(
                        tier1Phase = Tier1Phase.IDLE,
                        statusMessage = "已取消增量同步"
                    )
                    throw ce
                } catch (t: Throwable) {
                    val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    val reason = formatEngineeringError(t, "增量同步")
                    _progress.value = _progress.value.copy(
                        tier1Phase = Tier1Phase.IDLE,
                        lastScanDurationMs = elapsed,
                        errorMessage = reason,
                        statusMessage = reason
                    )
                }
            }
        }
        tier1Job = job
        return job
    }

    fun startFullScan(clearExisting: Boolean = false): Job {
        tier1Job?.cancel()
        tier2Job?.cancel()
        val job = scope.launch {
            tier1Mutex.withLock {
                runFullScanInternal(clearExisting)
            }
        }
        tier1Job = job
        return job
    }

    private suspend fun runFullScanInternal(clearExisting: Boolean) {
        val startMs = System.currentTimeMillis()
        val settings = settingsRepo.settings.value
        val mode = settings.accessMode
        val provider = providerResolver(mode)

        _progress.value = _progress.value.copy(
            tier1Phase = Tier1Phase.FULL_SCAN,
            tier1ScannedCount = 0,
            errorMessage = null,
            statusMessage = "正在检测 ${mode.label} 权限与服务连接…"
        )

        val availability = provider.checkAvailability()
        if (availability !is ModeAvailability.Ready) {
            val reason = availabilityReason(availability)
            _progress.value = _progress.value.copy(
                tier1Phase = Tier1Phase.IDLE,
                errorMessage = reason,
                statusMessage = reason
            )
            return
        }

        // Never wipe existing DB or Tier-2 cache before IPC binds and completes full tree traversal.
        _progress.value = _progress.value.copy(
            tier1Phase = Tier1Phase.FULL_SCAN,
            tier1ScannedCount = 0,
            errorMessage = null,
            statusMessage = when (mode) {
                AccessMode.ROOT -> "正在连接 Root 后台服务并扫描（可能需要数秒响应）…"
                AccessMode.SHIZUKU -> "正在连接 Shizuku 服务并扫描存储…"
                AccessMode.NORMAL -> "正在扫描公开存储目录…"
            }
        )

        val filter = FilterEngine(settings.filterConfig)
        val seenPaths = HashSet<String>(16384)
        var scannedCount = 0

        try {
            provider.scanFullTree(filter) { batch ->
                if (!currentCoroutineContext().isActive) return@scanFullTree
                val entries = ArrayList<FileUpsertEntry>(batch.size)
                for (record in batch) {
                    seenPaths.add(record.path)
                    entries.add(record.toUpsertEntry(filter))
                }
                db.upsertFileBatch(entries)
                scannedCount += batch.size
                val sec = ((System.currentTimeMillis() - startMs) / 1000L).coerceAtLeast(0L)
                _progress.value = _progress.value.copy(
                    tier1ScannedCount = scannedCount,
                    statusMessage = "一级建库中：已扫描 $scannedCount 项 (${sec}s)"
                )
            }

            if (currentCoroutineContext().isActive && seenPaths.isNotEmpty()) {
                _progress.value = _progress.value.copy(statusMessage = "正在比对并清理失效旧条目…")
                db.pruneMissingEntries(seenPaths, scopeMode = null)
            }

            val rootPaths = storageRootsResolver().let { base ->
                if (filter.isDataPotentiallyIndexed(mode)) (base + "/data").distinct() else base
            }
            runCatching {
                val snaps = provider.statDirectories(rootPaths)
                db.saveDirSnapshots(snaps, mode)
            }
            settingsRepo.markFullScanCompleted()

            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            val stats = db.getStats()
            _progress.value = _progress.value.copy(
                tier1Phase = Tier1Phase.IDLE,
                tier1ScannedCount = scannedCount,
                lastScanDurationMs = elapsed,
                stats = stats,
                tier2PendingCount = stats.pendingDocs,
                statusMessage = "全盘建库完成：共 ${stats.totalEntries} 项 · ${formatDuration(elapsed)}"
            )
            ensureTier2Running()
        } catch (ce: CancellationException) {
            val stats = runCatching { db.getStats() }.getOrElse { _progress.value.stats }
            _progress.value = _progress.value.copy(
                tier1Phase = Tier1Phase.IDLE,
                stats = stats,
                tier2PendingCount = stats.pendingDocs,
                statusMessage = "全盘扫描已取消（已保留当前 ${stats.totalEntries} 项）"
            )
            throw ce
        } catch (t: Throwable) {
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            val stats = runCatching { db.getStats() }.getOrElse { _progress.value.stats }
            val reason = formatEngineeringError(t, "全盘扫描")
            _progress.value = _progress.value.copy(
                tier1Phase = Tier1Phase.IDLE,
                lastScanDurationMs = elapsed,
                stats = stats,
                tier2PendingCount = stats.pendingDocs,
                errorMessage = reason,
                statusMessage = reason
            )
        }
    }

    private fun availabilityReason(availability: ModeAvailability): String = when (availability) {
        is ModeAvailability.NeedsPermission -> availability.reason
        is ModeAvailability.Unavailable -> availability.reason
        else -> "当前访问模式不可用"
    }

    private fun formatEngineeringError(t: Throwable, opName: String): String {
        val raw = t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName
        return when {
            raw.contains("超时") -> "$opName 中断：$raw（可能后台服务冷启动较慢或系统限制了 Root/Shizuku 拉起）"
            raw.contains("DeadObject", ignoreCase = true) || raw.contains("Broken pipe", ignoreCase = true) ->
                "$opName 中断：跨进程服务连接意外断开（$raw），可能被系统省电策略回收，建议重试或关闭部分大型目录后重建"
            else -> "$opName 中断：$raw"
        }
    }

    private fun formatDuration(ms: Long): String {
        return if (ms < 1000L) "${ms}ms" else String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
    }

    fun startDirectoryRefresh(dirPath: String) {
        startDirectoriesRefresh(listOf(dirPath))
    }

    fun startDirectoriesRefresh(dirPaths: List<String>): Job {
        val cleanDirs = dirPaths.map { it.trim().trimEnd('/').ifEmpty { "/" } }.distinct()
        return scope.launch {
            if (cleanDirs.isEmpty()) return@launch
            tier1Mutex.withLock {
                try {
                    val settings = settingsRepo.settings.value
                    val provider = providerResolver(settings.accessMode)
                    if (provider.checkAvailability() !is ModeAvailability.Ready) return@withLock
                    val filter = FilterEngine(settings.filterConfig)
                    val targetDirs = cleanDirs.filter { dir ->
                        settings.accessMode == AccessMode.ROOT || !dir.startsWith("/data")
                    }.filterNot { dir ->
                        filter.shouldSkipTier1Path(dir, dir.substringAfterLast('/'), isDir = true, mode = settings.accessMode)
                    }
                    if (targetDirs.isEmpty()) {
                        refreshStats()
                        ensureTier2Running()
                        return@withLock
                    }
                    _progress.value = _progress.value.copy(
                        tier1Phase = Tier1Phase.QUICK_SYNC,
                        tier1ScannedCount = 0,
                        errorMessage = null,
                        statusMessage = "正在扫描新增或变更目录…"
                    )
                    val seen = HashSet<String>(512)
                    seen.addAll(targetDirs)
                    val listedDirs = HashSet<String>(targetDirs.size)
                    var scanned = 0
                    provider.scanDirectories(
                        roots = targetDirs,
                        filterEngine = filter,
                        onDirListed = { listedDirs.add(it) }
                    ) { batch ->
                        for (r in batch) seen.add(r.path)
                        scanned += db.upsertFileBatch(batch.map { it.toUpsertEntry(filter) })
                        _progress.value = _progress.value.copy(
                            tier1ScannedCount = scanned,
                            statusMessage = "正在索引目录：已处理 $scanned 项"
                        )
                    }
                    for (dir in targetDirs) {
                        if (dir in listedDirs) {
                            db.pruneDirectorySubtree(dir, seen)
                        }
                    }
                    val snaps = runCatching { provider.statDirectories(targetDirs) }.getOrDefault(emptyMap())
                    db.saveDirSnapshots(snaps, settings.accessMode)
                    val stats = db.getStats()
                    _progress.value = _progress.value.copy(
                        tier1Phase = Tier1Phase.IDLE,
                        tier1ScannedCount = scanned,
                        stats = stats,
                        tier2PendingCount = stats.pendingDocs,
                        statusMessage = "就绪 · ${stats.totalEntries} 项 / ${stats.indexedDocs} 正文"
                    )
                    ensureTier2Running()
                } catch (ce: CancellationException) {
                    _progress.value = _progress.value.copy(tier1Phase = Tier1Phase.IDLE)
                    throw ce
                } catch (t: Throwable) {
                    _progress.value = _progress.value.copy(
                        tier1Phase = Tier1Phase.IDLE,
                        errorMessage = t.message ?: "目录刷新失败",
                        statusMessage = "目录刷新中断：${t.message ?: "请重试"}"
                    )
                }
            }
        }
    }

    fun ensureTier2Running() {
        if (tier2Job?.isActive == true) return
        tier2Job = scope.launch {
            try {
                val semaphore = Semaphore(2)
                var processed = 0
                while (isActive) {
                    val settings = settingsRepo.settings.value
                    val filter = FilterEngine(settings.filterConfig)
                    val tasks = db.fetchPendingTier2Tasks(
                        limit = 20,
                        priorityOrder = settings.filterConfig.tier2PriorityOrder
                    )
                    if (tasks.isEmpty()) break

                    _progress.value = _progress.value.copy(
                        tier2Phase = Tier2Phase.EXTRACTING,
                        tier2ProcessedCount = processed,
                        tier2PendingCount = db.getStats().pendingDocs
                    )

                    tasks.map { task ->
                        async {
                            semaphore.withPermit {
                                if (!isActive) return@withPermit
                                val provider = providerResolver(task.accessMode)
                                val maxBytes = filter.maxBytesForExtension(task.ext)
                                val outcome = runCatching {
                                    provider.openFileStream(task.path, maxBytes) { input ->
                                        extractorDispatcher.extractByExtension(task.ext, input, maxBytes)
                                    }
                                }.getOrElse { err ->
                                    if (err is CancellationException) throw err
                                    ExtractionResult.Failed(Tier2Status.ERROR, err.message ?: "无法读取文件")
                                }

                                when (outcome) {
                                    is ExtractionResult.Success -> {
                                        db.saveTier2Result(
                                            fileId = task.fileId,
                                            mtime = task.mtime,
                                            status = Tier2Status.INDEXED,
                                            extractedText = outcome.text
                                        )
                                    }
                                    is ExtractionResult.Failed -> {
                                        db.saveTier2Result(
                                            fileId = task.fileId,
                                            mtime = task.mtime,
                                            status = outcome.status,
                                            errorMessage = outcome.message
                                        )
                                    }
                                }
                            }
                        }
                    }.awaitAll()

                    processed += tasks.size
                    val stats = db.getStats()
                    _progress.value = _progress.value.copy(
                        tier2Phase = Tier2Phase.EXTRACTING,
                        tier2ProcessedCount = processed,
                        tier2PendingCount = stats.pendingDocs,
                        stats = stats
                    )
                }

                val finalStats = db.getStats()
                _progress.value = _progress.value.copy(
                    tier2Phase = Tier2Phase.IDLE,
                    tier2PendingCount = finalStats.pendingDocs,
                    stats = finalStats,
                    statusMessage = if (processed > 0 && _progress.value.tier1Phase == Tier1Phase.IDLE) {
                        "就绪 · ${finalStats.totalEntries} 项 / ${finalStats.indexedDocs} 正文"
                    } else {
                        _progress.value.statusMessage
                    }
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _progress.value = _progress.value.copy(
                    tier2Phase = Tier2Phase.IDLE,
                    errorMessage = t.message ?: "正文解析中断"
                )
            }
        }
    }

    private fun shouldPruneMissingDirectory(
        dir: String,
        mode: AccessMode,
        includeData: Boolean,
        rootSet: Set<String>,
        liveDirMtimes: Map<String, Long>
    ): Boolean {
        if (dir in rootSet) return false
        if (dir == "/storage" || dir == "/storage/emulated" || dir == "/storage/self" || dir == "/storage/enc_emulated") {
            return false
        }
        if (dir.startsWith("/data/")) {
            return includeData && "/data" in rootSet && liveDirMtimes["/data"] != null
        }
        if (dir.startsWith("/storage/")) {
            val volRoot = storageVolumeRootFor(dir) ?: return false
            if (dir == volRoot) return false
            if (volRoot !in rootSet || liveDirMtimes[volRoot] == null) return false
            if (mode == AccessMode.NORMAL && (
                    dir == "/storage/emulated/0/Android/data" ||
                        dir.startsWith("/storage/emulated/0/Android/data/") ||
                        dir == "/storage/emulated/0/Android/obb" ||
                        dir.startsWith("/storage/emulated/0/Android/obb/")
                    )
            ) {
                return false
            }
            return true
        }
        return false
    }

    private fun storageVolumeRootFor(path: String): String? {
        if (!path.startsWith("/storage/")) return null
        if (path.startsWith("/storage/emulated/")) {
            val userEnd = path.indexOf('/', "/storage/emulated/".length)
            return if (userEnd < 0) path else path.substring(0, userEnd)
        }
        val volEnd = path.indexOf('/', "/storage/".length)
        return if (volEnd < 0) path else path.substring(0, volEnd)
    }

    private fun ScannedFileRecord.toUpsertEntry(filter: FilterEngine): FileUpsertEntry {
        val status = filter.evaluateTier2Status(path, ext, size, isDir)
        return FileUpsertEntry(
            path = path,
            parentPath = parentPath,
            name = name,
            ext = ext,
            size = size,
            mtime = mtime,
            isDir = isDir,
            accessMode = accessMode,
            needsTier2 = status == Tier2Status.PENDING,
            initialTier2Status = status
        )
    }
}
