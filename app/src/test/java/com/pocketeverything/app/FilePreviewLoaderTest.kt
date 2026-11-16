package com.pocketeverything.app

import com.pocketeverything.app.ui.preview.FilePreviewLoader
import com.pocketeverything.app.ui.preview.PreviewKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FilePreviewLoaderTest {

    @Test
    fun `classifies arbitrary extensions across all preview kinds`() {
        assertEquals(PreviewKind.FOLDER, FilePreviewLoader.classifyPreviewKind("", isDir = true))
        assertEquals(PreviewKind.PDF, FilePreviewLoader.classifyPreviewKind("pdf", isDir = false))
        assertEquals(PreviewKind.TEXT, FilePreviewLoader.classifyPreviewKind("json", isDir = false))
        assertEquals(PreviewKind.TEXT, FilePreviewLoader.classifyPreviewKind("kt", isDir = false))
        assertEquals(PreviewKind.TEXT, FilePreviewLoader.classifyPreviewKind("log", isDir = false))
        assertEquals(PreviewKind.IMAGE, FilePreviewLoader.classifyPreviewKind("webp", isDir = false))
        assertEquals(PreviewKind.ARCHIVE, FilePreviewLoader.classifyPreviewKind("apk", isDir = false))
        assertEquals(PreviewKind.MEDIA, FilePreviewLoader.classifyPreviewKind("mp4", isDir = false))
        assertEquals(PreviewKind.MEDIA, FilePreviewLoader.classifyPreviewKind("flac", isDir = false))
        assertEquals(PreviewKind.UNKNOWN_SNIFF, FilePreviewLoader.classifyPreviewKind("bin", isDir = false))
    }

    @Test
    fun `sniffs UTF8 text cleanly and rejects binary NUL bytes`() {
        val textBytes = "{\n  \"name\": \"中文配置\",\n  \"ok\": true\n}".toByteArray(Charsets.UTF_8)
        assertTrue(FilePreviewLoader.isLikelyTextBytes(textBytes))

        val binaryBytes = byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x00, 0x01, 0x02)
        assertFalse(FilePreviewLoader.isLikelyTextBytes(binaryBytes))
        assertEquals("ELF 原生二进制 / 动态库", FilePreviewLoader.detectMagicSignature(binaryBytes, "so"))

        val hex = FilePreviewLoader.formatHexDump(binaryBytes)
        assertTrue(hex.contains("0000  7F 45 4C 46"))
        assertTrue(hex.contains(".ELF..."))
    }

    @Test
    fun `reads archive zip entries with counts and sizes`() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(ByteArray(32) { 1 })
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("assets/config.json"))
            zos.write("{\"v\":1}".toByteArray())
            zos.closeEntry()
        }

        val (entries, total, truncated) = FilePreviewLoader.readArchiveEntries(
            ByteArrayInputStream(baos.toByteArray()),
            maxEntries = 100
        )
        assertEquals(3, total)
        assertFalse(truncated)
        assertEquals(listOf("META-INF/", "classes.dex", "assets/config.json"), entries.map { it.path })
        assertTrue(entries[0].isDir)
    }
}
