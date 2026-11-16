package com.pocketeverything.app.fs

import android.os.ParcelFileDescriptor
import com.pocketeverything.app.ipc.IPrivilegedFsService

/**
 * Instantiated by Shizuku inside the privileged shell/root process via reflection.
 */
class ShizukuFsService : IPrivilegedFsService.Stub() {
    private val delegate = PrivilegedFsBinderImpl(exitOnDestroy = true)

    override fun scanTreeToPipe(
        roots: Array<String>,
        excludedPrefixes: Array<String>,
        excludedDirNames: Array<String>,
        deduplicateMounts: Boolean,
        outputFd: ParcelFileDescriptor
    ): Int = delegate.scanTreeToPipe(roots, excludedPrefixes, excludedDirNames, deduplicateMounts, outputFd)

    override fun statDirectories(dirPaths: Array<String>): LongArray = delegate.statDirectories(dirPaths)

    override fun copyFileToFd(path: String, outputFd: ParcelFileDescriptor, maxBytes: Long): Long =
        delegate.copyFileToFd(path, outputFd, maxBytes)

    override fun exists(path: String): Boolean = delegate.exists(path)

    override fun destroy() = delegate.destroy()
}
