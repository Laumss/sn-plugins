package me.laumss.notipal.ui_common.filepicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object PickerCovers {
    private const val TAG = "InklingPickerCover"
    private const val VERSION = "v1"
    private const val DISK_LIMIT = 32L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 40L * 1024 * 1024
    private const val JPEG_QUALITY = 85

    val EXTENSIONS = setOf("pdf", "epub", "cbz", "zip", "note")

    @Volatile var noteRenderer: ((path: String, page: Int, name: String,
                                  done: (File?, String) -> Unit) -> Unit)? = null
    private const val NOTE_TIMEOUT_MS = 20_000L
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    private val memory = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    @Volatile private var trimmed = false

    private val pdfLock = Any()

    fun supports(extension: String) = extension.lowercase(Locale.ROOT) in EXTENSIONS

    fun load(ctx: Context, path: String, reqW: Int, reqH: Int): Bitmap? {
        if (reqW <= 0 || reqH <= 0) return null
        val file = File(path)
        if (!file.isFile) return null
        val key = cacheKey(file)
        memory.get(key)?.let { if (!it.isRecycled) return it }
        val dir = File(ctx.applicationContext.cacheDir, "me.laumss.notipal/picker-covers")
        val jpg = File(dir, "$key.jpg")
        val none = File(dir, "$key.none")
        if (none.exists()) return null
        if (jpg.length() > 0) {
            decodeFile(jpg, reqW, reqH)?.let {
                jpg.setLastModified(System.currentTimeMillis())
                memory.put(key, it)
                return it
            }
        }
        val started = android.os.SystemClock.elapsedRealtime()
        val ext = file.extension.lowercase(Locale.ROOT)
        var transient = false
        val bmp = try {
            when (ext) {
                "pdf" -> renderPdf(file, reqW, reqH)
                "epub" -> epubCover(file, reqW, reqH)
                "cbz", "zip" -> archiveCover(file, reqW, reqH)
                "note" -> {

                    transient = true
                    noteCover(file, key, reqW, reqH)?.also { transient = false }
                }
                else -> null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cover failed: $path", t)
            transient = transient || t is OutOfMemoryError
            null
        }
        dir.mkdirs()
        if (bmp == null) {

            if (!transient) runCatching { none.createNewFile() }

            if (ext == "note") {
                val latest = latestNoteFile(dir, file)
                if (latest.length() > 0) decodeFile(latest, reqW, reqH)?.let { return it }
            }
            Log.i(TAG, "no cover: ${file.name}")
            return null
        }
        runCatching {
            val tmp = File(dir, "$key.tmp")
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (!tmp.renameTo(jpg)) tmp.delete()
            if (ext == "note") jpg.copyTo(latestNoteFile(dir, file), overwrite = true)
        }
        Log.i(TAG, "cover ${file.name} ${bmp.width}x${bmp.height} " +
            "${android.os.SystemClock.elapsedRealtime() - started}ms")
        memory.put(key, bmp)
        if (!trimmed) {
            trimmed = true
            trimDisk(dir)
        }
        return bmp
    }

    private fun latestNoteFile(dir: File, file: File): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(file.absolutePath.toByteArray())
        return File(dir, "latest-" + digest.joinToString("") { "%02x".format(it) } + ".jpg")
    }

    private fun cacheKey(file: File): String {
        val id = "$VERSION|${file.absolutePath}|${file.lastModified()}|${file.length()}"
        val digest = MessageDigest.getInstance("SHA-1").digest(id.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun trimDisk(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_LIMIT) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_LIMIT * 3 / 4) break
            total -= f.length()
            f.delete()
        }
    }

    private fun noteCover(file: File, key: String, reqW: Int, reqH: Int): Bitmap? {
        val render = noteRenderer ?: return null
        val latch = java.util.concurrent.CountDownLatch(1)
        var rendered: File? = null
        render(file.absolutePath, 0, "$key.host.png") { png, message ->
            rendered = png
            if (png == null) Log.w(TAG, "note cover ${file.name}: $message")
            latch.countDown()
        }
        if (!latch.await(NOTE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "note cover ${file.name}: host timeout")
            return null
        }
        val png = rendered ?: return null
        return try {
            if (png.length() <= 0) null
            else decodeFile(png, reqW, reqH)?.let { fitInto(it, reqW, reqH) }
        } finally {
            png.delete()
        }
    }

    private fun renderPdf(file: File, reqW: Int, reqH: Int): Bitmap? = synchronized(pdfLock) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount <= 0) return null
                renderer.openPage(0).use { page ->
                    val pw = page.width.coerceAtLeast(1)
                    val ph = page.height.coerceAtLeast(1)
                    val s = min(reqW.toFloat() / pw, reqH.toFloat() / ph)
                    val w = (pw * s).roundToInt().coerceAtLeast(1)
                    val h = (ph * s).roundToInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    val m = Matrix().apply { setScale(w.toFloat() / pw, h.toFloat() / ph) }
                    page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }

    private fun epubCover(file: File, reqW: Int, reqH: Int): Bitmap? = ZipFile(file).use { zip ->
        val href = runCatching { epubCoverPath(zip) }.getOrNull()
        href?.let { zip.getEntry(it) }?.let { entry ->
            decodeEntry(zip, entry, reqW, reqH)?.let { return it }
        }
        val images = imageEntries(zip)
        val fallback = images.firstOrNull {
            it.name.substringAfterLast('/').contains("cover", ignoreCase = true)
        } ?: images.firstOrNull()
        fallback?.let { decodeEntry(zip, it, reqW, reqH) }
    }

    private fun epubCoverPath(zip: ZipFile): String? {
        val opfPath = zip.getEntry("META-INF/container.xml")?.let { entry ->
            zip.getInputStream(entry).use { input ->
                val p = Xml.newPullParser()
                p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                p.setInput(input, null)
                var found: String? = null
                while (p.next() != XmlPullParser.END_DOCUMENT && found == null) {
                    if (p.eventType == XmlPullParser.START_TAG && localName(p.name) == "rootfile") {
                        found = p.getAttributeValue(null, "full-path")
                    }
                }
                found
            }
        } ?: return null
        val opfEntry = zip.getEntry(opfPath) ?: return null
        val base = opfPath.substringBeforeLast('/', "")

        class Item(val id: String?, val href: String, val mediaType: String?, val properties: String?)
        val items = ArrayList<Item>()
        var metaCoverId: String? = null
        var guideCover: String? = null
        zip.getInputStream(opfEntry).use { input ->
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(input, null)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (localName(p.name)) {
                    "item" -> p.getAttributeValue(null, "href")?.let { href ->
                        items += Item(
                            p.getAttributeValue(null, "id"), href,
                            p.getAttributeValue(null, "media-type"),
                            p.getAttributeValue(null, "properties"),
                        )
                    }
                    "meta" -> if (p.getAttributeValue(null, "name") == "cover") {
                        metaCoverId = p.getAttributeValue(null, "content")
                    }
                    "reference" -> if (p.getAttributeValue(null, "type")
                            ?.equals("cover", ignoreCase = true) == true) {
                        guideCover = p.getAttributeValue(null, "href")
                    }
                }
            }
        }
        fun isImage(item: Item) = item.mediaType?.startsWith("image/") == true ||
            extOf(item.href) in IMAGE_EXTS

        items.firstOrNull { it.properties?.split(' ')?.contains("cover-image") == true && isImage(it) }
            ?.let { return resolve(base, it.href) }
        metaCoverId?.let { id ->
            items.firstOrNull { it.id == id }?.let { item ->
                if (isImage(item)) return resolve(base, item.href)

                if (guideCover == null) guideCover = item.href
            }
        }
        guideCover?.let { pageHref ->
            val pagePath = resolve(base, pageHref.substringBefore('#'))
            if (extOf(pagePath) in IMAGE_EXTS) return pagePath
            return firstImageInPage(zip, pagePath)
        }
        return null
    }

    private val imgSrc = Regex("""<img\b[^>]*?\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val svgHref = Regex("""<image\b[^>]*?\b(?:xlink:)?href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private fun firstImageInPage(zip: ZipFile, pagePath: String): String? {
        val entry = zip.getEntry(pagePath) ?: return null
        if (entry.size > 2L * 1024 * 1024) return null
        val html = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        val candidates = listOfNotNull(imgSrc.find(html), svgHref.find(html))
        val src = candidates.minByOrNull { it.range.first }?.groupValues?.get(1) ?: return null
        return resolve(pagePath.substringBeforeLast('/', ""), src)
    }

    private fun localName(name: String) = name.substringAfter(':')

    private fun extOf(path: String) = path.substringAfterLast('.', "").lowercase(Locale.ROOT)

    private fun resolve(base: String, href: String): String {
        val decoded = runCatching { URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") }.getOrDefault(href)
        val parts = ArrayList<String>()
        if (!decoded.startsWith("/")) base.split('/').filter { it.isNotEmpty() }.forEach { parts += it }
        for (seg in decoded.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += seg
            }
        }
        return parts.joinToString("/")
    }

    private fun archiveCover(file: File, reqW: Int, reqH: Int): Bitmap? = ZipFile(file).use { zip ->
        val first = imageEntries(zip).firstOrNull() ?: return null
        decodeEntry(zip, first, reqW, reqH)
    }

    private fun imageEntries(zip: ZipFile) = zip.entries().asSequence()
        .filter { !it.isDirectory && extOf(it.name) in IMAGE_EXTS }
        .filter { e ->
            !e.name.startsWith("__MACOSX/") && e.name.split('/').none { it.startsWith(".") }
        }
        .sortedWith(compareBy(naturalOrder) { it.name.lowercase(Locale.ROOT) })
        .toList()

    private val chunk = Regex("""\d+|\D+""")
    private val naturalOrder = Comparator<String> { a, b ->
        val ra = chunk.findAll(a).map { it.value }.toList()
        val rb = chunk.findAll(b).map { it.value }.toList()
        for (i in 0 until min(ra.size, rb.size)) {
            val x = ra[i]
            val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0')
                val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            } else x.compareTo(y)
            if (c != 0) return@Comparator c
        }
        ra.size - rb.size
    }

    private fun decodeEntry(zip: ZipFile, entry: java.util.zip.ZipEntry, reqW: Int, reqH: Int): Bitmap? {
        if (entry.size > MAX_ENTRY_BYTES) return null
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH)
        }
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return fitInto(raw, reqW, reqH)
    }

    private fun decodeFile(file: File, reqW: Int, reqH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH)
        }
        return BitmapFactory.decodeFile(file.path, opts)
    }

    private fun sampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        while (w / (sample * 2) >= reqW && h / (sample * 2) >= reqH) sample *= 2
        return sample
    }

    private fun fitInto(src: Bitmap, reqW: Int, reqH: Int): Bitmap {
        val s = min(1f, min(reqW.toFloat() / src.width, reqH.toFloat() / src.height))
        val w = max(1, (src.width * s).roundToInt())
        val h = max(1, (src.height * s).roundToInt())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, null, android.graphics.Rect(0, 0, w, h),
                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
        if (out !== src) src.recycle()
        return out
    }
}
