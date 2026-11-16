package com.pocketeverything.app.fs

import android.content.Intent
import android.os.IBinder
import com.topjohnwu.superuser.ipc.RootService

/**
 * Runs inside a dedicated root process (uid=0) via libsu RootService.
 */
class RootFsService : RootService() {
    private val binder by lazy { PrivilegedFsBinderImpl(exitOnDestroy = false) }

    override fun onBind(intent: Intent): IBinder = binder
}
