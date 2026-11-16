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
    fun `root mode filters virtual filesystems bind mounts and noise caches`() {
        val engine = FilterEngine(FilterConfig())
        assertTrue(engine.shouldSkipTier1Path("/proc/cpuinfo", "cpuinfo", false, AccessMode.ROOT))
        assertTrue(engine.shouldSkipTier1Path("/sys/devices", "devices", true, AccessMode.ROOT))
        assertTrue(engine.shouldSkipTier1Path("/data/media/0/Download", "Download", true, AccessMode.ROOT))
        assertTrue(engine.shouldSkipTier1Path("/storage/emulated/0/.thumbnails", ".thumbnails", true, AccessMode.ROOT))
        assertFalse(engine.shouldSkipTier1Path("/storage/emulated/0/Download/合同.pdf", "合同.pdf", false, AccessMode.ROOT))
        assertFalse(engine.shouldSkipTier1Path("/system/build.prop", "build.prop", false, AccessMode.ROOT))
    }

    @Test
    fun `tier2 allows WeChat documents and enforces size limits while blocking system txt`() {
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
    }
}
