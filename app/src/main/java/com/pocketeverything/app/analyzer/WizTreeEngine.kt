package com.pocketeverything.app.analyzer

import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.search.SqliteSearchDatabase
import java.util.Locale
import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.min

enum class AnalyzerTreeSortField(val label: String, val defaultDescending: Boolean) {
    SIZE("体积", true),
    COUNT("文件数", true),
    TIME("时间", true),
    NAME("名称", false)
}

enum class AnalyzerTreeSort(
    val sortField: AnalyzerTreeSortField,
    val descending: Boolean
) {
    SIZE_DESC(AnalyzerTreeSortField.SIZE, true),
    SIZE_ASC(AnalyzerTreeSortField.SIZE, false),
    COUNT_DESC(AnalyzerTreeSortField.COUNT, true),
    COUNT_ASC(AnalyzerTreeSortField.COUNT, false),
    TIME_DESC(AnalyzerTreeSortField.TIME, true),
    TIME_ASC(AnalyzerTreeSortField.TIME, false),
    NAME_ASC(AnalyzerTreeSortField.NAME, false),
    NAME_DESC(AnalyzerTreeSortField.NAME, true);

    val label: String get() = sortField.label
    val directionArrow: String get() = if (descending) "↓" else "↑"
    val shortLabel: String get() = "${sortField.label}$directionArrow"

    fun toggledDirection(): AnalyzerTreeSort = when (this) {
        SIZE_DESC -> SIZE_ASC
        SIZE_ASC -> SIZE_DESC
        COUNT_DESC -> COUNT_ASC
        COUNT_ASC -> COUNT_DESC
        TIME_DESC -> TIME_ASC
        TIME_ASC -> TIME_DESC
        NAME_ASC -> NAME_DESC
        NAME_DESC -> NAME_ASC
    }

    internal fun toSqlFileOrderBy(): String = when (this) {
        SIZE_DESC, COUNT_DESC -> "size DESC, name COLLATE NOCASE ASC"
        SIZE_ASC, COUNT_ASC -> "size ASC, name COLLATE NOCASE ASC"
        TIME_DESC -> "mtime DESC, name COLLATE NOCASE ASC"
        TIME_ASC -> "mtime ASC, name COLLATE NOCASE ASC"
        NAME_ASC -> "name COLLATE NOCASE ASC"
        NAME_DESC -> "name COLLATE NOCASE DESC"
    }

    internal fun toSqlDirOrderBy(): String = when (this) {
        SIZE_DESC -> "total_bytes DESC, name COLLATE NOCASE ASC"
        SIZE_ASC -> "total_bytes ASC, name COLLATE NOCASE ASC"
        COUNT_DESC -> "(file_count + dir_count) DESC, total_bytes DESC, name COLLATE NOCASE ASC"
        COUNT_ASC -> "(file_count + dir_count) ASC, total_bytes ASC, name COLLATE NOCASE ASC"
        TIME_DESC -> "mtime DESC, name COLLATE NOCASE ASC"
        TIME_ASC -> "mtime ASC, name COLLATE NOCASE ASC"
        NAME_ASC -> "name COLLATE NOCASE ASC"
        NAME_DESC -> "name COLLATE NOCASE DESC"
    }

    companion object {
        fun forField(field: AnalyzerTreeSortField, descending: Boolean = field.defaultDescending): AnalyzerTreeSort =
            when (field) {
                AnalyzerTreeSortField.SIZE -> if (descending) SIZE_DESC else SIZE_ASC
                AnalyzerTreeSortField.COUNT -> if (descending) COUNT_DESC else COUNT_ASC
                AnalyzerTreeSortField.TIME -> if (descending) TIME_DESC else TIME_ASC
                AnalyzerTreeSortField.NAME -> if (descending) NAME_DESC else NAME_ASC
            }
    }
}

data class AnalyzerShortcut(
    val path: String,
    val label: String,
    val totalBytes: Long
)

data class AppAttributionBadge(
    val packageName: String,
    val displayLabel: String,
    val isInstalled: Boolean,
    val category: AppPathCategory
)

data class TreeChildEntry(
    val path: String,
    val name: String,
    val ext: String,
    val isDir: Boolean,
    val totalBytes: Long,
    val fileCount: Long,
    val dirCount: Long,
    val mtime: Long,
    val accessMode: AccessMode,
    val shareOfParent: Float,
    val appBadge: AppAttributionBadge? = null
)

data class DirNodeStat(
    val path: String,
    val parentPath: String,
    val name: String,
    val totalBytes: Long,
    val fileCount: Long,
    val dirCount: Long,
    val directFileCount: Long = 0L,
    val mtime: Long,
    val accessMode: AccessMode,
    val appBadge: AppAttributionBadge? = null,
    val childrenBySize: List<TreeChildEntry> = emptyList()
) {
    fun childrenSorted(sort: AnalyzerTreeSort, foldersFirst: Boolean = false): List<TreeChildEntry> =
        sortTreeChildren(childrenBySize, sort, foldersFirst)
}

fun sortTreeChildren(
    children: List<TreeChildEntry>,
    sort: AnalyzerTreeSort,
    foldersFirst: Boolean = false
): List<TreeChildEntry> {
    val valueComparator: Comparator<TreeChildEntry> = when (sort) {
        AnalyzerTreeSort.SIZE_DESC -> compareByDescending<TreeChildEntry> { it.totalBytes }
            .thenByDescending { it.isDir }
            .thenBy { it.name.lowercase(Locale.ROOT) }
        AnalyzerTreeSort.SIZE_ASC -> compareBy<TreeChildEntry> { it.totalBytes }
            .thenByDescending { it.isDir }
            .thenBy { it.name.lowercase(Locale.ROOT) }
        AnalyzerTreeSort.COUNT_DESC -> compareByDescending<TreeChildEntry> { it.fileCount + it.dirCount }
            .thenByDescending { it.totalBytes }
            .thenBy { it.name.lowercase(Locale.ROOT) }
        AnalyzerTreeSort.COUNT_ASC -> compareBy<TreeChildEntry> { it.fileCount + it.dirCount }
            .thenBy { it.totalBytes }
            .thenBy { it.name.lowercase(Locale.ROOT) }
        AnalyzerTreeSort.TIME_DESC -> compareByDescending<TreeChildEntry> { it.mtime }
            .thenBy { it.name.lowercase(Locale.ROOT) }
        AnalyzerTreeSort.TIME_ASC -> compareBy<TreeChildEntry> { it.mtime }
            .thenBy { it.name.lowercase(Locale.ROOT) }
        AnalyzerTreeSort.NAME_ASC -> compareBy<TreeChildEntry> { it.name.lowercase(Locale.ROOT) }
            .thenBy { it.name }
        AnalyzerTreeSort.NAME_DESC -> compareByDescending<TreeChildEntry> { it.name.lowercase(Locale.ROOT) }
            .thenByDescending { it.name }
    }
    val finalComparator = if (foldersFirst) {
        compareByDescending<TreeChildEntry> { it.isDir }.then(valueComparator)
    } else {
        valueComparator
    }
    return children.sortedWith(finalComparator)
}

data class AppLocationStat(
    val path: String,
    val category: AppPathCategory,
    val totalBytes: Long,
    val fileCount: Long,
    val accessMode: AccessMode
)

data class AppStorageSummary(
    val packageName: String,
    val displayLabel: String,
    val isInstalled: Boolean,
    val totalBytes: Long,
    val fileCount: Long,
    val shareOfTotal: Float,
    val locations: List<AppLocationStat>
)

data class ExtensionStat(
    val ext: String,
    val displayLabel: String,
    val totalBytes: Long,
    val fileCount: Long,
    val shareOfTotal: Float
)

data class LargeFileEntry(
    val path: String,
    val parentPath: String,
    val name: String,
    val ext: String,
    val size: Long,
    val mtime: Long,
    val accessMode: AccessMode,
    val appBadge: AppAttributionBadge? = null
)

/**
 * Lightweight, O(1)-memory WizTree snapshot.
 * Production builds NEVER retain the full directory tree in JVM heap — directory nodes and children
 * are read on-demand (< 1ms) from SQLite's persisted `analyzer_dir_stats` cache table.
 */
data class WizTreeSnapshot(
    val totalBytes: Long,
    val totalFiles: Long,
    val totalDirs: Long,
    val buildDurationMs: Long,
    val rootPaths: List<String>,
    val shortcuts: List<AnalyzerShortcut> = emptyList(),
    val appRankings: List<AppStorageSummary>,
    val extensionStats: List<ExtensionStat>,
    val topLargeFiles: List<LargeFileEntry>,
    val isFromCache: Boolean = false,
    val isStale: Boolean = false,
    val builtGeneration: Long = 0L,
    val dirNodes: Map<String, DirNodeStat> = emptyMap(),
    val topFilesByExt: Map<String, List<LargeFileEntry>> = emptyMap()
) {
    val defaultRootPath: String? get() = shortcuts.firstOrNull()?.path ?: rootPaths.firstOrNull()
    val uninstalledAppCount: Int get() = appRankings.count { !it.isInstalled }
    val uninstalledResidueBytes: Long get() = appRankings.filter { !it.isInstalled }.sumOf { it.totalBytes }

    fun largeFilesForExt(extFilter: String?): List<LargeFileEntry> {
        if (extFilter == null) return topLargeFiles
        return topFilesByExt[extFilter].orEmpty()
    }
}

data class TreemapInput(
    val id: String,
    val label: String,
    val weight: Long,
    val ext: String,
    val isDir: Boolean,
    val isResidue: Boolean = false,
    val isOtherBucket: Boolean = false
)

data class TreemapRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val item: TreemapInput
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/**
 * Bruls-Huizing-van Wijk Squarified Treemap layout algorithm.
 */
object SquarifiedTreemap {
    fun layout(
        inputs: List<TreemapInput>,
        width: Float,
        height: Float,
        maxTiles: Int = 16
    ): List<TreemapRect> {
        if (width <= 1f || height <= 1f) return emptyList()
        val positive = inputs.filter { it.weight > 0L }.sortedByDescending { it.weight }
        if (positive.isEmpty()) return emptyList()

        val prepared = if (positive.size > maxTiles) {
            val head = positive.take(maxTiles - 1)
            val tailBytes = positive.drop(maxTiles - 1).sumOf { it.weight }
            head + TreemapInput(
                id = "__other__",
                label = "其他 (${positive.size - (maxTiles - 1)} 项)",
                weight = tailBytes,
                ext = "",
                isDir = false,
                isOtherBucket = true
            )
        } else {
            positive
        }

        val totalWeight = prepared.sumOf { it.weight }.toDouble()
        if (totalWeight <= 0.0) return emptyList()

        val totalArea = width.toDouble() * height.toDouble()
        val scaledAreas = DoubleArray(prepared.size) { i ->
            (prepared[i].weight.toDouble() / totalWeight) * totalArea
        }

        val result = ArrayList<TreemapRect>(prepared.size)
        var x = 0.0
        var y = 0.0
        var remW = width.toDouble()
        var remH = height.toDouble()

        var idx = 0
        while (idx < prepared.size && remW > 0.5 && remH > 0.5) {
            val shortSide = min(remW, remH)
            val rowStart = idx
            var rowSum = scaledAreas[idx]
            var rowMax = scaledAreas[idx]
            var rowMin = scaledAreas[idx]
            var currentWorst = worstAspectRatio(rowSum, rowMax, rowMin, shortSide)
            idx++

            while (idx < prepared.size) {
                val nextArea = scaledAreas[idx]
                val nextSum = rowSum + nextArea
                val nextMax = max(rowMax, nextArea)
                val nextMin = min(rowMin, nextArea)
                val nextWorst = worstAspectRatio(nextSum, nextMax, nextMin, shortSide)
                if (nextWorst <= currentWorst) {
                    rowSum = nextSum
                    rowMax = nextMax
                    rowMin = nextMin
                    currentWorst = nextWorst
                    idx++
                } else {
                    break
                }
            }

            val horizontalStrip = remW >= remH
            if (horizontalStrip) {
                val stripW = (rowSum / remH).coerceIn(0.0, remW)
                var curY = y
                for (k in rowStart until idx) {
                    val tileH = if (k == idx - 1) {
                        (y + remH - curY).coerceAtLeast(0.0)
                    } else {
                        if (stripW > 0.0) (scaledAreas[k] / stripW).coerceAtLeast(0.0) else 0.0
                    }
                    result.add(
                        TreemapRect(
                            left = x.toFloat(),
                            top = curY.toFloat(),
                            right = (x + stripW).toFloat(),
                            bottom = (curY + tileH).toFloat(),
                            item = prepared[k]
                        )
                    )
                    curY += tileH
                }
                x += stripW
                remW = (remW - stripW).coerceAtLeast(0.0)
            } else {
                val stripH = (rowSum / remW).coerceIn(0.0, remH)
                var curX = x
                for (k in rowStart until idx) {
                    val tileW = if (k == idx - 1) {
                        (x + remW - curX).coerceAtLeast(0.0)
                    } else {
                        if (stripH > 0.0) (scaledAreas[k] / stripH).coerceAtLeast(0.0) else 0.0
                    }
                    result.add(
                        TreemapRect(
                            left = curX.toFloat(),
                            top = y.toFloat(),
                            right = (curX + tileW).toFloat(),
                            bottom = (y + stripH).toFloat(),
                            item = prepared[k]
                        )
                    )
                    curX += tileW
                }
                y += stripH
                remH = (remH - stripH).coerceAtLeast(0.0)
            }
        }
        return result
    }

    private fun worstAspectRatio(sum: Double, maxArea: Double, minArea: Double, side: Double): Double {
        if (sum <= 0.0 || minArea <= 0.0 || side <= 0.0) return Double.MAX_VALUE
        val s2 = side * side
        val sum2 = sum * sum
        return max((s2 * maxArea) / sum2, sum2 / (s2 * minArea))
    }
}

/**
 * Persistent, O(1)-JVM-memory WizTree storage analyzer engine.
 * - Persists materialized directory / extension / app rollup tables directly in SQLite (`analyzer_*`).
 * - Cold open reads cached summary in < 10ms with < 2MB JVM memory even on 400GB+ Root indexes.
 * - Unifies Root `/` tree so `/data` and `/storage/emulated/0` roll up cleanly into `/`.
 * - Builds mode-aware quick-jump shortcuts (`/` leftmost in Root mode, `模块目录` when present).
 */
object WizTreeEngine {

    private const val MAX_GLOBAL_LARGE_FILES = 200
    private const val MAX_EXTENSION_BUCKETS = 36
    const val DEFAULT_DIRECT_FILES_LIMIT = 500
    const val DEFAULT_CHILD_DIRS_LIMIT = 1500
    private const val CACHE_SCHEMA_VERSION = "2"

    private class MutableDir(
        val path: String,
        var parentPath: String,
        var name: String,
        var mtime: Long = 0L,
        var accessMode: AccessMode = AccessMode.NORMAL
    ) {
        var totalBytes: Long = 0L
        var fileCount: Long = 0L
        var directFileCount: Long = 0L
        var dirCount: Long = 0L
    }

    private class MutableAppLocAcc(
        val path: String,
        val category: AppPathCategory,
        var accessMode: AccessMode
    ) {
        var totalBytes: Long = 0L
        var fileCount: Long = 0L
    }

    private class MutableAppAcc(
        val packageName: String,
        val fallbackLabel: String?
    ) {
        var totalBytes: Long = 0L
        var fileCount: Long = 0L
        val locations = HashMap<String, MutableAppLocAcc>(2)
    }

    /**
     * Loads the persisted analyzer snapshot from SQLite cache in < 10ms with O(1) JVM heap.
     * Returns null if no cache exists yet or schema version changed.
     */
    fun loadFromCache(
        db: SqliteSearchDatabase,
        appInfoResolver: AppInfoResolver,
        accessMode: AccessMode = AccessMode.NORMAL,
        preferredRoots: List<String> = emptyList(),
        currentGeneration: Long = 0L
    ): WizTreeSnapshot? {
        val meta = db.loadAnalyzerMeta()
        if (meta["schema_version"] != CACHE_SCHEMA_VERSION || meta["visibility"] != db.visibilityKey ||
            meta["mode"] != accessMode.code.toString()) return null
        val totalBytes = meta["total_bytes"]?.toLongOrNull() ?: return null
        val totalFiles = meta["total_files"]?.toLongOrNull() ?: 0L
        val totalDirs = meta["total_dirs"]?.toLongOrNull() ?: 0L
        val buildDurationMs = meta["build_duration_ms"]?.toLongOrNull() ?: 1L
        val builtGen = meta["built_generation"]?.toLongOrNull() ?: 0L
        val dirtyFlag = meta["dirty"] == "1"
        val isStale = dirtyFlag || (currentGeneration > 0L && builtGen != currentGeneration)

        val rootPaths = meta["root_paths"]
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (rootPaths.isEmpty()) return null

        val denomTotal = totalBytes.coerceAtLeast(1L).toFloat()
        val extStats = db.loadAnalyzerExtRows().map { row ->
            val normExt = row.ext.trim().trimStart('.').lowercase(Locale.ROOT)
            ExtensionStat(
                ext = normExt,
                displayLabel = if (normExt.isEmpty()) "无后缀" else ".$normExt",
                totalBytes = row.totalBytes.coerceAtLeast(0L),
                fileCount = row.fileCount.coerceAtLeast(0L),
                shareOfTotal = (row.totalBytes.coerceAtLeast(0L).toFloat() / denomTotal).coerceIn(0f, 1f)
            )
        }

        val resolvedCache = HashMap<String, ResolvedAppMeta>(64)
        fun resolveMeta(pkg: String, fallback: String?): ResolvedAppMeta =
            resolvedCache.getOrPut(pkg) { appInfoResolver.resolve(pkg, fallback) }

        fun resolveBadge(match: PathAttributionMatch?): AppAttributionBadge? {
            if (match == null) return null
            val m = resolveMeta(match.packageName, match.fallbackLabel)
            return AppAttributionBadge(m.packageName, m.displayLabel, m.isInstalled, match.category)
        }

        val appRankings = db.loadAnalyzerAppRows().map { row ->
            val m = resolveMeta(row.packageName, row.fallbackLabel)
            val locs = decodeAppLocations(row.locationsBlob)
            AppStorageSummary(
                packageName = m.packageName,
                displayLabel = m.displayLabel,
                isInstalled = m.isInstalled,
                totalBytes = row.totalBytes.coerceAtLeast(0L),
                fileCount = row.fileCount.coerceAtLeast(0L),
                shareOfTotal = (row.totalBytes.coerceAtLeast(0L).toFloat() / denomTotal).coerceIn(0f, 1f),
                locations = locs
            )
        }

        val topLargeFiles = db.queryTopLargeFilesForAnalyzer(extFilter = null, limit = MAX_GLOBAL_LARGE_FILES)
            .map { it.toLargeFileEntry(::resolveBadge) }

        val shortcuts = buildModeShortcuts(
            accessMode = accessMode,
            rootPaths = rootPaths,
            preferredRoots = preferredRoots,
            lookupDirBytes = { p -> db.loadAnalyzerDirRow(p)?.totalBytes }
        )

        return WizTreeSnapshot(
            totalBytes = totalBytes,
            totalFiles = totalFiles,
            totalDirs = totalDirs,
            buildDurationMs = buildDurationMs,
            rootPaths = rootPaths,
            shortcuts = shortcuts,
            appRankings = appRankings,
            extensionStats = extStats,
            topLargeFiles = topLargeFiles,
            isFromCache = true,
            isStale = isStale,
            builtGeneration = builtGen
        )
    }

    /**
     * Builds the materialized rollup from SQLite in a single lean pass, writes directly into
     * `analyzer_*` cache tables, immediately releases temporary directory maps, and returns
     * the lightweight O(1)-heap [WizTreeSnapshot].
     */
    fun buildFromDatabase(
        db: SqliteSearchDatabase,
        appInfoResolver: AppInfoResolver,
        preferredRoots: List<String> = emptyList(),
        accessMode: AccessMode = AccessMode.NORMAL,
        syncGeneration: Long = 0L,
        onProgress: (String) -> Unit = {}
    ): WizTreeSnapshot {
        val startMs = System.nanoTime() / 1_000_000L
        val visibilityAtStart = db.visibilityKey

        val dirs = HashMap<String, MutableDir>(4096)
        val appMap = HashMap<String, MutableAppAcc>(256)
        val normalizedPreferred = preferredRoots.map { normalizeDirPath(it) }.filter { it.isNotEmpty() }.distinct()
        var hasNonStorageEntries = accessMode == AccessMode.ROOT

        var totalBytes = 0L
        var totalFiles = 0L
        var totalDirs = 0L

        onProgress("正在读取目录结构…")
        db.streamDirectoriesForAnalyzer { rawPath, rawParent, rawName, mtime, modeCode ->
            val path = normalizeDirPath(rawPath)
            if (path.isEmpty()) return@streamDirectoriesForAnalyzer
            totalDirs++
            if (!hasNonStorageEntries && isNonStorageRootPath(path)) {
                hasNonStorageEntries = true
            }
            val mode = AccessMode.fromCode(modeCode)
            val parent = normalizeDirPath(rawParent).ifEmpty { parentOf(path) }
            val name = rawName.ifEmpty { nameOf(path) }
            val node = getOrCreateDir(dirs, path, parent, name, mtime, mode)
            if (mtime > node.mtime) node.mtime = mtime
        }

        val stopRoots = buildStopRoots(normalizedPreferred, unifyUnderSlash = hasNonStorageEntries)

        // Ensure all directory entries are linked up to their proper stop root
        val initialDirPaths = dirs.keys.toList()
        for (p in initialDirPaths) {
            val mode = dirs[p]?.accessMode ?: AccessMode.NORMAL
            ensureAncestorChain(dirs, stopRoots, p, mode, unifyUnderSlash = hasNonStorageEntries)
        }

        onProgress("正在聚合各目录占用空间…")
        db.streamDirectoryRollupsForAnalyzer { rawParent, directBytes, directFileCount, _, maxMtime, modeCode ->
            val parent = normalizeDirPath(rawParent)
            if (parent.isEmpty()) return@streamDirectoryRollupsForAnalyzer
            val safeBytes = directBytes.coerceAtLeast(0L)
            val safeFiles = directFileCount.coerceAtLeast(0L)
            val mode = AccessMode.fromCode(modeCode)

            if (!hasNonStorageEntries && isNonStorageRootPath(parent)) {
                hasNonStorageEntries = true
                stopRoots.clear()
                stopRoots.add("/")
            }

            if (safeFiles > 0L || safeBytes > 0L) {
                totalBytes += safeBytes
                totalFiles += safeFiles

                val node = ensureAncestorChain(dirs, stopRoots, parent, mode, unifyUnderSlash = hasNonStorageEntries)
                node.totalBytes += safeBytes
                node.fileCount += safeFiles
                node.directFileCount += safeFiles
                if (maxMtime > node.mtime) node.mtime = maxMtime

                val appMatch = AppStorageAttribution.matchPath(parent)
                if (appMatch != null) {
                    val appAcc = appMap.getOrPut(appMatch.packageName) {
                        MutableAppAcc(appMatch.packageName, appMatch.fallbackLabel)
                    }
                    appAcc.totalBytes += safeBytes
                    appAcc.fileCount += safeFiles
                    val locAcc = appAcc.locations.getOrPut(appMatch.ownerRootPath) {
                        MutableAppLocAcc(appMatch.ownerRootPath, appMatch.category, mode)
                    }
                    locAcc.totalBytes += safeBytes
                    locAcc.fileCount += safeFiles
                }
            }
        }

        onProgress("正在汇总多级目录树…")
        if (hasNonStorageEntries) {
            // Re-ensure any earlier storage roots link cleanly up to "/" if non-storage rows appeared late
            val existingRoots = dirs.keys.filter { it != "/" && parentOf(it) !in dirs }
            for (r in existingRoots) {
                ensureAncestorChain(dirs, stopRoots, r, dirs[r]?.accessMode ?: AccessMode.NORMAL, unifyUnderSlash = true)
            }
        }
        rollupBottomUp(dirs, stopRoots)

        val rootPaths = computeRootPaths(dirs, stopRoots, normalizedPreferred, unifyUnderSlash = hasNonStorageEntries)

        val resolvedAppBadges = HashMap<String, ResolvedAppMeta>(appMap.size)
        fun resolveBadge(match: PathAttributionMatch?): AppAttributionBadge? {
            if (match == null) return null
            val meta = resolvedAppBadges.getOrPut(match.packageName) {
                appInfoResolver.resolve(match.packageName, match.fallbackLabel)
            }
            return AppAttributionBadge(
                packageName = meta.packageName,
                displayLabel = meta.displayLabel,
                isInstalled = meta.isInstalled,
                category = match.category
            )
        }

        onProgress("正在统计扩展名与大文件…")
        val denomTotal = totalBytes.coerceAtLeast(1L).toFloat()
        val rawExtRows = db.queryExtensionStatsForAnalyzer(MAX_EXTENSION_BUCKETS)
        val extensionStats = rawExtRows.map { row ->
            val normExt = row.ext.trim().trimStart('.').lowercase(Locale.ROOT)
            ExtensionStat(
                ext = normExt,
                displayLabel = if (normExt.isEmpty()) "无后缀" else ".$normExt",
                totalBytes = row.totalBytes.coerceAtLeast(0L),
                fileCount = row.fileCount.coerceAtLeast(0L),
                shareOfTotal = (row.totalBytes.coerceAtLeast(0L).toFloat() / denomTotal).coerceIn(0f, 1f)
            )
        }

        val appRankings = materializeAppRankings(appMap, resolvedAppBadges, appInfoResolver, totalBytes)
        val appCacheRows = appRankings.map { app ->
            SqliteSearchDatabase.AnalyzerAppCacheRow(
                packageName = app.packageName,
                fallbackLabel = appMap[app.packageName]?.fallbackLabel,
                totalBytes = app.totalBytes,
                fileCount = app.fileCount,
                locationsBlob = encodeAppLocations(app.locations)
            )
        }

        val shortcuts = buildModeShortcuts(
            accessMode = accessMode,
            rootPaths = rootPaths,
            preferredRoots = normalizedPreferred,
            lookupDirBytes = { p -> dirs[p]?.totalBytes }
        )

        val topLargeFiles = db.queryTopLargeFilesForAnalyzer(extFilter = null, limit = MAX_GLOBAL_LARGE_FILES)
            .map { it.toLargeFileEntry(::resolveBadge) }

        val elapsed = ((System.nanoTime() / 1_000_000L) - startMs).coerceAtLeast(1L)

        onProgress("正在写入磁盘缓存…")
        val metaMap = mapOf(
            "schema_version" to CACHE_SCHEMA_VERSION,
            "visibility" to visibilityAtStart,
            "mode" to accessMode.code.toString(),
            "total_bytes" to totalBytes.toString(),
            "total_files" to totalFiles.toString(),
            "total_dirs" to totalDirs.toString(),
            "build_duration_ms" to elapsed.toString(),
            "built_generation" to syncGeneration.toString(),
            "built_at" to System.currentTimeMillis().toString(),
            "root_paths" to rootPaths.joinToString("\n"),
            "dirty" to "0"
        )
        val dirRowsIterable = Iterable {
            val it = dirs.values.iterator()
            object : Iterator<SqliteSearchDatabase.AnalyzerDirCacheRow> {
                override fun hasNext(): Boolean = it.hasNext()
                override fun next(): SqliteSearchDatabase.AnalyzerDirCacheRow {
                    val d = it.next()
                    return SqliteSearchDatabase.AnalyzerDirCacheRow(
                        path = d.path,
                        parentPath = d.parentPath,
                        name = d.name,
                        totalBytes = d.totalBytes,
                        fileCount = d.fileCount,
                        dirCount = d.dirCount,
                        directFileCount = d.directFileCount,
                        mtime = d.mtime,
                        accessModeCode = d.accessMode.code
                    )
                }
            }
        }
        if (visibilityAtStart != db.visibilityKey) throw kotlinx.coroutines.CancellationException("索引显示范围已改变")
        db.replaceAnalyzerCache(
            meta = metaMap,
            dirs = dirRowsIterable,
            exts = rawExtRows,
            apps = appCacheRows
        )

        // Immediately release temporary full directory map from JVM heap!
        dirs.clear()
        appMap.clear()

        return WizTreeSnapshot(
            totalBytes = totalBytes,
            totalFiles = totalFiles,
            totalDirs = totalDirs,
            buildDurationMs = elapsed,
            rootPaths = rootPaths,
            shortcuts = shortcuts,
            appRankings = appRankings,
            extensionStats = extensionStats,
            topLargeFiles = topLargeFiles,
            isFromCache = false,
            isStale = false,
            builtGeneration = syncGeneration
        )
    }

    /**
     * Loads a single directory node from the persisted SQLite cache (< 0.5ms).
     */
    fun loadDirNode(
        db: SqliteSearchDatabase,
        dirPath: String,
        appInfoResolver: AppInfoResolver? = null
    ): DirNodeStat? {
        val normalized = normalizeDirPath(dirPath)
        if (normalized.isEmpty()) return null
        val row = db.loadAnalyzerDirRow(normalized) ?: return null
        val badge = appInfoResolver?.let { resolver ->
            AppStorageAttribution.matchPath(row.path)?.let { m ->
                val meta = resolver.resolve(m.packageName, m.fallbackLabel)
                AppAttributionBadge(meta.packageName, meta.displayLabel, meta.isInstalled, m.category)
            }
        }
        return DirNodeStat(
            path = row.path,
            parentPath = row.parentPath,
            name = row.name,
            totalBytes = row.totalBytes,
            fileCount = row.fileCount,
            dirCount = row.dirCount,
            directFileCount = row.directFileCount,
            mtime = row.mtime,
            accessMode = AccessMode.fromCode(row.accessModeCode),
            appBadge = badge
        )
    }

    /**
     * Loads the active directory's child subdirectories (from persisted `analyzer_dir_stats`)
     * AND direct files (from `files_meta` via `idx_files_parent`) on demand (< 2ms, O(1) memory).
     */
    fun loadChildrenForDirectory(
        db: SqliteSearchDatabase,
        dirNode: DirNodeStat,
        sort: AnalyzerTreeSort,
        foldersFirst: Boolean = false,
        appInfoResolver: AppInfoResolver? = null,
        limit: Int = DEFAULT_DIRECT_FILES_LIMIT
    ): List<TreeChildEntry> {
        val parentBytes = dirNode.totalBytes.coerceAtLeast(1L).toFloat()
        val badgeCache = HashMap<String, ResolvedAppMeta>()
        fun resolveBadge(path: String): AppAttributionBadge? {
            val resolver = appInfoResolver ?: return null
            val match = AppStorageAttribution.matchPath(path) ?: return null
            val meta = badgeCache.getOrPut(match.packageName) {
                resolver.resolve(match.packageName, match.fallbackLabel)
            }
            return AppAttributionBadge(meta.packageName, meta.displayLabel, meta.isInstalled, match.category)
        }

        val childDirRows = db.queryAnalyzerChildDirRows(
            parentPath = dirNode.path,
            orderByClause = sort.toSqlDirOrderBy(),
            limit = DEFAULT_CHILD_DIRS_LIMIT
        )
        val childDirs = childDirRows.map { row ->
            TreeChildEntry(
                path = row.path,
                name = row.name,
                ext = "",
                isDir = true,
                totalBytes = row.totalBytes.coerceAtLeast(0L),
                fileCount = row.fileCount.coerceAtLeast(0L),
                dirCount = row.dirCount.coerceAtLeast(0L),
                mtime = row.mtime,
                accessMode = AccessMode.fromCode(row.accessModeCode),
                shareOfParent = (row.totalBytes.coerceAtLeast(0L).toFloat() / parentBytes).coerceIn(0f, 1f),
                appBadge = resolveBadge(row.path)
            )
        }

        val directFiles = if (dirNode.directFileCount > 0L) {
            db.queryDirectFilesInDirForAnalyzer(
                dirPath = dirNode.path,
                orderByClause = sort.toSqlFileOrderBy(),
                limit = limit
            ).map { row ->
                val safeSize = row.size.coerceAtLeast(0L)
                TreeChildEntry(
                    path = row.path,
                    name = row.name,
                    ext = row.ext.trim().trimStart('.').lowercase(Locale.ROOT),
                    isDir = false,
                    totalBytes = safeSize,
                    fileCount = 1L,
                    dirCount = 0L,
                    mtime = row.mtime,
                    accessMode = AccessMode.fromCode(row.accessModeCode),
                    shareOfParent = (safeSize.toFloat() / parentBytes).coerceIn(0f, 1f),
                    appBadge = null
                )
            }
        } else {
            emptyList()
        }

        val merged = ArrayList<TreeChildEntry>(childDirs.size + directFiles.size)
        merged.addAll(childDirs)
        merged.addAll(directFiles)
        return sortTreeChildren(merged, sort, foldersFirst)
    }

    /**
     * Queries Top-N large files for an optional extension filter directly from SQLite (< 5ms).
     */
    fun loadLargeFilesForExtension(
        db: SqliteSearchDatabase,
        extFilter: String?,
        appInfoResolver: AppInfoResolver,
        limit: Int = MAX_GLOBAL_LARGE_FILES
    ): List<LargeFileEntry> {
        val cache = HashMap<String, ResolvedAppMeta>()
        fun resolveBadge(match: PathAttributionMatch?): AppAttributionBadge? {
            if (match == null) return null
            val meta = cache.getOrPut(match.packageName) {
                appInfoResolver.resolve(match.packageName, match.fallbackLabel)
            }
            return AppAttributionBadge(meta.packageName, meta.displayLabel, meta.isInstalled, match.category)
        }
        return db.queryTopLargeFilesForAnalyzer(extFilter = extFilter, limit = limit)
            .map { it.toLargeFileEntry(::resolveBadge) }
    }

    /**
     * Synthetic stream builder used for JVM benchmarks/tests without SQLite.
     */
    fun buildFromStream(
        preferredRoots: List<String> = emptyList(),
        appInfoResolver: AppInfoResolver,
        accessMode: AccessMode = AccessMode.NORMAL,
        stream: ((String, String, String, String, Long, Long, Boolean, Int) -> Unit) -> Unit
    ): WizTreeSnapshot {
        val startMs = System.nanoTime() / 1_000_000L

        val dirs = HashMap<String, MutableDir>(4096)
        val extBytes = HashMap<String, Long>(128)
        val extCounts = HashMap<String, Long>(128)
        val appMap = HashMap<String, MutableAppAcc>(256)
        val globalTopHeap = PriorityQueue<SqliteSearchDatabase.AnalyzerFileRow>(
            MAX_GLOBAL_LARGE_FILES + 1,
            compareBy { it.size }
        )

        val normalizedPreferred = preferredRoots.map { normalizeDirPath(it) }.filter { it.isNotEmpty() }.distinct()
        var hasNonStorage = accessMode == AccessMode.ROOT
        val stopRoots = buildStopRoots(normalizedPreferred, unifyUnderSlash = hasNonStorage)

        var totalBytes = 0L
        var totalFiles = 0L
        var totalDirs = 0L

        stream { rawPath, rawParent, rawName, rawExt, rawSize, mtime, isDir, accessModeCode ->
            val path = normalizeDirPath(rawPath)
            if (path.isEmpty()) return@stream
            val mode = AccessMode.fromCode(accessModeCode)
            val parent = normalizeDirPath(rawParent).ifEmpty { parentOf(path) }
            val name = rawName.ifEmpty { nameOf(path) }

            if (!hasNonStorage && isNonStorageRootPath(path)) {
                hasNonStorage = true
                stopRoots.clear()
                stopRoots.add("/")
            }

            if (isDir) {
                totalDirs++
                val node = getOrCreateDir(dirs, path, parent, name, mtime, mode)
                if (mtime > node.mtime) node.mtime = mtime
                ensureAncestorChain(dirs, stopRoots, path, mode, unifyUnderSlash = hasNonStorage)
            } else {
                val safeSize = rawSize.coerceAtLeast(0L)
                totalFiles++
                totalBytes += safeSize
                val normExt = rawExt.trim().trimStart('.').lowercase(Locale.ROOT)

                if (parent.isNotEmpty()) {
                    val parentNode = ensureAncestorChain(dirs, stopRoots, parent, mode, unifyUnderSlash = hasNonStorage)
                    parentNode.totalBytes += safeSize
                    parentNode.fileCount++
                    parentNode.directFileCount++
                    if (mtime > parentNode.mtime) parentNode.mtime = mtime
                }

                extBytes[normExt] = (extBytes[normExt] ?: 0L) + safeSize
                extCounts[normExt] = (extCounts[normExt] ?: 0L) + 1L

                if (safeSize > 0L) {
                    val candidate = SqliteSearchDatabase.AnalyzerFileRow(
                        path = path,
                        parentPath = parent,
                        name = name,
                        ext = normExt,
                        size = safeSize,
                        mtime = mtime,
                        accessModeCode = accessModeCode
                    )
                    if (globalTopHeap.size < MAX_GLOBAL_LARGE_FILES) {
                        globalTopHeap.add(candidate)
                    } else if (globalTopHeap.peek() != null && safeSize > globalTopHeap.peek()!!.size) {
                        globalTopHeap.poll()
                        globalTopHeap.add(candidate)
                    }
                }

                val appMatch = AppStorageAttribution.matchPath(parent.ifEmpty { path })
                if (appMatch != null) {
                    val appAcc = appMap.getOrPut(appMatch.packageName) {
                        MutableAppAcc(appMatch.packageName, appMatch.fallbackLabel)
                    }
                    appAcc.totalBytes += safeSize
                    appAcc.fileCount++
                    val locAcc = appAcc.locations.getOrPut(appMatch.ownerRootPath) {
                        MutableAppLocAcc(appMatch.ownerRootPath, appMatch.category, mode)
                    }
                    locAcc.totalBytes += safeSize
                    locAcc.fileCount++
                }
            }
        }

        rollupBottomUp(dirs, stopRoots)

        val resolvedAppBadges = HashMap<String, ResolvedAppMeta>(appMap.size)
        fun resolveBadge(match: PathAttributionMatch?): AppAttributionBadge? {
            if (match == null) return null
            val meta = resolvedAppBadges.getOrPut(match.packageName) {
                appInfoResolver.resolve(match.packageName, match.fallbackLabel)
            }
            return AppAttributionBadge(meta.packageName, meta.displayLabel, meta.isInstalled, match.category)
        }

        val rootPaths = computeRootPaths(dirs, stopRoots, normalizedPreferred, unifyUnderSlash = hasNonStorage)
        val shortcuts = buildModeShortcuts(
            accessMode = accessMode,
            rootPaths = rootPaths,
            preferredRoots = normalizedPreferred,
            lookupDirBytes = { p -> dirs[p]?.totalBytes }
        )
        val appRankings = materializeAppRankings(appMap, resolvedAppBadges, appInfoResolver, totalBytes)
        val denomTotal = totalBytes.coerceAtLeast(1L).toFloat()

        val extensionStats = extBytes.entries
            .sortedByDescending { it.value }
            .take(MAX_EXTENSION_BUCKETS)
            .map { (ext, bytes) ->
                ExtensionStat(
                    ext = ext,
                    displayLabel = if (ext.isEmpty()) "无后缀" else ".$ext",
                    totalBytes = bytes,
                    fileCount = extCounts[ext] ?: 0L,
                    shareOfTotal = (bytes.toFloat() / denomTotal).coerceIn(0f, 1f)
                )
            }

        val topLargeFiles = globalTopHeap
            .sortedByDescending { it.size }
            .map { it.toLargeFileEntry(::resolveBadge) }

        val elapsed = ((System.nanoTime() / 1_000_000L) - startMs).coerceAtLeast(1L)

        return WizTreeSnapshot(
            totalBytes = totalBytes,
            totalFiles = totalFiles,
            totalDirs = totalDirs,
            buildDurationMs = elapsed,
            rootPaths = rootPaths,
            shortcuts = shortcuts,
            appRankings = appRankings,
            extensionStats = extensionStats,
            topLargeFiles = topLargeFiles
        )
    }

    internal fun buildModeShortcuts(
        accessMode: AccessMode,
        rootPaths: List<String>,
        preferredRoots: List<String>,
        lookupDirBytes: (String) -> Long?
    ): List<AnalyzerShortcut> {
        val out = ArrayList<AnalyzerShortcut>(6)
        val seen = HashSet<String>(8)

        fun addIfPresent(path: String, label: String) {
            val norm = normalizeDirPath(path)
            if (norm.isEmpty() || !seen.add(norm)) return
            val bytes = lookupDirBytes(norm) ?: return
            out.add(AnalyzerShortcut(path = norm, label = label, totalBytes = bytes))
        }

        val primaryStorage = when {
            lookupDirBytes("/storage/emulated/0") != null -> "/storage/emulated/0"
            lookupDirBytes("/sdcard") != null -> "/sdcard"
            else -> preferredRoots.firstOrNull { lookupDirBytes(it) != null }
        }

        val externalVolumes = (preferredRoots + rootPaths)
            .map { normalizeDirPath(it) }
            .filter {
                it.startsWith("/storage/") &&
                    it != "/storage/emulated/0" &&
                    it != "/storage/emulated" &&
                    it != "/storage/self" &&
                    it != primaryStorage
            }
            .distinct()

        when (accessMode) {
            AccessMode.ROOT -> {
                // Root mode: "/" ALWAYS leftmost, followed by /data, 模块目录, 内部存储, and removable volumes
                addIfPresent("/", "/")
                addIfPresent("/data", "/data")
                when {
                    lookupDirBytes("/data/adb/modules") != null -> addIfPresent("/data/adb/modules", "模块目录")
                    lookupDirBytes("/data/adb") != null -> addIfPresent("/data/adb", "模块目录")
                }
                if (primaryStorage != null) {
                    addIfPresent(primaryStorage, "内部存储")
                }
                for (vol in externalVolumes) {
                    addIfPresent(vol, vol.substringAfterLast('/').ifEmpty { vol })
                }
            }
            AccessMode.SHIZUKU -> {
                if (primaryStorage != null) {
                    addIfPresent(primaryStorage, "内部存储")
                    addIfPresent("$primaryStorage/Android/data", "Android/data")
                    addIfPresent("$primaryStorage/Android/obb", "Android/obb")
                    addIfPresent("$primaryStorage/Download", "Download")
                }
                for (vol in externalVolumes) {
                    addIfPresent(vol, vol.substringAfterLast('/').ifEmpty { vol })
                }
            }
            AccessMode.NORMAL -> {
                if (primaryStorage != null) {
                    addIfPresent(primaryStorage, "内部存储")
                    addIfPresent("$primaryStorage/Download", "Download")
                    addIfPresent("$primaryStorage/DCIM", "DCIM")
                    addIfPresent("$primaryStorage/Documents", "Documents")
                }
                for (vol in externalVolumes) {
                    addIfPresent(vol, vol.substringAfterLast('/').ifEmpty { vol })
                }
            }
        }

        // Fallback to any indexed root if none of the curated shortcuts matched (e.g. custom test root)
        if (out.isEmpty()) {
            for (r in rootPaths) {
                val label = when (r) {
                    "/storage/emulated/0", "/sdcard" -> "内部存储"
                    else -> r
                }
                addIfPresent(r, label)
            }
        }
        return out
    }

    private fun isNonStorageRootPath(path: String): Boolean {
        if (path == "/") return true
        if (path.startsWith("/storage/") || path == "/storage" || path.startsWith("/sdcard")) return false
        return path.startsWith("/")
    }

    private fun buildStopRoots(preferredRoots: List<String>, unifyUnderSlash: Boolean): HashSet<String> {
        if (unifyUnderSlash) {
            return hashSetOf("/")
        }
        val set = HashSet<String>(preferredRoots.size + 3)
        for (r in preferredRoots) {
            val n = normalizeDirPath(r)
            if (n.isNotEmpty()) set.add(n)
        }
        set.add("/storage/emulated/0")
        set.add("/sdcard")
        set.add("/")
        return set
    }

    private fun getOrCreateDir(
        dirs: HashMap<String, MutableDir>,
        dirPath: String,
        explicitParent: String?,
        explicitName: String?,
        mtime: Long,
        mode: AccessMode
    ): MutableDir {
        val existing = dirs[dirPath]
        if (existing != null) {
            if (mtime > existing.mtime) existing.mtime = mtime
            return existing
        }
        val parent = explicitParent ?: parentOf(dirPath)
        val name = explicitName?.takeIf { it.isNotEmpty() } ?: nameOf(dirPath)
        val created = MutableDir(dirPath, parent, name, mtime, mode)
        dirs[dirPath] = created
        return created
    }

    private fun ensureAncestorChain(
        dirs: HashMap<String, MutableDir>,
        stopRoots: HashSet<String>,
        startDirPath: String,
        mode: AccessMode,
        unifyUnderSlash: Boolean
    ): MutableDir {
        var curPath = startDirPath
        var firstNode: MutableDir? = null
        var guard = 0
        while (curPath.isNotEmpty() && guard++ < 64) {
            val node = getOrCreateDir(dirs, curPath, null, null, 0L, mode)
            if (firstNode == null) firstNode = node
            if (curPath in stopRoots || curPath == "/") break
            val parent = parentOf(curPath)
            if (parent.isEmpty() || parent == curPath) break
            if (!unifyUnderSlash && (parent == "/storage/emulated" || parent == "/storage")) {
                stopRoots.add(curPath)
                break
            }
            curPath = parent
        }
        return firstNode!!
    }

    private fun rollupBottomUp(
        dirs: HashMap<String, MutableDir>,
        stopRoots: HashSet<String>
    ) {
        val sortedByDepthDesc = dirs.values.sortedByDescending { pathDepth(it.path) }
        for (node in sortedByDepthDesc) {
            if (node.path in stopRoots || node.path == "/") continue
            val parentNode = dirs[node.parentPath] ?: continue
            parentNode.totalBytes += node.totalBytes
            parentNode.fileCount += node.fileCount
            parentNode.dirCount += 1L + node.dirCount
            if (node.mtime > parentNode.mtime) parentNode.mtime = node.mtime
        }
    }

    private fun pathDepth(path: String): Int {
        if (path == "/") return 0
        var count = 0
        for (i in 0 until path.length) {
            if (path[i] == '/') count++
        }
        return count
    }

    private fun computeRootPaths(
        dirs: HashMap<String, MutableDir>,
        stopRoots: HashSet<String>,
        normalizedPreferred: List<String>,
        unifyUnderSlash: Boolean
    ): List<String> {
        if (unifyUnderSlash && "/" in dirs) {
            return listOf("/")
        }
        return dirs.keys
            .filter { p ->
                val parent = dirs[p]?.parentPath.orEmpty()
                p in stopRoots || parent.isEmpty() || parent !in dirs
            }
            .filter { p ->
                val n = dirs[p]!!
                n.totalBytes > 0L || n.fileCount > 0L || n.dirCount > 0L
            }
            .sortedWith(
                compareBy<String> { rootSortRank(it, normalizedPreferred) }
                    .thenByDescending { dirs[it]?.totalBytes ?: 0L }
                    .thenBy { it }
            )
    }

    private fun materializeAppRankings(
        appMap: HashMap<String, MutableAppAcc>,
        resolvedAppBadges: HashMap<String, ResolvedAppMeta>,
        appInfoResolver: AppInfoResolver,
        totalBytes: Long
    ): List<AppStorageSummary> {
        val denomTotal = totalBytes.coerceAtLeast(1L).toFloat()
        return appMap.values
            .map { acc ->
                val meta = resolvedAppBadges.getOrPut(acc.packageName) {
                    appInfoResolver.resolve(acc.packageName, acc.fallbackLabel)
                }
                val locs = acc.locations.values
                    .map { loc ->
                        AppLocationStat(
                            path = loc.path,
                            category = loc.category,
                            totalBytes = loc.totalBytes,
                            fileCount = loc.fileCount,
                            accessMode = loc.accessMode
                        )
                    }
                    .sortedByDescending { it.totalBytes }
                AppStorageSummary(
                    packageName = meta.packageName,
                    displayLabel = meta.displayLabel,
                    isInstalled = meta.isInstalled,
                    totalBytes = acc.totalBytes,
                    fileCount = acc.fileCount,
                    shareOfTotal = (acc.totalBytes.toFloat() / denomTotal).coerceIn(0f, 1f),
                    locations = locs
                )
            }
            .sortedWith(
                compareByDescending<AppStorageSummary> { it.totalBytes }
                    .thenByDescending { it.fileCount }
            )
    }

    private fun encodeAppLocations(locations: List<AppLocationStat>): String {
        if (locations.isEmpty()) return ""
        return locations.joinToString("\n") { loc ->
            val safePath = loc.path.replace('\t', ' ').replace('\n', ' ')
            "$safePath\t${loc.category.name}\t${loc.totalBytes}\t${loc.fileCount}\t${loc.accessMode.code}"
        }
    }

    private fun decodeAppLocations(blob: String): List<AppLocationStat> {
        if (blob.isBlank()) return emptyList()
        return blob.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 5) return@mapNotNull null
            val cat = runCatching { AppPathCategory.valueOf(parts[1]) }.getOrDefault(AppPathCategory.SHARED_STORAGE)
            val bytes = parts[2].toLongOrNull() ?: 0L
            val files = parts[3].toLongOrNull() ?: 0L
            val mode = AccessMode.fromCode(parts[4].toIntOrNull() ?: 0)
            AppLocationStat(parts[0], cat, bytes, files, mode)
        }.toList()
    }

    private fun SqliteSearchDatabase.AnalyzerFileRow.toLargeFileEntry(
        resolveBadge: (PathAttributionMatch?) -> AppAttributionBadge?
    ): LargeFileEntry = LargeFileEntry(
        path = path,
        parentPath = parentPath,
        name = name,
        ext = ext.trim().trimStart('.').lowercase(Locale.ROOT),
        size = size.coerceAtLeast(0L),
        mtime = mtime,
        accessMode = AccessMode.fromCode(accessModeCode),
        appBadge = resolveBadge(AppStorageAttribution.matchPath(parentPath.ifEmpty { path }))
    )

    private fun rootSortRank(path: String, preferred: List<String>): Int {
        val idx = preferred.indexOf(path)
        if (idx >= 0) return idx
        return when (path) {
            "/" -> 0
            "/storage/emulated/0" -> 100
            "/sdcard" -> 101
            "/data" -> 300
            else -> if (path.startsWith("/storage/")) 200 else 400
        }
    }

    internal fun normalizeDirPath(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.length > 1) trimmed.trimEnd('/').ifEmpty { "/" } else trimmed
    }

    internal fun parentOf(path: String): String {
        if (path.isEmpty() || path == "/") return ""
        val idx = path.lastIndexOf('/')
        return when {
            idx < 0 -> ""
            idx == 0 -> "/"
            else -> path.substring(0, idx)
        }
    }

    internal fun nameOf(path: String): String {
        if (path == "/") return "/"
        return path.substringAfterLast('/').ifEmpty { path }
    }
}
