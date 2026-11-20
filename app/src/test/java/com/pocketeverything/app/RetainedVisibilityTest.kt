package com.pocketeverything.app

import com.pocketeverything.app.analyzer.AppInfoResolver
import com.pocketeverything.app.analyzer.ResolvedAppMeta
import com.pocketeverything.app.analyzer.WizTreeEngine
import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.search.FileUpsertEntry
import com.pocketeverything.app.search.SqliteSearchDatabase
import com.pocketeverything.app.search.Tier2Status
import org.junit.Assert.*
import org.junit.Test

class RetainedVisibilityTest {
    @Test
    fun `visibility scopes FTS metadata recent children analyzer and cache without deleting records`() {
        var visibleMode = AccessMode.NORMAL.code
        val resolver = AppInfoResolver { pkg, fallback -> ResolvedAppMeta(pkg, fallback ?: pkg, true) }
        SqliteSearchDatabase(":memory:", { visibleMode }).use { db ->
            val parent = "/storage/emulated/0/Documents"
            db.upsertFileBatch(AccessMode.entries.map { mode ->
                FileUpsertEntry("$parent/needle${mode.code}.txt", parent, "needle${mode.code}.txt", "txt",
                    (mode.code + 1) * 100L, 1000, false, mode, true, Tier2Status.PENDING)
            })
            assertEquals(1, db.fetchPendingTier2Tasks(maxMode = AccessMode.NORMAL).size)
            assertEquals(2, db.fetchPendingTier2Tasks(maxMode = AccessMode.SHIZUKU).size)
            assertEquals(3, db.fetchPendingTier2Tasks(maxMode = AccessMode.ROOT).size)
            db.fetchPendingTier2Tasks().forEach { db.saveTier2Result(it.fileId, 1000, Tier2Status.INDEXED, "共同正文 needle") }
            for (query in listOf("", "needle", "name:needle*", "content:共同", "regex:needle", "ext:txt")) {
                assertEquals(query, 1, db.search(query).items.size)
            }
            assertEquals(1, db.listChildren(parent).size)
            assertEquals(1, db.queryTopLargeFilesForAnalyzer().size)
            val small = WizTreeEngine.buildFromDatabase(db, resolver, accessMode = AccessMode.NORMAL)
            assertEquals(100L, small.totalBytes)
            assertNotNull(WizTreeEngine.loadFromCache(db, resolver, AccessMode.NORMAL))
            visibleMode = AccessMode.ROOT.code
            assertNull(WizTreeEngine.loadFromCache(db, resolver, AccessMode.NORMAL))
            assertEquals(3, db.search("needle").items.size)
            val all = WizTreeEngine.buildFromDatabase(db, resolver, accessMode = AccessMode.ROOT)
            assertEquals(600L, all.totalBytes)
            assertEquals(3L, db.getStats().totalEntries)
            visibleMode = AccessMode.SHIZUKU.code
            assertEquals(2, db.search("content:共同").items.size)
            assertEquals(2, db.listChildren(parent).size)
            assertNull(WizTreeEngine.loadFromCache(db, resolver, AccessMode.SHIZUKU))
        }
    }

    @Test
    fun `full scan cleanup requires a confirmed listing and leaves inaccessible records intact`() {
        SqliteSearchDatabase(":memory:").use { db ->
            db.upsertFileBatch(listOf("/visible/gone.txt", "/denied/keep.txt").map { path ->
                FileUpsertEntry(path, path.substringBeforeLast('/'), path.substringAfterLast('/'), "txt",
                    10, 1000, false, AccessMode.NORMAL, false, Tier2Status.NOT_APPLICABLE)
            })
            db.pruneMissingEntries(emptySet()) { path, _ -> path.substringBeforeLast('/') == "/visible" }
            assertNull(db.getFileMetaByPath("/visible/gone.txt"))
            assertNotNull(db.getFileMetaByPath("/denied/keep.txt"))
        }
    }
}
