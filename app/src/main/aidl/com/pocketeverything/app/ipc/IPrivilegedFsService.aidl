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
        boolean deduplicateMounts,
        in ParcelFileDescriptor outputFd
    );

    /**
     * Stats directories in batch; returns parallel long[] mtimes (-1 if missing/inaccessible).
     */
    long[] statDirectories(in String[] dirPaths);

    /**
     * Copies a protected file into outputFd so the main process can parse or bridge-open it
     * without hitting Binder transaction size limits. Returns bytes written, or negative error code.
     */
    long copyFileToFd(String path, in ParcelFileDescriptor outputFd, long maxBytes);

    /**
     * Checks whether a path exists and is readable in the privileged context.
     */
    boolean exists(String path);

    /**
     * Gracefully terminates a Shizuku UserService when requested.
     */
    void destroy();
}
