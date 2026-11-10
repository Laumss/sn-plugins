package me.laumss.notipal.relay.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import me.laumss.notipal.FloatingToolbarModule
import me.laumss.notipal.NativeLocale
import me.laumss.notipal.relay.RelayOpenTrace
import me.laumss.notipal.relay.core.FormulaBlocks
import me.laumss.notipal.relay.core.MarkdownDocument
import me.laumss.notipal.relay.core.MarkdownDocumentParser
import me.laumss.notipal.relay.core.MarkdownRangeSelection
import me.laumss.notipal.relay.core.MarkdownSelectionState
import me.laumss.notipal.relay.core.RelayTurns
import me.laumss.notipal.relay.core.SelectionRowInfo
import me.laumss.notipal.relay.core.VoiceTags
import me.laumss.notipal.relay.model.RelayMessage
import me.laumss.notipal.ui_common.UiUtils
import me.laumss.notipal.ui_common.snux.InkScrollView
import me.laumss.notipal.ui_common.snux.LineIcon
import me.laumss.notipal.ui_common.snux.Snux
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val STATUS_ROW = 75
internal const val STATUS_ICON = 32

private const val TAG = "RelayDetail"

private const val FOLLOW_SLACK_PX = 200

private const val BUBBLE_SHARE = 0.92f
private const val BUBBLE_SHARE_SMALL = 1f

private const val REVEAL_STEP = 2

data class ChatTurn(
    val id: String,
    val fromUser: Boolean,
    val text: String,
    val continues: Boolean = false,
    val images: List<StagedImage> = emptyList(),
)

data class DetailSelectActions(
    val selectedRowCount: Int,
    val canInsert: Boolean,
    val clear: () -> Unit,
    val insert: () -> Unit,
)

private fun conversationTurns(
    message: RelayMessage?,
    markers: RelayTurns.Markers,
    leadTurn: ChatTurn?,
    chatTurns: List<ChatTurn>,
): List<ChatTurn> {
    if (message == null) return emptyList()
    val own = RelayTurns.split(message.id, message.text, markers, message.lead)
        .takeIf { it.size > 1 || it.firstOrNull()?.fromUser == true }
    val lead = leadTurn?.takeIf { own?.firstOrNull()?.fromUser != true }
    val body = own ?: listOf(ChatTurn(message.id, false, message.text))
    return RelayTurns.merge(listOfNotNull(lead) + body + chatTurns)
}

@SuppressLint("ViewConstructor")
internal class MessageDetailContent(context: Context, private val callbacks: Callbacks) : FrameLayout(context) {

    class Callbacks(
        val onSelectionPayload: (FormulaBlocks.Payload) -> Unit,
        val onReplaceHandwriting: (FormulaBlocks.Payload) -> Unit,
        val onSelectionActionsChanged: (DetailSelectActions?) -> Unit,

        val onReplaceChanged: ((() -> Unit)?) -> Unit,

        val onReadLine: (String?, Int?) -> Unit,
    )

    class Model(
        val message: RelayMessage?,
        val selecting: Boolean,

        val markers: RelayTurns.Markers,
        val chatTurns: List<ChatTurn>,
        val chatGenerating: Boolean,

        val followSignal: Int,

        val emptyTitle: String?,

        val leadTurn: ChatTurn?,
    )

    private fun u(v: Number) = Snux.u(context, v)

    private val baseFont = detailFont(context)
    private val metaUnits: Float = Snux.F_META.toFloat()

    private var scrollShare: Float? = null
    private var model: Model? = null

    private val readLayer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var readTitle: String? = null
    private var readPercent: Int? = null
    private var sentTitle: String? = null
    private var small = false
    private var replyBlock: LinearLayout? = null

    private val scroll = InkScrollView(context).apply { pageByLines = true }
    private val content = scroll.content
    private val empty = emptyLine(context, NativeLocale.t("relay_no_message")).apply { visibility = GONE }

    private var parsedDocument: MarkdownDocument = MarkdownDocumentParser.parse("")
    private var parsedFor = ""
    private var documentText = ""

    private fun document(): MarkdownDocument {
        if (parsedFor != documentText) {
            parsedDocument = MarkdownDocumentParser.parse(documentText)
            parsedFor = documentText
        }
        return parsedDocument
    }
    private var replyDocument = MarkdownDocumentLayout(context, baseFont, serifHeads = true)
    private var turnsKey: Triple<String, String, String>? = null
    private var turnsMarkers: RelayTurns.Markers? = null
    private var ownTurns: List<ChatTurn>? = null
    private val turnViews = HashMap<String, TurnView>()
    private var shown: List<ChatTurn> = emptyList()
    private val generating = Snux.text(context, NativeLocale.t("relay_chat_generating"), Snux.F_META, MUTED).apply {
        setPadding(0, u(20), 0, 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    private val endSpace = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, u(24))
    }
    private var replacePayload: FormulaBlocks.Payload? = null

    private var followBottom = true
    private var openAtAnswer = true

    private var hiddenBefore = 0
    private var weights = IntArray(0)

    private var revealAnchor: View? = null
    private var seenSignal = 0
    private var viewport = 0
    private var reading: String? = null
    private val updateProgress = Runnable { showProgress() }

    private var picking: SelectionDocument? = null
    private var pane: ContentSelectionPane? = null
    private var selection = MarkdownSelectionState()
    private var rows: List<SelectionRowInfo> = emptyList()

    init {
        val inset = u(Snux.PANE_INSET)
        placeContent()
        readLayer.addView(FrameLayout(context).apply {
            addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                leftMargin = inset
                rightMargin = inset
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(readLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        scroll.onPaged = {

            followBottom = revealAnchor == null && scroll.maxOffset - scroll.scrollOffset < FOLLOW_SLACK_PX
            openAtAnswer = false
        }
        scroll.onTopReached = ::revealEarlier
        scroll.onScrolled = {
            removeCallbacks(updateProgress)
            post(updateProgress)
        }

        content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeView() }
        scroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val height = bottom - top
            val shrink = viewport - height
            viewport = height

            if (shrink > 0 && height > 0) {
                openAtAnswer = false
                scroll.scrollNow(scroll.scrollOffset + shrink)
            }
            placeView()
        }
    }

    val touching: Boolean get() = scroll.touching

    var onReleased: (() -> Unit)?
        get() = scroll.onReleased
        set(value) { scroll.onReleased = value }

    fun setPaper(argb: Int) {
        setBackgroundColor(argb)
    }

    fun setSmall(small: Boolean) {
        if (small == this.small) return
        this.small = small
        placeContent()
        val max = scroll.maxOffset
        scrollShare = if (max > 0) scroll.scrollOffset.toFloat() / max else null
        RelayOpenTrace.mark("detail margins small=$small, ${content.childCount} views kept")
    }

    private fun placeContent() {
        if (small) content.setPadding(u(22), u(14), u(22), u(12)) else content.setPadding(u(36), u(32), u(60), u(28))
        generating.setPadding(0, u(20), 0, 0)
    }

    fun clear() {
        if (model == null) return
        model = null
        leavePicking()
        callbacks.onSelectionActionsChanged(null)
        setReading(null)
        setReplace(null)
        resetReading()
        viewport = 0
        setReadLine(null, null)
    }

    private fun resetReading() {
        scrollShare = null
        turnViews.clear()
        content.removeAllViews()
        replyDocument = MarkdownDocumentLayout(context, baseFont, serifHeads = true)
        replyBlock = null
        turnsKey = null
        ownTurns = null
        shown = emptyList()
        hiddenBefore = 0
        weights = IntArray(0)
        revealAnchor = null
        scroll.scrollNow(0)
    }

    fun bind(next: Model) {
        val previous = model
        val message = next.message
        val fresh = previous == null || previous.message?.id != message?.id
        if (fresh) {
            followBottom = message?.closed != true
            openAtAnswer = true
            setReading(null)
            resetReading()
        }
        val signalled = previous != null && next.followSignal != seenSignal
        seenSignal = next.followSignal
        if (signalled) followBottom = true

        val text = message?.text.orEmpty()
        documentText = text
        setReplace(
            if (message?.kind != "formula") null
            else document().let { doc -> FormulaBlocks.recognizedIndices(doc).takeIf { it.isNotEmpty() }?.let { FormulaBlocks.payload(doc, it) } },
        )
        model = next

        if (fresh || next.selecting != previous?.selecting) {
            leavePicking()
            if (next.selecting) enterPicking(next)
            publishActions()
        }
        readLayer.visibility = if (next.selecting) GONE else VISIBLE
        if (next.selecting) return

        renderRead(next)
        if (signalled) placeView()
    }

    private fun setReplace(payload: FormulaBlocks.Payload?) {
        if (payload == replacePayload) return
        replacePayload = payload
        val replace = payload?.let { { callbacks.onReplaceHandwriting(it) } }
        callbacks.onReplaceChanged(replace)
    }

    private fun setReadLine(title: String?, percent: Int?) {
        readTitle = title
        if (title == sentTitle && percent == readPercent) return
        sentTitle = title
        readPercent = percent
        callbacks.onReadLine(title, percent)
    }

    private fun setReading(answer: String?) {
        if (answer == reading) return
        reading = answer
    }

    private fun renderRead(m: Model) {
        val message = m.message
        readTitle = if (message == null) m.emptyTitle ?: NativeLocale.t("relay_msg_unavailable")
            else "${sourceLabel(message.source)} · ${formatInboxTime(message.updatedAt)}"
        val views = ArrayList<View>()
        val viewWeights = ArrayList<Int>()

        val fromUser = ArrayList<Boolean?>()
        fun add(view: View, weight: Int, user: Boolean?) {
            views += view
            viewWeights += weight
            fromUser += user
        }
        val used = HashSet<String>()
        val lead = m.leadTurn
        when {
            message == null && lead != null -> {
                shown = emptyList()
                add(turnView(lead, true, 1, used), lead.text.length, true)
                add(generating, 0, null)
            }
            message == null -> shown = emptyList()
            else -> {

                val turns = ownTurnsOf(message, m.markers)

                val above = lead?.takeIf { turns?.firstOrNull()?.fromUser != true }

                shown = RelayTurns.merge(turns.orEmpty() + m.chatTurns)
                var round = 0
                if (above != null) {
                    round = 1
                    add(turnView(above, true, round, used), above.text.length, true)
                }
                if (turns == null) {
                    replyDocument.setDocument(document())
                    add(replyBlockOf(above == null), documentText.length, false)
                    round = round.coerceAtLeast(1)
                }
                shown.forEachIndexed { index, turn ->
                    if (turn.fromUser) round++
                    add(turnView(turn, above == null && turns != null && index == 0, round.coerceAtLeast(1), used), turn.text.length, turn.fromUser)
                }
                if (m.chatGenerating && m.chatTurns.lastOrNull()?.fromUser != false) add(generating, 0, null)
                add(endSpace, 0, null)
            }
        }
        turnViews.keys.retainAll(used)

        if (openAtAnswer) {
            val answer = fromUser.indexOfLast { it == false }
            hiddenBefore = when {
                answer <= 0 -> 0
                fromUser[answer - 1] == true -> answer - 1
                else -> answer
            }
        }
        hiddenBefore = hiddenBefore.coerceIn(0, (views.size - 1).coerceAtLeast(0))
        weights = viewWeights.toIntArray()
        views.forEachIndexed { index, view ->
            val visibility = if (index < hiddenBefore) GONE else VISIBLE
            if (view.visibility != visibility) view.visibility = visibility
        }
        val same = content.childCount == views.size && views.indices.all { content.getChildAt(it) === views[it] }
        if (!same) {
            content.removeAllViews()
            views.forEach { content.addView(it) }
        }
        val blank = message == null && lead == null
        empty.visibility = if (blank) VISIBLE else GONE
        scroll.visibility = if (blank) GONE else VISIBLE
        showProgress()
    }

    private fun ownTurnsOf(message: RelayMessage, markers: RelayTurns.Markers): List<ChatTurn>? {
        val key = Triple(message.id, message.text, message.lead)
        if (key != turnsKey || markers !== turnsMarkers) {
            turnsKey = key
            turnsMarkers = markers
            ownTurns = RelayTurns.split(message.id, message.text, markers, message.lead)
                .takeIf { it.size > 1 || it.firstOrNull()?.fromUser == true }
        }
        return ownTurns
    }

    private fun turnView(turn: ChatTurn, first: Boolean, round: Int, used: MutableSet<String>): TurnView {
        var key = turn.id
        while (!used.add(key)) key += "+"
        return turnViews.getOrPut(key) { TurnView(context, baseFont, metaUnits) { if (small) BUBBLE_SHARE_SMALL else BUBBLE_SHARE }}.also { it.bind(turn, first, round) }
    }

    private fun replyBlockOf(first: Boolean): View {
        val block = replyBlock ?: LinearLayout(context).also { column ->
            replyBlock = column
            column.orientation = LinearLayout.VERTICAL
            column.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            column.addView(replyDocument, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        block.setPadding(0, if (first) 0 else turnUnits(context, ANSWER_GAP, metaUnits), 0, 0)
        return block
    }

    private fun placeView() {
        val m = model ?: return
        if (m.selecting) return
        val anchor = revealAnchor
        if (anchor != null) {

            revealAnchor = null
            if (anchor.parent === content && anchor.visibility == VISIBLE) {
                scroll.scrollNow(anchor.top)
                scroll.prevPage()
            }
        } else if (!scroll.touching) {
            val before = scroll.scrollOffset

            val why = when {
                followBottom -> "follow".also { scroll.scrollNow(scroll.maxOffset) }

                openAtAnswer -> "open".also { scroll.scrollNow(shown.lastOrNull { !it.fromUser }?.let { turnViews[it.id]?.top } ?: 0) }
                scrollShare != null -> "resize".also { scroll.scrollNow((scrollShare!! * scroll.maxOffset).roundToInt()) }
                else -> null
            }
            scrollShare = null
            val moved = scroll.scrollOffset - before
            if (why != null && abs(moved) > scroll.viewportHeight / 3) {
                Log.i(TAG, "view $why: $before -> ${scroll.scrollOffset} (max ${scroll.maxOffset}, viewport ${scroll.viewportHeight}, hidden $hiddenBefore)")
            }
        }
        removeCallbacks(updateProgress)
        post(updateProgress)
    }

    private fun showProgress() {
        val m = model ?: return
        if (m.selecting) return
        val max = scroll.maxOffset
        val total = weights.sum()
        var before = 0
        for (index in 0 until hiddenBefore.coerceAtMost(weights.size)) before += weights[index]

        val percent = when {
            before == 0 || total == 0 -> if (max > 0) (scroll.scrollOffset * 100 / max).coerceIn(0, 100) else null
            else -> {
                val share = if (max > 0) scroll.scrollOffset.toFloat() / max else 1f
                ((before + share * (total - before)) * 100 / total).roundToInt().coerceIn(0, 100)
            }
        }
        setReadLine(readTitle, percent)
        val line = scroll.scrollOffset + scroll.viewportHeight / 2
        setReading(shown.lastOrNull { turn ->
            !turn.fromUser && (turnViews[turn.id]?.takeIf { it.visibility == VISIBLE }?.top ?: Int.MAX_VALUE) <= line
        }?.text)
    }

    private fun revealEarlier(): Boolean {
        if (hiddenBefore <= 0) return false
        val anchor = content.getChildAt(hiddenBefore) ?: return false
        val start = (hiddenBefore - REVEAL_STEP).coerceAtLeast(0)
        for (index in start until hiddenBefore) content.getChildAt(index)?.visibility = VISIBLE
        hiddenBefore = start
        revealAnchor = anchor
        followBottom = false
        openAtAnswer = false
        return true
    }

    private fun enterPicking(m: Model) {
        val doc = SelectionDocument.of(conversationTurns(m.message, m.markers, m.leadTurn, m.chatTurns))
        picking = doc
        val view = ContentSelectionPane(
            context, doc.document, doc.turns, reading?.let { doc.blockOfAnswer(it) },
            onToggleRow = { index ->
                selection = MarkdownRangeSelection.toggle(selection, index, rows.size)
                pane?.setSelection(selection)
                publishActions()
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
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun leavePicking() {
        pane?.let(::removeView)
        pane = null
        picking = null
        selection = MarkdownSelectionState()
        rows = emptyList()
    }

    private fun publishActions() {
        val doc = picking?.document
        if (doc == null) {
            callbacks.onSelectionActionsChanged(null)
            return
        }
        val payload = FormulaBlocks.payload(doc, rows, selection.selectedIndices)
        callbacks.onSelectionActionsChanged(DetailSelectActions(
            selectedRowCount = selection.selectedIndices.size,
            canInsert = payload.plainText.isNotBlank(),
            clear = {
                selection = MarkdownSelectionState()
                pane?.setSelection(selection)
                publishActions()
            },
            insert = { callbacks.onSelectionPayload(payload) },
        ))
    }
}

@SuppressLint("ViewConstructor")
internal class DetailReadActions(
    context: Context,
    onSelect: () -> Unit,
    onLasso: () -> Unit,
    onImage: () -> Unit,
    onVoice: () -> Unit,
    onMode: () -> Unit,
) : LinearLayout(context) {
    private var onReplace: (() -> Unit)? = null
    private var hasReply = false

    private fun u(v: Number) = Snux.u(context, v)

    private val lassoItem = RowItem(context, "icons/ic_ai_selection.xml", NativeLocale.t("relay_tool_lasso"), onLasso)
    private val imageItem = RowItem(context, "icons/ic_ai_image.xml", NativeLocale.t("relay_tool_image"), onImage)
    private val micItem = RowItem(context, "icons/ic_ai_mic.xml", NativeLocale.t("relay_voice"), onVoice)
    private val selectItem = RowItem(context, "icons/ic_check_on.xml", NativeLocale.t("relay_select"), onSelect)
    private val modeItem = RowItem(context, "icons/ic_ai_mode_text.xml", NativeLocale.t("relay_tool_mode"), onMode).apply { visibility = View.GONE }
    private val replaceItem = RowItem(context, "icons/ic_bar_insert.xml", NativeLocale.t("relay_replace_handwriting")) { onReplace?.invoke() }

    val micButton: ImageView get() = micItem.icon

    val micCell: View get() = micItem

    val modeButton: ImageView get() = modeItem.icon
    val modeCell: View get() = modeItem

    init {
        orientation = VERTICAL
        setBackgroundColor(PAPER)

        addView(Snux.rule(context, LINE))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(u(28), 0, u(28), 0)
            addView(lassoItem)
            addView(imageItem)
            addView(micItem)
            addView(View(context), LayoutParams(0, 0, 1f))
            addView(replaceItem)
            addView(selectItem)
            addView(modeItem)
        }, LayoutParams(LayoutParams.MATCH_PARENT, u(STATUS_ROW)))
        bind(hasReply = false)
    }

    fun setReplace(replace: (() -> Unit)?) {
        onReplace = replace
        bind(hasReply)
    }

    fun bind(hasReply: Boolean) {
        this.hasReply = hasReply
        replaceItem.visibility = if (onReplace != null) VISIBLE else GONE
        for (item in listOf(selectItem, replaceItem)) item.isEnabled = hasReply
    }
}

@SuppressLint("ViewConstructor")
private class RowItem(context: Context, private val asset: String, label: String, onClick: () -> Unit) : LinearLayout(context) {
    val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER }
    private val name = Snux.text(context, label, 15, INK).apply { isSingleLine = true }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        contentDescription = label
        val size = Snux.u(context, STATUS_ICON)
        addView(icon, LayoutParams(size, size))
        addView(name, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = Snux.u(context, 6) })
        layoutParams = LayoutParams(Snux.u(context, 104), LayoutParams.MATCH_PARENT)
        setOnClickListener { onClick() }
        icon.setImageDrawable(UiUtils.loadAssetIcon(context, asset, size, INK))
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }
}

private fun turnUnits(context: Context, units: Number, metaUnits: Float): Int = Snux.u(context, units.toFloat() * metaUnits / Snux.F_META)

private fun turnAvatar(context: Context, round: Int, metaUnits: Float): TextView =
    Snux.text(context, "%02d".format(round), 16 * metaUnits / Snux.F_META, PAPER).apply {
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER
        includeFontPadding = false
        setBackgroundColor(INK)
        val side = turnUnits(context, AVATAR, metaUnits)
        layoutParams = LinearLayout.LayoutParams(side, side)
    }

private const val AVATAR = 44
private const val QUESTION_GAP = 44
private const val ANSWER_GAP = 24

@SuppressLint("ViewConstructor")
private class ShareColumn(context: Context, private val share: () -> Float) : LinearLayout(context) {
    init {
        orientation = VERTICAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val capped = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) widthMeasureSpec
            else MeasureSpec.makeMeasureSpec((size * share()).roundToInt(), MeasureSpec.AT_MOST)
        super.onMeasure(capped, heightMeasureSpec)
    }
}

@SuppressLint("ViewConstructor")
private class TurnView(
    context: Context,
    private val baseFont: Int,
    private val metaUnits: Float,

    private val bubbleShare: () -> Float,
) : LinearLayout(context) {
    private var turn: ChatTurn? = null
    private var first: Boolean? = null
    private var round = 0
    private var bubble: LinearLayout? = null
    private var answer: MarkdownDocumentLayout? = null
    private var answerText: String? = null
    private val body = LinearLayout(context).apply { orientation = VERTICAL }

    private fun u(v: Number) = Snux.u(context, v)
    private fun s(v: Number) = turnUnits(context, v, metaUnits)

    init {
        orientation = VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    fun bind(next: ChatTurn, first: Boolean, round: Int) {
        val previous = turn
        if (first != this.first || previous?.fromUser != next.fromUser) {
            this.first = first
            setPadding(0, if (first) 0 else s(if (next.fromUser) QUESTION_GAP else ANSWER_GAP), 0, 0)
        }
        if (previous == next && round == this.round) return
        turn = next
        if (previous == null || previous.fromUser != next.fromUser || round != this.round) {
            this.round = round
            removeAllViews()
            (body.parent as? android.view.ViewGroup)?.removeView(body)
            if (next.fromUser) {
                addView(LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    addView(turnAvatar(context, round, metaUnits))
                    addView(body, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = s(16) })
                }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            } else {
                addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
        }
        if (previous != null && previous.fromUser != next.fromUser) {
            body.removeAllViews()
            bubble = null
            answer = null
            answerText = null
        }
        if (next.fromUser) bindBubble(next) else bindAnswer(next)
    }

    private fun bindAnswer(next: ChatTurn) {
        val view = answer ?: MarkdownDocumentLayout(context, baseFont, serifHeads = true).also {
            answer = it
            body.addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        if (next.text != answerText) {
            answerText = next.text
            view.setDocument(MarkdownDocumentParser.parse(next.text))
        }
    }

    private fun bindBubble(next: ChatTurn) {
        bubble?.let(body::removeView)
        val block = ShareColumn(context, bubbleShare).apply {
            background = android.graphics.drawable.GradientDrawable().apply { setStroke(u(1).coerceAtLeast(1), INK) }
            setPadding(s(22), s(18), s(22), s(18))
        }
        next.images.forEach { image -> imageView(image)?.let { block.addView(it) } }
        VoiceTags.split(next.text).forEachIndexed { index, piece ->
            val gap = if (index == 0) 0 else s(14)
            when (piece) {
                is VoiceTags.Piece.Voice -> block.addView(voiceBlock(piece.text), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
                is VoiceTags.Piece.Plain -> if (piece.text.isNotBlank()) {
                    block.addView(bubbleText(piece.text), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
                }
            }
        }
        bubble = block
        body.addView(block, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    private fun bubbleText(text: String): TextView = TextView(context).apply {
        setTextColor(INK)
        breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, baseFont.toFloat())
        lineHeightPx(spPx(context, baseFont * 1.4f))
        this.text = text
    }

    private fun voiceBlock(text: String): View {
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LineIcon(context, LineIcon.Glyph.MIC, 100).apply { color = MUTED }, LayoutParams(u(Snux.F_META), u(Snux.F_META)))
            addView(metaLabel(context, NativeLocale.t("relay_voice_label")).apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, u(metaUnits).toFloat()) }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = u(6) })
        }
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(head)
            addView(bubbleText(text), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = u(6) })
        }
        val line = u(3)
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(View(context).apply { setBackgroundColor(INK) }, LayoutParams(line, LayoutParams.MATCH_PARENT))
            addView(column, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = u(16) - line })
        }
    }

    private fun imageView(image: StagedImage): View? {
        val max = u(160)
        val bitmap = loadStagedImage(image, max) ?: return null
        val edge = u(1).coerceAtLeast(1)
        return ImageView(context).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            maxHeight = max + edge * 2
            scaleType = ImageView.ScaleType.FIT_START
            background = android.graphics.drawable.GradientDrawable().apply { setStroke(edge, INK) }
            setPadding(edge, edge, edge, edge)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = s(14) }
        }
    }
}
