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
    val mtime: Long,
    val accessMode: AccessMode,
    val appBadge: AppAttributionBadge? = null,
    val childrenBySize: List<TreeChildEntry>
) {
    fun childrenSorted(sort: AnalyzerTreeSort): List<TreeChildEntry> = when (sort) {
        AnalyzerTreeSort.SIZE_DESC -> childrenBySize
        AnalyzerTreeSort.COUNT_DESC -> childrenBySize.sortedWith(
            compareByDescending<TreeChildEntry> { it.fileCount + it.dirCount }
                .thenByDescending { it.totalBytes }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        )
    }
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
    val topFilesByExt: Map<String, List<LargeFileEntry>>
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
 * Produces clean, aspect-ratio-optimized non-overlapping tiles bounded strictly inside [0..width] x [0..height].
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
 * Single-pass in-memory WizTree aggregation engine operating directly over existing files_meta.
 * Zero extra SQLite tables, zero duplicate disk scans.
 */
object WizTreeEngine {

    private const val MAX_GLOBAL_LARGE_FILES = 200
    private const val MAX_PER_EXT_LARGE_FILES = 40
    private const val MAX_EXTENSION_BUCKETS = 36

    private class MutableDir(
        val path: String,
        var parentPath: String,
        var name: String,
        var mtime: Long = 0L,
        var accessMode: AccessMode = AccessMode.NORMAL
    ) {
        var totalBytes: Long = 0L
        var fileCount: Long = 0L
        var dirCount: Long = 0L
        val childDirPaths = LinkedHashSet<String>()
        val directFiles = ArrayList<RawFile>()
    }

    private data class RawFile(
        val path: String,
        val parentPath: String,
        val name: String,
        val ext: String,
        val size: Long,
        val mtime: Long,
        val accessMode: AccessMode
    )

    private class MutableExtAcc(val ext: String) {
        var totalBytes: Long = 0L
        var fileCount: Long = 0L
        val topFiles = PriorityQueue<RawFile>(MAX_PER_EXT_LARGE_FILES + 1, compareBy { it.size })
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
        val locations = HashMap<String, MutableAppLocAcc>()
    }

    fun buildFromDatabase(
        db: SqliteSearchDatabase,
        appInfoResolver: AppInfoResolver,
        preferredRoots: List<String> = emptyList()
    ): WizTreeSnapshot = buildFromStream(
        preferredRoots = preferredRoots,
        appInfoResolver = appInfoResolver
    ) { visitor ->
        db.streamAllEntriesForAnalyzer(visitor)
    }

    fun buildFromStream(
        preferredRoots: List<String> = emptyList(),
        appInfoResolver: AppInfoResolver,
        stream: ((String, String, String, String, Long, Long, Boolean, Int) -> Unit) -> Unit
    ): WizTreeSnapshot {
        val startMs = System.nanoTime() / 1_000_000L

        val dirs = HashMap<String, MutableDir>(4096)
        val extMap = HashMap<String, MutableExtAcc>(128)
        val appMap = HashMap<String, MutableAppAcc>(256)
        val globalTopHeap = PriorityQueue<RawFile>(MAX_GLOBAL_LARGE_FILES + 1, compareBy { it.size })

        var totalBytes = 0L
        var totalFiles = 0L
        var totalDirs = 0L

        fun getOrCreateDir(dirPath: String, explicitParent: String?, explicitName: String?, mtime: Long, mode: AccessMode): MutableDir {
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

        // Known logical stop-roots so we don't synthesize useless /storage or /storage/emulated wrappers
        // when all indexed entries live under /storage/emulated/0.
        val normalizedPreferred = preferredRoots
            .map { normalizeDirPath(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        val stopRoots = HashSet<String>(normalizedPreferred)
        stopRoots.add("/storage/emulated/0")
        stopRoots.add("/sdcard")
        stopRoots.add("/data")
        stopRoots.add("/")

        fun ensureAncestorChain(startDirPath: String, mode: AccessMode): MutableDir {
            var curPath = startDirPath
            var childDir: MutableDir? = null
            var firstNode: MutableDir? = null
            while (curPath.isNotEmpty()) {
                val node = getOrCreateDir(curPath, null, null, 0L, mode)
                if (firstNode == null) firstNode = node
                if (childDir != null) {
                    node.childDirPaths.add(childDir.path)
                }
                if (curPath in stopRoots || curPath == "/") break
                val parent = parentOf(curPath)
                if (parent.isEmpty() || parent == curPath) break
                // Stop if parent is a bare mount container (/storage or /storage/emulated) while curPath is a volume root.
                if (parent == "/storage/emulated" || parent == "/storage") {
                    stopRoots.add(curPath)
                    break
                }
                childDir = node
                curPath = parent
            }
            return firstNode!!
        }

        stream { rawPath, rawParent, rawName, rawExt, rawSize, mtime, isDir, accessModeCode ->
            val path = normalizeDirPath(rawPath)
            if (path.isEmpty()) return@stream
            val mode = AccessMode.fromCode(accessModeCode)
            val parent = normalizeDirPath(rawParent).ifEmpty { parentOf(path) }
            val name = rawName.ifEmpty { nameOf(path) }

            if (isDir) {
                totalDirs++
                val node = getOrCreateDir(path, parent, name, mtime, mode)
                node.mtime = max(node.mtime, mtime)
                ensureAncestorChain(path, mode)
                // Propagate directory count (+1) up to ancestors (excluding self).
                var anc = node.parentPath
                var prev = path
                while (anc.isNotEmpty() && prev !in stopRoots) {
                    val ancNode = dirs[anc] ?: break
                    ancNode.dirCount++
                    if (anc in stopRoots || anc == "/") break
                    prev = anc
                    anc = ancNode.parentPath
                }
            } else {
                val safeSize = rawSize.coerceAtLeast(0L)
                totalFiles++
                totalBytes += safeSize

                val normExt = rawExt.trim().trimStart('.').lowercase(Locale.ROOT)
                val file = RawFile(path, parent, name, normExt, safeSize, mtime, mode)

                // 1. Directory tree rollup
                if (parent.isNotEmpty()) {
                    val parentNode = ensureAncestorChain(parent, mode)
                    parentNode.directFiles.add(file)
                    var cur: MutableDir? = parentNode
                    while (cur != null) {
                        cur.totalBytes += safeSize
                        cur.fileCount++
                        if (cur.path in stopRoots || cur.path == "/") break
                        cur = dirs[cur.parentPath]
                    }
                }

                // 2. Extension breakdown + per-ext top files
                val extAcc = extMap.getOrPut(normExt) { MutableExtAcc(normExt) }
                extAcc.totalBytes += safeSize
                extAcc.fileCount++
                if (safeSize > 0L) {
                    offerBounded(extAcc.topFiles, file, MAX_PER_EXT_LARGE_FILES)
                    offerBounded(globalTopHeap, file, MAX_GLOBAL_LARGE_FILES)
                }

                // 3. App attribution rollup
                val appMatch = AppStorageAttribution.matchPath(path)
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

        // Resolve package metadata once per unique package name.
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

        // Identify actual top-level roots present in dirs.
        val rootPaths = dirs.keys
            .filter { p ->
                val parent = dirs[p]?.parentPath.orEmpty()
                p in stopRoots || parent.isEmpty() || parent !in dirs
            }
            .filter { p ->
                val n = dirs[p]!!
                n.totalBytes > 0L || n.fileCount > 0L || n.childDirPaths.isNotEmpty() || n.directFiles.isNotEmpty()
            }
            .sortedWith(
                compareBy<String> { rootSortRank(it, normalizedPreferred) }
                    .thenByDescending { dirs[it]?.totalBytes ?: 0L }
                    .thenBy { it }
            )

        // Materialize immutable DirNodeStats.
        val immutableDirs = HashMap<String, DirNodeStat>(dirs.size)
        for ((dirPath, mDir) in dirs) {
            val parentBytes = mDir.totalBytes.coerceAtLeast(1L)
            val children = ArrayList<TreeChildEntry>(mDir.childDirPaths.size + mDir.directFiles.size)

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
                        shareOfParent = (c.totalBytes.toFloat() / parentBytes.toFloat()).coerceIn(0f, 1f),
                        appBadge = badge
                    )
                )
            }
            for (f in mDir.directFiles) {
                children.add(
                    TreeChildEntry(
                        path = f.path,
                        name = f.name,
                        ext = f.ext,
                        isDir = false,
                        totalBytes = f.size,
                        fileCount = 1L,
                        dirCount = 0L,
                        mtime = f.mtime,
                        accessMode = f.accessMode,
                        shareOfParent = (f.size.toFloat() / parentBytes.toFloat()).coerceIn(0f, 1f),
                        appBadge = null
                    )
                )
            }

            children.sortWith(
                compareByDescending<TreeChildEntry> { it.totalBytes }
                    .thenByDescending { it.isDir }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )

            immutableDirs[dirPath] = DirNodeStat(
                path = mDir.path,
                parentPath = mDir.parentPath,
                name = mDir.name,
                totalBytes = mDir.totalBytes,
                fileCount = mDir.fileCount,
                dirCount = mDir.dirCount,
                mtime = mDir.mtime,
                accessMode = mDir.accessMode,
                appBadge = resolveBadge(AppStorageAttribution.matchPath(mDir.path)),
                childrenBySize = children
            )
        }

        // Materialize App rankings.
        val denomTotal = totalBytes.coerceAtLeast(1L).toFloat()
        val appRankings = appMap.values
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

        // Materialize Extension stats + per-ext top files.
        val sortedExts = extMap.values.sortedByDescending { it.totalBytes }
        val extensionStats = sortedExts
            .take(MAX_EXTENSION_BUCKETS)
            .map { acc ->
                ExtensionStat(
                    ext = acc.ext,
                    displayLabel = if (acc.ext.isEmpty()) "无后缀" else ".${acc.ext}",
                    totalBytes = acc.totalBytes,
                    fileCount = acc.fileCount,
                    shareOfTotal = (acc.totalBytes.toFloat() / denomTotal).coerceIn(0f, 1f)
                )
            }

        val topFilesByExt = HashMap<String, List<LargeFileEntry>>(extensionStats.size)
        for (stat in extensionStats) {
            val acc = extMap[stat.ext] ?: continue
            topFilesByExt[stat.ext] = acc.topFiles
                .sortedByDescending { it.size }
                .map { it.toLargeFileEntry(::resolveBadge) }
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
            topFilesByExt = topFilesByExt
        )
    }

    private fun RawFile.toLargeFileEntry(
        resolveBadge: (PathAttributionMatch?) -> AppAttributionBadge?
    ): LargeFileEntry = LargeFileEntry(
        path = path,
        parentPath = parentPath,
        name = name,
        ext = ext,
        size = size,
        mtime = mtime,
        accessMode = accessMode,
        appBadge = resolveBadge(AppStorageAttribution.matchPath(path))
    )

    private fun offerBounded(heap: PriorityQueue<RawFile>, candidate: RawFile, cap: Int) {
        if (heap.size < cap) {
            heap.add(candidate)
        } else if (heap.peek() != null && candidate.size > heap.peek()!!.size) {
            heap.poll()
            heap.add(candidate)
        }
    }

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
