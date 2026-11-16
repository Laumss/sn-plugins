package me.laumss.notipal.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Bitmaps
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.Prefs
import me.laumss.notipal.common.Strings
import me.laumss.notipal.host.EinkAuto
import me.laumss.notipal.image.CropResult
import me.laumss.notipal.toolbar.Toolbar
import me.laumss.notipal.ui_common.BarCell
import me.laumss.notipal.ui_common.BarStyle
import me.laumss.notipal.ui_common.LineIcon
import me.laumss.notipal.ui_common.Panel
import me.laumss.notipal.ui_common.PanelBarHandle
import me.laumss.notipal.ui_common.Panels
import me.laumss.notipal.ui_common.SelectField
import me.laumss.notipal.ui_common.Style
import me.laumss.notipal.ui_common.Widgets
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object CropPanel : Panel("crop") {
    override val tag: String get() = "CropPanel"
    override val fullScreen: Boolean get() = true

    private enum class DragMode {
        MOVE, TOP, BOTTOM, LEFT, RIGHT,
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    private const val IMAGE_PAD_DP = 48
    private const val EDGE_HIT_ZONE_DP = 40
    private const val MIN_CROP_DP = 50
    private const val CORNER_ARM_DP = 28
    private const val CORNER_WIDTH_DP = 10
    private const val CORNER_STROKE_DP = 2f
    private const val CORNER_OUTSET_DP = 4
    private const val MID_CAP_LONG_DP = 28
    private const val MID_CAP_SHORT_DP = 10
    private const val MID_CAP_STROKE_DP = 2f
    private const val FRAME_STROKE_DP = 2f
    private const val DASH_ON_DP = 16
    private const val DASH_OFF_DP = 10
    private const val FULL_AUTO_RELEASE_DELAY_MS = 300L

    private val DENSITY_STEPS = listOf(100, 80, 60, 40, 20)

    private const val PREFS = "crop"

    private var imagePath: String? = null
    private var bitmap: Bitmap? = null
    private var onCropConfirm: ((CropResult) -> Unit)? = null
    private var showFooter = false
    private var multiMode = false
    private var hasStitchSession = false
    private var onLongScreenshot: (() -> Unit)? = null
    private var onSendToOtherDevices: ((CropResult) -> Unit)? = null
    private var onFooterCancel: (() -> Unit)? = null
    private var onCropConfirmMulti: ((CropResult, Boolean) -> Unit)? = null
    private var onAddToHistoryMulti: ((CropResult, Boolean) -> Unit)? = null

    private var handingOff = false
    private var handoffGeneration = 0

    private var frame: FrameLayout? = null
    private var busyLabel: TextView? = null
    private var actionBar: PanelBarHandle? = null
    private var cropView: CropView? = null

    private var filterMode: Int = Bitmaps.FILTER_NONE
    private var inkDensity: Int = 100
    private var filterField: SelectField? = null
    private var densityField: SelectField? = null

    private var origW = 0
    private var origH = 0
    private var filteredBitmap: Bitmap? = null
    private var previewGen = 0
    private var fullAutoEnabled = false
    private val releaseFullAutoRunnable = Runnable {
        setFullUiAuto(false, "release-delay")
    }

    private val ctx get() = App.context
    private val density: Float get() = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = Style.dp(v)

    fun beginHandoff() {
        handoffGeneration++
        handingOff = true
    }

    fun handoffToken(): Int? = if (handingOff) handoffGeneration else null

    fun isHandoffActive(token: Int): Boolean = handingOff && handoffGeneration == token

    fun finishHandoff(token: Int, close: Boolean): Boolean {
        if (!isHandoffActive(token)) return false
        handoffGeneration++
        setBusy(null)
        if (close) close() else handingOff = false
        return true
    }

    private fun resetHandoff() {
        handoffGeneration++
        handingOff = false
        setBusy(null)
    }

    fun close() {
        Main.post { if (showing) hide() }
    }

    private fun setBusy(text: String?) {
        val root = frame
        if (text == null || root == null) {
            busyLabel?.let { (it.parent as? ViewGroup)?.removeView(it) }
            busyLabel = null
            return
        }
        val label = busyLabel ?: TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Style.textPx(32))
            setTextColor(Style.ink)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(14), dp(24), dp(14))
            background = Style.boxPx(Style.paper, Style.ink, dp(2), dp(8).toFloat())
            root.addView(this, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            busyLabel = this
        }
        label.text = text
    }

    private fun setFullUiAuto(enable: Boolean, reason: String) {
        Main.handler.removeCallbacks(releaseFullAutoRunnable)
        if (fullAutoEnabled == enable) return
        if (enable) {
            if (!EinkAuto.acquire("crop", true)) return
        } else {
            EinkAuto.release("crop")
        }
        fullAutoEnabled = enable
        if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "fullUiAuto=$enable reason=$reason")
    }

    private fun scheduleFullUiAutoRelease(reason: String) {
        Main.handler.removeCallbacks(releaseFullAutoRunnable)
        Main.handler.postDelayed(releaseFullAutoRunnable, FULL_AUTO_RELEASE_DELAY_MS)
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(tag, "schedule fullUiAuto release reason=$reason delayMs=$FULL_AUTO_RELEASE_DELAY_MS")
        }
    }

    private fun filterPrefs() = Prefs.of(PREFS)

    fun open(path: String, onConfirm: (CropResult) -> Unit) {
        Main.post {
            resetHandoff()
            if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "show() path=$path")
            imagePath = path
            onCropConfirm = onConfirm
            showFooter = false
            filterPrefs().let {
                filterMode = it.getInt("filter", Bitmaps.FILTER_NONE)
                    .coerceIn(Bitmaps.FILTER_NONE, Bitmaps.FILTER_TEXT_BW)
                inkDensity = it.getInt("density", 100)
            }
            Panels.open(this) { shown -> if (!shown) Log.w(tag, "crop panel did not open") }
        }
    }

    fun openWithFooter(
        path: String,
        hasStitchSession: Boolean,
        onConfirm: (CropResult, Boolean) -> Unit,
        onLongScreenshot: () -> Unit,
        onSendToOtherDevices: (CropResult) -> Unit,
        onAddToHistory: (CropResult, Boolean) -> Unit,
        onCancel: () -> Unit,
        onShown: ((Boolean) -> Unit)? = null,
    ) {
        Main.post {
            if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "showWithFooter() path=$path stitch=$hasStitchSession")
            resetHandoff()
            imagePath = path
            this.hasStitchSession = hasStitchSession
            this.showFooter = true
            this.multiMode = false
            this.onCropConfirmMulti = onConfirm
            this.onLongScreenshot = onLongScreenshot
            this.onSendToOtherDevices = onSendToOtherDevices
            this.onAddToHistoryMulti = onAddToHistory
            this.onFooterCancel = onCancel
            Panels.open(this) { shown -> onShown?.invoke(shown) }
        }
    }

    override fun onHide() {
        Main.handler.removeCallbacks(releaseFullAutoRunnable)
        setFullUiAuto(false, "hide")
        SelectField.dismissAllPopups()
        previewGen++
        bitmap?.recycle()
        bitmap = null
        filteredBitmap?.recycle()
        filteredBitmap = null
        origW = 0
        origH = 0
        imagePath = null
        onCropConfirm = null
        onCropConfirmMulti = null
        onAddToHistoryMulti = null
        showFooter = false
        multiMode = false
        hasStitchSession = false
        resetHandoff()
        onLongScreenshot = null
        onSendToOtherDevices = null
        onFooterCancel = null
        actionBar = null
        filterMode = Bitmaps.FILTER_NONE
        inkDensity = 100
        filterField = null
        densityField = null
        cropView = null
        frame = null
    }

    override fun build(root: LinearLayout) {
        val dm = ctx.resources.displayMetrics

        val pending = imagePath?.let { StitchLayout.pendingPreview(it, dm.widthPixels, dm.heightPixels) }
        val bmp = if (pending != null) {
            origW = pending.origW
            origH = pending.origH
            pending.bitmap
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imagePath, bounds)
            origW = bounds.outWidth
            origH = bounds.outHeight
            if (origW > 0 && origH > 0) {
                BitmapFactory.decodeFile(imagePath, BitmapFactory.Options().apply {
                    inSampleSize = fitSampleSize(origW, origH, dm.widthPixels, dm.heightPixels)
                })
            } else null
        }
        bitmap = bmp

        val content = FrameLayout(root.context).apply { setBackgroundColor(Style.captureBackdrop) }
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        frame = content

        val center: List<BarCell> = if (showFooter) {
            val lsLabel = if (hasStitchSession) Strings.get("long_screenshot_active") else Strings.get("long_screenshot")
            listOf(
                BarCell.Action("icons/ic_edit_stitch.xml", lsLabel) {
                    if (handingOff) return@Action
                    onLongScreenshot?.invoke()
                    clearMultiAfterAction()
                },
                BarCell.Action("icons/ic_edit_save.xml", Strings.get("add_to_history")) {
                    if (handingOff) return@Action
                    val cv = cropView ?: return@Action
                    if (origW <= 0 || origH <= 0) return@Action
                    val wasMulti = multiMode
                    onAddToHistoryMulti?.invoke(cv.getCropResult(origW, origH), wasMulti)
                    if (wasMulti) clearMultiAfterAction() else hide()
                },
                BarCell.Action("icons/ic_send_localsend.xml", Strings.get("send_to_other_devices")) {
                    if (handingOff) return@Action
                    val cv = cropView ?: return@Action
                    if (origW <= 0 || origH <= 0) return@Action

                    val send = onSendToOtherDevices ?: return@Action
                    beginHandoff()

                    setBusy(Strings.get("sending"))
                    send(cv.getCropResult(origW, origH))
                },
                BarCell.Action("icons/ic_edit_to_note.xml", Strings.get("insert_next")) {
                    doConfirm()
                }
            )
        } else {
            emptyList()
        }

        val right: List<BarCell> = if (showFooter) {
            listOf(BarCell.Check(Strings.get("multi")) {
                multiMode = !multiMode
                actionBar?.setChecked(multiMode)
            })
        } else {
            listOf(BarCell.TextBtn(Strings.get("confirm")) { doConfirm() })
        }

        val left: List<BarCell> =
            listOf(BarCell.GlyphBtn(LineIcon.Glyph.BACK, Strings.get("picker_back")) { closeAndRestore() })

        val centerCells: List<BarCell> = if (showFooter) center else {
            listOf(BarCell.Title(Strings.get("cropper_title")))
        }

        val bar = Widgets.panelBar(
            ctx = root.context,
            style = if (showFooter) BarStyle.INBOX else BarStyle.PAGE_HEADER_ALIGNED,
            left = left,
            center = centerCells,
            right = right
        )
        actionBar = bar
        val headerH = bar.heightPx
        bar.view.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, headerH
        ).apply { gravity = Gravity.TOP }
        content.addView(bar.view)

        val bottomH = if (!showFooter) buildFilterBar(content) else 0

        if (bmp != null) {
            val view = CropView(bmp)
            view.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { topMargin = headerH; bottomMargin = bottomH }
            content.addView(view)
            cropView = view
            applyPreviewFilter()
        }
    }

    private fun fitSampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        val fit = minOf(1f, reqW.toFloat() / w, reqH.toFloat() / h)
        var sample = 1
        while (sample * 2 * fit <= 1f) sample *= 2
        return sample
    }

    private fun clearMultiAfterAction() {
        if (multiMode) {
            multiMode = false
            actionBar?.setChecked(false)
        }
    }

    private fun doConfirm() {
        if (handingOff) return
        val cv = cropView ?: return
        if (origW <= 0 || origH <= 0) return
        val result = cv.getCropResult(origW, origH)
        val hasFooter = showFooter
        val multi = multiMode
        if (hasFooter) {
            onCropConfirmMulti?.invoke(result, multi)
        } else {
            onCropConfirm?.invoke(result)
        }
        if (multi) {
            clearMultiAfterAction()
        } else {
            hide()
        }
        if (!hasFooter) Toolbar.restore("crop confirmed")
    }

    private fun buildFilterBar(root: FrameLayout): Int {
        fun px(v: Int) = Style.px(v)

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
            setPadding(px(52), dp(14), px(52), dp(18))
        }

        val filterOptions = listOf(
            Strings.get("filter_original"),
            Strings.get("filter_enhance"),
            Strings.get("filter_text_bw")
        )
        filterField = Widgets.select(ctx, Strings.get("filter"), filterOptions, filterMode) { idx ->
            filterMode = idx
            filterPrefs().edit().putInt("filter", idx).apply()
            filterField?.setValue(idx)
            applyPreviewFilter()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(200), LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(30) }
        }
        row.addView(filterField)

        val densityLabels = DENSITY_STEPS.map { "$it%" }
        val densityIdx = DENSITY_STEPS.indexOf(inkDensity).coerceAtLeast(0)
        inkDensity = DENSITY_STEPS[densityIdx]
        densityField = Widgets.select(ctx, Strings.get("density"), densityLabels, densityIdx) { idx ->
            inkDensity = DENSITY_STEPS[idx]
            filterPrefs().edit().putInt("density", inkDensity).apply()
            densityField?.setValue(idx)
            applyPreviewFilter()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(200), LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(densityField)

        wrapper.addView(row)
        root.addView(wrapper)

        val screenWpx = ctx.resources.displayMetrics.widthPixels
        wrapper.measure(
            View.MeasureSpec.makeMeasureSpec(screenWpx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return wrapper.measuredHeight
    }

    private fun applyPreviewFilter() {
        val base = bitmap ?: return
        val gen = ++previewGen
        val f = filterMode
        val d = inkDensity
        if (f == Bitmaps.FILTER_NONE && d >= 100) {
            val old = filteredBitmap
            filteredBitmap = null
            cropView?.setBitmap(base)
            old?.recycle()
            return
        }
        thread(isDaemon = true, name = "CropPanel-filter") {
            val work = try {
                base.copy(Bitmap.Config.ARGB_8888, true)
            } catch (e: Exception) {
                Log.w(tag, "preview copy failed: ${e.message}")
                null
            } ?: return@thread
            Bitmaps.process(work, f, d)
            Main.post {
                if (gen != previewGen || bitmap !== base) {
                    work.recycle()
                    return@post
                }
                val old = filteredBitmap
                filteredBitmap = work
                cropView?.setBitmap(work)
                old?.recycle()
            }
        }
    }

    private fun closeAndRestore() {
        if (handingOff) return
        val cancelCb = onFooterCancel
        hide()
        if (cancelCb != null) {
            cancelCb.invoke()
        } else {
            Toolbar.restore("crop closed")
        }
    }

    private class CropView(initialBmp: Bitmap) : View(App.context) {

        private var bmp: Bitmap = initialBmp

        fun setBitmap(b: Bitmap) {
            bmp = b
            invalidate()
        }

        private val imgPad = dp(IMAGE_PAD_DP)
        private val edgeHitZone = dp(EDGE_HIT_ZONE_DP)
        private val minCropSize = dp(MIN_CROP_DP)
        private val cornerArm = dp(CORNER_ARM_DP)
        private val cornerWidth = dp(CORNER_WIDTH_DP)
        private val cornerOutset = dp(CORNER_OUTSET_DP)
        private val midCapLong = dp(MID_CAP_LONG_DP)
        private val midCapShort = dp(MID_CAP_SHORT_DP)

        private var imgRect = RectF()

        private var cropBox = RectF()

        private var dragMode: DragMode? = null
        private var dragStartX = 0f
        private var dragStartY = 0f
        private var dragStartBox = RectF()

        private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Style.ink; style = Paint.Style.STROKE
            strokeWidth = FRAME_STROKE_DP * density
            pathEffect = DashPathEffect(floatArrayOf(dp(DASH_ON_DP).toFloat(), dp(DASH_OFF_DP).toFloat()), 0f)
        }
        private val cornerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Style.paper; style = Paint.Style.FILL
        }
        private val cornerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Style.ink; style = Paint.Style.STROKE
            strokeWidth = CORNER_STROKE_DP * density
            strokeJoin = Paint.Join.MITER
        }
        private val midCapStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Style.ink; style = Paint.Style.STROKE
            strokeWidth = MID_CAP_STROKE_DP * density
        }
        private val midCapFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Style.paper; style = Paint.Style.FILL
        }
        private val borderPaint = Paint().apply {
            color = Style.emptyText; style = Paint.Style.STROKE; strokeWidth = 1f * density
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            computeImageRect(w, h)
            cropBox.set(imgRect)
        }

        private fun computeImageRect(viewW: Int, viewH: Int) {
            val availW = viewW - imgPad * 2f
            val availH = viewH - imgPad * 2f
            val imgAspect = bmp.width.toFloat() / bmp.height
            val areaAspect = availW / availH

            val dispW: Float
            val dispH: Float
            if (imgAspect > areaAspect) {
                dispW = availW; dispH = availW / imgAspect
            } else {
                dispH = availH; dispW = availH * imgAspect
            }

            val ox = (viewW - dispW) / 2f
            val oy = imgPad + (availH - dispH) / 2f
            imgRect.set(ox, oy, ox + dispW, oy + dispH)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            canvas.drawBitmap(bmp, null, imgRect, null)

            canvas.drawRect(imgRect, borderPaint)

            canvas.drawRect(cropBox, framePaint)

            drawCornerOutside(canvas, cropBox.left, cropBox.top, -1, -1)
            drawCornerOutside(canvas, cropBox.right, cropBox.top, 1, -1)
            drawCornerOutside(canvas, cropBox.left, cropBox.bottom, -1, 1)
            drawCornerOutside(canvas, cropBox.right, cropBox.bottom, 1, 1)

            val midX = cropBox.centerX()
            val midY = cropBox.centerY()

            drawMidCap(canvas, midX, cropBox.top, horizontal = true)
            drawMidCap(canvas, midX, cropBox.bottom, horizontal = true)

            drawMidCap(canvas, cropBox.left, midY, horizontal = false)
            drawMidCap(canvas, cropBox.right, midY, horizontal = false)
        }

        private fun drawCornerOutside(canvas: Canvas, cx: Float, cy: Float, dx: Int, dy: Int) {
            val o = cornerOutset.toFloat()
            val arm = cornerArm.toFloat()
            val w = cornerWidth.toFloat()

            val ox = cx + dx * o
            val oy = cy + dy * o

            val path = Path().apply {
                moveTo(ox, oy)
                lineTo(ox - dx * arm, oy)
                lineTo(ox - dx * arm, oy - dy * w)
                lineTo(ox - dx * w, oy - dy * w)
                lineTo(ox - dx * w, oy - dy * arm)
                lineTo(ox, oy - dy * arm)
                close()
            }
            canvas.drawPath(path, cornerFillPaint)
            canvas.drawPath(path, cornerStrokePaint)
        }

        private fun drawMidCap(canvas: Canvas, cx: Float, cy: Float, horizontal: Boolean) {
            val w = if (horizontal) midCapLong.toFloat() else midCapShort.toFloat()
            val h = if (horizontal) midCapShort.toFloat() else midCapLong.toFloat()
            val r = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            canvas.drawRect(r, midCapFillPaint)
            canvas.drawRect(r, midCapStrokePaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragMode = detectDragMode(event.x, event.y)
                    dragStartX = event.x
                    dragStartY = event.y
                    dragStartBox = RectF(cropBox)
                    return dragMode != null
                }
                MotionEvent.ACTION_MOVE -> {
                    val mode = dragMode ?: return false
                    setFullUiAuto(true, "crop-move")
                    val dx = event.x - dragStartX
                    val dy = event.y - dragStartY
                    val orig = dragStartBox
                    var nl = orig.left
                    var nt = orig.top
                    var nr = orig.right
                    var nb = orig.bottom
                    when (mode) {
                        DragMode.MOVE -> { nl += dx; nt += dy; nr += dx; nb += dy }
                        DragMode.TOP -> { nt += dy }
                        DragMode.BOTTOM -> { nb += dy }
                        DragMode.LEFT -> { nl += dx }
                        DragMode.RIGHT -> { nr += dx }
                        DragMode.TOP_LEFT -> { nl += dx; nt += dy }
                        DragMode.TOP_RIGHT -> { nr += dx; nt += dy }
                        DragMode.BOTTOM_LEFT -> { nl += dx; nb += dy }
                        DragMode.BOTTOM_RIGHT -> { nr += dx; nb += dy }
                    }
                    clampAndSet(nl, nt, nr, nb, mode == DragMode.MOVE)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragMode = null
                    scheduleFullUiAutoRelease(
                        if (event.action == MotionEvent.ACTION_UP) "crop-up" else "crop-cancel"
                    )
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun detectDragMode(x: Float, y: Float): DragMode? {
            val nearL = Math.abs(x - cropBox.left) < edgeHitZone
            val nearR = Math.abs(x - cropBox.right) < edgeHitZone
            val nearT = Math.abs(y - cropBox.top) < edgeHitZone
            val nearB = Math.abs(y - cropBox.bottom) < edgeHitZone

            if (nearT && nearL) return DragMode.TOP_LEFT
            if (nearT && nearR) return DragMode.TOP_RIGHT
            if (nearB && nearL) return DragMode.BOTTOM_LEFT
            if (nearB && nearR) return DragMode.BOTTOM_RIGHT
            if (nearT) return DragMode.TOP
            if (nearB) return DragMode.BOTTOM
            if (nearL) return DragMode.LEFT
            if (nearR) return DragMode.RIGHT
            if (x in cropBox.left..cropBox.right && y in cropBox.top..cropBox.bottom) return DragMode.MOVE
            return null
        }

        private fun clampAndSet(l: Float, t: Float, r: Float, b: Float, isMove: Boolean) {
            var w = r - l
            var h = b - t
            if (isMove) {
                var nl = l
                var nt = t
                nl = max(imgRect.left, min(nl, imgRect.right - w))
                nt = max(imgRect.top, min(nt, imgRect.bottom - h))
                cropBox.set(nl, nt, nl + w, nt + h)
            } else {
                w = max(minCropSize.toFloat(), w)
                h = max(minCropSize.toFloat(), h)
                var nl = min(l, r - minCropSize)
                var nt = min(t, b - minCropSize)
                nl = max(imgRect.left, nl)
                nt = max(imgRect.top, nt)
                var nr = max(nl + minCropSize, nl + w)
                var nb = max(nt + minCropSize, nt + h)
                nr = min(nr, imgRect.right)
                nb = min(nb, imgRect.bottom)
                cropBox.set(nl, nt, nr, nb)
            }
        }

        fun getCropResult(origW: Int, origH: Int): CropResult {
            val scaleX = origW / imgRect.width()
            val scaleY = origH / imgRect.height()
            val relX = cropBox.left - imgRect.left
            val relY = cropBox.top - imgRect.top
            val ox = max(0, (relX * scaleX).roundToInt())
            val oy = max(0, (relY * scaleY).roundToInt())
            val cw = min(origW - ox, (cropBox.width() * scaleX).roundToInt())
            val ch = min(origH - oy, (cropBox.height() * scaleY).roundToInt())
            return CropResult(ox, oy, max(1, cw), max(1, ch), filterMode, inkDensity)
        }
    }
}
