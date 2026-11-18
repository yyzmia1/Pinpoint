package com.pocketeverything.app.di

import android.content.Context
import com.pocketeverything.app.data.SettingsRepository
import com.pocketeverything.app.fs.FileOpener
import com.pocketeverything.app.fs.FileSystemProvider
import com.pocketeverything.app.fs.NormalFileProvider
import com.pocketeverything.app.fs.PrivilegedFileProvider
import com.pocketeverything.app.indexer.TwoTierIndexManager
import com.pocketeverything.app.parser.ExtractorDispatcher
import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.search.SqliteSearchDatabase
import com.pocketeverything.app.ui.preview.FilePreviewLoader
import java.io.File

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsRepository = SettingsRepository(appContext)

    val database: SqliteSearchDatabase by lazy {
        val dbFile = File(appContext.filesDir, "pocket_everything_v1.db")
        SqliteSearchDatabase(dbFile.absolutePath)
    }

    val normalProvider: NormalFileProvider by lazy { NormalFileProvider(appContext) }
    val shizukuProvider: PrivilegedFileProvider by lazy {
        PrivilegedFileProvider(appContext, AccessMode.SHIZUKU)
    }
    val rootProvider: PrivilegedFileProvider by lazy {
        PrivilegedFileProvider(appContext, AccessMode.ROOT)
    }

    fun providerFor(mode: AccessMode): FileSystemProvider {
        return when (mode) {
            AccessMode.NORMAL -> normalProvider
            AccessMode.SHIZUKU -> shizukuProvider
            AccessMode.ROOT -> rootProvider
        }
    }

    val extractorDispatcher by lazy {
        ExtractorDispatcher(tempDir = File(appContext.cacheDir, "pdf_scratch").apply { mkdirs() })
    }

    val indexManager: TwoTierIndexManager by lazy {
        TwoTierIndexManager(
            context = appContext,
            db = database,
            settingsRepo = settingsRepository,
            providerResolver = ::providerFor,
            extractorDispatcher = extractorDispatcher
        )
    }

    val fileOpener: FileOpener by lazy {
        FileOpener(
            context = appContext,
            providerResolver = ::providerFor
        )
    }

    val previewLoader: FilePreviewLoader by lazy {
        FilePreviewLoader(
            context = appContext,
            db = database,
            extractorDispatcher = extractorDispatcher,
            providerResolver = ::providerFor
        )
    }
}
