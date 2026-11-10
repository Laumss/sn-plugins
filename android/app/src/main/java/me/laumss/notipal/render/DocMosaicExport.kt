package me.laumss.notipal.render

import me.laumss.notipal.NotipalDirs
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.util.JsonWriter
import android.util.Log
import android.util.SizeF
import com.facebook.react.bridge.ReactApplicationContext
import com.ratta.supernote.plugincommon.data.common.trail.Element
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object DocMosaicExport {
    private const val TAG = "DocMosaicExport"
    const val PREVIEW_WIDTH = 1440
    private const val HD_DPI = 600f
    private const val HD_MAX_LONG_SIDE = 7200
    private const val BAND_HEIGHT = 1024
    private val HOST_OUTPUT_DIR get() = NotipalDirs.hostRender

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "Notipal-doc-export").apply { isDaemon = true } }

    fun hdFile(dir: File, index: Int) = File(dir, "$index.hd.png")
    fun inkFile(dir: File, index: Int) = File(dir, "$index.ink.json")
    fun marksFile(dir: File, index: Int) = File(dir, "$index.marks.json")
    fun previewFile(dir: File, index: Int) = File(dir, "$index.png")

    fun export(
        ctx: ReactApplicationContext,
        pdfPath: String,
        pages: List<Int>,
        outDir: File,
        title: String,
        onProgress: (Int, Int) -> Unit,
        cancelled: () -> Boolean,

        onAnnotationsSkipped: () -> Unit = {},
        onDone: (Boolean, String?) -> Unit,
    ) {
        fun start(opened: Boolean, livePage: Int?, displayValue: Any?, displayError: String?) {
            PageImageSource.callHost(ctx, "getMarkPages", listOf(pdfPath)) { markValue, markError ->

                val markPages = if (markError != null) null else pageList(markValue).toSet()
                if (markError != null) Log.w(TAG, "getMarkPages: $markError")
                Export(ctx, pdfPath, pages, outDir, title, opened, livePage, displayValue, displayError, markPages,
                    onProgress, cancelled, onAnnotationsSkipped, onDone).start()
            }
        }
        PageImageSource.callHost(ctx, "getCurrentFilePath", emptyList()) { currentValue, _ ->

            if (!PageImageSource.samePath(currentValue as? String, pdfPath)) {
                start(opened = false, livePage = null, displayValue = null, displayError = null)
                return@callHost
            }
            PageImageSource.callHost(ctx, "getCurrentPageNum", emptyList()) { liveValue, _ ->
                val livePage = (liveValue as? Number)?.toInt()

                PageImageSource.callHost(ctx, "getPageDisplaySize", emptyList()) { displayValue, displayError ->
                    if (displayError != null) Log.w(TAG, "getPageDisplaySize: $displayError (using screen rotation)")
                    start(opened = true, livePage, displayValue, displayError)
                }
            }
        }
    }

    private class Export(
        val ctx: ReactApplicationContext,
        val pdfPath: String,
        val pages: List<Int>,
        val outDir: File,
        val title: String,
        val opened: Boolean,
        val livePage: Int?,
        val hostDisplay: Any?,
        val hostDisplayError: String?,
        val markPages: Set<Int>?,
        val onProgress: (Int, Int) -> Unit,
        val cancelled: () -> Boolean,
        val onAnnotationsSkipped: () -> Unit,
        val onDone: (Boolean, String?) -> Unit,
    ) {
        private val started = SystemClock.elapsedRealtime()
        private val hasMarkFile = File("$pdfPath.mark").isFile
        private var renderer: PdfRenderer? = null
        private var descriptor: ParcelFileDescriptor? = null
        private var marks: List<SupernoteTextMarks.Mark> = emptyList()
        private var finished = false

        private fun annotationPath(page: Int): String =
            if (page == livePage) pdfPath else File("$pdfPath.mark").takeIf { it.isFile }?.absolutePath ?: pdfPath

        fun start() {
            worker.execute {
                val error = runCatching {
                    check(outDir.isDirectory || outDir.mkdirs()) { "cannot create $outDir" }
                    descriptor = ParcelFileDescriptor.open(File(pdfPath), ParcelFileDescriptor.MODE_READ_ONLY)
                    renderer = PdfRenderer(descriptor!!)
                    marks = runCatching { SupernoteTextMarks.read(File("$pdfPath.mark")).marks }
                        .onFailure { Log.w(TAG, "text marks unreadable: ${it.message}") }
                        .getOrDefault(emptyList())
                    pages.forEach { require(it in 0 until renderer!!.pageCount) { "page ${it + 1} out of range" } }
                }.exceptionOrNull()
                main.post { if (error != null) fail(messageOf(error)) else step(0, 0) }
            }
        }

        private fun step(index: Int, attempt: Int) {
            if (finished) return
            if (cancelled()) { fail("cancelled"); return }
            if (index >= pages.size) { complete(); return }
            if (attempt == 0) onProgress(index + 1, pages.size)
            val page = pages[index]

            val needsElements = page == livePage || (hasMarkFile && (markPages == null || page in markPages))
            if (!needsElements) {
                render(index, page, null, emptyList(), "") {}
                return
            }
            val source = annotationPath(page)

            fun unreadable(message: String) {
                if (opened) {
                    fail(message)
                    return
                }
                Log.w(TAG, "[DOC-MOSAIC] page=${page + 1} annotations skipped: $message")
                onAnnotationsSkipped()
                render(index, page, null, emptyList(), "") {}
            }
            PageImageSource.callHost(ctx, "getPageSize", listOf(source, page)) { sizeValue, sizeError ->
                val size = sizeValue as? SizeF
                if (size == null || !(size.width > 0f) || !(size.height > 0f)) {
                    unreadable(sizeError ?: "page ${page + 1} size unavailable")
                    return@callHost
                }
                PageImageSource.loadPageElements(ctx, source, page) { elements, error, release ->
                    if (elements == null) {
                        release()
                        if (error == PageImageSource.TRAIL_CACHE_BUSY && attempt + 1 < PageImageSource.TRAIL_CACHE_BUSY_RETRY_COUNT) {
                            main.postDelayed({ step(index, attempt + 1) }, PageImageSource.TRAIL_CACHE_BUSY_RETRY_MS)
                        } else {
                            unreadable(error ?: "page ${page + 1} annotations unavailable")
                        }
                        return@loadPageElements
                    }
                    withLayerPreview(page, elements) { preview ->
                        render(index, page, size, elements, preview, release)
                    }
                }
            }
        }

        private fun withLayerPreview(page: Int, elements: List<Element>, onReady: (String) -> Unit) {
            val needed = elements.any {
                it.type == Element.TRAIL_TYPE_PICTURE &&
                    it.picture?.picturePath?.let { p -> p.isEmpty() || !File(p).canRead() } != false
            }
            if (!needed) return onReady("")
            val dir = File(HOST_OUTPUT_DIR).apply { mkdirs() }
            val path = File(dir, "doc_mosaic_${System.nanoTime()}_layer.png").absolutePath
            PageImageSource.callHost(ctx, "generateLayerPreviewImage", listOf(pdfPath, page, 0, path)) { v0, _ ->
                if (v0 == true && File(path).isFile) return@callHost onReady(path)
                PageImageSource.callHost(ctx, "generateLayerPreviewImage", listOf(pdfPath, page, -1, path)) { v1, e1 ->
                    if (v1 != true || !File(path).isFile) Log.w(TAG, "layer preview unavailable: $e1, pictures skipped")
                    onReady(if (v1 == true && File(path).isFile) path else "")
                }
            }
        }

        private fun render(index: Int, page: Int, pageSize: SizeF?, elements: List<Element>, layerPath: String, release: () -> Unit) {
            worker.execute {
                val pageStarted = SystemClock.elapsedRealtime()
                val error = try {
                    renderPage(index, page, pageSize, elements, layerPath)
                    null
                } catch (e: Throwable) {
                    e
                } finally {
                    release()
                    if (layerPath.isNotEmpty()) runCatching { File(layerPath).delete() }
                }
                Log.i(TAG, "[DOC-MOSAIC] page=${page + 1} (${index + 1}/${pages.size}) elements=${elements.size} " +
                    "marks=${marks.count { it.page == page }} elapsedMs=${SystemClock.elapsedRealtime() - pageStarted}")
                main.post { if (error != null) fail(messageOf(error)) else step(index + 1, 0) }
            }
        }

        private fun renderPage(index: Int, page: Int, hostPageSize: SizeF?, elements: List<Element>, layerPath: String) {
            val pageMarks = marks.filter { it.page == page }
            var layer: Bitmap? = null
            try {
                renderer!!.openPage(page).use { pdfPage ->
                    val ptW = pdfPage.width.toFloat()
                    val ptH = pdfPage.height.toFloat()
                    val pageSize = hostPageSize?.let { orientLikePdf(it, ptW, ptH, page) }

                    val rest = if (pageSize != null) writeInk(index, page, pageSize, elements) else elements
                    layer = layerPath.takeIf { it.isNotEmpty() }?.let { decodeLayer(it, pageSize) }
                    writeMarks(index, ptW, ptH, pageMarks)
                    val scale = min(HD_DPI / 72f, HD_MAX_LONG_SIDE / max(ptW, ptH))
                    val hdW = (ptW * scale).roundToInt().coerceAtLeast(1)
                    val hdH = (ptH * scale).roundToInt().coerceAtLeast(1)
                    val layers = PageLayers(pdfPage, ptW, ptH, rest, pageSize, layer)

                    val previewH = (PREVIEW_WIDTH * hdH.toFloat() / hdW).roundToInt().coerceAtLeast(1)
                    val preview = Bitmap.createBitmap(PREVIEW_WIDTH, previewH, Bitmap.Config.ARGB_8888)
                    try {
                        layers.draw(preview, PREVIEW_WIDTH, previewH, 0, previewH)
                        writeAtomically(previewFile(outDir, index)) { GrayPngWriter.write(it, preview) }
                    } finally {
                        preview.recycle()
                    }

                    val band = Bitmap.createBitmap(hdW, min(BAND_HEIGHT, hdH), Bitmap.Config.ARGB_8888)
                    try {
                        writeAtomically(hdFile(outDir, index)) { file ->
                            GrayPngWriter(file, hdW, hdH).use { png ->
                                var top = 0
                                while (top < hdH) {
                                    if (cancelled()) throw java.io.InterruptedIOException("cancelled")
                                    val rows = min(band.height, hdH - top)
                                    layers.draw(band, hdW, hdH, top, rows)
                                    png.writeRows(band, rows)
                                    top += rows
                                }
                                png.finish()
                            }
                        }
                    } finally {
                        band.recycle()
                    }
                    Log.i(TAG, "[DOC-MOSAIC] page=${page + 1} pt=${ptW}x$ptH hd=${hdW}x$hdH preview=${PREVIEW_WIDTH}x$previewH " +
                        "pageSize=$pageSize hdBytes=${hdFile(outDir, index).length()}")
                }
            } finally {
                layer?.recycle()
            }
        }

        private fun orientLikePdf(size: SizeF, ptW: Float, ptH: Float, page: Int): SizeF {
            val pageLandscape = size.width > size.height
            val pdfLandscape = ptW > ptH
            if (ptW == ptH || size.width == size.height || pageLandscape == pdfLandscape) return size
            Log.w(TAG, "[DOC-MOSAIC] page=${page + 1} host page size ${size.width.toInt()}x${size.height.toInt()} " +
                "oriented unlike the pdf page ${ptW.toInt()}x${ptH.toInt()}pt; swapped")
            return SizeF(size.height, size.width)
        }

        private fun writeInk(index: Int, page: Int, pageSize: SizeF, elements: List<Element>): List<Element> {
            val rest = ArrayList<Element>()
            var count = 0
            val target = inkFile(outDir, index)
            val temp = File(outDir, target.name + ".tmp")

            val frame = if (page == livePage) PageDisplayFrames.resolve(ctx, hostDisplay, hostDisplayError,
                pageSize.width, pageSize.height, "DOC-MOSAIC page=${page + 1}") else null
            val coordinates = frame?.coordinates(pageSize.width, pageSize.height)
            val redraw = elements.firstOrNull { it.contoursSrc?.any { ring -> ring.isNotEmpty() } == true }
                ?.let { "${it.redrawWidth}x${it.redrawHeight}" } ?: "none"
            Log.i(TAG, "[DOC-MOSAIC] page=${page + 1} source=${if (page == livePage) "live-display" else "file-page"} " +
                "pageSize=${pageSize.width.toInt()}x${pageSize.height.toInt()} displayFrame=${frame ?: "page"} redraw=$redraw " +
                "contourScale=${coordinates?.scaleX ?: 1f},${coordinates?.scaleY ?: 1f}")
            try {
                JsonWriter(temp.bufferedWriter()).use { out ->
                    out.beginObject().name("kind").value("note").name("version").value(2L)
                    out.name("coordinateSpace").value("note-page-px").name("source").value(pdfPath)
                    out.name("pages").beginArray().beginObject()
                    out.name("pageSize").beginObject().name("width").value(pageSize.width.toInt().toLong())
                        .name("height").value(pageSize.height.toInt().toLong()).endObject()
                    out.name("strokes").beginArray()
                    for (el in elements) {
                        if (cancelled()) throw java.io.InterruptedIOException("cancelled")
                        val reason = ClipboardElementIo.writeStroke(out, el, includeContours = true, noteCoordinates = coordinates)
                        if (reason == null) count++ else if (reason != "emptyStroke") rest.add(el)
                    }
                    out.endArray().name("strokeCount").value(count.toLong()).endObject().endArray().endObject()
                }
                if (count > 0) publish(temp, target)
            } finally {
                temp.delete()
            }
            return rest
        }

        private fun writeMarks(index: Int, ptW: Float, ptH: Float, pageMarks: List<SupernoteTextMarks.Mark>) {
            if (pageMarks.isEmpty()) return
            val list = JSONArray()
            for (mark in pageMarks) {
                val rects = JSONArray()
                for (r in mark.rects) rects.put(JSONArray().put(r.x0.toDouble()).put(r.y0.toDouble()).put(r.x1.toDouble()).put(r.y1.toDouble()))
                list.put(JSONObject().put("type", mark.type).put("rects", rects))
            }
            val json = JSONObject().put("pdfWidth", ptW.toDouble()).put("pdfHeight", ptH.toDouble()).put("marks", list)
            writeAtomically(marksFile(outDir, index)) { it.writeText(json.toString()) }
        }

        private fun complete() {
            worker.execute {
                val error = runCatching {
                    closeRenderer()
                    val meta = JSONObject().put("version", 2).put("kind", "doc").put("title", title)
                        .put("source", pdfPath).put("pages", JSONArray(pages)).put("previewWidth", PREVIEW_WIDTH)
                    writeAtomically(File(outDir, "doc.json")) { it.writeText(meta.toString()) }
                }.exceptionOrNull()
                main.post {
                    if (error != null) { fail(messageOf(error)); return@post }
                    finished = true
                    Log.i(TAG, "exported doc=$pdfPath pages=${pages.map { it + 1 }} dir=${outDir.name} " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - started}")
                    onDone(true, null)
                }
            }
        }

        private fun fail(message: String) {
            if (finished) return
            finished = true
            Log.w(TAG, "export failed doc=$pdfPath: $message")
            worker.execute {
                closeRenderer()
                runCatching { outDir.deleteRecursively() }
                main.post { onDone(false, message) }
            }
        }

        private fun closeRenderer() {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
            renderer = null
            descriptor = null
        }
    }

    private class PageLayers(
        val page: PdfRenderer.Page,
        val ptW: Float,
        val ptH: Float,
        val elements: List<Element>,
        val pageSize: SizeF?,
        val layer: Bitmap?,
    ) {
        private val matrix = Matrix()

        fun draw(bitmap: Bitmap, outW: Int, outH: Int, top: Int, rows: Int) {
            bitmap.eraseColor(Color.WHITE)
            matrix.setScale(outW / ptW, outH / ptH)
            matrix.postTranslate(0f, -top.toFloat())
            page.render(bitmap, Rect(0, 0, outW, rows), matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val canvas = Canvas(bitmap)
            canvas.clipRect(0, 0, outW, rows)
            canvas.translate(0f, -top.toFloat())
            if (elements.isNotEmpty() && pageSize != null) {
                val pageW = pageSize.width.toInt().coerceAtLeast(1)
                val pageH = pageSize.height.toInt().coerceAtLeast(1)
                val save = canvas.save()
                canvas.scale(outW.toFloat() / pageW, outH.toFloat() / pageH)
                VectorPageRenderer.drawElements(canvas, elements, pageW, pageH, layer)
                canvas.restoreToCount(save)
            }
        }
    }

    private fun decodeLayer(path: String, pageSize: SizeF?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val target = pageSize?.width?.toInt()?.coerceAtLeast(1) ?: bounds.outWidth
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target) sample *= 2
        return runCatching { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull()
    }

    private fun writeAtomically(target: File, write: (File) -> Unit) {
        val temp = File(target.parentFile, target.name + ".tmp")
        try {
            write(temp)
            publish(temp, target)
        } finally {
            temp.delete()
        }
    }

    private fun publish(temp: File, target: File) {
        check(temp.length() > 0L) {
            "write failed: ${target.name} (temp ${if (temp.exists()) "empty" else "missing"}, " +
                "dir exists=${target.parentFile?.isDirectory})"
        }
        try {
            Os.rename(temp.path, target.path)
        } catch (e: ErrnoException) {
            Log.w(TAG, "rename ${temp.name} -> ${target.name} failed: ${e.message}; copying instead")
            temp.copyTo(target, overwrite = true)
        }
    }

    private fun pageList(value: Any?): List<Int> = when (value) {
        is IntArray -> value.toList()
        is Array<*> -> value.mapNotNull { (it as? Number)?.toInt() }
        is List<*> -> value.mapNotNull { (it as? Number)?.toInt() }
        is com.facebook.react.bridge.ReadableArray -> value.toArrayList().mapNotNull { (it as? Number)?.toInt() }
        else -> emptyList()
    }

    private fun messageOf(error: Throwable): String = error.message ?: error.javaClass.simpleName
}
