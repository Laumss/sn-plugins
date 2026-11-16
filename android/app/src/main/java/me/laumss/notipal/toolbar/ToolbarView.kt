package me.laumss.notipal.toolbar

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.TouchInput
import me.laumss.notipal.host.PenGuard
import me.laumss.notipal.host.PenGuardTiming
import me.laumss.notipal.host.PenOwner
import me.laumss.notipal.host.Surface
import me.laumss.notipal.ui_common.Style
import kotlin.math.abs

internal data class ToolbarLayout(
    val surface: Surface,

    val tools: List<Tool>,

    val sidebar: List<Tool>,
    val clipFilled: BooleanArray,
    val activeIds: Set<String>,
)

internal interface ToolbarGestures {
    fun onToolTap(tool: Tool, view: View)
    fun onToolLongPress(tool: Tool, view: View)
    fun onClipTap(slot: Int)
    fun onClipLongPress(slot: Int)
    fun onCollapseTap()

    fun onDragBegin(view: View, x: Int, y: Int)
    fun onDragMove(view: View, x: Int, y: Int)
    fun onDragEnd(view: View, x: Int, y: Int)

    fun onHandleOpen()
    fun onHandleLongPress()

    fun onHandleDragging(dragging: Boolean)
}

internal object ToolbarMetrics {
    const val BTN_SIZE_DP = 48
    const val BTN_GAP_DP = 2
    const val PANEL_PAD_DP = 8
    const val BORDER_WIDTH_DP = 2
    const val CLIP_ICON_DP = 32
    const val CLIP_RADIUS_DP = 3

    const val COLLAPSED_WIDTH_DP = 16
    const val COLLAPSED_HIT_WIDTH_DP = 24
    const val COLLAPSED_HEIGHT_DP = 72
    const val LONG_PRESS_MS = 600L

    const val ICON_STROKE_DELTA_PX = -1f

    const val CLIP_SLOT_COUNT = 8

    const val MAX_VISIBLE_CLIPS = 6
    const val SWIPE_THRESHOLD_PX = 30
    const val DRAG_THRESHOLD_PX = 10

    fun dp(v: Int): Int = Style.toolbarDp(v)
    fun sp(v: Float): Float = v * Style.toolbarScale()
}

internal class ToolbarHandle(private val dockLeft: Boolean, private val gestures: ToolbarGestures) {
    val width: Int = ToolbarMetrics.dp(ToolbarMetrics.COLLAPSED_HIT_WIDTH_DP)
    val height: Int = ToolbarMetrics.dp(ToolbarMetrics.COLLAPSED_HEIGHT_DP)

    private var startRawX = 0f
    private var startRawY = 0f
    private var dragging = false
    private var longPressed = false
    private val longPress = Runnable {
        longPressed = true
        gestures.onHandleLongPress()
    }

    val view: View = FrameLayout(App.context).apply {
        val stripe = View(context).apply { background = HandleDrawable(dockLeft) }
        addView(stripe, FrameLayout.LayoutParams(ToolbarMetrics.dp(ToolbarMetrics.COLLAPSED_WIDTH_DP), this@ToolbarHandle.height).apply {
            gravity = if (dockLeft) Gravity.START else Gravity.END
        })
        setOnTouchListener { _, event -> onTouch(event) }
    }

    private fun onTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startRawX = event.rawX
                startRawY = event.rawY
                dragging = false
                longPressed = false
                Main.handler.postDelayed(longPress, ToolbarMetrics.LONG_PRESS_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!TouchInput.isFinger(event)) return true
                val dx = event.rawX - startRawX
                val dy = event.rawY - startRawY
                if (!dragging && (abs(dx) > ToolbarMetrics.DRAG_THRESHOLD_PX || abs(dy) > ToolbarMetrics.DRAG_THRESHOLD_PX)) {
                    dragging = true
                    gestures.onHandleDragging(true)
                    Main.handler.removeCallbacks(longPress)
                }
            }
            MotionEvent.ACTION_UP -> {
                Main.handler.removeCallbacks(longPress)
                val swiped = dragging

                dragging = false
                gestures.onHandleDragging(false)
                if (!longPressed) {
                    if (swiped) {
                        val dx = event.rawX - startRawX
                        val inward = if (dockLeft) dx > ToolbarMetrics.SWIPE_THRESHOLD_PX else dx < -ToolbarMetrics.SWIPE_THRESHOLD_PX
                        if (inward) gestures.onHandleOpen()
                    } else {
                        gestures.onHandleOpen()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                Main.handler.removeCallbacks(longPress)
                dragging = false
                gestures.onHandleDragging(false)
            }
            else -> return false
        }
        return true
    }

    fun dispose() {
        Main.handler.removeCallbacks(longPress)
    }

    private class HandleDrawable(private val dockLeft: Boolean) : Drawable() {
        private val strokeW = ToolbarMetrics.dp(3) / 2f
        private val radius = ToolbarMetrics.dp(6).toFloat()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Style.paper
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Style.toolbarBorder
            strokeWidth = strokeW
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun draw(canvas: Canvas) {
            val b = bounds
            val half = strokeW / 2f

            val rect = if (dockLeft) {
                RectF(b.left - radius, b.top + half, b.right - half, b.bottom - half)
            } else {
                RectF(b.left + half, b.top + half, b.right + radius, b.bottom - half)
            }
            canvas.drawRoundRect(rect, radius, radius, fill)
            canvas.drawRoundRect(rect, radius, radius, line)
            val cx = b.exactCenterX() + if (dockLeft) -half else half
            val cy = b.exactCenterY()
            val armX = ToolbarMetrics.dp(5) / 2f
            val armY = ToolbarMetrics.dp(5).toFloat()
            val dir = if (dockLeft) 1f else -1f
            path.reset()
            path.moveTo(cx - dir * armX, cy - armY)
            path.lineTo(cx + dir * armX, cy)
            path.lineTo(cx - dir * armX, cy + armY)
            canvas.drawPath(path, line)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("deprecated in Drawable")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}

internal class ToolbarPanel(private var layout: ToolbarLayout, private val gestures: ToolbarGestures) {
    private val ctx: Context = ContextThemeWrapper(App.context, android.R.style.Theme_DeviceDefault_Light)

    private var startX = 0
    private var startY = 0
    private var startRawX = 0f
    private var startRawY = 0f
    var dragging = false
        private set

    var windowX = 0
    var windowY = 0

    private var clipPage = 0
    private val clipIconViews = arrayOfNulls<TextView>(ToolbarMetrics.MAX_VISIBLE_CLIPS)
    private val pagedSidebarViews = ArrayList<View>()
    private val sidebarViews = HashMap<String, View>()
    private val toolViews = HashMap<String, View>()
    private val toolIconSpec = HashMap<String, IntArray>()
    private var collapseBtn: View? = null
    private val toolContainer: LinearLayout

    val view: LinearLayout = object : LinearLayout(ctx) {
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = windowX
                    startY = windowY
                    startRawX = ev.rawX
                    startRawY = ev.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!TouchInput.isFinger(ev)) return false
                    if (!dragging && (abs(ev.rawX - startRawX) > ToolbarMetrics.DRAG_THRESHOLD_PX ||
                            abs(ev.rawY - startRawY) > ToolbarMetrics.DRAG_THRESHOLD_PX)) {
                        dragging = true
                        gestures.onDragBegin(this, startX, startY)
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = windowX
                    startY = windowY
                    startRawX = ev.rawX
                    startRawY = ev.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!TouchInput.isFinger(ev)) return true
                    val dx = ev.rawX - startRawX
                    val dy = ev.rawY - startRawY
                    if (!dragging && (abs(dx) > ToolbarMetrics.DRAG_THRESHOLD_PX || abs(dy) > ToolbarMetrics.DRAG_THRESHOLD_PX)) {
                        dragging = true
                        gestures.onDragBegin(this, windowX, windowY)
                    }
                    if (dragging) gestures.onDragMove(this, startX + dx.toInt(), startY + dy.toInt())
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        gestures.onDragEnd(this, windowX, windowY)
                    }
                }
            }
            return true
        }
    }

    init {
        val borderPx = ToolbarMetrics.dp(ToolbarMetrics.BORDER_WIDTH_DP)
        view.orientation = LinearLayout.VERTICAL
        view.background = Style.boxPx(Style.paper, Style.toolbarBorder, borderPx, 0f)
        view.setPadding(borderPx, borderPx, borderPx, borderPx)
        view.clipToPadding = true

        val bodyRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        toolContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            val p = ToolbarMetrics.dp(ToolbarMetrics.BORDER_WIDTH_DP + ToolbarMetrics.PANEL_PAD_DP) - borderPx
            setPadding(p, ToolbarMetrics.dp(4), ToolbarMetrics.dp(2), p)
        }
        bodyRow.addView(toolContainer)
        bodyRow.addView(buildSidebar())
        view.addView(bodyRow)
        rebuildButtons()
    }

    private val noteSurface get() = layout.surface == Surface.NOTE
    private val mosaicSurface get() = layout.surface == Surface.MOSAIC
    private val docSurface get() = layout.surface == Surface.DOCUMENT

    private fun pagesSidebarTools(): Boolean =
        ((mosaicSurface || docSurface) && layout.tools.size < 5) || layout.tools.size == 4

    private fun clipCapacity(): Int = when {
        docSurface || mosaicSurface -> 4
        layout.tools.size >= 6 -> ToolbarMetrics.MAX_VISIBLE_CLIPS
        layout.tools.size == 5 -> 4
        else -> 3
    }

    private fun lastClipPage(): Int =
        if (pagesSidebarTools()) 1 else (ToolbarMetrics.CLIP_SLOT_COUNT - 1) / clipCapacity()

    private fun clipWindow(): Pair<Int, Int> {
        clipPage = clipPage.coerceIn(0, lastClipPage())
        if (pagesSidebarTools()) {
            return if (clipPage == 0) 3 to 0 else (ToolbarMetrics.CLIP_SLOT_COUNT - 3) to 3
        }
        val capacity = clipCapacity()
        val offset = if (capacity == ToolbarMetrics.MAX_VISIBLE_CLIPS) {
            minOf(clipPage * capacity, ToolbarMetrics.CLIP_SLOT_COUNT - capacity)
        } else {
            clipPage * capacity
        }
        return minOf(capacity, ToolbarMetrics.CLIP_SLOT_COUNT - offset) to offset
    }

    private fun buildSidebar(): View {
        val clipIconSz = ToolbarMetrics.dp(ToolbarMetrics.CLIP_ICON_DP)
        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Style.paper)
            layoutParams = LinearLayout.LayoutParams(ToolbarMetrics.dp(ToolbarMetrics.CLIP_ICON_DP + 6), LinearLayout.LayoutParams.MATCH_PARENT)

            setPadding(ToolbarMetrics.dp(3), ToolbarMetrics.dp(3), ToolbarMetrics.dp(3), ToolbarMetrics.dp(if (noteSurface) 4 else 2))
        }
        val pageTools = pagesSidebarTools()
        var swipeStartY = 0f
        var swipeStartX = 0f
        var swipeCaptured = false
        val clipCol = object : LinearLayout(ctx) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        swipeStartX = ev.rawX
                        swipeStartY = ev.rawY
                        swipeCaptured = false
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = abs(ev.rawX - swipeStartX)
                        val dy = abs(ev.rawY - swipeStartY)
                        if (!swipeCaptured && dy > ToolbarMetrics.dp(12) && dy > dx) {
                            swipeCaptured = true
                            return true
                        } else if (!swipeCaptured && dx > ToolbarMetrics.dp(12) && dx >= dy) {
                            parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        swipeCaptured = false
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                return false
            }

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                when (ev.action) {
                    MotionEvent.ACTION_UP -> {
                        if (swipeCaptured) {
                            val dy = ev.rawY - swipeStartY
                            if (dy < -ToolbarMetrics.dp(15) && clipPage < lastClipPage()) {
                                clipPage += 1
                                refreshClips()
                            } else if (dy > ToolbarMetrics.dp(15) && clipPage > 0) {
                                clipPage -= 1
                                refreshClips()
                            }
                        }
                        swipeCaptured = false
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        swipeCaptured = false
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                return swipeCaptured || ev.action == MotionEvent.ACTION_UP
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        for (vi in 0 until ToolbarMetrics.MAX_VISIBLE_CLIPS) {
            val tv = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, ToolbarMetrics.sp(13f))
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(clipIconSz, clipIconSz).apply { bottomMargin = ToolbarMetrics.dp(2) }
            }
            clipIconViews[vi] = tv
            clipCol.addView(tv)
        }
        titleCol.addView(clipCol)

        val sidebarParent = if (pageTools) clipCol else titleCol
        val last = layout.sidebar.lastIndex
        layout.sidebar.forEachIndexed { index, tool ->
            val btn = if (tool.highlights) makeLatchButton(tool, clipIconSz) else makeSidebarIconButton(tool)
            (btn.layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = ToolbarMetrics.dp(6)
                bottomMargin = when {

                    (docSurface || (mosaicSurface && !pageTools)) && index == last -> ToolbarMetrics.dp(2)
                    tool.highlights && !(pageTools && !mosaicSurface) -> ToolbarMetrics.dp(1)
                    else -> 0
                }
            }

            if (pageTools) pagedSidebarViews.add(btn)
            sidebarViews[tool.id] = btn
            sidebarParent.addView(btn)
        }
        titleCol.addView(makeCollapseButton(clipIconSz).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = ToolbarMetrics.dp(if (layout.sidebar.isNotEmpty()) 6 else 1)
        })
        refreshClips()
        refreshLatches()
        return titleCol
    }

    private fun toolbarIcon(tool: Tool, sizePx: Int, color: Int, padPx: Int): Drawable? {
        val drawPx = (sizePx - padPx * 2).coerceAtLeast(1)
        val bitmap = Style.iconBitmap(Tools.iconOf(tool, layout.surface), drawPx, color, ToolbarMetrics.ICON_STROKE_DELTA_PX)
            ?: return null
        return android.graphics.drawable.BitmapDrawable(ctx.resources, bitmap)
    }

    private fun iconOrText(tool: Tool, sizePx: Int, color: Int, padDp: Int, textSp: Float): View {
        val pad = ToolbarMetrics.dp(padDp)
        val icon = toolbarIcon(tool, sizePx, color, pad)
        return if (icon != null) {
            ImageView(ctx).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(pad, pad, pad, pad)
            }
        } else {
            TextView(ctx).apply {
                text = tool.fallbackText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, ToolbarMetrics.sp(textSp))
                setTextColor(color)
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
        }
    }

    private fun makeSidebarIconButton(tool: Tool): View {
        val size = ToolbarMetrics.dp(28)
        val view = iconOrText(tool, size, Style.toolbarFg, 3, 12f)
        view.background = null
        view.contentDescription = Tools.labelOf(tool)
        view.tooltipText = null
        view.layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = ToolbarMetrics.dp(1) }
        view.setOnClickListener { gestures.onToolTap(tool, view) }
        return view
    }

    private fun makeLatchButton(tool: Tool, size: Int): View {
        val view = iconOrText(tool, size, Style.ink, 5, 13f)
        view.layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = ToolbarMetrics.dp(1) }
        bindTool(view, tool)
        return view
    }

    private fun makeCollapseButton(size: Int): View {
        val view = iconOrText(Tools.COLLAPSE, size, Style.toolbarFg, 5, 14f)
        view.background = null
        view.layoutParams = LinearLayout.LayoutParams(size, size)
        view.setOnClickListener { gestures.onCollapseTap() }
        collapseBtn = view

        view.post { refreshCollapseIconSide() }
        return view
    }

    fun refreshCollapseIconSide() {
        val btn = collapseBtn ?: return
        val vw = view.width.takeIf { it > 0 } ?: view.measuredWidth
        btn.scaleX = if (windowX + vw / 2 <= Style.screenSize().first / 2) -1f else 1f
    }

    private fun applyLatchState(view: View, active: Boolean) {
        val fg = if (active) Style.paper else Style.ink
        view.background = Style.boxPx(if (active) Style.ink else Style.clear, 0, 0, ToolbarMetrics.dp(ToolbarMetrics.CLIP_RADIUS_DP).toFloat())
        when (view) {
            is ImageView -> view.setColorFilter(fg)
            is TextView -> view.setTextColor(fg)
        }
    }

    private fun refreshLatches() {
        for (tool in layout.sidebar) {
            if (!tool.highlights) continue
            sidebarViews[tool.id]?.let { applyLatchState(it, tool.id in layout.activeIds) }
        }
    }

    private fun refreshClips() {
        val (count, offset) = clipWindow()
        for (vi in clipIconViews.indices) {
            val tv = clipIconViews[vi] ?: continue
            if (vi >= count) {
                tv.visibility = View.GONE
                tv.isEnabled = false
                tv.setOnClickListener(null)
                tv.setOnLongClickListener(null)
                continue
            }
            tv.visibility = View.VISIBLE
            tv.isEnabled = true
            val di = offset + vi
            val filled = layout.clipFilled.getOrElse(di) { false }
            val slot = di + 1
            tv.text = slot.toString()
            tv.setTextColor(if (filled) Style.paper else Style.ink)
            tv.background = if (filled) {
                Style.boxPx(Style.ink, 0, 0, ToolbarMetrics.dp(ToolbarMetrics.CLIP_RADIUS_DP).toFloat())
            } else {
                null
            }
            tv.setOnClickListener { gestures.onClipTap(slot) }
            tv.setOnLongClickListener {
                gestures.onClipLongPress(slot)
                true
            }
            tv.invalidate()
        }
        val toolsVisibility = if (clipPage == 0) View.VISIBLE else View.GONE
        pagedSidebarViews.forEach { it.visibility = toolsVisibility }
    }

    private fun isActive(tool: Tool): Boolean = tool.id in layout.activeIds

    private fun rebuildButtons() {
        toolContainer.removeAllViews()
        toolViews.clear()
        toolIconSpec.clear()
        val btnSz = ToolbarMetrics.dp(ToolbarMetrics.BTN_SIZE_DP)
        val gap = ToolbarMetrics.dp(ToolbarMetrics.BTN_GAP_DP)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        for (tool in layout.tools) {
            val active = isActive(tool)
            val fg = if (active) Style.paper else Style.toolbarFg
            val iconPad = ToolbarMetrics.dp(10)
            val icon = toolbarIcon(tool, btnSz, fg, iconPad)
            val view: View = if (icon != null) {
                ImageView(ctx).apply {
                    setImageDrawable(icon)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(iconPad, iconPad, iconPad, iconPad)
                }
            } else {
                TextView(ctx).apply {
                    text = tool.fallbackText
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, ToolbarMetrics.sp(18f))
                    setTextColor(fg)
                    typeface = Typeface.DEFAULT
                    gravity = Gravity.CENTER
                }
            }
            view.background = Style.boxPx(if (active) Style.toolbarActive else Style.clear, 0, 0, 0f)
            view.contentDescription = Tools.labelOf(tool)
            view.layoutParams = LinearLayout.LayoutParams(btnSz, btnSz).apply {
                marginStart = gap / 2
                marginEnd = gap - gap / 2
                bottomMargin = gap
            }
            toolViews[tool.id] = view
            toolIconSpec[tool.id] = intArrayOf(btnSz, fg, iconPad)
            bindTool(view, tool)
            col.addView(view)
        }
        toolContainer.addView(col)
    }

    private fun bindTool(view: View, tool: Tool) {
        view.setOnClickListener { gestures.onToolTap(tool, view) }
        if (!tool.hasLongPress(layout.surface)) {

            view.setOnLongClickListener { true }
            return
        }
        val owner = PenOwner.toolbarPress(tool.id)
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> PenGuard.setFullScreenActive(true, owner)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Main.handler.postDelayed({
                    PenGuard.setFullScreenActive(false, owner)
                }, PenGuardTiming.TOOLBAR_PRESS_RELEASE_MS)
            }
            false
        }
        view.setOnLongClickListener {
            gestures.onToolLongPress(tool, view)
            Main.handler.postDelayed({
                PenGuard.setFullScreenActive(false, owner)
            }, PenGuardTiming.TOOLBAR_LONG_PRESS_RELEASE_MS)
            true
        }
    }

    fun update(next: ToolbarLayout): Boolean {
        val same = next.surface == layout.surface &&
            next.tools.map { it.id } == layout.tools.map { it.id } &&
            next.sidebar.map { it.id } == layout.sidebar.map { it.id }
        if (!same) return false
        val activeChanged = next.activeIds != layout.activeIds
        layout = next
        if (activeChanged) rebuildButtons() else refreshToolIcons()
        refreshClips()
        refreshLatches()
        return true
    }

    private val shownIcons = HashMap<String, String>()

    private fun refreshToolIcons() {
        for (tool in layout.tools) {
            val view = toolViews[tool.id] ?: continue
            val image = view as? ImageView ?: continue
            val spec = toolIconSpec[tool.id] ?: continue
            val name = Tools.iconOf(tool, layout.surface)
            if (shownIcons.put(tool.id, name) == name) continue
            toolbarIcon(tool, spec[0], spec[1], spec[2])?.let(image::setImageDrawable)
        }
    }

    fun flash(view: View) {
        val bg = view.background as? GradientDrawable ?: return
        bg.setColor(Style.toolbarFlash)
        if (view is TextView) view.setTextColor(Style.paper)
        if (view is ImageView) view.setColorFilter(Style.paper)
        Main.handler.postDelayed({
            val id = toolViews.entries.firstOrNull { it.value === view }?.key
            val active = id != null && id in layout.activeIds
            val fg = if (active) Style.paper else Style.toolbarFg
            bg.setColor(if (active) Style.toolbarActive else Style.clear)
            if (view is TextView) view.setTextColor(fg)
            if (view is ImageView) view.setColorFilter(fg)
        }, 120L)
    }

    fun toolView(id: String): View? = toolViews[id] ?: sidebarViews[id]
}
