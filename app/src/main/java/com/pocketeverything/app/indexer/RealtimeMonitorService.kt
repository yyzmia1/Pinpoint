package com.pocketeverything.app.indexer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.pocketeverything.app.MainActivity
import com.pocketeverything.app.PocketEverythingApp
import com.pocketeverything.app.R
import com.pocketeverything.app.fs.StorageRootsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Optional persistent background monitor service (disabled by default).
 * Combines MediaStore ContentObserver + FileObserver on hot folders with debounced incremental refresh.
 */
class RealtimeMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val observers = mutableListOf<FileObserver>()
    private var mediaObserver: ContentObserver? = null
    private var debounceJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        registerWatchers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        observers.forEach { runCatching { it.stopWatching() } }
        observers.clear()
        mediaObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerWatchers() {
        val dirs = StorageRootsHelper.getHotDirectories(this)
            .map { File(it) }
            .filter { it.exists() && it.isDirectory && it.canRead() }

        val mask = FileObserver.CREATE or FileObserver.DELETE or
            FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && dirs.isNotEmpty()) {
            val multiObserver = object : FileObserver(dirs, mask) {
                override fun onEvent(event: Int, path: String?) {
                    scheduleDebouncedSync()
                }
            }
            multiObserver.startWatching()
            observers.add(multiObserver)
        } else {
            for (d in dirs) {
                @Suppress("DEPRECATION")
                val single = object : FileObserver(d.absolutePath, mask) {
                    override fun onEvent(event: Int, path: String?) {
                        scheduleDebouncedSync()
                    }
                }
                single.startWatching()
                observers.add(single)
            }
        }

        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                scheduleDebouncedSync()
            }
        }
        mediaObserver = obs
        runCatching {
            contentResolver.registerContentObserver(
                MediaStore.Files.getContentUri("external"),
                true,
                obs
            )
        }
    }

    private fun scheduleDebouncedSync() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(2500L)
            val app = applicationContext as? PocketEverythingApp ?: return@launch
            app.container.indexManager.startQuickHotDirsRefresh()
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.monitor_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.monitor_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("PocketEverything 实时监听中")
            .setContentText("正在静默追踪新建文档与下载目录变更")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "pocket_monitor_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, RealtimeMonitorService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RealtimeMonitorService::class.java))
        }
    }
}
