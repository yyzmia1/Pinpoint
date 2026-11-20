package com.pocketeverything.app.ipc;

import android.os.ParcelFileDescriptor;

interface IPrivilegedFsService {
    /**
     * Walks the requested root paths inside the privileged process and streams encoded
     * FileEntry records into outputFd, closing outputFd when complete.
     */
    int scanTreeToPipe(
        in String[] roots,
        in String[] excludedPrefixes,
        in String[] excludedDirNames,
        in String[] includedPrefixes,
        boolean deduplicateMounts,
        in ParcelFileDescriptor outputFd
    ) = 0;

    /**
     * Stats directories in batch; returns parallel long[] mtimes (-1 if missing/inaccessible).
     */
    long[] statDirectories(in String[] dirPaths) = 1;

    /**
     * Copies a protected file into outputFd so the main process can parse or bridge-open it
     * without hitting Binder transaction size limits. Returns bytes written, or negative error code.
     */
    long copyFileToFd(String path, in ParcelFileDescriptor outputFd, long maxBytes) = 2;

    /**
     * Checks whether a path exists and is readable in the privileged context.
     */
    boolean exists(String path) = 3;

    /**
     * Gracefully terminates a Shizuku UserService when requested.
     * Transaction code 16777115 == FIRST_CALL_TRANSACTION + 16777114 per Shizuku-API spec.
     */
    void destroy() = 16777114;

    /**
     * Stats files or directories in batch; returns parallel long[] mtimes (-1 if missing/inaccessible).
     */
    long[] statPaths(in String[] paths) = 4;

    /**
     * Lists only direct children of [dirs] (non-recursive) for fast incremental refresh.
     */
    int listDirectChildrenToPipe(
        in String[] dirs,
        in String[] excludedPrefixes,
        in String[] excludedDirNames,
        in String[] includedPrefixes,
        boolean deduplicateMounts,
        in ParcelFileDescriptor outputFd
    ) = 5;

    /**
     * Safely deletes a file, symlink (unlinked without following), or directory subtree.
     * Enforces server-side DeletionSafetyGuard before touching disk.
     */
    boolean deletePath(String path) = 6;
}
