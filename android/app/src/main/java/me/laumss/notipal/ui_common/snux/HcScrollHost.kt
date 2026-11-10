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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import me.laumss.notipal.ui_common.filepicker.FilePickerScale as HcScale

class HcScrollHost(private val ctx: Context) {

    private fun dp(v: Int) = HcScale.dp(ctx, v)

    val view: LinearLayout
    val content: LinearLayout
    private val scrollView: ScrollView
    private val scrollBar: HcScrollBar

    var onScrolled: ((byUser: Boolean) -> Unit)? = null
    private var userScrolling = false

    init {
        view = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        scrollView = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = false
            addView(content)

            setOnScrollChangeListener { _, _, _, _, _ ->
                syncBarFromScroll()
                onScrolled?.invoke(userScrolling)
            }
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_MOVE) userScrolling = true
                false
            }
        }

        val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scrollView.post { syncBarFromScroll() } }
        scrollView.addOnLayoutChangeListener(relayout)
        content.addOnLayoutChangeListener(relayout)

        val barLaneW = dp(28)
        scrollBar = HcScrollBar(ctx).apply {
            visibility = View.GONE
            onDragScrollPercent = { pct ->
                userScrolling = true
                scrollView.getChildAt(0)?.let { child ->
                    val maxScroll = (child.height - scrollView.height).coerceAtLeast(0)
                    scrollView.scrollTo(0, (pct * maxScroll).toInt())
                }
            }
        }

        scrollView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        scrollBar.layoutParams = FrameLayout.LayoutParams(
            barLaneW, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.END }

        val innerFrame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
        }
        innerFrame.addView(scrollView)
        innerFrame.addView(scrollBar)
        view.addView(innerFrame)
    }

    fun prepareForContentChange() {
        userScrolling = false
        scrollView.scrollTo(0, 0)
        scrollBar.visibility = View.GONE
    }

    fun refreshThumb() {
        scrollView.post { syncBarFromScroll() }
    }

    fun scrollToTop() {
        userScrolling = false
        scrollView.scrollTo(0, 0)
        scrollView.post { syncBarFromScroll() }
    }

    val scrollOffset: Int get() = scrollView.scrollY

    fun restoreScrollOffset(offset: Int) {
        scrollView.post {
            userScrolling = false
            scrollView.scrollTo(0, offset.coerceAtLeast(0))
            syncBarFromScroll()
        }
    }

    private fun syncBarFromScroll() {
        val child = scrollView.getChildAt(0) ?: return
        val contentH = child.height
        val viewH = scrollView.height

        if (viewH <= 0 || contentH <= viewH + 2) {
            if (scrollBar.visibility != View.GONE) scrollBar.visibility = View.GONE
            return
        }
        if (scrollBar.visibility != View.VISIBLE) scrollBar.visibility = View.VISIBLE
        val percent = scrollView.scrollY.toFloat() /
            (contentH - viewH).coerceAtLeast(1).toFloat()
        scrollBar.setScrollPercent(percent.coerceIn(0f, 1f))
    }
}

private class HcScrollBar(ctx: Context) : View(ctx) {

    private val density = ctx.resources.displayMetrics.density * HcScale.contentFactor(ctx)
    private val barW = 18f * density
    private val barH = 48f * density
    private val rightPad = 4f * density
    private val strokeW = 2f * density
    private val lineInset = 4f * density
    private val lineH = 2f * density
    private val lineGap = 2f * density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = strokeW
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.FILL
    }

    private var thumbTopY: Float = 0f
    private var isDragging = false
    private var touchOffset = 0f

    var onDragScrollPercent: ((Float) -> Unit)? = null

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
            val r = RectF(lineLeft, ly - lineH / 2f, lineRight, ly + lineH / 2f)
            canvas.drawRoundRect(r, lineH / 2f, lineH / 2f, linePaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != VISIBLE) return false
        val maxTop = (height - barH).coerceAtLeast(0f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val y = event.y
                if (y >= thumbTopY && y <= thumbTopY + barH) {
                    isDragging = true
                    touchOffset = y - thumbTopY
                } else {
                    isDragging = true
                    touchOffset = barH / 2f
                    thumbTopY = (y - touchOffset).coerceIn(0f, maxTop)
                    emitPercent(maxTop)
                    invalidate()
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    thumbTopY = (event.y - touchOffset).coerceIn(0f, maxTop)
                    emitPercent(maxTop)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                    return true
                }
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
