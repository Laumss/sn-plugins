package me.laumss.notipal.ui_common.snux

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
internal class InkScrollView(ctx: Context) : FrameLayout(ctx) {

    companion object {

        const val LANE = 24
        private const val BAR_WIDTH = 10
        private const val BAR_MIN_LENGTH = 48

        private const val PAGE_SLOP = 6
    }

    val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    var onScrolled: (() -> Unit)? = null

    var onPaged: (() -> Unit)? = null

    var onTopReached: (() -> Boolean)? = null

    var touching = false
        private set

    var onReleased: (() -> Unit)? = null

    private val pageSlop = ViewConfiguration.get(ctx).scaledTouchSlop * PAGE_SLOP
    private val scroll = PagedScroll(ctx)
    private val bar = Bar(ctx)

    private var pendingOffset = -1

    init {
        scroll.isVerticalScrollBarEnabled = false
        scroll.isVerticalFadingEdgeEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            sync()
            onScrolled?.invoke()
        }

        val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            post {
                if (pendingOffset >= 0) scroll.scrollTo(0, pendingOffset)
                pendingOffset = -1
                sync()
                onScrolled?.invoke()
            }
        }
        scroll.addOnLayoutChangeListener(relayout)
        content.addOnLayoutChangeListener(relayout)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(bar, LayoutParams(Snux.px(ctx, LANE), LayoutParams.MATCH_PARENT, Gravity.END))
    }

    val viewportHeight: Int get() = scroll.height

    val scrollOffset: Int get() = scroll.scrollY

    fun scrollToOffset(offset: Int) {
        pendingOffset = offset.coerceAtLeast(0)
        if (pendingOffset == 0) scroll.scrollTo(0, 0)
    }

    fun scrollNow(offset: Int) {
        pendingOffset = -1
        scroll.scrollTo(0, offset.coerceIn(0, maxOffset))
    }

    val maxOffset: Int get() = (content.height - scroll.height).coerceAtLeast(0)

    var pageByLines = false

    var pageItems: (() -> List<View>)? = null

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
        scroll.scrollTo(0, target.coerceAtMost(maxOffset))
        return true
    }

    fun prevPage(): Boolean {
        val y = scroll.scrollY
        val h = scroll.height
        if (h <= 0 || y <= 0) return false
        val target = itemSpans().map { it.first }.filter { it >= y - h && it < y }.minOrNull() ?: (y - h)
        scroll.scrollTo(0, target.coerceAtLeast(0))
        return true
    }

    private fun sync() {
        val range = content.height
        val extent = scroll.height
        if (extent <= 0 || range <= extent + 2) {
            if (bar.visibility != View.GONE) bar.visibility = View.GONE
            return
        }
        if (bar.visibility != View.VISIBLE) bar.visibility = View.VISIBLE
        bar.set(extent.toFloat() / range, scroll.scrollY.toFloat() / (range - extent))
    }

    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class PagedScroll(ctx: Context) : ScrollView(ctx) {
        private var downX = 0f
        private var downY = 0f
        private var paging = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

        override fun onTouchEvent(ev: MotionEvent): Boolean = false

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
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
                        if (ev.actionMasked == MotionEvent.ACTION_UP && abs(dy) > pageSlop) {
                            val turned = if (dy < 0) nextPage() else prevPage() || onTopReached?.invoke() == true
                            if (turned) onPaged?.invoke()
                        }
                    } else {
                        super.dispatchTouchEvent(ev)
                    }
                    onReleased?.invoke()
                }
                else -> if (!paging) super.dispatchTouchEvent(ev)
            }
            return true
        }
    }

    private class Bar(ctx: Context) : View(ctx) {
        private val barWidth = Snux.px(ctx, BAR_WIDTH).toFloat()
        private val minLength = Snux.px(ctx, BAR_MIN_LENGTH).toFloat()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
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
}
