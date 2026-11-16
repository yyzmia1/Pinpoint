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
    private val extractorDispatcher: ExtractorDispatcher = ExtractorDispatcher()
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

    fun startQuickHotDirsRefresh() {
        tier1Job?.cancel()
        tier1Job = scope.launch {
            tier1Mutex.withLock {
                try {
                    val settings = settingsRepo.settings.value
                    val provider = providerResolver(settings.accessMode)
                    if (provider.checkAvailability() !is ModeAvailability.Ready) {
                        refreshStats()
                        return@withLock
                    }

                    _progress.value = _progress.value.copy(
                        tier1Phase = Tier1Phase.QUICK_SYNC,
                        tier1ScannedCount = 0,
                        statusMessage = "正在增量校验常用目录…",
                        errorMessage = null
                    )

                    val filter = FilterEngine(settings.filterConfig)
                    val hotDirs = StorageRootsHelper.getHotDirectories(context)
                    val cachedSnapshots = db.getDirSnapshots(hotDirs)
                    val currentStats = runCatching { provider.statDirectories(hotDirs) }.getOrDefault(emptyMap())

                    val dirtyDirs = currentStats.filter { (dir, mtime) ->
                        cachedSnapshots[dir] != mtime
                    }.keys.toList()

                    var scannedTotal = 0
                    if (dirtyDirs.isNotEmpty()) {
                        for (dir in dirtyDirs) {
                            if (!isActive) break
                            val seenInDir = HashSet<String>(512)
                            scannedTotal += provider.scanDirectories(listOf(dir), filter) { batch ->
                                for (r in batch) seenInDir.add(r.path)
                                val entries = batch.map { it.toUpsertEntry(filter) }
                                db.upsertFileBatch(entries)
                                _progress.value = _progress.value.copy(
                                    tier1ScannedCount = scannedTotal + seenInDir.size
                                )
                            }
                            db.pruneDirectorySubtree(dir, seenInDir)
                        }
                        db.saveDirSnapshots(currentStats, settings.accessMode)
                    }

                    val updatedStats = db.getStats()
                    _progress.value = _progress.value.copy(
                        tier1Phase = Tier1Phase.IDLE,
                        tier1ScannedCount = scannedTotal,
                        stats = updatedStats,
                        tier2PendingCount = updatedStats.pendingDocs,
                        statusMessage = if (dirtyDirs.isEmpty()) "索引已是最新" else "增量同步完成 (+$scannedTotal 项)"
                    )
                    ensureTier2Running()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    _progress.value = _progress.value.copy(
                        tier1Phase = Tier1Phase.IDLE,
                        errorMessage = t.message ?: "增量同步失败",
                        statusMessage = "增量同步中断：${t.message ?: "请重试"}"
                    )
                }
            }
        }
    }

    fun startFullScan(clearExisting: Boolean = false) {
        tier1Job?.cancel()
        tier2Job?.cancel()
        tier1Job = scope.launch {
            tier1Mutex.withLock {
                val settings = settingsRepo.settings.value
                val mode = settings.accessMode
                val provider = providerResolver(mode)
                val availability = provider.checkAvailability()
                if (availability !is ModeAvailability.Ready) {
                    val reason = when (availability) {
                        is ModeAvailability.NeedsPermission -> availability.reason
                        is ModeAvailability.Unavailable -> availability.reason
                        else -> "模式不可用"
                    }
                    _progress.value = _progress.value.copy(
                        tier1Phase = Tier1Phase.IDLE,
                        errorMessage = reason,
                        statusMessage = reason
                    )
                    return@withLock
                }

                if (clearExisting) {
                    db.clearAll()
                }

                _progress.value = _progress.value.copy(
                    tier1Phase = Tier1Phase.FULL_SCAN,
                    tier1ScannedCount = 0,
                    errorMessage = null,
                    statusMessage = "正在一级建库 (${mode.label})…"
                )

                val filter = FilterEngine(settings.filterConfig)
                val seenPaths = HashSet<String>(16384)
                var scannedCount = 0

                try {
                    provider.scanFullTree(filter) { batch ->
                        if (!isActive) return@scanFullTree
                        val entries = ArrayList<FileUpsertEntry>(batch.size)
                        for (record in batch) {
                            seenPaths.add(record.path)
                            entries.add(record.toUpsertEntry(filter))
                        }
                        db.upsertFileBatch(entries)
                        scannedCount += batch.size
                        _progress.value = _progress.value.copy(
                            tier1ScannedCount = scannedCount,
                            statusMessage = "一级索引中：已扫描 $scannedCount 项"
                        )
                    }

                    if (isActive && seenPaths.isNotEmpty()) {
                        db.pruneMissingEntries(seenPaths, scopeMode = if (mode == AccessMode.ROOT) null else mode)
                    }
                    val hotDirs = StorageRootsHelper.getHotDirectories(context)
                    runCatching {
                        val snaps = provider.statDirectories(hotDirs)
                        db.saveDirSnapshots(snaps, mode)
                    }
                    settingsRepo.markFullScanCompleted()

                    val stats = db.getStats()
                    _progress.value = _progress.value.copy(
                        tier1Phase = Tier1Phase.IDLE,
                        tier1ScannedCount = scannedCount,
                        stats = stats,
                        tier2PendingCount = stats.pendingDocs,
                        statusMessage = "一级索引完成（共 ${stats.totalEntries} 项）"
                    )
                    ensureTier2Running()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    _progress.value = _progress.value.copy(
                        tier1Phase = Tier1Phase.IDLE,
                        errorMessage = t.message ?: "扫描失败",
                        statusMessage = "扫描中断：${t.message ?: "请重试"}"
                    )
                }
            }
        }
    }

    fun startDirectoryRefresh(dirPath: String) {
        scope.launch {
            tier1Mutex.withLock {
                try {
                    val settings = settingsRepo.settings.value
                    val provider = providerResolver(settings.accessMode)
                    if (provider.checkAvailability() !is ModeAvailability.Ready) return@withLock
                    val filter = FilterEngine(settings.filterConfig)
                    val seen = HashSet<String>(256)
                    provider.scanDirectories(listOf(dirPath), filter) { batch ->
                        for (r in batch) seen.add(r.path)
                        db.upsertFileBatch(batch.map { it.toUpsertEntry(filter) })
                    }
                    db.pruneDirectorySubtree(dirPath, seen)
                    refreshStats()
                    ensureTier2Running()
                } catch (ce: CancellationException) {
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
                    val tasks = db.fetchPendingTier2Tasks(limit = 20)
                    if (tasks.isEmpty()) break

                    val settings = settingsRepo.settings.value
                    val filter = FilterEngine(settings.filterConfig)

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
                    statusMessage = if (_progress.value.tier1Phase == Tier1Phase.IDLE) {
                        "就绪 · ${finalStats.totalEntries} 文件 / ${finalStats.indexedDocs} 正文"
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
