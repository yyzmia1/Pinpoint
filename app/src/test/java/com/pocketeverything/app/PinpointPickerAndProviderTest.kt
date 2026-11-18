package com.pocketeverything.app

import com.pocketeverything.app.picker.PinpointDocContract
import com.pocketeverything.app.picker.PinpointDocId
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
import java.io.FileNotFoundException
import java.nio.file.Files

class PinpointPickerAndProviderTest {

    @Test
    fun `PinpointDocId encode parse round-trip and reject path traversal or invalid ids`() {
        assertEquals(PinpointDocId.Root, PinpointDocId.parse("root"))
        assertEquals(PinpointDocId.Recent, PinpointDocId.parse("recent"))

        val dirId = PinpointDocId.Dir("/storage/emulated/0/Download/")
        assertEquals("dir:/storage/emulated/0/Download", dirId.encode())
        assertEquals(PinpointDocId.Dir("/storage/emulated/0/Download"), PinpointDocId.parse(dirId.encode()))

        val fileId = PinpointDocId.File("/storage/emulated/0/合同.pdf")
        assertEquals("file:/storage/emulated/0/合同.pdf", fileId.encode())
        assertEquals(fileId, PinpointDocId.parse(fileId.encode()))

        assertNull(PinpointDocId.parse(null))
        assertNull(PinpointDocId.parse(""))
        assertNull(PinpointDocId.parse("unknown:123"))
        assertNull(PinpointDocId.parse("file:/"))
        assertNull(PinpointDocId.parse("file:relative/path.txt"))
        assertNull(PinpointDocId.parse("file:/storage/../etc/passwd"))
        assertNull(PinpointDocId.parse("dir:/storage/./emulated"))
        assertNull(PinpointDocId.parse("file:/storage/bad\u0000name.txt"))
    }

    @Test
    fun `PinpointDocContract validates child relationships strictly without sibling prefix leak`() {
        val allowedRoots = setOf("/storage/emulated/0")
        assertTrue(PinpointDocContract.isChildDocument("root", "recent", allowedRoots))
        assertTrue(PinpointDocContract.isChildDocument("root", "dir:/storage/emulated/0", allowedRoots))
        assertTrue(PinpointDocContract.isChildDocument("root", "file:/storage/emulated/0/a.txt", allowedRoots))
        assertFalse(PinpointDocContract.isChildDocument("root", "dir:/data/system", allowedRoots))

        assertTrue(PinpointDocContract.isChildDocument("recent", "file:/storage/emulated/0/a.txt", allowedRoots))
        assertFalse(PinpointDocContract.isChildDocument("recent", "dir:/storage/emulated/0/Download", allowedRoots))

        assertTrue(PinpointDocContract.isChildDocument("dir:/storage/emulated/0/Download", "file:/storage/emulated/0/Download/a.pdf", allowedRoots))
        assertFalse(PinpointDocContract.isChildDocument("dir:/storage/emulated/0/Download", "dir:/storage/emulated/0/Downloads", allowedRoots))
        assertFalse(PinpointDocContract.isChildDocument("file:/storage/emulated/0/a.txt", "file:/storage/emulated/0/a.txt", allowedRoots))
    }

    @Test
    fun `resolveOpenableFileMeta and getFileMetaByPath enforce read-only indexed boundaries`() {
        val dbFile = Files.createTempFile("pocket_picker_db_", ".db").toFile().apply { deleteOnExit() }
        SqliteSearchDatabase(dbFile.absolutePath).use { db ->
            val t0 = 1700000000000L
            db.upsertFileBatch(
                listOf(
                    FileUpsertEntry("/storage/emulated/0/Docs", "/storage/emulated/0", "Docs", "", 0L, t0, true, AccessMode.NORMAL, false, Tier2Status.NOT_APPLICABLE),
                    FileUpsertEntry("/storage/emulated/0/Docs/plan.pdf", "/storage/emulated/0/Docs", "plan.pdf", "pdf", 2048L, t0, false, AccessMode.SHIZUKU, false, Tier2Status.INDEXED)
                )
            )

            val meta = db.getFileMetaByPath("/storage/emulated/0/Docs/plan.pdf")
            assertNotNull(meta)
            assertEquals("plan.pdf", meta?.name)
            assertEquals(AccessMode.SHIZUKU, meta?.accessMode)
            assertNull(db.getFileMetaByPath("/storage/emulated/0/Docs/missing.pdf"))

            val resolved = PinpointDocContract.resolveOpenableFileMeta(
                db = db,
                documentId = "file:/storage/emulated/0/Docs/plan.pdf",
                mode = "r"
            )
            assertEquals("/storage/emulated/0/Docs/plan.pdf", resolved.path)

            val row = PinpointDocContract.toDocumentRow(resolved)
            val customProj = PinpointDocContract.resolveDocumentProjection(arrayOf("_display_name", "mime_type", "_size"))
            val values = PinpointDocContract.buildDocumentRowValues(customProj, row)
            assertEquals("plan.pdf", values[0])
            assertEquals("application/pdf", values[1])
            assertEquals(2048L, values[2])

            val writeRejected = runCatching {
                PinpointDocContract.resolveOpenableFileMeta(db, "file:/storage/emulated/0/Docs/plan.pdf", "rw")
            }.exceptionOrNull()
            assertTrue(writeRejected is UnsupportedOperationException)

            val dirRejected = runCatching {
                PinpointDocContract.resolveOpenableFileMeta(db, "dir:/storage/emulated/0/Docs", "r")
            }.exceptionOrNull()
            assertTrue(dirRejected is FileNotFoundException)

            val unindexedRejected = runCatching {
                PinpointDocContract.resolveOpenableFileMeta(db, "file:/data/system/users/0/settings.xml", "r")
            }.exceptionOrNull()
            assertTrue(unindexedRejected is FileNotFoundException)
        }
    }
}
