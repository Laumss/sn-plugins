package me.laumss.notipal.relay.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import me.laumss.notipal.NativeLocale
import me.laumss.notipal.relay.core.MarkdownBlock
import me.laumss.notipal.relay.core.MarkdownDocumentParser
import me.laumss.notipal.relay.transcript.TranscriptClient
import me.laumss.notipal.relay.transcript.TranscriptClient.Phase
import me.laumss.notipal.relay.transcript.TranscriptClient.Status
import me.laumss.notipal.relay.transcript.TranscriptClient.Transcript
import me.laumss.notipal.ui_common.snux.EmptyState
import me.laumss.notipal.ui_common.snux.InkScrollView
import me.laumss.notipal.ui_common.snux.LineIcon
import me.laumss.notipal.ui_common.snux.Snux
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

@SuppressLint("ViewConstructor")
internal class TranscriptPane(
    context: Context,
    private val client: TranscriptClient,
    private val callbacks: Callbacks,
) : LinearLayout(context), TranscriptClient.Listener {

    class Callbacks(
        val onInsert: (String) -> Unit,

        val onAsk: (Transcript) -> Unit,

        val onConversation: (Transcript) -> Unit,
        val tip: (String) -> Unit,

        val onChanged: () -> Unit,

        val onPicksChanged: () -> Unit,

        val onListDrawn: (Boolean) -> Unit,

        val onDetailClosed: () -> Unit,
    )

    private companion object {
        const val RENDER_DELAY_MS = 500L
        const val DELETE_ARM_MS = 3_000L
    }

    private fun u(v: Number) = Snux.u(context, v)
    private fun t(key: String) = NativeLocale.t(key)

    private var openId: String? = null
    private var choosingPrompt = false
    private var deleteArmedAt = 0L
    private var editing = false
    private val picked = LinkedHashSet<String>()

    private val checks = HashMap<String, InboxListView.CheckMark>()

    private var busy = false
    private var fetching: String? = null

    private var waitingFor: String? = null
    private var stale = false
    private var renderPending = false
    private val renderLater = Runnable {
        renderPending = false
        render(keepScroll = true)
    }

    private val status = Snux.oneLine(Snux.text(context, "", Snux.F_META, Snux.SOFT))
    private val button = TextView(context).apply {
        setTextColor(Color.BLACK)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, u(Snux.F_META).toFloat())
        gravity = Gravity.CENTER
        minWidth = u(120)
        setPadding(u(20), u(8), u(20), u(8))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(Snux.px(context, 2).coerceAtLeast(1), Color.BLACK)
            cornerRadius = u(6).toFloat()
        }
        setOnClickListener { onButton() }
    }

    private val scroll = InkScrollView(context)
    private val empty = EmptyState(context).apply { visibility = GONE }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = u(Snux.ROW_MIN)
            addView(LineIcon(context, LineIcon.Glyph.MIC, Snux.ICON), LayoutParams(u(Snux.ICON), u(Snux.ICON)).apply { rightMargin = u(14) })
            addView(status, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = u(16) })
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = u(Snux.PANE_INSET); rightMargin = u(Snux.PANE_INSET); topMargin = u(24)
        })
        addView(Snux.rule(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, Snux.px(context, 2).coerceAtLeast(1)).apply {
                leftMargin = u(Snux.PANE_INSET); rightMargin = u(Snux.PANE_INSET); topMargin = u(10)
            }
        })
        scroll.content.setPadding(0, 0, u(Snux.PANE_INSET), u(24))
        addView(FrameLayout(context).apply {

            setPadding(u(Snux.PANE_INSET), 0, 0, 0)
            addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(empty, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                .apply { rightMargin = u(Snux.PANE_INSET) })
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        client.addListener(this)

        callbacks.onChanged()
        render(keepScroll = false)
    }

    override fun onDetachedFromWindow() {
        client.removeListener(this)
        removeCallbacks(renderLater)
        renderPending = false
        super.onDetachedFromWindow()
    }

    override fun onTranscriptsChanged() {
        callbacks.onChanged()
        if (visibility != VISIBLE) {
            stale = true
            return
        }
        if (renderPending) return
        renderPending = true
        postDelayed(renderLater, RENDER_DELAY_MS)
    }

    fun refreshIfStale() {
        if (stale) render(keepScroll = true)
    }

    fun open(id: String?) {
        if (openId == id) return
        openId = id
        choosingPrompt = false
        deleteArmedAt = 0L
        render(keepScroll = false)
        if (id == null) callbacks.onDetailClosed()
    }

    fun back(): Boolean {
        if (openId == null) return false
        open(null)
        return true
    }

    val hasRows: Boolean get() = openId == null && client.transcripts().isNotEmpty()
    val pickedCount: Int get() = picked.size
    val pickedAllStarred: Boolean get() = picked.isNotEmpty() && picked.all(client::isStarred)

    fun setEditing(value: Boolean) {
        if (editing == value) return
        editing = value
        picked.clear()
        render(keepScroll = true)
    }

    fun clearPicks() {
        if (picked.isEmpty()) return
        picked.clear()
        render(keepScroll = true)
    }

    private fun toggle(id: String) {
        if (!picked.remove(id)) picked.add(id)
        checks[id]?.checked = id in picked
        callbacks.onPicksChanged()
    }

    fun starPicked(): Boolean {
        if (picked.isEmpty()) return false
        client.setStarred(picked.toList(), !pickedAllStarred)
        return true
    }

    fun deletePicked() {
        delete(picked.toList())
        picked.clear()
    }

    fun delete(ids: List<String>) {
        if (ids.isEmpty()) return
        io({ ids.mapNotNull(client::delete).firstOrNull() }) { error -> error?.let(callbacks.tip) }
    }

    private fun onButton() {
        if (busy) return
        val state = client.state
        when (state.phase) {
            Phase.IDLE -> {
                busy = true
                render(keepScroll = true)
                io({ client.startRecording() }) { result ->
                    busy = false
                    result.onSuccess { id -> openId = id; waitingFor = id; choosingPrompt = false }
                        .onFailure { callbacks.tip(it.message.orEmpty()) }
                    render(keepScroll = false)
                }
            }
            Phase.RECORDING -> stop()
            else -> Unit
        }
    }

    private fun stop() {
        busy = true
        render(keepScroll = true)
        io({ client.stopRecording() }) { error ->
            busy = false
            error?.let(callbacks.tip)
            render(keepScroll = true)
        }
    }

    private fun <T> io(block: () -> T, done: (T) -> Unit) {
        thread(name = "Relay-transcript-action", isDaemon = true) {
            val result = block()
            post { done(result) }
        }
    }

    private fun render(keepScroll: Boolean) {
        stale = false
        if (renderPending) {
            renderPending = false
            removeCallbacks(renderLater)
        }
        renderHeader()
        val offset = scroll.scrollOffset
        scroll.content.removeAllViews()
        checks.clear()
        val detail = openId?.let(client::transcript)

        if (detail == null && openId != null && openId != waitingFor) openId = null
        if (detail != null && openId == waitingFor) waitingFor = null
        if (detail != null) {
            empty.visibility = GONE
            renderDetail(detail)
        } else {
            val list = client.transcripts()
            if (picked.retainAll(list.filterNot { it.live }.mapTo(HashSet()) { it.id })) callbacks.onPicksChanged()
            empty.visibility = if (list.isEmpty()) VISIBLE else GONE
            if (list.isEmpty()) empty.set(LineIcon.Glyph.MIC, t("relay_audio_empty"), t("relay_audio_empty_desc"))
            list.groupBy { inboxDay(it.createdAt) }.toSortedMap().forEach { (day, inDay) ->
                scroll.content.addView(Snux.sectionLabel(context, inboxDayLabel(day), topU = 22))
                scroll.content.addView(Snux.rule(context))
                inDay.forEachIndexed { i, item ->
                    val check = if (editing && !item.live) InboxListView.CheckMark(context).apply { checked = item.id in picked }.also { checks[item.id] = it } else null
                    scroll.content.addView(row(item, last = i == inDay.lastIndex, check = check) {
                        when {
                            !editing -> open(item.id)
                            !item.live -> toggle(item.id)
                        }
                    })
                }
            }
        }
        scroll.scrollToOffset(if (keepScroll) offset else 0)
        callbacks.onListDrawn(hasRows)
    }

    private fun renderHeader() {
        val state = client.state
        val link = client.link
        val active = state.activeId?.let(client::transcript)
        val (line, label) = when {
            link == TranscriptClient.Link.WEB_MODE -> t("relay_audio_web") to null
            link == TranscriptClient.Link.NO_PHONE -> t("relay_audio_no_phone") to null
            link == TranscriptClient.Link.UNSUPPORTED -> t("relay_audio_unsupported") to null
            link == TranscriptClient.Link.CONNECTING -> t("relay_audio_connecting") to null
            link == TranscriptClient.Link.OFFLINE -> t("relay_audio_offline") to null
            state.phase == Phase.STARTING -> t("relay_audio_starting") to ""
            state.phase == Phase.RECORDING -> NativeLocale.t("relay_audio_recording",
                formatDuration(active?.durationMs ?: 0L), active?.length ?: 0) to t("relay_audio_stop")
            state.phase == Phase.PROCESSING -> t("relay_audio_processing") to ""
            state.error != null -> NativeLocale.t("relay_audio_last_error", state.error) to t("relay_audio_start")
            else -> t("relay_audio_idle") to t("relay_audio_start")
        }
        status.text = line
        status.setTypeface(null, if (state.phase == Phase.RECORDING) Typeface.BOLD else Typeface.NORMAL)
        button.visibility = if (label == null) GONE else VISIBLE
        val enabled = !busy && !label.isNullOrEmpty()
        button.text = if (busy || label.isNullOrEmpty()) "…" else label
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.4f
    }

    private fun meta(item: Transcript): String = buildList {
        if (item.durationMs > 0) add(formatDuration(item.durationMs))
        add(NativeLocale.t("relay_audio_chars", item.length))
        if (item.analysisCount > 0) add(NativeLocale.t("relay_audio_analyses", item.analysisCount))
        if (item.source == "notipal") add(t("relay_audio_from_notipal"))
        if (client.isStarred(item.id)) add(t("relay_tab_starred"))
    }.joinToString(" · ")

    fun row(item: Transcript, last: Boolean, check: View?, onTap: () -> Unit): View {
        val (line, strong) = when {
            item.status == Status.RECORDING -> t("relay_audio_live") to true
            item.status == Status.PROCESSING -> t("relay_audio_live_processing") to true
            item.analyzing -> t("relay_audio_analyzing") to true
            item.status == Status.ERROR && item.length == 0 -> NativeLocale.t("relay_audio_error", item.error.orEmpty()) to false
            else -> item.text.replace('\n', ' ').trim() to false
        }
        val extra = Snux.oneLine(Snux.text(context, line, Snux.F_META, if (strong) Snux.INK else Snux.SOFT, bold = strong))
        val lead: View = check ?: LineIcon(context, LineIcon.Glyph.MIC, Snux.ICON)
        lead.layoutParams = LayoutParams(u(Snux.ICON), u(Snux.ICON))
        return Snux.listRow(
            context, item.title,
            meta = meta(item),
            right = formatInboxTime(item.createdAt),
            solid = last,
            lead = lead,
            extra = extra.takeIf { line.isNotEmpty() },
            onClick = onTap,
        ).view
    }

    private fun renderDetail(item: Transcript) {
        val content = scroll.content
        content.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, u(18), 0, u(10))
            addView(LineIcon(context, LineIcon.Glyph.BACK, Snux.CHEVRON), LayoutParams(u(Snux.CHEVRON), u(Snux.CHEVRON)).apply { rightMargin = u(8) })
            addView(Snux.text(context, t("relay_audio_back"), Snux.F_META, Snux.MUTED))
            setOnClickListener { open(null) }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        content.addView(Snux.text(context, item.title, 26, bold = true))
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.createdAt))
        content.addView(Snux.text(context, "$time · ${meta(item)}", Snux.F_META, Snux.SUBTLE).apply { setPadding(0, u(6), 0, u(14)) })
        content.addView(actions(item))
        if (choosingPrompt) content.addView(promptChoices(item))
        content.addView(Snux.rule(context).apply {
            (layoutParams as LayoutParams).topMargin = u(14)
        })

        if (!item.full && fetching != item.id) {
            fetching = item.id
            io({ client.fetch(item.id) }) { fetching = null }
        }
        val body = when {

            !item.full -> if (client.available) t("relay_audio_loading") else item.text.trim() + "…"
            item.text.isNotBlank() -> item.text
            item.live -> t("relay_audio_waiting_text")
            else -> t("relay_audio_no_text")
        }
        content.addView(bodyText(body, if (item.full && item.text.isNotBlank()) Snux.INK else Snux.MUTED).apply {
            setPadding(0, u(18), 0, u(10))
        })
        item.error?.let { content.addView(Snux.text(context, NativeLocale.t("relay_audio_error", it), Snux.F_META, Snux.INK, bold = true)) }

        for (analysis in item.analyses.sortedByDescending { it.createdAt }) {
            val heading = listOf(analysis.promptName, analysis.model).filter { it.isNotBlank() }.joinToString(" · ")
            content.addView(Snux.sectionLabel(context, heading, topU = 30))
            content.addView(Snux.rule(context))
            val text = when {
                analysis.running -> t("relay_audio_analyzing")
                analysis.error != null -> NativeLocale.t("relay_audio_analysis_failed", analysis.error)
                else -> plainMarkdown(analysis.text)
            }
            content.addView(bodyText(text, if (analysis.running || analysis.error != null) Snux.MUTED else Snux.INK).apply {
                setPadding(0, u(14), 0, u(8))
            })
            if (!analysis.running) content.addView(linkRow(buildList {
                if (analysis.error == null && analysis.text.isNotBlank()) add(t("relay_insert_note") to { insert(plainMarkdown(analysis.text)) })
                add(t("relay_delete") to {
                    io({ client.deleteAnalysis(item.id, analysis.id) }) { error -> error?.let(callbacks.tip) }
                })
            }))
        }
    }

    private fun actions(item: Transcript): View {
        val state = client.state
        val recordingThis = state.activeId == item.id && state.phase == Phase.RECORDING
        val armed = System.currentTimeMillis() - deleteArmedAt < DELETE_ARM_MS
        return linkRow(buildList {
            if (recordingThis) add(t("relay_audio_stop_recording") to { if (!busy) stop() })
            if (item.usable && item.full) {
                add(t("relay_audio_analyze") to {
                    choosingPrompt = !choosingPrompt
                    render(keepScroll = true)
                })
                add(t("relay_audio_ask") to { callbacks.onAsk(item) })
                add(t("relay_insert_note") to { insert(item.text) })
                add(t("relay_audio_conversation") to { callbacks.onConversation(item) })
            }
            val starred = client.isStarred(item.id)
            add(t(if (starred) "relay_unstar" else "relay_star") to { client.setStarred(listOf(item.id), !starred) })
            if (!item.live) add(t(if (armed) "picker_delete_again" else "relay_delete") to { deleteTapped(item) })
        })
    }

    private fun promptChoices(item: Transcript): View {
        val prompts = client.prompts()
        val row = linkRow(prompts.map { prompt ->
            prompt.name to {
                choosingPrompt = false
                render(keepScroll = true)
                io({ client.analyze(item.id, prompt.id) }) { error -> error?.let(callbacks.tip) }
            }
        } + (t("cancel") to { choosingPrompt = false; render(keepScroll = true) }))
        row.addView(Snux.text(context, t(if (prompts.isEmpty()) "relay_audio_no_prompts" else "relay_audio_pick_prompt"),
            Snux.F_META, Snux.MUTED), 0)
        return row
    }

    private fun deleteTapped(item: Transcript) {
        val now = System.currentTimeMillis()
        if (now - deleteArmedAt >= DELETE_ARM_MS) {
            deleteArmedAt = now
            render(keepScroll = true)
            postDelayed({ if (deleteArmedAt == now) render(keepScroll = true) }, DELETE_ARM_MS)
            return
        }
        deleteArmedAt = 0L
        io({ client.delete(item.id) }) { error ->
            if (error != null) callbacks.tip(error) else if (openId == item.id) open(null)
        }
    }

    private fun insert(text: String) {
        if (text.isNotBlank()) callbacks.onInsert(text)
    }

    private fun linkRow(items: List<Pair<String, () -> Unit>>): ViewGroup = WrapRow(context, u(28), u(10)).apply {
        items.forEach { (label, action) ->
            addView(Snux.link(context, label, Snux.F_META) { action() }.apply { setPadding(0, u(8), 0, u(8)) })
        }
    }

    private fun bodyText(text: String, color: Int): TextView =
        Snux.text(context, text, Snux.F_BODY, color).apply { setLineSpacing(0f, 1.3f) }

    private fun plainMarkdown(text: String): String =
        MarkdownDocumentParser.parse(text).blocks.mapNotNull { block ->
            when (block) {
                is MarkdownBlock.Heading -> plainInline(block.text)
                is MarkdownBlock.Paragraph -> plainInline(block.text)
                is MarkdownBlock.Quote -> plainInline(block.text)
                is MarkdownBlock.ListItem -> "${block.marker} ${plainInline(block.text)}"
                is MarkdownBlock.CodeFence -> block.code.trimEnd()
                is MarkdownBlock.Math -> block.tex.trim()
                is MarkdownBlock.Table -> (listOf(block.headers) + block.rows).joinToString("\n") { cells ->
                    cells.joinToString(" · ") { plainInline(it) }
                }
                is MarkdownBlock.Rule -> null
            }?.takeIf { it.isNotBlank() }
        }.joinToString("\n").ifBlank { text.trim() }

    private class WrapRow(context: Context, private val gap: Int, private val lineGap: Int) : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            var x = 0
            var y = 0
            var lineHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
                if (x > 0 && x + child.measuredWidth > width) {
                    x = 0
                    y += lineHeight + lineGap
                    lineHeight = 0
                }
                x += child.measuredWidth + gap
                lineHeight = maxOf(lineHeight, child.measuredHeight)
            }
            setMeasuredDimension(width, y + lineHeight)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val width = r - l
            var x = 0
            var y = 0
            var lineHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue
                if (x > 0 && x + child.measuredWidth > width) {
                    x = 0
                    y += lineHeight + lineGap
                    lineHeight = 0
                }
                child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
                x += child.measuredWidth + gap
                lineHeight = maxOf(lineHeight, child.measuredHeight)
            }
        }
    }
}
