package com.pocketeverything.app.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeletionSafetyGuardTest {

    @Test
    fun `hard-blocks system partitions, root mountpoints, container roots, and traversal paths`() {
        val blockedPaths = listOf(
            "",
            "   ",
            "relative/path",
            "/storage/emulated/0/../system",
            "/storage/emulated/0/./Download",
            "/storage/emulated/0/bad\u0000file",
            "/",
            "/system",
            "/system/app/Settings/Settings.apk",
            "/vendor/lib64/libc.so",
            "/product/overlay",
            "/proc/1/status",
            "/sys/class/power_supply",
            "/dev/null",
            "/data",
            "/data/system/packages.xml",
            "/data/misc/wifi",
            "/data/dalvik-cache/arm64",
            "/data/app/com.example-1",
            "/data/adb/modules",
            "/data/data",
            "/data/user",
            "/data/user/0",
            "/data/user/999",
            "/data/user_de/0",
            "/storage",
            "/storage/emulated",
            "/storage/emulated/0",
            "/storage/emulated/0/",
            "/sdcard",
            "/storage/ABCD-1234",
            "/storage/emulated/0/Android",
            "/storage/emulated/0/Android/data",
            "/storage/emulated/0/Android/obb",
            "/storage/emulated/0/Android/media",
            "/storage/ABCD-1234/Android/data"
        )
        for (p in blockedPaths) {
            val res = DeletionSafetyGuard.evaluate(p)
            assertEquals("Expected BLOCKED for '$p'", DeletionRisk.BLOCKED, res.risk)
        }
    }

    @Test
    fun `flags personal user media and document folders as HIGH_PERSONAL`() {
        val personalPaths = listOf(
            "/storage/emulated/0/DCIM",
            "/storage/emulated/0/DCIM/Camera/IMG_001.jpg",
            "/storage/emulated/0/Pictures/WeiXin/photo.png",
            "/storage/emulated/0/Download/contract.pdf",
            "/storage/emulated/0/Documents/notes.md",
            "/sdcard/Movies/clip.mp4"
        )
        for (p in personalPaths) {
            val res = DeletionSafetyGuard.evaluate(p)
            assertEquals("Expected HIGH_PERSONAL for '$p'", DeletionRisk.HIGH_PERSONAL, res.risk)
        }
    }

    @Test
    fun `allows normal deletion of specific app residue and cache subdirectories`() {
        val allowedPaths = listOf(
            "/storage/emulated/0/Android/data/com.dead.game",
            "/storage/emulated/0/Android/obb/com.dead.game/main.obb",
            "/storage/emulated/0/Quark/cache/tmp.bin",
            "/data/local/tmp/debug.log",
            "/data/data/com.example.app/cache"
        )
        for (p in allowedPaths) {
            val res = DeletionSafetyGuard.evaluate(p)
            assertEquals("Expected NORMAL for '$p'", DeletionRisk.NORMAL, res.risk)
        }
    }

    @Test
    fun `deleteRecursivelyNoFollow rejects blocked paths and deletes real nested temp directories`() {
        assertFalse(DeletionSafetyGuard.deleteRecursivelyNoFollow("/"))
        assertFalse(DeletionSafetyGuard.deleteRecursivelyNoFollow("/system/bin"))
        assertFalse(DeletionSafetyGuard.deleteRecursivelyNoFollow("/storage/emulated/0"))

        val tempRoot = Files.createTempDirectory("wiztree_del_test_").toFile()
        val nested = File(tempRoot, "sub/child").apply { mkdirs() }
        val leaf = File(nested, "junk.tmp").apply { writeText("junk") }

        val unixStyle = tempRoot.absolutePath.replace('\\', '/')
        // On Windows host JVM paths start with drive letter (e.g. C:/...), so verify guard blocks non-slash paths cleanly.
        if (unixStyle.startsWith("/")) {
            assertTrue(DeletionSafetyGuard.deleteRecursivelyNoFollow(unixStyle))
            assertFalse(tempRoot.exists())
        } else {
            assertEquals(DeletionRisk.BLOCKED, DeletionSafetyGuard.evaluate(unixStyle).risk)
            assertTrue(leaf.exists())
            tempRoot.deleteRecursively()
        }
    }
}
