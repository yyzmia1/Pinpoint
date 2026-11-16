package com.pocketeverything.app

import android.app.Application
import com.pocketeverything.app.di.AppContainer
import com.pocketeverything.app.indexer.IncrementalSyncWorker
import com.pocketeverything.app.indexer.RealtimeMonitorService
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.topjohnwu.superuser.Shell

class PocketEverythingApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10)
        )
        PDFBoxResourceLoader.init(applicationContext)

        container = AppContainer(this)
        IncrementalSyncWorker.schedule(this)
        if (container.settingsRepository.settings.value.realtimeMonitorEnabled) {
            runCatching { RealtimeMonitorService.start(this) }
        }
    }
}
