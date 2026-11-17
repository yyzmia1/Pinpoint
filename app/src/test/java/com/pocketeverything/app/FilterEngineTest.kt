package com.pocketeverything.app

import com.pocketeverything.app.filter.FilterConfig
import com.pocketeverything.app.filter.FilterEngine
import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.search.Tier2Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterEngineTest {

    @Test
    fun `root mode filters virtual filesystems bind mounts default data and noise dirs`() {
        val engine = FilterEngine(FilterConfig(customExcludedDirNames = listOf("logs")))
        assertTrue(engine.shouldSkipTier1Path("/proc/cpuinfo", "cpuinfo", false, AccessMode.ROOT))
        assertTrue(engine.shouldSkipTier1Path("/sys/devices", "devices", true, AccessMode.ROOT))
        assertTrue(engine.shouldSkipTier1Path("/data/media/0/Download", "Download", true, AccessMode.ROOT))
        assertTrue(engine.shouldSkipTier1Path("/data/adb", "adb", true, AccessMode.ROOT))
        assertTrue(engine.shouldSkipTier1Path("/storage/emulated/0/.thumbnails", ".thumbnails", true, AccessMode.ROOT))
        assertTrue(engine.shouldSkipTier1Path("/storage/emulated/0/App/previews", "previews", true, AccessMode.ROOT))
        assertTrue(engine.shouldSkipTier1Path("/storage/emulated/0/App/temp", "temp", true, AccessMode.ROOT))
        assertTrue(engine.shouldSkipTier1Path("/storage/emulated/0/.MediaTrash", ".MediaTrash", true, AccessMode.ROOT))
        assertTrue(engine.shouldSkipTier1Path("/storage/emulated/0/App/logs", "logs", true, AccessMode.ROOT))
        assertFalse(engine.shouldSkipTier1Path("/storage/emulated/0/Download/合同.pdf", "合同.pdf", false, AccessMode.ROOT))
        assertFalse(engine.shouldSkipTier1Path("/system/build.prop", "build.prop", false, AccessMode.ROOT))

        val withData = FilterEngine(FilterConfig(rootIncludeData = true))
        assertFalse(withData.shouldSkipTier1Path("/data/adb", "adb", true, AccessMode.ROOT))
        assertFalse(withData.shouldSkipTier1Path("/data/local/tmp", "tmp", true, AccessMode.ROOT))
        assertTrue(withData.shouldSkipTier1Path("/data/media/0", "0", true, AccessMode.ROOT))
    }

    @Test
    fun `tier2 allows WeChat documents wildcard asterisk and enforces size limits while blocking system txt`() {
        val engine = FilterEngine(FilterConfig())
        assertEquals(
            Tier2Status.PENDING,
            engine.evaluateTier2Status(
                "/storage/emulated/0/Android/data/com.tencent.mm/MicroMsg/Download/报价单.pdf",
                "pdf",
                2L * 1024 * 1024,
                isDir = false
            )
        )
        assertEquals(
            Tier2Status.SKIPPED_FILTER,
            engine.evaluateTier2Status("/system/etc/notice.txt", "txt", 1024L, isDir = false)
        )
        assertEquals(
            Tier2Status.SKIPPED_SIZE,
            engine.evaluateTier2Status("/storage/emulated/0/Download/big.txt", "txt", 15L * 1024 * 1024, isDir = false)
        )
        assertEquals(
            Tier2Status.UNSUPPORTED_LEGACY_DOC,
            engine.evaluateTier2Status("/storage/emulated/0/Download/old.doc", "doc", 50_000L, isDir = false)
        )

        val starEngine = FilterEngine(FilterConfig(tier2AllowedPrefixes = listOf("*")))
        assertEquals(
            Tier2Status.PENDING,
            starEngine.evaluateTier2Status("/storage/emulated/0/11aa/note.md", "md", 1024L, isDir = false)
        )

        val emptyWhitelistEngine = FilterEngine(FilterConfig(tier2AllowedPrefixes = emptyList()))
        assertEquals(
            Tier2Status.PENDING,
            emptyWhitelistEngine.evaluateTier2Status("/data/local/tmp/note.md", "md", 1024L, isDir = false)
        )
        assertEquals(
            Tier2Status.SKIPPED_FILTER,
            emptyWhitelistEngine.evaluateTier2Status("/system/etc/notice.txt", "txt", 1024L, isDir = false)
        )
    }

    @Test
    fun `shouldSkipTier1Path matches nested subdirs and files while preserving logs2 sibling and data local tmp`() {
        val engine = FilterEngine(
            FilterConfig(
                skipNoiseCaches = true,
                rootIncludeData = true,
                customExcludedDirNames = listOf("logs")
            )
        )
        assertTrue(engine.shouldSkipTier1Path("/storage/emulated/0/App/logs", "logs", true, AccessMode.ROOT))
        assertTrue(engine.shouldSkipTier1Path("/storage/emulated/0/App/logs/nested", "nested", true, AccessMode.ROOT))
        assertTrue(engine.shouldSkipTier1Path("/storage/emulated/0/App/logs/nested/app.log", "app.log", false, AccessMode.ROOT))
        assertTrue(engine.shouldSkipTier1Path("/storage/emulated/0/App/cache/sub/data.bin", "data.bin", false, AccessMode.ROOT))

        // Sibling directory sharing prefix string must remain intact
        assertFalse(engine.shouldSkipTier1Path("/storage/emulated/0/logs2", "logs2", true, AccessMode.ROOT))
        assertFalse(engine.shouldSkipTier1Path("/storage/emulated/0/logs2/keep.txt", "keep.txt", false, AccessMode.ROOT))

        // /data/local/tmp exception is preserved for files and normal subdirs inside it, while nested cache inside it is still skipped
        assertFalse(engine.shouldSkipTier1Path("/data/local/tmp", "tmp", true, AccessMode.ROOT))
        assertFalse(engine.shouldSkipTier1Path("/data/local/tmp/script.sh", "script.sh", false, AccessMode.ROOT))
        assertFalse(engine.shouldSkipTier1Path("/data/local/tmp/work/out.txt", "out.txt", false, AccessMode.ROOT))
        assertTrue(engine.shouldSkipTier1Path("/data/local/tmp/work/cache/blob", "blob", false, AccessMode.ROOT))

        // Regular file named after an excluded dir name is NOT skipped, while trailing slashes in preset comparison normalize cleanly
        assertFalse(engine.shouldSkipTier1Path("/storage/emulated/0/Documents/logs", "logs", false, AccessMode.ROOT))
        assertFalse(
            FilterConfig(tier2AllowedPrefixes = listOf("/storage/emulated/", "/storage"))
                .differsFromPreset(AccessMode.NORMAL)
        )
    }
}
