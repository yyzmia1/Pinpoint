package com.pocketeverything.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.pocketeverything.app.data.SettingsRepository
import com.pocketeverything.app.filter.FilterEngine
import com.pocketeverything.app.fs.FileSystemProvider
import com.pocketeverything.app.fs.ModeAvailability
import com.pocketeverything.app.fs.ScannedFileRecord
import com.pocketeverything.app.indexer.TwoTierIndexManager
import com.pocketeverything.app.picker.PinpointDocContract
import com.pocketeverything.app.picker.PinpointDocId
import com.pocketeverything.app.picker.PinpointDocumentsProvider
import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.search.FileUpsertEntry
import com.pocketeverything.app.search.SqliteSearchDatabase
import com.pocketeverything.app.search.Tier2Status
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class PinpointPickerAndProviderTest {

    @Test
    fun `PinpointDocId encode parse round-trip and reject path traversal or invalid ids`() {
        assertEquals(PinpointDocId.Root, PinpointDocId.parse("root"))
        assertEquals(PinpointDocId.Recent, PinpointDocId.parse("recent"))

        val dirId = PinpointDocId.Dir("/storage/emulated/0/Download/")
        assertEquals("dir:/storage/emulated/0/Download", dirId.encode())
        assertEquals(PinpointDocId.Dir("/storage/emulated/0/Download"), PinpointDocId.parse(dirId.encode()))

        val fileId = PinpointDocId.File("/storage/emulated/0/合同.pdf")
        assertEquals("file:/storage/emulated/0/合同.pdf", fileId.encode())
        assertEquals(fileId, PinpointDocId.parse(fileId.encode()))

        assertNull(PinpointDocId.parse(null))
        assertNull(PinpointDocId.parse(""))
        assertNull(PinpointDocId.parse("unknown:123"))
        assertNull(PinpointDocId.parse("file:/"))
        assertNull(PinpointDocId.parse("file:relative/path.txt"))
        assertNull(PinpointDocId.parse("file:/storage/../etc/passwd"))
        assertNull(PinpointDocId.parse("dir:/storage/./emulated"))
        assertNull(PinpointDocId.parse("file:/storage/bad\u0000name.txt"))
    }

    @Test
    fun `PinpointDocContract validates child relationships strictly without sibling prefix leak`() {
        val allowedRoots = setOf("/storage/emulated/0")
        assertTrue(PinpointDocContract.isChildDocument("root", "recent", allowedRoots))
        assertTrue(PinpointDocContract.isChildDocument("root", "dir:/storage/emulated/0", allowedRoots))
        assertTrue(PinpointDocContract.isChildDocument("root", "file:/storage/emulated/0/a.txt", allowedRoots))
        assertFalse(PinpointDocContract.isChildDocument("root", "dir:/data/system", allowedRoots))

        assertTrue(PinpointDocContract.isChildDocument("recent", "file:/storage/emulated/0/a.txt", allowedRoots))
        assertFalse(PinpointDocContract.isChildDocument("recent", "dir:/storage/emulated/0/Download", allowedRoots))

        assertTrue(PinpointDocContract.isChildDocument("dir:/storage/emulated/0/Download", "file:/storage/emulated/0/Download/a.pdf", allowedRoots))
        assertFalse(PinpointDocContract.isChildDocument("dir:/storage/emulated/0/Download", "dir:/storage/emulated/0/Downloads", allowedRoots))
        assertFalse(PinpointDocContract.isChildDocument("file:/storage/emulated/0/a.txt", "file:/storage/emulated/0/a.txt", allowedRoots))
    }

    @Test
    fun `resolveOpenableFileMeta and getFileMetaByPath enforce read-only indexed boundaries`() {
        val dbFile = Files.createTempFile("pocket_picker_db_", ".db").toFile().apply { deleteOnExit() }
        SqliteSearchDatabase(dbFile.absolutePath).use { db ->
            val t0 = 1700000000000L
            db.upsertFileBatch(
                listOf(
                    FileUpsertEntry("/storage/emulated/0/Docs", "/storage/emulated/0", "Docs", "", 0L, t0, true, AccessMode.NORMAL, false, Tier2Status.NOT_APPLICABLE),
                    FileUpsertEntry("/storage/emulated/0/Docs/plan.pdf", "/storage/emulated/0/Docs", "plan.pdf", "pdf", 2048L, t0, false, AccessMode.SHIZUKU, false, Tier2Status.INDEXED)
                )
            )

            val meta = db.getFileMetaByPath("/storage/emulated/0/Docs/plan.pdf")
            assertNotNull(meta)
            assertEquals("plan.pdf", meta?.name)
            assertEquals(AccessMode.SHIZUKU, meta?.accessMode)
            assertNull(db.getFileMetaByPath("/storage/emulated/0/Docs/missing.pdf"))

            val resolved = PinpointDocContract.resolveOpenableFileMeta(
                db = db,
                documentId = "file:/storage/emulated/0/Docs/plan.pdf",
                mode = "r"
            )
            assertEquals("/storage/emulated/0/Docs/plan.pdf", resolved.path)

            val row = PinpointDocContract.toDocumentRow(resolved)
            val customProj = PinpointDocContract.resolveDocumentProjection(arrayOf("_display_name", "mime_type", "_size"))
            val values = PinpointDocContract.buildDocumentRowValues(customProj, row)
            assertEquals("plan.pdf", values[0])
            assertEquals("application/pdf", values[1])
            assertEquals(2048L, values[2])

            val writeRejected = runCatching {
                PinpointDocContract.resolveOpenableFileMeta(db, "file:/storage/emulated/0/Docs/plan.pdf", "rw")
            }.exceptionOrNull()
            assertTrue(writeRejected is UnsupportedOperationException)

            val dirRejected = runCatching {
                PinpointDocContract.resolveOpenableFileMeta(db, "dir:/storage/emulated/0/Docs", "r")
            }.exceptionOrNull()
            assertTrue(dirRejected is FileNotFoundException)

            val unindexedRejected = runCatching {
                PinpointDocContract.resolveOpenableFileMeta(db, "file:/data/system/users/0/settings.xml", "r")
            }.exceptionOrNull()
            assertTrue(unindexedRejected is FileNotFoundException)
        }
    }

    @Test
    fun `PinpointDocumentsProvider syncIndexForProviderQuery refreshes new files before query and notifies on slow sync`() = runBlocking {
        assertEquals(1_200L, PinpointDocumentsProvider.PROVIDER_SYNC_MAX_WAIT_MS)

        val prefs = object : SharedPreferences {
            private val map = LinkedHashMap<String, Any?>()
            override fun getAll(): MutableMap<String, *> = HashMap(map)
            override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
                @Suppress("UNCHECKED_CAST")
                return (map[key] as? Set<String>)?.toMutableSet() ?: defValues
            }
            override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
            override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
            override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
            override fun contains(key: String?): Boolean = map.containsKey(key)
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
                private val pending = LinkedHashMap<String, Any?>()
                override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
                override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values?.toSet() }
                override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
                override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
                override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
                override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
                override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
                override fun clear() = apply { map.clear() }
                override fun commit(): Boolean { apply(); return true }
                override fun apply() { pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v } }
            }
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }
        val ctx = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        }

        val dbFile = Files.createTempFile("pocket_provider_sync_", ".db").toFile().apply { deleteOnExit() }
        val settingsRepo = SettingsRepository(ctx)
        settingsRepo.updateAccessMode(AccessMode.NORMAL)

        var nowMs = 10_000L
        var dlMtime = 1_000L
        var gate: CompletableDeferred<Unit>? = null
        val statCalls = AtomicInteger(0)
        val diskDownloadFiles = mutableListOf(
            ScannedFileRecord("/storage/emulated/0/Download/old.pdf", "/storage/emulated/0/Download", "old.pdf", "pdf", 100L, 1_000L, false, AccessMode.NORMAL)
        )

        val provider = object : FileSystemProvider {
            override val mode: AccessMode = AccessMode.NORMAL
            override suspend fun checkAvailability(): ModeAvailability = ModeAvailability.Ready
            override suspend fun scanFullTree(filterEngine: FilterEngine, onBatch: suspend (List<ScannedFileRecord>) -> Unit): Int = 0
            override suspend fun scanDirectories(roots: List<String>, filterEngine: FilterEngine, onBatch: suspend (List<ScannedFileRecord>) -> Unit): Int = 0
            override suspend fun listDirectChildren(dirs: List<String>, filterEngine: FilterEngine, onBatch: suspend (List<ScannedFileRecord>) -> Unit): Int = 0
            override suspend fun listDirectChildren(
                dirs: List<String>,
                filterEngine: FilterEngine,
                onDirListed: (String) -> Unit,
                onBatch: suspend (List<ScannedFileRecord>) -> Unit
            ): Int {
                if ("/storage/emulated/0/Download" in dirs) {
                    onDirListed("/storage/emulated/0/Download")
                    onBatch(diskDownloadFiles.toList())
                    return diskDownloadFiles.size
                }
                return 0
            }
            override suspend fun statDirectories(dirPaths: List<String>): Map<String, Long> {
                statCalls.incrementAndGet()
                gate?.await()
                return mapOf(
                    "/storage/emulated/0" to 1_000L,
                    "/storage/emulated/0/Download" to dlMtime
                )
            }
            override suspend fun statPaths(paths: List<String>): Map<String, Long> = emptyMap()
            override suspend fun <T> openFileStream(path: String, maxBytes: Long, block: suspend (InputStream) -> T): T = error("unused")
            override suspend fun copyFileTo(path: String, targetFile: File, maxBytes: Long): Boolean = false
        }

        SqliteSearchDatabase(dbFile.absolutePath).use { db ->
            db.upsertFileBatch(
                listOf(
                    FileUpsertEntry("/storage/emulated/0/Download", "/storage/emulated/0", "Download", "", 0L, dlMtime, true, AccessMode.NORMAL, false, Tier2Status.NOT_APPLICABLE),
                    FileUpsertEntry("/storage/emulated/0/Download/old.pdf", "/storage/emulated/0/Download", "old.pdf", "pdf", 100L, 1_000L, false, AccessMode.NORMAL, false, Tier2Status.NOT_APPLICABLE)
                )
            )
            db.saveDirSnapshots(mapOf("/storage/emulated/0" to 1_000L), AccessMode.NORMAL)
            settingsRepo.markFullScanCompleted(1_000L)

            val mgr = TwoTierIndexManager(
                context = ctx,
                db = db,
                settingsRepo = settingsRepo,
                providerResolver = { provider },
                storageRootsResolver = { listOf("/storage/emulated/0") },
                clockMs = { nowMs }
            )

            // 1. User saves new_report.pdf outside Pinpoint, then opens Pinpoint in DocumentsUI left drawer
            dlMtime = 2_000L
            diskDownloadFiles.add(
                ScannedFileRecord("/storage/emulated/0/Download/new_report.pdf", "/storage/emulated/0/Download", "new_report.pdf", "pdf", 4096L, dlMtime, false, AccessMode.NORMAL)
            )
            PinpointDocumentsProvider.syncIndexForProviderQuery(indexManager = mgr)

            val searchHits = db.search("new_report").items
            assertEquals(1, searchHits.size)
            val row = PinpointDocContract.toDocumentRow(searchHits.single())
            assertEquals("file:/storage/emulated/0/Download/new_report.pdf", row.documentId)
            assertEquals("application/pdf", row.mimeType)
            assertEquals(1, statCalls.get())

            // 2. Subsequent query within cooldown does not re-scan
            nowMs += 500L
            PinpointDocumentsProvider.syncIndexForProviderQuery(indexManager = mgr)
            assertEquals(1, statCalls.get())

            // 3. Slow sync past maxWaitMs triggers deferred notification, and simulated DocumentsUI re-query hits cooldown cleanly
            nowMs += 5_000L
            dlMtime = 3_000L
            diskDownloadFiles.add(
                ScannedFileRecord("/storage/emulated/0/Download/late_invoice.pdf", "/storage/emulated/0/Download", "late_invoice.pdf", "pdf", 8192L, dlMtime, false, AccessMode.NORMAL)
            )
            gate = CompletableDeferred()
            val notified = CompletableDeferred<Int>()
            PinpointDocumentsProvider.syncIndexForProviderQuery(
                indexManager = mgr,
                maxWaitMs = 60L,
                onDeferredChange = {
                    // Simulate DocumentsUI ForceLoadContentObserver re-querying provider immediately
                    PinpointDocumentsProvider.syncIndexForProviderQuery(indexManager = mgr)
                    notified.complete(db.search("late_invoice").items.size)
                }
            )
            assertEquals(0, db.search("late_invoice").items.size)
            gate!!.complete(Unit)

            val hitsAfterNotify = withTimeout(5_000L) { notified.await() }
            assertEquals(1, hitsAfterNotify)
            assertEquals(2, statCalls.get())
        }
    }
}
