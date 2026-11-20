package com.pocketeverything.app

import com.pocketeverything.app.filter.FilterConfig
import com.pocketeverything.app.filter.FilterEngine
import com.pocketeverything.app.filter.RootDataScope
import com.pocketeverything.app.search.AccessMode
import com.pocketeverything.app.search.Tier2Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterEngineTest {

    @Test
    fun `unified excluded paths fuse data scopes while preserving sibling dirs and noise cache defaults`() {
        val defaultCfg = FilterConfig()
        assertEquals(RootDataScope.EXCLUDE_ALL, defaultCfg.rootDataScope())
        val defaultEngine = FilterEngine(defaultCfg)
        assertTrue(defaultEngine.shouldSkipTier1Path("/proc/cpuinfo", "cpuinfo", false, AccessMode.ROOT))
        assertTrue(defaultEngine.shouldSkipTier1Path("/sys/devices", "devices", true, AccessMode.ROOT))
        assertTrue(defaultEngine.shouldSkipTier1Path("/data/media/0/Download", "Download", true, AccessMode.ROOT))
        assertTrue(defaultEngine.shouldSkipTier1Path("/data/adb", "adb", true, AccessMode.ROOT))
        assertTrue(defaultEngine.shouldSkipTier1Path("/storage/emulated/0/.thumbnails", ".thumbnails", true, AccessMode.ROOT))
        assertTrue(defaultEngine.shouldSkipTier1Path("/storage/emulated/0/App/previews", "previews", true, AccessMode.ROOT))
        assertTrue(defaultEngine.shouldSkipTier1Path("/storage/emulated/0/App/temp", "temp", true, AccessMode.ROOT))
        assertTrue(defaultEngine.shouldSkipTier1Path("/storage/emulated/0/.MediaTrash", ".MediaTrash", true, AccessMode.ROOT))
        assertFalse(defaultEngine.shouldSkipTier1Path("/storage/emulated/0/Download/Backup/report.pdf", "report.pdf", false, AccessMode.ROOT))
        assertFalse(defaultEngine.shouldSkipTier1Path("/system/build.prop", "build.prop", false, AccessMode.ROOT))

        // Excluding private /data runtime zones opens /data/adb, /data/misc, /data/system, /data/app while blocking /data/data & /data/dalvik-cache
        val nonPrivateCfg = defaultCfg.withRootDataScope(RootDataScope.EXCLUDE_PRIVATE)
        assertEquals(RootDataScope.EXCLUDE_PRIVATE, nonPrivateCfg.rootDataScope())
        val nonPrivateEngine = FilterEngine(nonPrivateCfg)
        assertFalse(nonPrivateEngine.shouldSkipTier1Path("/data/adb/modules", "modules", true, AccessMode.ROOT))
        assertFalse(nonPrivateEngine.shouldSkipTier1Path("/data/misc/wifi/WifiConfigStore.xml", "WifiConfigStore.xml", false, AccessMode.ROOT))
        assertFalse(nonPrivateEngine.shouldSkipTier1Path("/data/system/packages.xml", "packages.xml", false, AccessMode.ROOT))
        assertFalse(nonPrivateEngine.shouldSkipTier1Path("/data/app/base.apk", "base.apk", false, AccessMode.ROOT))
        assertFalse(nonPrivateEngine.shouldSkipTier1Path("/data/local/tmp/script.sh", "script.sh", false, AccessMode.ROOT))
        assertTrue(nonPrivateEngine.shouldSkipTier1Path("/data/data/com.example/files/a.db", "a.db", false, AccessMode.ROOT))
        assertTrue(nonPrivateEngine.shouldSkipTier1Path("/data/user/0/com.example", "com.example", true, AccessMode.ROOT))
        assertTrue(nonPrivateEngine.shouldSkipTier1Path("/data/dalvik-cache/arm64", "arm64", true, AccessMode.ROOT))

        // Full /data inclusion unlocks /data/data while still deduplicating /data/media
        val fullDataCfg = nonPrivateCfg.withRootDataScope(RootDataScope.INCLUDE_ALL)
        assertEquals(RootDataScope.INCLUDE_ALL, fullDataCfg.rootDataScope())
        val fullDataEngine = FilterEngine(fullDataCfg)
        assertFalse(fullDataEngine.shouldSkipTier1Path("/data/data/com.termux/files/home", "home", true, AccessMode.ROOT))
        assertTrue(fullDataEngine.shouldSkipTier1Path("/data/media/0", "0", true, AccessMode.ROOT))

        // Partial user removal (e.g. removing /data/data only) transitions cleanly to custom (null preset) while keeping other exclusions
        val customPartial = nonPrivateCfg.copy(
            customExcludedPaths = nonPrivateCfg.customExcludedPaths - "/data/data" + "/storage/emulated/0/Download/Archive"
        )
        assertNull(customPartial.rootDataScope())
        val customEngine = FilterEngine(customPartial)
        assertFalse(customEngine.shouldSkipTier1Path("/data/data/com.termux", "com.termux", true, AccessMode.ROOT))
        assertTrue(customEngine.shouldSkipTier1Path("/data/dalvik-cache", "dalvik-cache", true, AccessMode.ROOT))
        assertTrue(customEngine.shouldSkipTier1Path("/storage/emulated/0/Download/Archive/old.zip", "old.zip", false, AccessMode.NORMAL))
        // Explicit path exclusion NEVER affects unrelated folders with same basename or sibling prefix
        assertFalse(customEngine.shouldSkipTier1Path("/storage/emulated/0/Documents/Archive/keep.pdf", "keep.pdf", false, AccessMode.NORMAL))
        assertFalse(customEngine.shouldSkipTier1Path("/storage/emulated/0/Download/Archive2/keep.pdf", "keep.pdf", false, AccessMode.NORMAL))
    }

    @Test
    fun `tier2 respects unlimited zero cap, high size caps, and path scoping`() {
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

        val unlimitedBlockedByDataEngine = FilterEngine(
            FilterConfig(
                tier2AllowedPrefixes = emptyList(),
                maxTxtMdBytes = FilterConfig.UNLIMITED_BYTES,
                maxPdfBytes = FilterConfig.UNLIMITED_BYTES
            )
        )
        assertEquals(
            Tier2Status.SKIPPED_FILTER,
            unlimitedBlockedByDataEngine.evaluateTier2Status("/data/local/tmp/huge.pdf", "pdf", 950L * 1024L * 1024L, isDir = false)
        )

        val unlimitedEngine = FilterEngine(
            FilterConfig(
                customExcludedPaths = FilterConfig.ROOT_DATA_PRIVATE_EXCLUDES,
                tier2AllowedPrefixes = emptyList(),
                maxTxtMdBytes = FilterConfig.UNLIMITED_BYTES,
                maxPdfBytes = FilterConfig.UNLIMITED_BYTES
            )
        )
        assertEquals(
            Tier2Status.PENDING,
            unlimitedEngine.evaluateTier2Status("/data/local/tmp/huge.pdf", "pdf", 950L * 1024L * 1024L, isDir = false)
        )
        assertEquals(
            Tier2Status.PENDING,
            unlimitedEngine.evaluateTier2Status("/storage/emulated/0/Download/giant.txt", "txt", 1500L * 1024L * 1024L, isDir = false)
        )
        assertEquals(
            Tier2Status.SKIPPED_FILTER,
            unlimitedEngine.evaluateTier2Status("/system/etc/notice.txt", "txt", 1024L, isDir = false)
        )
    }

    @Test
    fun `data local tmp and cache sibling directory guards behave safely`() {
        val engine = FilterEngine(FilterConfig(skipNoiseCaches = true, customExcludedPaths = emptyList()))
        assertFalse(engine.shouldSkipTier1Path("/data/local/tmp", "tmp", true, AccessMode.ROOT))
        assertFalse(engine.shouldSkipTier1Path("/data/local/tmp/script.sh", "script.sh", false, AccessMode.ROOT))
        assertFalse(engine.shouldSkipTier1Path("/data/local/tmp/work/out.txt", "out.txt", false, AccessMode.ROOT))
        assertTrue(engine.shouldSkipTier1Path("/data/local/tmp/work/cache/blob", "blob", false, AccessMode.ROOT))
        assertTrue(engine.shouldSkipTier1Path("/storage/emulated/0/App/cache/sub/data.bin", "data.bin", false, AccessMode.ROOT))
        assertFalse(engine.shouldSkipTier1Path("/storage/emulated/0/Documents/cache", "cache", false, AccessMode.ROOT))
    }

    @Test
    fun `whitelist traverses ancestors excludes siblings and blacklist and system protections always win`() {
        val config = FilterConfig(customIncludedPaths = listOf("/storage/emulated/0/Documents"),
            customExcludedPaths = listOf("/storage/emulated/0/Documents/private"))
        for (mode in AccessMode.entries) {
            val filter = FilterEngine(config)
            assertFalse(filter.shouldSkipTier1Path("/storage", "storage", true, mode))
            assertFalse(filter.shouldSkipTier1Path("/storage/emulated/0/Documents/a.txt", "a.txt", false, mode))
            assertTrue(filter.shouldSkipTier1Path("/storage/emulated/0/Documents2/a.txt", "a.txt", false, mode))
            assertTrue(filter.shouldSkipTier1Path("/storage/emulated/0/Documents/private/a.txt", "a.txt", false, mode))
            assertTrue(filter.shouldSkipTier1Path("/storage/emulated/0/other.txt", "other.txt", false, mode))
            assertEquals(Tier2Status.SKIPPED_FILTER, filter.evaluateTier2Status("/storage/emulated/0/other.txt", "txt", 10, false))
            assertEquals(Tier2Status.PENDING, filter.evaluateTier2Status("/storage/emulated/0/Documents/a.txt", "txt", 10, false))
        }
        val root = FilterEngine(FilterConfig(customIncludedPaths = listOf("/proc", "/data/media"), customExcludedPaths = emptyList()))
        assertTrue(root.shouldSkipTier1Path("/proc", "proc", true, AccessMode.ROOT))
        assertTrue(root.shouldSkipTier1Path("/data/media", "media", true, AccessMode.ROOT))
        assertFalse(FilterEngine(FilterConfig(customIncludedPaths = emptyList())).shouldSkipTier1Path("/storage/a.txt", "a.txt", false, AccessMode.NORMAL))
    }
}
