package com.pocketeverything.app.fs

import com.pocketeverything.app.search.AccessMode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

data class ScannedFileRecord(
    val path: String,
    val parentPath: String,
    val name: String,
    val ext: String,
    val size: Long,
    val mtime: Long,
    val isDir: Boolean,
    val accessMode: AccessMode
)

/**
 * Compact binary streaming codec for passing 100k+ scanned file entries across Binder IPC pipes
 * (ParcelFileDescriptor) with zero Binder transaction buffer overhead.
 */
object BinaryTreeStreamCodec {
    private const val MAGIC = 0x504B4556 // "PKEV"
    private const val RECORD_TAG: Byte = 1
    private const val DIR_LISTED_TAG: Byte = 2
    private const val EOF_TAG: Byte = 0

    class Writer(rawOut: OutputStream) : AutoCloseable {
        private val out = DataOutputStream(BufferedOutputStream(rawOut, 65536))

        init {
            out.writeInt(MAGIC)
        }

        fun writeRecord(
            path: String,
            parentPath: String,
            name: String,
            ext: String,
            size: Long,
            mtime: Long,
            isDir: Boolean
        ) {
            out.writeByte(RECORD_TAG.toInt())
            out.writeBoolean(isDir)
            out.writeLong(size)
            out.writeLong(mtime)
            out.writeUTF(path)
            out.writeUTF(parentPath)
            out.writeUTF(name)
            out.writeUTF(ext)
        }

        fun writeDirListed(path: String) {
            out.writeByte(DIR_LISTED_TAG.toInt())
            out.writeUTF(path)
        }

        fun finish() {
            out.writeByte(EOF_TAG.toInt())
            out.flush()
        }

        override fun close() {
            runCatching { finish() }
            runCatching { out.close() }
        }
    }

    suspend fun readStream(
        rawIn: InputStream,
        accessMode: AccessMode,
        batchSize: Int = 1500,
        onDirListed: (String) -> Unit = {},
        onBatch: suspend (List<ScannedFileRecord>) -> Unit
    ): Int {
        val inp = DataInputStream(BufferedInputStream(rawIn, 65536))
        var totalCount = 0
        inp.use { stream ->
            val magic = try {
                stream.readInt()
            } catch (_: EOFException) {
                return 0
            }
            if (magic != MAGIC) return 0

            val batch = ArrayList<ScannedFileRecord>(batchSize)
            val ctx = currentCoroutineContext()
            while (true) {
                ctx.ensureActive()
                val tag = try {
                    stream.readByte()
                } catch (_: EOFException) {
                    break
                }
                if (tag == EOF_TAG) break
                if (tag == DIR_LISTED_TAG) {
                    onDirListed(stream.readUTF())
                    continue
                }
                val isDir = stream.readBoolean()
                val size = stream.readLong()
                val mtime = stream.readLong()
                val path = stream.readUTF()
                val parentPath = stream.readUTF()
                val name = stream.readUTF()
                val ext = stream.readUTF()
                batch.add(
                    ScannedFileRecord(
                        path = path,
                        parentPath = parentPath,
                        name = name,
                        ext = ext,
                        size = size,
                        mtime = mtime,
                        isDir = isDir,
                        accessMode = accessMode
                    )
                )
                totalCount++
                if (batch.size >= batchSize) {
                    onBatch(batch.toList())
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) {
                onBatch(batch)
            }
        }
        return totalCount
    }
}
