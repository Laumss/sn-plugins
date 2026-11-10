package me.laumss.notipal.render

import android.graphics.Bitmap
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

internal class GrayPngWriter(file: File, private val width: Int, private val height: Int) : Closeable {
    private val out = DataOutputStream(BufferedOutputStream(FileOutputStream(file), 64 * 1024))
    private val deflater = Deflater(6)
    private val idat = object : OutputStream() {
        private val buffer = ByteArrayOutputStream(65536)
        private fun drain() { if (buffer.size() > 0) { chunk("IDAT", buffer.toByteArray()); buffer.reset() } }
        override fun write(value: Int) { buffer.write(value); if (buffer.size() >= 65536) drain() }
        override fun write(b: ByteArray, off: Int, len: Int) { buffer.write(b, off, len); if (buffer.size() >= 65536) drain() }
        override fun close() = drain()
    }
    private val compressed = DeflaterOutputStream(idat, deflater, 65536)
    private val pixels = IntArray(width)
    private val row = ByteArray(1 + width)
    private var written = 0
    private var closed = false

    init {
        require(width > 0 && height > 0) { "invalid PNG size ${width}x$height" }
        out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val header = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).apply { writeInt(width); writeInt(height); write(byteArrayOf(8, 0, 0, 0, 0)) }
        }.toByteArray()
        chunk("IHDR", header)
    }

    private fun chunk(type: String, bytes: ByteArray) {
        val tag = type.toByteArray(Charsets.US_ASCII)
        out.writeInt(bytes.size); out.write(tag); out.write(bytes)
        val crc = CRC32(); crc.update(tag); crc.update(bytes); out.writeInt(crc.value.toInt())
    }

    fun writeRows(bitmap: Bitmap, rows: Int) {
        check(!closed && written + rows <= height) { "PNG rows overflow" }
        require(bitmap.width == width && rows <= bitmap.height)
        for (y in 0 until rows) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
            row[0] = 0
            for (x in 0 until width) {
                val p = pixels[x]
                row[x + 1] = ((((p ushr 16) and 255) * 299 + ((p ushr 8) and 255) * 587 + (p and 255) * 114) / 1000).toByte()
            }
            compressed.write(row)
        }
        written += rows
    }

    fun finish() {
        check(written == height) { "PNG incomplete: $written/$height rows" }
        compressed.close()
        chunk("IEND", byteArrayOf())
        out.flush()
        closed = true
        out.close()
        deflater.end()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { out.close() }
        deflater.end()
    }

    companion object {
        fun write(file: File, bitmap: Bitmap) {
            GrayPngWriter(file, bitmap.width, bitmap.height).use {
                it.writeRows(bitmap, bitmap.height)
                it.finish()
            }
        }
    }
}
