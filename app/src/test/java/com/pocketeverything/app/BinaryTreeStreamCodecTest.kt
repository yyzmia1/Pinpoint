package com.pocketeverything.app

import com.pocketeverything.app.fs.BinaryTreeStreamCodec
import com.pocketeverything.app.fs.ScannedFileRecord
import com.pocketeverything.app.search.AccessMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

class BinaryTreeStreamCodecTest {

    @Test
    fun `streams 5000 Chinese and ASCII file records across pipe without loss`() {
        val pipedIn = PipedInputStream(65536)
        val pipedOut = PipedOutputStream(pipedIn)

        val writerThread = thread {
            BinaryTreeStreamCodec.Writer(pipedOut).use { writer ->
                for (i in 0 until 5000) {
                    writer.writeRecord(
                        path = "/storage/emulated/0/Android/data/com.tencent.mm/文件_$i.docx",
                        parentPath = "/storage/emulated/0/Android/data/com.tencent.mm",
                        name = "文件_$i.docx",
                        ext = "docx",
                        size = 1024L * i,
                        mtime = 1700000000000L + i,
                        isDir = i % 10 == 0
                    )
                }
            }
        }

        val collected = ArrayList<ScannedFileRecord>(5000)
        val count = runBlocking {
            BinaryTreeStreamCodec.readStream(pipedIn, AccessMode.ROOT, batchSize = 700) { batch ->
                collected.addAll(batch)
            }
        }
        writerThread.join()

        assertEquals(5000, count)
        assertEquals(5000, collected.size)
        assertEquals("文件_4999.docx", collected.last().name)
        assertEquals(AccessMode.ROOT, collected.last().accessMode)
        assertEquals(true, collected[0].isDir)
        assertEquals(false, collected[1].isDir)
    }

    @Test
    fun `streams listed directory markers independently of child file records`() {
        val pipedIn = PipedInputStream(4096)
        val pipedOut = PipedOutputStream(pipedIn)

        val writerThread = thread {
            BinaryTreeStreamCodec.Writer(pipedOut).use { writer ->
                writer.writeDirListed("/storage/emulated/0/EmptyDir")
                writer.writeDirListed("/storage/emulated/0/WithFile")
                writer.writeRecord(
                    path = "/storage/emulated/0/WithFile/doc.txt",
                    parentPath = "/storage/emulated/0/WithFile",
                    name = "doc.txt",
                    ext = "txt",
                    size = 42L,
                    mtime = 1234L,
                    isDir = false
                )
            }
        }

        val listed = mutableListOf<String>()
        val records = mutableListOf<ScannedFileRecord>()
        val count = runBlocking {
            BinaryTreeStreamCodec.readStream(pipedIn, AccessMode.NORMAL, onDirListed = { listed.add(it) }) {
                records.addAll(it)
            }
        }
        writerThread.join()

        assertEquals(1, count)
        assertEquals(listOf("/storage/emulated/0/EmptyDir", "/storage/emulated/0/WithFile"), listed)
        assertEquals(1, records.size)
    }

    @Test(expected = java.io.IOException::class)
    fun `truncated scan stream fails instead of permitting index cleanup`() {
        val output = java.io.ByteArrayOutputStream()
        BinaryTreeStreamCodec.Writer(output).use { it.writeDirListed("/storage/emulated/0") }
        val bytes = output.toByteArray().dropLast(1).toByteArray()
        runBlocking {
            BinaryTreeStreamCodec.readStream(java.io.ByteArrayInputStream(bytes), AccessMode.NORMAL) { }
        }
    }
}
