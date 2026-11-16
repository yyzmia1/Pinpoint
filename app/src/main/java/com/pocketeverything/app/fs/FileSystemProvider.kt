package com.pocketeverything.app.fs

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.pocketeverything.app.BuildConfig
import com.pocketeverything.app.filter.FilterEngine
import com.pocketeverything.app.ipc.IPrivilegedFsService
import com.pocketeverything.app.search.AccessMode
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

object StorageRootsHelper {
    fun getNormalStorageRoots(context: Context): List<String> {
        val roots = LinkedHashSet<String>()
        val primary = Environment.getExternalStorageDirectory().absolutePath
        if (File(primary).exists()) roots.add(primary)

        val externalDirs = context.getExternalFilesDirs(null)
        for (dir in externalDirs) {
            if (dir == null) continue
            val p = dir.absolutePath
            val androidIdx = p.indexOf("/Android/data")
            if (androidIdx > 0) {
                val volRoot = p.substring(0, androidIdx)
                if (File(volRoot).exists()) roots.add(volRoot)
            }
        }
        return roots.toList()
    }

    fun getHotDirectories(context: Context): List<String> {
        val list = LinkedHashSet<String>()
        for (root in getNormalStorageRoots(context)) {
            list.add("$root/Download")
            list.add("$root/Documents")
            list.add("$root/DCIM")
            list.add("$root/Pictures")
            list.add("$root/Android/data/com.tencent.mm/MicroMsg/Download")
            list.add("$root/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv")
        }
        return list.filter { File(it).exists() || it.contains("Android/data") }
    }
}

sealed interface ModeAvailability {
    data object Ready : ModeAvailability
    data class NeedsPermission(val reason: String) : ModeAvailability
    data class Unavailable(val reason: String) : ModeAvailability
}

interface FileSystemProvider {
    val mode: AccessMode
    suspend fun checkAvailability(): ModeAvailability
    suspend fun scanFullTree(
        filterEngine: FilterEngine,
        onBatch: suspend (List<ScannedFileRecord>) -> Unit
    ): Int
    suspend fun scanDirectories(
        roots: List<String>,
        filterEngine: FilterEngine,
        onBatch: suspend (List<ScannedFileRecord>) -> Unit
    ): Int
    suspend fun statDirectories(dirPaths: List<String>): Map<String, Long>
    suspend fun <T> openFileStream(path: String, maxBytes: Long, block: suspend (InputStream) -> T): T
    suspend fun copyFileTo(path: String, targetFile: File, maxBytes: Long): Boolean
}

class NormalFileProvider(
    private val context: Context
) : FileSystemProvider {
    override val mode: AccessMode = AccessMode.NORMAL

    override suspend fun checkAvailability(): ModeAvailability {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
        return if (granted) {
            ModeAvailability.Ready
        } else {
            ModeAvailability.NeedsPermission("需要授予“所有文件访问权限”，才能索引文档与下载目录")
        }
    }

    override suspend fun scanFullTree(
        filterEngine: FilterEngine,
        onBatch: suspend (List<ScannedFileRecord>) -> Unit
    ): Int {
        val roots = StorageRootsHelper.getNormalStorageRoots(context)
        return scanDirectories(roots, filterEngine, onBatch)
    }

    override suspend fun scanDirectories(
        roots: List<String>,
        filterEngine: FilterEngine,
        onBatch: suspend (List<ScannedFileRecord>) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val excludedPrefixes = (
            filterEngine.getRootExcludedPrefixes(AccessMode.NORMAL).toList() +
                listOf("/storage/emulated/0/Android/data", "/storage/emulated/0/Android/obb")
            ).toTypedArray()
        val excludedDirNames = filterEngine.getExcludedDirNames()

        coroutineScope {
            val writeJob = async(Dispatchers.IO) {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { rawOut ->
                    BinaryTreeStreamCodec.Writer(rawOut).use { writer ->
                        PrivilegedTreeWalker.walk(
                            roots = roots.toTypedArray(),
                            excludedPrefixes = excludedPrefixes,
                            excludedDirNames = excludedDirNames,
                            deduplicateMounts = filterEngine.config.deduplicateMounts,
                            writer = writer
                        )
                    }
                }
            }
            var total = 0
            val pendingBatches = ArrayList<List<ScannedFileRecord>>()
            ParcelFileDescriptor.AutoCloseInputStream(readEnd).use { rawIn ->
                total = BinaryTreeStreamCodec.readStream(rawIn, AccessMode.NORMAL) { batch ->
                    pendingBatches.add(batch)
                }
            }
            writeJob.await()
            for (batch in pendingBatches) {
                onBatch(batch)
            }
            total
        }
    }

    override suspend fun statDirectories(dirPaths: List<String>): Map<String, Long> = withContext(Dispatchers.IO) {
        val mtimes = PrivilegedTreeWalker.statDirectories(dirPaths.toTypedArray())
        val map = HashMap<String, Long>(dirPaths.size)
        for (i in dirPaths.indices) {
            if (mtimes[i] >= 0L) map[dirPaths[i]] = mtimes[i]
        }
        map
    }

    override suspend fun <T> openFileStream(
        path: String,
        maxBytes: Long,
        block: suspend (InputStream) -> T
    ): T = withContext(Dispatchers.IO) {
        FileInputStream(File(path)).use { input ->
            block(input)
        }
    }

    override suspend fun copyFileTo(path: String, targetFile: File, maxBytes: Long): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            FileInputStream(File(path)).use { input ->
                FileOutputStream(targetFile).use { out ->
                    input.copyTo(out)
                }
            }
            true
        }.getOrDefault(false)
    }
}

/**
 * Shared client wrapper for RootService (libsu) and Shizuku UserService.
 */
class PrivilegedFileProvider(
    private val context: Context,
    override val mode: AccessMode
) : FileSystemProvider {

    @Volatile
    private var service: IPrivilegedFsService? = null

    private var rootConn: ServiceConnection? = null
    private var shizukuConn: ServiceConnection? = null
    private var shizukuArgs: Shizuku.UserServiceArgs? = null

    override suspend fun checkAvailability(): ModeAvailability {
        return when (mode) {
            AccessMode.ROOT -> {
                val hasRoot = withContext(Dispatchers.IO) {
                    runCatching { Shell.getShell().isRoot }.getOrDefault(false)
                }
                if (hasRoot) ModeAvailability.Ready
                else ModeAvailability.Unavailable("当前设备未授予 Root 权限或未 Root")
            }
            AccessMode.SHIZUKU -> {
                val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                if (!alive) {
                    return ModeAvailability.Unavailable("Shizuku 服务未运行，请先启动 Shizuku")
                }
                val granted = runCatching {
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false)
                if (granted) ModeAvailability.Ready
                else ModeAvailability.NeedsPermission("请在 Shizuku 中授权 PocketEverything")
            }
            AccessMode.NORMAL -> ModeAvailability.Ready
        }
    }

    private suspend fun ensureBound(): IPrivilegedFsService {
        val current = service
        if (current != null && current.asBinder().isBinderAlive) return current

        return when (mode) {
            AccessMode.ROOT -> bindRootService()
            AccessMode.SHIZUKU -> bindShizukuService()
            AccessMode.NORMAL -> error("Normal mode does not use privileged binder")
        }
    }

    private suspend fun bindRootService(): IPrivilegedFsService {
        val deferred = CompletableDeferred<IPrivilegedFsService>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val stub = IPrivilegedFsService.Stub.asInterface(binder)
                service = stub
                if (!deferred.isCompleted) deferred.complete(stub)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        rootConn = conn
        withContext(Dispatchers.Main) {
            val intent = Intent(context, RootFsService::class.java)
            RootService.bind(intent, conn)
        }
        return withTimeoutOrNull(12_000L) { deferred.await() }
            ?: error("RootService 绑定超时，请检查 Root 授权状态")
    }

    private suspend fun bindShizukuService(): IPrivilegedFsService {
        val deferred = CompletableDeferred<IPrivilegedFsService>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null || !binder.isBinderAlive) {
                    if (!deferred.isCompleted) {
                        deferred.completeExceptionally(IllegalStateException("Shizuku Binder 无效"))
                    }
                    return
                }
                val stub = IPrivilegedFsService.Stub.asInterface(binder)
                service = stub
                if (!deferred.isCompleted) deferred.complete(stub)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShizukuFsService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shizuku_fs")
            .tag("pocket_shizuku_fs")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

        shizukuConn = conn
        shizukuArgs = args
        withContext(Dispatchers.Main) {
            Shizuku.bindUserService(args, conn)
        }
        return withTimeoutOrNull(12_000L) { deferred.await() }
            ?: error("Shizuku UserService 启动超时，请确认 Shizuku 正常运行并已授权")
    }

    override suspend fun scanFullTree(
        filterEngine: FilterEngine,
        onBatch: suspend (List<ScannedFileRecord>) -> Unit
    ): Int {
        val roots = if (mode == AccessMode.ROOT) {
            listOf("/")
        } else {
            StorageRootsHelper.getNormalStorageRoots(context)
        }
        return scanDirectories(roots, filterEngine, onBatch)
    }

    override suspend fun scanDirectories(
        roots: List<String>,
        filterEngine: FilterEngine,
        onBatch: suspend (List<ScannedFileRecord>) -> Unit
    ): Int = coroutineScope {
        val svc = ensureBound()
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val excludedPrefixes = filterEngine.getRootExcludedPrefixes(mode)
        val excludedDirNames = filterEngine.getExcludedDirNames()

        val remoteJob = async(Dispatchers.IO) {
            try {
                svc.scanTreeToPipe(
                    roots.toTypedArray(),
                    excludedPrefixes,
                    excludedDirNames,
                    filterEngine.config.deduplicateMounts,
                    writeEnd
                )
            } finally {
                runCatching { writeEnd.close() }
            }
        }

        val collected = ArrayList<ScannedFileRecord>(1500)
        var total = 0
        withContext(Dispatchers.IO) {
            ParcelFileDescriptor.AutoCloseInputStream(readEnd).use { rawIn ->
                total = BinaryTreeStreamCodec.readStream(rawIn, mode, batchSize = 1500) { batch ->
                    collected.addAll(batch)
                }
            }
        }
        remoteJob.await()
        for (chunk in collected.chunked(1500)) {
            onBatch(chunk)
        }
        total
    }

    override suspend fun statDirectories(dirPaths: List<String>): Map<String, Long> = withContext(Dispatchers.IO) {
        if (dirPaths.isEmpty()) return@withContext emptyMap()
        val svc = ensureBound()
        val mtimes = svc.statDirectories(dirPaths.toTypedArray())
        val map = HashMap<String, Long>(dirPaths.size)
        for (i in dirPaths.indices) {
            if (i < mtimes.size && mtimes[i] >= 0L) {
                map[dirPaths[i]] = mtimes[i]
            }
        }
        map
    }

    override suspend fun <T> openFileStream(
        path: String,
        maxBytes: Long,
        block: suspend (InputStream) -> T
    ): T = coroutineScope {
        // Fast path: if file is directly readable without IPC overhead, read locally
        val localFile = File(path)
        if (localFile.isFile && localFile.canRead()) {
            return@coroutineScope withContext(Dispatchers.IO) {
                FileInputStream(localFile).use { block(it) }
            }
        }

        val svc = ensureBound()
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]

        val copyJob = async(Dispatchers.IO) {
            try {
                svc.copyFileToFd(path, writeEnd, maxBytes)
            } finally {
                runCatching { writeEnd.close() }
            }
        }

        val result = withContext(Dispatchers.IO) {
            ParcelFileDescriptor.AutoCloseInputStream(readEnd).use { input ->
                block(input)
            }
        }
        copyJob.await()
        result
    }

    override suspend fun copyFileTo(path: String, targetFile: File, maxBytes: Long): Boolean {
        return runCatching {
            openFileStream(path, maxBytes) { input ->
                FileOutputStream(targetFile).use { out ->
                    input.copyTo(out)
                }
            }
            targetFile.exists() && targetFile.length() > 0L
        }.getOrDefault(false)
    }

    fun unbind() {
        runCatching {
            rootConn?.let { RootService.unbind(it) }
        }
        runCatching {
            val args = shizukuArgs
            val conn = shizukuConn
            if (args != null && conn != null) {
                Shizuku.unbindUserService(args, conn, true)
            }
        }
        service = null
    }
}
