package com.pocketeverything.app.fs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.search.SearchResultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/**
 * Opens files safely across permission boundaries:
 * - Normal accessible paths use FileProvider directly.
 * - Protected Root/Shizuku paths (e.g., /Android/data/com.tencent.mm/...) are streamed via
 *   the privileged pipe into cacheDir/preview_bridge/ before launching external apps.
 */
class FileOpener(
    private val context: Context,
    private val providerResolver: (AccessMode) -> FileSystemProvider
) {
    private val bridgeDir: File
        get() = File(context.cacheDir, "preview_bridge").apply { mkdirs() }

    suspend fun openWithSystemApp(item: SearchResultItem): Result<Unit> = runCatching {
        val uri = resolveShareableUri(item)
        val mime = if (item.isDir) {
            "resource/folder"
        } else {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.ext.lowercase()) ?: "*/*"
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        withContext(Dispatchers.Main) {
            context.startActivity(Intent.createChooser(intent, "选择打开方式").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    suspend fun shareFile(item: SearchResultItem): Result<Unit> = runCatching {
        if (item.isDir) error("暂不支持直接分享文件夹")
        val uri = resolveShareableUri(item)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.ext.lowercase()) ?: "*/*"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        withContext(Dispatchers.Main) {
            context.startActivity(Intent.createChooser(intent, "分享文件").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    suspend fun resolveShareableUri(item: SearchResultItem): Uri = withContext(Dispatchers.IO) {
        val rawFile = File(item.path)
        if (rawFile.canRead()) {
            runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", rawFile)
            }.getOrNull()?.let { return@withContext it }
        }
        if (item.isDir) {
            error("系统文件管理器不支持直接打开受限目录，已为您准备路径复制")
        }
        val staged = stageProtectedFile(
            path = item.path,
            fileName = item.name,
            accessMode = item.accessMode,
            maxBytes = 200L * 1024L * 1024L
        )
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", staged)
    }

    suspend fun openReadableDescriptor(
        path: String,
        accessMode: AccessMode,
        maxBytes: Long = 200L * 1024L * 1024L
    ): ParcelFileDescriptor = withContext(Dispatchers.IO) {
        val rawFile = File(path)
        if (rawFile.isDirectory || (rawFile.exists() && !rawFile.isFile)) {
            throw FileNotFoundException("不支持打开目录或特殊文件：$path")
        }
        if (rawFile.isFile && rawFile.canRead()) {
            return@withContext ParcelFileDescriptor.open(rawFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val fileName = rawFile.name.ifBlank { "file" }
        val staged = try {
            stageProtectedFile(path, fileName, accessMode, maxBytes)
        } catch (t: Throwable) {
            throw FileNotFoundException(t.message ?: "无法读取受保护文件：$path").apply { initCause(t) }
        }
        ParcelFileDescriptor.open(staged, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private suspend fun stageProtectedFile(
        path: String,
        fileName: String,
        accessMode: AccessMode,
        maxBytes: Long
    ): File {
        val safeName = fileName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "file"
        cleanOldBridgeFiles()
        val stageDir = File(bridgeDir, System.nanoTime().toString()).apply { mkdirs() }
        val staged = File(stageDir, safeName)
        val copied = providerResolver(accessMode).copyFileTo(path, staged, maxBytes = maxBytes)
        if (!copied) {
            runCatching { stageDir.deleteRecursively() }
            error("无法读取受保护文件：$path")
        }
        return staged
    }

    fun copyToClipboard(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun cleanOldBridgeFiles() {
        val cutoff = System.currentTimeMillis() - 30L * 60L * 1000L
        bridgeDir.listFiles()?.forEach { f ->
            if (f.lastModified() < cutoff) {
                runCatching { f.deleteRecursively() }
            }
        }
    }
}
