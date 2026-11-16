package com.pocketeverything.app

import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.search.FileUpsertEntry
import com.pocketeverything.app.search.QuickScope
import com.pocketeverything.app.search.SqliteSearchDatabase
import com.pocketeverything.app.search.Tier2Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SqliteSearchDatabaseAndBenchmarkTest {

    @Test
    fun `functional end-to-end search supports CJK substrings syntax and highlighting`() {
        val dbFile = Files.createTempFile("pocket_func_", ".db").toFile()
        dbFile.deleteOnExit()
        val now = System.currentTimeMillis()

        SqliteSearchDatabase(dbFile.absolutePath).use { db ->
            val entries = listOf(
                FileUpsertEntry(
                    path = "/storage/emulated/0/Download/2026年采购合同补充协议.docx",
                    parentPath = "/storage/emulated/0/Download",
                    name = "2026年采购合同补充协议.docx",
                    ext = "docx",
                    size = 2 * 1024 * 1024L,
                    mtime = now,
                    isDir = false,
                    accessMode = AccessMode.NORMAL,
                    needsTier2 = true,
                    initialTier2Status = Tier2Status.PENDING
                ),
                FileUpsertEntry(
                    path = "/storage/emulated/0/Documents/税务发票草稿.pdf",
                    parentPath = "/storage/emulated/0/Documents",
                    name = "税务发票草稿.pdf",
                    ext = "pdf",
                    size = 500_000L,
                    mtime = now - 86_400_000L * 10,
                    isDir = false,
                    accessMode = AccessMode.SHIZUKU,
                    needsTier2 = true,
                    initialTier2Status = Tier2Status.PENDING
                ),
                FileUpsertEntry(
                    path = "/storage/emulated/0/Documents/研发会议纪要.md",
                    parentPath = "/storage/emulated/0/Documents",
                    name = "研发会议纪要.md",
                    ext = "md",
                    size = 18_000L,
                    mtime = now,
                    isDir = false,
                    accessMode = AccessMode.NORMAL,
                    needsTier2 = true,
                    initialTier2Status = Tier2Status.PENDING
                ),
                FileUpsertEntry(
                    path = "/storage/emulated/0/Documents/ArchiveFolder",
                    parentPath = "/storage/emulated/0/Documents",
                    name = "ArchiveFolder",
                    ext = "",
                    size = 0L,
                    mtime = now,
                    isDir = true,
                    accessMode = AccessMode.NORMAL,
                    needsTier2 = false,
                    initialTier2Status = Tier2Status.NOT_APPLICABLE
                )
            )
            db.upsertFileBatch(entries)
            val pending = db.fetchPendingTier2Tasks(10)
            assertEquals(3, pending.size)

            db.saveTier2Result(
                pending.first { it.name.contains("采购合同") }.fileId,
                now,
                Tier2Status.INDEXED,
                "甲乙双方约定2026年交付设备并设立履约违约金条款，若延迟按每日万分之五结算，比例为15%。"
            )
            db.saveTier2Result(
                pending.first { it.name.contains("税务发票") }.fileId,
                now - 86_400_000L * 10,
                Tier2Status.INDEXED,
                "增值税专用发票开票清单明细汇总。"
            )
            db.saveTier2Result(
                pending.first { it.name.contains("研发会议纪要") }.fileId,
                now,
                Tier2Status.INDEXED,
                "会议讨论了本地全盘搜索引擎的中文N-gram索引方案与增值税报销流程。"
            )

            // 1. Arbitrary Chinese substring in filename ("同补充")
            val r1 = db.search("同补充")
            assertEquals(1, r1.items.size)
            assertEquals("2026年采购合同补充协议.docx", r1.items[0].name)

            // 2. Single Chinese character in document body ("税")
            val r2 = db.search("content:税")
            assertEquals(2, r2.items.size)
            assertTrue(r2.items.all { it.snippets.isNotEmpty() })

            // 3. Boolean syntax + size + exclusion
            val r3 = db.search("(合同 | 发票) -草稿 size:>1m")
            assertEquals(1, r3.items.size)
            assertEquals("2026年采购合同补充协议.docx", r3.items[0].name)

            // 4. Mixed ASCII + CJK ("2026年")
            val r4 = db.search("2026年")
            assertEquals(1, r4.items.size)

            // 5. QuickScope PDF / FILES / FOLDERS
            val r5 = db.search("税", quickScope = QuickScope.PDF)
            assertEquals(1, r5.items.size)
            assertEquals("税务发票草稿.pdf", r5.items[0].name)

            val onlyFolders = db.search("", quickScope = QuickScope.FOLDERS)
            assertEquals(1, onlyFolders.items.size)
            assertEquals("ArchiveFolder", onlyFolders.items[0].name)

            val onlyFiles = db.search("", quickScope = QuickScope.FILES)
            assertEquals(3, onlyFiles.items.size)
            assertTrue(onlyFiles.items.none { it.isDir })

            // 6. Empty query toggle
            val hiddenEmpty = db.search("", quickScope = QuickScope.ALL, showRecentOnEmpty = false)
            assertTrue(hiddenEmpty.items.isEmpty())
        }
    }

    @Test
    fun `strict wildcards, strict regex, and SQL metadata pushdown behave accurately`() {
        val dbFile = Files.createTempFile("pocket_regex_", ".db").toFile()
        dbFile.deleteOnExit()
        val now = System.currentTimeMillis()

        SqliteSearchDatabase(dbFile.absolutePath).use { db ->
            val items = mutableListOf(
                FileUpsertEntry("/storage/emulated/0/Download/2026_alpha.pdf", "/storage/emulated/0/Download", "2026_alpha.pdf", "pdf", 1024L, now, false, AccessMode.NORMAL, true, Tier2Status.PENDING),
                FileUpsertEntry("/storage/emulated/0/Download/old_2026_alpha.pdf", "/storage/emulated/0/Download", "old_2026_alpha.pdf", "pdf", 1024L, now, false, AccessMode.NORMAL, true, Tier2Status.PENDING),
                FileUpsertEntry("/storage/emulated/0/Download/2026_alpha.pdf.bak", "/storage/emulated/0/Download", "2026_alpha.pdf.bak", "bak", 1024L, now, false, AccessMode.NORMAL, false, Tier2Status.NOT_APPLICABLE),
                FileUpsertEntry("/storage/emulated/0/Download/BakaSU_v4.2.apk", "/storage/emulated/0/Download", "BakaSU_v4.2.apk", "apk", 9_000_000L, now, false, AccessMode.NORMAL, false, Tier2Status.NOT_APPLICABLE),
                FileUpsertEntry("/storage/emulated/0/Download/report_2026_v2.md", "/storage/emulated/0/Download", "report_2026_v2.md", "md", 2048L, now, false, AccessMode.NORMAL, true, Tier2Status.PENDING)
            )
            // Add 4500 decoy .jpg files containing "2026" to verify ext:pdf pushdown isn't starved by LIMIT 4000
            for (i in 0 until 4500) {
                items.add(
                    FileUpsertEntry(
                        "/storage/emulated/0/DCIM/IMG_2026_$i.jpg",
                        "/storage/emulated/0/DCIM",
                        "IMG_2026_$i.jpg",
                        "jpg",
                        2048L,
                        now - i,
                        false,
                        AccessMode.NORMAL,
                        false,
                        Tier2Status.NOT_APPLICABLE
                    )
                )
            }
            db.upsertFileBatch(items)
            val mdTask = db.fetchPendingTier2Tasks(10).first { it.name == "report_2026_v2.md" }
            db.saveTier2Result(mdTask.fileId, mdTask.mtime, Tier2Status.INDEXED, "ErrorCode: ERR_503 at line 42")

            // 1. Strict anchored wildcard: `2026*.pdf` must NOT match `old_2026_alpha.pdf` or `2026_alpha.pdf.bak`
            val w1 = db.search("2026*.pdf")
            assertEquals(listOf("2026_alpha.pdf"), w1.items.map { it.name })

            // 2. Wildcard prefix matching across tokenized word `bakasu`
            val w2 = db.search("Baka*.apk")
            assertEquals(listOf("BakaSU_v4.2.apk"), w2.items.map { it.name })

            // 3. Extension wildcard `*.apk`
            val w3 = db.search("*.apk")
            assertEquals(listOf("BakaSU_v4.2.apk"), w3.items.map { it.name })

            // 4. Metadata pushdown: `ext:pdf 2026` must find both pdfs despite 4500 `2026` jpg decoys
            val m1 = db.search("ext:pdf 2026")
            assertEquals(2, m1.items.size)
            assertTrue(m1.items.all { it.ext == "pdf" })

            // 5. Strict regex on filename and body
            val rx1 = db.search("""name:regex:^2026_[a-z]+\.pdf$""")
            assertEquals(listOf("2026_alpha.pdf"), rx1.items.map { it.name })

            val rx2 = db.search("""content:cregex:ERR_\d{3}""")
            assertEquals(listOf("report_2026_v2.md"), rx2.items.map { it.name })
            assertTrue(rx2.items[0].snippets.isNotEmpty())

            val rx3 = db.search("""/^Baka[A-Z]{2}_v\d+\.\d+\.apk$/c""")
            assertEquals(listOf("BakaSU_v4.2.apk"), rx3.items.map { it.name })
        }
    }

    @Test
    fun `benchmark 100k files and 2k Chinese documents achieve millisecond query latency`() {
        val dbFile = Files.createTempFile("pocket_bench_", ".db").toFile()
        dbFile.deleteOnExit()
        val walFile = java.io.File("${dbFile.absolutePath}-wal")
        val shmFile = java.io.File("${dbFile.absolutePath}-shm")
        walFile.deleteOnExit()
        shmFile.deleteOnExit()

        val baseTime = System.currentTimeMillis()
        SqliteSearchDatabase(dbFile.absolutePath).use { db ->
            val batch = ArrayList<FileUpsertEntry>(2000)
            val totalFiles = 100_000
            for (i in 0 until totalFiles) {
                val isDoc = i < 2000
                val dirIdx = i / 100
                val parent = if (i % 5 == 0) {
                    "/storage/emulated/0/Android/data/com.example.app/Download/dir_$dirIdx"
                } else {
                    "/storage/emulated/0/Documents/project_$dirIdx"
                }
                val name = when {
                    i == 777 -> "2026年华东区采购合同补充协议_终版.docx"
                    i == 888 -> "专票税务核算台账.pdf"
                    isDoc -> "文档资料_${i}_年度预算报告_${if (i % 7 == 0) "合同" else "纪要"}.docx"
                    else -> "资源文件_${i}_照片_${dirIdx}.jpg"
                }
                val ext = name.substringAfterLast('.')
                batch.add(
                    FileUpsertEntry(
                        path = "$parent/$name",
                        parentPath = parent,
                        name = name,
                        ext = ext,
                        size = 1024L * ((i % 500) + 1),
                        mtime = baseTime - i * 1000L,
                        isDir = false,
                        accessMode = AccessMode.NORMAL,
                        needsTier2 = isDoc,
                        initialTier2Status = if (isDoc) Tier2Status.PENDING else Tier2Status.NOT_APPLICABLE
                    )
                )
                if (batch.size >= 2000) {
                    db.upsertFileBatch(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) db.upsertFileBatch(batch)

            val pending = db.fetchPendingTier2Tasks(2500)
            assertEquals(2000, pending.size)
            for ((idx, task) in pending.withIndex()) {
                val specialClause = when (idx) {
                    0, 100 -> "本条款规定专属违约金计算方法与增值税发票核销细则。"
                    500 -> "特别约定：知识产权归属甲方并附带采购补充协议说明。"
                    else -> "常规项目进度周报第${idx}期，包含研发测试数据、服务器部署记录与日常审批流程。"
                }
                val body = "文档编号DOC-$idx。$specialClause " +
                    "系统架构采用分布式存储与本地检索引擎结合方案，支持多终端离线查询与历史档案归档。"
                db.saveTier2Result(task.fileId, task.mtime, Tier2Status.INDEXED, body)
            }

            db.search("合同")

            val q1 = db.search("税")
            val q2 = db.search("合同")
            val q3 = db.search("充协议")
            val q4 = db.search("content:违约金")
            val q5 = db.search("(采购 | 预算) ext:docx -终版")

            println(
                "BENCHMARK_100K_2K_DOCS => " +
                    "1-char('税')=${q1.elapsedMs}ms(${q1.totalReturned}), " +
                    "2-char('合同')=${q2.elapsedMs}ms(${q2.totalReturned}), " +
                    "3-char('充协议')=${q3.elapsedMs}ms(${q3.totalReturned}), " +
                    "body('content:违约金')=${q4.elapsedMs}ms(${q4.totalReturned}), " +
                    "syntax=${q5.elapsedMs}ms(${q5.totalReturned})"
            )

            assertTrue("Expected target 777 hit", q3.items.any { it.name.contains("采购合同补充协议") })
            assertTrue("Expected target 888 hit", q1.items.any { it.name.contains("税务核算") })
            assertTrue("Expected body hits for 违约金", q4.items.size >= 2 && q4.items[0].snippets.isNotEmpty())
            assertTrue("1-char query latency should be fast (<250ms), was ${q1.elapsedMs}ms", q1.elapsedMs < 250)
            assertTrue("2-char query latency should be fast (<200ms), was ${q2.elapsedMs}ms", q2.elapsedMs < 200)
            assertTrue("3-char query latency should be fast (<150ms), was ${q3.elapsedMs}ms", q3.elapsedMs < 150)
            assertTrue("Body query latency should be fast (<150ms), was ${q4.elapsedMs}ms", q4.elapsedMs < 150)
        }
    }

    @Test
    fun `listChildren returns direct children folders first sorted case-insensitively`() {
        val now = System.currentTimeMillis()
        SqliteSearchDatabase(":memory:").use { db ->
            db.upsertFileBatch(
                listOf(
                    FileUpsertEntry("/storage/emulated/0/Download/zeta.json", "/storage/emulated/0/Download", "zeta.json", "json", 128L, now, false, AccessMode.NORMAL, false, Tier2Status.NOT_APPLICABLE),
                    FileUpsertEntry("/storage/emulated/0/Download/subB", "/storage/emulated/0/Download", "subB", "", 0L, now, true, AccessMode.NORMAL, false, Tier2Status.NOT_APPLICABLE),
                    FileUpsertEntry("/storage/emulated/0/Download/alpha.png", "/storage/emulated/0/Download", "alpha.png", "png", 4096L, now, false, AccessMode.NORMAL, false, Tier2Status.NOT_APPLICABLE),
                    FileUpsertEntry("/storage/emulated/0/Download/subA", "/storage/emulated/0/Download", "subA", "", 0L, now, true, AccessMode.NORMAL, false, Tier2Status.NOT_APPLICABLE),
                    FileUpsertEntry("/storage/emulated/0/Download/subA/nested.txt", "/storage/emulated/0/Download/subA", "nested.txt", "txt", 16L, now, false, AccessMode.NORMAL, false, Tier2Status.NOT_APPLICABLE)
                )
            )
            val children = db.listChildren("/storage/emulated/0/Download")
            assertEquals(listOf("subA", "subB", "alpha.png", "zeta.json"), children.map { it.name })
        }
    }

    @Test
    fun `updating modified files and pruning deleted files keeps FTS5 index consistent`() {
        val t0 = System.currentTimeMillis()
        val path = "/storage/emulated/0/Download/notes.md"
        SqliteSearchDatabase(":memory:").use { db ->
            db.upsertFileBatch(
                listOf(
                    FileUpsertEntry(path, "/storage/emulated/0/Download", "notes.md", "md", 120L, t0, false, AccessMode.NORMAL, true, Tier2Status.PENDING)
                )
            )
            val id = db.fetchPendingTier2Tasks(5).single().fileId
            db.saveTier2Result(id, t0, Tier2Status.INDEXED, "initial english draft only")
            assertEquals(1, db.search("content:english").items.size)

            // File modified: size/mtime changed -> re-queued Tier 2 -> body replaced with Chinese text
            db.upsertFileBatch(
                listOf(
                    FileUpsertEntry(path, "/storage/emulated/0/Download", "notes.md", "md", 260L, t0 + 5000L, false, AccessMode.NORMAL, true, Tier2Status.PENDING)
                )
            )
            val updatedTask = db.fetchPendingTier2Tasks(5).single()
            db.saveTier2Result(updatedTask.fileId, t0 + 5000L, Tier2Status.INDEXED, "更新后的财务预算明细")
            assertEquals(0, db.search("content:english").items.size)
            assertEquals(1, db.search("content:预算").items.size)

            // Directory pruned when file is removed
            assertEquals(1, db.pruneDirectorySubtree("/storage/emulated/0/Download", emptySet()))
            assertEquals(0L, db.getStats().totalEntries)
            assertEquals(0, db.search("content:预算").items.size)
        }
    }
}
