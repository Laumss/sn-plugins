package me.laumss.notipal.relay.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import me.laumss.notipal.NativeLocale
import me.laumss.notipal.relay.model.RelayMessage
import me.laumss.notipal.relay.transcript.TranscriptClient
import me.laumss.notipal.ui_common.snux.InkScrollView
import me.laumss.notipal.ui_common.snux.EmptyState
import me.laumss.notipal.ui_common.snux.LineIcon
import me.laumss.notipal.ui_common.snux.SearchBox
import me.laumss.notipal.ui_common.snux.SideNav
import me.laumss.notipal.ui_common.snux.Snux

@SuppressLint("ViewConstructor")
internal class InboxListView(
    context: Context,
    private val callbacks: Callbacks,

    private val settings: View,
) : LinearLayout(context) {

    class Callbacks(

        val onOpen: (String) -> Unit,
        val onStar: (Set<String>, Boolean) -> Unit,
        val onDelete: (Set<String>) -> Unit,

        val onSelectDone: () -> Unit,

        val questionOf: (String) -> String?,

        val onInsertText: (String) -> Unit,
        val onAskTranscript: (TranscriptClient.Transcript) -> Unit,
        val onTranscriptConversation: (TranscriptClient.Transcript) -> Unit,
        val tip: (String) -> Unit,
    )

    private enum class Filter(val id: String) { CHAT("chat"), AUDIO("audio"), STARRED("starred"), SETTINGS("settings") }

    private companion object {

        const val AUDIO_KEY = "audio:"
        const val SEARCH_DELAY_MS = 300L

        const val RENDER_DELAY_MS = 500L
    }

    private fun u(v: Number) = Snux.u(context, v)
    private fun t(key: String) = NativeLocale.t(key)

    private var messages: List<RelayMessage> = emptyList()
    private var filter = Filter.CHAT
    private var editing = false
    private var query = ""
    private val picked = LinkedHashSet<String>()

    private val pickedAudio = LinkedHashSet<String>()

    private var shownTranscripts: List<TranscriptClient.Transcript> = emptyList()

    private var backToStarred = false

    private val checks = HashMap<String, CheckMark>()

    private var stale = false
    private var renderPending = false

    private var navKey = ""

    private val summaries = HashMap<String, Triple<Long, String, String>>()

    private val nav = SideNav(context, ::onNav)
    private val client = TranscriptClient.get(context)

    private val pane = LinearLayout(context).apply { orientation = VERTICAL }
    private val transcripts = TranscriptPane(context, client, TranscriptPane.Callbacks(
        onInsert = callbacks.onInsertText,
        onAsk = callbacks.onAskTranscript,
        onConversation = callbacks.onTranscriptConversation,
        tip = callbacks.tip,
        onChanged = {
            renderNav()
            if (filter == Filter.STARRED && starredTranscripts() != shownTranscripts) renderSoon()
        },
        onPicksChanged = { renderActions() },
        onListDrawn = { rows -> if (audioShown) updateHasRows(rows) },
        onDetailClosed = {
            if (backToStarred) {
                backToStarred = false
                onNav(Filter.STARRED.id)
            }
        },
    )).apply { visibility = GONE }
    val search: SearchBox = SearchBox(context, onClear = { setQuery("") }, onGo = { hideKeyboard(); setQuery(searchText()) },
        goLabel = t("relay_search_go"), clearLabel = t("picker_clear"))

    private val scroll = InkScrollView(context)
    private val empty = EmptyState(context).apply { visibility = GONE }

    var onPicks: ((Int, Boolean) -> Unit)? = null

    val audioShown: Boolean get() = filter == Filter.AUDIO

    var hasRows = false
        private set
    var onRowsChanged: (() -> Unit)? = null
    private val runSearch = Runnable { setQuery(searchText()) }
    private val renderLater = Runnable {
        renderPending = false
        render(keepScroll = true)
    }

    init {
        orientation = HORIZONTAL
        setBackgroundColor(Color.WHITE)
        addView(nav, LayoutParams(u(Snux.SIDEBAR), LayoutParams.MATCH_PARENT))

        addView(View(context).apply { setBackgroundColor(Color.BLACK) }, LayoutParams(Snux.px(context, 2).coerceAtLeast(1), LayoutParams.MATCH_PARENT))
        search.input.hint = t("relay_search_hint")
        search.input.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            hideKeyboard()
            setQuery(searchText())
            true
        }
        search.input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                removeCallbacks(runSearch)
                postDelayed(runSearch, SEARCH_DELAY_MS)
            }
        })
        pane.addView(search, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = u(Snux.PANE_INSET); rightMargin = u(Snux.PANE_INSET); topMargin = u(24)
        })
        scroll.content.setPadding(0, 0, u(Snux.PANE_INSET), u(24))

        pane.addView(FrameLayout(context).apply {

            setPadding(u(Snux.PANE_INSET), 0, 0, 0)
            addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(empty, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                .apply { rightMargin = u(Snux.PANE_INSET) })
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(pane, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(transcripts, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        settings.visibility = GONE
        addView(settings, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        render(keepScroll = false)
    }

    fun setMessages(list: List<RelayMessage>, now: Boolean = false) {
        messages = list
        picked.retainAll(groups().mapTo(HashSet()) { it.key })
        summaries.keys.retainAll(list.mapTo(HashSet()) { it.id })
        if (now) {
            render(keepScroll = false)
            return
        }
        renderSoon()
    }

    private fun renderSoon() {
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
        if (audioShown) transcripts.refreshIfStale()
    }

    fun back(): Boolean = audioShown && transcripts.back()

    fun showTranscript(id: String?) {
        backToStarred = false
        onNav(Filter.AUDIO.id)
        transcripts.open(id)
    }

    fun showSettings() {
        backToStarred = false
        onNav(Filter.SETTINGS.id)
    }

    fun setEditing(value: Boolean) {
        if (editing == value) return
        editing = value
        picked.clear()
        pickedAudio.clear()
        transcripts.setEditing(value)
        if (value) hideKeyboard()
        render(keepScroll = true)
    }

    fun hideKeyboard(): Boolean {
        val input = search.input
        if (!input.isFocused) return false
        input.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        (parent as? View)?.requestFocus()
        return true
    }

    private fun searchText(): String = search.input.text?.toString()?.trim().orEmpty()

    private fun setQuery(value: String) {
        removeCallbacks(runSearch)
        if (value.isEmpty() && searchText().isNotEmpty()) search.input.setText("")
        if (value == query) return
        query = value

        picked.retainAll(shown(groups()).mapTo(HashSet()) { it.key })
        render(keepScroll = false)
    }

    private fun onNav(id: String) {
        val next = Filter.values().firstOrNull { it.id == id } ?: return
        if (next == filter) return
        backToStarred = false
        filter = next
        picked.clear()
        pickedAudio.clear()
        transcripts.clearPicks()
        render(keepScroll = false)
    }

    private fun groups(): List<InboxGroup> = inboxGroups(messages)

    private fun matches(group: InboxGroup): Boolean {
        if (query.isEmpty()) return true
        return group.replies.any { reply ->
            reply.text.contains(query, ignoreCase = true) ||
                callbacks.questionOf(reply.id)?.contains(query, ignoreCase = true) == true
        }
    }

    private fun shown(all: List<InboxGroup>): List<InboxGroup> = when (filter) {
        Filter.AUDIO, Filter.SETTINGS -> emptyList()
        Filter.STARRED -> all.filter { it.starred }
        else -> all
    }.filter(::matches)

    private fun starredTranscripts(): List<TranscriptClient.Transcript> =
        if (filter != Filter.STARRED) emptyList()
        else client.transcripts().filter { item ->
            client.isStarred(item.id) && (query.isEmpty() || item.title.contains(query, ignoreCase = true) || item.text.contains(query, ignoreCase = true))
        }

    private fun render(keepScroll: Boolean) {
        stale = false
        if (renderPending) {
            renderPending = false
            removeCallbacks(renderLater)
        }
        val all = groups()
        renderNav(all)
        val audio = audioShown
        val settingsShown = filter == Filter.SETTINGS
        pane.visibility = if (audio || settingsShown) GONE else VISIBLE
        transcripts.visibility = if (audio) VISIBLE else GONE
        settings.visibility = if (settingsShown) VISIBLE else GONE
        if (audio || settingsShown) hideKeyboard()
        if (audio) transcripts.refreshIfStale()
        val offset = scroll.scrollOffset
        val content = scroll.content
        content.removeAllViews()
        checks.clear()
        val rows = shown(all)
        val audioRows = starredTranscripts()
        shownTranscripts = audioRows
        pickedAudio.retainAll(audioRows.filterNot { it.live }.mapTo(HashSet()) { it.id })
        val entries = ArrayList<Pair<Long, (Boolean) -> View>>()
        rows.forEach { group -> entries.add(group.updated to { last: Boolean -> row(group, last) }) }
        audioRows.forEach { item -> entries.add(item.createdAt to { last: Boolean -> transcriptRow(item, last) }) }

        updateHasRows(if (audio) transcripts.hasRows else !settingsShown && entries.isNotEmpty())
        empty.visibility = if (entries.isEmpty()) VISIBLE else GONE
        if (entries.isEmpty()) {
            showEmpty()
        } else {
            entries.groupBy { inboxDay(it.first) }.toSortedMap().forEach { (day, inDay) ->
                content.addView(Snux.sectionLabel(context, inboxDayLabel(day), topU = 22))
                content.addView(Snux.rule(context))
                inDay.sortedByDescending { it.first }.forEachIndexed { i, entry -> content.addView(entry.second(i == inDay.lastIndex)) }
            }
        }
        renderActions()
        scroll.scrollToOffset(if (keepScroll) offset else 0)
    }

    private fun updateHasRows(value: Boolean) {
        if (hasRows == value) return
        hasRows = value
        onRowsChanged?.invoke()
    }

    private fun renderNav(all: List<InboxGroup> = groups()) {
        val recordings = client.transcripts().size.toString().let { if (client.state.active) "● $it" else it }
        val starred = all.count { it.starred } + client.transcripts().count { client.isStarred(it.id) }
        val key = "${all.size}|$starred|$recordings|${filter.id}"
        if (key == navKey) return
        navKey = key
        nav.setEntries(
            listOf(
                SideNav.Entry.Item(Filter.CHAT.id, LineIcon.Glyph.CHAT, t("relay_nav_chat"), all.size.toString()),
                SideNav.Entry.Dots,
                SideNav.Entry.Item(Filter.AUDIO.id, LineIcon.Glyph.MIC, t("relay_nav_audio"), recordings),
                SideNav.Entry.Rule,
                SideNav.Entry.Item(Filter.STARRED.id, LineIcon.Glyph.STAR, t("relay_tab_starred"), starred.toString()),
                SideNav.Entry.Rule,
            ),

            listOf(
                SideNav.Entry.Rule,
                SideNav.Entry.Item(Filter.SETTINGS.id, LineIcon.Glyph.GEAR, t("relay_nav_settings")),
            ),
        )
        nav.setActive(filter.id)
    }

    private fun showEmpty() {
        when {
            query.isNotEmpty() -> empty.set(LineIcon.Glyph.SEARCH, NativeLocale.t("relay_search_none", query), t("relay_search_none_desc"))
            filter == Filter.STARRED -> empty.set(LineIcon.Glyph.STAR, t("relay_starred_empty"), t("relay_starred_hint"))
            else -> empty.set(LineIcon.Glyph.CHAT, t("relay_empty"), t("relay_empty_desc"))
        }
    }

    private fun row(group: InboxGroup, last: Boolean): View {
        val first = group.replies.first()
        val title = rowTitle(callbacks.questionOf(first.id), summary(first).second)
        val meta = buildList {
            add(NativeLocale.t("relay_row_replies", group.replies.size))
            if (group.replies.any { it.kind == "formula" }) add(t("relay_kind_formula"))
            if (group.replies.any { it.kind == "transcript" }) add(t("relay_kind_transcript"))
            if (group.starred) add(t("relay_tab_starred"))
            if (group.replies.any { it.inserted }) add(t("relay_sent_to_note"))
        }.joinToString(" · ")
        val latest = group.replies.maxByOrNull { it.updatedAt } ?: first
        val status = Snux.oneLine(Snux.text(context,
            if (group.generating) t("relay_chat_generating") else summary(latest).third,
            Snux.F_META, if (group.generating) Snux.INK else Snux.SOFT, bold = group.generating))
        val lead: View = if (editing) CheckMark(context).apply { checked = group.key in picked }.also { checks[group.key] = it }
            else LineIcon(context, if (first.kind == "transcript") LineIcon.Glyph.MIC else LineIcon.Glyph.CHAT, Snux.ICON)
        lead.layoutParams = LayoutParams(u(Snux.ICON), u(Snux.ICON))
        return Snux.listRow(
            context, title,
            meta = meta,
            right = formatInboxTime(group.updated),
            solid = last,
            titleBold = group.unseen,
            lead = lead,
            extra = status,
            onClick = if (editing) ({ toggle(group) }) else ({ callbacks.onOpen(first.id) }),
        ).view
    }

    private fun transcriptRow(item: TranscriptClient.Transcript, last: Boolean): View =
        transcripts.row(item, last, check = if (editing && !item.live) audioCheck(item.id) else null) {
            when {
                !editing -> {
                    showTranscript(item.id)
                    backToStarred = true
                }
                !item.live -> {
                    if (!pickedAudio.remove(item.id)) pickedAudio.add(item.id)
                    checks[AUDIO_KEY + item.id]?.checked = item.id in pickedAudio
                    renderActions()
                }
            }
        }

    private fun audioCheck(id: String): CheckMark =
        CheckMark(context).apply { checked = id in pickedAudio }.also { checks[AUDIO_KEY + id] = it }

    private fun toggle(group: InboxGroup) {
        if (!picked.remove(group.key)) picked.add(group.key)
        checks[group.key]?.checked = group.key in picked
        renderActions()
    }

    private fun pickedIds(): Set<String> =
        groups().filter { it.key in picked }.flatMapTo(LinkedHashSet()) { it.ids }

    private fun pickedCount(): Int = if (audioShown) transcripts.pickedCount else picked.size + pickedAudio.size

    fun starPicked() {
        if (audioShown) {
            if (transcripts.starPicked()) callbacks.onSelectDone()
            return
        }
        val star = filter != Filter.STARRED
        val audio = pickedAudio.toList()
        val ids = pickedIds()
        if (audio.isNotEmpty()) client.setStarred(audio, star)
        if (ids.isNotEmpty()) callbacks.onStar(ids, star) else if (audio.isNotEmpty()) callbacks.onSelectDone()
    }

    fun deletePicked() {
        if (pickedCount() == 0) return
        if (audioShown) {
            transcripts.deletePicked()
            callbacks.onSelectDone()
            return
        }
        val ids = pickedIds()
        val audio = pickedAudio.toList()
        pickedAudio.clear()
        transcripts.delete(audio)
        if (ids.isNotEmpty()) callbacks.onDelete(ids) else if (audio.isNotEmpty()) callbacks.onSelectDone()
    }

    fun renderActions() {
        if (!editing) return
        onPicks?.invoke(pickedCount(), if (audioShown) transcripts.pickedAllStarred else filter == Filter.STARRED)
    }

    private fun summary(reply: RelayMessage): Triple<Long, String, String> =
        summaries[reply.id]?.takeIf { it.first == reply.updatedAt } ?: summaryLines(reply.text).let { lines ->
            Triple(reply.updatedAt, lines.firstOrNull() ?: reply.text.trim(), excerptOf(lines))
        }.also { summaries[reply.id] = it }

    class CheckMark(context: Context) : View(context) {
        var checked = false
            set(value) { field = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val tick = Path()

        override fun onDraw(canvas: Canvas) {
            val size = minOf(width, height).toFloat()
            val k = size / 30f
            val stroke = 2f * k
            paint.style = Paint.Style.FILL
            paint.color = if (checked) Color.BLACK else Color.WHITE
            canvas.drawRect(k, k, size - k, size - k, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = Color.BLACK
            canvas.drawRect(k, k, size - k, size - k, paint)
            if (!checked) return
            tick.reset()
            tick.moveTo(7f * k, 15.5f * k)
            tick.lineTo(12.5f * k, 21f * k)
            tick.lineTo(23f * k, 9f * k)
            paint.color = Color.WHITE
            paint.strokeWidth = 2.6f * k
            canvas.drawPath(tick, paint)
        }
    }
}
