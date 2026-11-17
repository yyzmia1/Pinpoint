package com.pocketeverything.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.pocketeverything.app.data.SettingsRepository
import com.pocketeverything.app.filter.FilterEngine
import com.pocketeverything.app.fs.FileSystemProvider
import com.pocketeverything.app.fs.ModeAvailability
import com.pocketeverything.app.fs.ScannedFileRecord
import com.pocketeverything.app.indexer.Tier1Phase
import com.pocketeverything.app.indexer.Tier2Phase
import com.pocketeverything.app.indexer.TwoTierIndexManager
import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.search.FileUpsertEntry
import com.pocketeverything.app.search.SqliteSearchDatabase
import com.pocketeverything.app.search.Tier2Status
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files

class TwoTierIndexManagerTest {

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return (data[key] as? Set<String>)?.toMutableSet() ?: defValues
        }
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private val removals = HashSet<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values?.toSet() }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) removals.add(key) }
            override fun clear() = apply { clearAll = true }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) data.clear()
                removals.forEach { data.remove(it) }
                pending.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class TestContext : ContextWrapper(null) {
        private val prefs = InMemorySharedPreferences()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    private class FakeProvider(
        override val mode: AccessMode
    ) : FileSystemProvider {
        var availability: ModeAvailability = ModeAvailability.Ready
        var fullTreeHandler: suspend (FilterEngine, suspend (List<ScannedFileRecord>) -> Unit) -> Int = { _, _ -> 0 }
        var statDirsHandler: (List<String>) -> Map<String, Long> = { emptyMap() }
        var statPathsHandler: (List<String>) -> Map<String, Long> = { emptyMap() }
        var listDirectHandler: suspend (List<String>, (String) -> Unit, suspend (List<ScannedFileRecord>) -> Unit) -> Int = { _, _, _ -> 0 }
        var scanDirsHandler: suspend (List<String>, (String) -> Unit, suspend (List<ScannedFileRecord>) -> Unit) -> Int = { _, _, _ -> 0 }
        var fileContents: MutableMap<String, String> = HashMap()
        var listDirectCallCount = 0

        override suspend fun checkAvailability(): ModeAvailability = availability

        override suspend fun scanFullTree(
            filterEngine: FilterEngine,
            onBatch: suspend (List<ScannedFileRecord>) -> Unit
        ): Int = fullTreeHandler(filterEngine, onBatch)

        override suspend fun scanDirectories(
            roots: List<String>,
            filterEngine: FilterEngine,
            onBatch: suspend (List<ScannedFileRecord>) -> Unit
        ): Int = scanDirsHandler(roots, {}, onBatch)

        override suspend fun scanDirectories(
            roots: List<String>,
            filterEngine: FilterEngine,
            onDirListed: (String) -> Unit,
            onBatch: suspend (List<ScannedFileRecord>) -> Unit
        ): Int = scanDirsHandler(roots, onDirListed, onBatch)

        override suspend fun listDirectChildren(
            dirs: List<String>,
            filterEngine: FilterEngine,
            onBatch: suspend (List<ScannedFileRecord>) -> Unit
        ): Int = listDirectChildren(dirs, filterEngine, {}, onBatch)

        override suspend fun listDirectChildren(
            dirs: List<String>,
            filterEngine: FilterEngine,
            onDirListed: (String) -> Unit,
            onBatch: suspend (List<ScannedFileRecord>) -> Unit
        ): Int {
            listDirectCallCount++
            return listDirectHandler(dirs, onDirListed, onBatch)
        }

        override suspend fun statDirectories(dirPaths: List<String>): Map<String, Long> = statDirsHandler(dirPaths)
        override suspend fun statPaths(paths: List<String>): Map<String, Long> = statPathsHandler(paths)

        override suspend fun <T> openFileStream(
            path: String,
            maxBytes: Long,
            block: suspend (InputStream) -> T
        ): T {
            val text = fileContents[path] ?: ""
            return ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)).use { block(it) }
        }

        override suspend fun copyFileTo(path: String, targetFile: File, maxBytes: Long): Boolean = false
    }

    @Test
    fun `root incremental sync is idempotent and does not repeat dirty rescan on rootSet`() = runBlocking {
        val dbFile = Files.createTempFile("pocket_mgr_root_", ".db").toFile().apply { deleteOnExit() }
        val ctx = TestContext()
        val settingsRepo = SettingsRepository(ctx)
        settingsRepo.updateAccessMode(AccessMode.ROOT)
        val provider = FakeProvider(AccessMode.ROOT)
        var rootMtime = 1000L
        val subMtime = 1000L

        provider.fullTreeHandler = { _, onBatch ->
            val batch = listOf(
                ScannedFileRecord("/storage/emulated", "/storage", "emulated", "", 0L, 500L, true, AccessMode.ROOT),
                ScannedFileRecord("/storage/emulated/0", "/storage/emulated", "0", "", 0L, rootMtime, true, AccessMode.ROOT),
                ScannedFileRecord("/storage/emulated/0/Download", "/storage/emulated/0", "Download", "", 0L, subMtime, true, AccessMode.ROOT),
                ScannedFileRecord("/storage/emulated/0/Download/note.bin", "/storage/emulated/0/Download", "note.bin", "bin", 12L, 1000L, false, AccessMode.ROOT)
            )
            onBatch(batch)
            batch.size
        }
        provider.statDirsHandler = { dirs ->
            dirs.mapNotNull { d ->
                when (d) {
                    "/storage/emulated/0" -> d to rootMtime
                    "/storage/emulated/0/Download" -> d to subMtime
                    else -> null
                }
            }.toMap()
        }
        provider.listDirectHandler = { dirs, onDirListed, onBatch ->
            if ("/storage/emulated/0" in dirs) {
                onDirListed("/storage/emulated/0")
                onBatch(
                    listOf(
                        ScannedFileRecord("/storage/emulated/0/Download", "/storage/emulated/0", "Download", "", 0L, subMtime, true, AccessMode.ROOT),
                        ScannedFileRecord("/storage/emulated/0/new.bin", "/storage/emulated/0", "new.bin", "bin", 32L, rootMtime, false, AccessMode.ROOT)
                    )
                )
                2
            } else 0
        }

        SqliteSearchDatabase(dbFile.absolutePath).use { db ->
            val mgr = TwoTierIndexManager(
                context = ctx,
                db = db,
                settingsRepo = settingsRepo,
                providerResolver = { provider },
                storageRootsResolver = { listOf("/storage/emulated/0") }
            )
            mgr.startFullScan(clearExisting = false)
            withTimeout(5000L) {
                mgr.progress.first { it.tier1Phase == Tier1Phase.IDLE && it.stats.totalEntries == 4L }
            }

            // Simulate root folder mtime bump on disk
            rootMtime = 2000L
            mgr.startQuickHotDirsRefresh().join()
            assertEquals(1, provider.listDirectCallCount)
            assertEquals(1, db.search("name:new.bin").items.size)

            // 2nd incremental sync with unchanged rootMtime must NOT re-scan /storage/emulated/0 again
            mgr.startQuickHotDirsRefresh().join()
            assertEquals(1, provider.listDirectCallCount)

            // Empty Tier-2 queue must NOT overwrite Tier-1 completion status message
            delay(150)
            assertTrue(
                "Expected Tier-1 summary to be preserved, got: ${mgr.progress.value.statusMessage}",
                mgr.progress.value.statusMessage.contains("增量")
            )
        }
    }

    @Test
    fun `failed or cancelled full rebuild preserves existing index and Tier-2 extracted content`() = runBlocking {
        val dbFile = Files.createTempFile("pocket_mgr_atomic_", ".db").toFile().apply { deleteOnExit() }
        val ctx = TestContext()
        val settingsRepo = SettingsRepository(ctx)
        settingsRepo.updateAccessMode(AccessMode.ROOT)
        val provider = FakeProvider(AccessMode.ROOT)

        SqliteSearchDatabase(dbFile.absolutePath).use { db ->
            val t0 = 1000L
            db.upsertFileBatch(
                listOf(
                    FileUpsertEntry("/storage/emulated/0/Download/keep.txt", "/storage/emulated/0/Download", "keep.txt", "txt", 128L, t0, false, AccessMode.ROOT, true, Tier2Status.PENDING)
                )
            )
            val id = db.fetchPendingTier2Tasks(5).single().fileId
            db.saveTier2Result(id, t0, Tier2Status.INDEXED, "重要备份正文内容")
            settingsRepo.markFullScanCompleted(t0)

            val mgr = TwoTierIndexManager(
                context = ctx,
                db = db,
                settingsRepo = settingsRepo,
                providerResolver = { provider },
                storageRootsResolver = { listOf("/storage/emulated/0") }
            )

            // 1. IPC bind failure / timeout even with clearExisting = true must preserve DB & Tier-2 body
            provider.fullTreeHandler = { _, _ -> throw IllegalStateException("RootService 绑定超时") }
            mgr.startFullScan(clearExisting = true)
            withTimeout(5000L) {
                mgr.progress.first { it.tier1Phase == Tier1Phase.IDLE && it.errorMessage != null }
            }
            assertEquals(1L, db.getStats().totalEntries)
            assertEquals(1L, db.getStats().indexedDocs)
            assertEquals(1, db.search("content:重要备份").items.size)

            // 2. Cancelled mid-scan must also preserve old entries & Tier-2 body
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            provider.fullTreeHandler = { _, _ ->
                started.complete(Unit)
                gate.await()
                0
            }
            mgr.startFullScan(clearExisting = true)
            withTimeout(5000L) { started.await() }
            mgr.cancelCurrentScan()
            withTimeout(5000L) {
                mgr.progress.first { it.tier1Phase == Tier1Phase.IDLE }
            }
            assertEquals(1L, db.getStats().totalEntries)
            assertEquals(1, db.search("content:重要备份").items.size)
        }
    }

    @Test
    fun `unmounted volume and unreadable File list null never wipe existing files`() = runBlocking {
        val dbFile = Files.createTempFile("pocket_mgr_guard_", ".db").toFile().apply { deleteOnExit() }
        val ctx = TestContext()
        val settingsRepo = SettingsRepository(ctx)
        settingsRepo.updateAccessMode(AccessMode.NORMAL)
        val provider = FakeProvider(AccessMode.NORMAL)

        SqliteSearchDatabase(dbFile.absolutePath).use { db ->
            val t0 = 1000L
            db.upsertFileBatch(
                listOf(
                    FileUpsertEntry("/storage/emulated", "/storage", "emulated", "", 0L, t0, true, AccessMode.ROOT, false, Tier2Status.NOT_APPLICABLE),
                    FileUpsertEntry("/storage/emulated/0/LockedDir", "/storage/emulated/0", "LockedDir", "", 0L, t0, true, AccessMode.NORMAL, false, Tier2Status.NOT_APPLICABLE),
                    FileUpsertEntry("/storage/emulated/0/LockedDir/secret.bin", "/storage/emulated/0/LockedDir", "secret.bin", "bin", 64L, t0, false, AccessMode.NORMAL, false, Tier2Status.NOT_APPLICABLE),
                    FileUpsertEntry("/storage/emulated/0/ClearedDir", "/storage/emulated/0", "ClearedDir", "", 0L, t0, true, AccessMode.NORMAL, false, Tier2Status.NOT_APPLICABLE),
                    FileUpsertEntry("/storage/emulated/0/ClearedDir/old.bin", "/storage/emulated/0/ClearedDir", "old.bin", "bin", 64L, t0, false, AccessMode.NORMAL, false, Tier2Status.NOT_APPLICABLE),
                    FileUpsertEntry("/storage/1234-5678/SdCardDir", "/storage/1234-5678", "SdCardDir", "", 0L, t0, true, AccessMode.NORMAL, false, Tier2Status.NOT_APPLICABLE),
                    FileUpsertEntry("/storage/1234-5678/SdCardDir/photo.jpg", "/storage/1234-5678/SdCardDir", "photo.jpg", "jpg", 64L, t0, false, AccessMode.NORMAL, false, Tier2Status.NOT_APPLICABLE)
                )
            )
            db.saveDirSnapshots(mapOf("/storage/emulated/0" to t0), AccessMode.NORMAL)
            settingsRepo.markFullScanCompleted(t0)

            // Primary storage is mounted, SD card /storage/1234-5678 is unmounted,
            // LockedDir & ClearedDir changed mtime.
            provider.statDirsHandler = {
                mapOf(
                    "/storage/emulated/0" to t0,
                    "/storage/emulated/0/LockedDir" to t0 + 500L,
                    "/storage/emulated/0/ClearedDir" to t0 + 500L
                )
            }
            // ClearedDir returns empty list (onDirListed called), LockedDir returns null (onDirListed NOT called)
            provider.listDirectHandler = { dirs, onDirListed, _ ->
                if ("/storage/emulated/0/ClearedDir" in dirs) {
                    onDirListed("/storage/emulated/0/ClearedDir")
                }
                0
            }

            val mgr = TwoTierIndexManager(
                context = ctx,
                db = db,
                settingsRepo = settingsRepo,
                providerResolver = { provider },
                storageRootsResolver = { listOf("/storage/emulated/0") }
            )
            mgr.startQuickHotDirsRefresh().join()

            // LockedDir child preserved because File.list() == null
            assertEquals(1, db.search("name:secret.bin").items.size)
            // ClearedDir child pruned because genuinely empty
            assertEquals(0, db.search("name:old.bin").items.size)
            // Unmounted SD card preserved
            assertEquals(1, db.search("name:photo.jpg").items.size)
        }
    }

    @Test
    fun `in-place doc edit syncs size and re-indexes body with accurate unit wording`() = runBlocking {
        val dbFile = Files.createTempFile("pocket_mgr_doc_", ".db").toFile().apply { deleteOnExit() }
        val ctx = TestContext()
        val settingsRepo = SettingsRepository(ctx)
        settingsRepo.updateAccessMode(AccessMode.NORMAL)
        val provider = FakeProvider(AccessMode.NORMAL)

        SqliteSearchDatabase(dbFile.absolutePath).use { db ->
            val t0 = 1000L
            val docPath = "/storage/emulated/0/Download/report.txt"
            db.upsertFileBatch(
                listOf(
                    FileUpsertEntry(docPath, "/storage/emulated/0/Download", "report.txt", "txt", 100L, t0, false, AccessMode.NORMAL, true, Tier2Status.PENDING)
                )
            )
            val id = db.fetchPendingTier2Tasks(5).single().fileId
            db.saveTier2Result(id, t0, Tier2Status.INDEXED, "旧版关键字内容")
            db.saveDirSnapshots(mapOf("/storage/emulated/0" to t0), AccessMode.NORMAL)
            settingsRepo.markFullScanCompleted(t0)

            provider.statDirsHandler = { mapOf("/storage/emulated/0" to t0) }
            provider.statPathsHandler = { mapOf(docPath to t0 + 2000L) }
            provider.listDirectHandler = { dirs, onDirListed, onBatch ->
                if ("/storage/emulated/0/Download" in dirs) {
                    onDirListed("/storage/emulated/0/Download")
                    onBatch(
                        listOf(
                            ScannedFileRecord(docPath, "/storage/emulated/0/Download", "report.txt", "txt", 512L, t0 + 2000L, false, AccessMode.NORMAL)
                        )
                    )
                    1
                } else 0
            }
            provider.fileContents[docPath] = "新版正文关键字"

            val mgr = TwoTierIndexManager(
                context = ctx,
                db = db,
                settingsRepo = settingsRepo,
                providerResolver = { provider },
                storageRootsResolver = { listOf("/storage/emulated/0") }
            )
            mgr.startQuickHotDirsRefresh().join()
            withTimeout(5000L) {
                mgr.progress.first {
                    it.tier1Phase == Tier1Phase.IDLE &&
                        it.tier2Phase == Tier2Phase.IDLE &&
                        it.tier2ProcessedCount >= 1 &&
                        it.statusMessage.startsWith("就绪")
                }
            }

            assertTrue(mgr.progress.value.statusMessage.contains("项 /"))
            assertEquals(0, db.search("content:旧版关键字").items.size)
            val updated = db.search("content:新版正文").items.single()
            assertEquals(512L, updated.size)
        }
    }
}
