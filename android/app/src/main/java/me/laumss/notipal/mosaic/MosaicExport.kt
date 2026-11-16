package me.laumss.notipal.mosaic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
import android.util.SizeF
import com.facebook.react.bridge.ReactApplicationContext
import com.ratta.supernote.plugincommon.data.common.trail.Element
import dalvik.system.PathClassLoader
import me.laumss.notipal.clipboard.ClipboardIo
import me.laumss.notipal.capture.DocHd
import me.laumss.notipal.common.Log
import me.laumss.notipal.host.GrayPngWriter
import me.laumss.notipal.host.HostProbe
import me.laumss.notipal.host.NotePageCoordinates
import me.laumss.notipal.host.PageDisplayFrames
import me.laumss.notipal.host.PageRender
import me.laumss.notipal.host.SupernoteTextMarks
import me.laumss.notipal.host.VectorPageRenderer
import me.laumss.notipal.ui_common.Style
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

internal object DocMosaicExport {
    private const val TAG = "DocMosaicExport"
    const val PREVIEW_WIDTH = 1440
    private const val BAND_HEIGHT = 1024

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
            PageRender.callHost(ctx, "getMarkPages", listOf(pdfPath)) { markValue, markError ->

                val markPages = if (markError != null) null else pageList(markValue).toSet()
                if (markError != null) Log.w(TAG, "getMarkPages: $markError")
                Export(ctx, pdfPath, pages, outDir, title, opened, livePage, displayValue, displayError, markPages,
                    onProgress, cancelled, onAnnotationsSkipped, onDone).start()
            }
        }
        PageRender.callHost(ctx, "getCurrentFilePath", emptyList()) { currentValue, _ ->

            if (!PageRender.samePath(currentValue as? String, pdfPath)) {
                start(opened = false, livePage = null, displayValue = null, displayError = null)
                return@callHost
            }
            PageRender.callHost(ctx, "getCurrentPageNum", emptyList()) { liveValue, _ ->
                val livePage = (liveValue as? Number)?.toInt()

                PageRender.callHost(ctx, "getPageDisplaySize", emptyList()) { displayValue, displayError ->
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
            PageRender.callHost(ctx, "getPageSize", listOf(source, page)) { sizeValue, sizeError ->
                val size = sizeValue as? SizeF
                if (size == null || !(size.width > 0f) || !(size.height > 0f)) {
                    unreadable(sizeError ?: "page ${page + 1} size unavailable")
                    return@callHost
                }
                PageRender.loadPageElements(ctx, source, page) { elements, error, release ->
                    if (elements == null) {
                        release()
                        if (error == PageRender.TRAIL_CACHE_BUSY && attempt + 1 < PageRender.TRAIL_CACHE_BUSY_RETRY_COUNT) {
                            main.postDelayed({ step(index, attempt + 1) }, PageRender.TRAIL_CACHE_BUSY_RETRY_MS)
                        } else {
                            unreadable(error ?: "page ${page + 1} annotations unavailable")
                        }
                        return@loadPageElements
                    }
                    PageRender.withLayerPreview(ctx, pdfPath, page, "doc_mosaic_${System.nanoTime()}", elements) { preview ->
                        render(index, page, size, elements, preview, release)
                    }
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
                    layer = layerPath.takeIf { it.isNotEmpty() }?.let { DocHd.decodeLayer(it, pageSize) }
                    writeMarks(index, ptW, ptH, pageMarks)
                    val scale = DocHd.hdScale(ptW, ptH)
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
                        val reason = ClipboardIo.writeStroke(out, el, includeContours = true, noteCoordinates = coordinates)
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
            bitmap.eraseColor(Style.paper)
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

internal object NoteMosaicExport {

    private const val TAG = "NoteMosaicExport"

    private const val TITLE_INK_WHITE = 0xFE

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Notipal-note-export").apply { isDaemon = true }
    }

    class Exported(val notePath: String, val pageCount: Int, val strokeCount: Int)

    interface PageSink {
        fun accept(page: Map<String, Any>)
        fun finish()
        fun abort()
    }

    fun exportCurrentNote(
        ctx: ReactApplicationContext,
        outFile: File,

        pages: List<Int>?,
        onProgress: (Int, Int) -> Unit,
        cancelled: () -> Boolean,
        sink: PageSink,
        onDone: (Exported?, String?) -> Unit,
    ) {
        PageRender.callHost(ctx, "getCurrentFilePath", emptyList()) { pathValue, pathError ->
            val path = (pathValue as? String)?.takeIf { it.endsWith(".note", ignoreCase = true) }
            if (path == null) {
                onDone(null, pathError ?: "current file is not a note: $pathValue")
                return@callHost
            }
            export(ctx, path, opened = true, outFile, pages, onProgress, cancelled, sink, onDone)
        }
    }

    fun exportNote(
        ctx: ReactApplicationContext,
        path: String,
        outFile: File,
        pages: List<Int>?,
        onProgress: (Int, Int) -> Unit,
        cancelled: () -> Boolean,
        sink: PageSink,
        onDone: (Exported?, String?) -> Unit,
    ) {
        PageRender.callHost(ctx, "getCurrentFilePath", emptyList()) { current, _ ->
            val opened = PageRender.samePath(current as? String, path)
            export(ctx, path, opened, outFile, pages, onProgress, cancelled, sink, onDone)
        }
    }

    private fun export(
        ctx: ReactApplicationContext,
        path: String,
        opened: Boolean,
        outFile: File,
        pages: List<Int>?,
        onProgress: (Int, Int) -> Unit,
        cancelled: () -> Boolean,
        sink: PageSink,
        onDone: (Exported?, String?) -> Unit,
    ) {
        PageRender.callHost(ctx, "getNoteTotalPageNum", listOf(path)) { totalValue, totalError ->
            val total = (totalValue as? Number)?.toInt()?.takeIf { it > 0 }
            if (total == null) {
                onDone(null, totalError ?: "note page count empty")
                return@callHost
            }
            val selected = pages?.filter { it in 0 until total }?.distinct()?.sorted() ?: (0 until total).toList()
            if (!opened) {

                if (selected.isEmpty()) onDone(null, "no pages selected")
                else Export(ctx, path, selected, null, null, -1, outFile, onProgress, cancelled, sink, onDone).start()
                return@callHost
            }

            PageRender.callHost(ctx, "getPageDisplaySize", emptyList()) { displayValue, displayError ->
                if (displayError != null) Log.w(TAG, "getPageDisplaySize: $displayError (using screen rotation)")

                PageRender.callHost(ctx, "getCurrentPageNum", emptyList()) { pageValue, pageError ->
                    val livePage = (pageValue as? Number)?.toInt()?.takeIf { it in 0 until total }
                    if (livePage == null) onDone(null, pageError ?: "current note page unavailable")
                    else if (selected.isEmpty()) onDone(null, "no pages selected")
                    else Export(ctx, path, selected, displayValue, displayError, livePage, outFile, onProgress, cancelled, sink, onDone).start()
                }
            }
        }
    }

    private class Export(
        val ctx: ReactApplicationContext,
        val path: String,
        val selected: List<Int>,

        val hostDisplay: Any?,
        val hostDisplayError: String?,
        val livePage: Int,
        val outFile: File,
        val onProgress: (Int, Int) -> Unit,
        val cancelled: () -> Boolean,
        val sink: PageSink,
        val onDone: (Exported?, String?) -> Unit,
    ) {
        private val started = SystemClock.elapsedRealtime()
        private var strokeCount = 0
        private var finished = false
        private val total = selected.size

        fun start() = step(0, 0)

        private fun step(index: Int, attempt: Int) {
            if (finished) return
            if (cancelled()) { fail("cancelled"); return }
            if (index >= total) {
                complete()
                return
            }
            val page = selected[index]
            if (attempt == 0) onProgress(index + 1, total)
            PageRender.callHost(ctx, "getPageSize", listOf(path, page)) { sizeValue, sizeError ->
                val size = sizeValue as? SizeF
                if (size == null || !(size.width > 0f) || !(size.height > 0f)) {
                    fail(sizeError ?: "page $page size unavailable")
                    return@callHost
                }
                PageRender.loadPageElements(ctx, path, page) { elements, error, release ->
                    if (elements == null) {
                        release()
                        if (error == PageRender.TRAIL_CACHE_BUSY && attempt + 1 < PageRender.TRAIL_CACHE_BUSY_RETRY_COUNT) {
                            mainHandler.postDelayed({ step(index, attempt + 1) }, PageRender.TRAIL_CACHE_BUSY_RETRY_MS)
                        } else {
                            fail(error ?: "page $page elements unavailable")
                        }
                        return@loadPageElements
                    }

                    PageRender.withLayerPreview(ctx, path, page, "note_mosaic_${System.nanoTime()}", elements) { layerPath ->
                        worker.execute {
                            val writeError = try {
                                val copied = try { copyPage(page, index, size, elements, layerPath) } finally {
                                    release()
                                    if (layerPath.isNotEmpty()) runCatching { File(layerPath).delete() }
                                }
                                sink.accept(copied)
                                null
                            } catch (e: Throwable) {
                                e
                            }
                            mainHandler.post {
                                if (writeError != null) fail(messageOf(writeError)) else step(index + 1, 0)
                            }
                        }
                    }
                }
            }
        }

        private fun copyPage(page: Int, index: Int, size: SizeF, elements: List<Element>, layerPath: String): Map<String, Any> {
            val started = SystemClock.elapsedRealtime()
            val pageW = size.width.toInt()
            val pageH = size.height.toInt()

            val frame = if (page == livePage) PageDisplayFrames.resolve(ctx, hostDisplay, hostDisplayError,
                size.width, size.height, "NOTE-MOSAIC page=${page + 1}") else null
            val coordinates = frame?.coordinates(size.width, size.height) ?: NotePageCoordinates.pagePixels(size.width, size.height)

            val titleStyles = HashMap<Int, Int>()
            for (el in elements) {
                val title = el.title?.takeIf { el.type == Element.TRAIL_TYPE_TILE && it.style in 1..4 } ?: continue
                title.controlTrailNums.orEmpty().forEach { if (it >= 0) titleStyles[it] = title.style }
            }
            val strokes = ArrayList<Map<String, Any>>()
            var pageStrokes = 0
            var contourStrokes = 0
            var whiteInk = 0

            val titleInk = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            val penTypes = linkedMapOf<Int, Int>()
            val skipped = linkedMapOf<String, Int>()
            val layer = layerPath.takeIf { it.isNotEmpty() }?.let { VectorPageRenderer.decodeSampledBitmap(it, pageW, pageH) }
            val cards = PageCards(page, pageW, pageH, layer)
            try {
                for (el in elements) {
                    if (cancelled()) throw java.io.InterruptedIOException("cancelled")
                    if (el.type != Element.TRAIL_TYPE_STROKE && el.type != Element.TRAIL_TYPE_GEO) {
                        cards.add(el)?.let { skipped[it] = (skipped[it] ?: 0) + 1 }
                        continue
                    }
                    val titleStyle = titleStyles[el.trailNumInPage] ?: titleStyles[el.trailNum]
                    val white = titleStyle == 1 || titleStyle == 2
                    val reason = ClipboardIo.copyNoteStroke(el, coordinates, if (white) TITLE_INK_WHITE else null) { stroke ->
                        strokes.add(stroke)
                        if (titleStyle != null) {
                            val points = stroke["points"] as FloatArray
                            for (i in 0 until points.size / 2) {
                                titleInk[0] = minOf(titleInk[0], points[i * 2]); titleInk[1] = minOf(titleInk[1], points[i * 2 + 1])
                                titleInk[2] = maxOf(titleInk[2], points[i * 2]); titleInk[3] = maxOf(titleInk[3], points[i * 2 + 1])
                            }
                        }
                    }
                    if (reason == null) {
                        strokeCount++; pageStrokes++
                        if (white) whiteInk++
                        val pen = if (el.type == Element.TRAIL_TYPE_GEO) el.geometry?.penType else el.stroke?.penType
                        if (pen != null) penTypes[pen] = (penTypes[pen] ?: 0) + 1
                        if (el.contoursSrc?.any { it.isNotEmpty() } == true) contourStrokes++
                    } else {
                        skipped[reason] = (skipped[reason] ?: 0) + 1
                        if (reason == "emptyStroke" && skipped[reason] == 1) {
                            Log.i(TAG, "[NOTE-MOSAIC] empty stroke page=${page + 1} trail=${el.trailNumInPage} " +
                                "pen=${el.stroke?.penType} points=${el.stroke?.points?.size ?: 0} contours=${el.contoursSrc?.size ?: 0}")
                        }

                        check(reason == "emptyStroke" || el.type != Element.TRAIL_TYPE_STROKE) {
                            "Handwriting export incomplete: page=${page + 1} $reason trail=${el.trailNumInPage} contours=${el.contoursSrc?.size ?: 0}"
                        }
                    }
                }
            } finally {
                layer?.recycle()
            }

            val redraw = elements.firstOrNull { it.contoursSrc?.any { ring -> ring.isNotEmpty() } == true }
                ?.let { "${it.redrawWidth}x${it.redrawHeight}" } ?: "none"
            val titles = if (cards.titleRects.isEmpty()) "" else " titles=${cards.titleRects} titleInk=" +
                (if (titleInk[0] <= titleInk[2]) titleInk.joinToString(",", "[", "]") { it.toInt().toString() } else "none") +
                " whiteInk=$whiteInk"
            Log.i(TAG, "[NOTE-MOSAIC] page=${page + 1}/$total source=${if (page == livePage) "live-display" else "file-page"} livePage=${livePage + 1} pageSize=$size displayFrame=${frame ?: "page"} redraw=$redraw contourScale=${coordinates.scaleX},${coordinates.scaleY} strokes=$pageStrokes contours=$contourStrokes pens=$penTypes cards=${cards.roles} skippedPictures=${cards.skippedPictures} layerPreview=${layerPath.isNotEmpty()}$titles skipped=$skipped copyMs=${SystemClock.elapsedRealtime() - started}")
            return mapOf("op" to "page", "index" to index, "width" to pageW, "height" to pageH,
                "strokes" to strokes, "cards" to cards.toList())
        }

        private fun complete() {
            worker.execute {
                val error = runCatching {
                    sink.finish()
                    outFile.writeText(org.json.JSONObject().put("kind", "prepared-note")
                        .put("version", 1).put("source", path).put("pages", total).put("strokes", strokeCount).toString())
                }.exceptionOrNull()
                mainHandler.post {
                    if (error != null) {
                        fail(messageOf(error))
                        return@post
                    }
                    finished = true
                    Log.i(TAG, "exported note=$path pages=$total strokes=$strokeCount " +
                        "bytes=${outFile.length()} elapsedMs=${SystemClock.elapsedRealtime() - started}")
                    onDone(Exported(path, total, strokeCount), null)
                }
            }
        }

        private fun fail(message: String) {
            if (finished) return
            finished = true
            Log.w(TAG, "export failed note=$path: $message")
            worker.execute {
                runCatching { sink.abort() }
                runCatching { outFile.delete() }
                mainHandler.post { onDone(null, message) }
            }
        }

        private fun messageOf(error: Throwable): String = error.message ?: error.javaClass.simpleName
    }

    private class PageCards(val page: Int, val pageW: Int, val pageH: Int, val layer: Bitmap?) {
        private val titles = ArrayList<Map<String, Any>>()
        private val content = ArrayList<Map<String, Any>>()
        private val links = ArrayList<Map<String, Any>>()
        val roles = linkedMapOf<String, Int>()
        val titleRects = ArrayList<String>()
        var skippedPictures = 0

        fun toList(): List<Map<String, Any>> = (titles + content + links).mapIndexed { z, card -> card + ("z" to z) }

        fun add(el: Element): String? {
            val (target, card) = when (el.type) {
                Element.TRAIL_TYPE_TEXT, Element.TRAIL_TYPE_TEXT_DIGEST_QUOTE, Element.TRAIL_TYPE_TEXT_DIGEST_CREATE ->
                    content to (text(el) ?: return "text:empty")
                Element.TRAIL_TYPE_PICTURE -> content to (picture(el) ?: return "picture:unavailable")
                Element.TRAIL_TYPE_TILE -> {
                    val title = el.title?.takeIf { it.style in 1..4 } ?: return "title:style${el.title?.style}"
                    val rect = Rect(title.x, title.y, title.x + title.width, title.y + title.height)
                    titleRects.add("${title.style}@[${rect.left},${rect.top},${rect.right},${rect.bottom}]")
                    titles to (image("title", el, rect) ?: return "title:emptyRect")
                }
                Element.TRAIL_TYPE_LINK -> {
                    val link = el.link ?: return "link:missing"
                    links to (image("link", el, Rect(link.x, link.y, link.x + link.width, link.y + link.height))
                        ?: return "link:emptyRect")
                }
                else -> return "type:${el.type}"
            }
            target.add(card)
            val role = card.getValue("role") as String
            roles[role] = (roles[role] ?: 0) + 1
            return null
        }

        private fun text(el: Element): Map<String, Any>? {
            val box = el.textBox ?: return null
            val label = box.textContentFull?.takeIf { it.isNotEmpty() } ?: return null

            val rect = box.textRect?.let { Rect(it) }?.takeIf { it.intersect(0, 0, pageW, pageH) && !it.isEmpty } ?: return null

            val text = if (el.type == Element.TRAIL_TYPE_TEXT_DIGEST_CREATE) "[ $label ]" else label
            return card("text", if (el.type == Element.TRAIL_TYPE_TEXT) "textbox" else "digest", rect) + mapOf(
                "text" to text, "fontSize" to (box.fontSize.takeIf { it.isFinite() && it > 0f } ?: 0f),
                "bold" to (box.textBold == 1), "italic" to (box.textItalics == 1))
        }

        private fun picture(el: Element): Map<String, Any>? {
            val rect = el.picture?.rect
            val card = rect?.let { image("picture", el, it) }
            if (card == null) {
                skippedPictures++
                Log.w(TAG, "[NOTE-MOSAIC] picture skipped page=${page + 1} trail=${el.trailNumInPage} " +
                    "rect=$rect path=${el.picture?.picturePath} layerPreview=${layer != null}")
            }
            return card
        }

        private fun image(role: String, el: Element, bounds: Rect): Map<String, Any>? {
            val rect = Rect(bounds)
            if (!rect.intersect(0, 0, pageW, pageH) || rect.isEmpty) return null
            val bitmap = VectorPageRenderer.renderCard(el, rect, pageW, pageH, layer) ?: return null
            val png = try {
                ByteArrayOutputStream().also { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) return null }.toByteArray()
            } finally {
                bitmap.recycle()
            }
            return card("image", role, rect) + ("png" to png)
        }

        private fun card(kind: String, role: String, rect: Rect): Map<String, Any> = mapOf("kind" to kind, "role" to role,
            "left" to rect.left.toFloat(), "top" to rect.top.toFloat(), "right" to rect.right.toFloat(), "bottom" to rect.bottom.toFloat())
    }
}

internal object MosaicNotePreparation {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "Notipal-Mosaic-prepare").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    fun convert(ctx: com.facebook.react.bridge.ReactApplicationContext, source: File, pages: List<Int>?,
                onProgress: (Int, Int) -> Unit, onFinalProgress: (Int) -> Unit,
                cancelled: () -> Boolean, notePath: String? = null,
                done: (NoteMosaicExport.Exported?, String?) -> Unit) {
        worker.execute {
            val opened = runCatching {
                Pipeline(openPreparer(ctx), source, onFinalProgress, cancelled, onePerPage = notePath != null)
            }
            main.post {
                val pipeline = opened.getOrElse { done(null, it.toString()); return@post }
                val finished: (NoteMosaicExport.Exported?, String?) -> Unit = { exported, error ->

                    worker.execute {
                        if (exported == null) pipeline.abort()
                        main.post { done(exported, error) }
                    }
                }
                if (notePath == null) NoteMosaicExport.exportCurrentNote(ctx, source, pages, onProgress, cancelled, pipeline, finished)
                else NoteMosaicExport.exportNote(ctx, notePath, source, pages, onProgress, cancelled, pipeline, finished)
            }
        }
    }

    private fun openPreparer(ctx: Context): Class<*> {
        val managerType = Class.forName(HostProbe.HOST_PLUGIN_MANAGER_CLASS, false, ctx.classLoader)
        val manager = managerType.getMethod("getInstance").invoke(null)
        val app = managerType.getMethod("getPluginApp", String::class.java).invoke(manager, MosaicLink.PLUGIN_ID)
            ?: error("Mosaic unavailable")
        val field = app.javaClass.getDeclaredField("nativeCodePackage").apply { isAccessible = true }
        val apk = File(field.get(app) as String)
        check(apk.isFile) { "Mosaic native package unavailable" }
        return Class.forName("me.laumss.mosaic.note.PreparedNoteImport", true, PathClassLoader(apk.absolutePath, managerType.classLoader))
    }

    internal class Pipeline(preparer: Class<*>, source: File, onProgress: (Int) -> Unit,
                            val cancelled: () -> Boolean, onePerPage: Boolean = false) : NoteMosaicExport.PageSink {
        private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        private val error = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        private val ended = java.util.concurrent.CountDownLatch(1)
        private val queue = java.util.concurrent.ArrayBlockingQueue<Map<String, Any>>(1)
        private fun isCancelled() = stopped.get() || cancelled()
        private fun checkActive() {
            error.get()?.let { throw it }
            if (isCancelled()) throw java.io.InterruptedIOException("cancelled")
            check(ended.count > 0) { "Note conversion session closed" }
        }
        init {
            @Suppress("UNCHECKED_CAST")
            val session = (if (onePerPage) {
                preparer.getMethod("openCardSession", String::class.java, java.util.function.BooleanSupplier::class.java)
                    .invoke(null, source.absolutePath, java.util.function.BooleanSupplier { isCancelled() })
            } else {
                preparer.getMethod("openPageSession", String::class.java,
                    java.util.function.IntConsumer::class.java, java.util.function.BooleanSupplier::class.java)
                    .invoke(null, source.absolutePath,
                        java.util.function.IntConsumer { percent -> main.post { if (!isCancelled()) onProgress(percent) } },
                        java.util.function.BooleanSupplier { isCancelled() })
            }) as java.util.function.Consumer<Map<String, Any>>
            Thread({
                try {
                    while (true) {
                        if (isCancelled()) throw java.io.InterruptedIOException("cancelled")
                        val message = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                        session.accept(message)
                        if (message["op"] == "finish") break
                    }
                } catch (failure: Throwable) {
                    error.set(failure)
                    runCatching { session.accept(mapOf("op" to "abort")) }
                } finally {
                    queue.clear(); ended.countDown()
                }
            }, "Notipal-Mosaic-pages").apply { isDaemon = true }.start()
        }
        override fun accept(page: Map<String, Any>) {
            while (true) {
                checkActive()
                if (queue.offer(page, 100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    error.get()?.let { throw it }
                    return
                }
            }
        }
        override fun finish() {
            accept(mapOf("op" to "finish"))
            ended.await()
            error.get()?.let { throw it }
        }
        override fun abort() { stopped.set(true); ended.await() }
    }
}

internal class NoteImportCompletion(private val startedAt: Long) {
    enum class Outcome { SUCCESS, IMPORT_FAILED, OPEN_FAILED }
    private var result: Boolean? = null
    private var finished = false
    private var entered = false

    fun observe(now: Long, visible: Boolean, reported: Boolean?): Outcome? {
        if (finished) return null
        entered = entered || visible
        if (reported != null) result = reported
        val outcome = when {
            result == false && visible -> Outcome.IMPORT_FAILED
            result == true && visible -> Outcome.SUCCESS
            !entered && now - startedAt >= OPEN_TIMEOUT_MS -> Outcome.OPEN_FAILED
            now - startedAt >= RESULT_TIMEOUT_MS -> Outcome.IMPORT_FAILED
            else -> null
        }
        if (outcome != null) finished = true
        return outcome
    }

    companion object {
        private const val OPEN_TIMEOUT_MS = 15_000L
        private const val RESULT_TIMEOUT_MS = 300_000L
    }
}
