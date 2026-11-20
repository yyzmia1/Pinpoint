package com.pocketeverything.app.fs

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * High-speed non-following directory tree walker using POSIX lstat + (st_dev, st_ino)
 * bind-mount / loop deduplication. Runs identically in Normal, Shizuku, and Root processes.
 */
object PrivilegedTreeWalker {

    private data class DevInode(val dev: Long, val ino: Long)

    fun walk(
        roots: Array<String>,
        excludedPrefixes: Array<String>,
        excludedDirNames: Array<String>,
        deduplicateMounts: Boolean,
        writer: BinaryTreeStreamCodec.Writer,
        recursive: Boolean = true
    ): Int {
        val excludedPrefixSet = excludedPrefixes.map { normalizePrefix(it) }.toHashSet()
        val excludedDirSet = excludedDirNames.map { it.lowercase() }.toHashSet()
        val visitedInodes = if (deduplicateMounts) HashSet<DevInode>(4096) else null
        var count = 0

        val stack = ArrayDeque<String>()
        val rootSet = HashSet<String>(roots.size)
        for (rawRoot in roots) {
            val root = normalizePrefix(rawRoot)
            if (!isExcludedPrefix(root, excludedPrefixSet)) {
                stack.addLast(root)
                rootSet.add(root)
            }
        }

        while (stack.isNotEmpty()) {
            val currentPath = stack.removeLast()
            val dirStat = runCatching { Os.lstat(currentPath) }.getOrNull() ?: continue
            if (OsConstants.S_ISLNK(dirStat.st_mode)) continue
            if (!OsConstants.S_ISDIR(dirStat.st_mode)) continue

            if (visitedInodes != null) {
                val key = DevInode(dirStat.st_dev, dirStat.st_ino)
                if (!visitedInodes.add(key)) continue
            }

            val children = runCatching { File(currentPath).list() }.getOrNull() ?: continue
            if (!recursive || currentPath in rootSet) {
                writer.writeDirListed(currentPath)
            }
            for (childName in children) {
                if (childName == "." || childName == "..") continue
                val childPath = if (currentPath == "/") "/$childName" else "$currentPath/$childName"

                if (isExcludedPrefix(childPath, excludedPrefixSet)) continue

                val st = runCatching { Os.lstat(childPath) }.getOrNull() ?: continue
                if (OsConstants.S_ISLNK(st.st_mode)) continue

                val isDir = OsConstants.S_ISDIR(st.st_mode)
                if (isDir && childName.lowercase() in excludedDirSet && childPath != "/data/local/tmp") continue

                val ext = if (!isDir) {
                    val dot = childName.lastIndexOf('.')
                    if (dot > 0 && dot < childName.lastIndex) childName.substring(dot + 1).lowercase() else ""
                } else {
                    ""
                }
                val size = if (isDir) 0L else st.st_size
                val mtime = st.st_mtime * 1000L

                writer.writeRecord(
                    path = childPath,
                    parentPath = currentPath,
                    name = childName,
                    ext = ext,
                    size = size,
                    mtime = mtime,
                    isDir = isDir
                )
                count++

                if (isDir && recursive) {
                    stack.addLast(childPath)
                }
            }
        }
        return count
    }

    private val statWorkers: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    private val statExecutor = Executors.newFixedThreadPool(statWorkers) { r ->
        Thread(r, "pinpoint-lstat").apply { isDaemon = true }
    }

    fun statPaths(paths: Array<String>, requireDir: Boolean = false): LongArray {
        val n = paths.size
        val result = LongArray(n) { -1L }
        if (n <= 1500) {
            statRange(paths, result, 0, n, requireDir)
            return result
        }
        val workers = statWorkers.coerceAtMost(n / 400)
        if (workers <= 1) {
            statRange(paths, result, 0, n, requireDir)
            return result
        }
        val chunk = (n + workers - 1) / workers
        val latch = CountDownLatch(workers)
        for (w in 0 until workers) {
            val start = w * chunk
            val end = minOf(n, start + chunk)
            if (start >= end) {
                latch.countDown()
                continue
            }
            statExecutor.execute {
                try {
                    statRange(paths, result, start, end, requireDir)
                } finally {
                    latch.countDown()
                }
            }
        }
        runCatching { latch.await() }
        return result
    }

    private fun statRange(
        paths: Array<String>,
        result: LongArray,
        start: Int,
        end: Int,
        requireDir: Boolean
    ) {
        for (i in start until end) {
            val st = runCatching { Os.lstat(paths[i]) }.getOrNull() ?: continue
            if (!requireDir || OsConstants.S_ISDIR(st.st_mode)) {
                result[i] = st.st_mtime * 1000L
            }
        }
    }

    fun statDirectories(dirPaths: Array<String>): LongArray = statPaths(dirPaths, requireDir = true)

    private fun normalizePrefix(p: String): String {
        val trimmed = p.trim()
        return if (trimmed.length > 1) trimmed.trimEnd('/') else trimmed
    }

    private fun isExcludedPrefix(path: String, excluded: Set<String>): Boolean {
        if (path in excluded) return true
        for (ex in excluded) {
            if (ex == "/") continue
            if (path.startsWith("$ex/")) return true
        }
        return false
    }
}
