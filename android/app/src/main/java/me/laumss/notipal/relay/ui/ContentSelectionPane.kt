package me.laumss.notipal.relay.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import me.laumss.notipal.NativeLocale
import me.laumss.notipal.relay.core.FormulaBlocks
import me.laumss.notipal.relay.core.MarkdownBlock
import me.laumss.notipal.relay.core.MarkdownDocument
import me.laumss.notipal.relay.core.MarkdownRangeSelection
import me.laumss.notipal.relay.core.MarkdownSelectionState
import me.laumss.notipal.relay.core.SelectionRowInfo
import me.laumss.notipal.ui_common.snux.InkScrollView
import me.laumss.notipal.ui_common.snux.Snux
import kotlin.math.roundToInt

private const val TAG = "RelaySelect"

private const val SELECTED_BG = 0xFFCCCCCC.toInt()
private const val ANCHOR_BG = 0xFFDDDDDD.toInt()

private const val INDICATOR_U = 32

private fun rowIndicator(selection: MarkdownSelectionState, index: Int): String? {
    if (selection.anchorBlockIndex == index) return "▲"
    for (range in selection.ranges) {

        if (index == range.firstBlockIndex && index == range.lastBlockIndex) return "◆"
        if (index == range.firstBlockIndex) return "▲"
        if (index == range.lastBlockIndex) return "▼"
    }
    return null
}

private class PaneRow(
    val info: SelectionRowInfo,
    val display: String?,
    val mono: Boolean = false,
    val quote: Boolean = false,
    val indentDp: Int = 0,
    val markerPrefix: String? = null,
    val fontSize: Int = 0,
)

private class BubbleEdges(val top: Boolean, val bottom: Boolean, stroke: Float) {
    private val half = stroke / 2
    private val paint = Paint().apply {
        color = INK
        strokeWidth = stroke
    }

    fun draw(canvas: Canvas, width: Int, height: Int) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawLine(half, 0f, half, h, paint)
        canvas.drawLine(w - half, 0f, w - half, h, paint)
        if (top) canvas.drawLine(0f, half, w, half, paint)
        if (bottom) canvas.drawLine(0f, h - half, w, h - half, paint)
    }
}

@SuppressLint("ViewConstructor")
private class RowView(context: Context, private val edges: BubbleEdges?) : LinearLayout(context) {

    lateinit var indicator: TextView
    var shownBg = Color.TRANSPARENT
    var shownMark: String? = null

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        edges?.draw(canvas, width, height)
    }
}

@SuppressLint("ViewConstructor")
private class GapView(context: Context, private val edges: BubbleEdges?) : View(context) {
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        edges?.draw(canvas, width, height)
    }
}

@SuppressLint("ViewConstructor")
internal class ContentSelectionPane(
    context: Context,
    private val document: MarkdownDocument,
    private val turns: List<SelectionTurn>,
    private val openAtBlock: Int?,
    private val onToggleRow: (Int) -> Unit,
    private val onRowsChanged: (List<SelectionRowInfo>) -> Unit,
) : FrameLayout(context) {

    private fun u(v: Number) = Snux.u(context, v)

    private val baseFont = detailFont(context)

    private val scroll = InkScrollView(context)
    private val content = scroll.content
    private val lane = Snux.px(context, InkScrollView.LANE)
    private var builtFor = -1
    private var selection = MarkdownSelectionState()
    private var rows: List<PaneRow> = emptyList()
    private var rowViews: List<RowView> = emptyList()

    private var gaps: Map<Int, GapView> = emptyMap()
    private var openRow: View? = null

    private var upAt = 0L
    private var paintedRows = 0
    private var paintedGaps = 0
    private var paintedTop = Int.MAX_VALUE
    private var paintedBottom = Int.MIN_VALUE

    init {
        content.setPadding(u(Snux.PANE_INSET), u(10), lane, u(18))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            openRow?.let { row ->
                openRow = null
                scroll.scrollNow(row.top)
            }
        }
    }

    fun setSelection(value: MarkdownSelectionState) {
        selection = value
        paint()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP) upAt = ev.eventTime
        return super.dispatchTouchEvent(ev)
    }

    private fun tapped(index: Int) {
        val clicked = SystemClock.uptimeMillis()
        onToggleRow(index)
        val worked = SystemClock.uptimeMillis()
        val up = upAt
        Log.i(TAG, "tap row=$index/${rows.size} up→click=${clicked - up}ms work=${worked - clicked}ms " +
            "repainted rows=$paintedRows gaps=$paintedGaps y=${if (paintedRows + paintedGaps > 0) "$paintedTop..$paintedBottom" else "-"} " +
            "scrollY=${scroll.scrollOffset} anchor=${selection.anchorBlockIndex} ranges=${selection.ranges.size}")
        val observer = viewTreeObserver
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(this)
                val preDraw = SystemClock.uptimeMillis()

                post {
                    Log.i(TAG, "tap row=$index frame: up→preDraw=${preDraw - up}ms up→drawn=${SystemClock.uptimeMillis() - up}ms")
                }
                return true
            }
        })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (width > 0 && width != builtFor) {
            builtFor = width
            build(width)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun build(width: Int) {
        val started = SystemClock.uptimeMillis()
        val contentWidth = width - u(Snux.PANE_INSET) - u(INDICATOR_U) - lane
        val density = resources.displayMetrics.density
        rows = buildRows((u(18) / density).roundToInt()) { text, fontSize, mono, insetDp ->
            measureLines(text, fontSize, mono, (contentWidth - dpPx(context, insetDp)).coerceAtLeast(64))
        }
        content.removeAllViews()
        openRow = null
        val outline = u(OUTLINE_U).toFloat().coerceAtLeast(1f)
        val views = ArrayList<RowView>(rows.size)
        val gapViews = HashMap<Int, GapView>()
        rows.forEachIndexed { index, row ->
            val block = row.info.blockIndex
            val turn = turns.firstOrNull { block in it.firstBlock..it.lastBlock }
            val blockStart = index == 0 || rows[index - 1].info.blockIndex != block
            val blockEnd = index == rows.lastIndex || rows[index + 1].info.blockIndex != block
            val inBubble = turn?.fromUser == true
            if (turn != null && blockStart && block == turn.firstBlock) {
                content.addView(metaLabel(context, if (turn.fromUser) NativeLocale.t("relay_chat_you") else "AI").apply {
                    setPadding(0, if (index == 0) 0 else u(24), 0, u(6))
                })
            }
            val edges = if (inBubble) BubbleEdges(blockStart && block == turn?.firstBlock, blockEnd && block == turn?.lastBlock, outline) else null
            val view = rowView(index, row, edges)
            views += view
            content.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            if (blockStart && block == openAtBlock) openRow = view
            if (index < rows.lastIndex && blockEnd) {
                val nextInBubble = inBubble && rows[index + 1].info.blockIndex <= (turn?.lastBlock ?: -1)
                val gap = GapView(context, if (nextInBubble) BubbleEdges(false, false, outline) else null)
                gapViews[index] = gap
                content.addView(gap, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, u(10)))
            }
        }
        content.addView(View(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, u(24)))
        rowViews = views
        gaps = gapViews
        paint()
        Log.i(TAG, "built rows=${rows.size} blocks=${document.blocks.size} width=$width in ${SystemClock.uptimeMillis() - started}ms")
        val infos = rows.map { it.info }
        post { onRowsChanged(infos) }
    }

    private fun rowView(index: Int, row: PaneRow, edges: BubbleEdges?): RowView {
        val ctx = context
        val left = FrameLayout(ctx)
        if (row.display == null) {
            left.addView(markdownBlockView(ctx, document.blocks[row.info.blockIndex], baseFont, boldBody = true),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        } else {
            val face = if (row.mono) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.DEFAULT_BOLD
            left.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dpPx(ctx, row.indentDp), 0, 0, 0)
                if (row.markerPrefix != null) addView(
                    rowText(ctx, row.markerPrefix, row.fontSize, face, INK),
                    LinearLayout.LayoutParams(dpPx(ctx, LIST_MARKER_DP), LinearLayout.LayoutParams.WRAP_CONTENT),
                )
                addView(rowText(ctx, row.display, row.fontSize, face, if (row.quote) MUTED else INK))
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        val mark = Snux.text(ctx, "", Snux.F_META, INK, bold = true).apply { gravity = Gravity.CENTER }
        return RowView(ctx, edges).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            indicator = mark
            addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(mark, LinearLayout.LayoutParams(u(INDICATOR_U), LinearLayout.LayoutParams.WRAP_CONTENT))
            setOnClickListener { tapped(index) }
        }
    }

    private fun rowText(ctx: Context, value: String, sizeSp: Int, face: Typeface, color: Int) = TextView(ctx).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
        typeface = face
        isSingleLine = true
        gravity = Gravity.CENTER_VERTICAL
        minHeight = spPx(ctx, sizeSp * 1.55f).roundToInt()
        text = value
    }

    private fun paint() {
        paintedRows = 0
        paintedGaps = 0
        paintedTop = Int.MAX_VALUE
        paintedBottom = Int.MIN_VALUE
        fun painted(view: View) {
            paintedTop = minOf(paintedTop, view.top)
            paintedBottom = maxOf(paintedBottom, view.bottom)
        }
        rowViews.forEachIndexed { index, view ->
            val selected = MarkdownRangeSelection.isSelected(selection, index)
            val bg = when {
                selected -> SELECTED_BG
                selection.anchorBlockIndex == index -> ANCHOR_BG
                else -> Color.TRANSPARENT
            }
            var changed = false
            if (bg != view.shownBg) {
                view.shownBg = bg
                view.setBackgroundColor(bg)
                changed = true
            }
            val mark = rowIndicator(selection, index)
            if (mark != view.shownMark) {
                view.shownMark = mark
                view.indicator.text = mark.orEmpty()
                changed = true
            }
            if (changed) {
                paintedRows++
                painted(view)
            }
            gaps[index]?.let { gap ->
                val on = selected && MarkdownRangeSelection.isSelected(selection, index + 1)
                val want = if (on) SELECTED_BG else Color.TRANSPARENT
                if (gap.tag != want) {
                    gap.tag = want
                    gap.setBackgroundColor(want)
                    paintedGaps++
                    painted(gap)
                }
            }
        }
    }

    private fun buildRows(
        bubbleInsetDp: Int,
        lines: (text: String, fontSize: Int, mono: Boolean, insetDp: Int) -> List<Pair<Int, Int>>,
    ): List<PaneRow> = document.blocks.flatMapIndexed { bi, block ->
        val text = if (block is MarkdownBlock.Heading) null else FormulaBlocks.selectableText(block)
        if (text.isNullOrBlank()) return@flatMapIndexed listOf(PaneRow(SelectionRowInfo(bi), display = null))
        val item = block as? MarkdownBlock.ListItem
        val mono = block is MarkdownBlock.CodeFence
        val inBubble = turns.any { it.fromUser && bi in it.firstBlock..it.lastBlock }
        val indentDp = (item?.indent ?: 0) * LIST_INDENT_DP + if (inBubble) bubbleInsetDp else 0

        val insetDp = when (block) {
            is MarkdownBlock.ListItem -> indentDp + LIST_MARKER_DP
            is MarkdownBlock.Quote -> 13
            is MarkdownBlock.CodeFence -> 22
            else -> indentDp
        }
        val fontSize = if (mono) baseFont - 3 else baseFont
        lines(text, fontSize, mono, insetDp).mapIndexed { li, (start, end) ->
            PaneRow(
                SelectionRowInfo(bi, start, end, atomic = false),
                display = text.substring(start, end).trimEnd('\n'),
                mono = mono,
                quote = block is MarkdownBlock.Quote,
                indentDp = indentDp,
                markerPrefix = item?.let { if (li == 0) it.marker else "" },
                fontSize = fontSize,
            )
        }
    }

    private fun measureLines(text: String, fontSize: Int, mono: Boolean, width: Int): List<Pair<Int, Int>> {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = spPx(context, fontSize)
            typeface = if (mono) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.DEFAULT_BOLD
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)

            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
        return (0 until layout.lineCount).map { layout.getLineStart(it) to layout.getLineEnd(it) }
    }
}
