package me.laumss.notipal.render

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.SizeF
import com.facebook.react.bridge.ReactApplicationContext
import com.ratta.supernote.plugincommon.data.common.trail.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

object NoteMosaicExport {

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
        PageImageSource.callHost(ctx, "getCurrentFilePath", emptyList()) { pathValue, pathError ->
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
        PageImageSource.callHost(ctx, "getCurrentFilePath", emptyList()) { current, _ ->
            val opened = PageImageSource.samePath(current as? String, path)
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
        PageImageSource.callHost(ctx, "getNoteTotalPageNum", listOf(path)) { totalValue, totalError ->
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

            PageImageSource.callHost(ctx, "getPageDisplaySize", emptyList()) { displayValue, displayError ->
                if (displayError != null) Log.w(TAG, "getPageDisplaySize: $displayError (using screen rotation)")

                PageImageSource.callHost(ctx, "getCurrentPageNum", emptyList()) { pageValue, pageError ->
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
            PageImageSource.callHost(ctx, "getPageSize", listOf(path, page)) { sizeValue, sizeError ->
                val size = sizeValue as? SizeF
                if (size == null || !(size.width > 0f) || !(size.height > 0f)) {
                    fail(sizeError ?: "page $page size unavailable")
                    return@callHost
                }
                PageImageSource.loadPageElements(ctx, path, page) { elements, error, release ->
                    if (elements == null) {
                        release()
                        if (error == PageImageSource.TRAIL_CACHE_BUSY && attempt + 1 < PageImageSource.TRAIL_CACHE_BUSY_RETRY_COUNT) {
                            mainHandler.postDelayed({ step(index, attempt + 1) }, PageImageSource.TRAIL_CACHE_BUSY_RETRY_MS)
                        } else {
                            fail(error ?: "page $page elements unavailable")
                        }
                        return@loadPageElements
                    }

                    PageImageSource.withLayerPreview(ctx, path, page, "note_mosaic_${System.nanoTime()}", elements) { layerPath ->
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
                    val reason = ClipboardElementIo.copyNoteStroke(el, coordinates, if (white) TITLE_INK_WHITE else null) { stroke ->
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
