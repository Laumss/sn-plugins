package me.laumss.notipal.relay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Bitmaps
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.PluginIdentity
import me.laumss.notipal.common.Strings
import me.laumss.notipal.host.HostSession
import me.laumss.notipal.host.SystemKeyboard
import me.laumss.notipal.ui_common.Dialogs
import me.laumss.notipal.ui_common.LineIcon
import me.laumss.notipal.ui_common.ScrollHost
import me.laumss.notipal.ui_common.Style
import me.laumss.notipal.ui_common.Widgets
import me.laumss.notipal.ui_common.setLineHeightPx
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.reflect.KProperty

internal const val STATUS_ROW = 75
internal const val STATUS_ICON = 32

private const val TAG = "RelayDetail"

private const val FOLLOW_SLACK_PX = 200

private const val BUBBLE_SHARE = 0.92f
private const val BUBBLE_SHARE_SMALL = 1f

private const val REVEAL_STEP = 2

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

        val onReadLine: (String?, Int?) -> Unit,

        val onImageTap: (StagedImage) -> Unit,
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

    private fun u(v: Number) = Style.u(v)

    private val baseFont = Style.detailFont()
    private val metaUnits: Float = Style.F_META.toFloat()

    private var scrollShare: Float? = null
    private var model: Model? = null

    private val readLayer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var readTitle: String? = null
    private var readPercent: Int? = null
    private var sentTitle: String? = null
    private var small = false
    private var replyBlock: LinearLayout? = null

    private val scroll = ScrollHost(context).apply { pageByLines = true }
    private val content = scroll.content
    private val empty = Widgets.emptyLine(context, Strings.get("relay_no_message")).apply { visibility = GONE }

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
    private var replyDocument = MarkdownView(context, baseFont, serifHeads = true)
    private var turnsKey: Triple<String, String, String>? = null
    private var turnsMarkers: RelayTurns.Markers? = null
    private var ownTurns: List<ChatTurn>? = null
    private val turnViews = HashMap<String, TurnView>()
    private var shown: List<ChatTurn> = emptyList()
    private val generating = Widgets.text(context, Strings.get("relay_chat_generating"), Style.F_META, Style.hint).apply {
        setPadding(0, u(20), 0, 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    private val endSpace = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, u(24))
    }

    private var followBottom = true
    private var openAtAnswer = true

    private var hiddenBefore = 0
    private var weights = IntArray(0)

    private var revealAnchor: View? = null
    private var seenSignal = 0
    private var viewport = 0
    private var reading: String? = null
    private val updateProgress = Runnable { showProgress() }

    init {
        val inset = u(Style.PANE_INSET)
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

    val readingAnswer: String? get() = reading

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
        RelayHooks.openTrace("detail margins small=$small, ${content.childCount} views kept")
    }

    private fun placeContent() {

        if (small) content.setPadding(u(22), u(14), u(22), u(12)) else content.setPadding(u(36), u(32), maxOf(u(60), scroll.followLaneWidthPx), u(28))
        scroll.follow = !small
        generating.setPadding(0, u(20), 0, 0)
    }

    fun clear() {
        if (model == null) return
        model = null
        setReading(null)
        resetReading()
        viewport = 0
        setReadLine(null, null)
    }

    private fun resetReading() {
        scrollShare = null
        turnViews.clear()
        content.removeAllViews()
        replyDocument = MarkdownView(context, baseFont, serifHeads = true)
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
        model = next

        readLayer.visibility = if (next.selecting) GONE else VISIBLE
        if (next.selecting) return

        renderRead(next)
        if (signalled) placeView()
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
        readTitle = if (message == null) m.emptyTitle ?: Strings.get("relay_msg_unavailable")
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
        return turnViews.getOrPut(key) {
            TurnView(context, baseFont, metaUnits, { if (small) BUBBLE_SHARE_SMALL else BUBBLE_SHARE }, callbacks.onImageTap)
        }.also { it.bind(turn, first, round) }
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

                if (!scroll.follow) scroll.prevPage()
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
}

@SuppressLint("ViewConstructor")
internal class DetailReadActions(
    context: Context,
    onSelect: () -> Unit,
    onLasso: () -> Unit,
    onImage: () -> Unit,
    pagesLabel: String,
    onPages: () -> Unit,
    onVoice: () -> Unit,
    onSettings: () -> Unit,
) : LinearLayout(context) {
    private var hasReply = false

    private fun u(v: Number) = Style.u(v)

    private val lassoItem = assetItem(context, "icons/ic_ai_selection.xml", Strings.get("relay_tool_lasso"), onLasso)
    private val imageItem = assetItem(context, "icons/ic_ai_image.xml", Strings.get("relay_tool_image"), onImage)
    private val pagesItem = assetItem(context, "icons/ic_ai_document.xml", pagesLabel, onPages)
    private val micItem = assetItem(context, "icons/ic_ai_mic.xml", Strings.get("relay_voice"), onVoice)
    private val selectItem = assetItem(context, "icons/ic_check_on.xml", Strings.get("relay_select"), onSelect)
    private val settingsItem = RowItem(context, LineIcon(context, LineIcon.Glyph.GEAR, STATUS_ICON).apply { color = Style.ink },
        Strings.get("relay_nav_settings"), onSettings)

    val micButton: ImageView get() = micItem.icon

    val micCell: View get() = micItem

    fun setPagesLabel(label: String) = pagesItem.setLabel(label)

    init {
        orientation = VERTICAL
        setBackgroundColor(Style.paper)

        addView(Widgets.rule(context, Style.line))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(u(28), 0, u(28), 0)
            addView(lassoItem)
            addView(imageItem)
            addView(pagesItem)
            addView(micItem)
            addView(View(context), LayoutParams(0, 0, 1f))
            addView(selectItem)
            addView(settingsItem)
        }, LayoutParams(LayoutParams.MATCH_PARENT, u(STATUS_ROW)))
        bind(hasReply = false)
    }

    fun bind(hasReply: Boolean) {
        this.hasReply = hasReply
        for (item in listOf(selectItem)) item.isEnabled = hasReply
    }
}

@SuppressLint("ViewConstructor")
private class RowItem<V : View>(context: Context, val icon: V, label: String, onClick: () -> Unit) : LinearLayout(context) {
    private val name = Widgets.text(context, label, 15, Style.ink).apply { isSingleLine = true }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        contentDescription = label
        val size = Style.u(STATUS_ICON)
        addView(icon, LayoutParams(size, size))
        addView(name, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = Style.u(6) })
        layoutParams = LayoutParams(Style.u(104), LayoutParams.MATCH_PARENT)
        setOnClickListener { onClick() }
    }

    fun setLabel(label: String) {
        if (name.text.toString() == label) return
        name.text = label
        contentDescription = label
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else Style.DISABLED_ALPHA
    }
}

private fun assetItem(context: Context, asset: String, label: String, onClick: () -> Unit): RowItem<ImageView> {
    val icon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER
        setImageDrawable(Style.iconPx(asset, Style.u(STATUS_ICON), Style.ink))
    }
    return RowItem(context, icon, label, onClick)
}

private fun turnUnits(context: Context, units: Number, metaUnits: Float): Int = Style.u(units.toFloat() * metaUnits / Style.F_META)

private fun turnAvatar(context: Context, round: Int, metaUnits: Float): TextView =
    Widgets.text(context, "%02d".format(round), 16 * metaUnits / Style.F_META, Style.paper).apply {
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER
        includeFontPadding = false
        setBackgroundColor(Style.ink)
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
    private val onImageTap: (StagedImage) -> Unit,
) : LinearLayout(context) {
    private var turn: ChatTurn? = null
    private var first: Boolean? = null
    private var round = 0
    private var bubble: LinearLayout? = null
    private var answer: MarkdownView? = null
    private var answerText: String? = null
    private val body = LinearLayout(context).apply { orientation = VERTICAL }

    private fun u(v: Number) = Style.u(v)
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
        val view = answer ?: MarkdownView(context, baseFont, serifHeads = true).also {
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
            background = Style.boxPx(Style.clear, Style.ink, u(1).coerceAtLeast(1))
            setPadding(s(22), s(18), s(22), s(18))
        }
        next.images.forEach { image ->

            val view = if (image.isDocument) bubbleText(Strings.get("relay_doc_alt", image.fileName)).apply {
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = s(14) }
            } else imageView(image)
            view?.let { block.addView(it) }
        }
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
        setTextColor(Style.ink)
        breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, baseFont.toFloat())
        setLineHeightPx(Style.rawSp(baseFont * 1.4f))
        this.text = text
    }

    private fun voiceBlock(text: String): View {
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LineIcon(context, LineIcon.Glyph.MIC, 100).apply { color = Style.hint }, LayoutParams(u(Style.F_META), u(Style.F_META)))
            addView(Widgets.metaLabel(context, Strings.get("relay_voice_label")).apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, u(metaUnits).toFloat()) }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = u(6) })
        }
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(head)
            addView(bubbleText(text), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = u(6) })
        }
        val line = u(3)
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(View(context).apply { setBackgroundColor(Style.ink) }, LayoutParams(line, LayoutParams.MATCH_PARENT))
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
            background = Style.boxPx(Style.clear, Style.ink, edge)
            setPadding(edge, edge, edge, edge)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = s(14) }
            setOnClickListener { onImageTap(image) }
        }
    }
}

internal data class VoiceChip(val id: String, val title: String, val detail: String)

internal class AttachmentStrip(context: Context, private val onRemove: (StagedImage) -> Unit) : HorizontalScrollView(context) {

    var onRemoveVoice: ((String) -> Unit)? = null

    private fun u(v: Number) = Style.u(v)
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(u(Style.PANE_INSET), u(10), u(Style.PANE_INSET), 0)
    }
    private val thumbs = HashMap<StagedImage, Bitmap?>()
    private val linePaint = Paint().apply { color = Style.ink }

    init {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(Style.paper)
        setWillNotDraw(false)
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(scrollX.toFloat(), 0f, scrollX + width.toFloat(), Style.inkPx(2).toFloat().coerceAtLeast(1f), linePaint)
    }

    fun setItems(items: List<StagedImage>, voices: List<VoiceChip> = emptyList()) {
        thumbs.keys.retainAll(items.filterNot { it.isDocument }.toSet())
        row.removeAllViews()
        voices.forEach { voice ->
            row.addView(labelChip(LineIcon(context, LineIcon.Glyph.MIC, 100), voice.title, voice.detail) { onRemoveVoice?.invoke(voice.id) })
        }
        items.forEach { item ->
            val view = if (item.isDocument) labelChip(documentIcon(), item.fileName, item.detail, onClose = { onRemove(item) }) else chip(item)
            row.addView(view)
        }
        visibility = if (items.isEmpty() && voices.isEmpty()) GONE else VISIBLE
    }

    private fun documentIcon(): View = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER
        setImageDrawable(Style.iconPx("icons/ic_ai_document.xml", u(28), Style.ink))
    }

    private fun labelChip(icon: View, title: String, detail: String, onClose: () -> Unit): FrameLayout {
        val height = u(THUMB_HEIGHT_U)
        val close = u(30)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Style.boxPx(Style.paper, Style.ink, u(Style.OUTLINE_U).coerceAtLeast(1))
            setPadding(u(10), u(6), u(14), u(6))
            addView(icon, LinearLayout.LayoutParams(u(28), u(28)).apply { rightMargin = u(8) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title
                    setTextColor(Style.ink)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, u(Style.F_META).toFloat())
                    typeface = Typeface.DEFAULT_BOLD
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                    maxWidth = u(260)
                })
                addView(TextView(context).apply {
                    text = detail
                    setTextColor(Style.muted)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, u(Style.F_META).toFloat())
                    isSingleLine = true
                })
            })
        }
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, height + close / 2).apply { rightMargin = u(10) }
            addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, height, Gravity.BOTTOM or Gravity.START).apply {
                marginEnd = close / 2
            })
            addView(TextView(context).apply {
                text = "×"
                setTextColor(Style.paper)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, u(Style.F_META).toFloat())
                gravity = Gravity.CENTER
                background = Style.oval(Style.ink)
                setOnClickListener { onClose() }
            }, FrameLayout.LayoutParams(close, close, Gravity.TOP or Gravity.END))
        }
    }

    private fun chip(item: StagedImage): FrameLayout {
        val height = u(THUMB_HEIGHT_U)
        val bitmap = thumbs.getOrPut(item) { loadThumb(item, height) }
        val width = bitmap?.let { (height * it.width / it.height.toFloat()).roundToInt().coerceIn(height / 2, height * 3) } ?: height
        val close = u(30)
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(width + close / 2, height + close / 2).apply { rightMargin = u(10) }
            addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(bitmap)
                background = Style.boxPx(Style.paper, Style.ink, u(Style.OUTLINE_U).coerceAtLeast(1))
                setPadding(u(1), u(1), u(1), u(1))
            }, FrameLayout.LayoutParams(width, height, Gravity.BOTTOM or Gravity.START))
            addView(TextView(context).apply {
                text = "×"
                setTextColor(Style.paper)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, u(Style.F_META).toFloat())
                gravity = Gravity.CENTER
                background = Style.oval(Style.ink)
                setOnClickListener { onRemove(item) }
            }, FrameLayout.LayoutParams(close, close, Gravity.TOP or Gravity.END))
        }
    }

    private fun loadThumb(item: StagedImage, height: Int): Bitmap? = loadStagedImage(item, height)

    private companion object {
        const val THUMB_HEIGHT_U = 64
    }
}

internal fun loadStagedImage(item: StagedImage, height: Int): Bitmap? =
    if (item.isDocument) null else PluginIdentity.asNotipal { decodeStagedImage(item, height) }

private fun decodeStagedImage(item: StagedImage, height: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(item.imagePath, bounds)
    val box = maskBox(item.maskPath, bounds.outWidth, bounds.outHeight)
    val sample = Bitmaps.powerOfTwoSampleSize(box[2], box[3], 1, height)
    val source = BitmapFactory.decodeFile(item.imagePath, BitmapFactory.Options().apply { inSampleSize = sample })
    source?.let {
        val x = (box[0] / sample).coerceIn(0, it.width - 1)
        val y = (box[1] / sample).coerceIn(0, it.height - 1)
        val w = (box[2] / sample).coerceIn(1, it.width - x)
        val h = (box[3] / sample).coerceIn(1, it.height - y)
        if (x == 0 && y == 0 && w == it.width && h == it.height) it
        else Bitmap.createBitmap(it, x, y, w, h).also { _ -> it.recycle() }
    }
} catch (_: Exception) { null }

private fun maskBox(maskPath: String, imageW: Int, imageH: Int): IntArray {
    val whole = intArrayOf(0, 0, imageW.coerceAtLeast(1), imageH.coerceAtLeast(1))
    if (maskPath.isBlank()) return whole
    val box = runCatching { JSONObject(File(maskPath).readText()).optJSONObject("boundingBox") }.getOrNull() ?: return whole
    val w = box.optInt("w")
    val h = box.optInt("h")
    return if (w > 0 && h > 0) intArrayOf(box.optInt("x"), box.optInt("y"), w, h) else whole
}

class ChatInput(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private fun u(v: Number) = Style.u(v)
    private val imm get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    private val caretPaint = Paint()

    var onSend: (String) -> Unit = {}

    var onStop: () -> Unit = {}

    var onImeEdit: ((String) -> Unit)? = null

    var canSendEmpty: () -> Boolean = { false }

    val input: EditText = object : EditText(context) {
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            val connection = super.onCreateInputConnection(outAttrs) ?: return null
            return object : InputConnectionWrapper(connection, false) {
                private fun edited(what: String, done: Boolean) = done.also { onImeEdit?.invoke(what) }
                override fun commitText(text: CharSequence?, newCursorPosition: Int) =
                    edited("commit", super.commitText(text, newCursorPosition))
                override fun setComposingText(text: CharSequence?, newCursorPosition: Int) =
                    edited("compose", super.setComposingText(text, newCursorPosition))
                override fun finishComposingText() = edited("finish", super.finishComposingText())
                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) =
                    edited("delete", super.deleteSurroundingText(beforeLength, afterLength))
                override fun sendKeyEvent(event: KeyEvent?): Boolean {
                    val done = super.sendKeyEvent(event)
                    return if (event?.action == KeyEvent.ACTION_UP) edited("key", done) else done
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val layout = layout ?: return
            val offset = selectionStart
            if (!isFocused || offset < 0 || offset != selectionEnd) return
            val line = layout.getLineForOffset(offset)
            val metrics = paint.fontMetrics

            val box = height - extendedPaddingTop - extendedPaddingBottom
            val centred = gravity and Gravity.VERTICAL_GRAVITY_MASK == Gravity.CENTER_VERTICAL
            val textTop = extendedPaddingTop + if (centred) ((box - layout.height) / 2).coerceAtLeast(0) else 0
            val baseline = textTop + layout.getLineBaseline(line).toFloat()
            val x = compoundPaddingLeft + layout.getPrimaryHorizontal(offset)
            caretPaint.color = currentTextColor
            canvas.drawRect(x, baseline + metrics.ascent, x + maxOf(2f, 1.5f * density), baseline + metrics.descent, caretPaint)
        }
    }.apply {
        isCursorVisible = false
        hint = Strings.get("relay_chat_hint")
        setTextColor(Style.ink)
        setHintTextColor(Style.hint)
        highlightColor = Style.highlight
        setTextSize(TypedValue.COMPLEX_UNIT_PX, u(TEXT_SIZE).toFloat())
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        minLines = 1
        maxLines = 4
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        background = Style.boxPx(Style.paper, Style.ink, u(OUTLINE).coerceAtLeast(1))
        setPadding(u(TEXT_START), u(12), u(12), u(12))
    }

    private val action = TextView(context).apply {
        setTextColor(Style.paper)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, u(TEXT_SIZE).toFloat())
        gravity = Gravity.CENTER
        isSingleLine = true
    }

    private val sendIcon = LineIcon(context, LineIcon.Glyph.SEND, SEND_ICON)

    private val actionFill = Style.boxPx(Style.paper)

    private val actionCell = FrameLayout(context).apply {
        background = actionFill
        addView(action, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(sendIcon, FrameLayout.LayoutParams(u(SEND_ICON), u(SEND_ICON), Gravity.CENTER))

        isFocusable = true
        isFocusableInTouchMode = false
        setOnClickListener {
            val text = input.text.toString().trim()
            if (generating) onStop() else if (!sending && (text.isNotEmpty() || canSendEmpty())) onSend(text)
        }
    }

    private var drawn: Int? = null

    var generating = false
        set(value) { field = value; refresh() }

    var sending = false
        set(value) { field = value; refresh() }

    init {
        orientation = HORIZONTAL

        gravity = Gravity.BOTTOM
        setBackgroundColor(Style.paper)
        addView(input, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(actionCell)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        measures(small = false)
        refresh()
    }

    fun refreshSend() = refresh()

    private fun refresh() {
        val ready = input.text.isNotBlank() || canSendEmpty()
        val state = when {
            generating -> 0
            sending -> 1
            ready -> 2
            else -> 3
        }
        if (state == drawn) return
        drawn = state
        action.text = if (state == 0) Strings.get("relay_chat_stop") else ""
        sendIcon.visibility = if (state == 0) GONE else VISIBLE
        sendIcon.color = if (state == 3) Style.sendIdle else Style.paper
        actionFill.setColor(if (state == 3) Style.paper else Style.ink)
        actionFill.setStroke(u(OUTLINE).coerceAtLeast(1), Style.ink)
        actionCell.alpha = if (state == 1) Style.DISABLED_ALPHA else 1f
    }

    private fun measures(small: Boolean) {
        val height = u(if (small) 60 else 72)
        input.minHeight = height
        val outline = u(OUTLINE).coerceAtLeast(1)
        actionCell.layoutParams = LayoutParams(u(if (small) 76 else 96), height).apply { leftMargin = -outline }
        val side = u(if (small) 18 else 40)
        val edge = u(if (small) 10 else 16)
        setPadding(side, edge, side, edge)
    }

    fun clearText() = input.setText("")

    fun focusAt(offset: Int) {
        input.requestFocus()
        if (offset in 0..input.length()) input.setSelection(offset)
        imm?.showSoftInput(input, 0)
    }

    fun hideKeyboard(): Boolean {
        if (!input.isFocused) return false
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        return true
    }

    fun setSmall(small: Boolean) {

        val px = u(TEXT_SIZE).toFloat()
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        action.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        measures(small)
    }

    companion object {

        private const val OUTLINE = 2f
        private const val SEND_ICON = 32
        private const val TEXT_START = 18.63f
        private const val TEXT_SIZE = 21.11f

        class Keyboard(val keys: Int, val strip: Int, val shown: Boolean, val how: String)

        fun keyboard(root: View, screenHeight: Int): Keyboard {
            val density = root.resources.displayMetrics.density
            val resizedBy = (screenHeight - root.height).coerceAtLeast(0)
            val ime = if (Build.VERSION.SDK_INT >= 30) {
                root.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
            } else 0
            val visible = Rect().also(root::getWindowVisibleDisplayFrame)
            val location = IntArray(2).also(root::getLocationOnScreen)
            val rootBottom = location[1] + root.height
            val frame = (rootBottom - visible.bottom).coerceIn(0, root.height)
            val visibleHeight = SystemKeyboard.imeVisibleHeight(root.context)
            val window = (visibleHeight - (screenHeight - rootBottom)).coerceIn(0, root.height)

            val keys = maxOf(ime, frame).takeIf { it > 24 * density } ?: window
            val strip = (window - keys).coerceAtLeast(0)
            val shown = resizedBy + keys + strip > 120 * density
            return Keyboard(keys, strip, shown, "ime=$ime frame=$frame visible=$visibleHeight resizedBy=$resizedBy")
        }
    }
}

class DetailPane(private val host: RelayHost) {
    companion object {
        private const val TAG = "RelayDetailPane"

        private const val SLOW_RENDER_MS = 60L

        private const val QUESTIONS_KEPT = 50
        private val questions = LinkedHashMap<String, ChatTurn>()

        fun question(replyId: String): ChatTurn? = questions[replyId]
    }

    private enum class Page { OFF, READ, SELECT }

    private val ctx: Context = App.context
    private val handler = Main.handler

    private inner class Shown<T>(initial: T) {
        var value: T = initial
            set(next) {
                if (field != next) {
                    field = next
                    scheduleContent()
                }
            }

        operator fun getValue(owner: Any?, property: KProperty<*>): T = value

        operator fun setValue(owner: Any?, property: KProperty<*>, next: T) {
            value = next
        }
    }

    private var page by Shown(Page.OFF)
    private var requestedDetailId by Shown<String?>(null)
    private val messagesState = Shown<List<RelayMessage>>(emptyList())

    private var live = false

    private var session = 0
    private var contentQueued = false

    private val renderQueued = Choreographer.FrameCallback {
        if (rendersHeld()) {

            contentQueued = false
            if (!renderHeld) Log.i(TAG, "render held: finger on the reply or dragging the window")
            renderHeld = true
        } else renderContent()
    }

    private var renderHeld = false

    private var composing by Shown(false)

    private var newChatTapped by Shown(false)

    private var awaitingReplyAfter: Set<String>? = null

    private var pendingQuestion by Shown<ChatTurn?>(null)

    private var waitingHidden by Shown(false)

    private var snapshotLead by Shown<ChatTurn?>(null)

    private var chat: ConversationStream? = null
    private var chatConversationId: String? = null
    private var chatAnchor: Int? = null

    private var chatKept: List<ChatTurn> = emptyList()
    private var chatLiveFrom: Int? = null
    private var chatDetailId: String? = null
    private var lastSnapshot: ConversationStream.Snapshot? = null
    private var chatSettings: ConversationStream.RelaySettings? = null

    private val chatMarkers = Shown(RelayTurns.Markers(RelayTurns.DEFAULT_MARKERS))

    private var chatBaseId: String? = null
    private val chatTurns = Shown<List<ChatTurn>>(emptyList())
    private val chatGenerating = Shown(false)
    private val followSignal = Shown(0)

    private val attachments = mutableListOf<StagedImage>()

    private var nameKey: String? = null
    private var nameShown = ""

    var dragging = false
        set(value) {
            field = value
            if (!value) releaseRender()
        }

    private val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    private val contentHost = FrameLayout(ctx)
    private var recentView: RecentConversations? = null
    private var detailView: MessageDetailContent? = null
    private val attachmentStrip = AttachmentStrip(ctx) { item ->
        attachments.remove(item)

        if (item.isDocument) PluginIdentity.asNotipal { File(item.imagePath).parentFile?.deleteRecursively() }
        refreshAttachments()
    }

    val input = ChatInput(ctx).apply { visibility = View.GONE }

    internal val actions = DetailReadActions(
        ctx,
        onSelect = { host.open(RelayPage.SELECT) },
        onLasso = { host.pageAction("lasso_ai") },
        onImage = { host.pageAction("screenshot_ai") },
        pagesLabel = pagesLabel(),
        onPages = { host.attachPages() },
        onVoice = { host.voice.toggle() },
        onSettings = { host.open(RelayPage.SETTINGS) },
    ).apply { visibility = View.GONE }

    val view: View get() = root

    init {
        root.addView(contentHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        attachmentStrip.onRemoveVoice = { id -> host.voice.drop(id) }
        root.addView(attachmentStrip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        input.onSend = ::sendChat
        input.onStop = ::stopChat
        input.canSendEmpty = { attachments.isNotEmpty() || host.voice.staged.isNotEmpty() }
        root.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    val cardId: String? get() = requestedDetailId

    val message: RelayMessage? get() = detailMessage()

    val atStart: Boolean get() = page == Page.READ && requestedDetailId == null

    val atHome: Boolean get() = atStart && composing && (pendingQuestion == null || waitingHidden)

    val newChatPending: Boolean get() = newChatTapped

    enum class Opened {

        SAME,

        FROM_START,

        OTHER,
    }

    fun show(id: String, keepKeyboard: Boolean = false): Opened {
        val opened = when {
            id == requestedDetailId -> Opened.SAME
            atStart -> Opened.FROM_START
            else -> Opened.OTHER
        }
        go(Page.READ, id, keepKeyboard)
        return opened
    }

    fun compose() {
        composing = true
        awaitingReplyAfter = null
        go(Page.READ, null)
    }

    fun start() {
        composing = true
        go(Page.READ, null)
    }

    fun newChat() {
        newChatTapped = true
        waitingHidden = true
        start()
    }

    fun read() = go(Page.READ, requestedDetailId)

    fun select() = go(Page.SELECT, requestedDetailId)

    fun leave() = go(Page.OFF, null)

    private fun go(to: Page, detailId: String?, keepKeyboard: Boolean = false) {
        closeImage()

        if (to != Page.READ || detailId != null) {
            composing = false
            newChatTapped = false
        }
        page = to
        requestedDetailId = detailId
        syncChat(keepKeyboard)
        markShownSeen()
    }

    fun redraw() {
        if (!live) {
            live = true
            RelayTranslucency.load()
            messagesState.value = Conversations.inbox.snapshot()
            actions.setPagesLabel(pagesLabel())
        }
        syncChat()
        applyLook()
        renderContent()
    }

    fun beforeHandover() {
        closeImage()
        input.hideKeyboard()
    }

    fun focusInput(offset: Int) {
        if (!host.small) return
        input.focusAt(offset)
    }

    fun clear() {
        closeImage()
        live = false
        session++
        page = Page.OFF
        requestedDetailId = null
        composing = false
        newChatTapped = false
        awaitingReplyAfter = null
        pendingQuestion = null
        waitingHidden = false
        snapshotLead = null
        attachments.clear()
        attachmentStrip.setItems(emptyList())
        chat?.stop()
        chat = null
        chatConversationId = null
        chatDetailId = null
        lastSnapshot = null
        chatSettings = null
        chatBaseId = null
        input.hideKeyboard()
        input.clearText()
        input.sending = false
        input.generating = false
        input.visibility = View.GONE
        actions.visibility = View.GONE
        Choreographer.getInstance().removeFrameCallback(renderQueued)
        contentQueued = false
        renderHeld = false
        dragging = false
        detailView?.clear()
        nameKey = null
    }

    fun inboxChanged(messages: List<RelayMessage>): String? {
        messagesState.value = messages
        val sentWith = awaitingReplyAfter ?: return null
        val reply = messagesState.value.firstOrNull { it.source == "llm" && it.id !in sentWith } ?: return null
        awaitingReplyAfter = null
        takeAwaitedReply(reply.id)
        return reply.id
    }

    val cardGone: Boolean
        get() = requestedDetailId != null && messagesState.value.none { it.id == requestedDetailId }

    fun inboxSettled() {

        markShownSeen()
        syncChat()
        host.detailChanged()
    }

    fun markShownSeen() {
        if (host.collapsed || page != Page.READ) return
        val shown = detailMessage() ?: return
        val ids = messagesState.value.filter { card ->
            !card.seen && (card.id == shown.id || (shown.conversationId.isNotEmpty() && card.conversationId == shown.conversationId))
        }.map { it.id }
        if (ids.isNotEmpty()) Conversations.markSeen(ids)
    }

    fun backendChanged() = syncChat()

    fun pause() {
        chat?.pause()
    }

    fun resume() {
        chat?.resume()
    }

    fun replyFailed(waiting: Boolean) {
        val question = pendingQuestion
        if (waiting && question != null && awaitingReplyAfter != null) {
            awaitingReplyAfter = null
            pendingQuestion = null
            waitingHidden = false
            input.input.let { edit ->
                if (edit.text.isNullOrBlank()) {
                    edit.setText(question.text)
                    edit.setSelection(question.text.length)
                }
            }
            question.images.filterTo(attachments) { it !in attachments }
            refreshAttachments()
        }
    }

    fun selectionTurns(): List<ChatTurn> =
        conversationTurns(detailMessage(), chatMarkers.value, leadTurn(), chatTurns.value)

    val reading: String? get() = detailView?.readingAnswer

    fun stageText(text: String) {
        host.reveal("text staged")
        val edit = input.input
        val current = edit.text?.toString().orEmpty().trimEnd()
        val joined = if (current.isEmpty()) text else "$current\n$text"
        edit.setText(joined)
        edit.setSelection(joined.length)
        Log.i(TAG, "staged text ${text.length} chars")
    }

    fun stageImage(imagePath: String, maskPath: String) {
        host.reveal("image staged")
        attachments += StagedImage(imagePath, maskPath)
        refreshAttachments()
        Log.i(TAG, "staged image $imagePath (${attachments.size} waiting)")
    }

    fun stagePages(files: List<StagedImage>) {
        if (files.isEmpty()) return
        host.reveal("pages staged")
        attachments += files
        refreshAttachments()
        Log.i(TAG, "staged ${files.size} file(s), first ${File(files.first().imagePath).name} (${attachments.size} waiting)")
    }

    fun refreshAttachments() {
        input.refreshSend()
        attachmentStrip.setItems(attachments.toList(), host.voice.staged.map { id ->
            val (title, detail) = host.voice.chip(id)
            VoiceChip(id, title, detail)
        })
        syncChat()
    }

    private var imageShown: View? = null
    private var imageFromSmall = false

    private fun showImage(image: StagedImage) {
        if (host.collapsed) return
        val fromSmall = host.small
        if (fromSmall) host.growForImage()
        closeImage()
        val (screenW, screenH) = Style.screenSize()
        val bitmap = loadStagedImage(image, maxOf(screenW, screenH)) ?: run {
            Log.w(TAG, "image ${image.imagePath} could not be read")
            return
        }
        fun u(v: Number) = Style.u(v)
        val viewer = FrameLayout(ctx).apply {
            setBackgroundColor(Style.paper)
            setOnClickListener { closeImage(back = true) }
            addView(ImageView(ctx).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                setMargins(u(24), u(56), u(24), u(24))
            })
            addView(Widgets.text(ctx, Strings.get("relay_image_close"), Style.F_META, Style.subtle).apply {
                setPadding(u(24), u(16), u(24), 0)
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        }
        contentHost.addView(viewer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        imageShown = viewer
        imageFromSmall = fromSmall
        Log.i(TAG, "image opened ${bitmap.width}x${bitmap.height} fromMini=$fromSmall")
    }

    fun closeImage(back: Boolean = false): Boolean {
        val viewer = imageShown ?: return false
        imageShown = null
        (viewer.parent as? ViewGroup)?.removeView(viewer)
        val toSmall = back && imageFromSmall && !host.small
        imageFromSmall = false
        if (toSmall) host.backToSmall()
        return true
    }

    fun applyLook() {
        val small = host.small

        val paper = if (small) RelayTranslucency.paperArgbValue else Style.paper
        recentView?.setBackgroundColor(paper)
        detailView?.setPaper(paper)
        recentView?.setSmall(small)
        detailView?.setSmall(small)
        input.setSmall(small)
    }

    fun title(): String {
        val message = detailMessage()
        if (message == null || atHome) {
            if (composing && newChatTapped) return Strings.get("relay_new_chat")
            val waiting = pendingQuestion?.text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
            return waiting?.takeIf { !atHome } ?: "AIRelay"
        }
        lastSnapshot?.takeIf { it.conversationId == message.conversationId }?.title?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        val first = conversationCards(message, messagesState.value).minByOrNull { it.createdAt } ?: message
        val key = "${first.id}@${first.updatedAt}@${questions[first.id]?.text}"
        if (key != nameKey) {
            nameKey = key
            nameShown = rowTitle(questions[first.id]?.text, summaryLines(first.text).firstOrNull() ?: first.text.trim())
        }
        return nameShown
    }

    private fun detailMessage(): RelayMessage? = messagesState.value.firstOrNull { it.id == requestedDetailId }

    private fun syncChat(keepKeyboard: Boolean = false) {
        val detail = page == Page.READ || page == Page.SELECT
        val conversationId = if (live && detail) {
            messagesState.value.firstOrNull { it.id == requestedDetailId }?.takeIf(Conversations::ownsCard)?.conversationId?.ifEmpty { null }
        } else null
        if (conversationId != chatConversationId || (conversationId != null && chat == null)) {
            chat?.stop()
            chat = null
            chatAnchor = null
            chatKept = emptyList()
            chatLiveFrom = null
            lastSnapshot = null
            chatSettings = null
            chatMarkers.value = RelayTurns.Markers(RelayTurns.DEFAULT_MARKERS)
            chatBaseId = null
            chatTurns.value = inboxTurnsAfter(requestedDetailId)
            chatGenerating.value = false
            snapshotLead = null

            input.apply {
                if (!keepKeyboard) hideKeyboard()
                sending = false
                generating = false
            }
            chat = conversationId?.let(Conversations::openConversation)?.also { stream ->
                stream.onSnapshot = { snapshot -> handler.post { if (chat === stream) applySnapshot(snapshot) } }
                stream.onReply = Conversations::offerReply
                thread(name = "Relay-chat-settings", isDaemon = true) {
                    val settings = stream.fetchRelaySettings() ?: return@thread
                    handler.post { if (chat === stream) applyRelaySettings(settings) }
                }
            }
            chatConversationId = conversationId.takeIf { chat != null }
        }
        if (requestedDetailId != chatDetailId) {

            chatDetailId = requestedDetailId
            chatAnchor = null
            chatKept = emptyList()
            chatLiveFrom = null
            val snapshot = lastSnapshot
            if (snapshot != null) applySnapshot(snapshot) else chatTurns.value = inboxTurnsAfter(requestedDetailId)
        }
        val small = host.small

        val voices = host.voice.staged
        val showBar = page == Page.READ && (chat != null || small || composing || attachments.isNotEmpty() || voices.isNotEmpty())

        if (!showBar) input.hideKeyboard()
        input.visibility = if (showBar) View.VISIBLE else View.GONE
        attachmentStrip.visibility = if (showBar && (attachments.isNotEmpty() || voices.isNotEmpty())) View.VISIBLE else View.GONE
        actions.visibility = if (page == Page.READ && !small) View.VISIBLE else View.GONE

        val hint = Strings.get(when {
            !composing -> "relay_chat_hint"
            newChatTapped -> "relay_new_chat_hint"
            else -> "relay_current_chat_hint"
        })
        input.input.let { edit -> if (edit.hint?.toString() != hint) edit.hint = hint }
        host.voice.refresh()
    }

    private fun inboxTurnsAfter(id: String?): List<ChatTurn> {
        val shown = messagesState.value.firstOrNull { it.id == id } ?: return emptyList()
        if (shown.conversationId.isEmpty()) return emptyList()
        return messagesState.value
            .filter { it.conversationId == shown.conversationId && it.createdAt > shown.createdAt }
            .sortedBy { it.createdAt }
            .flatMap { reply -> listOfNotNull(questions[reply.id]) + RelayTurns.split(reply.id, reply.text, chatMarkers.value, lead = reply.lead) }
    }

    private fun applySnapshot(snapshot: ConversationStream.Snapshot) {
        val previous = lastSnapshot?.takeIf { it.conversationId == snapshot.conversationId }
        lastSnapshot = snapshot
        val nodes = snapshot.nodes
        Conversations.noteChatMessages(snapshot.conversationId, nodes.flatMap { node -> node.messages.map { it.id } })
        val found = nodes.indexOfFirst { node -> node.messages.any { it.id == requestedDetailId } }

        val anchor = if (found >= 0) found else chatAnchor ?: nodes.lastIndex
        chatAnchor = anchor
        previous?.let { rewrittenFrom(it.nodes, nodes) }?.takeIf { it >= anchor - 1 }?.let { from ->

            val liveFrom = minOf(from, chatLiveFrom ?: from)
            val current = nodes.drop(liveFrom).mapNotNullTo(HashSet()) { it.current?.id }
            chatKept = chatTurns.value.filter { it.id.substringBefore('#') !in current }
            chatLiveFrom = liveFrom
            Log.i(TAG, "chat rewritten on the phone from node $from: ${chatKept.size} turns kept, built again from node $liveFrom")
        }
        val liveFrom = chatLiveFrom

        if (liveFrom == null) {
            snapshotLead = nodes.getOrNull(found - 1)?.takeIf { found > 0 }?.current
                ?.takeIf { it.role == "USER" }
                ?.let(::userTurn)
        }
        val turns = chatKept.toMutableList()
        val keptIds = chatKept.mapTo(HashSet()) { it.id.substringBefore('#') }
        var baseInNode: String? = null

        val first = liveFrom ?: if (found >= 0) found else anchor + 1
        for (i in first until nodes.size) {
            val node = nodes[i]
            val message = node.current ?: continue
            if (message.id in keptIds) continue
            if (i == found) {
                val detail = node.messages.first { it.id == requestedDetailId }

                val head = detail.text.trim()
                val grown = message.takeIf {
                    it.id != detail.id && it.role == "ASSISTANT" && head.isNotEmpty() && it.text.trimStart().startsWith(head)
                }
                when {
                    grown != null -> {

                        val startsUser = RelayTurns.endsInUserTurn(detail.text, chatMarkers.value)
                        turns += RelayTurns.split(grown.id, grown.text.trimStart().substring(head.length), chatMarkers.value, startsUser = startsUser)
                    }

                    liveFrom != null && message.id != detail.id -> turns += chatTurnsOf(message)
                }
                baseInNode = (grown ?: detail).id
                continue
            }
            turns += chatTurnsOf(message)
        }
        chatTurns.value = turns

        chatBaseId = if (liveFrom == null && found >= 0 && found == nodes.lastIndex) baseInNode
            else nodes.lastOrNull()?.current?.takeIf { it.role == "ASSISTANT" }?.id
        chatGenerating.value = snapshot.generating
        input.generating = snapshot.generating

        host.detailChanged()
    }

    private fun chatTurnsOf(message: ConversationStream.Message): List<ChatTurn> = when (message.role) {
        "USER" -> listOfNotNull(userTurn(message))
        "ASSISTANT" -> if (message.text.isNotBlank()) RelayTurns.split(message.id, message.text, chatMarkers.value) else emptyList()
        else -> emptyList()
    }

    private fun rewrittenFrom(before: List<ConversationStream.Node>, after: List<ConversationStream.Node>): Int? {
        for ((i, old) in before.withIndex()) {
            val was = old.current ?: continue
            val now = after.getOrNull(i)?.current ?: return i
            if (now.id == was.id) continue
            val text = now.text.trimStart()
            if (now.role == was.role && old.messages.any { it.id != now.id && it.text.isNotBlank() && text.startsWith(it.text.trim()) }) continue
            return i
        }
        return null
    }

    private fun userTurn(message: ConversationStream.Message): ChatTurn? {
        val local = message.images.mapNotNull(Conversations::localImagePath)
        val lines = List(message.images.size - local.size) { Strings.get("relay_image_part") } +
            listOfNotNull(message.text.trim().ifEmpty { null })
        if (lines.isEmpty() && local.isEmpty()) return null
        return ChatTurn(message.id, true, lines.joinToString("\n"), images = local.map { StagedImage(it, "") })
    }

    private fun applyRelaySettings(settings: ConversationStream.RelaySettings) {
        chatSettings = settings
        chatMarkers.value = RelayTurns.Markers(settings.startMarkers.ifBlank { RelayTurns.DEFAULT_MARKERS })
        lastSnapshot?.let(::applySnapshot)
    }

    private fun sendChat(text: String) {
        val bar = input
        val voiceIds = host.voice.ready() ?: return
        val images = attachments.toList()
        val stream = chat
        val conversationId = chatConversationId
        if (stream == null || conversationId == null) {
            sendAsNoteQuery(bar, text, images, voiceIds)
            return
        }

        val plainOnly = detailMessage()?.kind == "transcript"
        val existingIds = lastSnapshot?.nodes?.flatMap { node -> node.messages.map { it.id } }?.toSet().orEmpty()
        val cached = chatSettings
        val base = chatBaseId

        val inUserTurn = chatTurns.value.lastOrNull()?.fromUser
            ?: messagesState.value.firstOrNull { it.id == requestedDetailId }
                ?.let { RelayTurns.endsInUserTurn(it.text, chatMarkers.value, it.lead) }
            ?: false
        bar.sending = true
        followSignal.value++
        thread(name = "Relay-chat-send", isDaemon = true) {

            if (!Conversations.webCanAnswer()) {
                handler.post { bar.sending = false }
                return@thread
            }

            val settings = stream.fetchRelaySettings()

            val attached = images.isNotEmpty() || voiceIds.isNotEmpty()
            val prefill = !attached && !plainOnly && (settings ?: cached)?.prefillEnabled == true
            Conversations.prepareChatSend(conversationId, existingIds, prefill)
            val blocks = host.voice.blocks(voiceIds)
            val sent = when {
                blocks == null -> false
                attached -> Conversations.sendWithImages(stream, host.voice.withVoices(text, blocks), images.map { it.imagePath to it.maskPath })
                prefill -> stream.sendQuery(text, base, continueUserTurn = inUserTurn)
                else -> stream.sendText(text)
            }
            handler.post {
                if (chat !== stream) return@post
                settings?.let(::applyRelaySettings)
                bar.sending = false
                if (sent) sentOut(bar, images, voiceIds) else Dialogs.tip(Strings.get("relay_chat_failed"))
            }
        }
    }

    private fun sendAsNoteQuery(bar: ChatInput, text: String, images: List<StagedImage>, voiceIds: List<String>) {
        if (composing && images.isEmpty() && voiceIds.isEmpty() && !Conversations.isEnabled()) {
            sendOnceFromCompose(bar, text)
            return
        }

        val newConversation = composing && newChatTapped

        val awaiting = composing || host.small
        if (awaiting) awaitingReplyAfter = messagesState.value.mapTo(HashSet()) { it.id }
        bar.sending = true
        val sentIn = session

        val done: (Conversations.SubmitResult, String) -> Unit = { result, shown ->
            handler.post {
                if (session != sentIn) return@post
                bar.sending = false
                if (result == Conversations.SubmitResult.SENT) {
                    sentOut(bar, images, voiceIds)
                    if (newConversation) {
                        newChatTapped = false
                        syncChat(keepKeyboard = true)
                    }
                    if (awaiting && awaitingReplyAfter != null) {
                        pendingQuestion = ChatTurn("question-${System.currentTimeMillis()}", true, shown, images = images)
                        waitingHidden = false

                        host.awaitReply()
                    }
                } else if (result != Conversations.SubmitResult.NOT_CONNECTED && result != Conversations.SubmitResult.NO_ENDPOINT) {

                    Dialogs.tip(Strings.get("relay_chat_failed"))
                }
            }
        }
        if (images.isEmpty() && voiceIds.isEmpty()) {
            Conversations.submitQuery(text, newConversation) { done(it, text.trim()) }
        } else thread(name = "Relay-note-send", isDaemon = true) {
            val message = host.voice.blocks(voiceIds)?.let { host.voice.withVoices(text, it) }
            val paths = images.map { it.imagePath to it.maskPath }
            val result = when {
                message == null -> Conversations.SubmitResult.FAILED
                (if (newConversation) Conversations.sendInNewConversation(message, paths) else Conversations.sendWithImages(null, message, paths)) ->
                    Conversations.SubmitResult.SENT
                !Conversations.isConnected() || Conversations.webPageMissing() -> Conversations.SubmitResult.NOT_CONNECTED
                else -> Conversations.SubmitResult.FAILED
            }
            done(result, message.orEmpty())
        }
    }

    private fun sendOnceFromCompose(bar: ChatInput, text: String) {
        if (text.isBlank()) return
        bar.sending = true
        val sentIn = session
        Conversations.sendOnce(text) { result ->
            handler.post {
                Log.i(TAG, "compose sent with AIRelay off: $result")
                if (session == sentIn) bar.sending = false
                val key = when (result) {
                    Conversations.OnceResult.SENT -> if (Conversations.isWebMode()) "relay_lexi_sent_web" else "relay_lexi_sent"
                    Conversations.OnceResult.NO_PAGE -> "relay_lexi_no_page"
                    Conversations.OnceResult.UNPAIRED -> "relay_lexi_unpaired"
                    Conversations.OnceResult.UNREACHABLE -> "relay_lexi_unreachable"
                    Conversations.OnceResult.REFUSED -> "relay_lexi_refused"
                }
                Dialogs.result(Strings.get(key))
                if (result == Conversations.OnceResult.SENT && session == sentIn) {
                    bar.clearText()
                    host.close()
                }
            }
        }
    }

    private fun sentOut(bar: ChatInput, images: List<StagedImage>, voiceIds: List<String> = emptyList()) {
        bar.clearText()
        attachments.removeAll(images.toSet())
        host.voice.remove(voiceIds)
        refreshAttachments()
    }

    private fun takeAwaitedReply(replyId: String) {
        pendingQuestion?.let { question ->
            questions[replyId] = question
            while (questions.size > QUESTIONS_KEPT) questions.remove(questions.keys.first())
        }
        pendingQuestion = null
    }

    private fun stopChat() {
        val stream = chat ?: return
        thread(name = "Relay-chat-stop", isDaemon = true) { stream.stopGeneration() }
    }

    private fun pagesLabel(): String =
        Strings.get(if (HostSession.state.document) "relay_tool_pages_doc" else "relay_tool_pages_note")

    private fun scheduleContent() {
        if (!live || contentQueued) return
        contentQueued = true
        Choreographer.getInstance().postFrameCallback(renderQueued)
    }

    private fun rendersHeld() = dragging || detailView?.touching == true

    private fun releaseRender() {
        if (!renderHeld || rendersHeld()) return
        renderHeld = false
        Log.i(TAG, "render released")
        scheduleContent()
    }

    private fun renderContent() {
        val started = SystemClock.elapsedRealtime()
        try { renderContentNow() } finally {
            val took = SystemClock.elapsedRealtime() - started
            if (took >= SLOW_RENDER_MS) Log.i(TAG, "slow render: ${took}ms (page=$page small=${host.small})")
        }
    }

    private fun renderContentNow() {
        Choreographer.getInstance().removeFrameCallback(renderQueued)
        contentQueued = false
        renderHeld = false
        if (!live) return
        val frame = contentHost
        val detailScreen = page == Page.READ || page == Page.SELECT
        val home = detailScreen && atHome
        if (home) {
            val recent = recentView ?: RecentConversations(
                ctx,
                questionOf = host::question,
                onOpen = host::openHere,
                onInbox = { host.open(RelayPage.INBOX) },
                emptyHint = Strings.get("relay_compose_hint"),
            ).also {
                recentView = it
                frame.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                applyLook()
            }
            recent.visibility = View.VISIBLE

            recent.setNewChat(newChatTapped)
            recent.setMessages(if (newChatTapped) emptyList() else messagesState.value)
        } else {
            recentView?.visibility = View.GONE
        }
        if (detailScreen && !home) {
            val detail = detailView ?: newDetailView(frame)
            detail.visibility = View.VISIBLE
            detail.bind(MessageDetailContent.Model(
                message = detailMessage(),
                selecting = page == Page.SELECT,
                markers = chatMarkers.value,
                chatTurns = chatTurns.value,
                chatGenerating = chatGenerating.value,
                followSignal = followSignal.value,
                emptyTitle = if (composing && newChatTapped) Strings.get("relay_new_chat") else null,
                leadTurn = leadTurn(),
            ))
        } else {
            detailView?.let {
                it.clear()
                it.visibility = View.GONE
            }
        }
        actions.bind(detailMessage() != null)
        host.detailChanged()
    }

    private fun leadTurn(): ChatTurn? = requestedDetailId.let { id ->
        if (id == null) pendingQuestion

        else questions[id] ?: snapshotLead?.takeIf { detailMessage()?.kind != "formula" }
    }

    private fun newDetailView(frame: FrameLayout): MessageDetailContent {
        val detail = MessageDetailContent(ctx, MessageDetailContent.Callbacks(

            onReadLine = { _, _ -> },

            onImageTap = ::showImage,
        ))
        detail.onReleased = ::releaseRender
        detailView = detail
        frame.addView(detail, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        applyLook()
        return detail
    }
}
