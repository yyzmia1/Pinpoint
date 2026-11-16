package com.pocketeverything.app.indexer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pocketeverything.app.PocketEverythingApp

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? PocketEverythingApp ?: return
        if (app.container.settingsRepository.settings.value.realtimeMonitorEnabled) {
            runCatching { RealtimeMonitorService.start(context) }
        }
    }
}
