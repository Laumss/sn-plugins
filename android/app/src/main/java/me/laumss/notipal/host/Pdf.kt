package me.laumss.notipal.host

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.roundToInt
import me.laumss.notipal.common.Dirs
import me.laumss.notipal.common.Log
import me.laumss.notipal.ui_common.Style

object Pdf {
    private const val TAG = "PagesPdf"

    const val MAX_BYTES = 14L * 1024 * 1024

    private const val RASTER_LONG_SIDE = 2000
    private const val JPEG_QUALITY = 82
    private const val NOTE_PAGE_WIDTH_PT = 595f

    private const val IMAGE_LONG_SIDE = 2000
    private const val NOTE_PNG_TIMEOUT_S = 20L

    enum class Result { OK, TOO_LARGE, FAILED, CANCELLED }

    private val main = Handler(Looper.getMainLooper())

    fun fromPdf(source: File, pages: List<Int>, output: File, cancelled: () -> Boolean, onPage: (Int) -> Unit): Result {
        try {
            PdfPageSubset.extract(source, pages, output)
            if (output.length() in 1..MAX_BYTES) return Result.OK
            Log.i(TAG, "copied pages are ${output.length()} bytes, rendering them instead")
        } catch (e: PdfPageSubset.Unsupported) {
            Log.i(TAG, "pages not copied (${e.message}), rendering them instead")
        } catch (e: Exception) {
            Log.w(TAG, "page copy failed, rendering the pages instead", e)
        }
        output.delete()
        if (cancelled()) return Result.CANCELLED
        val result = try {
            renderPdf(source, pages, output, cancelled, onPage)
        } catch (e: Exception) {

            Log.w(TAG, "page render failed", e)
            Result.FAILED
        }
        if (result != Result.OK) output.delete()
        return result
    }

    private fun renderPdf(source: File, pages: List<Int>, output: File, cancelled: () -> Boolean, onPage: (Int) -> Unit): Result {
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                ImagePdfWriter(output).use { writer ->
                    for ((done, index) in pages.withIndex()) {
                        if (cancelled()) return Result.CANCELLED
                        onPage(done)
                        renderer.openPage(index).use { page ->
                            val scale = RASTER_LONG_SIDE / maxOf(page.width, page.height, 1).toFloat()
                            val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                            val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Style.paper)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val jpeg = ByteArrayOutputStream(width * height / 8)
                                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg)
                                writer.addPage(jpeg.toByteArray(), width, height, true, page.width.toFloat(), page.height.toFloat())
                            } finally {
                                bitmap.recycle()
                            }
                        }
                        if (writer.size > MAX_BYTES) return Result.TOO_LARGE
                    }
                    writer.finish()
                }
            }
        }
        return Result.OK
    }

    fun fromNote(
        ctx: ReactApplicationContext,
        notePath: String,
        pages: List<Int>,
        output: File,
        cancelled: () -> Boolean,
        onPage: (Int) -> Unit,
    ): Result {
        val result = try {
            renderNote(ctx, notePath, pages, output, cancelled, onPage)
        } catch (e: Exception) {
            Log.w(TAG, "note pages failed", e)
            Result.FAILED
        }
        if (result != Result.OK) output.delete()
        return result
    }

    private fun renderNote(
        ctx: ReactApplicationContext,
        notePath: String,
        pages: List<Int>,
        output: File,
        cancelled: () -> Boolean,
        onPage: (Int) -> Unit,
    ): Result {

        val dir = Dirs.hostRender.apply { mkdirs() }
        ImagePdfWriter(output).use { writer ->
            for ((done, index) in pages.withIndex()) {
                if (cancelled()) return Result.CANCELLED
                onPage(done)
                val png = File(dir, "attach_${System.nanoTime()}_$index.png")
                try {
                    if (!hostPng(ctx, notePath, index, png)) return Result.FAILED
                    val bitmap = BitmapFactory.decodeFile(png.absolutePath) ?: return Result.FAILED
                    try {
                        writer.addPage(grayFlate(bitmap), bitmap.width, bitmap.height, false,
                            NOTE_PAGE_WIDTH_PT, NOTE_PAGE_WIDTH_PT * bitmap.height / bitmap.width)
                    } finally {
                        bitmap.recycle()
                    }
                } finally {
                    png.delete()
                }
                if (writer.size > MAX_BYTES) return Result.TOO_LARGE
            }
            writer.finish()
        }
        return Result.OK
    }

    fun noteImages(
        ctx: ReactApplicationContext,
        notePath: String,
        pages: List<Int>,
        dir: File,
        into: MutableList<File>,
        cancelled: () -> Boolean,
        onPage: (Int) -> Unit,
    ): Result {
        val staging = Dirs.hostRender.apply { mkdirs() }
        try {
            for ((done, index) in pages.withIndex()) {
                if (cancelled()) return Result.CANCELLED
                onPage(done)
                val png = File(staging, "attach_${System.nanoTime()}_$index.png")
                try {
                    if (!hostPng(ctx, notePath, index, png)) return Result.FAILED
                    val target = File(dir, "p${index + 1}.png")
                    if (!fitImage(png, target)) return Result.FAILED
                    into += target
                } finally {
                    png.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "note page images failed", e)
            return Result.FAILED
        }
        return Result.OK
    }

    private fun fitImage(source: File, target: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        val side = maxOf(bounds.outWidth, bounds.outHeight)
        if (side <= 0) return false
        if (side <= IMAGE_LONG_SIDE) {
            if (source.renameTo(target)) return true
            source.copyTo(target, overwrite = true)
            return true
        }
        val bitmap = BitmapFactory.decodeFile(source.absolutePath) ?: return false
        val scale = IMAGE_LONG_SIDE / side.toFloat()
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
        try {
            return target.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }

    private fun hostPng(ctx: ReactApplicationContext, notePath: String, page: Int, png: File): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        main.post {
            val params = Arguments.createMap().apply {
                putString("notePath", notePath); putInt("page", page); putInt("times", 1)
                putString("pngPath", png.absolutePath); putInt("type", 1)
            }
            PageRender.callHost(ctx, "generateNotePng", listOf(params)) { value, error ->
                ok = value == true && png.isFile
                if (!ok) Log.w(TAG, "note page ${page + 1}: $error")
                latch.countDown()
            }
        }
        return latch.await(NOTE_PNG_TIMEOUT_S, TimeUnit.SECONDS) && ok
    }

    private fun grayFlate(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val band = 64
        val pixels = IntArray(width * band)
        val gray = ByteArray(width * band)
        val packed = ByteArray(1 shl 16)
        val out = ByteArrayOutputStream(width * height / 16 + 1024)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            var y = 0
            while (y < height) {
                val rows = minOf(band, height - y)
                bitmap.getPixels(pixels, 0, width, 0, y, width, rows)
                for (i in 0 until width * rows) {
                    val c = pixels[i]
                    val luma = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
                    gray[i] = (255 - (255 - luma) * (c ushr 24) / 255).toByte()
                }
                deflater.setInput(gray, 0, width * rows)
                while (!deflater.needsInput()) out.write(packed, 0, deflater.deflate(packed))
                y += rows
            }
            deflater.finish()
            while (!deflater.finished()) out.write(packed, 0, deflater.deflate(packed))
        } finally {
            deflater.end()
        }
        return out.toByteArray()
    }
}

internal object PdfPageSubset {
    class Unsupported(message: String) : Exception(message)

    fun extract(source: File, pages: List<Int>, output: File) {
        RandomAccessFile(source, "r").use { file ->
            val doc = Doc(file)
            if (doc.encrypted) throw Unsupported("encrypted")

            val skip = HashSet<Int>()
            val all = doc.pages(skip)
            val chosen = pages.map { all.getOrNull(it) ?: throw Unsupported("page ${it + 1} of ${all.size}") }
            all.forEach { skip += it.num }
            val kept = chosen.mapTo(HashSet()) { it.num }
            val pagesNum = doc.objectCount
            val catalogNum = pagesNum + 1

            val offsets = TreeMap<Int, LongArray>()
            val written = HashSet<Int>()
            val queue = ArrayDeque<Int>()

            val nulled = java.util.TreeSet<Int>()
            fun follow(raw: ByteArray) {
                for (num in references(raw)) {
                    if (num in written || num in kept) continue
                    if (num in skip) nulled += num else queue.addLast(num)
                }
            }
            Out(BufferedOutputStream(FileOutputStream(output), 1 shl 16)).use { out ->
                out.write("%PDF-1.7\n%âãÏÓ\n")
                val kids = StringBuilder()
                for (page in chosen) {
                    val obj = doc.load(page.num) ?: throw Unsupported("page object ${page.num}")
                    kids.append(page.num).append(' ').append(obj.gen).append(" R ")
                    if (!written.add(page.num)) continue

                    val more = ByteArrayOutputStream()
                    for ((key, value) in page.inherited) if (!obj.dict.containsKey(key)) {
                        more.write("/$key ".toByteArray(Charsets.ISO_8859_1))
                        more.write(value)
                        more.write('\n'.code)
                    }
                    more.write("/Parent $pagesNum 0 R".toByteArray(Charsets.ISO_8859_1))
                    val body = rebuilt(obj, PAGE_DROPPED, more.toByteArray())
                    offsets[page.num] = longArrayOf(out.position, obj.gen.toLong())
                    out.write("${page.num} ${obj.gen} obj\n")
                    out.write(body)
                    out.write("\nendobj\n")
                    follow(body)
                }

                val layers = doc.load(doc.root)?.value("OCProperties")
                layers?.let { follow(it) }
                doc.info?.let { follow(it) }
                while (queue.isNotEmpty()) {
                    val num = queue.removeFirst()
                    if (num in skip || !written.add(num)) continue
                    val obj = doc.load(num) ?: continue
                    offsets[num] = longArrayOf(out.position, obj.gen.toLong())
                    out.write("$num ${obj.gen} obj\n")
                    if (obj.dataAt < 0) {
                        out.write(obj.raw)
                    } else {
                        val length = doc.dataLength(obj)

                        out.write(if (length.declared) obj.raw else rebuilt(obj, LENGTH_DROPPED, "/Length ${length.bytes}".toByteArray()))
                        out.write("\nstream\n")
                        doc.copyData(obj.dataAt, length.bytes, out)
                        out.write("\nendstream")
                    }
                    out.write("\nendobj\n")
                    follow(obj.raw)
                }
                for (num in nulled) {
                    val gen = doc.generation(num)
                    offsets[num] = longArrayOf(out.position, gen.toLong())
                    out.write("$num $gen obj\nnull\nendobj\n")
                }
                offsets[pagesNum] = longArrayOf(out.position, 0L)
                out.write("$pagesNum 0 obj\n<</Type/Pages/Kids[${kids.toString().trim()}]/Count ${chosen.size}>>\nendobj\n")
                offsets[catalogNum] = longArrayOf(out.position, 0L)
                out.write("$catalogNum 0 obj\n<</Type/Catalog/Pages $pagesNum 0 R")
                if (layers != null) {
                    out.write("/OCProperties ")
                    out.write(layers)
                }
                out.write(">>\nendobj\n")

                val xrefAt = out.position
                val table = StringBuilder("xref\n0 1\n0000000000 65535 f \n")
                val run = ArrayList<Map.Entry<Int, LongArray>>()
                fun flush() {
                    if (run.isEmpty()) return
                    table.append(run[0].key).append(' ').append(run.size).append('\n')
                    for (entry in run) table.append(String.format(Locale.US, "%010d %05d n \n", entry.value[0], entry.value[1]))
                    run.clear()
                }
                for (entry in offsets.entries) {
                    if (run.isNotEmpty() && entry.key != run.last().key + 1) flush()
                    run += entry
                }
                flush()
                table.append("trailer\n<</Size ").append(catalogNum + 1).append("/Root ").append(catalogNum).append(" 0 R")
                doc.info?.let { table.append("/Info ").append(String(it, Charsets.ISO_8859_1)) }
                table.append(">>\nstartxref\n").append(xrefAt).append("\n%%EOF\n")
                out.write(table.toString())
            }
        }
    }

    private val PAGE_DROPPED = setOf("Parent", "B")
    private val LENGTH_DROPPED = setOf("Length")

    private fun rebuilt(obj: Obj, drop: Set<String>, more: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        body.write('<'.code)
        body.write('<'.code)
        for ((key, range) in obj.dict) {
            if (key in drop) continue
            body.write("/$key ".toByteArray(Charsets.ISO_8859_1))
            body.write(obj.raw, range.first, range.last - range.first + 1)
            body.write('\n'.code)
        }
        body.write(more)
        body.write('>'.code)
        body.write('>'.code)
        return body.toByteArray()
    }
}

private const val T_EOF = 0
private const val T_INT = 1
private const val T_NAME = 2
private const val T_STRING = 3
private const val T_DICT_OPEN = 4
private const val T_DICT_CLOSE = 5
private const val T_ARRAY_OPEN = 6
private const val T_ARRAY_CLOSE = 7

private const val T_WORD = 8

private const val T_REF = 9

private val INHERITED = listOf("Resources", "MediaBox", "CropBox", "Rotate")

private const val MAX_OBJECT = 16L shl 20

private const val MAX_DECODED = 64L shl 20
private const val MAX_OBJECT_NUMBER = 8_388_607

private fun isSpace(c: Int) = c == 32 || c == 10 || c == 13 || c == 9 || c == 12 || c == 0

private fun isRegular(c: Int) = !isSpace(c) && c != '('.code && c != ')'.code && c != '<'.code && c != '>'.code &&
    c != '['.code && c != ']'.code && c != '{'.code && c != '}'.code && c != '/'.code && c != '%'.code

private class Truncated : Exception()

private class Lex(private val b: ByteArray, var p: Int, private val end: Int, private val whole: Boolean) {

    var int = 0L

    var text = ""

    var start = 0

    fun skipSpace() {
        while (p < end) {
            val c = b[p].toInt()
            if (c == '%'.code) {
                while (p < end && b[p].toInt() != 10 && b[p].toInt() != 13) p++
            } else if (isSpace(c)) {
                p++
            } else {
                return
            }
        }
    }

    fun next(): Int {
        skipSpace()
        start = p
        if (p >= end) {
            if (!whole) throw Truncated()
            return T_EOF
        }
        when (b[p].toInt()) {
            '/'.code -> {
                p++
                val from = p
                while (p < end && isRegular(b[p].toInt())) p++
                if (p >= end && !whole) throw Truncated()
                text = String(b, from, p - from, Charsets.ISO_8859_1)
                return T_NAME
            }
            '('.code -> {
                var depth = 0
                while (true) {
                    if (p >= end) {
                        if (!whole) throw Truncated()
                        p = end
                        break
                    }
                    val c = b[p].toInt()
                    if (c == '\\'.code) {
                        p += 2
                        continue
                    }
                    if (c == '('.code) {
                        depth++
                    } else if (c == ')'.code) {
                        depth--
                        if (depth == 0) {
                            p++
                            break
                        }
                    }
                    p++
                }
                return T_STRING
            }
            '<'.code -> {
                if (p + 1 >= end && !whole) throw Truncated()
                if (p + 1 < end && b[p + 1].toInt() == '<'.code) {
                    p += 2
                    return T_DICT_OPEN
                }
                while (p < end && b[p].toInt() != '>'.code) p++
                if (p < end) p++ else if (!whole) throw Truncated()
                return T_STRING
            }
            '>'.code -> {
                if (p + 1 >= end && !whole) throw Truncated()
                if (p + 1 < end && b[p + 1].toInt() == '>'.code) {
                    p += 2
                    return T_DICT_CLOSE
                }
                p++
                text = ">"
                return T_WORD
            }
            '['.code -> {
                p++
                return T_ARRAY_OPEN
            }
            ']'.code -> {
                p++
                return T_ARRAY_CLOSE
            }
            '{'.code, '}'.code, ')'.code -> {
                p++
                text = ""
                return T_WORD
            }
        }
        val from = p
        while (p < end && isRegular(b[p].toInt())) p++
        if (p >= end && !whole) throw Truncated()
        if (p == from) p++
        var i = from
        if (b[i].toInt() == '+'.code || b[i].toInt() == '-'.code) i++
        var digits = i < p && p - i <= 18
        var value = 0L
        while (digits && i < p) {
            val d = b[i].toInt() - '0'.code
            if (d < 0 || d > 9) digits = false else value = value * 10 + d
            i++
        }
        if (digits) {
            int = if (b[from].toInt() == '-'.code) -value else value
            return T_INT
        }
        text = String(b, from, p - from, Charsets.ISO_8859_1)
        return T_WORD
    }

    fun skipValue(): Int {
        val first = next()
        when (first) {
            T_DICT_OPEN, T_ARRAY_OPEN -> {
                var depth = 1
                while (depth > 0) {
                    when (next()) {
                        T_DICT_OPEN, T_ARRAY_OPEN -> depth++
                        T_DICT_CLOSE, T_ARRAY_CLOSE -> depth--
                        T_EOF -> return first
                    }
                }
            }
            T_DICT_CLOSE, T_ARRAY_CLOSE -> p = start
            T_INT -> {
                val num = int
                val after = p
                if (next() == T_INT && next() == T_WORD && text == "R") {
                    int = num
                    return T_REF
                }
                p = after
                int = num
            }
        }
        return first
    }

    fun dict(): LinkedHashMap<String, IntRange>? {
        if (next() != T_DICT_OPEN) return null
        val entries = LinkedHashMap<String, IntRange>()
        while (true) {
            when (next()) {
                T_DICT_CLOSE, T_EOF -> return entries
                T_NAME -> {
                    val key = text
                    skipSpace()
                    val from = p
                    skipValue()
                    entries[key] = from until p
                }
            }
        }
    }
}

private fun references(raw: ByteArray, from: Int = 0, to: Int = raw.size): List<Int> {
    val out = ArrayList<Int>()
    val lex = Lex(raw, from, to, true)

    var kind1 = T_EOF
    var kind2 = T_EOF
    var kind3 = T_EOF
    var int2 = 0L
    var int1 = 0L
    var name2 = ""
    var name3 = ""
    var name1 = ""
    while (true) {
        val kind = lex.next()
        if (kind == T_EOF) break
        if (kind == T_WORD && lex.text == "R" && kind1 == T_INT && kind2 == T_INT &&
            !(kind3 == T_NAME && name3 == "Parent") && int2 in 1..MAX_OBJECT_NUMBER
        ) {
            out += int2.toInt()
        }
        kind3 = kind2
        name3 = name2
        kind2 = kind1
        name2 = name1
        int2 = int1
        kind1 = kind
        int1 = lex.int
        name1 = if (kind == T_NAME) lex.text else ""
    }
    return out
}

private fun integers(raw: ByteArray): List<Long> {
    val out = ArrayList<Long>()
    val lex = Lex(raw, 0, raw.size, true)
    while (true) {
        val kind = lex.next()
        if (kind == T_EOF) break
        if (kind == T_INT) out += lex.int
    }
    return out
}

private fun indexOf(data: ByteArray, word: String, from: Int): Int {
    var i = from
    while (i + word.length <= data.size) {
        var j = 0
        while (j < word.length && data[i + j].toInt() == word[j].code) j++
        if (j == word.length) return i
        i++
    }
    return -1
}

private fun lastIndexOf(data: ByteArray, word: String): Int {
    var i = data.size - word.length
    while (i >= 0) {
        var j = 0
        while (j < word.length && data[i + j].toInt() == word[j].code) j++
        if (j == word.length) return i
        i--
    }
    return -1
}

private class Entry(val type: Int, val a: Long, val b: Int)

private class StreamLength(val bytes: Long, val declared: Boolean)

private class Obj(val num: Int, val gen: Int, val raw: ByteArray, val dataAt: Long) {

    val dict: Map<String, IntRange> by lazy { Lex(raw, 0, raw.size, true).dict() ?: emptyMap() }

    fun value(key: String): ByteArray? = dict[key]?.let { raw.copyOfRange(it.first, it.last + 1) }
}

private class Out(private val stream: OutputStream) : Closeable {
    var position = 0L
        private set

    fun write(bytes: ByteArray, from: Int = 0, count: Int = bytes.size) {
        stream.write(bytes, from, count)
        position += count
    }

    fun write(text: String) = write(text.toByteArray(Charsets.ISO_8859_1))

    override fun close() = stream.close()
}

private class Doc(private val file: RandomAccessFile) {
    class Page(val num: Int, val inherited: Map<String, ByteArray>)

    private val length = file.length()

    private val base: Long
    private val xref = HashMap<Int, Entry>()

    private val streams = object : LinkedHashMap<Int, Pair<ByteArray, IntArray>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Pair<ByteArray, IntArray>>): Boolean = this.size > 8
    }

    var objectCount = 0
        private set
    var root = -1
        private set

    var info: ByteArray? = null
        private set
    var encrypted = false
        private set
    private var trailerSeen = false

    init {
        val head = read(0, minOf(1024L, length).toInt())
        val header = indexOf(head, "%PDF-", 0)
        if (header < 0) throw PdfPageSubset.Unsupported("no header")
        base = header.toLong()
        val tailAt = maxOf(0L, length - 2048)
        val tail = read(tailAt, (length - tailAt).toInt())
        val mark = lastIndexOf(tail, "startxref")
        if (mark < 0) throw PdfPageSubset.Unsupported("no startxref")
        val lex = Lex(tail, mark + 9, tail.size, true)
        if (lex.next() != T_INT) throw PdfPageSubset.Unsupported("startxref")
        var at = lex.int
        val seen = HashSet<Long>()
        while (at >= 0 && seen.add(at)) at = section(base + at)
        if (root < 0) throw PdfPageSubset.Unsupported("no catalog")
        objectCount = maxOf(objectCount, (xref.keys.maxOrNull() ?: 0) + 1)
    }

    private fun read(at: Long, count: Int): ByteArray {
        if (at < 0 || count < 0 || at + count > length) throw PdfPageSubset.Unsupported("read past the end")
        val bytes = ByteArray(count)
        file.seek(at)
        file.readFully(bytes)
        return bytes
    }

    private fun section(at: Long): Long {
        if (at < 0 || at >= length) throw PdfPageSubset.Unsupported("xref offset")
        val probe = read(at, minOf(16L, length - at).toInt())
        var i = 0
        while (i < probe.size && isSpace(probe[i].toInt())) i++
        return if (indexOf(probe, "xref", i) == i) table(at) else trailer(xrefStream(at))
    }

    private fun table(at: Long): Long {
        var want = 1L shl 16
        while (true) {
            val count = minOf(want, length - at).toInt()
            val whole = at + count >= length
            val buf = read(at, count)
            try {
                val lex = Lex(buf, 0, count, whole)
                lex.next()
                val entries = ArrayList<Pair<Int, Entry>>()
                while (true) {
                    val kind = lex.next()
                    if (kind == T_WORD && lex.text == "trailer") break
                    if (kind != T_INT) throw PdfPageSubset.Unsupported("xref table")
                    var num = lex.int.toInt()
                    if (lex.next() != T_INT) throw PdfPageSubset.Unsupported("xref table")
                    val rows = lex.int
                    var row = 0L
                    while (row < rows) {
                        if (lex.next() != T_INT) throw PdfPageSubset.Unsupported("xref entry")
                        val offset = lex.int
                        if (lex.next() != T_INT) throw PdfPageSubset.Unsupported("xref entry")
                        val gen = lex.int.toInt()
                        if (lex.next() != T_WORD) throw PdfPageSubset.Unsupported("xref entry")
                        entries += num to (if (lex.text == "n") Entry(1, offset, gen) else Entry(0, 0L, gen))
                        num++
                        row++
                    }
                }
                val dict = lex.dict() ?: throw PdfPageSubset.Unsupported("trailer")
                val values = dict.mapValues { (_, range) -> buf.copyOfRange(range.first, range.last + 1) }

                values["XRefStm"]?.let { integers(it).firstOrNull() }?.let { xrefStream(base + it) }
                for ((num, entry) in entries) if (!xref.containsKey(num)) xref[num] = entry
                return trailer(values)
            } catch (_: Truncated) {
                if (whole || want >= length) throw PdfPageSubset.Unsupported("xref table cut off")
                want *= 4
            }
        }
    }

    private fun xrefStream(at: Long): Map<String, ByteArray> {
        val obj = readAt(at)
        if (obj.dataAt < 0) throw PdfPageSubset.Unsupported("xref stream")
        val values = obj.dict.keys.associateWith { obj.value(it)!! }
        val widths = values["W"]?.let(::integers).orEmpty()
        if (widths.size < 3) throw PdfPageSubset.Unsupported("xref stream widths")
        val w0 = widths[0].toInt()
        val w1 = widths[1].toInt()
        val w2 = widths[2].toInt()
        val total = values["Size"]?.let(::integers)?.firstOrNull() ?: throw PdfPageSubset.Unsupported("xref stream size")
        val index = values["Index"]?.let(::integers)?.takeIf { it.size >= 2 } ?: listOf(0L, total)
        val data = decode(obj)
        var p = 0
        for (k in 0 until index.size / 2) {
            var num = index[k * 2].toInt()
            var left = index[k * 2 + 1]
            while (left > 0 && p + w0 + w1 + w2 <= data.size) {
                val type = if (w0 == 0) 1L else field(data, p, w0)
                val second = field(data, p + w0, w1)
                val third = field(data, p + w0 + w1, w2)
                p += w0 + w1 + w2
                if (!xref.containsKey(num)) {
                    xref[num] = when (type) {
                        1L -> Entry(1, second, third.toInt())
                        2L -> Entry(2, second, third.toInt())
                        else -> Entry(0, 0L, 0)
                    }
                }
                num++
                left--
            }
        }
        return values
    }

    private fun field(data: ByteArray, at: Int, width: Int): Long {
        var value = 0L
        for (i in 0 until width) value = (value shl 8) or (data[at + i].toLong() and 0xFF)
        return value
    }

    private fun trailer(values: Map<String, ByteArray>): Long {
        values["Size"]?.let(::integers)?.firstOrNull()?.let { objectCount = maxOf(objectCount, it.toInt()) }
        if (!trailerSeen) {
            trailerSeen = true
            encrypted = values.containsKey("Encrypt")
            root = values["Root"]?.let { references(it) }?.firstOrNull() ?: -1
            info = values["Info"]?.takeIf { references(it).isNotEmpty() }
        } else if (root < 0) {
            root = values["Root"]?.let { references(it) }?.firstOrNull() ?: -1
        }
        return values["Prev"]?.let(::integers)?.firstOrNull() ?: -1L
    }

    private fun readAt(at: Long): Obj {
        if (at < 0 || at >= length) throw PdfPageSubset.Unsupported("object offset")
        var want = 4096L
        while (true) {
            val count = minOf(want, length - at).toInt()
            val whole = at + count >= length
            val buf = read(at, count)
            try {
                val lex = Lex(buf, 0, count, whole)
                if (lex.next() != T_INT) throw PdfPageSubset.Unsupported("no object at $at")
                val num = lex.int.toInt()
                if (lex.next() != T_INT) throw PdfPageSubset.Unsupported("no object at $at")
                val gen = lex.int.toInt()
                if (lex.next() != T_WORD || lex.text != "obj") throw PdfPageSubset.Unsupported("no object at $at")
                lex.skipSpace()
                val from = lex.p
                lex.skipValue()
                val to = lex.p
                var dataAt = -1L
                if (lex.next() == T_WORD && lex.text == "stream") {

                    var q = lex.p
                    if (q + 2 > count && !whole) throw Truncated()
                    if (q < count && buf[q].toInt() == 13) q++
                    if (q < count && buf[q].toInt() == 10) q++
                    dataAt = at + q
                }
                return Obj(num, gen, buf.copyOfRange(from, to), dataAt)
            } catch (_: Truncated) {
                if (whole || want >= MAX_OBJECT) throw PdfPageSubset.Unsupported("object at $at cut off")
                want *= 4
            }
        }
    }

    fun load(num: Int): Obj? {
        val entry = xref[num] ?: return null
        return when (entry.type) {
            1 -> readAt(base + entry.a).also {
                if (it.num != num) throw PdfPageSubset.Unsupported("object $num is not at its offset")
            }
            2 -> packed(entry.a.toInt(), entry.b, num)
            else -> null
        }
    }

    fun generation(num: Int): Int = xref[num]?.takeIf { it.type == 1 }?.b ?: 0

    private fun packed(holder: Int, index: Int, num: Int): Obj? {
        val (data, table) = streams[holder] ?: run {
            val entry = xref[holder]?.takeIf { it.type == 1 } ?: throw PdfPageSubset.Unsupported("object stream $holder")
            val stream = readAt(base + entry.a)
            if (stream.dataAt < 0) throw PdfPageSubset.Unsupported("object stream $holder")
            val bytes = decode(stream)
            val count = stream.value("N")?.let(::integers)?.firstOrNull()?.toInt() ?: throw PdfPageSubset.Unsupported("object stream $holder")
            val first = stream.value("First")?.let(::integers)?.firstOrNull()?.toInt() ?: throw PdfPageSubset.Unsupported("object stream $holder")
            if (count < 0 || first < 0 || first > bytes.size) throw PdfPageSubset.Unsupported("object stream $holder")
            val lex = Lex(bytes, 0, first, true)
            val pairs = IntArray(count * 2)
            for (i in pairs.indices) {
                if (lex.next() != T_INT) throw PdfPageSubset.Unsupported("object stream $holder")
                pairs[i] = lex.int.toInt() + if (i % 2 == 1) first else 0
            }
            (bytes to pairs).also { streams[holder] = it }
        }
        var slot = index
        if (slot < 0 || slot * 2 >= table.size || table[slot * 2] != num) {
            slot = (0 until table.size / 2).firstOrNull { table[it * 2] == num } ?: return null
        }
        val from = table[slot * 2 + 1]
        if (from < 0 || from > data.size) return null
        val lex = Lex(data, from, data.size, true)
        lex.skipSpace()
        val begin = lex.p
        lex.skipValue()
        return Obj(num, 0, data.copyOfRange(begin, lex.p), -1L)
    }

    fun dataLength(obj: Obj): StreamLength {
        val declared = obj.dict["Length"]?.let { range ->
            val lex = Lex(obj.raw, range.first, range.last + 1, true)
            when (lex.skipValue()) {
                T_INT -> lex.int
                T_REF -> load(lex.int.toInt())?.let { integers(it.raw).firstOrNull() }
                else -> null
            }
        }
        if (declared != null && declared >= 0 && endsAt(obj.dataAt + declared)) return StreamLength(declared, true)
        return StreamLength(findEnd(obj.dataAt) - obj.dataAt, false)
    }

    private fun endsAt(at: Long): Boolean {
        if (at < 0 || at >= length) return false
        val probe = read(at, minOf(16L, length - at).toInt())
        var i = 0
        while (i < probe.size && isSpace(probe[i].toInt())) i++
        return indexOf(probe, "endstream", i) == i
    }

    private fun findEnd(from: Long): Long {
        var at = from
        while (at < length) {
            val count = minOf(1L shl 16, length - at).toInt()
            val buf = read(at, count)
            val hit = indexOf(buf, "endstream", 0)
            if (hit >= 0) {
                var end = at + hit
                if (end - 1 >= from && read(end - 1, 1)[0].toInt() == 10) end--
                if (end - 1 >= from && read(end - 1, 1)[0].toInt() == 13) end--
                return end
            }
            if (at + count >= length) break

            at += count - 8
        }
        throw PdfPageSubset.Unsupported("stream without end")
    }

    fun copyData(at: Long, count: Long, out: Out) {
        if (at < 0 || count < 0 || at + count > length) throw PdfPageSubset.Unsupported("stream past the end")
        val buf = ByteArray(1 shl 16)
        var left = count
        file.seek(at)
        while (left > 0) {
            val step = minOf(left, buf.size.toLong()).toInt()
            file.readFully(buf, 0, step)
            out.write(buf, 0, step)
            left -= step
        }
    }

    private fun decode(obj: Obj): ByteArray {
        val length = dataLength(obj).bytes
        if (length > MAX_DECODED) throw PdfPageSubset.Unsupported("stream too large")
        val packed = read(obj.dataAt, length.toInt())
        val filters = obj.value("Filter")?.let { names(it) }.orEmpty()
        if (filters.isEmpty()) return packed
        if (filters.size != 1 || (filters[0] != "FlateDecode" && filters[0] != "Fl")) {
            throw PdfPageSubset.Unsupported("filter $filters")
        }
        val data = inflate(packed)
        val parms = obj.value("DecodeParms") ?: obj.value("DP") ?: return data
        val lex = Lex(parms, 0, parms.size, true)
        var kind = lex.next()
        while (kind != T_DICT_OPEN && kind != T_EOF) kind = lex.next()
        if (kind == T_EOF) return data
        lex.p = lex.start
        val entries = lex.dict() ?: return data
        fun number(key: String, fallback: Int): Int =
            entries[key]?.let { integers(parms.copyOfRange(it.first, it.last + 1)).firstOrNull()?.toInt() } ?: fallback
        val predictor = number("Predictor", 1)
        if (predictor == 1) return data
        if (predictor < 10) throw PdfPageSubset.Unsupported("predictor $predictor")
        val columns = number("Columns", 1)
        val sampleBytes = (number("Colors", 1) * number("BitsPerComponent", 8) + 7) / 8
        return unpredict(data, columns * sampleBytes, maxOf(1, sampleBytes))
    }

    private fun names(raw: ByteArray): List<String> {
        val out = ArrayList<String>()
        val lex = Lex(raw, 0, raw.size, true)
        while (true) {
            val kind = lex.next()
            if (kind == T_EOF) break
            if (kind == T_NAME) out += lex.text
        }
        return out
    }

    private fun inflate(packed: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(packed)
            val out = ByteArrayOutputStream(packed.size * 3)
            val buf = ByteArray(1 shl 16)
            while (!inflater.finished()) {
                val got = inflater.inflate(buf)
                if (got > 0) {
                    out.write(buf, 0, got)
                    if (out.size() > MAX_DECODED) throw PdfPageSubset.Unsupported("stream too large")
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    break
                }
            }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            throw PdfPageSubset.Unsupported("bad Flate data: ${e.message}")
        } finally {
            inflater.end()
        }
    }

    private fun unpredict(data: ByteArray, rowBytes: Int, step: Int): ByteArray {
        if (rowBytes <= 0) throw PdfPageSubset.Unsupported("predictor columns")
        val rows = data.size / (rowBytes + 1)
        val out = ByteArray(rows * rowBytes)
        for (row in 0 until rows) {
            val source = row * (rowBytes + 1)
            val type = data[source].toInt()
            val target = row * rowBytes
            for (i in 0 until rowBytes) {
                val left = if (i >= step) out[target + i - step].toInt() and 0xFF else 0
                val up = if (row > 0) out[target - rowBytes + i].toInt() and 0xFF else 0
                val corner = if (row > 0 && i >= step) out[target - rowBytes + i - step].toInt() and 0xFF else 0
                val add = when (type) {
                    0 -> 0
                    1 -> left
                    2 -> up
                    3 -> (left + up) / 2
                    4 -> {
                        val guess = left + up - corner
                        val toLeft = Math.abs(guess - left)
                        val toUp = Math.abs(guess - up)
                        val toCorner = Math.abs(guess - corner)
                        if (toLeft <= toUp && toLeft <= toCorner) left else if (toUp <= toCorner) up else corner
                    }
                    else -> throw PdfPageSubset.Unsupported("predictor row type $type")
                }
                out[target + i] = ((data[source + 1 + i].toInt() and 0xFF) + add).toByte()
            }
        }
        return out
    }

    fun pages(nodes: MutableSet<Int>): List<Page> {
        val catalog = load(root) ?: throw PdfPageSubset.Unsupported("no catalog")
        val top = catalog.value("Pages")?.let { references(it) }?.firstOrNull() ?: throw PdfPageSubset.Unsupported("no page tree")
        val out = ArrayList<Page>()
        val seen = HashSet<Int>()
        val stack = ArrayDeque<Page>()
        stack.addLast(Page(top, emptyMap()))
        while (stack.isNotEmpty()) {
            val at = stack.removeLast()
            if (!seen.add(at.num)) continue
            val obj = load(at.num) ?: continue
            val kids = obj.value("Kids")
            if (kids == null && obj.value("Type")?.let { names(it) }?.firstOrNull() != "Pages") {
                out += at
                continue
            }
            nodes += at.num
            var inherited = at.inherited
            for (key in INHERITED) obj.value(key)?.let { inherited = inherited + (key to it) }

            val lex = Lex(kids ?: ByteArray(0), 0, kids?.size ?: 0, true)
            val children = if (lex.skipValue() == T_REF) load(lex.int.toInt())?.let { references(it.raw) }.orEmpty()
                else references(kids ?: ByteArray(0))
            for (i in children.indices.reversed()) stack.addLast(Page(children[i], inherited))
        }
        return out
    }
}

internal class ImagePdfWriter(file: File) : Closeable {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)

    private val offsets = ArrayList<Long>()
    private val pages = ArrayList<Int>()

    var size = 0L
        private set

    init {
        write("%PDF-1.4\n%âãÏÓ\n")

        offsets += 0L
        offsets += 0L
    }

    fun addPage(image: ByteArray, pixelWidth: Int, pixelHeight: Int, jpeg: Boolean, width: Float, height: Float) {
        val w = String.format(Locale.US, "%.2f", width)
        val h = String.format(Locale.US, "%.2f", height)
        val page = begin()
        write("$page 0 obj\n<</Type/Page/Parent 2 0 R/MediaBox[0 0 $w $h]/Resources<</XObject<</Im ${page + 2} 0 R>>>>" +
            "/Contents ${page + 1} 0 R>>\nendobj\n")
        val content = "q $w 0 0 $h 0 0 cm /Im Do Q"
        begin()
        write("${page + 1} 0 obj\n<</Length ${content.length}>>\nstream\n$content\nendstream\nendobj\n")
        begin()
        val coding = if (jpeg) "/ColorSpace/DeviceRGB/Filter/DCTDecode" else "/ColorSpace/DeviceGray/Filter/FlateDecode"
        write("${page + 2} 0 obj\n<</Type/XObject/Subtype/Image/Width $pixelWidth/Height $pixelHeight/BitsPerComponent 8" +
            "$coding/Length ${image.size}>>\nstream\n")
        out.write(image)
        size += image.size
        write("\nendstream\nendobj\n")
        pages += page
    }

    fun finish() {
        offsets[0] = size
        write("1 0 obj\n<</Type/Catalog/Pages 2 0 R>>\nendobj\n")
        offsets[1] = size
        write("2 0 obj\n<</Type/Pages/Kids[${pages.joinToString(" ") { "$it 0 R" }}]/Count ${pages.size}>>\nendobj\n")
        val xrefAt = size
        val table = StringBuilder("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) table.append(String.format(Locale.US, "%010d 00000 n \n", offset))
        table.append("trailer\n<</Size ${offsets.size + 1}/Root 1 0 R>>\nstartxref\n$xrefAt\n%%EOF\n")
        write(table.toString())
        out.flush()
    }

    override fun close() = out.close()

    private fun begin(): Int {
        offsets += size
        return offsets.size
    }

    private fun write(text: String) {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        out.write(bytes)
        size += bytes.size
    }
}
