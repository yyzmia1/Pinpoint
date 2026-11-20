package com.pocketeverything.app.indexer

import android.content.Context
import android.provider.Settings
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
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

enum class Tier1Phase { IDLE, QUICK_SYNC, FULL_SCAN }
enum class Tier2Phase { IDLE, EXTRACTING }

data class IndexProgressState(
    val tier1Phase: Tier1Phase = Tier1Phase.IDLE,
    val tier1ScannedCount: Int = 0,
    val tier2Phase: Tier2Phase = Tier2Phase.IDLE,
    val tier2ProcessedCount: Int = 0,
    val tier2PendingCount: Long = 0,
    val lastScanDurationMs: Long = 0L,
    val syncGeneration: Long = 0L,
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
    private val storageRootsResolver: () -> List<String> = { StorageRootsHelper.getNormalStorageRoots(context) },
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val bootCountProvider: () -> Int = {
        runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1)
    }
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable is CancellationException) return@CoroutineExceptionHandler
        _progress.update {
            it.copy(
                tier1Phase = Tier1Phase.IDLE,
                tier2Phase = Tier2Phase.IDLE,
                errorMessage = throwable.message ?: "索引任务异常",
                statusMessage = "同步中断：${throwable.message ?: "请重试"}"
            )
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val tier1Mutex = Mutex()
    private val triggerLock = Any()
    @Volatile
    private var tier1Job: Job? = null
    @Volatile
    private var tier1HotStageDone: CompletableDeferred<Unit>? = null
    private var tier2Job: Job? = null
    private var hasSyncedAtLeastOnce: Boolean = false
    private var lastSyncClockMs: Long = 0L

    private val _progress = MutableStateFlow(IndexProgressState())
    val progress: StateFlow<IndexProgressState> = _progress.asStateFlow()

    fun refreshStats() {
        val stats = runCatching { db.getStats() }.getOrNull() ?: return
        _progress.update {
            it.copy(
                stats = stats,
                tier2PendingCount = stats.pendingDocs
            )
        }
    }

    /**
     * Notifies search and analyzer observers after an out-of-band DB mutation (e.g. WizTree deletion).
     */
    fun notifyExternalMutation() {
        val stats = runCatching { db.getStats() }.getOrNull()
        _progress.update { cur ->
            cur.copy(
                stats = stats ?: cur.stats,
                tier2PendingCount = stats?.pendingDocs ?: cur.tier2PendingCount,
                syncGeneration = cur.syncGeneration + 1L
            )
        }
    }

    /**
     * Triggered on app launch: performs a quick hot-directory mtime delta check if index already exists,
     * or runs a full initial scan if database is empty.
     */
    fun triggerStartupSync() {
        synchronized(triggerLock) {
            if (tier1Job?.isActive == true) return
            hasSyncedAtLeastOnce = true
            lastSyncClockMs = clockMs()
            tier1Job = launchQuickSyncLocked(silentIfUnavailable = false)
        }
    }

    /**
     * Throttled incremental sync for foreground resume and SAF DocumentsProvider queries.
     * Reuses any active Tier-1 scan, skips within [minIntervalMs] cooldown, and optionally
     * waits up to [maxWaitMs]. When a scan continues past [maxWaitMs] and mutates the index,
     * [onChanged] is invoked on completion so callers (e.g. DocumentsUI) can refresh.
     */
    fun triggerForegroundSync(
        minIntervalMs: Long = DEFAULT_FOREGROUND_SYNC_INTERVAL_MS,
        maxWaitMs: Long = 0L,
        onChanged: (() -> Unit)? = null
    ): Job? {
        val genBefore = _progress.value.syncGeneration
        val (targetJob, hotStageSignal) = synchronized(triggerLock) {
            val active = tier1Job
            if (active?.isActive == true) {
                active to tier1HotStageDone
            } else {
                val now = clockMs()
                val withinCooldown = hasSyncedAtLeastOnce &&
                    minIntervalMs > 0L &&
                    (now - lastSyncClockMs) < minIntervalMs
                if (withinCooldown) {
                    return null
                }
                hasSyncedAtLeastOnce = true
                lastSyncClockMs = now
                val hotSignal = CompletableDeferred<Unit>()
                tier1HotStageDone = hotSignal
                val created = launchQuickSyncLocked(silentIfUnavailable = true, hotStageDone = hotSignal)
                tier1Job = created
                created to hotSignal
            }
        }

        if (maxWaitMs > 0L) {
            val completedInTime = runCatching {
                runBlocking {
                    withTimeoutOrNull(maxWaitMs) {
                        if (hotStageSignal != null) {
                            hotStageSignal.await()
                        } else {
                            targetJob.join()
                        }
                        true
                    } ?: false
                }
            }.getOrDefault(false)

            if (completedInTime) {
                if (onChanged != null && targetJob.isActive) {
                    val genAfterHot = _progress.value.syncGeneration
                    targetJob.invokeOnCompletion { cause ->
                        if (cause == null && _progress.value.syncGeneration != genAfterHot) {
                            runCatching { onChanged() }
                        }
                    }
                }
                return targetJob
            }
        }

        if (onChanged != null) {
            targetJob.invokeOnCompletion { cause ->
                if (cause == null && _progress.value.syncGeneration != genBefore) {
                    runCatching { onChanged() }
                }
            }
        }
        return targetJob
    }

    suspend fun cancelScanAndAwait() {
        val previous = synchronized(triggerLock) {
            listOfNotNull(tier1Job, tier2Job).also { jobs -> jobs.forEach { it.cancel() } }
        }
        previous.forEach { it.join() }
        tier1Mutex.withLock { }
    }

    suspend fun switchMode(mode: AccessMode): Job {
        cancelScanAndAwait()
        tier1Mutex.withLock { settingsRepo.updateAccessMode(mode) }
        return startFullScan(clearExisting = false)
    }

    fun isTier1Busy(): Boolean = synchronized(triggerLock) {
        tier1Job?.isActive == true
    } || _progress.value.tier1Phase != Tier1Phase.IDLE

    fun clearErrorMessage() {
        _progress.update { it.copy(errorMessage = null) }
    }

    fun cancelCurrentScan() {
        synchronized(triggerLock) {
            tier1Job?.cancel()
            tier1Job = null
        }
        _progress.update {
            it.copy(
                tier1Phase = Tier1Phase.IDLE,
                statusMessage = "已取消扫描",
                errorMessage = null
            )
        }
        scope.launch { refreshStats() }
    }

    fun pruneByCurrentFilter(): PruneSummary {
        val settings = settingsRepo.settings.value
        val summary = db.pruneMatchingFilterDetailed(FilterEngine(settings.filterConfig), settings.accessMode)
        val mutated = summary.removedEntries > 0 || summary.clearedDocBodies > 0 || summary.requeuedDocs > 0
        if (summary.removedEntries > 0) {
            runCatching { db.markAnalyzerCacheDirty() }
        }
        val stats = runCatching { db.getStats() }.getOrNull()
        if (stats != null || mutated) {
            _progress.update { cur ->
                cur.copy(
                    stats = stats ?: cur.stats,
                    tier2PendingCount = stats?.pendingDocs ?: cur.tier2PendingCount,
                    syncGeneration = if (mutated) cur.syncGeneration + 1L else cur.syncGeneration
                )
            }
        }
        if (summary.requeuedDocs > 0) {
            ensureTier2Running()
        }
        return summary
    }

    fun startQuickHotDirsRefresh(
        silentIfUnavailable: Boolean = false,
        includeRootOther: Boolean = false
    ): Job {
        synchronized(triggerLock) {
            val active = tier1Job
            if (active?.isActive == true) {
                return active
            }
            val hotSignal = CompletableDeferred<Unit>()
            tier1HotStageDone = hotSignal
            val job = launchQuickSyncLocked(
                silentIfUnavailable = silentIfUnavailable,
                hotStageDone = hotSignal,
                includeRootOther = includeRootOther
            )
            tier1Job = job
            return job
        }
    }

    private fun finishTier1JobIfMatching(job: Job?, recordCooldown: Boolean) {
        synchronized(triggerLock) {
            if (job != null && tier1Job === job) {
                tier1Job = null
                tier1HotStageDone?.complete(Unit)
                tier1HotStageDone = null
            }
            if (recordCooldown) {
                hasSyncedAtLeastOnce = true
                lastSyncClockMs = clockMs()
            }
        }
    }

    private data class DirSyncSubsetResult(
        val checkedDirs: Int,
        val changedCount: Int,
        val removedCount: Int,
        val dirtyDirCount: Int
    )

    private suspend fun syncDirectorySubset(
        trackedDirs: LinkedHashMap<String, Long>,
        snapshotRoots: List<String>,
        rootSnapshots: Map<String, Long>,
        mode: AccessMode,
        includeData: Boolean,
        filter: FilterEngine,
        provider: FileSystemProvider,
        progressPrefix: String
    ): DirSyncSubsetResult {
        if (trackedDirs.isEmpty()) {
            return DirSyncSubsetResult(0, 0, 0, 0)
        }
        var changedCount = 0
        var removedCount = 0

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

        // A successful parent listing distinguishes deletion from EACCES or an unmounted volume.
        for (missing in missingSubdirs) {
            val parent = missing.substringBeforeLast('/').ifEmpty { "/" }
            if (parent in trackedDirs && parent !in dirtyDirs) dirtyDirs.add(parent)
        }

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
                _progress.update { cur ->
                    cur.copy(
                        tier1ScannedCount = changedCount,
                        statusMessage = "${progressPrefix}已处理 $changedCount 项"
                    )
                }
            }
            // Only prune children of directories that were actually listed (never on list() == null / EACCES).
            for (dirty in listedDirs) {
                removedCount += db.pruneDirectChildren(dirty, seenByParent[dirty].orEmpty(), mode)
            }
            if (newSubdirs.isNotEmpty()) {
                provider.scanDirectories(newSubdirs.distinct(), filter) { batch ->
                    changedCount += db.upsertFileBatch(batch.map { it.toUpsertEntry(filter) })
                    _progress.update { cur ->
                        cur.copy(
                            tier1ScannedCount = changedCount,
                            statusMessage = "${progressPrefix}新增目录 $changedCount 项"
                        )
                    }
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
            if (updatedRootSnapshots.isNotEmpty()) {
                db.saveDirSnapshots(updatedRootSnapshots, mode)
            }
        } else if (snapshotRoots.any { it !in rootSnapshots }) {
            val missingSnapshots = snapshotRoots.mapNotNull { r ->
                if (r in rootSnapshots) null else liveDirMtimes[r]?.let { r to it }
            }.toMap()
            if (missingSnapshots.isNotEmpty()) {
                db.saveDirSnapshots(missingSnapshots, mode)
            }
        }

        return DirSyncSubsetResult(
            checkedDirs = trackedDirs.size,
            changedCount = changedCount,
            removedCount = removedCount,
            dirtyDirCount = dirtyDirs.size
        )
    }

    private fun launchQuickSyncLocked(
        silentIfUnavailable: Boolean,
        hotStageDone: CompletableDeferred<Unit>? = null,
        includeRootOther: Boolean = false
    ): Job = scope.launch {
        var cancelled = false
        try {
            tier1Mutex.withLock {
                val startMs = System.currentTimeMillis()
                var changedCount = 0
                var removedCount = 0
                try {
                    val settings = settingsRepo.settings.value
                    val mode = settings.accessMode
                    val provider = providerResolver(mode)
                    val availability = provider.checkAvailability()
                    if (availability !is ModeAvailability.Ready) {
                        val stats = runCatching { db.getStats() }.getOrNull()
                        hotStageDone?.complete(Unit)
                        finishTier1JobIfMatching(currentCoroutineContext()[Job], recordCooldown = true)
                        if (silentIfUnavailable) {
                            _progress.update { cur ->
                                cur.copy(
                                    tier1Phase = Tier1Phase.IDLE,
                                    stats = stats ?: cur.stats,
                                    tier2PendingCount = stats?.pendingDocs ?: cur.tier2PendingCount
                                )
                            }
                        } else {
                            val reason = availabilityReason(availability)
                            _progress.update { cur ->
                                cur.copy(
                                    tier1Phase = Tier1Phase.IDLE,
                                    stats = stats ?: cur.stats,
                                    tier2PendingCount = stats?.pendingDocs ?: cur.tier2PendingCount,
                                    errorMessage = reason,
                                    statusMessage = reason
                                )
                            }
                        }
                        return@withLock
                    }

                    val currentDbStats = db.getStats()
                    if (currentDbStats.totalEntries == 0L || settings.lastFullScanAtMillis == 0L) {
                        runFullScanInternal(clearExisting = false)
                        hotStageDone?.complete(Unit)
                        return@withLock
                    }

                    _progress.update { cur ->
                        cur.copy(
                            tier1Phase = Tier1Phase.QUICK_SYNC,
                            tier1ScannedCount = 0,
                            stats = currentDbStats,
                            tier2PendingCount = currentDbStats.pendingDocs,
                            statusMessage = "正在极速校验用户存储目录…",
                            errorMessage = null
                        )
                    }

                    val filter = FilterEngine(settings.filterConfig)
                    val includeData = filter.isDataPotentiallyIndexed(mode)
                    val storageRoots = storageRootsResolver().distinct()
                    val storageRootSnapshots = db.getDirSnapshots(storageRoots)

                    // ================= STAGE 1: Hot User Storage (/storage/...) + Recent 30-day Docs (< 1s target) =================
                    val stage1StartMs = System.currentTimeMillis()
                    val hotTrackedDirs = LinkedHashMap<String, Long>(1024)
                    for ((dirPath, savedMtime) in db.getTrackedStorageDirectories()) {
                        val name = dirPath.substringAfterLast('/')
                        if (!filter.shouldSkipTier1Path(dirPath, name, isDir = true, mode = mode)) {
                            hotTrackedDirs[dirPath] = savedMtime
                        }
                    }
                    for (r in storageRoots) {
                        val name = r.substringAfterLast('/')
                        if (!filter.shouldSkipTier1Path(r, name, isDir = true, mode = mode)) {
                            hotTrackedDirs[r] = storageRootSnapshots[r] ?: hotTrackedDirs[r] ?: -1L
                        }
                    }

                    val stage1Res = syncDirectorySubset(
                        trackedDirs = hotTrackedDirs,
                        snapshotRoots = storageRoots,
                        rootSnapshots = storageRootSnapshots,
                        mode = mode,
                        includeData = includeData,
                        filter = filter,
                        provider = provider,
                        progressPrefix = "用户目录同步中："
                    )
                    changedCount += stage1Res.changedCount
                    removedCount += stage1Res.removedCount

                    // Check Top-1000 most recently modified tracked Tier-2 documents for in-place content edits (< 5ms)
                    val recentDocs = db.getRecentTrackedDocFiles(sinceMtimeMs = 0L, limit = RECENT_DOC_CHECK_LIMIT)
                    if (recentDocs.isNotEmpty()) {
                        val docMtimes = provider.statPaths(recentDocs.map { it.path })
                        val staleDocs = recentDocs.filter { doc ->
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

                    val stage1Elapsed = (System.currentTimeMillis() - stage1StartMs).coerceAtLeast(1L)
                    val stage1Mutated = changedCount > 0 || removedCount > 0
                    if (stage1Mutated) {
                        runCatching { db.markAnalyzerCacheDirty() }
                    }

                    // Detect device reboot (Settings.Global.BOOT_COUNT) to trigger Stage 3 `/` partition verification
                    val currentBootCount = runCatching { bootCountProvider() }.getOrDefault(-1)
                    val savedBootCount = settingsRepo.getLastRootOtherSyncBootCount()
                    val rebootDetected = mode == AccessMode.ROOT &&
                        currentBootCount >= 0 &&
                        savedBootCount >= 0 &&
                        currentBootCount != savedBootCount
                    if (mode == AccessMode.ROOT && savedBootCount < 0 && currentBootCount >= 0 && !includeRootOther) {
                        // Seed boot count baseline on first run after upgrading so the next reboot triggers Stage 3.
                        settingsRepo.setLastRootOtherSyncBootCount(currentBootCount)
                    }
                    val runStage3 = mode == AccessMode.ROOT && (includeRootOther || rebootDetected)

                    val hasFollowUpStages = includeData || runStage3
                    if (!hasFollowUpStages) {
                        val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                        val updatedStats = db.getStats()
                        val msg = if (changedCount == 0 && removedCount == 0) {
                            "增量校验完成：检查 ${stage1Res.checkedDirs} 个目录，均无变动 (${formatDuration(elapsed)})"
                        } else {
                            "增量同步完成：更新 $changedCount / 清理 $removedCount 项 (${formatDuration(elapsed)})"
                        }
                        finishTier1JobIfMatching(currentCoroutineContext()[Job], recordCooldown = true)
                        _progress.update { cur ->
                            cur.copy(
                                tier1Phase = Tier1Phase.IDLE,
                                tier1ScannedCount = changedCount,
                                lastScanDurationMs = elapsed,
                                stats = updatedStats,
                                tier2PendingCount = updatedStats.pendingDocs,
                                syncGeneration = if (stage1Mutated) cur.syncGeneration + 1L else cur.syncGeneration,
                                statusMessage = msg
                            )
                        }
                        hotStageDone?.complete(Unit)
                    } else {
                        // Publish Stage 1 completion immediately so search & picker see fresh user files right away!
                        val s1Stats = db.getStats()
                        val nextStageHint = when {
                            includeData -> "后台继续校验 /data…"
                            rebootDetected -> "检测到设备刚重启，后台校验 / 其余分区…"
                            else -> "后台继续校验 / 其余分区…"
                        }
                        val s1Note = if (stage1Mutated) {
                            "用户区已同步（+${changedCount}/-${removedCount}，${formatDuration(stage1Elapsed)}），$nextStageHint"
                        } else {
                            "用户区已同步（${formatDuration(stage1Elapsed)}），$nextStageHint"
                        }
                        _progress.update { cur ->
                            cur.copy(
                                tier1Phase = Tier1Phase.QUICK_SYNC,
                                tier1ScannedCount = changedCount,
                                stats = s1Stats,
                                tier2PendingCount = s1Stats.pendingDocs,
                                syncGeneration = if (stage1Mutated) cur.syncGeneration + 1L else cur.syncGeneration,
                                statusMessage = s1Note
                            )
                        }
                        // Unblock foreground / SAF waiters right after Stage 1 (< 1s)!
                        hotStageDone?.complete(Unit)

                        var totalChecked = stage1Res.checkedDirs
                        var tailMutated = false
                        val timingParts = ArrayList<String>(3)
                        timingParts.add("用户区 ${formatDuration(stage1Elapsed)}")

                        // ================= STAGE 2: Seamless Background /data Incremental Verification =================
                        if (includeData) {
                            val stage2StartMs = System.currentTimeMillis()
                            val dataRoots = listOf("/data")
                            val dataRootSnapshots = db.getDirSnapshots(dataRoots)
                            val dataTrackedDirs = LinkedHashMap<String, Long>(4096)
                            for ((dirPath, savedMtime) in db.getTrackedDataDirectories()) {
                                val name = dirPath.substringAfterLast('/')
                                if (!filter.shouldSkipTier1Path(dirPath, name, isDir = true, mode = mode)) {
                                    dataTrackedDirs[dirPath] = savedMtime
                                }
                            }
                            if (!filter.shouldSkipTier1Path("/data", "data", isDir = true, mode = mode)) {
                                dataTrackedDirs["/data"] = dataRootSnapshots["/data"] ?: dataTrackedDirs["/data"] ?: -1L
                            }

                            val stage2Res = syncDirectorySubset(
                                trackedDirs = dataTrackedDirs,
                                snapshotRoots = dataRoots,
                                rootSnapshots = dataRootSnapshots,
                                mode = mode,
                                includeData = true,
                                filter = filter,
                                provider = provider,
                                progressPrefix = "后台校验 /data："
                            )
                            val stage2Mutated = stage2Res.changedCount > 0 || stage2Res.removedCount > 0
                            changedCount += stage2Res.changedCount
                            removedCount += stage2Res.removedCount
                            totalChecked += stage2Res.checkedDirs
                            if (stage2Mutated) {
                                tailMutated = true
                                runCatching { db.markAnalyzerCacheDirty() }
                            }
                            val stage2Elapsed = (System.currentTimeMillis() - stage2StartMs).coerceAtLeast(1L)
                            timingParts.add("/data ${formatDuration(stage2Elapsed)}")

                            if (runStage3) {
                                val s2Stats = db.getStats()
                                val s3TriggerNote = if (rebootDetected) "检测到设备重启，继续校验 / 其余分区…" else "继续校验 / 其余分区…"
                                _progress.update { cur ->
                                    cur.copy(
                                        tier1Phase = Tier1Phase.QUICK_SYNC,
                                        tier1ScannedCount = changedCount,
                                        stats = s2Stats,
                                        tier2PendingCount = s2Stats.pendingDocs,
                                        syncGeneration = if (stage2Mutated) cur.syncGeneration + 1L else cur.syncGeneration,
                                        statusMessage = "用户区与 /data 已同步，$s3TriggerNote"
                                    )
                                }
                            }
                        }

                        // ================= STAGE 3: Boot / Manual Root Remaining `/` Partitions Verification =================
                        if (runStage3) {
                            val stage3StartMs = System.currentTimeMillis()
                            val slashRoots = listOf("/")
                            val slashRootSnapshots = db.getDirSnapshots(slashRoots)
                            val otherTrackedDirs = LinkedHashMap<String, Long>(2048)
                            for ((dirPath, savedMtime) in db.getTrackedRootOtherDirectories()) {
                                val name = if (dirPath == "/") "/" else dirPath.substringAfterLast('/')
                                if (dirPath == "/" || !filter.shouldSkipTier1Path(dirPath, name, isDir = true, mode = mode)) {
                                    otherTrackedDirs[dirPath] = savedMtime
                                }
                            }
                            otherTrackedDirs["/"] = slashRootSnapshots["/"] ?: otherTrackedDirs["/"] ?: -1L

                            val stage3Res = syncDirectorySubset(
                                trackedDirs = otherTrackedDirs,
                                snapshotRoots = slashRoots,
                                rootSnapshots = slashRootSnapshots,
                                mode = mode,
                                includeData = includeData,
                                filter = filter,
                                provider = provider,
                                progressPrefix = "校验 / 其余分区："
                            )
                            val stage3Mutated = stage3Res.changedCount > 0 || stage3Res.removedCount > 0
                            changedCount += stage3Res.changedCount
                            removedCount += stage3Res.removedCount
                            totalChecked += stage3Res.checkedDirs
                            if (stage3Mutated) {
                                tailMutated = true
                                runCatching { db.markAnalyzerCacheDirty() }
                            }
                            if (currentBootCount >= 0) {
                                settingsRepo.setLastRootOtherSyncBootCount(currentBootCount)
                            }
                            val stage3Elapsed = (System.currentTimeMillis() - stage3StartMs).coerceAtLeast(1L)
                            timingParts.add("/ 其余 ${formatDuration(stage3Elapsed)}")
                        }

                        val totalElapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                        val updatedStats = db.getStats()
                        val timingSummary = timingParts.joinToString(" · ")
                        val msg = if (changedCount == 0 && removedCount == 0) {
                            "增量校验完成：${totalChecked} 目录均无变动（$timingSummary）"
                        } else {
                            "增量同步完成：更新 $changedCount / 清理 $removedCount 项（$timingSummary）"
                        }
                        finishTier1JobIfMatching(currentCoroutineContext()[Job], recordCooldown = true)
                        _progress.update { cur ->
                            cur.copy(
                                tier1Phase = Tier1Phase.IDLE,
                                tier1ScannedCount = changedCount,
                                lastScanDurationMs = totalElapsed,
                                stats = updatedStats,
                                tier2PendingCount = updatedStats.pendingDocs,
                                syncGeneration = if (tailMutated) cur.syncGeneration + 1L else cur.syncGeneration,
                                statusMessage = msg
                            )
                        }
                    }
                    ensureTier2Running()
                } catch (ce: CancellationException) {
                    cancelled = true
                    hotStageDone?.complete(Unit)
                    val mutated = changedCount > 0 || removedCount > 0
                    finishTier1JobIfMatching(currentCoroutineContext()[Job], recordCooldown = false)
                    _progress.update { cur ->
                        cur.copy(
                            tier1Phase = Tier1Phase.IDLE,
                            syncGeneration = if (mutated) cur.syncGeneration + 1L else cur.syncGeneration,
                            statusMessage = "已取消增量同步"
                        )
                    }
                    throw ce
                } catch (t: Throwable) {
                    hotStageDone?.complete(Unit)
                    val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    val reason = formatEngineeringError(t, "增量同步")
                    val mutated = changedCount > 0 || removedCount > 0
                    val stats = runCatching { db.getStats() }.getOrNull()
                    finishTier1JobIfMatching(currentCoroutineContext()[Job], recordCooldown = true)
                    _progress.update { cur ->
                        cur.copy(
                            tier1Phase = Tier1Phase.IDLE,
                            lastScanDurationMs = elapsed,
                            stats = stats ?: cur.stats,
                            tier2PendingCount = stats?.pendingDocs ?: cur.tier2PendingCount,
                            syncGeneration = if (mutated) cur.syncGeneration + 1L else cur.syncGeneration,
                            errorMessage = reason,
                            statusMessage = reason
                        )
                    }
                }
            }
        } catch (ce: CancellationException) {
            cancelled = true
            hotStageDone?.complete(Unit)
            throw ce
        } finally {
            hotStageDone?.complete(Unit)
            finishTier1JobIfMatching(currentCoroutineContext()[Job], recordCooldown = !cancelled)
        }
    }

    fun startFullScan(clearExisting: Boolean = false): Job {
        synchronized(triggerLock) {
            tier1Job?.cancel()
            tier2Job?.cancel()
            val job = scope.launch {
                var cancelled = false
                try {
                    tier1Mutex.withLock {
                        runFullScanInternal(clearExisting)
                    }
                } catch (ce: CancellationException) {
                    cancelled = true
                    throw ce
                } finally {
                    finishTier1JobIfMatching(currentCoroutineContext()[Job], recordCooldown = !cancelled)
                }
            }
            tier1Job = job
            return job
        }
    }

    private suspend fun runFullScanInternal(clearExisting: Boolean) {
        val startMs = System.currentTimeMillis()
        val settings = settingsRepo.settings.value
        val mode = settings.accessMode
        val provider = providerResolver(mode)

        _progress.update { cur ->
            cur.copy(
                tier1Phase = Tier1Phase.FULL_SCAN,
                tier1ScannedCount = 0,
                errorMessage = null,
                statusMessage = "正在检测 ${mode.label} 权限与服务连接…"
            )
        }

        val availability = provider.checkAvailability()
        if (availability !is ModeAvailability.Ready) {
            val reason = availabilityReason(availability)
            finishTier1JobIfMatching(currentCoroutineContext()[Job], recordCooldown = true)
            _progress.update { cur ->
                cur.copy(
                    tier1Phase = Tier1Phase.IDLE,
                    errorMessage = reason,
                    statusMessage = reason
                )
            }
            return
        }

        // Never wipe existing DB or Tier-2 cache before IPC binds and completes full tree traversal.
        _progress.update { cur ->
            cur.copy(
                tier1Phase = Tier1Phase.FULL_SCAN,
                tier1ScannedCount = 0,
                errorMessage = null,
                statusMessage = when (mode) {
                    AccessMode.ROOT -> "正在连接 Root 后台服务并扫描（可能需要数秒响应）…"
                    AccessMode.SHIZUKU -> "正在连接 Shizuku 服务并扫描存储…"
                    AccessMode.NORMAL -> "正在扫描公开存储目录…"
                }
            )
        }

        val filter = FilterEngine(settings.filterConfig)
        val seenPaths = HashSet<String>(16384)
        val listedDirectories = HashSet<String>()
        var scannedCount = 0
        var upsertedCount = 0
        var prunedCount = 0

        try {
            provider.scanFullTree(filter, { listedDirectories.add(it) }) { batch ->
                if (!currentCoroutineContext().isActive) return@scanFullTree
                val entries = ArrayList<FileUpsertEntry>(batch.size)
                for (record in batch) {
                    seenPaths.add(record.path)
                    entries.add(record.toUpsertEntry(filter))
                }
                upsertedCount += db.upsertFileBatch(entries)
                scannedCount += batch.size
                val sec = ((System.currentTimeMillis() - startMs) / 1000L).coerceAtLeast(0L)
                _progress.update { cur ->
                    cur.copy(
                        tier1ScannedCount = scannedCount,
                        statusMessage = "一级建库中：已扫描 $scannedCount 项 (${sec}s)"
                    )
                }
            }

            if (currentCoroutineContext().isActive && listedDirectories.isNotEmpty()) {
                _progress.update { cur -> cur.copy(statusMessage = "正在比对并清理失效旧条目…") }
                val missingTrees = ArrayList<String>()
                prunedCount = db.pruneMissingEntries(seenPaths) { path, sourceMode ->
                    val parent = path.substringBeforeLast('/').ifEmpty { "/" }
                    val confirmedMissing = parent in listedDirectories || missingTrees.any { path.startsWith("$it/") }
                    val remove = if (sourceMode.code > mode.code) {
                        settings.clearIndexOnModeSwitch
                    } else confirmedMissing && !filter.shouldSkipTier1Path(path, path.substringAfterLast('/'), true, mode)
                    if (remove && confirmedMissing) missingTrees.add(path)
                    remove
                }
            }

            val rootPaths = storageRootsResolver().let { base ->
                val withData = if (filter.isDataPotentiallyIndexed(mode)) base + "/data" else base
                val withSlash = if (mode == AccessMode.ROOT) withData + "/" else withData
                withSlash.distinct()
            }
            runCatching {
                val snaps = provider.statDirectories(rootPaths)
                db.saveDirSnapshots(snaps, mode)
            }
            if (mode == AccessMode.ROOT) {
                val bootCount = runCatching { bootCountProvider() }.getOrDefault(-1)
                if (bootCount >= 0) {
                    settingsRepo.setLastRootOtherSyncBootCount(bootCount)
                }
            }
            settingsRepo.markFullScanCompleted()

            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            val stats = db.getStats()
            val mutated = upsertedCount > 0 || prunedCount > 0
            if (mutated) {
                runCatching { db.markAnalyzerCacheDirty() }
            }
            finishTier1JobIfMatching(currentCoroutineContext()[Job], recordCooldown = true)
            _progress.update { cur ->
                cur.copy(
                    tier1Phase = Tier1Phase.IDLE,
                    tier1ScannedCount = scannedCount,
                    lastScanDurationMs = elapsed,
                    stats = stats,
                    tier2PendingCount = stats.pendingDocs,
                    syncGeneration = if (mutated) cur.syncGeneration + 1L else cur.syncGeneration,
                    statusMessage = "全盘建库完成：共 ${stats.totalEntries} 项 · ${formatDuration(elapsed)}"
                )
            }
            ensureTier2Running()
        } catch (ce: CancellationException) {
            val stats = runCatching { db.getStats() }.getOrElse { _progress.value.stats }
            val mutated = upsertedCount > 0 || prunedCount > 0
            finishTier1JobIfMatching(currentCoroutineContext()[Job], recordCooldown = false)
            _progress.update { cur ->
                cur.copy(
                    tier1Phase = Tier1Phase.IDLE,
                    stats = stats,
                    tier2PendingCount = stats.pendingDocs,
                    syncGeneration = if (mutated) cur.syncGeneration + 1L else cur.syncGeneration,
                    statusMessage = "全盘扫描已取消（已保留当前 ${stats.totalEntries} 项）"
                )
            }
            throw ce
        } catch (t: Throwable) {
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            val stats = runCatching { db.getStats() }.getOrElse { _progress.value.stats }
            val reason = formatEngineeringError(t, "全盘扫描")
            val mutated = upsertedCount > 0 || prunedCount > 0
            finishTier1JobIfMatching(currentCoroutineContext()[Job], recordCooldown = true)
            _progress.update { cur ->
                cur.copy(
                    tier1Phase = Tier1Phase.IDLE,
                    lastScanDurationMs = elapsed,
                    stats = stats,
                    tier2PendingCount = stats.pendingDocs,
                    syncGeneration = if (mutated) cur.syncGeneration + 1L else cur.syncGeneration,
                    errorMessage = reason,
                    statusMessage = reason
                )
            }
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
                    _progress.update { cur ->
                        cur.copy(
                            tier1Phase = Tier1Phase.QUICK_SYNC,
                            tier1ScannedCount = 0,
                            errorMessage = null,
                            statusMessage = "正在扫描新增或变更目录…"
                        )
                    }
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
                        _progress.update { cur ->
                            cur.copy(
                                tier1ScannedCount = scanned,
                                statusMessage = "正在索引目录：已处理 $scanned 项"
                            )
                        }
                    }
                    var pruned = 0
                    for (dir in listedDirs) {
                        pruned += db.pruneDirectChildren(dir, seen, settings.accessMode)
                    }
                    val snaps = runCatching { provider.statDirectories(targetDirs) }.getOrDefault(emptyMap())
                    db.saveDirSnapshots(snaps, settings.accessMode)
                    val stats = db.getStats()
                    val mutated = scanned > 0 || pruned > 0
                    _progress.update { cur ->
                        cur.copy(
                            tier1Phase = Tier1Phase.IDLE,
                            tier1ScannedCount = scanned,
                            stats = stats,
                            tier2PendingCount = stats.pendingDocs,
                            syncGeneration = if (mutated) cur.syncGeneration + 1L else cur.syncGeneration,
                            statusMessage = "就绪 · ${stats.totalEntries} 项 / ${stats.indexedDocs} 正文"
                        )
                    }
                    ensureTier2Running()
                } catch (ce: CancellationException) {
                    _progress.update { it.copy(tier1Phase = Tier1Phase.IDLE) }
                    throw ce
                } catch (t: Throwable) {
                    _progress.update { cur ->
                        cur.copy(
                            tier1Phase = Tier1Phase.IDLE,
                            errorMessage = t.message ?: "目录刷新失败",
                            statusMessage = "目录刷新中断：${t.message ?: "请重试"}"
                        )
                    }
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
                        priorityOrder = settings.filterConfig.tier2PriorityOrder,
                        maxMode = settings.accessMode
                    )
                    if (tasks.isEmpty()) break

                    val pendingBefore = db.getStats().pendingDocs
                    _progress.update { cur ->
                        cur.copy(
                            tier2Phase = Tier2Phase.EXTRACTING,
                            tier2ProcessedCount = processed,
                            tier2PendingCount = pendingBefore
                        )
                    }

                    val indexedAny = tasks.map { task ->
                        async {
                            semaphore.withPermit {
                                if (!isActive) return@withPermit false
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
                                        true
                                    }
                                    is ExtractionResult.Failed -> {
                                        db.saveTier2Result(
                                            fileId = task.fileId,
                                            mtime = task.mtime,
                                            status = outcome.status,
                                            errorMessage = outcome.message
                                        )
                                        false
                                    }
                                }
                            }
                        }
                    }.awaitAll().any { it }

                    processed += tasks.size
                    val stats = db.getStats()
                    _progress.update { cur ->
                        cur.copy(
                            tier2Phase = Tier2Phase.EXTRACTING,
                            tier2ProcessedCount = processed,
                            tier2PendingCount = stats.pendingDocs,
                            stats = stats,
                            syncGeneration = if (indexedAny) cur.syncGeneration + 1L else cur.syncGeneration
                        )
                    }
                }

                val finalStats = db.getStats()
                _progress.update { cur ->
                    cur.copy(
                        tier2Phase = Tier2Phase.IDLE,
                        tier2PendingCount = finalStats.pendingDocs,
                        stats = finalStats,
                        statusMessage = if (processed > 0 && cur.tier1Phase == Tier1Phase.IDLE) {
                            "就绪 · ${finalStats.totalEntries} 项 / ${finalStats.indexedDocs} 正文"
                        } else {
                            cur.statusMessage
                        }
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _progress.update { cur ->
                    cur.copy(
                        tier2Phase = Tier2Phase.IDLE,
                        errorMessage = t.message ?: "正文解析中断"
                    )
                }
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
        if (mode == AccessMode.ROOT && dir.startsWith("/") && dir != "/" && "/" in rootSet && liveDirMtimes["/"] != null) {
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

    companion object {
        const val DEFAULT_FOREGROUND_SYNC_INTERVAL_MS = 2_500L
        const val RECENT_DOC_CHECK_WINDOW_MS = 30L * 24L * 3600L * 1000L
        const val RECENT_DOC_CHECK_LIMIT = 1_000
    }
}
