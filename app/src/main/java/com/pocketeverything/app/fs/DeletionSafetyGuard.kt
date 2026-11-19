package com.pocketeverything.app.fs

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

enum class DeletionRisk {
    BLOCKED,
    HIGH_PERSONAL,
    NORMAL
}

data class DeletionCheckResult(
    val risk: DeletionRisk,
    val normalizedPath: String,
    val reason: String? = null
)

/**
 * Dual-side (UI + privileged Binder) deletion safety guard.
 * Hard-blocks system partitions, root mountpoints, and shared app container roots,
 * flags personal media/document folders, and deletes strictly without following symlinks.
 */
object DeletionSafetyGuard {

    /** Immutable OS / virtual / firmware partitions: both the root and every descendant are blocked. */
    private val BLOCKED_SUBTREES = setOf(
        "/system",
        "/system_ext",
        "/vendor",
        "/product",
        "/odm",
        "/apex",
        "/proc",
        "/sys",
        "/dev",
        "/acct",
        "/config",
        "/bin",
        "/sbin",
        "/etc",
        "/lib",
        "/lib64",
        "/boot",
        "/recovery",
        "/metadata",
        "/efs",
        "/persist",
        "/dsp",
        "/firmware",
        "/data/system",
        "/data/system_ce",
        "/data/system_de",
        "/data/misc",
        "/data/misc_ce",
        "/data/misc_de",
        "/data/dalvik-cache",
        "/data/app",
        "/data/vendor",
        "/data/apex",
        "/data/adb"
    )

    /** Container roots where the container itself cannot be wiped, though specific children may be deleted. */
    private val BLOCKED_EXACT_ROOTS = setOf(
        "/",
        "/mnt",
        "/storage",
        "/storage/emulated",
        "/storage/emulated/0",
        "/storage/self",
        "/storage/self/primary",
        "/sdcard",
        "/cache",
        "/data",
        "/data/data",
        "/data/user",
        "/data/user/0",
        "/data/user_de",
        "/data/user_de/0",
        "/data/media",
        "/data/media/0",
        "/data/local",
        "/storage/emulated/0/Android",
        "/storage/emulated/0/Android/data",
        "/storage/emulated/0/Android/obb",
        "/storage/emulated/0/Android/media",
        "/sdcard/Android",
        "/sdcard/Android/data",
        "/sdcard/Android/obb",
        "/sdcard/Android/media",
        "/data/media/0/Android",
        "/data/media/0/Android/data",
        "/data/media/0/Android/obb",
        "/data/media/0/Android/media"
    )

    private val PERSONAL_TOP_DIRS = setOf(
        "dcim",
        "pictures",
        "movies",
        "music",
        "documents",
        "download",
        "downloads"
    )

    private val STORAGE_PREFIXES = listOf(
        "/storage/emulated/0",
        "/sdcard",
        "/data/media/0"
    )

    fun normalizePath(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val collapsed = trimmed.replace(Regex("/+"), "/")
        return if (collapsed.length > 1) collapsed.trimEnd('/') else collapsed
    }

    fun evaluate(rawPath: String): DeletionCheckResult {
        if (rawPath.isBlank() || rawPath.contains('\u0000')) {
            return DeletionCheckResult(DeletionRisk.BLOCKED, "", "路径为空或包含非法字符")
        }
        val trimmed = rawPath.trim()
        if (!trimmed.startsWith('/')) {
            return DeletionCheckResult(DeletionRisk.BLOCKED, trimmed, "仅允许删除绝对路径")
        }
        val rawSegments = trimmed.split('/')
        if (rawSegments.any { it == ".." || it == "." }) {
            return DeletionCheckResult(DeletionRisk.BLOCKED, trimmed, "路径不允许包含相对段 (. 或 ..)")
        }

        val normalized = normalizePath(trimmed)

        if (normalized in BLOCKED_EXACT_ROOTS) {
            return DeletionCheckResult(DeletionRisk.BLOCKED, normalized, "受保护的根目录或系统容器，禁止直接删除")
        }

        for (prefix in BLOCKED_SUBTREES) {
            if (normalized == prefix || normalized.startsWith("$prefix/")) {
                return DeletionCheckResult(DeletionRisk.BLOCKED, normalized, "受保护的系统分区目录 ($prefix)，禁止删除")
            }
        }

        // Also protect removable SD card / USB volume roots like /storage/XXXX-XXXX and its /Android/data container.
        if (normalized.startsWith("/storage/")) {
            val rest = normalized.removePrefix("/storage/")
            val parts = rest.split('/')
            if (parts.size == 1) {
                return DeletionCheckResult(DeletionRisk.BLOCKED, normalized, "受保护的存储卷根目录，禁止直接删除")
            }
            if (parts.size == 2 && parts[1] == "Android") {
                return DeletionCheckResult(DeletionRisk.BLOCKED, normalized, "受保护的 Android 应用目录根，禁止直接删除")
            }
            if (parts.size == 3 && parts[1] == "Android" && parts[2] in setOf("data", "obb", "media")) {
                return DeletionCheckResult(DeletionRisk.BLOCKED, normalized, "受保护的应用数据容器根，禁止整体删除")
            }
            if (parts.size >= 2 && parts[0] == "emulated" && parts[1].all { it.isDigit() } && parts.size == 2) {
                return DeletionCheckResult(DeletionRisk.BLOCKED, normalized, "受保护的用户存储根目录，禁止直接删除")
            }
        }

        // Protect /data/user/<uid> and /data/user_de/<uid> roots for multi-user / dual-app profiles.
        if (normalized.startsWith("/data/user/") || normalized.startsWith("/data/user_de/")) {
            val parts = normalized.split('/').filter { it.isNotEmpty() }
            if (parts.size <= 3) {
                return DeletionCheckResult(DeletionRisk.BLOCKED, normalized, "受保护的用户数据容器根，禁止直接删除")
            }
        }

        // Flag personal media/document folders so UI shows an extra clear confirmation warning.
        for (root in STORAGE_PREFIXES) {
            if (normalized.startsWith("$root/")) {
                val rel = normalized.removePrefix("$root/")
                val topDir = rel.substringBefore('/').lowercase()
                if (topDir in PERSONAL_TOP_DIRS) {
                    return DeletionCheckResult(
                        risk = DeletionRisk.HIGH_PERSONAL,
                        normalizedPath = normalized,
                        reason = "位于个人资料目录 (${rel.substringBefore('/')})，删除后无法恢复"
                    )
                }
            }
        }

        return DeletionCheckResult(DeletionRisk.NORMAL, normalized)
    }

    /**
     * Deletes a file, symlink, or directory tree strictly without following symlinks.
     * Re-enforces [evaluate] before touching the filesystem.
     */
    fun deleteRecursivelyNoFollow(rawPath: String): Boolean {
        val check = evaluate(rawPath)
        if (check.risk == DeletionRisk.BLOCKED) return false
        val root = check.normalizedPath
        return deleteNodeNoFollow(root)
    }

    private fun deleteNodeNoFollow(path: String): Boolean {
        return when (probeKindNoFollow(path)) {
            NodeKind.MISSING -> true
            NodeKind.SYMLINK, NodeKind.FILE -> unlinkSingle(path)
            NodeKind.DIRECTORY -> {
                val dir = File(path)
                val childNames = dir.list() ?: return false
                var allOk = true
                for (name in childNames) {
                    if (name == "." || name == "..") continue
                    val childPath = if (path == "/") "/$name" else "$path/$name"
                    if (!deleteNodeNoFollow(childPath)) {
                        allOk = false
                    }
                }
                if (!allOk) return false
                unlinkSingle(path)
            }
        }
    }

    private enum class NodeKind { MISSING, FILE, DIRECTORY, SYMLINK }

    private fun probeKindNoFollow(path: String): NodeKind {
        // Prefer Android POSIX Os.lstat (never follows symlinks, works across all Android API >= 21).
        try {
            val st = Os.lstat(path)
            val mode = st.st_mode
            return when {
                OsConstants.S_ISLNK(mode) -> NodeKind.SYMLINK
                OsConstants.S_ISDIR(mode) -> NodeKind.DIRECTORY
                else -> NodeKind.FILE
            }
        } catch (_: LinkageError) {
            // Host JVM unit tests (Android stub Os throws or is unavailable) -> fall through to NIO.
        } catch (t: Throwable) {
            // Android ErrnoException ENOENT or host RuntimeException("Stub!").
            val msg = t.message.orEmpty()
            if (msg.contains("ENOENT")) return NodeKind.MISSING
            if (!msg.contains("Stub")) {
                // Fall through to NIO probe only if Os.lstat isn't available; otherwise check exists.
                val f = File(path)
                if (!f.exists() && !isNioSymlink(path)) return NodeKind.MISSING
            }
        }

        val nioPath = runCatching { Paths.get(path) }.getOrNull() ?: return NodeKind.MISSING
        if (Files.isSymbolicLink(nioPath)) return NodeKind.SYMLINK
        if (!Files.exists(nioPath, LinkOption.NOFOLLOW_LINKS)) return NodeKind.MISSING
        return if (Files.isDirectory(nioPath, LinkOption.NOFOLLOW_LINKS)) NodeKind.DIRECTORY else NodeKind.FILE
    }

    private fun isNioSymlink(path: String): Boolean =
        runCatching { Files.isSymbolicLink(Paths.get(path)) }.getOrDefault(false)

    private fun unlinkSingle(path: String): Boolean {
        try {
            Os.remove(path)
            return true
        } catch (_: LinkageError) {
            // Host JVM fallback
        } catch (t: Throwable) {
            val msg = t.message.orEmpty()
            if (msg.contains("ENOENT")) return true
        }
        return runCatching {
            val p = Paths.get(path)
            Files.deleteIfExists(p) || !Files.exists(p, LinkOption.NOFOLLOW_LINKS)
        }.getOrElse {
            val f = File(path)
            f.delete() || !f.exists()
        }
    }
}
