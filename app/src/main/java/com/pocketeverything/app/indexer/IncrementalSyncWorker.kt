package com.pocketeverything.app.indexer

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pocketeverything.app.PocketEverythingApp
import java.util.concurrent.TimeUnit

class IncrementalSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? PocketEverythingApp ?: return Result.success()
        val silent = inputData.getBoolean(KEY_SILENT_IF_UNREADY, false)
        val includeRootOther = inputData.getBoolean(KEY_INCLUDE_ROOT_OTHER, false)
        app.container.indexManager.startQuickHotDirsRefresh(
            silentIfUnavailable = silent,
            includeRootOther = includeRootOther
        ).join()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "pocket_everything_incremental_sync"
        private const val KEY_SILENT_IF_UNREADY = "silent_if_unready"
        private const val KEY_INCLUDE_ROOT_OTHER = "include_root_other"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            val req = PeriodicWorkRequestBuilder<IncrementalSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }

        fun enqueueBootSync(context: Context) {
            val req = OneTimeWorkRequestBuilder<IncrementalSyncWorker>()
                .setInputData(
                    workDataOf(
                        KEY_SILENT_IF_UNREADY to true,
                        KEY_INCLUDE_ROOT_OTHER to true
                    )
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_boot",
                ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }
}
