package me.laumss.notipal.panels

import me.laumss.notipal.NotipalDirs
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import me.laumss.notipal.FloatingToolbarModule
import me.laumss.notipal.NativeLocale
import me.laumss.notipal.panels.pagepicker.PagePickerBar
import me.laumss.notipal.panels.pagepicker.PagePickerCell
import me.laumss.notipal.panels.pagepicker.PagePickerGridGeometry
import me.laumss.notipal.panels.pagepicker.PagePickerPager
import me.laumss.notipal.panels.pagepicker.PagePickerPagination
import me.laumss.notipal.panels.pagepicker.PagePickerScale
import me.laumss.notipal.panels.pagepicker.PagePickerViewport
import me.laumss.notipal.render.PageImageSource
import me.laumss.notipal.ui_common.PanelBase
import java.io.File
import java.security.MessageDigest
import java.util.TreeSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DocPagePickerPanel(ctx: ReactApplicationContext, toolbar: FloatingToolbarModule) : PanelBase(ctx, toolbar) {
    override val tag = "DocPagePicker"
    override val panelName = "doc-page-picker"
    override val fullScreen = true

    private class GridCell(val page: Int, val frame: PagePickerCell, var loaded: Boolean = false)

    private val columns = 5
    private val rows = 4
    private val pagination = PagePickerPagination()
    private val selected = TreeSet<Int>()
    private val cells = ArrayList<GridCell>()
    private val thumbs = LruCache<Int, Bitmap>(48)
    private val renderExecutor = Executors.newSingleThreadExecutor { Thread(it, "Notipal-page-picker").apply { isDaemon = true } }

    private var pdfPath = ""
    private var total = 0
    private var currentPage = -1
    private var focusPage: Int? = null
    private var generation = 0
    private var loading = false
    private var onResult: ((List<Int>?) -> Unit)? = null

    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null

    private var bar: PagePickerBar? = null
    private var countView: TextView? = null
    private var viewport: PagePickerViewport? = null
    private var grid: LinearLayout? = null
    private var pager: PagePickerPager? = null
    private var zoomOverlay: FrameLayout? = null
    private var zoomImage: ImageView? = null

    fun show(path: String, pageCount: Int, current: Int, selectAll: Boolean = false, done: (List<Int>?) -> Unit, onShown: (Boolean) -> Unit) {
        pdfPath = path
        total = pageCount
        currentPage = current
        onResult = done
        selected.clear()
        if (selectAll) selected.addAll(0 until total)
        else selected.addAll(PagePickerPagination.initialSelection(total, current, emptyList()))
        focusPage = current.takeIf { it in 0 until total } ?: 0
        thumbs.evictAll()
        showPanel(onShown)
    }

    private fun px(v: Int) = PagePickerScale.dp(reactContext, v)

    override fun buildFullScreenContent(): View {
        val ctx = reactContext
        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.WHITE); isClickable = true }
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        bar = PagePickerBar(ctx, NativeLocale.t("doc_mosaic_pick_title"), NativeLocale.t("cancel"),
            NativeLocale.t("confirm"), onClose = { finish(null) }, onAction = { finish(selected.toList()) },
        ).also { column.addView(it.view) }

        val selectionRowHeight = px(36)
        column.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(28), 0, px(28), 0)
            addView(PagePickerBar.underlinedLink(ctx, NativeLocale.t("doc_mosaic_select_all")) {
                selected.addAll(0 until total)
                cells.forEach { it.frame.setPageSelected(true) }
                refreshCount()
            })
            countView = TextView(ctx).apply {
                textSize = PagePickerScale.sp(ctx, 13f)
                setTextColor(Color.BLACK)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }.also { addView(it, LinearLayout.LayoutParams(0, -1, 1f)) }
        }, LinearLayout.LayoutParams(-1, selectionRowHeight))

        viewport = PagePickerViewport(ctx) { delta -> movePage(pagination.page + delta) }.apply {
            setPadding(px(28), px(8), px(28), px(8))
            addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
                if (r - l != oldR - oldL || b - t != oldB - oldT) post { rebuildGrid() }
            }
        }
        grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        viewport!!.addView(grid, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        column.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))

        column.addView(View(ctx), LinearLayout.LayoutParams(-1, selectionRowHeight))
        pager = PagePickerPager(ctx, ::movePage).also { column.addView(it) }

        zoomImage = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        zoomOverlay = FrameLayout(ctx).apply {
            setBackgroundColor(Color.parseColor("#CC000000"))
            visibility = View.GONE
            setOnClickListener { hideZoom() }
            addView(zoomImage, FrameLayout.LayoutParams(-1, -1).apply { setMargins(px(30), px(30), px(30), px(30)) })
        }
        root.addView(zoomOverlay, FrameLayout.LayoutParams(-1, -1))

        refreshCount()
        renderExecutor.execute { openRenderer(); pruneThumbCache() }
        return root
    }

    private fun refreshCount() {
        countView?.text = NativeLocale.t("doc_mosaic_selection_count", selected.size, total)
        bar?.setActionEnabled(selected.isNotEmpty())
    }

    private fun movePage(page: Int) {
        if (zoomOverlay?.visibility == View.VISIBLE) return
        val previous = pagination.page
        focusPage = null
        pagination.moveTo(page)
        if (pagination.page != previous) rebuildGrid()
    }

    private fun rebuildGrid() {
        val container = grid ?: return
        val view = viewport ?: return
        container.removeAllViews()
        cells.clear()
        generation++
        loading = false
        val innerWidth = ((view.width.takeIf { it > 0 } ?: screenW) - view.paddingLeft - view.paddingRight).coerceAtLeast(columns)
        val innerHeight = (view.height - view.paddingTop - view.paddingBottom).coerceAtLeast(0)
        val geometry = PagePickerGridGeometry.fit(innerWidth, innerHeight, columns, rows,
            PagePickerCell.inset(reactContext), PagePickerCell.footerHeight(reactContext),
            PagePickerCell.bottomInset(reactContext), PagePickerCell.topInset(reactContext))
        pagination.configure(total, columns * rows, focusPage)
        if (innerHeight > 0) focusPage = null
        pager?.update(pagination.page, pagination.pageCount)
        var row: LinearLayout? = null
        for (page in pagination.visibleItems) {
            val col = (page - pagination.firstItem) % columns
            if (col == 0) {
                row = LinearLayout(reactContext).apply { orientation = LinearLayout.HORIZONTAL }
                container.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
            val label = if (page == currentPage) NativeLocale.t("doc_mosaic_page_current", page + 1) else "${page + 1}"
            val width = geometry.cellWidth + if (col == columns - 1) innerWidth % columns else 0
            val frame = PagePickerCell(reactContext, width, geometry.thumbnailHeight, label).apply {
                setOnClickListener { togglePage(page) }
                setOnLongClickListener { showZoom(page); true }
                setPageSelected(page in selected)
            }
            thumbs.get(page)?.let { frame.image.setImageBitmap(it) }
            cells.add(GridCell(page, frame, loaded = thumbs.get(page) != null))
            row!!.addView(frame)
        }
        loadNextThumb()
    }

    private fun togglePage(page: Int) {
        if (!selected.remove(page)) selected.add(page)
        cells.firstOrNull { it.page == page }?.frame?.setPageSelected(page in selected)
        refreshCount()
    }

    private fun loadNextThumb() {
        if (loading || !isShowing) return
        val cell = cells.firstOrNull { !it.loaded } ?: return
        val token = generation
        val width = cell.frame.image.layoutParams.width.coerceAtLeast(1)
        val height = cell.frame.image.layoutParams.height.coerceAtLeast(1)
        loading = true
        renderExecutor.execute {
            val bitmap = cachedOrRender(cell.page, width, height)
            handler.post {
                if (token != generation) return@post
                loading = false
                cell.loaded = true
                if (bitmap != null) {
                    thumbs.put(cell.page, bitmap)
                    cell.frame.image.setImageBitmap(bitmap)
                }
                loadNextThumb()
            }
        }
    }

    private fun showZoom(page: Int) {
        val overlay = zoomOverlay ?: return
        overlay.visibility = View.VISIBLE
        zoomImage?.setImageBitmap(thumbs.get(page))
        val token = ++generation
        loading = false
        val w = (screenW - px(60)).coerceAtLeast(1)
        val h = (screenH - px(60)).coerceAtLeast(1)
        renderExecutor.execute {
            val bitmap = renderPage(page, w, h)
            handler.post { if (token == generation && overlay.visibility == View.VISIBLE && bitmap != null) zoomImage?.setImageBitmap(bitmap) }
        }
    }

    private fun hideZoom() {
        zoomOverlay?.visibility = View.GONE
        zoomImage?.setImageDrawable(null)
        rebuildGrid()
    }

    private val thumbDir: File by lazy {
        val source = File(pdfPath)
        val identity = "$pdfPath|${source.lastModified()}|${source.length()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        File(File(reactContext.cacheDir, THUMB_CACHE_DIR), digest.take(12).joinToString("") { "%02x".format(it) })
    }

    private fun cachedOrRender(page: Int, boxW: Int, boxH: Int): Bitmap? {
        val file = File(thumbDir, "p${page + 1}_${boxW}x$boxH.jpg")
        if (file.isFile && file.length() > 0L) {
            val cached = BitmapFactory.decodeFile(file.absolutePath,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
            if (cached != null) return cached
            file.delete()
        }
        val bitmap = renderPage(page, boxW, boxH) ?: return null
        runCatching {
            thumbDir.mkdirs()
            val temp = File(thumbDir, "${file.name}.tmp")
            temp.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_JPEG_QUALITY, it)) }
            if (!temp.renameTo(file)) { temp.copyTo(file, overwrite = true); temp.delete() }
            thumbDir.setLastModified(System.currentTimeMillis())
        }.onFailure { Log.w(tag, "thumbnail cache page=${page + 1}: ${it.message}") }
        return bitmap
    }

    private fun pruneThumbCache() {
        runCatching {
            val now = System.currentTimeMillis()
            thumbDir.takeIf { it.isDirectory }?.setLastModified(now)
            File(reactContext.cacheDir, THUMB_CACHE_DIR).listFiles()?.forEach { dir ->
                if (dir.isDirectory && now - dir.lastModified() >= THUMB_CACHE_MAX_AGE_MS) dir.deleteRecursively()
            }
        }
    }

    private fun openRenderer() {
        if (renderer != null || isNote) return
        runCatching {
            descriptor = ParcelFileDescriptor.open(File(pdfPath), ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor!!)
        }.onFailure { Log.w(tag, "open ${File(pdfPath).name}: ${it.message}"); closeRenderer() }
    }

    private fun closeRenderer() {
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
        renderer = null
        descriptor = null
    }

    private val isNote: Boolean get() = pdfPath.endsWith(".note", ignoreCase = true)

    private fun renderPage(page: Int, boxW: Int, boxH: Int): Bitmap? =
        if (isNote) renderNotePage(page, boxW, boxH) else renderPdfPage(page, boxW, boxH)

    private fun renderNotePage(page: Int, boxW: Int, boxH: Int): Bitmap? = runCatching {
        val dir = File(NOTE_THUMB_DIR).apply { mkdirs() }
        val png = File(dir, "picker_${System.nanoTime()}_$page.png")
        val latch = CountDownLatch(1)
        var ok = false
        handler.post {
            val params = Arguments.createMap().apply {
                putString("notePath", pdfPath); putInt("page", page); putInt("times", 1)
                putString("pngPath", png.absolutePath); putInt("type", 1)
            }
            PageImageSource.callHost(reactContext, "generateNotePng", listOf(params)) { value, error ->
                ok = value == true && png.isFile
                if (!ok) Log.w(tag, "note thumbnail page=${page + 1}: $error")
                latch.countDown()
            }
        }
        try {
            if (!latch.await(NOTE_THUMB_TIMEOUT_S, TimeUnit.SECONDS) || !ok) return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(png.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= boxW && bounds.outHeight / (sample * 2) >= boxH) sample *= 2
            val decoded = BitmapFactory.decodeFile(png.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@runCatching null
            val scale = minOf(boxW.toFloat() / decoded.width, boxH.toFloat() / decoded.height, 1f)
            if (scale >= 1f) decoded else Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1), true).also { if (it !== decoded) decoded.recycle() }
        } finally {
            png.delete()
        }
    }.onFailure { Log.w(tag, "note thumbnail page=${page + 1}: ${it.message}") }.getOrNull()

    private fun renderPdfPage(page: Int, boxW: Int, boxH: Int): Bitmap? = runCatching {
        openRenderer()
        renderer!!.openPage(page).use { pdfPage ->
            val scale = minOf(boxW.toFloat() / pdfPage.width, boxH.toFloat() / pdfPage.height)
            val w = (pdfPage.width * scale).toInt().coerceAtLeast(1)
            val h = (pdfPage.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                pdfPage.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }.onFailure { Log.w(tag, "thumbnail page=${page + 1}: ${it.message}") }.getOrNull()

    private fun finish(result: List<Int>?) {
        if (result != null && result.isEmpty()) return
        val callback = onResult ?: return
        onResult = null

        callback(result)
        hide()
    }

    override fun onHide() {
        generation++
        loading = false
        cells.clear()
        bar = null; countView = null; viewport = null; grid = null; pager = null
        zoomOverlay = null; zoomImage = null

        renderExecutor.execute { closeRenderer() }
        renderExecutor.shutdown()
        handler.post { thumbs.evictAll() }
        onResult?.let { onResult = null; it(null) }
    }

    private companion object {

        val NOTE_THUMB_DIR get() = NotipalDirs.hostRender
        const val NOTE_THUMB_TIMEOUT_S = 10L
        const val THUMB_CACHE_DIR = "page_thumbs"
        const val THUMB_JPEG_QUALITY = 85
        val THUMB_CACHE_MAX_AGE_MS = TimeUnit.DAYS.toMillis(4)
    }
}
