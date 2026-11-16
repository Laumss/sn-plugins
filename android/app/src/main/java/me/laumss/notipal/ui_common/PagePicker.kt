package me.laumss.notipal.ui_common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.facebook.react.bridge.Arguments
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Dirs
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.PluginIdentity
import me.laumss.notipal.common.Strings
import me.laumss.notipal.host.PageRender
import java.io.File
import java.security.MessageDigest
import java.util.TreeSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class PageSource(
    val path: String,
    val pageCount: Int,
    val currentPage: Int = -1,
    val selectAll: Boolean = false,
    val title: String = Strings.get("doc_mosaic_pick_title"),
) {
    private val tag = "DocPagePicker"
    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null

    val isNote: Boolean get() = path.endsWith(".note", ignoreCase = true)

    internal fun identity(): String {
        val source = File(path)
        return "$path|${source.lastModified()}|${source.length()}"
    }

    fun thumbnail(page: Int, boxW: Int, boxH: Int): Bitmap? =
        if (isNote) renderNotePage(page, boxW, boxH) else renderPdfPage(page, boxW, boxH)

    internal fun openRenderer() {
        if (renderer != null || isNote) return
        runCatching {
            val fd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            descriptor = fd
            renderer = PdfRenderer(fd)
        }.onFailure { Log.w(tag, "open ${File(path).name}: ${it.message}"); close() }
    }

    internal fun close() {
        runCatching { renderer?.close() }.onFailure { Log.w(tag, "close renderer: ${it.message}") }
        runCatching { descriptor?.close() }.onFailure { Log.w(tag, "close descriptor: ${it.message}") }
        renderer = null
        descriptor = null
    }

    private fun renderNotePage(page: Int, boxW: Int, boxH: Int): Bitmap? = runCatching {

        PluginIdentity.claim()
        val react = App.react ?: return@runCatching null

        val dir = Dirs.hostRender.apply { mkdirs() }
        val png = File(dir, "picker_${System.nanoTime()}_$page.png")
        val latch = CountDownLatch(1)
        var ok = false
        Main.post {
            val params = Arguments.createMap().apply {
                putString("notePath", path); putInt("page", page); putInt("times", 1)
                putString("pngPath", png.absolutePath); putInt("type", 1)
            }
            PageRender.callHost(react, "generateNotePng", listOf(params)) { value, error ->
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
                eraseColor(Style.paper)
                pdfPage.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }.onFailure { Log.w(tag, "thumbnail page=${page + 1}: ${it.message}") }.getOrNull()

    private companion object {
        const val NOTE_THUMB_TIMEOUT_S = 10L
    }
}

class PagePicker(val source: PageSource) : Panel("page-picker") {
    override val tag: String get() = "DocPagePicker"
    override val fullScreen: Boolean get() = true

    private class GridCell(val page: Int, val frame: PageCell, var loaded: Boolean = false)

    private val columns = 5
    private val rows = 4
    private val total = source.pageCount
    private val currentPage = source.currentPage
    private val pagination = PagePagination()
    private val selected = TreeSet<Int>()
    private val cells = ArrayList<GridCell>()
    private val thumbs = LruCache<Int, Bitmap>(48)
    private var renderExecutor: ExecutorService? = null

    private var focusPage: Int? = null
    private var generation = 0
    private var loading = false
    private var started = false
    private var onPicked: ((List<Int>) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null

    private var bar: PagePickerBar? = null
    private var countView: TextView? = null
    private var viewport: PageViewport? = null
    private var grid: LinearLayout? = null
    private var pager: Pager? = null
    private var zoomOverlay: FrameLayout? = null
    private var zoomImage: ImageView? = null

    private val ctx: Context get() = App.context

    private fun px(v: Int) = Style.hcDp(v)

    fun open(onPicked: (List<Int>) -> Unit, onCancel: () -> Unit = {}, onShown: (Boolean) -> Unit = {}) {
        Main.post {
            this.onPicked = onPicked
            this.onCancel = onCancel
            selected.clear()
            if (source.selectAll) selected.addAll(0 until total)
            else selected.addAll(PagePagination.initialSelection(total, currentPage))
            focusPage = currentPage.takeIf { it in 0 until total } ?: 0
            thumbs.evictAll()
            Panels.open(this) { shown ->
                started = shown
                onShown(shown)
            }
        }
    }

    override fun build(root: LinearLayout) {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "Notipal-page-picker").apply { isDaemon = true } }
        renderExecutor = executor
        val frame = FrameLayout(ctx).apply { setBackgroundColor(Style.paper); isClickable = true }
        root.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(column, FrameLayout.LayoutParams(-1, -1))

        bar = PagePickerBar(ctx, source.title, Strings.get("cancel"), Strings.get("confirm"),
            onClose = { finish(null) }, onAction = { finish(selected.toList()) },
        ).also { column.addView(it.view) }

        val selectionRowHeight = px(36)
        column.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(28), 0, px(28), 0)
            addView(PagePickerBar.underlinedLink(ctx, Strings.get("doc_mosaic_select_all")) {
                selected.addAll(0 until total)
                cells.forEach { it.frame.setPageSelected(true) }
                refreshCount()
            })

            addView(PagePickerBar.underlinedLink(ctx, Strings.get("page_picker_invert")) {
                val inverted = (0 until total).filterNot { it in selected }
                selected.clear()
                selected.addAll(inverted)
                cells.forEach { it.frame.setPageSelected(it.page in selected) }
                refreshCount()
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(28) })
            countView = TextView(ctx).apply {
                textSize = Style.hcSp(13f)
                setTextColor(Style.ink)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }.also { addView(it, LinearLayout.LayoutParams(0, -1, 1f)) }
        }, LinearLayout.LayoutParams(-1, selectionRowHeight))

        val view = PageViewport(ctx) { delta -> movePage(pagination.page + delta) }.apply {
            setPadding(px(28), px(8), px(28), px(8))
            addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
                if (r - l != oldR - oldL || b - t != oldB - oldT) post { rebuildGrid() }
            }
        }
        viewport = view
        val gridView = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        grid = gridView
        view.addView(gridView, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        column.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))

        column.addView(View(ctx), LinearLayout.LayoutParams(-1, selectionRowHeight))
        pager = Widgets.pager(ctx, ::movePage).also { column.addView(it) }

        val zoom = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        zoomImage = zoom
        zoomOverlay = FrameLayout(ctx).apply {
            setBackgroundColor(Style.pickerZoomScrim)
            visibility = View.GONE
            setOnClickListener { hideZoom() }
            addView(zoom, FrameLayout.LayoutParams(-1, -1).apply { setMargins(px(30), px(30), px(30), px(30)) })
        }
        frame.addView(zoomOverlay, FrameLayout.LayoutParams(-1, -1))

        refreshCount()
        executor.execute { source.openRenderer(); pruneThumbCache() }
    }

    private fun refreshCount() {
        countView?.text = Strings.get("doc_mosaic_selection_count", selected.size, total)
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
        val geometry = PageGridGeometry.fit(innerWidth, innerHeight, columns, rows,
            PageCell.inset(), PageCell.footerHeight(), PageCell.bottomInset(), PageCell.topInset())
        pagination.configure(total, columns * rows, focusPage)
        if (innerHeight > 0) focusPage = null
        pager?.update(pagination.page, pagination.pageCount)
        var row: LinearLayout? = null
        for (page in pagination.visibleItems) {
            val col = (page - pagination.firstItem) % columns
            if (col == 0) {
                row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                container.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
            val label = if (page == currentPage) Strings.get("doc_mosaic_page_current", page + 1) else "${page + 1}"
            val width = geometry.cellWidth + if (col == columns - 1) innerWidth % columns else 0
            val frame = PageCell(ctx, width, geometry.thumbnailHeight, label).apply {
                setOnClickListener { togglePage(page) }
                setOnLongClickListener { showZoom(page); true }
                setPageSelected(page in selected)
            }
            thumbs.get(page)?.let { frame.image.setImageBitmap(it) }
            cells.add(GridCell(page, frame, loaded = thumbs.get(page) != null))
            row?.addView(frame)
        }
        loadNextThumb()
    }

    private fun togglePage(page: Int) {
        if (!selected.remove(page)) selected.add(page)
        cells.firstOrNull { it.page == page }?.frame?.setPageSelected(page in selected)
        refreshCount()
    }

    private fun loadNextThumb() {
        if (loading || !showing) return
        val executor = renderExecutor ?: return
        val cell = cells.firstOrNull { !it.loaded } ?: return
        val token = generation
        val width = cell.frame.image.layoutParams.width.coerceAtLeast(1)
        val height = cell.frame.image.layoutParams.height.coerceAtLeast(1)
        loading = true
        executor.execute {
            val bitmap = cachedOrRender(cell.page, width, height)
            Main.post {
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
        val executor = renderExecutor ?: return
        overlay.visibility = View.VISIBLE
        zoomImage?.setImageBitmap(thumbs.get(page))
        val token = ++generation
        loading = false
        val w = (screenW - px(60)).coerceAtLeast(1)
        val h = (screenH - px(60)).coerceAtLeast(1)
        executor.execute {
            val bitmap = source.thumbnail(page, w, h)
            Main.post { if (token == generation && overlay.visibility == View.VISIBLE && bitmap != null) zoomImage?.setImageBitmap(bitmap) }
        }
    }

    private fun hideZoom() {
        zoomOverlay?.visibility = View.GONE
        zoomImage?.setImageDrawable(null)
        rebuildGrid()
    }

    private val thumbDir: File by lazy {
        val digest = MessageDigest.getInstance("SHA-256").digest(source.identity().toByteArray(Charsets.UTF_8))
        File(File(App.context.cacheDir, THUMB_CACHE_DIR), digest.take(12).joinToString("") { "%02x".format(it) })
    }

    private fun cachedOrRender(page: Int, boxW: Int, boxH: Int): Bitmap? {
        val file = File(thumbDir, "p${page + 1}_${boxW}x$boxH.jpg")
        if (file.isFile && file.length() > 0L) {
            val cached = BitmapFactory.decodeFile(file.absolutePath,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
            if (cached != null) return cached
            file.delete()
        }
        val bitmap = source.thumbnail(page, boxW, boxH) ?: return null
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
            File(App.context.cacheDir, THUMB_CACHE_DIR).listFiles()?.forEach { dir ->
                if (dir.isDirectory && now - dir.lastModified() >= THUMB_CACHE_MAX_AGE_MS) dir.deleteRecursively()
            }
        }.onFailure { Log.w(tag, "prune thumbnail cache: ${it.message}") }
    }

    private fun finish(result: List<Int>?) {
        if (result != null && result.isEmpty()) return
        val picked = onPicked
        val cancel = onCancel
        if (picked == null && cancel == null) return
        onPicked = null
        onCancel = null

        if (result != null) picked?.invoke(result) else cancel?.invoke()
        hide()
    }

    override fun onHide() {
        generation++
        loading = false
        cells.clear()
        bar = null; countView = null; viewport = null; grid = null; pager = null
        zoomOverlay = null; zoomImage = null
        renderExecutor?.let { executor ->
            executor.execute { source.close() }
            executor.shutdown()
        }
        renderExecutor = null
        Main.post { thumbs.evictAll() }
        val cancel = if (started) onCancel else null
        started = false
        onPicked = null
        onCancel = null
        cancel?.invoke()
    }

    private companion object {
        const val THUMB_CACHE_DIR = "page_thumbs"
        const val THUMB_JPEG_QUALITY = 85
        val THUMB_CACHE_MAX_AGE_MS = TimeUnit.DAYS.toMillis(4)
    }
}

private class PagePickerBar(
    ctx: Context,
    title: String,
    closeLabel: String,
    actionLabel: String,
    onClose: () -> Unit,
    onAction: () -> Unit,
) {
    val view: View
    val actionButton: TextView

    init {
        fun textPx(v: Int): Float = Style.hcPx(v).toFloat()
        val horizontalPad = Style.hcPx(52)
        val bar = FrameLayout(ctx).apply {
            setBackgroundColor(Style.paper)
            layoutParams = LinearLayout.LayoutParams(-1, Style.hcPx(135))
        }
        bar.addView(TextView(ctx).apply {
            text = closeLabel
            setTextColor(Style.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx(40))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(horizontalPad, 0, horizontalPad, 0)
            setOnClickListener { onClose() }
        }, FrameLayout.LayoutParams(-2, -1, Gravity.START or Gravity.CENTER_VERTICAL))
        bar.addView(TextView(ctx).apply {
            text = title
            setTextColor(Style.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx(44))
            typeface = Style.bold
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-2, -1, Gravity.CENTER))
        actionButton = TextView(ctx).apply {
            text = actionLabel
            setTextColor(Style.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx(40))
            setSingleLine(true)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(horizontalPad, 0, horizontalPad, 0)
            setOnClickListener { if (isEnabled) onAction() }
        }
        bar.addView(actionButton, FrameLayout.LayoutParams(-2, -1, Gravity.END or Gravity.CENTER_VERTICAL))
        view = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(View(ctx).apply { setBackgroundColor(Style.ink) }, LinearLayout.LayoutParams(-1, 1))
        }
    }

    fun setActionEnabled(enabled: Boolean) {
        actionButton.isEnabled = enabled
        actionButton.setTextColor(if (enabled) Style.ink else Style.pickerActionDisabled)
    }

    companion object {

        fun underlinedLink(ctx: Context, label: String, onClick: () -> Unit): TextView =
            object : TextView(ctx) {
                private val underline = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    val textLayout = layout ?: return
                    if (textLayout.lineCount == 0 || text.isEmpty()) return
                    underline.color = currentTextColor
                    underline.strokeWidth = Style.hcPx(2).toFloat().coerceAtLeast(1f)
                    val y = baseline + paint.fontMetrics.descent + Style.hcPx(4)
                    canvas.drawLine(compoundPaddingLeft + textLayout.getLineLeft(0), y,
                        compoundPaddingLeft + textLayout.getLineRight(0), y, underline)
                }
            }.apply {
                text = label
                textSize = Style.hcNoteSp(34)
                setTextColor(Style.ink)
                typeface = Style.bold
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, 0, Style.hcPx(10))
                setOnClickListener { onClick() }
            }
    }
}

private class PageCell(ctx: Context, widthPx: Int, thumbnailHeightPx: Int, label: String) : FrameLayout(ctx) {
    val image = ImageView(ctx)
    val pageLabel = TextView(ctx)

    init {
        val inset = topInset()
        val labelHeight = Style.hcDp(20)
        layoutParams = LinearLayout.LayoutParams(widthPx, totalHeight(thumbnailHeightPx))
        setBackgroundColor(Style.paper)
        val border = Style.hcPx(1).coerceAtLeast(1)
        image.apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = Style.boxPx(Style.paper, Style.pickerPageBorder, border)
            setPadding(border, border, border, border)
        }
        addView(image, LayoutParams(thumbnailHeightPx / 4 * 3, thumbnailHeightPx, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = inset
        })
        pageLabel.apply {
            text = label
            textSize = Style.hcSp(10.5f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Style.ink)
            gravity = Gravity.CENTER
        }
        addView(pageLabel, LayoutParams(LayoutParams.MATCH_PARENT, labelHeight, Gravity.TOP).apply {
            topMargin = inset + thumbnailHeightPx
        })
    }

    fun setPageSelected(selected: Boolean) {
        isSelected = selected
        setBackgroundColor(if (selected) Style.ink else Style.paper)
        pageLabel.setTextColor(if (selected) Style.paper else Style.ink)
    }

    companion object {
        fun inset(): Int = Style.hcDp(8)
        fun topInset(): Int = Style.hcDp(12)
        fun bottomInset(): Int = Style.hcDp(7.5f)
        fun footerHeight(): Int = Style.hcDp(29)
        fun totalHeight(thumbnailHeightPx: Int): Int =
            thumbnailHeightPx + footerHeight() + topInset() + bottomInset()
    }
}

private data class PageGridGeometry(val cellWidth: Int, val thumbnailWidth: Int, val thumbnailHeight: Int, val cellHeight: Int) {
    companion object {
        fun fit(width: Int, height: Int, columns: Int, rows: Int, inset: Int, footerHeight: Int, bottomInset: Int = inset, topInset: Int = inset): PageGridGeometry {
            val cellWidth = (width / columns.coerceAtLeast(1)).coerceAtLeast(1)
            val chrome = topInset + bottomInset + footerHeight
            val widthUnits = ((cellWidth - inset * 2) / 3).coerceAtLeast(1)
            val heightUnits = if (height > 0) ((height / rows.coerceAtLeast(1) - chrome) / 4).coerceAtLeast(1) else widthUnits
            val units = minOf(widthUnits, heightUnits)
            return PageGridGeometry(cellWidth, units * 3, units * 4, units * 4 + chrome)
        }
    }
}

private class PagePagination {
    var total: Int = 0
        private set
    var capacity: Int = 1
        private set
    var page: Int = 0
        private set
    val pageCount: Int get() = ((total + capacity - 1) / capacity).coerceAtLeast(1)
    val firstItem: Int get() = page * capacity
    val visibleItems: IntRange get() = firstItem until minOf(firstItem + capacity, total)

    fun configure(total: Int, capacity: Int, focusItem: Int? = null) {
        val anchor = focusItem ?: firstItem
        this.total = total.coerceAtLeast(0)
        this.capacity = capacity.coerceAtLeast(1)
        page = (anchor.coerceAtLeast(0) / this.capacity).coerceIn(0, pageCount - 1)
    }

    fun moveTo(page: Int) {
        this.page = page.coerceIn(0, pageCount - 1)
    }

    companion object {

        fun initialSelection(total: Int, currentPage: Int): List<Int> {
            if (total <= 0) return emptyList()
            return listOf(currentPage.coerceIn(0, total - 1))
        }
    }
}

private class PageViewport(ctx: Context, private val onSwipe: (Int) -> Unit) : FrameLayout(ctx) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private val turnDistance = Style.hcDp(48).toFloat().coerceAtLeast(slop * 2)
    private var downX = 0f
    private var downY = 0f
    private var eligible = false
    private var dragging = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                eligible = event.pointerCount == 1 && event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS &&
                    event.getToolType(0) != MotionEvent.TOOL_TYPE_ERASER
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                if (eligible && event.pointerCount == 1 && dx > slop && dx > dy * 1.3f) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> eligible = false
            MotionEvent.ACTION_CANCEL -> resetGesture()
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!eligible) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) resetGesture()
            return super.onTouchEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val turn = abs(dx) >= turnDistance && abs(dx) > abs(dy) * 1.3f
                resetGesture()
                if (turn) onSwipe(if (dx < 0) 1 else -1)
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> resetGesture()
        }
        return true
    }

    private fun resetGesture() {
        eligible = false
        dragging = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }
}
