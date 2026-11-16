package com.pocketeverything.app.fs

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.ArrayDeque

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
        writer: BinaryTreeStreamCodec.Writer
    ): Int {
        val excludedPrefixSet = excludedPrefixes.map { normalizePrefix(it) }.toHashSet()
        val excludedDirSet = excludedDirNames.map { it.lowercase() }.toHashSet()
        val visitedInodes = if (deduplicateMounts) HashSet<DevInode>(4096) else null
        var count = 0

        val stack = ArrayDeque<String>()
        for (rawRoot in roots) {
            val root = normalizePrefix(rawRoot)
            if (!isExcludedPrefix(root, excludedPrefixSet)) {
                stack.addLast(root)
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
            for (childName in children) {
                if (childName == "." || childName == "..") continue
                val childPath = if (currentPath == "/") "/$childName" else "$currentPath/$childName"

                if (isExcludedPrefix(childPath, excludedPrefixSet)) continue

                val st = runCatching { Os.lstat(childPath) }.getOrNull() ?: continue
                if (OsConstants.S_ISLNK(st.st_mode)) continue

                val isDir = OsConstants.S_ISDIR(st.st_mode)
                if (isDir && childName.lowercase() in excludedDirSet) continue

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

                if (isDir) {
                    stack.addLast(childPath)
                }
            }
        }
        return count
    }

    fun statDirectories(dirPaths: Array<String>): LongArray {
        val result = LongArray(dirPaths.size) { -1L }
        for (i in dirPaths.indices) {
            val st = runCatching { Os.lstat(dirPaths[i]) }.getOrNull()
            if (st != null && OsConstants.S_ISDIR(st.st_mode)) {
                result[i] = st.st_mtime * 1000L
            }
        }
        return result
    }

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
