package com.pocketeverything.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.pocketeverything.app.data.SettingsRepository
import com.pocketeverything.app.filter.FilterEngine
import com.pocketeverything.app.filter.RootDataScope
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

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
        var statDirsHandler: suspend (List<String>) -> Map<String, Long> = { emptyMap() }
        var statPathsHandler: (List<String>) -> Map<String, Long> = { emptyMap() }
        var listDirectHandler: suspend (List<String>, (String) -> Unit, suspend (List<ScannedFileRecord>) -> Unit) -> Int = { _, _, _ -> 0 }
        var scanDirsHandler: suspend (List<String>, (String) -> Unit, suspend (List<ScannedFileRecord>) -> Unit) -> Int = { _, _, _ -> 0 }
        var fileContents: MutableMap<String, String> = HashMap()
        var listDirectCallCount = 0
        var statDirsCallCount = 0

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

        override suspend fun statDirectories(dirPaths: List<String>): Map<String, Long> {
            statDirsCallCount++
            return statDirsHandler(dirPaths)
        }
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
            withTimeout(5000L) {
                mgr.startFullScan(clearExisting = false).join()
            }
            assertEquals(4L, db.getStats().totalEntries)

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
        settingsRepo.setClearIndexOnModeSwitch(true)
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
            withTimeout(5000L) {
                mgr.startFullScan(clearExisting = true).join()
            }
            assertTrue(mgr.progress.value.errorMessage != null)
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
            val scanJob = mgr.startFullScan(clearExisting = true)
            withTimeout(5000L) { started.await() }
            mgr.cancelCurrentScan()
            withTimeout(5000L) {
                scanJob.join()
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

    @Test
    fun `legacy root_include_data migrates cleanly and root incremental sync tracks non-adb data subdirs`() = runBlocking {
        // 1. Legacy v1.4.2 prefs with root_include_data=false migrates /data into customExcludedPaths
        val legacyFalseCtx = TestContext()
        legacyFalseCtx.getSharedPreferences("pocket_everything_prefs", 0).edit()
            .putBoolean("root_include_data", false)
            .putString("custom_excludes", "/storage/emulated/0/Movies")
            .apply()
        val migratedFalseRepo = SettingsRepository(legacyFalseCtx)
        assertEquals(RootDataScope.EXCLUDE_ALL, migratedFalseRepo.settings.value.filterConfig.rootDataScope())
        assertTrue("/storage/emulated/0/Movies" in migratedFalseRepo.settings.value.filterConfig.customExcludedPaths)

        // 2. Legacy v1.4.2 prefs with root_include_data=true keeps /data unexcluded
        val legacyTrueCtx = TestContext()
        legacyTrueCtx.getSharedPreferences("pocket_everything_prefs", 0).edit()
            .putBoolean("root_include_data", true)
            .putString("custom_excludes", "")
            .apply()
        val migratedTrueRepo = SettingsRepository(legacyTrueCtx)
        assertEquals(RootDataScope.INCLUDE_ALL, migratedTrueRepo.settings.value.filterConfig.rootDataScope())

        // 3. Relaxing from EXCLUDE_ALL -> EXCLUDE_PRIVATE scans /data/misc and /data/adb, then incremental sync catches updates under /data/misc
        val dbFile = Files.createTempFile("pocket_data_inc_", ".db").toFile().apply { deleteOnExit() }
        val ctx = TestContext()
        val settingsRepo = SettingsRepository(ctx)
        settingsRepo.updateAccessMode(AccessMode.ROOT)
        settingsRepo.updateFilterConfig(settingsRepo.settings.value.filterConfig.withRootDataScope(RootDataScope.EXCLUDE_PRIVATE))

        val provider = FakeProvider(AccessMode.ROOT)
        var miscMtime = 1000L
        var includeNewWifiFile = false

        provider.scanDirsHandler = { roots, onDirListed, onBatch ->
            if ("/data" in roots) {
                onDirListed("/data")
                val records = listOf(
                    ScannedFileRecord("/data", "/", "data", "", 0L, 1000L, true, AccessMode.ROOT),
                    ScannedFileRecord("/data/misc", "/data", "misc", "", 0L, miscMtime, true, AccessMode.ROOT),
                    ScannedFileRecord("/data/misc/wifi.conf", "/data/misc", "wifi.conf", "conf", 120L, miscMtime, false, AccessMode.ROOT),
                    ScannedFileRecord("/data/adb", "/data", "adb", "", 0L, 1000L, true, AccessMode.ROOT),
                    ScannedFileRecord("/data/adb/mod.sh", "/data/adb", "mod.sh", "sh", 64L, 1000L, false, AccessMode.ROOT)
                )
                onBatch(records)
                records.size
            } else 0
        }
        provider.statDirsHandler = { dirs ->
            dirs.mapNotNull { d ->
                when (d) {
                    "/storage/emulated/0" -> d to 1000L
                    "/data" -> d to 1000L
                    "/data/misc" -> d to miscMtime
                    "/data/adb" -> d to 1000L
                    else -> null
                }
            }.toMap()
        }
        provider.listDirectHandler = { dirs, onDirListed, onBatch ->
            if ("/data/misc" in dirs && includeNewWifiFile) {
                onDirListed("/data/misc")
                val updatedList = listOf(
                    ScannedFileRecord("/data/misc/wifi.conf", "/data/misc", "wifi.conf", "conf", 120L, miscMtime, false, AccessMode.ROOT),
                    ScannedFileRecord("/data/misc/bluedroid.conf", "/data/misc", "bluedroid.conf", "conf", 80L, miscMtime, false, AccessMode.ROOT)
                )
                onBatch(updatedList)
                updatedList.size
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
            mgr.startDirectoriesRefresh(listOf("/data")).join()
            settingsRepo.markFullScanCompleted(1000L)
            assertEquals(1, db.search("name:wifi.conf").items.size)
            assertEquals(1, db.search("name:mod.sh").items.size)

            miscMtime = 2000L
            includeNewWifiFile = true
            mgr.startQuickHotDirsRefresh().join()
            assertEquals(1, db.search("name:bluedroid.conf").items.size)
        }
    }

    @Test
    fun `triggerForegroundSync enforces cooldown throttling and reuses active in-flight scan`() = runBlocking {
        val dbFile = Files.createTempFile("pocket_mgr_fg_throttle_", ".db").toFile().apply { deleteOnExit() }
        val ctx = TestContext()
        val settingsRepo = SettingsRepository(ctx)
        settingsRepo.updateAccessMode(AccessMode.NORMAL)
        val provider = FakeProvider(AccessMode.NORMAL)
        var nowMs = 10_000L
        var statGate: CompletableDeferred<Unit>? = null
        var statStarted: CompletableDeferred<Unit>? = null

        SqliteSearchDatabase(dbFile.absolutePath).use { db ->
            val t0 = 1000L
            db.upsertFileBatch(
                listOf(
                    FileUpsertEntry("/storage/emulated/0/Download", "/storage/emulated/0", "Download", "", 0L, t0, true, AccessMode.NORMAL, false, Tier2Status.NOT_APPLICABLE),
                    FileUpsertEntry("/storage/emulated/0/Download/a.bin", "/storage/emulated/0/Download", "a.bin", "bin", 32L, t0, false, AccessMode.NORMAL, false, Tier2Status.NOT_APPLICABLE)
                )
            )
            db.saveDirSnapshots(mapOf("/storage/emulated/0" to t0), AccessMode.NORMAL)
            settingsRepo.markFullScanCompleted(t0)

            provider.statDirsHandler = {
                statStarted?.complete(Unit)
                statGate?.await()
                mapOf(
                    "/storage/emulated/0" to t0,
                    "/storage/emulated/0/Download" to t0
                )
            }

            val mgr = TwoTierIndexManager(
                context = ctx,
                db = db,
                settingsRepo = settingsRepo,
                providerResolver = { provider },
                storageRootsResolver = { listOf("/storage/emulated/0") },
                clockMs = { nowMs }
            )

            // 1. First foreground sync executes immediately
            val first = mgr.triggerForegroundSync(minIntervalMs = 2_500L)
            assertNotNull(first)
            withTimeout(5_000L) { first!!.join() }
            assertEquals(1, provider.statDirsCallCount)

            // 2. Within cooldown (< 2500ms) -> throttled
            nowMs += 1_000L
            assertNull(mgr.triggerForegroundSync(minIntervalMs = 2_500L))
            nowMs += 1_499L
            assertNull(mgr.triggerForegroundSync(minIntervalMs = 2_500L))
            assertEquals(1, provider.statDirsCallCount)

            // 3. Exactly at cooldown boundary (2500ms) -> executes again
            nowMs += 1L
            val second = mgr.triggerForegroundSync(minIntervalMs = 2_500L)
            assertNotNull(second)
            withTimeout(5_000L) { second!!.join() }
            assertEquals(2, provider.statDirsCallCount)

            // 4. Concurrent call while scan is active reuses the in-flight job without starting a duplicate
            nowMs += 5_000L
            statStarted = CompletableDeferred()
            statGate = CompletableDeferred()
            val activeJob = mgr.triggerForegroundSync(minIntervalMs = 2_500L)
            assertNotNull(activeJob)
            withTimeout(5_000L) { statStarted!!.await() }

            val concurrentJob = mgr.triggerForegroundSync(minIntervalMs = 0L)
            assertSame(activeJob, concurrentJob)
            assertEquals(3, provider.statDirsCallCount)

            statGate!!.complete(Unit)
            withTimeout(5_000L) { activeJob!!.join() }
            assertEquals(3, provider.statDirsCallCount)
        }
    }

    @Test
    fun `syncGeneration increments on mutation even with net-zero count change and stays stable on no-op`() = runBlocking {
        val dbFile = Files.createTempFile("pocket_mgr_sync_gen_", ".db").toFile().apply { deleteOnExit() }
        val ctx = TestContext()
        val settingsRepo = SettingsRepository(ctx)
        settingsRepo.updateAccessMode(AccessMode.NORMAL)
        val provider = FakeProvider(AccessMode.NORMAL)
        var downloadMtime = 1000L
        var fileInDownload = "old.bin"

        provider.fullTreeHandler = { _, onBatch ->
            val batch = listOf(
                ScannedFileRecord("/storage/emulated/0", "/storage/emulated", "0", "", 0L, 1000L, true, AccessMode.NORMAL),
                ScannedFileRecord("/storage/emulated/0/Download", "/storage/emulated/0", "Download", "", 0L, downloadMtime, true, AccessMode.NORMAL),
                ScannedFileRecord("/storage/emulated/0/Download/$fileInDownload", "/storage/emulated/0/Download", fileInDownload, "bin", 16L, 1000L, false, AccessMode.NORMAL)
            )
            onBatch(batch)
            batch.size
        }
        provider.statDirsHandler = {
            mapOf(
                "/storage/emulated/0" to 1000L,
                "/storage/emulated/0/Download" to downloadMtime
            )
        }
        provider.listDirectHandler = { dirs, onDirListed, onBatch ->
            if ("/storage/emulated/0/Download" in dirs) {
                onDirListed("/storage/emulated/0/Download")
                onBatch(
                    listOf(
                        ScannedFileRecord("/storage/emulated/0/Download/$fileInDownload", "/storage/emulated/0/Download", fileInDownload, "bin", 24L, downloadMtime, false, AccessMode.NORMAL)
                    )
                )
                1
            } else 0
        }
        provider.scanDirsHandler = { roots, onDirListed, onBatch ->
            if ("/storage/emulated/0/Download" in roots) {
                onDirListed("/storage/emulated/0/Download")
                onBatch(
                    listOf(
                        ScannedFileRecord("/storage/emulated/0/Download/$fileInDownload", "/storage/emulated/0/Download", fileInDownload, "bin", 24L, downloadMtime, false, AccessMode.NORMAL),
                        ScannedFileRecord("/storage/emulated/0/Download/note.txt", "/storage/emulated/0/Download", "note.txt", "txt", 32L, downloadMtime, false, AccessMode.NORMAL)
                    )
                )
                2
            } else 0
        }
        provider.fileContents["/storage/emulated/0/Download/note.txt"] = "增量同步测试正文"

        SqliteSearchDatabase(dbFile.absolutePath).use { db ->
            val mgr = TwoTierIndexManager(
                context = ctx,
                db = db,
                settingsRepo = settingsRepo,
                providerResolver = { provider },
                storageRootsResolver = { listOf("/storage/emulated/0") }
            )
            assertEquals(0L, mgr.progress.value.syncGeneration)

            // 1. Full scan mutates -> increments
            withTimeout(5_000L) { mgr.startFullScan().join() }
            val genAfterFull = mgr.progress.value.syncGeneration
            assertTrue("Expected syncGeneration > 0 after full scan", genAfterFull > 0L)
            assertEquals(3L, db.getStats().totalEntries)

            // 2. No-op quick sync -> syncGeneration unchanged
            withTimeout(5_000L) { mgr.startQuickHotDirsRefresh().join() }
            assertEquals(genAfterFull, mgr.progress.value.syncGeneration)

            // 3. Replace 1 file with 1 new file (totalEntries stays 3L!) -> syncGeneration MUST increment
            downloadMtime = 2000L
            fileInDownload = "replaced.bin"
            withTimeout(5_000L) { mgr.startQuickHotDirsRefresh().join() }
            assertEquals(3L, db.getStats().totalEntries)
            assertEquals(0, db.search("name:old.bin").items.size)
            assertEquals(1, db.search("name:replaced.bin").items.size)
            val genAfterSwap = mgr.progress.value.syncGeneration
            assertEquals(genAfterFull + 1L, genAfterSwap)

            // 4. Directory refresh adds note.txt -> increments (and Tier-2 extracts body -> increments again)
            withTimeout(5_000L) { mgr.startDirectoriesRefresh(listOf("/storage/emulated/0/Download")).join() }
            withTimeout(5_000L) {
                mgr.progress.first {
                    it.tier1Phase == Tier1Phase.IDLE &&
                        it.tier2Phase == Tier2Phase.IDLE &&
                        it.stats.indexedDocs >= 1L
                }
            }
            val genAfterDirAndTier2 = mgr.progress.value.syncGeneration
            assertTrue("Expected syncGeneration to advance after dir refresh & tier2", genAfterDirAndTier2 > genAfterSwap)

            // 5. Filter prune removing /storage/emulated/0/Download -> increments
            val cfg = settingsRepo.settings.value.filterConfig.copy(
                customExcludedPaths = listOf("/storage/emulated/0/Download")
            )
            settingsRepo.updateFilterConfig(cfg)
            val summary = mgr.pruneByCurrentFilter()
            assertTrue(summary.removedEntries > 0)
            assertEquals(genAfterDirAndTier2 + 1L, mgr.progress.value.syncGeneration)
        }
    }

    @Test
    fun `triggerForegroundSync runs full scan when DB is empty and stays silent when unavailable`() = runBlocking {
        val dbFile = Files.createTempFile("pocket_mgr_fg_empty_", ".db").toFile().apply { deleteOnExit() }
        val ctx = TestContext()
        val settingsRepo = SettingsRepository(ctx)
        settingsRepo.updateAccessMode(AccessMode.NORMAL)
        val provider = FakeProvider(AccessMode.NORMAL)
        provider.availability = ModeAvailability.NeedsPermission("缺少存储权限")
        provider.fullTreeHandler = { _, onBatch ->
            onBatch(
                listOf(
                    ScannedFileRecord("/storage/emulated/0", "/storage/emulated", "0", "", 0L, 1000L, true, AccessMode.NORMAL),
                    ScannedFileRecord("/storage/emulated/0/doc.pdf", "/storage/emulated/0", "doc.pdf", "pdf", 128L, 1000L, false, AccessMode.NORMAL)
                )
            )
            2
        }
        var nowMs = 1_000L

        SqliteSearchDatabase(dbFile.absolutePath).use { db ->
            val mgr = TwoTierIndexManager(
                context = ctx,
                db = db,
                settingsRepo = settingsRepo,
                providerResolver = { provider },
                storageRootsResolver = { listOf("/storage/emulated/0") },
                clockMs = { nowMs }
            )

            // Unavailable -> stays silent (no error banner)
            val j1 = mgr.triggerForegroundSync(minIntervalMs = 2_500L)
            assertNotNull(j1)
            withTimeout(5_000L) { j1!!.join() }
            assertNull(mgr.progress.value.errorMessage)
            assertEquals(0L, db.getStats().totalEntries)

            // Granted permission + after cooldown -> runs full scan on empty DB
            provider.availability = ModeAvailability.Ready
            nowMs += 3_000L
            val j2 = mgr.triggerForegroundSync(minIntervalMs = 2_500L)
            assertNotNull(j2)
            withTimeout(5_000L) { j2!!.join() }
            assertEquals(2L, db.getStats().totalEntries)
            assertTrue(mgr.progress.value.syncGeneration >= 1L)
        }
    }

    @Test
    fun `triggerForegroundSync maxWaitMs waits for fast sync and invokes onChanged only on deferred mutation`() = runBlocking {
        val dbFile = Files.createTempFile("pocket_mgr_fg_wait_", ".db").toFile().apply { deleteOnExit() }
        val ctx = TestContext()
        val settingsRepo = SettingsRepository(ctx)
        settingsRepo.updateAccessMode(AccessMode.NORMAL)
        val provider = FakeProvider(AccessMode.NORMAL)
        var nowMs = 1_000L
        var dirMtime = 1000L
        val diskFiles = mutableListOf(
            ScannedFileRecord("/storage/emulated/0/Download/init.pdf", "/storage/emulated/0/Download", "init.pdf", "pdf", 64L, 1000L, false, AccessMode.NORMAL)
        )
        var slowGate: CompletableDeferred<Unit>? = null

        SqliteSearchDatabase(dbFile.absolutePath).use { db ->
            db.upsertFileBatch(
                listOf(
                    FileUpsertEntry("/storage/emulated/0/Download", "/storage/emulated/0", "Download", "", 0L, dirMtime, true, AccessMode.NORMAL, false, Tier2Status.NOT_APPLICABLE),
                    FileUpsertEntry("/storage/emulated/0/Download/init.pdf", "/storage/emulated/0/Download", "init.pdf", "pdf", 64L, 1000L, false, AccessMode.NORMAL, false, Tier2Status.NOT_APPLICABLE)
                )
            )
            db.saveDirSnapshots(mapOf("/storage/emulated/0" to 1000L), AccessMode.NORMAL)
            settingsRepo.markFullScanCompleted(1000L)

            provider.statDirsHandler = {
                slowGate?.await()
                mapOf(
                    "/storage/emulated/0" to 1000L,
                    "/storage/emulated/0/Download" to dirMtime
                )
            }
            provider.listDirectHandler = { dirs, onDirListed, onBatch ->
                if ("/storage/emulated/0/Download" in dirs) {
                    onDirListed("/storage/emulated/0/Download")
                    onBatch(diskFiles.toList())
                    diskFiles.size
                } else 0
            }

            val mgr = TwoTierIndexManager(
                context = ctx,
                db = db,
                settingsRepo = settingsRepo,
                providerResolver = { provider },
                storageRootsResolver = { listOf("/storage/emulated/0") },
                clockMs = { nowMs }
            )

            // 1. Fast sync finishes within maxWaitMs: newly created file immediately visible, deferred onChanged NOT called
            dirMtime = 2000L
            diskFiles.add(
                ScannedFileRecord("/storage/emulated/0/Download/fresh.pdf", "/storage/emulated/0/Download", "fresh.pdf", "pdf", 128L, dirMtime, false, AccessMode.NORMAL)
            )
            val fastCallbacks = AtomicInteger(0)
            val fastJob = mgr.triggerForegroundSync(
                minIntervalMs = 2_500L,
                maxWaitMs = 5_000L,
                onChanged = { fastCallbacks.incrementAndGet() }
            )
            assertNotNull(fastJob)
            assertEquals(1, db.search("name:fresh.pdf").items.size)
            assertEquals(0, fastCallbacks.get())

            // 2. Slow sync exceeds maxWaitMs and mutates DB: returns early, fires onChanged once completed, and cooldown stops re-entry loop
            nowMs += 5_000L
            dirMtime = 3000L
            diskFiles.add(
                ScannedFileRecord("/storage/emulated/0/Download/delayed.pdf", "/storage/emulated/0/Download", "delayed.pdf", "pdf", 256L, dirMtime, false, AccessMode.NORMAL)
            )
            slowGate = CompletableDeferred()
            val deferredFired = CompletableDeferred<Boolean>()
            val slowJob = mgr.triggerForegroundSync(
                minIntervalMs = 2_500L,
                maxWaitMs = 60L,
                onChanged = {
                    val reTrigger = mgr.triggerForegroundSync(minIntervalMs = 2_500L)
                    deferredFired.complete(reTrigger == null)
                }
            )
            assertNotNull(slowJob)
            assertEquals(0, db.search("name:delayed.pdf").items.size)

            slowGate!!.complete(Unit)
            val reTriggerWasThrottled = withTimeout(5_000L) { deferredFired.await() }
            assertTrue("Re-query inside deferred onChanged must hit cooldown to prevent refresh loops", reTriggerWasThrottled)
            assertEquals(1, db.search("name:delayed.pdf").items.size)

            // 3. Slow sync exceeds maxWaitMs with NO changes: onChanged must NOT be invoked
            nowMs += 5_000L
            slowGate = CompletableDeferred()
            val noOpCallbacks = AtomicInteger(0)
            val noOpJob = mgr.triggerForegroundSync(
                minIntervalMs = 2_500L,
                maxWaitMs = 60L,
                onChanged = { noOpCallbacks.incrementAndGet() }
            )
            assertNotNull(noOpJob)
            slowGate!!.complete(Unit)
            withTimeout(5_000L) { noOpJob!!.join() }
            assertEquals(0, noOpCallbacks.get())
        }
    }

    @Test
    fun `root stage3 verifies remaining slash partitions on reboot or manual sync while normal foreground skips them`() = runBlocking {
        val dbFile = Files.createTempFile("pocket_mgr_stage3_", ".db").toFile().apply { deleteOnExit() }
        val ctx = TestContext()
        val settingsRepo = SettingsRepository(ctx)
        settingsRepo.updateAccessMode(AccessMode.ROOT)
        settingsRepo.setLastRootOtherSyncBootCount(10)
        val provider = FakeProvider(AccessMode.ROOT)

        var currentBoot = 10
        var nowMs = 1_000L
        val stattedDirs = mutableListOf<String>()
        var systemPrivMtime = 1000L
        var includeModuleOverlay = false

        SqliteSearchDatabase(dbFile.absolutePath).use { db ->
            db.upsertFileBatch(
                listOf(
                    FileUpsertEntry("/storage/emulated/0", "/storage/emulated", "0", "", 0L, 1000L, true, AccessMode.ROOT, false, Tier2Status.NOT_APPLICABLE),
                    FileUpsertEntry("/system", "/", "system", "", 0L, 1000L, true, AccessMode.ROOT, false, Tier2Status.NOT_APPLICABLE),
                    FileUpsertEntry("/system/priv-app", "/system", "priv-app", "", 0L, 1000L, true, AccessMode.ROOT, false, Tier2Status.NOT_APPLICABLE),
                    FileUpsertEntry("/system/priv-app/Base.apk", "/system/priv-app", "Base.apk", "apk", 500L, 1000L, false, AccessMode.ROOT, false, Tier2Status.NOT_APPLICABLE)
                )
            )
            db.saveDirSnapshots(
                mapOf("/storage/emulated/0" to 1000L, "/" to 1000L),
                AccessMode.ROOT
            )
            settingsRepo.markFullScanCompleted(1000L)

            provider.statDirsHandler = { dirs ->
                stattedDirs.addAll(dirs)
                buildMap {
                    for (d in dirs) {
                        when (d) {
                            "/storage/emulated/0" -> put(d, 1000L)
                            "/" -> put(d, 1000L)
                            "/system" -> put(d, 1000L)
                            "/system/priv-app" -> put(d, systemPrivMtime)
                        }
                    }
                }
            }
            provider.listDirectHandler = { dirs, onDirListed, onBatch ->
                if ("/system/priv-app" in dirs) {
                    onDirListed("/system/priv-app")
                    val rows = buildList {
                        add(ScannedFileRecord("/system/priv-app/Base.apk", "/system/priv-app", "Base.apk", "apk", 500L, 1000L, false, AccessMode.ROOT))
                        if (includeModuleOverlay) {
                            add(ScannedFileRecord("/system/priv-app/OverlayMod.apk", "/system/priv-app", "OverlayMod.apk", "apk", 2048L, systemPrivMtime, false, AccessMode.ROOT))
                        }
                    }
                    onBatch(rows)
                    rows.size
                } else 0
            }

            val mgr = TwoTierIndexManager(
                context = ctx,
                db = db,
                settingsRepo = settingsRepo,
                providerResolver = { provider },
                storageRootsResolver = { listOf("/storage/emulated/0") },
                clockMs = { nowMs },
                bootCountProvider = { currentBoot }
            )

            // 1. Same boot session (bootCount == 10): foreground sync must NOT stat /system/priv-app
            val fgJob = mgr.triggerForegroundSync(minIntervalMs = 0L)
            assertNotNull(fgJob)
            withTimeout(5_000L) { fgJob!!.join() }
            assertFalse("Normal same-boot foreground sync should skip /system", "/system/priv-app" in stattedDirs)

            // 2. Device reboots (bootCount 10 -> 11) and Root module injects OverlayMod.apk into /system/priv-app
            stattedDirs.clear()
            nowMs += 10_000L
            currentBoot = 11
            systemPrivMtime = 2000L
            includeModuleOverlay = true

            val postRebootJob = mgr.triggerForegroundSync(minIntervalMs = 0L)
            assertNotNull(postRebootJob)
            withTimeout(5_000L) { postRebootJob!!.join() }
            assertTrue("Post-reboot sync must run Stage 3 on /system/priv-app", "/system/priv-app" in stattedDirs)
            assertEquals(1, db.search("name:OverlayMod.apk").items.size)
            assertEquals(11, settingsRepo.getLastRootOtherSyncBootCount())

            // 3. Subsequent foreground sync on same boot 11 skips /system again
            stattedDirs.clear()
            nowMs += 10_000L
            val fgAfterReboot = mgr.triggerForegroundSync(minIntervalMs = 0L)
            assertNotNull(fgAfterReboot)
            withTimeout(5_000L) { fgAfterReboot!!.join() }
            assertFalse("After reboot baseline is recorded, foreground sync skips /system again", "/system/priv-app" in stattedDirs)

            // 4. Explicit manual sync (includeRootOther = true) always runs Stage 3 even within same boot
            stattedDirs.clear()
            mgr.startQuickHotDirsRefresh(includeRootOther = true).join()
            assertTrue("Manual quick sync must verify /system/priv-app in Stage 3", "/system/priv-app" in stattedDirs)
        }
    }

    @Test
    fun `mode preferences persist and whitelist migration leaves existing exclusions unchanged`() {
        val ctx = TestContext()
        val first = SettingsRepository(ctx)
        assertFalse(first.settings.value.clearIndexOnModeSwitch)
        assertTrue(first.settings.value.showRetainedModeRecords)
        assertTrue(first.settings.value.filterConfig.customIncludedPaths.isEmpty())
        val blacklist = first.settings.value.filterConfig.customExcludedPaths
        first.setClearIndexOnModeSwitch(true)
        first.setShowRetainedModeRecords(false)
        first.updateFilterConfig(first.settings.value.filterConfig.copy(customIncludedPaths = listOf("/storage/emulated/0/Documents/")))
        val restored = SettingsRepository(ctx).settings.value
        assertTrue(restored.clearIndexOnModeSwitch)
        assertFalse(restored.showRetainedModeRecords)
        assertEquals(blacklist, restored.filterConfig.customExcludedPaths)
        assertEquals(listOf("/storage/emulated/0/Documents"), restored.filterConfig.customIncludedPaths)
    }

    @Test
    fun `root records and body survive downgrade subsequent full scans and return until cleanup enabled`() = runBlocking {
        val ctx = TestContext()
        val repo = SettingsRepository(ctx)
        repo.updateAccessMode(AccessMode.ROOT)
        val normal = FakeProvider(AccessMode.NORMAL)
        normal.fullTreeHandler = { _, emit ->
            emit(listOf(ScannedFileRecord("/storage/emulated/0/visible.txt", "/storage/emulated/0", "visible.txt", "txt", 10, 1000, false, AccessMode.NORMAL)))
            1
        }
        val root = FakeProvider(AccessMode.ROOT)
        SqliteSearchDatabase(":memory:").use { db ->
            db.upsertFileBatch(listOf(FileUpsertEntry("/data/private.txt", "/data", "private.txt", "txt", 10, 1000,
                false, AccessMode.ROOT, true, Tier2Status.PENDING)))
            val id = db.fetchPendingTier2Tasks().single().fileId
            db.saveTier2Result(id, 1000, Tier2Status.INDEXED, "retained secret body")
            val manager = TwoTierIndexManager(ctx, db, repo, { if (it == AccessMode.ROOT) root else normal },
                storageRootsResolver = { listOf("/storage/emulated/0") })
            manager.switchMode(AccessMode.NORMAL).join()
            assertEquals(1, db.search("content:secret").items.size)
            manager.startFullScan().join()
            assertEquals(1, db.search("content:secret").items.size)
            manager.switchMode(AccessMode.SHIZUKU).join()
            assertNotNull(db.getFileMetaByPath("/data/private.txt"))
            manager.switchMode(AccessMode.ROOT).join()
            assertEquals(1, db.search("content:secret").items.size)
            repo.setClearIndexOnModeSwitch(true)
            manager.switchMode(AccessMode.NORMAL).join()
            assertNull(db.getFileMetaByPath("/data/private.txt"))
        }
    }
}
