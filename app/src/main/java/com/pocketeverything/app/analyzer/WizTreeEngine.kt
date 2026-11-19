package com.pocketeverything.app.analyzer

import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.search.SqliteSearchDatabase
import java.util.Locale
import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.min

enum class AnalyzerTreeSort {
    SIZE_DESC,
    COUNT_DESC
}

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
    val childrenBySize: List<TreeChildEntry>
) {
    fun childrenSorted(sort: AnalyzerTreeSort): List<TreeChildEntry> = sortTreeChildren(childrenBySize, sort)
}

fun sortTreeChildren(children: List<TreeChildEntry>, sort: AnalyzerTreeSort): List<TreeChildEntry> = when (sort) {
    AnalyzerTreeSort.SIZE_DESC -> children.sortedWith(
        compareByDescending<TreeChildEntry> { it.totalBytes }
            .thenByDescending { it.isDir }
            .thenBy { it.name.lowercase(Locale.ROOT) }
    )
    AnalyzerTreeSort.COUNT_DESC -> children.sortedWith(
        compareByDescending<TreeChildEntry> { it.fileCount + it.dirCount }
            .thenByDescending { it.totalBytes }
            .thenBy { it.name.lowercase(Locale.ROOT) }
    )
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

data class WizTreeSnapshot(
    val totalBytes: Long,
    val totalFiles: Long,
    val totalDirs: Long,
    val buildDurationMs: Long,
    val rootPaths: List<String>,
    val dirNodes: Map<String, DirNodeStat>,
    val appRankings: List<AppStorageSummary>,
    val extensionStats: List<ExtensionStat>,
    val topLargeFiles: List<LargeFileEntry>,
    val topFilesByExt: Map<String, List<LargeFileEntry>> = emptyMap()
) {
    val defaultRootPath: String? get() = rootPaths.firstOrNull()
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
 * Memory-bounded WizTree aggregation engine.
 * Pushes per-directory SUM/COUNT, extension breakdown, and Top-N large-file queries into SQLite C++
 * so even 1M+ indexed files consume < 10 MB of Android JVM heap.
 * Direct files inside the currently viewed directory are fetched on-demand via idx_files_parent (< 1ms).
 */
object WizTreeEngine {

    private const val MAX_GLOBAL_LARGE_FILES = 200
    private const val MAX_EXTENSION_BUCKETS = 36
    const val DEFAULT_DIRECT_FILES_LIMIT = 500

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
        val childDirPaths = ArrayList<String>(4)
        private var childDirSet: HashSet<String>? = null

        fun addChildDir(childPath: String) {
            val set = childDirSet
            if (set != null) {
                if (set.add(childPath)) childDirPaths.add(childPath)
                return
            }
            if (childDirPaths.size < 8) {
                if (childPath !in childDirPaths) childDirPaths.add(childPath)
            } else {
                val created = HashSet<String>(childDirPaths.size * 2).apply { addAll(childDirPaths) }
                childDirSet = created
                if (created.add(childPath)) childDirPaths.add(childPath)
            }
        }
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
     * Primary production entry point: executes off-heap SQLite GROUP BY rollups so JVM only holds
     * directory summary nodes (~10k-40k) + top 36 extensions + top 200 large files.
     */
    fun buildFromDatabase(
        db: SqliteSearchDatabase,
        appInfoResolver: AppInfoResolver,
        preferredRoots: List<String> = emptyList()
    ): WizTreeSnapshot {
        val startMs = System.nanoTime() / 1_000_000L

        val dirs = HashMap<String, MutableDir>(4096)
        val appMap = HashMap<String, MutableAppAcc>(256)
        val stopRoots = buildStopRoots(preferredRoots)
        val normalizedPreferred = preferredRoots.map { normalizeDirPath(it) }.filter { it.isNotEmpty() }.distinct()

        var totalBytes = 0L
        var totalFiles = 0L
        var totalDirs = 0L

        // 1. Load directory metadata rows (is_dir = 1) so empty folders & mtimes are preserved.
        db.streamDirectoriesForAnalyzer { rawPath, rawParent, rawName, mtime, modeCode ->
            val path = normalizeDirPath(rawPath)
            if (path.isEmpty()) return@streamDirectoriesForAnalyzer
            totalDirs++
            val mode = AccessMode.fromCode(modeCode)
            val parent = normalizeDirPath(rawParent).ifEmpty { parentOf(path) }
            val name = rawName.ifEmpty { nameOf(path) }
            val node = getOrCreateDir(dirs, path, parent, name, mtime, mode)
            if (mtime > node.mtime) node.mtime = mtime
            ensureAncestorChain(dirs, stopRoots, path, mode)
        }

        // 2. Aggregate per-directory file bytes & counts directly from SQLite GROUP BY parent_path.
        db.streamDirectoryRollupsForAnalyzer { rawParent, directBytes, directFileCount, _, maxMtime, modeCode ->
            val parent = normalizeDirPath(rawParent)
            if (parent.isEmpty()) return@streamDirectoryRollupsForAnalyzer
            val safeBytes = directBytes.coerceAtLeast(0L)
            val safeFiles = directFileCount.coerceAtLeast(0L)
            val mode = AccessMode.fromCode(modeCode)

            if (safeFiles > 0L || safeBytes > 0L) {
                totalBytes += safeBytes
                totalFiles += safeFiles

                val node = ensureAncestorChain(dirs, stopRoots, parent, mode)
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

        // 3. Bottom-up rollup by path depth descending (exact recursive sums, zero double counting).
        rollupBottomUp(dirs, stopRoots)

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

        val rootPaths = computeRootPaths(dirs, stopRoots, normalizedPreferred)
        val immutableDirs = materializeDirs(dirs, ::resolveBadge)
        val appRankings = materializeAppRankings(appMap, resolvedAppBadges, appInfoResolver, totalBytes)

        // 4. Extension stats directly from SQLite GROUP BY LOWER(ext).
        val denomTotal = totalBytes.coerceAtLeast(1L).toFloat()
        val extensionStats = db.queryExtensionStatsForAnalyzer(MAX_EXTENSION_BUCKETS).map { row ->
            val normExt = row.ext.trim().trimStart('.').lowercase(Locale.ROOT)
            ExtensionStat(
                ext = normExt,
                displayLabel = if (normExt.isEmpty()) "无后缀" else ".$normExt",
                totalBytes = row.totalBytes.coerceAtLeast(0L),
                fileCount = row.fileCount.coerceAtLeast(0L),
                shareOfTotal = (row.totalBytes.coerceAtLeast(0L).toFloat() / denomTotal).coerceIn(0f, 1f)
            )
        }

        // 5. Global Top 200 large files directly from SQLite ORDER BY size DESC LIMIT 200.
        val topLargeFiles = db.queryTopLargeFilesForAnalyzer(extFilter = null, limit = MAX_GLOBAL_LARGE_FILES)
            .map { it.toLargeFileEntry(::resolveBadge) }

        val elapsed = ((System.nanoTime() / 1_000_000L) - startMs).coerceAtLeast(1L)

        return WizTreeSnapshot(
            totalBytes = totalBytes,
            totalFiles = totalFiles,
            totalDirs = totalDirs,
            buildDurationMs = elapsed,
            rootPaths = rootPaths,
            dirNodes = immutableDirs,
            appRankings = appRankings,
            extensionStats = extensionStats,
            topLargeFiles = topLargeFiles,
            topFilesByExt = emptyMap()
        )
    }

    /**
     * Loads the active directory's direct files from SQLite via idx_files_parent (< 1ms)
     * and merges them with its pre-computed subdirectory nodes.
     */
    fun loadChildrenForDirectory(
        db: SqliteSearchDatabase,
        dirNode: DirNodeStat,
        sort: AnalyzerTreeSort,
        limit: Int = DEFAULT_DIRECT_FILES_LIMIT
    ): List<TreeChildEntry> {
        val parentBytes = dirNode.totalBytes.coerceAtLeast(1L).toFloat()
        val directFiles = if (dirNode.directFileCount > 0L) {
            db.queryDirectFilesInDirForAnalyzer(dirNode.path, limit = limit).map { row ->
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
        val merged = ArrayList<TreeChildEntry>(dirNode.childrenBySize.size + directFiles.size)
        merged.addAll(dirNode.childrenBySize)
        merged.addAll(directFiles)
        return sortTreeChildren(merged, sort)
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
     * Streaming builder (used for synthetic JVM benchmarks/tests without SQLite).
     * Never stores individual file rows in MutableDir so memory stays O(directories + 200).
     */
    fun buildFromStream(
        preferredRoots: List<String> = emptyList(),
        appInfoResolver: AppInfoResolver,
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

        val stopRoots = buildStopRoots(preferredRoots)
        val normalizedPreferred = preferredRoots.map { normalizeDirPath(it) }.filter { it.isNotEmpty() }.distinct()

        var totalBytes = 0L
        var totalFiles = 0L
        var totalDirs = 0L

        stream { rawPath, rawParent, rawName, rawExt, rawSize, mtime, isDir, accessModeCode ->
            val path = normalizeDirPath(rawPath)
            if (path.isEmpty()) return@stream
            val mode = AccessMode.fromCode(accessModeCode)
            val parent = normalizeDirPath(rawParent).ifEmpty { parentOf(path) }
            val name = rawName.ifEmpty { nameOf(path) }

            if (isDir) {
                totalDirs++
                val node = getOrCreateDir(dirs, path, parent, name, mtime, mode)
                if (mtime > node.mtime) node.mtime = mtime
                ensureAncestorChain(dirs, stopRoots, path, mode)
            } else {
                val safeSize = rawSize.coerceAtLeast(0L)
                totalFiles++
                totalBytes += safeSize
                val normExt = rawExt.trim().trimStart('.').lowercase(Locale.ROOT)

                if (parent.isNotEmpty()) {
                    val parentNode = ensureAncestorChain(dirs, stopRoots, parent, mode)
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

        val rootPaths = computeRootPaths(dirs, stopRoots, normalizedPreferred)
        val immutableDirs = materializeDirs(dirs, ::resolveBadge)
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
            dirNodes = immutableDirs,
            appRankings = appRankings,
            extensionStats = extensionStats,
            topLargeFiles = topLargeFiles,
            topFilesByExt = emptyMap()
        )
    }

    private fun buildStopRoots(preferredRoots: List<String>): HashSet<String> {
        val set = HashSet<String>(preferredRoots.size + 4)
        for (r in preferredRoots) {
            val n = normalizeDirPath(r)
            if (n.isNotEmpty()) set.add(n)
        }
        set.add("/storage/emulated/0")
        set.add("/sdcard")
        set.add("/data")
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
        mode: AccessMode
    ): MutableDir {
        var curPath = startDirPath
        var childPath: String? = null
        var firstNode: MutableDir? = null
        while (curPath.isNotEmpty()) {
            val node = getOrCreateDir(dirs, curPath, null, null, 0L, mode)
            if (firstNode == null) firstNode = node
            if (childPath != null) {
                node.addChildDir(childPath)
            }
            if (curPath in stopRoots || curPath == "/") break
            val parent = parentOf(curPath)
            if (parent.isEmpty() || parent == curPath) break
            if (parent == "/storage/emulated" || parent == "/storage") {
                stopRoots.add(curPath)
                break
            }
            childPath = curPath
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
        var count = 0
        for (i in 0 until path.length) {
            if (path[i] == '/') count++
        }
        return count
    }

    private fun computeRootPaths(
        dirs: HashMap<String, MutableDir>,
        stopRoots: HashSet<String>,
        normalizedPreferred: List<String>
    ): List<String> = dirs.keys
        .filter { p ->
            val parent = dirs[p]?.parentPath.orEmpty()
            p in stopRoots || parent.isEmpty() || parent !in dirs
        }
        .filter { p ->
            val n = dirs[p]!!
            n.totalBytes > 0L || n.fileCount > 0L || n.childDirPaths.isNotEmpty()
        }
        .sortedWith(
            compareBy<String> { rootSortRank(it, normalizedPreferred) }
                .thenByDescending { dirs[it]?.totalBytes ?: 0L }
                .thenBy { it }
        )

    private fun materializeDirs(
        dirs: HashMap<String, MutableDir>,
        resolveBadge: (PathAttributionMatch?) -> AppAttributionBadge?
    ): Map<String, DirNodeStat> {
        val immutableDirs = HashMap<String, DirNodeStat>(dirs.size)
        for ((dirPath, mDir) in dirs) {
            val parentBytes = mDir.totalBytes.coerceAtLeast(1L).toFloat()
            val children = ArrayList<TreeChildEntry>(mDir.childDirPaths.size)
            for (childDirPath in mDir.childDirPaths) {
                val c = dirs[childDirPath] ?: continue
                val badge = resolveBadge(AppStorageAttribution.matchPath(c.path))
                children.add(
                    TreeChildEntry(
                        path = c.path,
                        name = c.name,
                        ext = "",
                        isDir = true,
                        totalBytes = c.totalBytes,
                        fileCount = c.fileCount,
                        dirCount = c.dirCount,
                        mtime = c.mtime,
                        accessMode = c.accessMode,
                        shareOfParent = (c.totalBytes.toFloat() / parentBytes).coerceIn(0f, 1f),
                        appBadge = badge
                    )
                )
            }
            children.sortWith(
                compareByDescending<TreeChildEntry> { it.totalBytes }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
            immutableDirs[dirPath] = DirNodeStat(
                path = mDir.path,
                parentPath = mDir.parentPath,
                name = mDir.name,
                totalBytes = mDir.totalBytes,
                fileCount = mDir.fileCount,
                dirCount = mDir.dirCount,
                directFileCount = mDir.directFileCount,
                mtime = mDir.mtime,
                accessMode = mDir.accessMode,
                appBadge = resolveBadge(AppStorageAttribution.matchPath(mDir.path)),
                childrenBySize = children
            )
        }
        return immutableDirs
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
