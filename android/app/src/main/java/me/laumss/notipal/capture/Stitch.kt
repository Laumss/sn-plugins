package me.laumss.notipal.capture

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.PluginIdentity
import me.laumss.notipal.common.Prefs
import me.laumss.notipal.common.Strings
import me.laumss.notipal.ui_common.BarCell
import me.laumss.notipal.ui_common.BarStyle
import me.laumss.notipal.ui_common.Panel
import me.laumss.notipal.ui_common.Panels
import me.laumss.notipal.ui_common.Style
import me.laumss.notipal.ui_common.Widgets
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class StitchImage(
    val path: String,
    val width: Int,
    val height: Int,

    var trimTop: Int = 0,
    var overlap: Int = 0
)

data class StitchParams(
    var mode: String = MODE_STRIP,
    var cols: Int = 2,
    var gridOrder: String = "row",

    var cropTop: Float = 0f,
    var cropBottom: Float = 0f,
    var cropLeft: Float = 0f,
    var cropRight: Float = 0f
) {
    companion object {
        const val MODE_STRIP = "strip"
        const val MODE_GRID = "grid"
    }
}

data class StitchSessionData(
    val images: MutableList<StitchImage>,
    val params: StitchParams,
    val createdAt: Long
)

object StitchPanel : Panel("stitch") {
    override val tag: String get() = "StitchPanel"
    override val fullScreen: Boolean get() = true

    override val forcedOrientation: Int get() = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

    private const val PREF_LAST_MODE = "stitch_last_mode"
    private const val PREF_LAST_COLS = "stitch_last_cols"

    private const val PAD_DP = 16
    private const val HIT_RADIUS_DP = 32
    private const val MAX_EDGE_CROP = 0.6f
    private const val MAX_SIDE_CROP = 0.8f
    private val FINE_STEPS = intArrayOf(-10, -1, 1, 10)

    private const val H_SEAM = 0
    private const val H_TOP = 1
    private const val H_BOTTOM = 2
    private const val H_LEFT = 3
    private const val H_RIGHT = 4

    private var session: StitchSessionData? = null
    private var onConfirm: ((StitchSessionData) -> Unit)? = null
    private var onContinue: ((StitchSessionData) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null
    private val previews = mutableListOf<Bitmap?>()
    private var frame: FrameLayout? = null
    private var canvasView: StitchCanvas? = null
    private var seamLabel: TextView? = null

    private var selectedSeam = 1
    private var busy = false
    private var aligning = false
    private var alignGen = 0

    private val ctx get() = App.context

    private fun px(v: Number) = Style.px(v)
    private fun dp(v: Int) = Style.dp(v)
    private fun isStrip(s: StitchSessionData) = s.params.mode != StitchParams.MODE_GRID

    fun open(
        session: StitchSessionData,
        onConfirm: (StitchSessionData) -> Unit,
        onContinue: (StitchSessionData) -> Unit,
        onCancel: () -> Unit,
        onShown: ((Boolean) -> Unit)? = null
    ) {
        Main.post {
            if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "show() images=${session.images.size}")
            this.session = session
            this.onConfirm = onConfirm
            this.onContinue = onContinue
            this.onCancel = onCancel
            busy = false
            aligning = false
            alignGen++
            selectedSeam = session.images.size - 1
            applySavedLayout(session)
            loadPreviews(session)
            if (showing) {
                rebuildContent()
                onShown?.invoke(true)
                return@post
            }
            Panels.open(this) { shown -> onShown?.invoke(shown) }
        }
    }

    fun close() {
        Main.post { if (showing) hide() }
    }

    override fun onHide() {
        alignGen++
        recyclePreviews()
        session = null
        onConfirm = null
        onContinue = null
        onCancel = null
        frame = null
        canvasView = null
        seamLabel = null
        busy = false
        aligning = false
    }

    private fun prefs() = Prefs.of(ScreenshotFlow.PREFS)

    private fun applySavedLayout(s: StitchSessionData) {
        val p = prefs()
        s.params.mode = if (p.getString(PREF_LAST_MODE, null) == StitchParams.MODE_GRID)
            StitchParams.MODE_GRID else StitchParams.MODE_STRIP
        s.params.cols = p.getInt(PREF_LAST_COLS, 2).coerceIn(2, 4)
    }

    private fun saveLayout(params: StitchParams) {
        prefs().edit()
            .putString(PREF_LAST_MODE, params.mode)
            .putInt(PREF_LAST_COLS, params.cols)
            .apply()
    }

    private fun loadPreviews(s: StitchSessionData) {
        recyclePreviews()
        val sample = if (s.images.size <= 2) 1 else 2
        for (img in s.images) {
            previews.add(try {
                BitmapFactory.decodeFile(img.path, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                })
            } catch (e: OutOfMemoryError) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(tag, "preview decode out of memory", e)
                null
            })
        }
    }

    private fun recyclePreviews() {
        previews.forEach { it?.recycle() }
        previews.clear()
    }

    override fun build(root: LinearLayout) {
        val content = FrameLayout(root.context).apply { setBackgroundColor(Style.captureBackdrop) }
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        frame = content
        populateContent(content)
    }

    private fun rebuildContent() {
        val root = frame ?: return
        root.removeAllViews()
        canvasView = null
        seamLabel = null
        populateContent(root)
    }

    private fun populateContent(root: FrameLayout) {
        val s = session ?: return
        val strip = isStrip(s)

        val actions = mutableListOf<BarCell>(
            BarCell.Action("icons/ic_stitch_add.xml", Strings.get("stitch_add_page")) { doContinue() }
        )
        if (strip) {
            actions.add(BarCell.Action("icons/ic_stitch_align.xml", Strings.get("stitch_auto_align")) { realignAll() })
        }
        actions.add(BarCell.Action("icons/ic_stitch_reverse.xml", Strings.get("stitch_reverse")) { reverseOrder() })
        actions.add(BarCell.Action("icons/ic_stitch_remove.xml", Strings.get("stitch_remove_last")) { removeLast() })

        val bar = Widgets.panelBar(
            ctx = root.context,
            style = BarStyle.INBOX,
            left = listOf(BarCell.TextBtn(Strings.get("cancel")) { doCancel() }),
            center = actions,
            right = listOf(BarCell.TextBtn(Strings.get("stitch_next")) { doConfirm() })
        )
        val headerH = bar.heightPx
        bar.view.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, headerH
        ).apply { gravity = Gravity.TOP }
        root.addView(bar.view)

        val footer = buildFooter(s, strip)
        root.addView(footer)
        val footerH = measureHeight(footer)

        val cv = StitchCanvas()
        cv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { topMargin = headerH; bottomMargin = footerH }
        root.addView(cv)
        canvasView = cv
        updateSeamLabel()
    }

    private fun measureHeight(v: View): Int {
        val w = ctx.resources.displayMetrics.widthPixels
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return v.measuredHeight
    }

    private fun buildFooter(s: StitchSessionData, strip: Boolean): LinearLayout {
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Style.paper)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
        }
        wrapper.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(1).coerceAtLeast(1)
            )
            setBackgroundColor(Style.ink)
        })

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(52), px(26), px(52), px(30))
        }
        row.addView(segmented(
            listOf(Strings.get("stitch_mode_strip"), Strings.get("stitch_mode_grid")),
            if (strip) 0 else 1
        ) { idx -> setMode(if (idx == 0) StitchParams.MODE_STRIP else StitchParams.MODE_GRID) })

        if (strip) {
            buildSeamControls(row)
        } else {
            row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            buildGridControls(row, s)
        }
        wrapper.addView(row)
        return wrapper
    }

    private fun buildSeamControls(row: LinearLayout) {
        val label = makeLabel("").apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = px(24); marginEnd = px(8) }
        }
        seamLabel = label
        row.addView(label)
        for (step in FINE_STEPS) {
            row.addView(makeChip(if (step > 0) "+$step" else "−${-step}") { nudgeSeam(step) })
        }
    }

    private fun buildGridControls(row: LinearLayout, s: StitchSessionData) {
        val n = s.images.size
        if (n > 2) {
            row.addView(makeLabel(Strings.get("stitch_cols")))
            val active = s.params.cols.coerceIn(2, min(4, n))
            for (c in 2..min(4, n)) {
                row.addView(makeChip("$c", selected = c == active) { setCols(c) })
            }
        }

        if (n > s.params.cols.coerceIn(1, n)) {
            row.addView(
                segmented(
                    listOf(Strings.get("stitch_order_row"), Strings.get("stitch_order_col")),
                    if (s.params.gridOrder == "col") 1 else 0
                ) { idx -> setGridOrder(if (idx == 1) "col" else "row") },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = px(36) }
            )
        }
    }

    private fun makeLabel(text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.textPx(30))
        setTextColor(Style.ink)
    }

    private fun makeChip(label: String, selected: Boolean = false, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.textPx(30))
            setTextColor(if (selected) Style.paper else Style.ink)
            gravity = Gravity.CENTER
            minWidth = px(88)
            setPadding(px(18), px(14), px(18), px(14))
            background = Style.boxPx(
                if (selected) Style.ink else Style.paper, Style.ink, px(3).coerceAtLeast(1), px(8).toFloat()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = px(14) }
            setOnClickListener { onClick() }
        }

    private fun segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val stroke = px(3).coerceAtLeast(1)
        val radius = px(8).toFloat()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(stroke, stroke, stroke, stroke)
            background = Style.boxPx(Style.paper, Style.ink, stroke, radius)
        }
        labels.forEachIndexed { i, label ->
            val on = i == selected
            box.addView(TextView(ctx).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.textPx(30))
                setTextColor(if (on) Style.paper else Style.ink)
                typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                gravity = Gravity.CENTER
                minWidth = px(120)
                setPadding(px(26), px(12), px(26), px(12))
                if (on) background = Style.boxPx(Style.ink, radiusPx = radius * 0.6f)
                setOnClickListener { if (!on) onSelect(i) }
            })
        }
        return box
    }

    private fun updateSeamLabel() {
        val label = seamLabel ?: return
        val s = session ?: return
        if (aligning) {
            label.text = Strings.get("stitch_aligning")
            return
        }
        if (s.images.size < 2) { label.text = ""; return }
        val i = selectedSeam.coerceIn(1, s.images.size - 1)
        val overlap = Strings.get("stitch_overlap",
            s.images[i].overlap.coerceAtMost(StitchLayout.maxOverlap(s, i)))
        label.text = if (s.images.size > 2) "${Strings.get("stitch_seam", i)} · $overlap" else overlap
    }

    private fun setMode(mode: String) {
        val s = session ?: return
        if (busy) return
        s.params.mode = mode
        saveLayout(s.params)
        rebuildContent()
    }

    private fun setCols(cols: Int) {
        val s = session ?: return
        if (busy) return
        s.params.cols = cols
        saveLayout(s.params)
        rebuildContent()
    }

    private fun setGridOrder(order: String) {
        val s = session ?: return
        if (busy) return
        s.params.gridOrder = order
        rebuildContent()
    }

    private fun nudgeSeam(step: Int) {
        val s = session ?: return
        if (busy || aligning || s.images.size < 2) return
        val i = selectedSeam.coerceIn(1, s.images.size - 1)
        val img = s.images[i]
        img.overlap = (img.overlap.coerceAtMost(StitchLayout.maxOverlap(s, i)) + step)
            .coerceIn(0, StitchLayout.maxOverlap(s, i))
        canvasView?.refresh()
        updateSeamLabel()
    }

    private fun realignAll() {
        val s = session ?: return
        if (busy || aligning || s.images.size < 2) return
        aligning = true
        val gen = ++alignGen
        updateSeamLabel()
        val snapshot = s.images.toList()
        kotlin.concurrent.thread(isDaemon = true, name = "StitchPanel-align") {

            PluginIdentity.claim()
            val seams = (1 until snapshot.size).map { i -> StitchAligner.align(snapshot[i - 1], snapshot[i]) }
            Main.post {
                if (gen != alignGen || session !== s) return@post
                aligning = false
                seams.forEachIndexed { k, seam ->
                    snapshot[k + 1].trimTop = seam?.trimTop ?: 0
                    snapshot[k + 1].overlap = seam?.overlap ?: 0
                }
                canvasView?.refresh()
                updateSeamLabel()
            }
        }
    }

    private fun reverseOrder() {
        val s = session ?: return
        if (busy || aligning) return
        s.images.reverse()
        previews.reverse()

        for (img in s.images) { img.trimTop = 0; img.overlap = 0 }
        selectedSeam = s.images.size - 1
        rebuildContent()
        realignAll()
    }

    private fun removeLast() {
        val s = session ?: return
        if (busy || aligning || s.images.size < 2) return
        val last = s.images.removeAt(s.images.size - 1)
        if (!File(last.path).delete()) Log.w(tag, "removed stitch image not deleted: ${last.path}")
        if (previews.size > s.images.size) previews.removeAt(previews.size - 1)?.recycle()
        if (s.images.size < 2) {

            busy = true
            onContinue?.invoke(s)
            return
        }
        selectedSeam = s.images.size - 1
        rebuildContent()
    }

    private fun doContinue() {
        val s = session ?: return
        if (busy || aligning) return
        busy = true
        saveLayout(s.params)
        onContinue?.invoke(s)
    }

    private fun doConfirm() {
        val s = session ?: return
        if (busy || aligning) return
        busy = true
        saveLayout(s.params)
        onConfirm?.invoke(s)
    }

    private fun doCancel() {
        if (busy) return
        onCancel?.invoke()
        hide()
    }

    private class Handle(
        val kind: Int, val index: Int,
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val cx: Float, val cy: Float
    ) {
        val horizontal get() = y1 == y2

        fun distance(x: Float, y: Float): Float {
            val dx = max(max(x1 - x, 0f), x - x2)
            val dy = max(max(y1 - y, 0f), y - y2)
            return hypot(dx, dy)
        }
    }

    private class StitchCanvas : View(App.context) {

        private val pad = dp(PAD_DP).toFloat()
        private val hitRadius = dp(HIT_RADIUS_DP).toFloat()
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val framePaint = Paint().apply {
            color = Style.hint; style = Paint.Style.STROKE
            strokeWidth = px(2).coerceAtLeast(1).toFloat()
        }
        private val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Style.ink; style = Paint.Style.STROKE
            strokeWidth = px(3).coerceAtLeast(1).toFloat()
            pathEffect = DashPathEffect(floatArrayOf(px(16).toFloat(), px(10).toFloat()), 0f)
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = px(3).coerceAtLeast(1).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Style.paper; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            textSize = Style.textPx(26)
        }

        private var arrangement: StitchLayout.Result? = null
        private var scale = 1f
        private var originX = 0f
        private var originY = 0f
        private var scrollOff = 0f
        private var maxScroll = 0f
        private var focusPending = true

        private var drag: Handle? = null
        private var dragScroll = false
        private var moved = false
        private var downX = 0f
        private var downY = 0f
        private var startVal = 0f
        private var startOverlap = 0
        private var startScroll = 0f
        private var startOriginX = 0f
        private var startOriginY = 0f

        fun refresh() {
            recompute()
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            recompute()
        }

        private fun recompute() {
            val s = session ?: return
            if (width == 0 || height == 0) return
            val lay = StitchLayout.compute(s)
            arrangement = lay
            if (lay.width <= 0 || lay.height <= 0) return
            val availW = width - pad * 2
            val availH = height - pad * 2
            val fitWidth = availW / lay.width
            val fitAll = min(fitWidth, availH / lay.height)

            scale = if (isStrip(s)) min(fitWidth, max(fitAll, fitWidth * 0.4f)) else fitAll
            val contentH = lay.height * scale
            originX = (width - lay.width * scale) / 2f
            if (contentH <= availH) {
                originY = (height - contentH) / 2f
                maxScroll = 0f
            } else {
                originY = pad
                maxScroll = contentH - availH
            }
            if (focusPending) {

                focusPending = false
                val seamY = lay.pieces.getOrNull(selectedSeam)?.dst?.top ?: 0f
                scrollOff = seamY * scale - availH / 2f
            }
            scrollOff = scrollOff.coerceIn(0f, maxScroll)
        }

        private fun vx(x: Float) = originX + x * scale
        private fun vy(y: Float) = originY - scrollOff + y * scale
        private fun toView(r: RectF) = RectF(vx(r.left), vy(r.top), vx(r.right), vy(r.bottom))

        private fun handles(lay: StitchLayout.Result): List<Handle> {
            val pieces = lay.pieces
            if (pieces.isEmpty()) return emptyList()
            val left = vx(pieces.minOf { it.dst.left })
            val right = vx(pieces.maxOf { it.dst.right })
            val top = vy(pieces.first().dst.top)
            val bottom = vy(pieces.last().dst.bottom)
            val cx = (left + right) / 2f
            val midY = (height / 2f).coerceIn(top, max(top, bottom))
            val list = ArrayList<Handle>()
            list.add(Handle(H_TOP, 0, left, top, right, top, cx, top))
            list.add(Handle(H_BOTTOM, 0, left, bottom, right, bottom, cx, bottom))
            list.add(Handle(H_LEFT, 0, left, top, left, bottom, left, midY))
            list.add(Handle(H_RIGHT, 0, right, top, right, bottom, right, midY))
            for (i in 1 until pieces.size) {
                val y = vy(pieces[i].dst.top)
                list.add(Handle(H_SEAM, i, left, y, right, y, cx, y))
            }
            return list
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val s = session ?: return
            val lay = arrangement ?: return
            val strip = isStrip(s)

            for (piece in lay.pieces) {
                val bmp = previews.getOrNull(piece.index) ?: continue
                if (bmp.isRecycled) continue
                val img = s.images[piece.index]
                val fx = bmp.width.toFloat() / img.width
                val fy = bmp.height.toFloat() / img.height
                val src = Rect(
                    (piece.src.left * fx).toInt(), (piece.src.top * fy).toInt(),
                    (piece.src.right * fx).roundToInt(), (piece.src.bottom * fy).roundToInt()
                )
                val dst = toView(piece.dst)
                if (dst.bottom < 0f || dst.top > height) continue
                canvas.drawBitmap(bmp, src, dst, bitmapPaint)
                if (!strip) canvas.drawRect(dst, framePaint)
            }

            if (strip && lay.pieces.isNotEmpty()) {
                val all = handles(lay)
                canvas.drawRect(all[0].x1, all[0].y1, all[1].x2, all[1].y1, framePaint)
                for (h in all) {
                    if (h.kind == H_SEAM) {
                        canvas.drawLine(h.x1, h.y1, h.x2, h.y2, seamPaint)
                        drawSeamHandle(canvas, h.cx, h.cy, h.index == selectedSeam)
                    } else {
                        drawEdgeHandle(canvas, h.cx, h.cy, h.horizontal)
                    }
                }
            }

            for (piece in lay.pieces) drawBadge(canvas, toView(piece.dst), piece.index + 1)
        }

        private fun drawSeamHandle(canvas: Canvas, cx: Float, cy: Float, selected: Boolean) {
            val w = px(112).toFloat()
            val h = px(44).toFloat()
            val r = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            fillPaint.color = if (selected) Style.ink else Style.paper
            canvas.drawRoundRect(r, h / 2, h / 2, fillPaint)
            strokePaint.color = Style.ink
            canvas.drawRoundRect(r, h / 2, h / 2, strokePaint)
            strokePaint.color = if (selected) Style.paper else Style.ink
            val gw = px(22).toFloat()
            val gy = px(6).toFloat()
            canvas.drawLine(cx - gw, cy - gy, cx + gw, cy - gy, strokePaint)
            canvas.drawLine(cx - gw, cy + gy, cx + gw, cy + gy, strokePaint)
        }

        private fun drawEdgeHandle(canvas: Canvas, cx: Float, cy: Float, horizontal: Boolean) {
            val long = px(112).toFloat()
            val short = px(16).toFloat()
            val w = if (horizontal) long else short
            val h = if (horizontal) short else long
            fillPaint.color = Style.ink
            canvas.drawRoundRect(RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), short / 2, short / 2, fillPaint)
        }

        private fun drawBadge(canvas: Canvas, r: RectF, number: Int) {
            val size = px(44).toFloat()
            val inset = px(12).toFloat()
            val left = r.left + inset
            val top = r.top + inset
            if (top + size < 0f || top > height) return
            fillPaint.color = Style.ink
            canvas.drawRoundRect(RectF(left, top, left + size, top + size), px(6).toFloat(), px(6).toFloat(), fillPaint)
            val baseline = top + size / 2 - (badgeText.descent() + badgeText.ascent()) / 2
            canvas.drawText("$number", left + size / 2, baseline, badgeText)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val s = session ?: return false
            val lay = arrangement ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y
                    moved = false
                    drag = null
                    dragScroll = false
                    if (aligning || busy || !isStrip(s)) return true
                    drag = handles(lay)
                        .map { it to it.distance(e.x, e.y) }
                        .filter { it.second <= hitRadius }
                        .minByOrNull { it.second }?.first
                    startOriginX = originX
                    startOriginY = originY
                    val h = drag
                    if (h == null) {
                        dragScroll = maxScroll > 0f
                        startScroll = scrollOff
                        return true
                    }
                    when (h.kind) {
                        H_SEAM -> {
                            startOverlap = s.images[h.index].overlap.coerceAtMost(StitchLayout.maxOverlap(s, h.index))
                            if (selectedSeam != h.index) {
                                selectedSeam = h.index
                                updateSeamLabel()
                                invalidate()
                            }
                        }
                        H_TOP -> startVal = s.params.cropTop
                        H_BOTTOM -> startVal = s.params.cropBottom
                        H_LEFT -> startVal = s.params.cropLeft
                        H_RIGHT -> startVal = s.params.cropRight
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (!moved && hypot(dx, dy) < touchSlop) return true
                    moved = true
                    val h = drag
                    if (h == null) {
                        if (dragScroll) {
                            scrollOff = (startScroll - dy).coerceIn(0f, maxScroll)
                            invalidate()
                        }
                        return true
                    }
                    val first = s.images.first()
                    val last = s.images.last()
                    when (h.kind) {
                        H_SEAM -> s.images[h.index].overlap = (startOverlap - dy / scale).roundToInt()
                            .coerceIn(0, StitchLayout.maxOverlap(s, h.index))
                        H_TOP -> s.params.cropTop = (startVal + dy / scale / first.height).coerceIn(0f, MAX_EDGE_CROP)
                        H_BOTTOM -> s.params.cropBottom = (startVal - dy / scale / last.height).coerceIn(0f, MAX_EDGE_CROP)
                        H_LEFT -> s.params.cropLeft = (startVal + dx / scale / first.width)
                            .coerceIn(0f, max(0f, MAX_SIDE_CROP - s.params.cropRight))
                        H_RIGHT -> s.params.cropRight = (startVal - dx / scale / first.width)
                            .coerceIn(0f, max(0f, MAX_SIDE_CROP - s.params.cropLeft))
                    }

                    arrangement = StitchLayout.compute(s)
                    when (h.kind) {
                        H_TOP -> originY = startOriginY + (s.params.cropTop - startVal) * first.height * scale
                        H_LEFT -> originX = startOriginX + (s.params.cropLeft - startVal) * first.width * scale
                    }
                    if (h.kind == H_SEAM) updateSeamLabel()
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val h = drag
                    drag = null
                    dragScroll = false
                    if (h != null && moved) refresh()
                    return true
                }
            }
            return true
        }
    }
}

object StitchLayout {

    private const val TAG = "StitchLayout"

    const val MIN_VISIBLE_PX = 24

    class Piece(val index: Int, val src: Rect, val dst: RectF)

    class Result(val width: Int, val height: Int, val pieces: List<Piece>)

    fun compute(s: StitchSessionData): Result =
        if (s.params.mode == StitchParams.MODE_GRID) grid(s) else strip(s)

    fun maxOverlap(s: StitchSessionData, i: Int): Int {
        if (i <= 0 || i >= s.images.size) return 0
        val srcs = stripSources(s)
        return max(0, min(srcs[i - 1].height() - MIN_VISIBLE_PX, srcs[i].height()))
    }

    private fun stripSources(s: StitchSessionData): List<Rect> {
        val p = s.params
        val last = s.images.size - 1
        return s.images.mapIndexed { i, img ->
            val l = (img.width * p.cropLeft).roundToInt()
            val r = max(l + 1, (img.width * (1f - p.cropRight)).roundToInt())
            val b = if (i == last) (img.height * (1f - p.cropBottom)).roundToInt() else img.height
            val t = if (i == 0) (img.height * p.cropTop).roundToInt() else img.trimTop
            Rect(l, t.coerceIn(0, max(0, b - MIN_VISIBLE_PX)), r, b)
        }
    }

    private fun strip(s: StitchSessionData): Result {
        val srcs = stripSources(s)
        if (srcs.isEmpty()) return Result(0, 0, emptyList())
        val width = srcs.maxOf { it.width() }
        val pieces = ArrayList<Piece>(srcs.size)
        var y = 0f
        for ((i, src) in srcs.withIndex()) {
            if (i > 0) {
                val cap = max(0, min(srcs[i - 1].height() - MIN_VISIBLE_PX, src.height()))
                y -= s.images[i].overlap.coerceIn(0, cap)
            }
            val x = (width - src.width()) / 2f
            pieces.add(Piece(i, src, RectF(x, y, x + src.width(), y + src.height())))
            y += src.height()
        }
        val height = pieces.maxOf { it.dst.bottom }.roundToInt()
        return Result(width, height, pieces)
    }

    private fun grid(s: StitchSessionData): Result {
        val p = s.params
        val n = s.images.size
        if (n == 0) return Result(0, 0, emptyList())
        val cols = p.cols.coerceIn(1, n)
        val rows = (n + cols - 1) / cols
        val srcs = s.images.map { img ->
            val l = (img.width * p.cropLeft).roundToInt()
            val t = (img.height * p.cropTop).roundToInt()
            Rect(
                l, t,
                max(l + 1, (img.width * (1f - p.cropRight)).roundToInt()),
                max(t + 1, (img.height * (1f - p.cropBottom)).roundToInt())
            )
        }
        val cellW = srcs.maxOf { it.width() }
        val cellH = srcs.maxOf { it.height() }
        val byColumn = p.gridOrder == "col"
        val pieces = srcs.mapIndexed { i, src ->
            val col = if (byColumn) i / rows else i % cols
            val row = if (byColumn) i % rows else i / cols
            val fit = min(cellW.toFloat() / src.width(), cellH.toFloat() / src.height())
            val w = src.width() * fit
            val h = src.height() * fit
            val x = col * cellW + (cellW - w) / 2f
            val y = row * cellH + (cellH - h) / 2f
            Piece(i, src, RectF(x, y, x + w, y + h))
        }
        return Result(cellW * cols, cellH * rows, pieces)
    }

    private val pendingLock = Any()
    private var pendingPath: String? = null
    private var pendingBitmap: Bitmap? = null

    private class Write(val width: Int, val height: Int) {
        val done = java.util.concurrent.CountDownLatch(1)
        @Volatile var ok = false
    }

    private val writes = HashMap<String, Write>()

    fun render(s: StitchSessionData, outPath: String): Pair<Int, Int>? {
        val layout = compute(s)
        if (layout.width <= 0 || layout.height <= 0) return null

        DocShotHd.registerComposite(outPath, layout.pieces.map { DocShotHd.Piece(s.images[it.index].path, it.src, it.dst) })
        val t0 = System.currentTimeMillis()
        var out: Bitmap? = null
        try {
            val result = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
            out = result
            val canvas = Canvas(result)
            canvas.drawColor(Style.paper)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            for (piece in layout.pieces) {
                val bmp = BitmapFactory.decodeFile(s.images[piece.index].path) ?: return null
                canvas.drawBitmap(bmp, piece.src, piece.dst, paint)
                bmp.recycle()
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "render failed: ${e.message}", e)
            out?.recycle()
            return null
        } catch (e: OutOfMemoryError) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "render out of memory", e)
            out?.recycle()
            return null
        }
        val result = out ?: return null
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "composed ${layout.width}x${layout.height} from ${layout.pieces.size} images in ${System.currentTimeMillis() - t0}ms")
        }
        val previous = synchronized(pendingLock) {
            val p = pendingPath
            pendingPath = outPath
            pendingBitmap = result
            writes.keys.retainAll { it == outPath }
            writes[outPath] = Write(result.width, result.height)
            p
        }
        deleteStaleResults(outPath, previous)
        kotlin.concurrent.thread(isDaemon = false, name = "stitch-write") { writePending(outPath, result) }
        return layout.width to layout.height
    }

    private fun writePending(path: String, bmp: Bitmap) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        val t0 = System.currentTimeMillis()
        val ok = try {
            FileOutputStream(path).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "write failed: ${e.message}", e)
            false
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "wrote $path ok=$ok in ${System.currentTimeMillis() - t0}ms")
        synchronized(pendingLock) {
            writes[path]?.let { it.ok = ok; it.done.countDown() }

            if (!ok && pendingBitmap === bmp) return
            if (pendingBitmap === bmp) {
                pendingBitmap = null
                pendingPath = null
            }
            bmp.recycle()
        }
    }

    private fun deleteStaleResults(current: String, previous: String?) {
        val dir = java.io.File(current).parentFile ?: return
        try {
            dir.listFiles { f -> f.name.startsWith("stitch_result_") && f.absolutePath != current }
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "stale stitch results not deleted: ${e.message}")
        }
        if (previous != null && previous != current) java.io.File(previous).delete()
    }

    fun resultSize(path: String): Pair<Int, Int>? =
        synchronized(pendingLock) { writes[path]?.let { it.width to it.height } }

    fun awaitWritten(path: String): Boolean {
        val w = synchronized(pendingLock) { writes[path] } ?: return true
        w.done.await()
        return w.ok
    }

    class Preview(val bitmap: Bitmap, val origW: Int, val origH: Int)

    fun pendingPreview(path: String, reqW: Int, reqH: Int): Preview? = synchronized(pendingLock) {
        val bmp = pendingBitmap?.takeIf { pendingPath == path && !it.isRecycled } ?: return null
        val f = min(1f, min(reqW.toFloat() / bmp.width, reqH.toFloat() / bmp.height))
        val w = max(1, (bmp.width * f).roundToInt())
        val h = max(1, (bmp.height * f).roundToInt())
        try {
            val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
                .let { if (it === bmp) bmp.copy(Bitmap.Config.ARGB_8888, false) else it }
            Preview(scaled, bmp.width, bmp.height)
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    fun pendingCrop(path: String, src: Rect): Bitmap? = synchronized(pendingLock) {
        val bmp = pendingBitmap?.takeIf { pendingPath == path && !it.isRecycled } ?: return null
        val out = Bitmap.createBitmap(src.width(), src.height(), Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bmp, src, Rect(0, 0, src.width(), src.height()), null)
        out
    }
}

object StitchAligner {

    private const val TAG = "StitchAligner"

    class Seam(val trimTop: Int, val overlap: Int)

    private const val COLS = 48
    private const val SAME_ROW = 3f
    private const val FLAT_ROW = 10f
    private const val BG_TOL = 16f
    private const val MATCH_MAX = 6f
    private const val COARSE = 4
    private const val EDGE_MAX = 0.3f
    private const val EDGE_GAP = 0.04f
    private const val MIN_OVERLAP = 0.05f
    private const val KEEP_BEST = 0.85f

    fun align(prev: StitchImage, next: StitchImage): Seam? {
        if (prev.width != next.width || prev.height != next.height) return null
        return try {
            val a = signatures(prev.path) ?: return null
            val b = signatures(next.path) ?: return null
            if (a.n != b.n) return null
            compute(a, b).also {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "align trimTop=${it.trimTop} overlap=${it.overlap}")
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "align failed: ${e.message}", e)
            null
        } catch (e: OutOfMemoryError) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "align out of memory", e)
            null
        }
    }

    private fun compute(a: Rows, b: Rows): Seam {
        val n = a.n
        if (a.meanDiff(b) < SAME_ROW * 0.5f) return Seam(0, 0)

        val bg = a.background()
        val blankA = a.blanks(bg)
        val blankB = b.blanks(bg)
        var head = edgeBand(a, b, blankA, fromTop = true)
        var foot = edgeBand(a, b, blankA, fromTop = false)
        if (head + foot > n * 0.6f) { head = 0; foot = 0 }

        val c0 = head
        val c1 = n - foot
        val content = c1 - c0
        val minOverlap = max(12, (content * MIN_OVERLAP).toInt())
        if (content <= minOverlap + 1) return Seam(head, foot)

        val a4 = a.down(COARSE)
        val b4 = b.down(COARSE)
        val blankA4 = a4.blanks(bg)
        val blankB4 = b4.blanks(bg)
        val c04 = (c0 + COARSE - 1) / COARSE
        val c14 = c1 / COARSE
        val minOverlap4 = max(3, ((c14 - c04) * MIN_OVERLAP).toInt())
        val buf = FloatArray(n)

        val candidates = ArrayList<Pair<Float, Int>>()
        for (s in 1 until (c14 - c04) - minOverlap4) {
            val sc = score(a4, b4, blankA4, blankB4, s, c04, c14, 4, buf)
            if (!sc.isNaN()) candidates.add(sc to s)
        }
        candidates.sortBy { it.first }

        var best = Float.MAX_VALUE
        var bestShift = -1
        val tried = HashSet<Int>()
        for ((_, s4) in candidates.take(3)) {
            for (s in (s4 * COARSE - COARSE - 2)..(s4 * COARSE + COARSE + 2)) {
                if (s < 1 || s > content - minOverlap || !tried.add(s)) continue
                val sc = score(a, b, blankA, blankB, s, c0, c1, 12, buf)
                if (!sc.isNaN() && sc < best) { best = sc; bestShift = s }
            }
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "head=$head foot=$foot shift=$bestShift score=$best")
        return if (bestShift > 0 && best <= MATCH_MAX) Seam(head, n - head - bestShift) else Seam(head, foot)
    }

    private fun edgeBand(a: Rows, b: Rows, blankA: BooleanArray, fromTop: Boolean): Int {
        val n = a.n
        val limit = (n * EDGE_MAX).toInt()
        val tolerance = max(4, (n * EDGE_GAP).toInt())
        var band = 0
        var run = 0
        for (i in 0 until limit) {
            val y = if (fromTop) i else n - 1 - i
            if (a.diff(y, b, y) <= SAME_ROW) {
                if (!blankA[y]) { band = i + 1; run = 0 }
            } else {
                run++
                if (run > tolerance) break
            }
        }
        return band
    }

    private fun score(
        a: Rows, b: Rows, blankA: BooleanArray, blankB: BooleanArray,
        shift: Int, c0: Int, c1: Int, minRows: Int, buf: FloatArray
    ): Float {
        var count = 0
        for (k in 0 until c1 - c0 - shift) {
            val ya = c0 + shift + k
            val yb = c0 + k
            if (blankA[ya] && blankB[yb]) continue
            buf[count++] = a.diff(ya, b, yb)
        }
        if (count < minRows) return Float.NaN
        java.util.Arrays.sort(buf, 0, count)
        val keep = max(minRows, (count * KEEP_BEST).toInt())
        var sum = 0f
        for (i in 0 until keep) sum += buf[i]
        return sum / keep
    }

    private fun signatures(path: String): Rows? {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
        try {
            val w = bmp.width
            val h = bmp.height

            val x0 = (w * 0.04f).toInt()
            val x1 = (w * 0.96f).toInt()
            val edges = IntArray(COLS + 1) { x0 + (x1 - x0) * it / COLS }
            val line = IntArray(w)
            val out = FloatArray(h * COLS)
            for (y in 0 until h) {
                bmp.getPixels(line, 0, w, 0, y, w, 1)
                for (c in 0 until COLS) {
                    var sum = 0
                    for (x in edges[c] until edges[c + 1]) {
                        val p = line[x]
                        sum += ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                    }
                    out[y * COLS + c] = sum.toFloat() / max(1, edges[c + 1] - edges[c])
                }
            }
            return Rows(h, out)
        } finally {
            bmp.recycle()
        }
    }

    private class Rows(val n: Int, val v: FloatArray) {

        fun diff(y: Int, o: Rows, oy: Int): Float {
            val i = y * COLS
            val j = oy * COLS
            var s = 0f
            for (c in 0 until COLS) s += abs(v[i + c] - o.v[j + c])
            return s / COLS
        }

        fun meanDiff(o: Rows): Float {
            var s = 0f
            for (y in 0 until n) s += diff(y, o, y)
            return s / n
        }

        private inline fun rowStats(y: Int, block: (lo: Float, hi: Float, mean: Float) -> Unit) {
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            var sum = 0f
            for (c in 0 until COLS) {
                val x = v[y * COLS + c]
                if (x < lo) lo = x
                if (x > hi) hi = x
                sum += x
            }
            block(lo, hi, sum / COLS)
        }

        fun background(): Float {
            val means = ArrayList<Float>()
            for (y in 0 until n) rowStats(y) { lo, hi, mean -> if (hi - lo < FLAT_ROW) means.add(mean) }
            if (means.isEmpty()) return 255f
            means.sort()
            return means[means.size / 2]
        }

        fun blanks(bg: Float): BooleanArray = BooleanArray(n) { y ->
            var blank = false
            rowStats(y) { lo, hi, mean -> blank = hi - lo < FLAT_ROW && abs(mean - bg) < BG_TOL }
            blank
        }

        fun down(f: Int): Rows {
            val m = n / f
            val out = FloatArray(m * COLS)
            for (y in 0 until m) {
                for (c in 0 until COLS) {
                    var s = 0f
                    for (k in 0 until f) s += v[(y * f + k) * COLS + c]
                    out[y * COLS + c] = s / f
                }
            }
            return Rows(m, out)
        }
    }
}
