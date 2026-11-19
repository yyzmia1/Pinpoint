package com.pocketeverything.app.analyzer

import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.search.FileUpsertEntry
import com.pocketeverything.app.search.SqliteSearchDatabase
import com.pocketeverything.app.search.Tier2Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.math.abs

class WizTreeEngineTest {

    @Test
    fun `single-pass SQLite rollup aggregates recursive dir sizes, app attribution, uninstalled residue, and ext filters`() {
        val dbFile = Files.createTempFile("wiztree_rollup_", ".db").toFile().apply { deleteOnExit() }
        val installedPkgs = mapOf(
            "com.tencent.mm" to "微信",
            "com.quark.browser" to "夸克"
        )
        val resolver = AppInfoResolver { pkg, fallback ->
            val label = installedPkgs[pkg]
            if (label != null) ResolvedAppMeta(pkg, label, isInstalled = true)
            else ResolvedAppMeta(pkg, fallback ?: pkg, isInstalled = false)
        }

        SqliteSearchDatabase(dbFile.absolutePath).use { db ->
            db.upsertFileBatch(
                listOf(
                    dir("/storage/emulated/0/Android"),
                    dir("/storage/emulated/0/Android/data"),
                    dir("/storage/emulated/0/Android/data/com.tencent.mm"),
                    file("/storage/emulated/0/Android/data/com.tencent.mm/cache.dat", "dat", 400L),
                    file("/storage/emulated/0/Android/data/com.tencent.mm/blob_no_ext", "", 600L),
                    dir("/storage/emulated/0/Pictures"),
                    dir("/storage/emulated/0/Pictures/WeiXin"),
                    file("/storage/emulated/0/Pictures/WeiXin/img1.jpg", "jpg", 300L),
                    dir("/storage/emulated/0/Android/obb"),
                    dir("/storage/emulated/0/Android/obb/com.dead.game"),
                    file("/storage/emulated/0/Android/obb/com.dead.game/main.obb", "obb", 2_500L),
                    dir("/storage/emulated/0/Quark"),
                    file("/storage/emulated/0/Quark/movie.mp4", "mp4", 1_200L),
                    dir("/storage/emulated/0/Download"),
                    file("/storage/emulated/0/Download/many_1.txt", "txt", 10L),
                    file("/storage/emulated/0/Download/many_2.txt", "txt", 10L),
                    file("/storage/emulated/0/Download/many_3.txt", "txt", 10L),
                    file("/storage/emulated/0/Download/many_4.txt", "txt", 10L)
                )
            )

            val snap = WizTreeEngine.buildFromDatabase(
                db = db,
                appInfoResolver = resolver,
                preferredRoots = listOf("/storage/emulated/0")
            )

            assertEquals(listOf("/storage/emulated/0"), snap.rootPaths)
            assertEquals(5_040L, snap.totalBytes)
            assertEquals(9L, snap.totalFiles)

            // Root dir recursive rollup
            val root = snap.dirNodes["/storage/emulated/0"]
            assertNotNull(root)
            assertEquals(5_040L, root!!.totalBytes)
            assertEquals(9L, root.fileCount)

            // Children sorted by size DESC: Android (3500) > Quark (1200) > Pictures (300) > Download (40)
            val bySize = root.childrenSorted(AnalyzerTreeSort.SIZE_DESC)
            assertEquals(listOf("Android", "Quark", "Pictures", "Download"), bySize.map { it.name })
            assertEquals(3_500L, bySize[0].totalBytes)
            assertEquals(3_500f / 5_040f, bySize[0].shareOfParent, 0.001f)

            // Children sorted by count DESC: Android (3 files + 4 subdirs = 7) vs Download (4 files)
            val byCount = root.childrenSorted(AnalyzerTreeSort.COUNT_DESC)
            assertEquals("Android", byCount[0].name)
            assertEquals("Download", byCount[1].name)

            // App attribution merges Android/data/com.tencent.mm (1000) + Pictures/WeiXin (300) = 1300
            assertEquals(3, snap.appRankings.size)
            assertEquals("com.dead.game", snap.appRankings[0].packageName)
            assertFalse(snap.appRankings[0].isInstalled)
            assertEquals(2_500L, snap.appRankings[0].totalBytes)

            val wechat = snap.appRankings.first { it.packageName == "com.tencent.mm" }
            assertTrue(wechat.isInstalled)
            assertEquals("微信", wechat.displayLabel)
            assertEquals(1_300L, wechat.totalBytes)
            assertEquals(2, wechat.locations.size)

            assertEquals(1, snap.uninstalledAppCount)
            assertEquals(2_500L, snap.uninstalledResidueBytes)

            // Extension breakdown includes blank extension labeled "无后缀"
            val extMap = snap.extensionStats.associateBy { it.ext }
            assertEquals(2_500L, extMap["obb"]?.totalBytes)
            assertEquals(1_200L, extMap["mp4"]?.totalBytes)
            assertEquals("无后缀", extMap[""]?.displayLabel)
            assertEquals(600L, extMap[""]?.totalBytes)

            // Global top large files & extension filtering
            assertEquals("main.obb", snap.topLargeFiles.first().name)
            assertEquals(listOf("movie.mp4"), snap.largeFilesForExt("mp4").map { it.name })
            assertEquals(4, snap.largeFilesForExt("txt").size)
        }
    }

    @Test
    fun `AppStorageAttribution matches Android data, obb, media, root data, and shared folders accurately`() {
        val m1 = AppStorageAttribution.matchPath("/storage/emulated/0/Android/data/com.tencent.mm/MicroMsg/a.jpg")
        assertEquals("com.tencent.mm", m1?.packageName)
        assertEquals("/storage/emulated/0/Android/data/com.tencent.mm", m1?.ownerRootPath)
        assertEquals(AppPathCategory.APP_DATA, m1?.category)

        val m2 = AppStorageAttribution.matchPath("/data/user/0/org.telegram.messenger/files/video.mp4")
        assertEquals("org.telegram.messenger", m2?.packageName)
        assertEquals("/data/user/0/org.telegram.messenger", m2?.ownerRootPath)
        assertEquals(AppPathCategory.PRIVATE_DATA, m2?.category)

        val m3 = AppStorageAttribution.matchPath("/data/app/~~abc123==/tv.danmaku.bili-xyz==/base.apk")
        assertEquals("tv.danmaku.bili", m3?.packageName)
        assertEquals(AppPathCategory.APP_APK, m3?.category)

        val m4 = AppStorageAttribution.matchPath("/storage/emulated/0/Download/BaiduNetdisk/archive.zip")
        assertEquals("com.baidu.netdisk", m4?.packageName)
        assertEquals("/storage/emulated/0/Download/BaiduNetdisk", m4?.ownerRootPath)
        assertEquals(AppPathCategory.SHARED_STORAGE, m4?.category)

        assertNull(AppStorageAttribution.matchPath("/storage/emulated/0/Android/data/.nomedia"))
        assertNull(AppStorageAttribution.matchPath("/storage/emulated/0/DCIM/Camera/IMG_001.jpg"))
    }

    @Test
    fun `SquarifiedTreemap produces bounded non-overlapping area-preserving layout with tail bucket`() {
        val inputs = (1..25).map { i ->
            TreemapInput(
                id = "item_$i",
                label = "Item $i",
                weight = (26 - i).toLong() * 100L,
                ext = if (i % 2 == 0) "mp4" else "",
                isDir = i % 2 != 0
            )
        }
        val width = 360f
        val height = 160f
        val rects = SquarifiedTreemap.layout(inputs, width, height, maxTiles = 12)

        assertEquals(12, rects.size)
        assertTrue(rects.last().item.isOtherBucket)

        var areaSum = 0.0
        for (i in rects.indices) {
            val r = rects[i]
            assertTrue("left in bounds", r.left >= -0.01f && r.left <= width + 0.01f)
            assertTrue("right in bounds", r.right >= r.left && r.right <= width + 0.05f)
            assertTrue("top in bounds", r.top >= -0.01f && r.top <= height + 0.01f)
            assertTrue("bottom in bounds", r.bottom >= r.top && r.bottom <= height + 0.05f)
            areaSum += (r.width.toDouble() * r.height.toDouble())

            for (j in i + 1 until rects.size) {
                val o = rects[j]
                val overlapX = minOf(r.right, o.right) - maxOf(r.left, o.left)
                val overlapY = minOf(r.bottom, o.bottom) - maxOf(r.top, o.top)
                assertFalse(
                    "Tiles $i and $j must not overlap significantly (ox=$overlapX, oy=$overlapY)",
                    overlapX > 0.5f && overlapY > 0.5f
                )
            }
        }
        val expectedArea = width.toDouble() * height.toDouble()
        assertTrue(
            "Total area ($areaSum) should match canvas area ($expectedArea)",
            abs(areaSum - expectedArea) < expectedArea * 0.01
        )
    }

    @Test
    fun `single-pass rollup handles 50k synthetic entries quickly`() {
        val resolver = AppInfoResolver { pkg, fallback -> ResolvedAppMeta(pkg, fallback ?: pkg, true) }
        val snap = WizTreeEngine.buildFromStream(
            preferredRoots = listOf("/storage/emulated/0"),
            appInfoResolver = resolver
        ) { emit ->
            for (i in 0 until 50_000) {
                val bucket = i % 50
                val sub = (i / 50) % 20
                val parent = "/storage/emulated/0/Android/data/com.pkg$bucket/cache/sub$sub"
                val ext = when (i % 5) {
                    0 -> "mp4"
                    1 -> "jpg"
                    2 -> "db"
                    3 -> ""
                    else -> "zip"
                }
                val name = if (ext.isEmpty()) "blob_$i" else "file_$i.$ext"
                emit("$parent/$name", parent, name, ext, (i + 1).toLong() * 1024L, 1794900000000L, false, AccessMode.NORMAL.code)
            }
        }
        assertEquals(50_000L, snap.totalFiles)
        assertEquals(50, snap.appRankings.size)
        assertEquals(200, snap.topLargeFiles.size)
        assertTrue("50k single-pass rollup should finish fast, took ${snap.buildDurationMs}ms", snap.buildDurationMs < 5_000L)
    }

    private fun dir(path: String): FileUpsertEntry = FileUpsertEntry(
        path = path,
        parentPath = path.substringBeforeLast('/').ifEmpty { "/" },
        name = path.substringAfterLast('/'),
        ext = "",
        size = 0L,
        mtime = 1794900000000L,
        isDir = true,
        accessMode = AccessMode.NORMAL,
        needsTier2 = false,
        initialTier2Status = Tier2Status.NOT_APPLICABLE
    )

    private fun file(path: String, ext: String, size: Long): FileUpsertEntry = FileUpsertEntry(
        path = path,
        parentPath = path.substringBeforeLast('/').ifEmpty { "/" },
        name = path.substringAfterLast('/'),
        ext = ext,
        size = size,
        mtime = 1794900000000L,
        isDir = false,
        accessMode = AccessMode.NORMAL,
        needsTier2 = false,
        initialTier2Status = Tier2Status.NOT_APPLICABLE
    )
}
