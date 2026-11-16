package me.laumss.notipal.relay

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Strings
import me.laumss.notipal.host.HostSession
import me.laumss.notipal.ui_common.Dialogs
import me.laumss.notipal.ui_common.ScrollHost
import me.laumss.notipal.ui_common.Style
import me.laumss.notipal.ui_common.Widgets
import kotlin.math.roundToInt

data class MarkdownBlockRange(
    val firstBlockIndex: Int,
    val lastBlockIndex: Int,
) {
    init {
        require(firstBlockIndex >= 0)
        require(lastBlockIndex >= firstBlockIndex)
    }

    operator fun contains(index: Int): Boolean = index in firstBlockIndex..lastBlockIndex
}

data class MarkdownSelectionState(
    val anchorBlockIndex: Int? = null,
    val ranges: List<MarkdownBlockRange> = emptyList(),
) {

    val selectedIndices: List<Int> get() = ranges.flatMap { it.firstBlockIndex..it.lastBlockIndex }
}

object MarkdownRangeSelection {
    fun toggle(
        state: MarkdownSelectionState,
        blockIndex: Int,
        blockCount: Int,
    ): MarkdownSelectionState {
        if (blockIndex !in 0 until blockCount) return state

        val anchor = state.anchorBlockIndex
        if (anchor == null) {
            val selectedRange = state.ranges.firstOrNull { blockIndex in it }
            return if (selectedRange != null) {
                state.copy(ranges = state.ranges - selectedRange)
            } else {
                state.copy(anchorBlockIndex = blockIndex)
            }
        }

        val completed = MarkdownBlockRange(
            firstBlockIndex = minOf(anchor, blockIndex),
            lastBlockIndex = maxOf(anchor, blockIndex),
        )
        return MarkdownSelectionState(
            anchorBlockIndex = null,
            ranges = mergeRanges(state.ranges + completed),
        )
    }

    fun isSelected(state: MarkdownSelectionState, blockIndex: Int): Boolean =
        state.ranges.any { blockIndex in it }

    private fun mergeRanges(ranges: List<MarkdownBlockRange>): List<MarkdownBlockRange> {
        val sorted = ranges.sortedBy { it.firstBlockIndex }
        if (sorted.isEmpty()) return emptyList()

        val merged = mutableListOf(sorted.first())
        sorted.drop(1).forEach { next ->
            val current = merged.last()
            if (next.firstBlockIndex <= current.lastBlockIndex + 1) {
                merged[merged.lastIndex] = MarkdownBlockRange(
                    firstBlockIndex = current.firstBlockIndex,
                    lastBlockIndex = maxOf(current.lastBlockIndex, next.lastBlockIndex),
                )
            } else {
                merged += next
            }
        }
        return merged
    }
}

data class SelectionRowInfo(
    val blockIndex: Int,
    val charStart: Int = 0,
    val charEnd: Int = 0,
    val atomic: Boolean = true,
)

internal object SelectionText {

    private class Builder {
        val text = StringBuilder()
        private val plain = StringBuilder()

        fun flush() {
            val t = text.toString().trim('\r', '\n')
            text.setLength(0)
            if (t.isNotBlank()) put(t)
        }

        private fun put(plainForm: String) {
            if (plain.isNotEmpty()) plain.append("\n\n")
            plain.append(plainForm)
        }

        fun build(): String {
            flush()
            return plain.toString()
        }
    }

    fun selectableText(block: MarkdownBlock): String? = when (block) {
        is MarkdownBlock.Paragraph -> block.text
        is MarkdownBlock.Quote -> block.text
        is MarkdownBlock.ListItem -> block.text
        is MarkdownBlock.Heading -> block.text
        is MarkdownBlock.CodeFence -> block.code
        else -> null
    }

    fun selectionText(block: MarkdownBlock, asWritten: Boolean = false): String? =
        if (asWritten || block is MarkdownBlock.CodeFence) selectableText(block)
        else selectableText(block)?.let(MarkdownDocumentParser::plainInline)

    private fun MarkdownDocument.slice(from: MarkdownBlock, to: MarkdownBlock = from) =
        source.substring(from.sourceRange.startOffset, to.sourceRange.endOffsetExclusive)

    private fun blockText(document: MarkdownDocument, block: MarkdownBlock, plain: Boolean = false, asWritten: Boolean = false): String {
        val text = if (plain) selectionText(block, asWritten) else selectableText(block)
        return when (block) {
            is MarkdownBlock.ListItem -> "${block.marker} ${text.orEmpty()}".trimEnd()
            is MarkdownBlock.Table -> document.slice(block).trim('\r', '\n')
            else -> text.orEmpty()
        }
    }

    fun text(
        document: MarkdownDocument,
        rows: List<SelectionRowInfo>,
        selected: List<Int>,
        asWritten: (blockIndex: Int) -> Boolean = { false },
    ): String {
        val out = Builder()

        val texts = HashMap<Int, String>()
        var prevIndex = -2
        for (ri in selected) {
            val row = rows.getOrNull(ri) ?: continue
            val block = document.blocks.getOrNull(row.blockIndex) ?: continue
            when (block) {
                is MarkdownBlock.Rule -> out.flush()
                else -> {
                    val written = asWritten(row.blockIndex)
                    val piece = if (row.atomic) blockText(document, block, plain = true, asWritten = written)
                        else lineText(block, row, texts.getOrPut(row.blockIndex) { selectionText(block, written).orEmpty() })
                    if (!row.atomic || piece.isNotBlank()) {
                        val continues = ri == prevIndex + 1 && rows[prevIndex].blockIndex == row.blockIndex
                        if (out.text.isNotEmpty() && !continues) out.text.append('\n')
                        out.text.append(piece)
                    }
                }
            }
            prevIndex = ri
        }
        return out.build()
    }

    private fun lineText(block: MarkdownBlock, row: SelectionRowInfo, full: String): String {
        val line = full.substring(row.charStart.coerceAtMost(full.length), row.charEnd.coerceAtMost(full.length))
        return if (block is MarkdownBlock.ListItem && row.charStart == 0) "${block.marker} $line" else line
    }
}

data class SelectionTurn(val firstBlock: Int, val lastBlock: Int, val fromUser: Boolean)

class SelectionDocument(val document: MarkdownDocument, val turns: List<SelectionTurn>) {

    fun blockOfAnswer(text: String): Int? {
        val index = sourceTurns.indexOfLast { !it.fromUser && it.text == text }
        return if (index < 0) null else turnOfSource[index]?.firstBlock
    }

    private var sourceTurns: List<ChatTurn> = emptyList()
    private var turnOfSource: Map<Int, SelectionTurn> = emptyMap()

    companion object {
        fun of(chat: List<ChatTurn>): SelectionDocument {
            val source = StringBuilder()
            val blocks = ArrayList<MarkdownBlock>()
            val turns = ArrayList<SelectionTurn>()
            val ofSource = HashMap<Int, SelectionTurn>()
            chat.forEachIndexed { index, turn ->
                val first = blocks.size
                if (turn.fromUser) {
                    VoiceTags.split(turn.text).flatMap { it.text.lines() }.filter { it.isNotBlank() }.forEach { line ->
                        val start = source.length
                        source.append(line)
                        blocks += MarkdownBlock.Paragraph(line, SourceRange(start, source.length))
                        source.append('\n')
                    }
                } else {
                    val parsed = MarkdownDocumentParser.parse(turn.text)
                    val offset = source.length
                    parsed.blocks.forEach { blocks += moved(it, offset) }
                    source.append(parsed.source).append('\n')
                }
                if (blocks.size > first) {
                    val selectionTurn = SelectionTurn(first, blocks.lastIndex, turn.fromUser)
                    turns += selectionTurn
                    ofSource[index] = selectionTurn
                }
            }
            return SelectionDocument(MarkdownDocument(source.toString(), blocks), turns).also {
                it.sourceTurns = chat
                it.turnOfSource = ofSource
            }
        }

        private fun moved(block: MarkdownBlock, by: Int): MarkdownBlock {
            val range = SourceRange(block.sourceRange.startOffset + by, block.sourceRange.endOffsetExclusive + by)
            return when (block) {
                is MarkdownBlock.Heading -> block.copy(sourceRange = range)
                is MarkdownBlock.Paragraph -> block.copy(sourceRange = range)
                is MarkdownBlock.Quote -> block.copy(sourceRange = range)
                is MarkdownBlock.CodeFence -> block.copy(sourceRange = range)
                is MarkdownBlock.ListItem -> block.copy(sourceRange = range)
                is MarkdownBlock.Rule -> block.copy(sourceRange = range)
                is MarkdownBlock.Table -> block.copy(sourceRange = range)
            }
        }
    }
}

private const val TAG = "RelaySelect"

private val INVERT = Paint().apply {
    colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    )))
}

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
        color = Style.ink
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

    var inverted = false
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

    private fun u(v: Number) = Style.u(v)

    private val baseFont = Style.detailFont()

    private val scroll = ScrollHost(context)
    private val content = scroll.content
    private val lane = Style.inkPx(ScrollHost.LANE)
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
        content.setPadding(u(Style.PANE_INSET), u(10), lane, u(18))
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
        val contentWidth = width - u(Style.PANE_INSET) - u(INDICATOR_U) - lane
        val density = resources.displayMetrics.density
        rows = buildRows((u(18) / density).roundToInt()) { text, fontSize, mono, insetDp ->
            measureLines(text, fontSize, mono, (contentWidth - Style.rawDp(insetDp)).coerceAtLeast(64))
        }
        content.removeAllViews()
        openRow = null
        val outline = u(Style.OUTLINE_U).toFloat().coerceAtLeast(1f)
        val views = ArrayList<RowView>(rows.size)
        val gapViews = HashMap<Int, GapView>()
        rows.forEachIndexed { index, row ->
            val block = row.info.blockIndex
            val turn = turns.firstOrNull { block in it.firstBlock..it.lastBlock }
            val blockStart = index == 0 || rows[index - 1].info.blockIndex != block
            val blockEnd = index == rows.lastIndex || rows[index + 1].info.blockIndex != block
            val inBubble = turn?.fromUser == true
            if (turn != null && blockStart && block == turn.firstBlock) {
                content.addView(Widgets.metaLabel(context, if (turn.fromUser) Strings.get("relay_chat_you") else "AI").apply {
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
                setPadding(Style.rawDp(row.indentDp), 0, 0, 0)
                if (row.markerPrefix != null) addView(
                    rowText(ctx, row.markerPrefix, row.fontSize, face, Style.ink),
                    LinearLayout.LayoutParams(Style.rawDp(LIST_MARKER_DP), LinearLayout.LayoutParams.WRAP_CONTENT),
                )
                addView(rowText(ctx, row.display, row.fontSize, face, if (row.quote) Style.hint else Style.ink))
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        val mark = Widgets.text(ctx, "", Style.F_META, Style.ink, bold = true).apply {
            gravity = Gravity.CENTER

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isFallbackLineSpacing = false
        }
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
        minHeight = Style.rawSp(sizeSp * 1.55f).roundToInt()
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
            val inverted = selected || selection.anchorBlockIndex == index
            val mark = rowIndicator(selection, index)
            if (inverted != view.inverted || mark != view.shownMark) {
                paintedRows++
                painted(view)
            }
            if (inverted != view.inverted) {
                view.inverted = inverted

                view.setBackgroundColor(if (inverted) Style.paper else Style.clear)
                view.setLayerType(if (inverted) LAYER_TYPE_HARDWARE else LAYER_TYPE_NONE, if (inverted) INVERT else null)
            }
            if (mark != view.shownMark) {
                view.shownMark = mark
                view.indicator.text = mark.orEmpty()
            }
            gaps[index]?.let { gap ->
                val on = selected && MarkdownRangeSelection.isSelected(selection, index + 1)
                val want = if (on) Style.ink else Style.clear
                if (gap.tag != want) {
                    paintedGaps++
                    painted(gap)
                    gap.tag = want
                    gap.setBackgroundColor(want)
                }
            }
        }
    }

    private fun isBubbleBlock(block: Int) = turns.any { it.fromUser && block in it.firstBlock..it.lastBlock }

    private fun buildRows(
        bubbleInsetDp: Int,
        lines: (text: String, fontSize: Int, mono: Boolean, insetDp: Int) -> List<Pair<Int, Int>>,
    ): List<PaneRow> = document.blocks.flatMapIndexed { bi, block ->
        val inBubble = isBubbleBlock(bi)

        val text = if (block is MarkdownBlock.Heading) null else SelectionText.selectionText(block, asWritten = inBubble)
        if (text.isNullOrBlank()) return@flatMapIndexed listOf(PaneRow(SelectionRowInfo(bi), display = null))
        val item = block as? MarkdownBlock.ListItem
        val mono = block is MarkdownBlock.CodeFence
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
            textSize = Style.rawSp(fontSize)
            typeface = if (mono) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.DEFAULT_BOLD
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)

            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
        return (0 until layout.lineCount).map { layout.getLineStart(it) to layout.getLineEnd(it) }
    }
}

data class SelectActions(
    val selectedRowCount: Int,
    val canInsert: Boolean,
    val clear: () -> Unit,
    val insert: () -> Unit,
)

class SelectionPane(private val host: RelayHost) {
    private val context: Context get() = App.context

    private val frame = FrameLayout(App.context).apply { setBackgroundColor(Style.paper) }
    private var message: RelayMessage? = null
    private var picking: SelectionDocument? = null
    private var pane: ContentSelectionPane? = null
    private var selection = MarkdownSelectionState()
    private var rows: List<SelectionRowInfo> = emptyList()

    val view: View get() = frame

    var actions: SelectActions? = null
        private set

    var onActions: ((SelectActions?) -> Unit)? = null

    fun show(message: RelayMessage, turns: List<ChatTurn>, reading: String?) {
        leavePicking()
        this.message = message
        enterPicking(turns, reading)
        publishActions()
    }

    fun clear() {
        if (message == null && picking == null) return
        leavePicking()
        message = null
        publishActions()
    }

    private fun enterPicking(turns: List<ChatTurn>, reading: String?) {
        val doc = SelectionDocument.of(turns)
        picking = doc
        val view = ContentSelectionPane(
            context, doc.document, doc.turns, reading?.let { doc.blockOfAnswer(it) },
            onToggleRow = { index ->
                selection = MarkdownRangeSelection.toggle(selection, index, rows.size)
                pane?.setSelection(selection)

                frame.removeCallbacks(publishLater)
                frame.post(publishLater)
            },
            onRowsChanged = { updated ->
                if (updated != rows) {
                    rows = updated
                    selection = MarkdownSelectionState()
                    pane?.setSelection(selection)
                    publishActions()
                }
            },
        )
        pane = view
        frame.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private val publishLater = Runnable { publishActions() }

    private fun leavePicking() {
        frame.removeCallbacks(publishLater)
        pane?.let(frame::removeView)
        pane = null
        picking = null
        selection = MarkdownSelectionState()
        rows = emptyList()
    }

    private fun publishActions() {
        val doc = picking?.document
        if (doc == null) {
            setActions(null)
            return
        }
        val picked = selection.selectedIndices
        val turns = picking?.turns.orEmpty()
        val text = SelectionText.text(doc, rows, picked) { block -> turns.any { it.fromUser && block in it.firstBlock..it.lastBlock } }
        setActions(SelectActions(
            selectedRowCount = picked.size,
            canInsert = text.isNotBlank(),
            clear = {
                selection = MarkdownSelectionState()
                pane?.setSelection(selection)
                publishActions()
            },
            insert = { insertPicked(text) },
        ))
    }

    private fun setActions(value: SelectActions?) {
        actions = value
        onActions?.invoke(value)
    }

    private fun insertPicked(text: String) {
        if (HostSession.state.document) {
            copyToClipboard(text)
            return
        }
        val id = message?.id ?: return
        Conversations.insertSelection(id, text)
        host.showInsertOnPage()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Notipal", text))

        host.open(RelayPage.DETAIL)
        Dialogs.tip(Strings.get("relay_copied"))
    }
}
