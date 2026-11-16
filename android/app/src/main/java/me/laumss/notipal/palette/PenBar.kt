package me.laumss.notipal.palette

import android.content.Context
import android.graphics.Point
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Cancelable
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.Strings
import me.laumss.notipal.common.TouchInput
import me.laumss.notipal.mosaic.MosaicEvent
import me.laumss.notipal.mosaic.MosaicLink
import me.laumss.notipal.toolbar.Toolbar
import me.laumss.notipal.toolbar.Tools
import me.laumss.notipal.ui_common.Dialogs
import me.laumss.notipal.ui_common.FloatWindow
import me.laumss.notipal.ui_common.LineIcon
import me.laumss.notipal.ui_common.SmallWindow
import me.laumss.notipal.ui_common.Style
import me.laumss.notipal.ui_common.WindowHooks
import me.laumss.notipal.ui_common.WindowSupport
import kotlin.math.abs
import kotlin.math.roundToInt

object PenBar : FloatWindow("pen_bar") {
    override val tag: String get() = TAG
    private const val TAG = "PenBar"

    private const val LEXI_OWNER = "penBar"
    private const val FOLDED_ICON = "icons/ic_pen_bar_folded.xml"

    private const val FOLDED_ICON_PX = 76

    private const val BAR_W = 64f
    private const val CELL_H = 64f
    private const val ICON = 28f

    private const val CTRL_H = 48f
    private const val CHEVRON = 24f
    private const val GEAR = 26f
    private const val SIZE_TEXT = 12f
    private const val SIZE_INSET = 4f

    private const val SIZE_RIGHT = 9f
    private const val NAME_TEXT = 13f
    private const val RIGHT_MARGIN = 28f
    private const val FIRST_TOP_OF_SCREEN = 0.08f

    private const val DESIGN_DENSITY = 1.875f
    private const val DESIGN_PX_PER_UNIT = 2f

    private const val SHRINK = 0.85f

    private const val BORDER_DP = 2

    private class Entry(val index: Int, val slot: PaletteSlot)

    private var book: PenBook = PenBook.Host
    private var entries: List<Entry> = emptyList()
    private var body: BarLayout? = null
    private val cells = ArrayList<FrameLayout>()

    private var lastTapped: Int? = null
    private var penWatch: Cancelable? = null

    private var folded = false

    private var wantShown = false
        set(value) {
            field = value
            if (value) WindowHooks.lexiHold(LEXI_OWNER) else WindowHooks.lexiRelease(LEXI_OWNER)
        }

    private var placedW = 0
    private var placedH = 0

    private var shownHeight = 0

    fun toggle() {
        Main.post {
            if (attached) {
                wantShown = false
                closeWindow()
                return@post
            }
            val current = PenBook.current()
            if (current == null) {
                Log.i(TAG, "toggle outside note, document and Mosaic; ignored")
                return@post
            }
            book = current
            wantShown = true
            folded = false
            showBar()
        }
    }

    fun hideAndForget() {
        Main.post {
            wantShown = false
            closeWindow()
        }
    }

    fun foldForTwoFinger() {
        Main.post {
            if (!attached || folded) return@post
            Log.i(TAG, "two-finger double tap: folded")
            setFolded(true)
        }
    }

    fun foldForCanvasMotion() {
        if (body == null || folded) return
        Log.i(TAG, "Mosaic canvas motion: folded")
        setFolded(true)
    }

    fun reloadPresets() {
        Main.post {
            val view = body ?: return@post
            entries = loadEntries()
            populate(view)
            resize()
        }
    }

    override val rememberPosition: Boolean get() = false
    override val dragByBody: Boolean get() = false

    override fun closeForRotation() {
        wantShown = false
        closeWindow()
    }

    override fun windowSize(content: View, screenW: Int, screenH: Int): Pair<Int, Int> {
        content.measure(
            View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST)
        )
        return (if (folded) bubbleSize() else u(BAR_W)) to content.measuredHeight
    }

    private fun bubbleSize(): Int = Style.px(SmallWindow.BUBBLE_PX)

    override fun defaultPosition(w: Int, h: Int, screenW: Int, screenH: Int): Point =
        Point((screenW - w - u(RIGHT_MARGIN)).coerceAtLeast(0), (screenH * FIRST_TOP_OF_SCREEN).toInt())

    override fun clamp(x: Int, y: Int, w: Int, h: Int, screenW: Int, screenH: Int): Point {
        if (screenW == placedW && screenH == placedH) {
            val grew = shownHeight in 1 until h
            return if (grew && y + h > screenH) Point(x, minOf(y, (screenH - h).coerceAtLeast(0))) else Point(x, y)
        }
        placedW = screenW
        placedH = screenH
        return Point(
            x.coerceIn(0, (screenW - w).coerceAtLeast(0)),
            y.coerceIn(0, (screenH - h).coerceAtLeast(0))
        )
    }

    override fun clampDrag(x: Int, y: Int, w: Int, h: Int, screenW: Int, screenH: Int): Point = Point(x, y)

    override fun buildParams(w: Int, h: Int): WindowManager.LayoutParams =
        super.buildParams(w, h).apply { flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS }

    override fun onMoved(x: Int, y: Int) {
        val (screenW, screenH) = Style.screenSize()
        placedW = screenW
        placedH = screenH
    }

    override fun onAttached(view: View) {
        shownHeight = currentParams?.height ?: 0
        Toolbar.setActive(Tools.PEN_SLOTS, true)
        penWatch?.cancel()
        penWatch = if (book == PenBook.Mosaic) {
            MosaicLink.addListener { event -> if (event is MosaicEvent.PenState) refreshCells() }
        } else {
            HostPen.addListener { refreshCells() }
        }

        refreshCells()
    }

    override fun onDetached() {
        penWatch?.cancel()
        penWatch = null
        Main.post { if (!attached) Toolbar.setActive(Tools.PEN_SLOTS, false) }
    }

    private fun showBar() {

        PaletteHooks.closeRelaySmall()
        entries = loadEntries()
        val view = BarLayout(App.context) { ev -> handleDragTouch(ev) }
        body = view
        populate(view)
        show(view)
    }

    private fun closeWindow() {
        body = null
        cells.clear()
        hide()
    }

    private fun loadEntries(): List<Entry> =
        PaletteSlots.load(book).withIndex().mapNotNull { (i, p) -> p?.let { Entry(i, it) } }

    private fun unit(): Float =
        App.context.resources.displayMetrics.density / DESIGN_DENSITY * DESIGN_PX_PER_UNIT * SHRINK * WindowSupport.bubbleScale()

    private fun u(v: Float): Int = (v * unit()).roundToInt().coerceAtLeast(1)

    private fun border(): Int = Style.toolbarDp(BORDER_DP).coerceAtLeast(1)

    private fun populate(container: LinearLayout) {
        container.removeAllViews()
        cells.clear()
        container.orientation = LinearLayout.VERTICAL
        container.gravity = Gravity.CENTER_HORIZONTAL
        val ctx = container.context
        if (folded) {
            container.addView(foldedBox(ctx))
        } else {
            container.addView(buildBar(ctx))
            refreshCells()
        }
    }

    private fun frame(ctx: Context): LinearLayout {
        val line = border()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(line, line, line, line)
            background = Style.boxPx(Style.paper, Style.toolbarBorder, line, 0f)
            layoutParams = LinearLayout.LayoutParams(u(BAR_W), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun foldedBox(ctx: Context): FrameLayout {
        val size = bubbleSize()
        return FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = Style.oval(Style.paper, Style.ink, Style.px(SmallWindow.BORDER_PX).coerceAtLeast(1))
            contentDescription = Strings.get("palette_unfold")
            setOnClickListener { setFolded(false) }
            val icon = Style.px(FOLDED_ICON_PX)
            addView(ImageView(ctx).apply {
                setImageBitmap(Style.iconBitmap(FOLDED_ICON, icon, Style.ink))
                layoutParams = FrameLayout.LayoutParams(icon, icon, Gravity.CENTER)
            })
        }
    }

    private fun buildBar(ctx: Context): LinearLayout {
        val bar = frame(ctx)
        bar.addView(controlCell(ctx, "palette_fold") { setFolded(true) }.apply {

            addView(LineIcon(ctx, LineIcon.Glyph.CHEVRON, CHEVRON).apply {
                rotation = -90f
                layoutParams = FrameLayout.LayoutParams(u(CHEVRON), u(CHEVRON), Gravity.CENTER)
            })
        })
        bar.addView(divider(ctx))
        for (entry in entries) {
            val cell = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, u(CELL_H))
                setOnClickListener { onCellTap(entry) }
            }
            cells.add(cell)
            bar.addView(cell)
        }
        if (entries.isNotEmpty()) bar.addView(divider(ctx))
        bar.addView(controlCell(ctx, "palette_header_title") { openSettings() }.apply {
            addView(LineIcon(ctx, LineIcon.Glyph.GEAR, GEAR).apply {
                layoutParams = FrameLayout.LayoutParams(u(GEAR), u(GEAR), Gravity.CENTER)
            })
        })
        return bar
    }

    private fun controlCell(ctx: Context, description: String, onTap: () -> Unit): FrameLayout = FrameLayout(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, u(CTRL_H))
        contentDescription = Strings.get(description)
        setOnClickListener { onTap() }
    }

    private fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(Style.paletteDivider)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (border() / 2).coerceAtLeast(1))
    }

    private fun openSettings() {
        if (PenBook.current() != book) {
            Log.i(TAG, "settings tapped after leaving ${book.javaClass.simpleName}; ignored")
            return
        }
        Log.i(TAG, "settings tapped")
        Palette.onSlotsLongPress()
    }

    private fun setFolded(value: Boolean) {
        if (folded == value) return
        folded = value
        Log.i(TAG, if (value) "folded" else "unfolded")
        val view = body ?: return
        populate(view)
        resize()
    }

    private fun resize() {
        val view = body ?: return
        val lp = currentParams ?: return
        if (!attached) return
        val (screenW, screenH) = Style.screenSize()
        val (w, h) = windowSize(view, screenW, screenH)
        val y = if (h > lp.height && lp.y + h > screenH) minOf(lp.y, (screenH - h).coerceAtLeast(0)) else lp.y
        val moved = y != lp.y
        updateParams {
            it.width = w
            it.height = h
            it.y = y
        }
        shownHeight = h
        if (moved) moveTo(lp.x, y)
    }

    private fun activeIndex(): Int? {
        val matching: List<Entry> = if (book == PenBook.Mosaic) {
            val pen = MosaicLink.externalPen ?: return null
            entries.filter {
                book.normalize(pen.penId) == it.slot.penId &&
                    book.snap(it.slot.penId, pen.thickness) == book.snap(it.slot.penId, it.slot.thickness)
            }
        } else {
            val now = HostPen.current() ?: return null
            entries.filter { now.matches(it.slot.penId, it.slot.thickness, color(it.slot)) }
        }
        val matches = matching.map { it.index }
        return lastTapped?.takeIf { it in matches } ?: matches.firstOrNull()
    }

    private fun barPenInUse(): Boolean =
        if (book == PenBook.Mosaic) MosaicLink.externalPen != null else HostPen.slotPenHeld()

    private fun color(slot: PaletteSlot): String = HostPenSpec.normalizeColor(slot.color)

    private fun refreshCells() {
        if (cells.size != entries.size || cells.isEmpty()) return
        val active = activeIndex()
        for (k in cells.indices) {
            val cell = cells[k]
            val entry = entries[k]
            val slot = entry.slot
            val on = entry.index == active
            cell.removeAllViews()
            cell.contentDescription = describe(entry)
            cell.background = if (on) Style.boxPx(Style.toolbarActive) else null
            addGlyph(cell, slot, on)
            val size = book.slotLabel(slot.penId, slot.thickness)
            if (size.isNotEmpty()) cell.addView(sizeLabel(cell.context, size, on))
        }
    }

    private fun describe(entry: Entry): String {
        val slot = entry.slot
        val size = book.slotLabel(slot.penId, slot.thickness)
        return Strings.get("palette_slot_title", entry.index + 1, book.penLabel(slot.penId)) +
            if (size.isEmpty()) "" else " $size"
    }

    private fun sizeLabel(ctx: Context, value: String, inverted: Boolean): TextView = TextView(ctx).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_PX, u(SIZE_TEXT).toFloat())
        typeface = Style.bold
        setTextColor(if (inverted) Style.paper else Style.ink)
        includeFontPadding = false
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END
        ).apply {
            rightMargin = u(SIZE_RIGHT)
            bottomMargin = u(SIZE_INSET)
        }
    }

    private fun addGlyph(host: FrameLayout, slot: PaletteSlot, inverted: Boolean) {
        val asset = book.icon(slot.penId, color(slot))
        if (asset != null) {
            val size = u(ICON)
            host.addView(ImageView(host.context).apply {
                setImageBitmap(Style.iconBitmap(asset, size, if (inverted) Style.paper else null))
                layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            })
            return
        }
        host.addView(TextView(host.context).apply {
            text = book.penLabel(slot.penId)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(NAME_TEXT).toFloat())
            typeface = Style.bold
            setTextColor(if (inverted) Style.paper else Style.ink)
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        })
    }

    private fun onCellTap(entry: Entry) {
        if (PenBook.current() != book) {
            Log.i(TAG, "slot ${entry.index} tapped after leaving ${book.javaClass.simpleName}; ignored")
            return
        }
        if (entry.index == activeIndex() && barPenInUse()) {
            restoreOwnTool(entry)
            return
        }
        lastTapped = entry.index
        val slot = entry.slot
        if (book == PenBook.Mosaic) {
            MosaicLink.usePen(slot.penId, slot.thickness) { result -> onMosaicResult("use ${entry.index}", result) }
            return
        }
        HostPen.apply(slot.penId, slot.thickness, color(slot)) { result ->
            if (result == HostPen.Result.OK) refreshCells() else Palette.hostResultTip(result)
        }
    }

    private fun restoreOwnTool(entry: Entry) {
        Log.i(TAG, "slot ${entry.index} tapped again: back to the ${book.javaClass.simpleName} tool")
        if (book == PenBook.Mosaic) {
            MosaicLink.releasePen { result -> onMosaicResult("release", result) }
            return
        }
        HostPen.restoreHost { result ->
            if (result == HostPen.Result.OK) refreshCells() else Palette.hostResultTip(result)
        }
    }

    private fun onMosaicResult(what: String, result: org.json.JSONObject) {
        if (result.optBoolean("success")) {
            refreshCells()
            return
        }
        Log.i(TAG, "Mosaic pen $what failed: ${result.optString("error")}")
        Dialogs.tip(Strings.get("palette_host_failed"))
    }

    private class BarLayout(ctx: Context, private val drag: (MotionEvent) -> Boolean) : LinearLayout(ctx) {
        private var downRawX = 0f
        private var downRawY = 0f
        private var dragging = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    dragging = false
                    drag(ev)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!TouchInput.isFinger(ev)) return false
                    if (dragging) return true
                    if (abs(ev.rawX - downRawX) > WindowSupport.DRAG_THRESHOLD_PX ||
                        abs(ev.rawY - downRawY) > WindowSupport.DRAG_THRESHOLD_PX
                    ) {
                        dragging = true
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {

            if (ev.actionMasked != MotionEvent.ACTION_DOWN) drag(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) dragging = false
            return true
        }
    }
}
