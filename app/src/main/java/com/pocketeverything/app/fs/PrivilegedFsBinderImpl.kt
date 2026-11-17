package com.pocketeverything.app.fs

import android.os.ParcelFileDescriptor
import com.pocketeverything.app.ipc.IPrivilegedFsService
import java.io.File
import java.io.FileInputStream
import kotlin.system.exitProcess

/**
 * Shared AIDL Binder stub implementation used inside both RootService (uid=0)
 * and Shizuku UserService (uid=2000 or uid=0).
 */
class PrivilegedFsBinderImpl(
    private val exitOnDestroy: Boolean
) : IPrivilegedFsService.Stub() {

    override fun scanTreeToPipe(
        roots: Array<String>,
        excludedPrefixes: Array<String>,
        excludedDirNames: Array<String>,
        deduplicateMounts: Boolean,
        outputFd: ParcelFileDescriptor
    ): Int = walkToPipe(roots, excludedPrefixes, excludedDirNames, deduplicateMounts, outputFd, recursive = true)

    override fun listDirectChildrenToPipe(
        dirs: Array<String>,
        excludedPrefixes: Array<String>,
        excludedDirNames: Array<String>,
        deduplicateMounts: Boolean,
        outputFd: ParcelFileDescriptor
    ): Int = walkToPipe(dirs, excludedPrefixes, excludedDirNames, deduplicateMounts, outputFd, recursive = false)

    private fun walkToPipe(
        roots: Array<String>,
        excludedPrefixes: Array<String>,
        excludedDirNames: Array<String>,
        deduplicateMounts: Boolean,
        outputFd: ParcelFileDescriptor,
        recursive: Boolean
    ): Int {
        return outputFd.use { pfd ->
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { rawOut ->
                BinaryTreeStreamCodec.Writer(rawOut).use { writer ->
                    PrivilegedTreeWalker.walk(
                        roots = roots,
                        excludedPrefixes = excludedPrefixes,
                        excludedDirNames = excludedDirNames,
                        deduplicateMounts = deduplicateMounts,
                        writer = writer,
                        recursive = recursive
                    )
                }
            }
        }
    }

    override fun statDirectories(dirPaths: Array<String>): LongArray {
        return PrivilegedTreeWalker.statDirectories(dirPaths)
    }

    override fun statPaths(paths: Array<String>): LongArray {
        return PrivilegedTreeWalker.statPaths(paths, requireDir = false)
    }

    override fun copyFileToFd(
        path: String,
        outputFd: ParcelFileDescriptor,
        maxBytes: Long
    ): Long {
        return outputFd.use { pfd ->
            try {
                val file = File(path)
                if (!file.exists() || !file.isFile) return@use -2L
                val cap = if (maxBytes <= 0L) Long.MAX_VALUE else maxBytes
                var total = 0L
                FileInputStream(file).use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
                        val buf = ByteArray(32 * 1024)
                        while (total < cap) {
                            val toRead = minOf(buf.size.toLong(), cap - total).toInt()
                            val n = input.read(buf, 0, toRead)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            total += n
                        }
                        out.flush()
                    }
                }
                total
            } catch (_: Throwable) {
                -1L
            }
        }
    }

    override fun exists(path: String): Boolean {
        return runCatching { File(path).exists() }.getOrDefault(false)
    }

    override fun destroy() {
        if (exitOnDestroy) {
            exitProcess(0)
        }
    }
}
