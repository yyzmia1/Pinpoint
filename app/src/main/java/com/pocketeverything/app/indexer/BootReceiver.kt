package com.pocketeverything.app.indexer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.pocketeverything.app.PocketEverythingApp
import com.pocketeverything.app.search.AccessMode
import rikka.shizuku.Shizuku

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? PocketEverythingApp ?: return
        val settings = app.container.settingsRepository.settings.value
        val skipBootSync = settings.accessMode == AccessMode.SHIZUKU && !runCatching {
            Shizuku.pingBinder() &&
                !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        if (!skipBootSync) {
            IncrementalSyncWorker.enqueueBootSync(context)
        }
        if (settings.realtimeMonitorEnabled) {
            runCatching { RealtimeMonitorService.start(context) }
        }
    }
}
