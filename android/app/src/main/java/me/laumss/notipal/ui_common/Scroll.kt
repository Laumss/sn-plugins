package me.laumss.notipal.ui_common

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class ScrollHost(ctx: Context, val mode: Mode = Mode.PAGED, private val overlayBar: Boolean = false) : FrameLayout(ctx) {

    enum class Mode { PAGED, FREE }

    companion object {

        const val LANE = 24
        private const val BAR_WIDTH = 10
        private const val BAR_MIN_LENGTH = 48

        private const val PAGE_SLOP = 6

        const val FREE_LANE_DP = 28

        private const val FLING_MS = 3_000L
    }

    val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    var onScrolled: (() -> Unit)? = null

    var onPaged: (() -> Unit)? = null

    var onTopReached: (() -> Boolean)? = null

    var touching = false
        private set

    var onReleased: (() -> Unit)? = null

    var pageByLines = false

    var pageItems: (() -> List<View>)? = null

    private val paged = mode == Mode.PAGED
    private val pageSlop = ViewConfiguration.get(ctx).scaledTouchSlop * PAGE_SLOP
    private val pagedScroll: PagedScroll? = if (paged) PagedScroll(ctx) else null
    private val scroll: ScrollView = pagedScroll ?: ScrollView(ctx)
    private val inkBar: InkBar? = if (paged) InkBar(ctx) else null

    private var thumb: DragThumb? = if (paged) null else DragThumb(ctx)

    private var pendingOffset = -1

    private var placing = false

    private var flingUntil = 0L

    val scrollBarLaneWidthPx: Int = if (paged) Style.inkPx(LANE) else Style.dp(FREE_LANE_DP)

    val followLaneWidthPx: Int get() = Style.dp(FREE_LANE_DP)

    var follow: Boolean = false
        set(value) {
            if (!paged || value == field) return
            field = value
            if (value) ensureFollowThumb()
            if (flingUntil != 0L) {
                flingUntil = 0L
                pagedScroll?.stopFling()
            }
            sync()
        }

    init {
        scroll.isVerticalScrollBarEnabled = false
        scroll.isVerticalFadingEdgeEnabled = false
        scroll.setOnScrollChangeListener { _, _, y, _, oldY ->
            sync()
            onScrolled?.invoke()
            if (follow && !placing && (touching || SystemClock.uptimeMillis() < flingUntil)) {
                onPaged?.invoke()
                if (y == 0 && oldY > 0) onTopReached?.invoke()
            }
        }
        if (paged) {
            scroll.overScrollMode = View.OVER_SCROLL_NEVER
            scroll.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

            val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                post {
                    if (pendingOffset >= 0) place(pendingOffset)
                    pendingOffset = -1
                    sync()
                    onScrolled?.invoke()
                }
            }
            scroll.addOnLayoutChangeListener(relayout)
            content.addOnLayoutChangeListener(relayout)
            addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(inkBar, LayoutParams(scrollBarLaneWidthPx, LayoutParams.MATCH_PARENT, Gravity.END))
        } else {
            val pad = Style.dp(8)
            content.setPadding(pad, pad, pad, pad)
            scroll.addView(content)
            val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sync() }
            scroll.addOnLayoutChangeListener(relayout)
            content.addOnLayoutChangeListener(relayout)
            thumb!!.apply {

                visibility = View.INVISIBLE
                onDragScrollPercent = { pct -> scroll.scrollTo(0, (pct * maxOffset).toInt()) }
            }
            setPadding(0, Style.dp(20), 0, Style.dp(if (overlayBar) 12 else 16))
            addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                if (!overlayBar) rightMargin = scrollBarLaneWidthPx
            })
            addView(thumb, LayoutParams(scrollBarLaneWidthPx, LayoutParams.MATCH_PARENT, Gravity.END))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
    }

    val viewportHeight: Int get() = scroll.height

    val scrollOffset: Int get() = scroll.scrollY

    val maxOffset: Int get() = (content.height - scroll.height).coerceAtLeast(0)

    val contentHeight: Int get() = content.height

    fun scrollToOffset(offset: Int) {
        pendingOffset = offset.coerceAtLeast(0)
        if (pendingOffset == 0) place(0)
    }

    fun scrollNow(offset: Int) {
        pendingOffset = -1
        place(offset.coerceIn(0, maxOffset))
    }

    private fun place(y: Int) {
        placing = true
        try {
            scroll.scrollTo(0, y)
        } finally {
            placing = false
        }
        if (flingUntil != 0L) {
            flingUntil = 0L
            pagedScroll?.stopFling()
        }
    }

    private fun ensureFollowThumb() {
        if (thumb != null) return
        val t = DragThumb(context).apply {
            visibility = View.INVISIBLE
            onDragScrollPercent = { pct -> scroll.scrollTo(0, (pct * maxOffset).toInt()) }
            onDragChanged = { dragging ->
                touching = dragging
                if (!dragging) onReleased?.invoke()
            }
        }
        thumb = t
        addView(t, LayoutParams(followLaneWidthPx, LayoutParams.MATCH_PARENT, Gravity.END))
    }

    fun refreshThumb() {
        scroll.post { sync() }
    }

    fun availableContentWidth(panelWidth: Int): Int {
        val lane = if (paged || overlayBar) 0 else scrollBarLaneWidthPx
        return panelWidth - lane - content.paddingLeft - content.paddingRight
    }

    fun topInContent(view: View): Int? {
        var top = 0
        var v: View? = view
        while (v != null && v !== content) {
            top += v.top
            v = v.parent as? View
        }
        return if (v === content) top else null
    }

    private fun itemSpans(): List<Pair<Int, Int>> {
        if (pageByLines) return lineSpans()
        val items = pageItems?.invoke() ?: List(content.childCount) { content.getChildAt(it) }
        return items.filter { it.visibility != View.GONE }.mapNotNull { item ->
            topInContent(item)?.let { top -> top to top + item.height }
        }
    }

    private fun lineSpans(): List<Pair<Int, Int>> {
        val spans = ArrayList<Pair<Int, Int>>()
        fun walk(view: View, top: Int) {
            if (view.visibility != View.VISIBLE || view.height <= 0) return
            val layout = (view as? TextView)?.layout
            when {
                layout != null -> {
                    val base = top + (view as TextView).totalPaddingTop
                    for (line in 0 until layout.lineCount) spans += (base + layout.getLineTop(line)) to (base + layout.getLineBottom(line))
                }
                view is ViewGroup && view.childCount > 0 -> for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i)
                    walk(child, top + child.top)
                }
                else -> spans += top to top + view.height
            }
        }
        for (i in 0 until content.childCount) content.getChildAt(i).let { walk(it, it.top) }
        return spans
    }

    fun nextPage(): Boolean {
        val y = scroll.scrollY
        val h = scroll.height
        if (h <= 0 || y >= maxOffset) return false
        val edge = y + h
        val spans = itemSpans()
        val cut = spans.filter { (top, bottom) -> top < edge && bottom > edge }
        val target = if (cut.isNotEmpty()) {
            cut.filter { it.first > y }.minOfOrNull { it.first } ?: edge
        } else {
            spans.filter { it.first >= edge }.minOfOrNull { it.first } ?: edge
        }
        place(target.coerceAtMost(maxOffset))
        return true
    }

    fun prevPage(): Boolean {
        val y = scroll.scrollY
        val h = scroll.height
        if (h <= 0 || y <= 0) return false
        val target = itemSpans().map { it.first }.filter { it >= y - h && it < y }.minOrNull() ?: (y - h)
        place(target.coerceAtLeast(0))
        return true
    }

    fun turn(step: Int): Boolean {
        val turned = if (step > 0) nextPage() else prevPage() || onTopReached?.invoke() == true
        if (turned) onPaged?.invoke()
        return turned
    }

    fun scrubTo(offset: Int) {
        pendingOffset = -1
        val target = offset.coerceIn(0, maxOffset)
        if (target == scroll.scrollY) return
        place(target)
        onPaged?.invoke()
    }

    private fun sync() {
        val range = content.height
        val extent = scroll.height
        val bar = inkBar
        if (bar != null && !follow) {
            thumb?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
            if (extent <= 0 || range <= extent + 2) {
                if (bar.visibility != View.GONE) bar.visibility = View.GONE
                return
            }
            if (bar.visibility != View.VISIBLE) bar.visibility = View.VISIBLE
            bar.set(extent.toFloat() / range, scroll.scrollY.toFloat() / (range - extent))
            return
        }
        if (bar != null && bar.visibility != View.GONE) bar.visibility = View.GONE
        val t = thumb ?: return
        if (range <= extent || extent <= 0) {
            t.visibility = View.INVISIBLE
            return
        }
        if (t.visibility != View.VISIBLE) t.visibility = View.VISIBLE
        val percent = scroll.scrollY.toFloat() / (range - extent).coerceAtLeast(1).toFloat()
        t.setScrollPercent(percent.coerceIn(0f, 1f))
    }

    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class PagedScroll(ctx: Context) : ScrollView(ctx) {
        private var downX = 0f
        private var downY = 0f
        private var paging = false

        private var downAtTop = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = follow && super.onInterceptTouchEvent(ev)

        override fun onTouchEvent(ev: MotionEvent): Boolean = follow && super.onTouchEvent(ev)

        override fun fling(velocityY: Int) {
            if (!follow) return
            flingUntil = SystemClock.uptimeMillis() + FLING_MS
            super.fling(velocityY)
        }

        fun stopFling() = super.fling(0)

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (follow) return followTouch(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    paging = false
                    touching = true
                    super.dispatchTouchEvent(ev)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (paging) return true
                    val dx = abs(ev.x - downX)
                    val dy = abs(ev.y - downY)
                    if (dy > pageSlop && dy > dx) {
                        paging = true
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    } else {
                        super.dispatchTouchEvent(ev)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    if (paging) {
                        val dy = ev.y - downY
                        paging = false
                        if (ev.actionMasked == MotionEvent.ACTION_UP && abs(dy) > pageSlop) turn(if (dy < 0) 1 else -1)
                    } else {
                        super.dispatchTouchEvent(ev)
                    }
                    onReleased?.invoke()
                }
                else -> if (!paging) super.dispatchTouchEvent(ev)
            }
            return true
        }

        private fun followTouch(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                downY = ev.y
                downAtTop = scrollY == 0
                touching = true
                flingUntil = 0L
            }
            super.dispatchTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                touching = false
                if (ev.actionMasked == MotionEvent.ACTION_UP && downAtTop && scrollY == 0 && ev.y - downY > pageSlop) {
                    onTopReached?.invoke()
                }
                onReleased?.invoke()
            }
            return true
        }
    }

    private class InkBar(ctx: Context) : View(ctx) {
        private val barWidth = Style.inkPx(BAR_WIDTH).toFloat()
        private val minLength = Style.inkPx(BAR_MIN_LENGTH).toFloat()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Style.ink
            style = Paint.Style.FILL
        }
        private val rect = RectF()
        private var share = 1f
        private var position = 0f

        fun set(share: Float, position: Float) {
            this.share = share.coerceIn(0f, 1f)
            this.position = position.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val length = (height * share).coerceAtLeast(minLength).coerceAtMost(height.toFloat())
            val top = (height - length) * position
            val left = (width - barWidth) / 2f
            rect.set(left, top, left + barWidth, top + length)
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint)
        }
    }

    private class DragThumb(ctx: Context) : View(ctx) {
        private val unit = ctx.resources.displayMetrics.density * Style.factor()
        private val barW = 18f * unit
        private val barH = 48f * unit
        private val rightPad = 4f * unit
        private val strokeW = 2f * unit
        private val lineInset = 4f * unit
        private val lineH = 2f * unit
        private val lineGap = 2f * unit

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Style.paper; style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Style.ink; style = Paint.Style.STROKE; strokeWidth = strokeW }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Style.ink; style = Paint.Style.FILL }

        private var thumbTopY = 0f
        private var isDragging = false
        private var touchOffset = 0f

        var onDragScrollPercent: ((Float) -> Unit)? = null

        var onDragChanged: ((Boolean) -> Unit)? = null

        fun setScrollPercent(percent: Float) {
            val maxTop = (height - barH).coerceAtLeast(0f)
            thumbTopY = percent.coerceIn(0f, 1f) * maxTop
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (visibility != VISIBLE) return
            val left = width - barW - rightPad
            val top = thumbTopY
            val right = left + barW
            val bottom = top + barH
            val corner = barW / 2f
            val inset = strokeW / 2f
            val body = RectF(left + inset, top + inset, right - inset, bottom - inset)
            canvas.drawRoundRect(body, corner, corner, fillPaint)
            canvas.drawRoundRect(body, corner, corner, strokePaint)
            val centerY = (top + bottom) / 2f
            val lineLeft = left + lineInset
            val lineRight = right - lineInset
            val step = lineH + lineGap
            for (i in -1..1) {
                val ly = centerY + i * step
                canvas.drawRoundRect(RectF(lineLeft, ly - lineH / 2f, lineRight, ly + lineH / 2f), lineH / 2f, lineH / 2f, linePaint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (visibility != VISIBLE) return false
            val maxTop = (height - barH).coerceAtLeast(0f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val y = event.y
                    isDragging = true
                    onDragChanged?.invoke(true)
                    if (y >= thumbTopY && y <= thumbTopY + barH) {
                        touchOffset = y - thumbTopY
                    } else {

                        touchOffset = barH / 2f
                        thumbTopY = (y - touchOffset).coerceIn(0f, maxTop)
                        emitPercent(maxTop)
                        invalidate()
                    }
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                MotionEvent.ACTION_MOVE -> if (isDragging) {
                    thumbTopY = (event.y - touchOffset).coerceIn(0f, maxTop)
                    emitPercent(maxTop)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (isDragging) {
                    isDragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    onDragChanged?.invoke(false)
                    if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        private fun emitPercent(maxTop: Float) {
            if (maxTop <= 0f) return
            onDragScrollPercent?.invoke(thumbTopY / maxTop)
        }
    }
}

class MasonryLayout(ctx: Context) : ViewGroup(ctx) {

    var columns = 3
    var gap = 0
    var rowGap = 0

    private var lefts = IntArray(0)
    private var tops = IntArray(0)

    fun columnWidth(width: Int): Int {
        val cols = columns.coerceAtLeast(1)
        return ((width - paddingLeft - paddingRight - gap * (cols - 1)) / cols).coerceAtLeast(0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val cols = columns.coerceAtLeast(1)
        val colW = columnWidth(width)
        val childW = MeasureSpec.makeMeasureSpec(colW, MeasureSpec.EXACTLY)
        val childH = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

        val bottoms = IntArray(cols) { -rowGap }
        lefts = IntArray(childCount)
        tops = IntArray(childCount)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.measure(childW, childH)
            var c = 0
            for (k in 1 until cols) if (bottoms[k] < bottoms[c]) c = k
            lefts[i] = paddingLeft + c * (colW + gap)
            tops[i] = paddingTop + bottoms[c] + rowGap
            bottoms[c] += rowGap + child.measuredHeight
        }
        setMeasuredDimension(width, paddingTop + bottoms.max().coerceAtLeast(0) + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until minOf(childCount, tops.size)) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.layout(lefts[i], tops[i], lefts[i] + child.measuredWidth, tops[i] + child.measuredHeight)
        }
    }
}
